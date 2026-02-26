/*
 * Copyright (c) 2026 MakiBytes.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package de.makibytes.chaincheck.monitor;

import java.io.IOException;
import java.net.URI;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.makibytes.chaincheck.chain.shared.Confidence;
import de.makibytes.chaincheck.config.ChainCheckProperties;
import de.makibytes.chaincheck.model.AnomalyEvent;
import de.makibytes.chaincheck.model.AnomalyType;
import de.makibytes.chaincheck.model.MetricSample;
import de.makibytes.chaincheck.model.MetricSource;
import de.makibytes.chaincheck.monitor.NodeRegistry.NodeDefinition;
import de.makibytes.chaincheck.store.InMemoryMetricsStore;

public class WsMonitorService {

    private static final Logger logger = LoggerFactory.getLogger(WsMonitorService.class);

    private final RpcMonitorService monitor;
    private final NodeRegistry nodeRegistry;
    private final InMemoryMetricsStore store;
    private final AnomalyDetector detector;
    private final ChainCheckProperties properties;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Map<String, RpcMonitorService.NodeState> nodeStates;
    private final HttpMonitorService httpMonitorService;
    private final ExecutorService recoveryExecutor = Executors.newVirtualThreadPerTaskExecutor();
    // Bounded memory tracking:
    // - recoveryAttemptByNodeAndBlock: cleaned up entries older than RECOVERY_ATTEMPT_DEDUP_WINDOW (10s)
    // - wsHashesByNodeAndNumber: cleaned up entries before blockNumber - INVALID_TRACKING_LOOKBACK_BLOCKS (64)
    // - chainTrackers: per-node ChainTracker with internal limits (MAX_KNOWN_BLOCKS=1024, MAX_CANONICAL_LENGTH=256)
    private final Map<String, Instant> recoveryAttemptByNodeAndBlock = new ConcurrentHashMap<>();
    private final Map<String, Map<Long, Set<String>>> wsHashesByNodeAndNumber = new ConcurrentHashMap<>();
    private final Map<String, ChainTracker> chainTrackers = new ConcurrentHashMap<>();
    private static final Duration RECOVERY_ATTEMPT_DEDUP_WINDOW = Duration.ofSeconds(10);
    private static final long INVALID_TRACKING_LOOKBACK_BLOCKS = 64;
    private static final int RECOVERY_MAX_RETRIES = 3;

    public WsMonitorService(RpcMonitorService monitor,
                            NodeRegistry nodeRegistry,
                            InMemoryMetricsStore store,
                            AnomalyDetector detector,
                            ChainCheckProperties properties,
                            Map<String, RpcMonitorService.NodeState> nodeStates,
                            HttpMonitorService httpMonitorService) {
        this.monitor = monitor;
        this.nodeRegistry = nodeRegistry;
        this.store = store;
        this.detector = detector;
        this.properties = properties;
        this.nodeStates = nodeStates;
        this.httpMonitorService = httpMonitorService;
    }

    public void ensureWebSocket() {
        if (nodeRegistry == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Instant nowInstant = Instant.now();
        for (NodeDefinition node : nodeRegistry.getNodes()) {
            if (node.ws() == null || node.ws().isBlank()) {
                continue;
            }
            RpcMonitorService.NodeState state = nodeStates.computeIfAbsent(node.key(), key -> new RpcMonitorService.NodeState());
            WebSocket existing = state.webSocketRef.get();
            if (existing != null) {
                checkWsHealth(node, state, existing);
                continue;
            }
            if (state.wsNextConnectAttemptAt != null && nowInstant.isBefore(state.wsNextConnectAttemptAt)) {
                continue;
            }
            if (now - state.lastWsAttemptEpochMs < node.pollIntervalMs()) {
                continue;
            }
            state.lastWsAttemptEpochMs = now;
            httpMonitorService.getHttpClient(properties.getDefaults().getConnectTimeoutMs()).newWebSocketBuilder()
                    .buildAsync(URI.create(node.ws()), new WsListener(node, state))
                    .thenAccept(state.webSocketRef::set)
                    .exceptionally(ex -> {
                        String msg = ex.getMessage();
                        if (ex.getCause() instanceof WebSocketHandshakeException handshakeEx) {
                            try {
                                int status = handshakeEx.getResponse().statusCode();
                                msg = "Handshake failed (HTTP " + status + "): " + ex.getMessage();
                            } catch (Exception ignored) {
                                // cannot get response
                            }
                        }
                        logger.error("WebSocket connection failure ({} / ws): {}", node.name(), msg);
                        WsConnectionTracker tracker = nodeRegistry.getWsTracker(node.key());
                        if (tracker != null) {
                            tracker.onConnectFailure(ex);
                        }
                        monitor.recordFailure(node, MetricSource.WS, msg);
                        state.webSocketRef.set(null);
                        return null;
                    });
        }
    }

    void checkWsHealth(NodeDefinition node, RpcMonitorService.NodeState state, WebSocket webSocket) {
        Instant now = Instant.now();
        Instant lastActivity = state.lastWsMessageReceivedAt != null
                ? state.lastWsMessageReceivedAt
                : state.lastWsConnectedAt;
        if (lastActivity == null) {
            return;
        }

        long idleSeconds = Duration.between(lastActivity, now).toSeconds();
        if (state.lastWsPingSentAt != null
                && (state.lastWsPongReceivedAt == null || state.lastWsPongReceivedAt.isBefore(state.lastWsPingSentAt))) {
            long pingAge = Duration.between(state.lastWsPingSentAt, now).toSeconds();
            if (pingAge > RpcMonitorService.WS_PING_INTERVAL_SECONDS) {
                monitor.recordFailure(node, MetricSource.WS, "WebSocket ping timeout after " + pingAge + "s");
                monitor.closeWebSocket(node, state, "ping timeout");
                return;
            }
        }

        if (idleSeconds >= RpcMonitorService.WS_FRESH_SECONDS) {
            boolean shouldPing = state.lastWsPingSentAt == null
                || Duration.between(state.lastWsPingSentAt, now).toSeconds() >= RpcMonitorService.WS_PING_INTERVAL_SECONDS;
            if (shouldPing) {
                try {
                    state.lastWsPingSentAt = now;
                    webSocket.sendPing(ByteBuffer.wrap(new byte[] { 1 }));
                } catch (RuntimeException ex) {
                    monitor.recordFailure(node, MetricSource.WS, "WebSocket ping failed: " + ex.getMessage());
                    monitor.closeWebSocket(node, state, "ping failure");
                }
            }
        }
    }

    private class WsListener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();
        private final NodeDefinition node;
        private final RpcMonitorService.NodeState state;

        private WsListener(NodeDefinition node, RpcMonitorService.NodeState state) {
            this.node = node;
            this.state = state;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            WebSocket.Listener.super.onOpen(webSocket);
            Instant now = Instant.now();
            state.lastWsConnectedAt = now;
            state.lastWsMessageReceivedAt = now;
            state.lastWsPingSentAt = null;
            state.lastWsPongReceivedAt = null;
            WsConnectionTracker tracker = nodeRegistry.getWsTracker(node.key());
            if (tracker != null) {
                tracker.onConnect();
            }
            String subscribe = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"eth_subscribe\",\"params\":[\"newHeads\"]}";
            webSocket.sendText(subscribe, true);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String payload = buffer.toString();
                buffer.setLength(0);
                state.lastWsMessageReceivedAt = Instant.now();
                handleWsMessage(payload);
            }
            return WebSocket.Listener.super.onText(webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            return WebSocket.Listener.super.onBinary(webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            state.lastWsPongReceivedAt = Instant.now();
            return WebSocket.Listener.super.onPong(webSocket, message);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            logger.error("WebSocket error ({} / ws): {}", node.name(), error.getMessage());
            WsConnectionTracker tracker = nodeRegistry.getWsTracker(node.key());
            if (tracker != null) {
                tracker.onError(error);
            }
            monitor.recordFailure(node, MetricSource.WS, error.getMessage());
            clearWsConnectionState();
            WebSocket.Listener.super.onError(webSocket, error);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            WsConnectionTracker tracker = nodeRegistry.getWsTracker(node.key());
            if (tracker != null) {
                tracker.onDisconnect();
            }
            clearWsConnectionState();
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        private void clearWsConnectionState() {
            state.lastWsConnectedAt = null;
            state.lastWsMessageReceivedAt = null;
            state.lastWsPingSentAt = null;
            state.lastWsPongReceivedAt = null;
            state.webSocketRef.set(null);
        }

        private void handleWsMessage(String payload) {
            try {
                JsonNode root = mapper.readTree(payload);
                if (root.has("method") && "eth_subscription".equals(root.get("method").asText())) {
                    JsonNode result = root.path("params").path("result");
                    RpcMonitorService.BlockInfo blockInfo = EthHex.parseBlockFields(result);
                    String blockHash = blockInfo != null ? blockInfo.blockHash() : null;
                    String parentHash = blockInfo != null ? blockInfo.parentHash() : null;
                    Instant blockTimestamp = blockInfo != null ? blockInfo.blockTimestamp() : null;
                    Long blockNumber = blockInfo != null ? blockInfo.blockNumber() : null;

                    Instant now = Instant.now();
                    state.lastWsEventReceivedAt = now;
                    state.wsNewHeadCount++;

                    if (properties.getMode() == ChainCheckProperties.Mode.ETHEREUM && blockHash != null) {
                        handleEthereumNewHead(node, blockHash, now);
                    } else {
                        handleCosmosNewHead(node, blockNumber, blockHash, parentHash, blockTimestamp, now);
                    }
                }
            } catch (IOException | RuntimeException ex) {
                logger.error("WebSocket message handling failure ({}): {}", node.name(), ex.getMessage());
                monitor.recordFailure(node, MetricSource.WS, ex.getMessage());
            }
        }

        private void handleEthereumNewHead(NodeDefinition node, String eventHash, Instant now) {
            try {
                RpcMonitorService.BlockInfo fullBlock = httpMonitorService.fetchBlockByHash(node, eventHash);
                if (fullBlock == null || fullBlock.blockHash() == null) {
                    logger.warn("Failed to fetch full block by hash {} for node {}", eventHash, node.name());
                    return;
                }

                String blockHash = fullBlock.blockHash();
                String parentHash = fullBlock.parentHash();
                Long blockNumber = fullBlock.blockNumber();
                Instant blockTimestamp = fullBlock.blockTimestamp();

                ChainTracker tracker = chainTrackers.computeIfAbsent(node.key(), ChainTracker::new);
                ChainTracker.BlockNode blockNode = new ChainTracker.BlockNode(
                        blockHash, parentHash, 
                        blockNumber != null ? blockNumber : 0L,
                        blockTimestamp, now, Confidence.NEW);

                ChainTracker.ChainUpdate update = tracker.registerBlock(blockNode);

                if (update.reorg() != null) {
                    logger.info("Reorg detected via ChainTracker for node {}: depth={}, oldHead={}@{}, newHead={}@{}",
                            node.name(), update.reorg().reorgDepth(),
                            update.reorg().oldHeadHash(), update.reorg().oldHeadNumber(),
                            update.reorg().newHeadHash(), update.reorg().newHeadNumber());
                }

                if (!update.isNewBlock()) {
                    return;
                }

                Long headDelayMs = null;
                if (blockTimestamp != null) {
                    long delayMs = Duration.between(blockTimestamp, now).toMillis();
                    if (delayMs >= 0) {
                        headDelayMs = delayMs;
                    }
                    state.lastWsBlockTimestamp = blockTimestamp;
                }

                MetricSample sample = MetricSample.builder(now, MetricSource.WS)
                        .success(true)
                        .blockNumber(blockNumber)
                        .blockTimestamp(blockTimestamp)
                        .blockHash(blockHash)
                        .parentHash(parentHash)
                        .headDelayMs(headDelayMs)
                        .build();
                if (monitor.isWarmupComplete()) {
                    store.addSample(node.key(), sample);
                }
                if (node.http() != null && !node.http().isBlank()) {
                    final String verifyHash = blockHash;
                    recoveryExecutor.submit(() -> scheduleBlockVerification(node, verifyHash));
                }
                monitor.resetWsBackoff(state);

                WsConnectionTracker connTracker = nodeRegistry.getWsTracker(node.key());
                if (connTracker != null) {
                    connTracker.clearLastError();
                }

                if (blockNumber != null && blockHash != null) {
                    monitor.recordBlock(node.key(), blockNumber, blockHash, Confidence.NEW);
                }

                state.lastWsBlockNumber = blockNumber;
                if (blockHash != null) {
                    state.lastWsBlockHash = blockHash;
                }

                if (monitor.isWarmupComplete()) {
                    List<AnomalyEvent> anomalies = detector.detect(
                            node.key(),
                            sample,
                            node.anomalyDelayMs(),
                            properties.getAnomalyDetection().getStaleBlockThresholdMs(),
                            null,
                            null,
                            state.lastHttpBlockNumber);
                    for (AnomalyEvent anomaly : anomalies) {
                        store.addAnomaly(node.key(), anomaly);
                    }
                }

                List<Long> missing = tracker.findMissingBlockNumbers(blockNode);
                if (!missing.isEmpty() && node.wsGapRecoveryEnabled() && blockNumber != null) {
                    scheduleMissingBlockRecoveryForAllNodes(node, blockNumber - missing.size() - 1, blockNumber);
                }

                trackWsBlockHash(node, blockNumber, blockHash);

            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                logger.warn("Ethereum newHead handling interrupted ({}): {}", node.name(), ex.getMessage());
            } catch (IOException | RuntimeException ex) {
                logger.error("Ethereum newHead handling failure ({}): {}", node.name(), ex.getMessage());
                monitor.recordFailure(node, MetricSource.WS, ex.getMessage());
            }
        }

        private void handleCosmosNewHead(NodeDefinition node,
                Long blockNumber, String blockHash, String parentHash, Instant blockTimestamp, Instant now) {

            Long headDelayMs = null;
            if (blockTimestamp != null) {
                long delayMs = Duration.between(blockTimestamp, now).toMillis();
                if (delayMs >= 0) {
                    headDelayMs = delayMs;
                }
                state.lastWsBlockTimestamp = blockTimestamp;
            }

            MetricSample sample = MetricSample.builder(now, MetricSource.WS)
                    .success(true)
                    .blockNumber(blockNumber)
                    .blockTimestamp(blockTimestamp)
                    .blockHash(blockHash)
                    .parentHash(parentHash)
                    .headDelayMs(headDelayMs)
                    .build();
            if (monitor.isWarmupComplete()) {
                store.addSample(node.key(), sample);
            }
            monitor.resetWsBackoff(state);

            WsConnectionTracker tracker = nodeRegistry.getWsTracker(node.key());
            if (tracker != null) {
                tracker.clearLastError();
            }

            // Cosmos mode: register block via ChainTracker for reorg detection via parentHash linkage
            if (blockHash != null && parentHash != null && blockNumber != null && blockTimestamp != null) {
                ChainTracker chainTracker = chainTrackers.computeIfAbsent(node.key(), ChainTracker::new);
                ChainTracker.BlockNode blockNode = new ChainTracker.BlockNode(
                        blockHash, parentHash, blockNumber, blockTimestamp, now, Confidence.NEW);
                ChainTracker.ChainUpdate update = chainTracker.registerBlock(blockNode);

                if (update.reorg() != null) {
                    logger.info("Reorg detected via ChainTracker for Cosmos node {}: depth={}, oldHead={}@{}, newHead={}@{}",
                            node.name(), update.reorg().reorgDepth(),
                            update.reorg().oldHeadHash(), update.reorg().oldHeadNumber(),
                            update.reorg().newHeadHash(), update.reorg().newHeadNumber());
                }
            }

            if (monitor.isWarmupComplete()) {
                List<AnomalyEvent> anomalies = detector.detect(
                        node.key(),
                        sample,
                        node.anomalyDelayMs(),
                        properties.getAnomalyDetection().getStaleBlockThresholdMs(),
                        state.lastWsBlockNumber,
                        state.lastWsBlockHash,
                        state.lastHttpBlockNumber);
                for (AnomalyEvent anomaly : anomalies) {
                    store.addAnomaly(node.key(), anomaly);
                    if (anomaly.getType() == AnomalyType.REORG && "Block height decreased".equals(anomaly.getMessage())) {
                        state.hasOpenBlockHeightDecreasedAnomaly = true;
                        state.consecutiveIncreasingBlocksAfterDecrease = 0;
                    }
                }
            }

            if (state.hasOpenBlockHeightDecreasedAnomaly && blockNumber != null && state.lastWsBlockNumber != null) {
                if (blockNumber > state.lastWsBlockNumber) {
                    state.consecutiveIncreasingBlocksAfterDecrease++;
                    if (state.consecutiveIncreasingBlocksAfterDecrease >= 3) {
                        store.closeLastAnomaly(node.key(), MetricSource.WS, AnomalyType.REORG);
                        state.hasOpenBlockHeightDecreasedAnomaly = false;
                        state.consecutiveIncreasingBlocksAfterDecrease = 0;
                    }
                } else {
                    state.consecutiveIncreasingBlocksAfterDecrease = 0;
                }
            }

            if (blockNumber != null && state.lastWsBlockNumber != null) {
                scheduleMissingBlockRecoveryForAllNodes(node, state.lastWsBlockNumber, blockNumber);
            }

            trackWsBlockHash(node, blockNumber, blockHash);
            if (blockNumber != null) {
                scheduleInvalidDrivenGapRecovery();
            }

            state.lastWsBlockNumber = blockNumber;
            if (blockHash != null) {
                state.lastWsBlockHash = blockHash;
            }

            if (blockNumber != null && blockHash != null) {
                monitor.recordBlock(node.key(), blockNumber, blockHash, Confidence.NEW);
            }
        }
    }

    private void scheduleMissingBlockRecoveryForAllNodes(NodeDefinition triggerNode, long previousBlockNumber, long currentBlockNumber) {
        if (!triggerNode.wsGapRecoveryEnabled()) {
            return;
        }
        long gap = currentBlockNumber - previousBlockNumber - 1;
        if (gap <= 0) {
            return;
        }
        int maxBlocks = triggerNode.wsGapRecoveryMaxBlocks();
        long start = previousBlockNumber + 1;
        long end = currentBlockNumber - 1;
        if (maxBlocks > 0 && gap > maxBlocks) {
            long cappedStart = currentBlockNumber - maxBlocks;
            start = Math.max(start, cappedStart);
            logger.warn("WS gap recovery capped (trigger={}): previous={} current={} cappedStart={} maxBlocks={}",
                    triggerNode.name(), previousBlockNumber, currentBlockNumber, start, maxBlocks);
        }

        logger.info("WS gap detected (trigger={}): previous={} current={} recovering blocks {}..{} on all execution nodes",
                triggerNode.name(), previousBlockNumber, currentBlockNumber, start, end);

        for (NodeDefinition targetNode : nodeRegistry.getNodes()) {
            if (targetNode.http() == null || targetNode.http().isBlank()) {
                continue;
            }
            if (!targetNode.wsGapRecoveryEnabled()) {
                continue;
            }
            for (long number = start; number <= end; number++) {
                if (!shouldAttemptRecovery(targetNode.key(), number)) {
                    continue;
                }
                long recoveryNumber = number;
                recoveryExecutor.submit(() -> recoverMissingBlockWithRetry(targetNode, recoveryNumber));
            }
        }
    }

    private void scheduleInvalidDrivenGapRecovery() {
        for (NodeDefinition node : nodeRegistry.getNodes()) {
            if (node.http() == null || node.http().isBlank() || !node.wsGapRecoveryEnabled()) {
                continue;
            }
            RpcMonitorService.NodeState state = nodeStates.get(node.key());
            if (state == null || state.lastWsBlockNumber == null) {
                continue;
            }

            Map<Long, Set<String>> byNumber = wsHashesByNodeAndNumber.get(node.key());
            if (byNumber == null || byNumber.isEmpty()) {
                continue;
            }

            long nodeHead = state.lastWsBlockNumber;
            for (Map.Entry<Long, Set<String>> entry : byNumber.entrySet()) {
                long potentiallyInvalidNumber = entry.getKey();
                Set<String> hashes = entry.getValue();
                if (hashes == null || hashes.size() < 2) {
                    continue;
                }

                long nextNumber = potentiallyInvalidNumber + 1;
                if (nodeHead <= nextNumber) {
                    continue;
                }
                if (byNumber.containsKey(nextNumber)) {
                    continue;
                }

                long start = nextNumber;
                long end = nodeHead - 1;
                int maxBlocks = node.wsGapRecoveryMaxBlocks();
                if (maxBlocks > 0 && end - start + 1 > maxBlocks) {
                    start = end - maxBlocks + 1;
                }

                logger.info("Invalid-driven gap recovery (node={}): invalidHeight={} missingRange={}..{}",
                        node.name(), potentiallyInvalidNumber, start, end);
                for (long blockNumber = start; blockNumber <= end; blockNumber++) {
                    if (!shouldAttemptRecovery(node.key(), blockNumber)) {
                        continue;
                    }
                    long recoveryNumber = blockNumber;
                    recoveryExecutor.submit(() -> recoverMissingBlockWithRetry(node, recoveryNumber));
                }
            }
        }
    }

    private void trackWsBlockHash(NodeDefinition node, Long blockNumber, String blockHash) {
        if (blockNumber == null || blockHash == null || blockHash.isBlank()) {
            return;
        }
        Map<Long, Set<String>> byNumber = wsHashesByNodeAndNumber.computeIfAbsent(node.key(), key -> new ConcurrentHashMap<>());
        Set<String> hashes = byNumber.computeIfAbsent(blockNumber, key -> ConcurrentHashMap.newKeySet());
        hashes.add(blockHash.toLowerCase());

        long pruneBefore = blockNumber - INVALID_TRACKING_LOOKBACK_BLOCKS;
        if (pruneBefore > 0) {
            byNumber.entrySet().removeIf(entry -> entry.getKey() < pruneBefore);
        }
    }

    private boolean shouldAttemptRecovery(String nodeKey, long blockNumber) {
        Instant now = Instant.now();
        Instant cutoff = now.minus(RECOVERY_ATTEMPT_DEDUP_WINDOW);
        recoveryAttemptByNodeAndBlock.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));

        String key = nodeKey + ":" + blockNumber;
        Instant previous = recoveryAttemptByNodeAndBlock.put(key, now);
        return previous == null || previous.isBefore(cutoff);
    }

    private void scheduleBlockVerification(NodeDefinition node, String blockHash) {
        long delayMs = properties.getBlockVerificationDelayMs();
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        long startNanos = System.nanoTime();
        try {
            RpcMonitorService.BlockInfo block = httpMonitorService.fetchBlockByHash(node, blockHash);
            if (block == null || block.blockHash() == null) {
                return;
            }
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            Instant now = Instant.now();
            MetricSample sample = MetricSample.builder(now, MetricSource.HTTP)
                    .success(true)
                    .latencyMs(latencyMs)
                    .blockNumber(block.blockNumber())
                    .blockTimestamp(block.blockTimestamp())
                    .blockHash(block.blockHash())
                    .parentHash(block.parentHash())
                    .transactionCount(block.transactionCount())
                    .gasPriceWei(block.gasPriceWei())
                    .build();
            if (monitor.isWarmupComplete()) {
                store.addSample(node.key(), sample);
            }
            logger.debug("Block verification sample (node={}, hash={}, number={})",
                    node.name(), blockHash, block.blockNumber());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException ex) {
            logger.debug("Block verification failed (node={}, hash={}): {}", node.name(), blockHash, ex.getMessage());
        }
    }

    private void recoverMissingBlockWithRetry(NodeDefinition node, long blockNumber) {
        if (node.http() == null || node.http().isBlank()) {
            return;
        }
        for (int attempt = 1; attempt <= RECOVERY_MAX_RETRIES; attempt++) {
            long startNanos = System.nanoTime();
            try {
                RpcMonitorService.BlockInfo block = httpMonitorService.fetchBlockByNumber(node, blockNumber);
                if (block == null || block.blockNumber() == null || block.blockHash() == null) {
                    throw new IOException("block " + blockNumber + " not found");
                }

                long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
                Instant now = Instant.now();
                Long headDelayMs = null;
                if (block.blockTimestamp() != null) {
                    long delay = Duration.between(block.blockTimestamp(), now).toMillis();
                    if (delay >= 0) {
                        headDelayMs = delay;
                    }
                }
                MetricSample sample = MetricSample.builder(now, MetricSource.HTTP)
                        .success(true)
                        .latencyMs(latencyMs)
                        .blockNumber(block.blockNumber())
                        .blockTimestamp(block.blockTimestamp())
                        .blockHash(block.blockHash())
                        .parentHash(block.parentHash())
                        .transactionCount(block.transactionCount())
                        .gasPriceWei(block.gasPriceWei())
                        .headDelayMs(headDelayMs)
                        .build();
                if (monitor.isWarmupComplete()) {
                    store.addSample(node.key(), sample);
                }
                logger.info("WS gap recovery success (node={}, block={}, attempt={})", node.name(), blockNumber, attempt);
                return;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                logger.warn("WS gap recovery interrupted ({}): {}", node.name(), ex.getMessage());
                return;
            } catch (IOException | RuntimeException ex) {
                if (attempt >= RECOVERY_MAX_RETRIES) {
                    logger.warn("WS gap recovery gave up (node={}, block={}, attempts={}): {}",
                            node.name(), blockNumber, RECOVERY_MAX_RETRIES, ex.getMessage());
                    return;
                }
                logger.debug("WS gap recovery retry scheduled (node={}, block={}, attempt={}): {}",
                        node.name(), blockNumber, attempt, ex.getMessage());
                try {
                    Thread.sleep(Math.max(1, node.pollIntervalMs()));
                } catch (InterruptedException interruptedEx) {
                    Thread.currentThread().interrupt();
                    logger.warn("WS gap recovery backoff interrupted ({}): {}", node.name(), interruptedEx.getMessage());
                    return;
                }
            }
        }
    }

}

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
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.makibytes.chaincheck.config.ChainCheckProperties;
import de.makibytes.chaincheck.model.AnomalyEvent;
import de.makibytes.chaincheck.model.MetricSample;
import de.makibytes.chaincheck.model.MetricSource;
import de.makibytes.chaincheck.monitor.NodeRegistry.NodeDefinition;
import de.makibytes.chaincheck.store.InMemoryMetricsStore;
import jakarta.annotation.PostConstruct;

@Service
public class RpcMonitorService {

    private static final String JSONRPC_VERSION = "2.0";
    private static final Logger logger = LoggerFactory.getLogger(RpcMonitorService.class);
    private static final long WS_FRESH_SECONDS = 30;
    private static final long WS_DEAD_SECONDS = 120;
    private static final long WS_PING_AFTER_SECONDS = 45;
    private static final long WS_PING_INTERVAL_SECONDS = 30;
    private static final long WS_PING_TIMEOUT_SECONDS = 30;
    private static final String WS_NO_NEW_HEADS_PREFIX = "WebSocket not receiving newHeads events";

    private final NodeRegistry nodeRegistry;
    private final InMemoryMetricsStore store;
    private final AnomalyDetector detector;
    private final ChainCheckProperties properties;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Map<Long, HttpClient> httpClients = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, NodeState> nodeStates = new ConcurrentHashMap<>();
    private final AtomicReference<ReferenceState> referenceState = new AtomicReference<>();
        private static final int REFERENCE_SELECTION_WINDOW = 20;
        private static final int REFERENCE_SWITCH_THRESHOLD = 15;
        private final ReferenceSelectionPolicy referenceSelectionPolicy =
            new ReferenceSelectionPolicy(REFERENCE_SELECTION_WINDOW, REFERENCE_SWITCH_THRESHOLD);
    private Long referenceFirstSetAtBlock = null;
    private String currentReferenceNodeKey;

    @Autowired
    public RpcMonitorService(NodeRegistry nodeRegistry,
                             InMemoryMetricsStore store,
                             AnomalyDetector detector,
                             ChainCheckProperties properties) {
        this.nodeRegistry = nodeRegistry;
        this.store = store;
        this.detector = detector;
        this.properties = properties;
    }

    /**
     * Backwards-compatible constructor used by older tests that did not wire properties.
     */
    public RpcMonitorService(NodeRegistry nodeRegistry,
                             InMemoryMetricsStore store,
                             AnomalyDetector detector) {
        this(nodeRegistry, store, detector, new ChainCheckProperties());
    }

    @PostConstruct
    public void init() {
        ensureWebSocket();
    }

    /**
     * Returns the node key of the current reference node, or null if no reference is set.
     */
    public String getReferenceNodeKey() {
        if (currentReferenceNodeKey != null && nodeStates.containsKey(currentReferenceNodeKey)) {
            return currentReferenceNodeKey;
        }
        ReferenceState ref = referenceState.get();
        if (ref == null || ref.headNumber == null || ref.headHash == null) {
            return null;
        }

        // Find which node matches the reference block
        for (Map.Entry<String, NodeState> entry : nodeStates.entrySet()) {
            NodeState state = entry.getValue();
            
            // Check WebSocket head first (preferred)
            if (state.lastWsBlockNumber != null && state.lastWsBlockNumber.equals(ref.headNumber)
                    && state.lastWsBlockHash != null && state.lastWsBlockHash.equals(ref.headHash)) {
                return entry.getKey();
            }
            
            // Check HTTP head
            if (state.lastHttpBlockNumber != null && state.lastHttpBlockNumber.equals(ref.headNumber)
                    && state.lastHttpBlockHash != null && state.lastHttpBlockHash.equals(ref.headHash)) {
                return entry.getKey();
            }
        }
        
        return null; // No node currently matches reference
    }

    /**
     * Returns whether the given node matches the current reference head, or empty if reference state is unavailable.
     */
    public Optional<Boolean> isReferenceNode(String nodeKey) {
        if (nodeKey == null || nodeKey.isBlank()) {
            return Optional.empty();
        }
        ReferenceState ref = referenceState.get();
        if (ref == null || ref.headNumber == null) {
            return Optional.empty();
        }
        NodeState state = nodeStates.get(nodeKey);
        if (state == null) {
            return Optional.empty();
        }
        Long nodeBlockNumber = state.lastWsBlockNumber != null
                ? state.lastWsBlockNumber
                : state.lastHttpBlockNumber;
        if (nodeBlockNumber == null) {
            return Optional.empty();
        }
        return Optional.of(nodeBlockNumber.equals(ref.headNumber));
    }

    @Scheduled(fixedDelay = 250, initialDelay = 500)
    public void pollNodes() {
        long now = System.currentTimeMillis();
        refreshReferenceFromNodes();
        for (NodeDefinition node : nodeRegistry.getNodes()) {
            if (node.http() == null || node.http().isBlank()) {
                continue;
            }
            NodeState state = nodeStates.computeIfAbsent(node.key(), key -> new NodeState());
            if (now - state.lastPollEpochMs < node.pollIntervalMs()) {
                continue;
            }
            state.lastPollEpochMs = now;
            executor.submit(() -> pollHttp(node, state));
        }
    }

    private void recordFailure(NodeDefinition node, MetricSource source, String errorMessage) {
        checkAndCloseAnomaly(node, errorMessage, source);

        if (source == MetricSource.HTTP) {
            HttpConnectionTracker httpTracker = nodeRegistry.getHttpTracker(node.key());
            if (httpTracker != null) {
                httpTracker.onErrorMessage(errorMessage);
            }
        } else if (source == MetricSource.WS) {
            WsConnectionTracker wsTracker = nodeRegistry.getWsTracker(node.key());
            if (wsTracker != null) {
                wsTracker.setLastError(errorMessage);
            }
        }
        
        MetricSample sample = new MetricSample(
                Instant.now(),
                source,
                false,
                -1,
                null,
                null,
                null,
                null,
                null,
                null,
                errorMessage,
                null,
                null,
                null);
        store.addSample(node.key(), sample);
        NodeState state = nodeStates.get(node.key());
        Long lastBlock = getLastBlock(state, source);
        String lastHash = getLastHash(state, source);

        List<AnomalyEvent> anomalies = detector.detect(
                node.key(),
                sample,
                node.anomalyDelayMs(),
                lastBlock,
                lastHash);
        for (AnomalyEvent anomaly : anomalies) {
            store.addAnomaly(node.key(), anomaly);
        }
    }

    private HttpClient getHttpClient(long connectTimeoutMs) {
        long effectiveTimeout = connectTimeoutMs > 0 ? connectTimeoutMs : properties.getDefaults().getConnectTimeoutMs();
        return httpClients.computeIfAbsent(effectiveTimeout, timeout -> HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1, timeout)))
                .build());
    }

    private boolean areErrorsSame(String error1, String error2) {
        String norm1 = normalizeError(error1);
        String norm2 = normalizeError(error2);
        return java.util.Objects.equals(norm1, norm2);
    }

    private String normalizeError(String error) {
        if (error == null) {
            return null;
        }
        if (isWsNoNewHeadsError(error)) {
            return "ws-no-new-heads";
        }
        if (error.contains("call rate limit exhausted")) {
            return "rate-limit";
        }
        return error;
    }

    private String classifyError(Throwable error) {
        if (error == null) {
            return "Unknown error";
        }
        if (error instanceof HttpTimeoutException) {
            return "HTTP timeout: " + error.getMessage();
        }
        if (error instanceof java.net.ConnectException) {
            return "Connect error: " + error.getMessage();
        }
        if (error instanceof HttpStatusException statusEx) {
            return "HTTP " + statusEx.getStatusCode() + ": " + statusEx.getMessage();
        }
        return error.getMessage();
    }

    private void checkAndCloseAnomaly(NodeDefinition node, String currentError, MetricSource source) {
        NodeState state = nodeStates.computeIfAbsent(node.key(), key -> new NodeState());
        String previousError = getLastError(state, source);
        
        // If the result changed significantly (not just a counter update)
        if (!areErrorsSame(currentError, previousError)) {
            // If the previous state was an error, close the related anomaly
            if (previousError != null) {
                store.closeLastAnomaly(node.key(), source);
            }
        }
        
        // Always update state to the latest error message (even if technically "same" type)
        // so that we have the latest counter/details
        setLastError(state, source, currentError);
    }

    private void pollHttp(NodeDefinition node, NodeState state) {
        Instant timestamp = Instant.now();
        long startNanos = System.nanoTime();
        try {
            Long blockNumber = fetchBlockNumber(node);
            if (blockNumber == null) {
                recordFailure(node, MetricSource.HTTP, "Could not fetch block number");
                return;
            }

            // Detect dead websocket connections using event timestamp
            if (node.ws() != null && !node.ws().isBlank()) {
                WebSocket ws = state.webSocketRef.get();
                if (ws != null) {
                    Long previousHttpBlock = state.lastHttpBlockNumber;
                    Instant lastWsEvent = state.lastWsEventReceivedAt;
                    
                    // If HTTP block advanced (meaning new blocks are being produced)
                    if (previousHttpBlock != null && blockNumber > previousHttpBlock) {
                        // Check if WS is receiving events
                        if (lastWsEvent == null) {
                            // Never received any WS event - dead from beginning
                            recordFailure(node, MetricSource.WS, wsNoNewHeadsSinceConnectionMessage());
                        } else {
                            // Check if last WS event is too old (no events for 10+ seconds)
                            long secondsSinceLastEvent = Duration.between(lastWsEvent, Instant.now()).toSeconds();
                            if (secondsSinceLastEvent > WS_DEAD_SECONDS) {
                                recordFailure(node, MetricSource.WS, wsNoNewHeadsForSecondsMessage(secondsSinceLastEvent));
                                closeWebSocket(node, state, "stale subscription");
                            } else {
                                // WS is healthy (receiving events recently), treat as update to close potential previous errors
                                checkAndCloseAnomaly(node, null, MetricSource.WS);
                            }
                        }
                    }
                }
            }

            // When safe blocks are enabled, alternate between safe and finalized queries
            // When safe blocks are disabled, always query finalized
            String blockTag;
            if (node.safeBlocksEnabled()) {
                state.pollCounter++;
                blockTag = (state.pollCounter % 2 == 1) ? "safe" : "finalized";
            } else {
                blockTag = "finalized";
            }
            
            BlockInfo checkpointBlock = fetchBlockByTag(node, blockTag);
            if (checkpointBlock == null) {
                recordFailure(node, MetricSource.HTTP, blockTag + " block not found");
                return;
            }

            if ("safe".equals(blockTag)) {
                state.lastSafeBlockNumber = checkpointBlock.blockNumber();
                state.lastSafeBlockHash = checkpointBlock.blockHash();
            } else {
                state.lastFinalizedBlockNumber = checkpointBlock.blockNumber();
                state.lastFinalizedBlockHash = checkpointBlock.blockHash();
            }

            maybeCompareToReference(node, blockTag, checkpointBlock);
            
            // Successfully fetched all data - this counts as a success sample for HTTP
            checkAndCloseAnomaly(node, null, MetricSource.HTTP);
            
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            
            HttpConnectionTracker tracker = nodeRegistry.getHttpTracker(node.key());
            if (tracker != null) {
                tracker.onSuccess();
            }

            // Calculate delays using block timestamps
            Long headDelayMs = null;
            Long safeDelayMs = null;
            Long finalizedDelayMs = null;
            
            // Only calculate head delay if WS data is fresh (received within last 30 seconds)
            boolean wsDataFresh = state.lastWsBlockTimestamp != null
                && Duration.between(state.lastWsBlockTimestamp, timestamp).toSeconds() < WS_FRESH_SECONDS;

            if (wsDataFresh) {
                // Head delay: age of the WS head block
                headDelayMs = Duration.between(state.lastWsBlockTimestamp, timestamp).toMillis();
            }

            // Checkpoint delay: age of the safe/finalized block
            if (checkpointBlock.blockTimestamp() != null) {
                long checkpointDelay = Duration.between(
                    checkpointBlock.blockTimestamp(),
                    timestamp
                ).toMillis();

                // Only record positive ages
                if (checkpointDelay >= 0) {
                    if ("safe".equals(blockTag)) {
                        safeDelayMs = checkpointDelay;
                    } else {
                        finalizedDelayMs = checkpointDelay;
                    }
                }
            }

            MetricSample sample = new MetricSample(
                    timestamp,
                    MetricSource.HTTP,
                    true,
                    latencyMs,
                    blockNumber,
                    checkpointBlock.blockTimestamp(),
                    checkpointBlock.blockHash(),
                    checkpointBlock.parentHash(),
                    checkpointBlock.transactionCount(),
                    checkpointBlock.gasPriceWei(),
                    null,
                    headDelayMs,
                    safeDelayMs,
                    finalizedDelayMs);
            store.addSample(node.key(), sample);
            state.lastHttpBlockNumber = blockNumber;
            state.lastHttpBlockHash = checkpointBlock.blockHash();
        } catch (HttpStatusException ex) {
            logger.error("HTTP RPC failure ({} / http): status {} ({})", node.name(), ex.getStatusCode(), ex.getMessage());
            recordFailure(node, MetricSource.HTTP, classifyError(ex));
        } catch (IOException | InterruptedException ex) {
            recordFailure(node, MetricSource.HTTP, classifyError(ex));
        } catch (RuntimeException ex) {
            logger.error("HTTP RPC failure ({}): {}", node.name(), ex.getMessage());
            recordFailure(node, MetricSource.HTTP, ex.getMessage());
        }
    }

    private void maybeCompareToReference(NodeDefinition node, String blockTag, BlockInfo checkpointBlock) {
        ReferenceState ref = referenceState.get();
        if (ref == null || ref.headNumber == null) {
            return;
        }

        String referenceNodeKey = getReferenceNodeKey();
        if (referenceNodeKey == null || referenceNodeKey.equals(node.key())) {
            return;
        }

        NodeState state = nodeStates.get(node.key());
        if (state != null && isWsNoNewHeadsError(state.lastWsError)) {
            return;
        }

        MetricSource source = MetricSource.HTTP;
        Long blockNumber = null;
        String blockHash = null;
        if (state != null) {
            boolean wsFresh = isWsFresh(state, Instant.now());
            if (wsFresh && state.lastWsBlockNumber != null && state.lastWsBlockHash != null) {
                source = MetricSource.WS;
                blockNumber = state.lastWsBlockNumber;
                blockHash = state.lastWsBlockHash;
            } else if (state.lastHttpBlockNumber != null && state.lastHttpBlockHash != null) {
                blockNumber = state.lastHttpBlockNumber;
                blockHash = state.lastHttpBlockHash;
            }
        }

        if (blockNumber == null || blockHash == null) {
            return;
        }

        // Skip comparisons until a reference node exists and at least 10 newHead events were observed
        if (referenceFirstSetAtBlock == null || ref.headNumber - referenceFirstSetAtBlock < 10) {
            return;
        }

        long lag = ref.headNumber - blockNumber;

        // Only flag as delay anomaly if lag exceeds configurable threshold
        // Small differences are normal and expected
        ChainCheckProperties.AnomalyDetection detection = properties.getAnomalyDetection();
        int lagThreshold = detection != null && detection.getLongDelayBlockCount() != null
            ? detection.getLongDelayBlockCount()
            : 15;

        if (lag >= lagThreshold) {
            AnomalyEvent anomaly = detector.referenceDelay(
                    node.key(),
                    Instant.now(),
                    source,
                    blockNumber,
                    blockHash,
                    "Node behind reference: reference head " + ref.headNumber + " vs node " + blockNumber);
            store.addAnomaly(node.key(), anomaly);
        }

        NodeState referenceStateForNode = nodeStates.get(referenceNodeKey);
        if (referenceStateForNode == null || checkpointBlock == null) {
            return;
        }

        Long referenceCheckpointNumber;
        String referenceCheckpointHash;
        if ("safe".equals(blockTag)) {
            referenceCheckpointNumber = referenceStateForNode.lastSafeBlockNumber;
            referenceCheckpointHash = referenceStateForNode.lastSafeBlockHash;
        } else {
            referenceCheckpointNumber = referenceStateForNode.lastFinalizedBlockNumber;
            referenceCheckpointHash = referenceStateForNode.lastFinalizedBlockHash;
        }

        Long checkpointNumber = checkpointBlock.blockNumber();
        if (referenceCheckpointNumber == null || referenceCheckpointHash == null || checkpointNumber == null || checkpointBlock.blockHash() == null) {
            return;
        }

        if (referenceCheckpointNumber.equals(checkpointNumber) && !referenceCheckpointHash.equals(checkpointBlock.blockHash())) {
                AnomalyEvent anomaly = detector.wrongHead(
                    node.key(),
                    Instant.now(),
                    MetricSource.HTTP,
                    checkpointNumber,
                    checkpointBlock.blockHash(),
                    "Hash mismatch at " + blockTag + " height " + checkpointNumber + " (reference " + referenceCheckpointHash + ")");
            store.addAnomaly(node.key(), anomaly);
        }
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 1000)
    public void ensureWebSocket() {
        long now = System.currentTimeMillis();
        for (NodeDefinition node : nodeRegistry.getNodes()) {
            if (node.ws() == null || node.ws().isBlank()) {
                continue;
            }
            NodeState state = nodeStates.computeIfAbsent(node.key(), key -> new NodeState());
            WebSocket existing = state.webSocketRef.get();
            if (existing != null) {
                checkWsHealth(node, state, existing);
                continue;
            }
            if (now - state.lastWsAttemptEpochMs < node.pollIntervalMs()) {
                continue;
            }
            state.lastWsAttemptEpochMs = now;
            getHttpClient(properties.getDefaults().getConnectTimeoutMs()).newWebSocketBuilder()
                    .buildAsync(URI.create(node.ws()), new WsListener(node, state))
                    .thenAccept(state.webSocketRef::set)
                    .exceptionally(ex -> {
                        String msg = ex.getMessage();
                        if (ex.getCause() instanceof java.net.http.WebSocketHandshakeException handshakeEx) {
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
                        recordFailure(node, MetricSource.WS, msg);
                        state.webSocketRef.set(null);
                        return null;
                    });
        }
    }

    private void refreshReferenceFromNodes() {
        List<NodeDefinition> nodes = nodeRegistry.getNodes();
        if (nodes.isEmpty()) {
            referenceState.set(null);
            return;
        }

        for (NodeDefinition node : nodes) {
            if (node.ws() == null || node.ws().isBlank()) {
                continue;
            }
            NodeState state = nodeStates.get(node.key());
            if (state == null) {
                referenceState.set(null);
                return;
            }
            if (state.lastWsError != null) {
                continue;
            }
            if (state.wsNewHeadCount < 10) {
                referenceState.set(null);
                return;
            }
        }

        Instant now = Instant.now();
        List<ReferenceCandidate> candidates = new ArrayList<>();
        long maxHeight = Long.MIN_VALUE;

        for (NodeDefinition node : nodes) {
            NodeState state = nodeStates.get(node.key());
            if (state == null) {
                continue;
            }

                boolean wsFresh = isWsFresh(state, now);

            if (wsFresh && state.lastWsBlockNumber != null && state.lastWsBlockHash != null) {
                candidates.add(new ReferenceCandidate(node.key(), state.lastWsBlockNumber, state.lastWsBlockHash, true));
                maxHeight = Math.max(maxHeight, state.lastWsBlockNumber);
                continue;
            }

            if (state.lastHttpBlockNumber != null && state.lastHttpBlockHash != null) {
                candidates.add(new ReferenceCandidate(node.key(), state.lastHttpBlockNumber, state.lastHttpBlockHash, false));
                maxHeight = Math.max(maxHeight, state.lastHttpBlockNumber);
            }
        }

        if (candidates.isEmpty() || maxHeight == Long.MIN_VALUE) {
            referenceState.set(null);
            return;
        }

        long freshestHeight = maxHeight;
        List<ReferenceCandidate> nearTip = candidates.stream()
                .filter(c -> c.blockNumber != null && freshestHeight - c.blockNumber <= 2)
                .toList();

        boolean manyStalled = nearTip.size() * 2 < candidates.size();
        List<ReferenceCandidate> healthyWs = nearTip.stream()
                .filter(ReferenceCandidate::wsFresh)
                .toList();

        List<ReferenceCandidate> selectionPool = (!healthyWs.isEmpty() && manyStalled) ? healthyWs : candidates;
        ReferenceCandidate majority = selectMajority(selectionPool);

        if (majority != null) {
            String selectedKey = majority.nodeKey();
            referenceSelectionPolicy.registerSelection(selectedKey);

            if (currentReferenceNodeKey == null) {
                currentReferenceNodeKey = selectedKey;
                updateReferenceState(majority, true);
                return;
            }

                if (!currentReferenceNodeKey.equals(selectedKey)
                    && referenceSelectionPolicy.shouldSwitchTo(selectedKey)) {
                currentReferenceNodeKey = selectedKey;
                updateReferenceState(majority, true);
                return;
            }

            ReferenceCandidate currentCandidate = candidates.stream()
                    .filter(candidate -> currentReferenceNodeKey.equals(candidate.nodeKey()))
                    .findFirst()
                    .orElse(null);
            if (currentCandidate != null) {
                updateReferenceState(currentCandidate, false);
                return;
            }

            currentReferenceNodeKey = selectedKey;
            updateReferenceState(majority, true);
        } else {
            referenceState.set(null);
        }
    }

    private void updateReferenceState(ReferenceCandidate candidate, boolean resetReferenceWindow) {
        if (candidate == null) {
            return;
        }
        ReferenceState oldRef = referenceState.get();
        referenceState.set(new ReferenceState(candidate.blockNumber(), candidate.blockHash(), Instant.now()));

        if (resetReferenceWindow) {
            referenceFirstSetAtBlock = candidate.blockNumber();
            logger.info("Reference head established at block {}", candidate.blockNumber());
            return;
        }

        if (oldRef == null && referenceFirstSetAtBlock == null) {
            referenceFirstSetAtBlock = candidate.blockNumber();
            logger.info("Reference head established at block {}", candidate.blockNumber());
        }
    }

    private boolean isWsFresh(NodeState state, Instant now) {
        return state.lastWsEventReceivedAt != null
                && Duration.between(state.lastWsEventReceivedAt, now).toSeconds() < WS_FRESH_SECONDS;
    }

    private boolean isWsNoNewHeadsError(String error) {
        return error != null && error.startsWith(WS_NO_NEW_HEADS_PREFIX);
    }

    private void checkWsHealth(NodeDefinition node, NodeState state, WebSocket webSocket) {
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
            if (pingAge > WS_PING_TIMEOUT_SECONDS) {
                recordFailure(node, MetricSource.WS, "WebSocket ping timeout after " + pingAge + "s");
                closeWebSocket(node, state, "ping timeout");
                return;
            }
        }

        if (idleSeconds >= WS_PING_AFTER_SECONDS) {
            boolean shouldPing = state.lastWsPingSentAt == null
                    || Duration.between(state.lastWsPingSentAt, now).toSeconds() >= WS_PING_INTERVAL_SECONDS;
            if (shouldPing) {
                try {
                    state.lastWsPingSentAt = now;
                    webSocket.sendPing(ByteBuffer.wrap(new byte[] { 1 }));
                } catch (RuntimeException ex) {
                    recordFailure(node, MetricSource.WS, "WebSocket ping failed: " + ex.getMessage());
                    closeWebSocket(node, state, "ping failure");
                }
            }
        }
    }

    private void closeWebSocket(NodeDefinition node, NodeState state, String reason) {
        WebSocket webSocket = state.webSocketRef.getAndSet(null);
        if (webSocket == null) {
            return;
        }
        try {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, reason == null ? "reconnect" : reason);
        } catch (RuntimeException ex) {
            logger.debug("WebSocket close failed ({} / ws): {}", node.name(), ex.getMessage());
            webSocket.abort();
        }
    }

    private String wsNoNewHeadsSinceConnectionMessage() {
        return WS_NO_NEW_HEADS_PREFIX + " (no events since connection)";
    }

    private String wsNoNewHeadsForSecondsMessage(long secondsSinceLastEvent) {
        return WS_NO_NEW_HEADS_PREFIX + " (last event " + secondsSinceLastEvent + "s ago)";
    }

    private String getLastError(NodeState state, MetricSource source) {
        if (state == null) {
            return null;
        }
        return source == MetricSource.HTTP ? state.lastHttpError : state.lastWsError;
    }

    private void setLastError(NodeState state, MetricSource source, String currentError) {
        if (state == null) {
            return;
        }
        if (source == MetricSource.HTTP) {
            state.lastHttpError = currentError;
        } else {
            state.lastWsError = currentError;
        }
    }

    private Long getLastBlock(NodeState state, MetricSource source) {
        if (state == null) {
            return null;
        }
        return source == MetricSource.HTTP ? state.lastHttpBlockNumber : state.lastWsBlockNumber;
    }

    private String getLastHash(NodeState state, MetricSource source) {
        if (state == null) {
            return null;
        }
        return source == MetricSource.HTTP ? state.lastHttpBlockHash : state.lastWsBlockHash;
    }

    private ReferenceCandidate selectMajority(List<ReferenceCandidate> candidates) {
        if (candidates.isEmpty()) {
            return null;
        }

        Map<ReferenceKey, MajorityCounter> counts = new HashMap<>();
        for (ReferenceCandidate candidate : candidates) {
            if (candidate.blockNumber == null || candidate.blockHash == null) {
                continue;
            }
            ReferenceKey key = new ReferenceKey(candidate.blockNumber, candidate.blockHash);
            counts.computeIfAbsent(key, unused -> new MajorityCounter()).increment(candidate.wsFresh);
        }

        if (counts.isEmpty()) {
            return null;
        }

        Map.Entry<ReferenceKey, MajorityCounter> firstEntry = counts.entrySet().iterator().next();
        ReferenceKey bestKey = firstEntry.getKey();
        MajorityCounter bestCounter = firstEntry.getValue();

        for (Map.Entry<ReferenceKey, MajorityCounter> entry : counts.entrySet()) {
            ReferenceKey key = entry.getKey();
            MajorityCounter counter = entry.getValue();
            if (counter.count > bestCounter.count
                    || (counter.count == bestCounter.count && key.blockNumber() > bestKey.blockNumber())
                    || (counter.count == bestCounter.count && key.blockNumber().equals(bestKey.blockNumber())
                        && counter.wsFreshCount > bestCounter.wsFreshCount)) {
                bestKey = key;
                bestCounter = counter;
            }
        }

        ReferenceKey winningKey = bestKey;
        MajorityCounter winningCounter = bestCounter;
        return candidates.stream()
                .filter(candidate -> winningKey.equals(new ReferenceKey(candidate.blockNumber(), candidate.blockHash())))
                .sorted((left, right) -> Boolean.compare(right.wsFresh(), left.wsFresh()))
                .findFirst()
                .orElseGet(() -> new ReferenceCandidate("majority", winningKey.blockNumber(), winningKey.blockHash(), winningCounter.wsFreshCount > 0));
    }

    private Long fetchBlockNumber(NodeDefinition node) throws IOException, InterruptedException {
        JsonNode response = sendRpcWithRetry(
                node.http(),
                node.headers(),
                node.readTimeoutMs(),
                node.maxRetries(),
                node.retryBackoffMs(),
                node.connectTimeoutMs(),
                "eth_blockNumber",
                mapper.createArrayNode());
        if (response.has("error")) {
            throw new IOException(response.get("error").toString());
        }
        JsonNode result = response.get("result");
        if (result == null || result.isNull()) {
            return null;
        }
        return parseHexLong(result.asText());
    }

    private BlockInfo fetchBlockByTag(NodeDefinition node, String blockTag) throws IOException, InterruptedException {
        return fetchBlockByTag(node.http(), node.readTimeoutMs(), node.headers(), node.maxRetries(), node.retryBackoffMs(), node.connectTimeoutMs(), blockTag);
    }

    private BlockInfo fetchBlockByTag(String httpUrl,
                                      long readTimeoutMs,
                                      Map<String, String> headers,
                                      int maxRetries,
                                      long retryBackoffMs,
                                      long connectTimeoutMs,
                                      String blockTag) throws IOException, InterruptedException {
        JsonNode params = mapper.createArrayNode()
                .add(blockTag)
                .add(false);
        JsonNode response = sendRpcWithRetry(httpUrl, headers, readTimeoutMs, maxRetries, retryBackoffMs, connectTimeoutMs, "eth_getBlockByNumber", params);
        if (response.has("error")) {
            throw new IOException(response.get("error").toString());
        }
        JsonNode result = response.get("result");
        if (result == null || result.isNull()) {
            return null;
        }
        Long blockNumber = parseHexLong(result.path("number").asText(null));
        String blockHash = result.path("hash").asText(null);
        String parentHash = result.path("parentHash").asText(null);
        Instant blockTimestamp = null;
        String timestampHex = result.path("timestamp").asText(null);
        if (timestampHex != null && !timestampHex.isBlank()) {
            Long timestampSeconds = parseHexLong(timestampHex);
            if (timestampSeconds != null) {
                blockTimestamp = Instant.ofEpochSecond(timestampSeconds);
            }
        }
        Long gasPriceWei = null;
        String baseFeeHex = result.path("baseFeePerGas").asText(null);
        if (baseFeeHex != null && !baseFeeHex.isBlank()) {
            gasPriceWei = parseHexLong(baseFeeHex);
        }
        Integer txCount = null;
        JsonNode transactions = result.path("transactions");
        if (transactions.isArray()) {
            txCount = transactions.size();
        }
        return new BlockInfo(blockNumber, blockHash, parentHash, txCount, gasPriceWei, blockTimestamp);
    }

    private JsonNode sendRpcWithRetry(String httpUrl,
                                      Map<String, String> headers,
                                      long readTimeoutMs,
                                      int maxRetries,
                                      long retryBackoffMs,
                                      long connectTimeoutMs,
                                      String method,
                                      JsonNode params) throws IOException, InterruptedException {
        int attempts = Math.max(0, maxRetries);
        IOException lastIo = null;
        for (int attempt = 0; attempt <= attempts; attempt++) {
            try {
                return sendRpcOnce(httpUrl, headers, readTimeoutMs, connectTimeoutMs, method, params);
            } catch (HttpStatusException statusEx) {
                if (!shouldRetryStatus(statusEx.getStatusCode()) || attempt == attempts) {
                    throw statusEx;
                }
                sleepBackoff(retryBackoffMs, attempt);
            } catch (IOException | InterruptedException ioEx) {
                lastIo = ioEx instanceof IOException ? (IOException) ioEx : new IOException(ioEx);
                if (attempt == attempts) {
                    throw ioEx;
                }
                sleepBackoff(retryBackoffMs, attempt);
            }
        }
        if (lastIo != null) {
            throw lastIo;
        }
        throw new IOException("RPC call failed after retries");
    }

    private void sleepBackoff(long backoffMs, int attempt) throws InterruptedException {
        long delay = Math.max(0, backoffMs) * (attempt + 1);
        if (delay > 0) {
            Thread.sleep(delay);
        }
    }

    private boolean shouldRetryStatus(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private JsonNode sendRpcOnce(String httpUrl,
                                 Map<String, String> headers,
                                 long readTimeoutMs,
                                 long connectTimeoutMs,
                                 String method,
                                 JsonNode params) throws IOException, InterruptedException {
        JsonNode body = mapper.createObjectNode()
                .put("jsonrpc", JSONRPC_VERSION)
                .put("id", 1)
                .put("method", method)
                .set("params", params);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(httpUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (readTimeoutMs > 0) {
            builder.timeout(Duration.ofMillis(readTimeoutMs));
        }
        headers.forEach(builder::header);
        HttpClient client = getHttpClient(connectTimeoutMs);
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new HttpStatusException(response.statusCode(), httpUrl);
        }
        return mapper.readTree(response.body());
    }

    private Long parseHexLong(String hex) {
        if (hex == null) {
            return null;
        }
        String normalized = hex.startsWith("0x") ? hex.substring(2) : hex;
        if (normalized.isBlank()) {
            return null;
        }
        return new BigInteger(normalized, 16).longValue();
    }

    private class WsListener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();
        private final NodeDefinition node;
        private final NodeState state;

        private WsListener(NodeDefinition node, NodeState state) {
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
            recordFailure(node, MetricSource.WS, error.getMessage());
            state.lastWsConnectedAt = null;
            state.lastWsMessageReceivedAt = null;
            state.lastWsPingSentAt = null;
            state.lastWsPongReceivedAt = null;
            state.webSocketRef.set(null);
            WebSocket.Listener.super.onError(webSocket, error);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            WsConnectionTracker tracker = nodeRegistry.getWsTracker(node.key());
            if (tracker != null) {
                tracker.onDisconnect();
            }
            state.lastWsConnectedAt = null;
            state.lastWsMessageReceivedAt = null;
            state.lastWsPingSentAt = null;
            state.lastWsPongReceivedAt = null;
            state.webSocketRef.set(null);
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        private void handleWsMessage(String payload) {
            try {
                JsonNode root = mapper.readTree(payload);
                if (root.has("method") && "eth_subscription".equals(root.get("method").asText())) {
                    JsonNode result = root.path("params").path("result");
                    String blockNumberHex = result.path("number").asText(null);
                    String blockHash = result.path("hash").asText(null);
                    String parentHash = result.path("parentHash").asText(null);
                    Instant blockTimestamp = null;
                    String tsHex = result.path("timestamp").asText(null);
                    if (tsHex != null && !tsHex.isBlank()) {
                        Long tsSeconds = parseHexLong(tsHex);
                        if (tsSeconds != null) {
                            blockTimestamp = Instant.ofEpochSecond(tsSeconds);
                        }
                    }
                    Long blockNumber = blockNumberHex == null ? null : parseHexLong(blockNumberHex);
                    
                    // Calculate head delay: now - block timestamp
                    Instant now = Instant.now();
                    
                    // Track that we received a WS event
                    state.lastWsEventReceivedAt = now;
                    state.wsNewHeadCount++;
                    
                    Long headDelayMs = null;
                    if (blockTimestamp != null) {
                        headDelayMs = Duration.between(blockTimestamp, now).toMillis();
                        state.lastWsBlockTimestamp = blockTimestamp;
                    }

                    MetricSample sample = new MetricSample(
                            now,
                            MetricSource.WS,
                            true,
                            -1,
                            blockNumber,
                            blockTimestamp,
                            blockHash,
                            parentHash,
                            null,
                            null,
                            null,
                            headDelayMs,
                            null,
                            null);
                    store.addSample(node.key(), sample);

                    WsConnectionTracker tracker = nodeRegistry.getWsTracker(node.key());
                    if (tracker != null) {
                        tracker.clearLastError();
                    }

                    List<AnomalyEvent> anomalies = detector.detect(
                            node.key(),
                            sample,
                            node.anomalyDelayMs(),
                            state.lastWsBlockNumber,
                            state.lastWsBlockHash);
                    for (AnomalyEvent anomaly : anomalies) {
                        store.addAnomaly(node.key(), anomaly);
                    }

                    state.lastWsBlockNumber = blockNumber;
                    if (blockHash != null) {
                        state.lastWsBlockHash = blockHash;
                    }
                }
            } catch (IOException | RuntimeException ex) {
                logger.error("WebSocket message handling failure ({}): {}", node.name(), ex.getMessage(), ex);
                recordFailure(node, MetricSource.WS, ex.getMessage());
            }
        }
    }

    private record BlockInfo(Long blockNumber, String blockHash, String parentHash, Integer transactionCount, Long gasPriceWei, Instant blockTimestamp) {
    }

    private static class HttpStatusException extends IOException {
        private final int statusCode;

        private HttpStatusException(int statusCode, String url) {
            super("HTTP " + statusCode + " from " + url);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }

    static class NodeState {
        final AtomicReference<WebSocket> webSocketRef = new AtomicReference<>();
        long lastPollEpochMs = 0;
        long lastWsAttemptEpochMs = 0;
        Long lastHttpBlockNumber;
        String lastHttpBlockHash;
        Long lastWsBlockNumber;
        String lastWsBlockHash;
        Long lastSafeBlockNumber;
        String lastSafeBlockHash;
        Long lastFinalizedBlockNumber;
        String lastFinalizedBlockHash;
        Instant lastWsBlockTimestamp;
        Instant lastWsEventReceivedAt;  // Timestamp when last WS newHeads event was received
        Instant lastWsConnectedAt;
        Instant lastWsMessageReceivedAt;
        Instant lastWsPingSentAt;
        Instant lastWsPongReceivedAt;
        int wsNewHeadCount = 0;
        int pollCounter = 0;
        String lastHttpError;
        String lastWsError;
    }

    private static class MajorityCounter {
        int count = 0;
        int wsFreshCount = 0;

        void increment(boolean wsFresh) {
            count++;
            if (wsFresh) {
                wsFreshCount++;
            }
        }
    }

    private record ReferenceCandidate(String nodeKey, Long blockNumber, String blockHash, boolean wsFresh) {
    }

    private record ReferenceKey(Long blockNumber, String blockHash) {
    }

    private record ReferenceState(Long headNumber, String headHash, Instant fetchedAt) {
    }
}

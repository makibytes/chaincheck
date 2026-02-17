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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.makibytes.chaincheck.config.ChainCheckProperties;
import de.makibytes.chaincheck.model.AnomalyEvent;
import de.makibytes.chaincheck.model.AnomalyType;
import de.makibytes.chaincheck.model.MetricSample;
import de.makibytes.chaincheck.model.MetricSource;
import de.makibytes.chaincheck.monitor.NodeRegistry.NodeDefinition;
import de.makibytes.chaincheck.reference.block.ReferenceBlocks.Confidence;
import de.makibytes.chaincheck.store.InMemoryMetricsStore;

public class HttpMonitorService {

    private static final String JSONRPC_VERSION = "2.0";
    private static final String CONNECT_ERROR_HOST_DOWN = "Connect error: Host is down";
    private static final Logger logger = LoggerFactory.getLogger(HttpMonitorService.class);

    private final RpcMonitorService monitor;
    private final NodeRegistry nodeRegistry;
    private final InMemoryMetricsStore store;
    private final AnomalyDetector detector;
    private final ChainCheckProperties properties;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Map<Long, HttpClient> httpClients = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, RpcMonitorService.NodeState> nodeStates;

    public HttpMonitorService(RpcMonitorService monitor,
                              NodeRegistry nodeRegistry,
                              InMemoryMetricsStore store,
                              AnomalyDetector detector,
                              ChainCheckProperties properties,
                              Map<String, RpcMonitorService.NodeState> nodeStates) {
        this.monitor = monitor;
        this.nodeRegistry = nodeRegistry;
        this.store = store;
        this.detector = detector;
        this.properties = properties;
        this.nodeStates = nodeStates;
    }

    public void pollNodes() {
        if (nodeRegistry == null) {
            return;
        }
        long now = System.currentTimeMillis();
        monitor.refreshReferenceFromNodes();
        for (NodeDefinition node : nodeRegistry.getNodes()) {
            if (node.http() == null || node.http().isBlank()) {
                continue;
            }
            if (!monitor.shouldPollExecutionHttp(node.key())) {
                continue;
            }
            RpcMonitorService.NodeState state = nodeStates.computeIfAbsent(node.key(), key -> new RpcMonitorService.NodeState());
            if (now - state.lastPollEpochMs < node.pollIntervalMs()) {
                continue;
            }
            state.lastPollEpochMs = now;
            executor.submit(() -> pollHttp(node, state));
        }
    }

    private void pollHttp(NodeDefinition node, RpcMonitorService.NodeState state) {
        Instant timestamp = Instant.now();
        long startNanos = System.nanoTime();
        try {
            Long blockNumber = fetchBlockNumber(node);
            if (blockNumber == null) {
                monitor.recordFailure(node, MetricSource.HTTP, "Could not fetch block number");
                return;
            }

            // Detect dead websocket connections using event timestamp
            checkWebSocketHealth(node, state, blockNumber);

            RpcMonitorService.BlockInfo safeBlock = null;
            RpcMonitorService.BlockInfo finalizedBlock = null;
            RpcMonitorService.BlockInfo latestBlock = null;
            boolean safeEnabled = node.safeBlocksEnabled();
            boolean finalizedEnabled = node.finalizedBlocksEnabled();
            if (node.ws() == null || node.ws().isBlank()) {
                if (safeEnabled) {
                    safeBlock = fetchBlockByTag(node, "safe");
                    if (safeBlock == null) {
                        monitor.recordFailure(node, MetricSource.HTTP, "safe block not found");
                        return;
                    }
                    state.lastSafeBlockNumber = safeBlock.blockNumber();
                    state.lastSafeBlockHash = safeBlock.blockHash();
                    monitor.maybeCompareToReference(node, "safe", safeBlock);
                    monitor.recordBlock(node.key(), safeBlock.blockNumber(), safeBlock.blockHash(), Confidence.SAFE);
                }

                if (shouldFetchLatest(state, timestamp, node.pollIntervalMs())) {
                    state.lastLatestFetchAt = timestamp;
                    latestBlock = fetchBlockByTag(node, "latest");
                    if (latestBlock != null) {
                        handleLatestBlock(node, state, latestBlock);
                    }
                }

                if (finalizedEnabled) {
                    finalizedBlock = fetchBlockByTag(node, "finalized");
                    if (finalizedBlock == null) {
                        monitor.recordFailure(node, MetricSource.HTTP, "finalized block not found");
                        return;
                    }
                    recordFinalizedReorgIfNeeded(node, state, finalizedBlock, timestamp);
                    state.lastFinalizedBlockNumber = finalizedBlock.blockNumber();
                    state.lastFinalizedBlockHash = finalizedBlock.blockHash();
                    state.lastFinalizedFetchAt = timestamp;
                    trackFinalizedChainAndCloseReorg(node, state, finalizedBlock);
                    monitor.maybeCompareToReference(node, "finalized", finalizedBlock);
                    monitor.recordBlock(node.key(), finalizedBlock.blockNumber(), finalizedBlock.blockHash(), Confidence.FINALIZED);
                }
            } else {
                String blockTag;
                if (safeEnabled && finalizedEnabled) {
                    state.pollCounter++;
                    blockTag = (state.pollCounter % 2 == 1) ? "safe" : "finalized";
                } else if (safeEnabled) {
                    blockTag = "safe";
                } else if (finalizedEnabled) {
                    blockTag = "finalized";
                } else {
                    blockTag = "latest";
                }

                logger.debug("HTTP poll ({}) pollCounter={} blockTag={}", node.name(), state.pollCounter, blockTag);
                RpcMonitorService.BlockInfo checkpointBlock = fetchBlockByTag(node, blockTag);
                if (checkpointBlock == null) {
                    monitor.recordFailure(node, MetricSource.HTTP, blockTag + " block not found");
                    return;
                }
                if ("safe".equals(blockTag)) {
                    logger.debug("HTTP poll ({}) safe block: number={} hash={} timestamp={}",
                            node.name(), checkpointBlock.blockNumber(), checkpointBlock.blockHash(), checkpointBlock.blockTimestamp());
                }

                if ("safe".equals(blockTag)) {
                    state.lastSafeBlockNumber = checkpointBlock.blockNumber();
                    state.lastSafeBlockHash = checkpointBlock.blockHash();
                } else if ("finalized".equals(blockTag)) {
                    recordFinalizedReorgIfNeeded(node, state, checkpointBlock, timestamp);
                    state.lastFinalizedBlockNumber = checkpointBlock.blockNumber();
                    state.lastFinalizedBlockHash = checkpointBlock.blockHash();
                    trackFinalizedChainAndCloseReorg(node, state, checkpointBlock);
                } else {
                    latestBlock = checkpointBlock;
                }

                if (!"latest".equals(blockTag)) {
                    monitor.maybeCompareToReference(node, blockTag, checkpointBlock);
                }
                Confidence conf = "safe".equals(blockTag)
                        ? Confidence.SAFE
                        : ("finalized".equals(blockTag) ? Confidence.FINALIZED : Confidence.NEW);
                monitor.recordBlock(node.key(), checkpointBlock.blockNumber(), checkpointBlock.blockHash(), conf);
                if ("safe".equals(blockTag)) {
                    safeBlock = checkpointBlock;
                } else if ("finalized".equals(blockTag)) {
                    finalizedBlock = checkpointBlock;
                }
            }

            // Successfully fetched all data - this counts as a success sample for HTTP
            monitor.checkAndCloseAnomaly(node, null, MetricSource.HTTP);

            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;

            HttpConnectionTracker tracker = nodeRegistry.getHttpTracker(node.key());
            if (tracker != null) {
                tracker.onSuccess();
            }

            Long headDelayMs = null;
            Long safeDelayMs = null;
            Long finalizedDelayMs = null;

            boolean wsDataFresh = state.lastWsBlockTimestamp != null
                    && Duration.between(state.lastWsBlockTimestamp, timestamp).toSeconds() < RpcMonitorService.WS_FRESH_SECONDS;

            if (wsDataFresh) {
                headDelayMs = Duration.between(state.lastWsBlockTimestamp, timestamp).toMillis();
            }

            if (safeBlock != null) {
                Instant safeObservedAt = monitor.getReferenceObservedAt(Confidence.SAFE,
                        safeBlock.blockNumber(), safeBlock.blockHash());
                Instant safeBaseline = safeObservedAt != null ? safeObservedAt : safeBlock.blockTimestamp();
                if (safeBaseline != null) {
                    long safeDelay = Duration.between(safeBaseline, timestamp).toMillis();
                    if (safeDelay >= 0) {
                        safeDelayMs = safeDelay;
                    }
                }
            }

            if (finalizedBlock != null) {
                Instant finalizedObservedAt = monitor.getReferenceObservedAt(Confidence.FINALIZED,
                        finalizedBlock.blockNumber(), finalizedBlock.blockHash());
                Instant finalizedBaseline = finalizedObservedAt != null ? finalizedObservedAt : finalizedBlock.blockTimestamp();
                if (finalizedBaseline != null) {
                    long checkpointDelay = Duration.between(finalizedBaseline, timestamp).toMillis();
                    if (checkpointDelay >= 0) {
                        finalizedDelayMs = checkpointDelay;
                    }
                }
            }

            if (safeDelayMs != null || finalizedDelayMs != null) {
                logger.debug("HTTP poll ({}) delays: safeDelayMs={} finalizedDelayMs={} blockHash={}",
                        node.name(), safeDelayMs, finalizedDelayMs,
                        (safeBlock != null ? safeBlock.blockHash() : (finalizedBlock != null ? finalizedBlock.blockHash() : null)));
            }
            RpcMonitorService.BlockInfo metadataBlock = finalizedBlock != null
                    ? finalizedBlock
                    : (safeBlock != null ? safeBlock : latestBlock);
            state.lastHttpBlockNumber = blockNumber;
            state.lastHttpBlockHash = metadataBlock == null ? null : metadataBlock.blockHash();

            if (monitor.isWarmupComplete()) {
                MetricSample.Builder sampleBuilder = MetricSample.builder(timestamp, MetricSource.HTTP)
                        .success(true)
                        .latencyMs(latencyMs)
                        .blockNumber(blockNumber)
                        .headDelayMs(headDelayMs)
                        .safeDelayMs(safeDelayMs)
                        .finalizedDelayMs(finalizedDelayMs);
                if (metadataBlock != null) {
                    sampleBuilder.blockTimestamp(metadataBlock.blockTimestamp())
                            .blockHash(metadataBlock.blockHash())
                            .parentHash(metadataBlock.parentHash())
                            .transactionCount(metadataBlock.transactionCount())
                            .gasPriceWei(metadataBlock.gasPriceWei());
                }
                store.addSample(node.key(), sampleBuilder.build());
            }
        } catch (HttpStatusException ex) {
            logger.error("HTTP RPC failure ({} / http): status {} ({})", node.name(), ex.getStatusCode(), ex.getMessage());
            monitor.recordFailure(node, MetricSource.HTTP, monitor.classifyError(ex));
        } catch (IOException | InterruptedException ex) {
            monitor.recordFailure(node, MetricSource.HTTP, classifyHttpErrorForNode(state, ex));
        } catch (RuntimeException ex) {
            logger.error("HTTP RPC failure ({}): {}", node.name(), ex.getMessage());
            monitor.recordFailure(node, MetricSource.HTTP, ex.getMessage());
        }
    }

    HttpClient getHttpClient(long connectTimeoutMs) {
        long effectiveTimeout = connectTimeoutMs > 0 ? connectTimeoutMs : properties.getDefaults().getConnectTimeoutMs();
        return httpClients.computeIfAbsent(effectiveTimeout, timeout -> HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1, timeout)))
                .build());
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
        return EthHex.parseLong(result.asText());
    }

    private RpcMonitorService.BlockInfo fetchBlockByTag(NodeDefinition node, String blockTag) throws IOException, InterruptedException {
        return fetchBlockByTag(node.http(), node.readTimeoutMs(), node.headers(), node.maxRetries(), node.retryBackoffMs(), node.connectTimeoutMs(), blockTag);
    }

    RpcMonitorService.BlockInfo fetchBlockByNumber(NodeDefinition node, long blockNumber) throws IOException, InterruptedException {
        String hex = formatBlockNumberHex(blockNumber);
        return fetchBlockByTag(node.http(), node.readTimeoutMs(), node.headers(), node.maxRetries(), node.retryBackoffMs(), node.connectTimeoutMs(), hex);
    }

    private void checkWebSocketHealth(NodeDefinition node, RpcMonitorService.NodeState state, Long blockNumber) {
        if (node.ws() == null || node.ws().isBlank()) {
            return;
        }
        if (state.webSocketRef.get() == null) {
            return;
        }
        Long previousHttpBlock = state.lastHttpBlockNumber;
        Instant lastWsEvent = state.lastWsEventReceivedAt;

        if (previousHttpBlock != null && blockNumber > previousHttpBlock) {
            if (lastWsEvent == null) {
                monitor.recordFailure(node, MetricSource.WS, monitor.wsNoNewHeadsSinceConnectionMessage());
            } else {
                long secondsSinceLastEvent = Duration.between(lastWsEvent, Instant.now()).toSeconds();
                if (secondsSinceLastEvent > RpcMonitorService.WS_DEAD_SECONDS) {
                    monitor.recordFailure(node, MetricSource.WS, monitor.wsNoNewHeadsForSecondsMessage(secondsSinceLastEvent));
                    monitor.closeWebSocket(node, state, "stale subscription");
                } else {
                    monitor.checkAndCloseAnomaly(node, null, MetricSource.WS);
                }
            }
        }
    }

    private RpcMonitorService.BlockInfo fetchBlockByTag(String httpUrl,
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
        return EthHex.parseBlockFields(response.get("result"));
    }

    private String formatBlockNumberHex(long blockNumber) {
        return "0x" + Long.toHexString(blockNumber);
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
                if (isHostDownConnectError(ioEx)) {
                    throw ioEx;
                }
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

    private String classifyHttpErrorForNode(RpcMonitorService.NodeState state, Throwable error) {
        if (isHostDownConnectError(error)) {
            return CONNECT_ERROR_HOST_DOWN;
        }
        if (isTimeoutError(error) && isHostDownMessage(state != null ? state.lastHttpError : null)) {
            return CONNECT_ERROR_HOST_DOWN;
        }
        return monitor.classifyError(error);
    }

    private boolean isHostDownConnectError(Throwable error) {
        return error != null && isHostDownMessage(error.getMessage());
    }

    private boolean isTimeoutError(Throwable error) {
        if (error == null) {
            return false;
        }
        if (error instanceof HttpTimeoutException) {
            return true;
        }
        String message = error.getMessage();
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("timeout") || lower.contains("timed out");
    }

    private boolean isHostDownMessage(String message) {
        if (message == null) {
            return false;
        }
        return message.toLowerCase().contains("host is down");
    }

    private void handleLatestBlock(NodeDefinition node, RpcMonitorService.NodeState state, RpcMonitorService.BlockInfo latestBlock) {
        Instant now = Instant.now();
        state.lastWsEventReceivedAt = now;
        state.wsNewHeadCount++;

        Long headDelayMs = null;
        Instant referenceHeadObservedAt = monitor.getReferenceObservedAt(Confidence.NEW,
                latestBlock.blockNumber(), latestBlock.blockHash());
        if (referenceHeadObservedAt != null) {
            headDelayMs = Duration.between(referenceHeadObservedAt, now).toMillis();
        } else if (latestBlock.blockTimestamp() != null) {
            headDelayMs = Duration.between(latestBlock.blockTimestamp(), now).toMillis();
            state.lastWsBlockTimestamp = latestBlock.blockTimestamp();
        }

        MetricSample sample = MetricSample.builder(now, MetricSource.HTTP)
                .success(true)
                .blockNumber(latestBlock.blockNumber())
                .blockTimestamp(latestBlock.blockTimestamp())
                .blockHash(latestBlock.blockHash())
                .parentHash(latestBlock.parentHash())
                .transactionCount(latestBlock.transactionCount())
                .gasPriceWei(latestBlock.gasPriceWei())
                .headDelayMs(headDelayMs)
                .build();
        if (monitor.isWarmupComplete()) {
            store.addSample(node.key(), sample);
        }

        if (latestBlock.blockNumber() != null && latestBlock.blockHash() != null) {
            monitor.recordBlock(node.key(), latestBlock.blockNumber(), latestBlock.blockHash(), Confidence.NEW);
        }

        if (monitor.isWarmupComplete()) {
            List<AnomalyEvent> anomalies = detector.detect(
                    node.key(),
                    sample,
                    node.anomalyDelayMs(),
                    null,
                    null,
                    sample.getBlockNumber());
            for (AnomalyEvent anomaly : anomalies) {
                store.addAnomaly(node.key(), anomaly);
            }
        }
    }

    private void recordFinalizedReorgIfNeeded(NodeDefinition node,
                                              RpcMonitorService.NodeState state,
                                              RpcMonitorService.BlockInfo finalizedBlock,
                                              Instant timestamp) {
        if (finalizedBlock == null) {
            return;
        }
        Long previousNumber = state.lastFinalizedBlockNumber;
        String previousHash = state.lastFinalizedBlockHash;
        Long currentNumber = finalizedBlock.blockNumber();
        String currentHash = finalizedBlock.blockHash();
        if (previousNumber == null || currentNumber == null || !previousNumber.equals(currentNumber)) {
            return;
        }
        if (previousHash == null || currentHash == null || previousHash.equals(currentHash)) {
            return;
        }

        MetricSample sample = MetricSample.builder(timestamp, MetricSource.HTTP)
                .success(true)
                .latencyMs(0)
                .blockNumber(currentNumber)
                .blockTimestamp(finalizedBlock.blockTimestamp())
                .blockHash(currentHash)
                .parentHash(finalizedBlock.parentHash())
                .transactionCount(finalizedBlock.transactionCount())
                .gasPriceWei(finalizedBlock.gasPriceWei())
                .build();
        if (monitor.isWarmupComplete()) {
            List<AnomalyEvent> anomalies = detector.detect(
                    node.key(),
                    sample,
                    node.anomalyDelayMs(),
                    previousNumber,
                    previousHash,
                    sample.getBlockNumber());
            for (AnomalyEvent anomaly : anomalies) {
                store.addAnomaly(node.key(), anomaly);
            }
        }
    }

    private boolean shouldFetchLatest(RpcMonitorService.NodeState state, Instant now, long pollIntervalMs) {
        if (state.lastLatestFetchAt == null) {
            return true;
        }
        long minDelayMs = Math.max(1, pollIntervalMs);
        return Duration.between(state.lastLatestFetchAt, now).toMillis() >= minDelayMs;
    }

    private void trackFinalizedChainAndCloseReorg(NodeDefinition node,
                                                  RpcMonitorService.NodeState state,
                                                  RpcMonitorService.BlockInfo finalizedBlock) {
        if (finalizedBlock == null || finalizedBlock.blockNumber() == null || finalizedBlock.blockHash() == null) {
            return;
        }
        if (!state.finalizedHistory.isEmpty()) {
            RpcMonitorService.BlockInfo last = state.finalizedHistory.peekLast();
            if (last != null
                    && last.blockNumber() != null
                    && last.blockHash() != null
                    && last.blockNumber().equals(finalizedBlock.blockNumber())
                    && last.blockHash().equals(finalizedBlock.blockHash())) {
                return;
            }
        }

        state.finalizedHistory.addLast(finalizedBlock);
        while (state.finalizedHistory.size() > 3) {
            state.finalizedHistory.removeFirst();
        }

        if (state.finalizedHistory.size() < 3) {
            return;
        }

        RpcMonitorService.BlockInfo first = state.finalizedHistory.pollFirst();
        RpcMonitorService.BlockInfo second = state.finalizedHistory.pollFirst();
        RpcMonitorService.BlockInfo third = state.finalizedHistory.pollFirst();
        state.finalizedHistory.addLast(first);
        state.finalizedHistory.addLast(second);
        state.finalizedHistory.addLast(third);

        if (first == null || second == null || third == null) {
            return;
        }

        boolean chainLink = second.parentHash() != null
                && third.parentHash() != null
                && second.parentHash().equals(first.blockHash())
                && third.parentHash().equals(second.blockHash());
        if (chainLink) {
            store.closeLastAnomaly(node.key(), MetricSource.HTTP, AnomalyType.REORG);
        }
    }

    static class HttpStatusException extends IOException {
        private final int statusCode;

        private HttpStatusException(int statusCode, String url) {
            super("HTTP " + statusCode + " from " + url);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }
}

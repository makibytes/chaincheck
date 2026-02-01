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
import de.makibytes.chaincheck.store.InMemoryMetricsStore;

public class HttpMonitorService {

    private static final String JSONRPC_VERSION = "2.0";
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
            if (node.ws() != null && !node.ws().isBlank()) {
                if (state.webSocketRef.get() != null) {
                    Long previousHttpBlock = state.lastHttpBlockNumber;
                    Instant lastWsEvent = state.lastWsEventReceivedAt;

                    // If HTTP block advanced (meaning new blocks are being produced)
                    if (previousHttpBlock != null && blockNumber > previousHttpBlock) {
                        // Check if WS is receiving events
                        if (lastWsEvent == null) {
                            // Never received any WS event - dead from beginning
                            monitor.recordFailure(node, MetricSource.WS, monitor.wsNoNewHeadsSinceConnectionMessage());
                        } else {
                            // Check if last WS event is too old
                            long secondsSinceLastEvent = Duration.between(lastWsEvent, Instant.now()).toSeconds();
                            if (secondsSinceLastEvent > RpcMonitorService.WS_DEAD_SECONDS) {
                                monitor.recordFailure(node, MetricSource.WS, monitor.wsNoNewHeadsForSecondsMessage(secondsSinceLastEvent));
                                monitor.closeWebSocket(node, state, "stale subscription");
                            } else {
                                // WS is healthy (receiving events recently), treat as update to close potential previous errors
                                monitor.checkAndCloseAnomaly(node, null, MetricSource.WS);
                            }
                        }
                    }
                }
            }

            RpcMonitorService.BlockInfo safeBlock = null;
            RpcMonitorService.BlockInfo finalizedBlock = null;
            if (node.ws() == null || node.ws().isBlank()) {
                if (node.safeBlocksEnabled()) {
                    safeBlock = fetchBlockByTag(node, "safe");
                    if (safeBlock == null) {
                        monitor.recordFailure(node, MetricSource.HTTP, "safe block not found");
                        return;
                    }
                    state.lastSafeBlockNumber = safeBlock.blockNumber();
                    state.lastSafeBlockHash = safeBlock.blockHash();
                    monitor.maybeCompareToReference(node, "safe", safeBlock);
                }

                if (shouldFetchLatest(state, timestamp, node.pollIntervalMs())) {
                    state.lastLatestFetchAt = timestamp;
                    RpcMonitorService.BlockInfo latestBlock = fetchBlockByTag(node, "latest");
                    if (latestBlock != null) {
                        handleLatestBlock(node, state, latestBlock);
                    }
                }

                finalizedBlock = fetchBlockByTag(node, "finalized");
                if (finalizedBlock == null) {
                    monitor.recordFailure(node, MetricSource.HTTP, "finalized block not found");
                    return;
                }
                recordFinalizedReorgIfNeeded(node, state, finalizedBlock, timestamp);
                state.lastFinalizedBlockNumber = finalizedBlock.blockNumber();
                state.lastFinalizedBlockHash = finalizedBlock.blockHash();
                state.lastFinalizedFetchAt = timestamp;
                recordFinalizedChainReorg(node, state, finalizedBlock, timestamp);
                trackFinalizedChainAndCloseReorg(node, state, finalizedBlock);
                monitor.maybeCompareToReference(node, "finalized", finalizedBlock);
            } else {
                String blockTag;
                if (node.safeBlocksEnabled()) {
                    state.pollCounter++;
                    blockTag = (state.pollCounter % 2 == 1) ? "safe" : "finalized";
                } else {
                    blockTag = "finalized";
                }

                RpcMonitorService.BlockInfo checkpointBlock = fetchBlockByTag(node, blockTag);
                if (checkpointBlock == null) {
                    monitor.recordFailure(node, MetricSource.HTTP, blockTag + " block not found");
                    return;
                }

                if ("safe".equals(blockTag)) {
                    state.lastSafeBlockNumber = checkpointBlock.blockNumber();
                    state.lastSafeBlockHash = checkpointBlock.blockHash();
                } else {
                    recordFinalizedReorgIfNeeded(node, state, checkpointBlock, timestamp);
                    state.lastFinalizedBlockNumber = checkpointBlock.blockNumber();
                    state.lastFinalizedBlockHash = checkpointBlock.blockHash();
                    recordFinalizedChainReorg(node, state, checkpointBlock, timestamp);
                    trackFinalizedChainAndCloseReorg(node, state, checkpointBlock);
                }

                monitor.maybeCompareToReference(node, blockTag, checkpointBlock);
                finalizedBlock = checkpointBlock;
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

            if (finalizedBlock != null && finalizedBlock.blockTimestamp() != null) {
                long checkpointDelay = Duration.between(finalizedBlock.blockTimestamp(), timestamp).toMillis();
                if (checkpointDelay >= 0) {
                    finalizedDelayMs = checkpointDelay;
                }
            }

            MetricSample sample = new MetricSample(
                    timestamp,
                    MetricSource.HTTP,
                    true,
                    latencyMs,
                    blockNumber,
                        finalizedBlock == null ? null : finalizedBlock.blockTimestamp(),
                        finalizedBlock == null ? null : finalizedBlock.blockHash(),
                        finalizedBlock == null ? null : finalizedBlock.parentHash(),
                        finalizedBlock == null ? null : finalizedBlock.transactionCount(),
                        finalizedBlock == null ? null : finalizedBlock.gasPriceWei(),
                    null,
                    headDelayMs,
                    safeDelayMs,
                    finalizedDelayMs);
            store.addSample(node.key(), sample);
            state.lastHttpBlockNumber = blockNumber;
                    state.lastHttpBlockHash = finalizedBlock == null ? null : finalizedBlock.blockHash();
        } catch (HttpStatusException ex) {
            logger.error("HTTP RPC failure ({} / http): status {} ({})", node.name(), ex.getStatusCode(), ex.getMessage());
            monitor.recordFailure(node, MetricSource.HTTP, monitor.classifyError(ex));
        } catch (IOException | InterruptedException ex) {
            monitor.recordFailure(node, MetricSource.HTTP, monitor.classifyError(ex));
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
        return parseHexLong(result.asText());
    }

    private RpcMonitorService.BlockInfo fetchBlockByTag(NodeDefinition node, String blockTag) throws IOException, InterruptedException {
        return fetchBlockByTag(node.http(), node.readTimeoutMs(), node.headers(), node.maxRetries(), node.retryBackoffMs(), node.connectTimeoutMs(), blockTag);
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
        return new RpcMonitorService.BlockInfo(blockNumber, blockHash, parentHash, txCount, gasPriceWei, blockTimestamp);
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

    private void handleLatestBlock(NodeDefinition node, RpcMonitorService.NodeState state, RpcMonitorService.BlockInfo latestBlock) {
        Instant now = Instant.now();
        state.lastWsEventReceivedAt = now;
        state.wsNewHeadCount++;

        Long headDelayMs = null;
        if (latestBlock.blockTimestamp() != null) {
            headDelayMs = Duration.between(latestBlock.blockTimestamp(), now).toMillis();
            state.lastWsBlockTimestamp = latestBlock.blockTimestamp();
        }

        MetricSample sample = new MetricSample(
            now,
            MetricSource.HTTP,
                true,
                -1,
                latestBlock.blockNumber(),
                latestBlock.blockTimestamp(),
                latestBlock.blockHash(),
                latestBlock.parentHash(),
                latestBlock.transactionCount(),
                latestBlock.gasPriceWei(),
                null,
                headDelayMs,
                null,
                null);
        store.addSample(node.key(), sample);

        List<AnomalyEvent> anomalies = detector.detect(
                node.key(),
                sample,
                node.anomalyDelayMs(),
                state.lastWsBlockNumber,
                state.lastWsBlockHash);
        for (AnomalyEvent anomaly : anomalies) {
            store.addAnomaly(node.key(), anomaly);
        }

        state.lastWsBlockNumber = latestBlock.blockNumber();
        if (latestBlock.blockHash() != null) {
            state.lastWsBlockHash = latestBlock.blockHash();
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

        MetricSample sample = new MetricSample(
                timestamp,
                MetricSource.HTTP,
                true,
                0,
                currentNumber,
                finalizedBlock.blockTimestamp(),
                currentHash,
                finalizedBlock.parentHash(),
                finalizedBlock.transactionCount(),
                finalizedBlock.gasPriceWei(),
                null,
                null,
                null,
                null);
        List<AnomalyEvent> anomalies = detector.detect(
                node.key(),
                sample,
                node.anomalyDelayMs(),
                previousNumber,
                previousHash);
        for (AnomalyEvent anomaly : anomalies) {
            store.addAnomaly(node.key(), anomaly);
        }
    }

    private boolean shouldFetchLatest(RpcMonitorService.NodeState state, Instant now, long pollIntervalMs) {
        if (state.lastLatestFetchAt == null) {
            return true;
        }
        long minDelayMs = Math.max(1, pollIntervalMs);
        return Duration.between(state.lastLatestFetchAt, now).toMillis() >= minDelayMs;
    }

    private void recordFinalizedChainReorg(NodeDefinition node,
                                           RpcMonitorService.NodeState state,
                                           RpcMonitorService.BlockInfo finalizedBlock,
                                           Instant timestamp) {
        if (finalizedBlock == null || finalizedBlock.blockNumber() == null || finalizedBlock.blockHash() == null) {
            return;
        }
        if (finalizedBlock.blockNumber().equals(state.lastReorgBlockNumber)
                && finalizedBlock.blockHash().equals(state.lastReorgBlockHash)) {
            return;
        }

        ChainSelection selection = selectLatestChain(node, timestamp);
        if (selection == null || selection.tipNumber == null || selection.hashes.isEmpty() || selection.depth < 7) {
            return;
        }
        long windowStart = selection.tipNumber - selection.depth;
        Long blockNumber = finalizedBlock.blockNumber();
        if (blockNumber < windowStart || blockNumber > selection.tipNumber) {
            return;
        }
        if (selection.hashes.contains(finalizedBlock.blockHash())) {
            return;
        }

        String details = "Finalized block hash " + finalizedBlock.blockHash()
                + " not in latest chain (tip " + selection.tipNumber + ")";
        store.addAnomaly(node.key(), detector.reorgFinalized(
                node.key(),
                timestamp,
                MetricSource.HTTP,
                finalizedBlock.blockNumber(),
                finalizedBlock.blockHash(),
                details));
        state.lastReorgBlockNumber = finalizedBlock.blockNumber();
        state.lastReorgBlockHash = finalizedBlock.blockHash();
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

    private ChainSelection selectLatestChain(NodeDefinition node, Instant now) {
        Instant since = now.minus(Duration.ofMinutes(30));
        List<MetricSample> recent = store.getRawSamplesSince(node.key(), since).stream()
                .filter(sample -> sample.getHeadDelayMs() != null)
                .filter(sample -> sample.getBlockHash() != null && sample.getParentHash() != null)
                .filter(sample -> sample.getBlockNumber() != null)
                .toList();
        if (recent.isEmpty()) {
            return null;
        }

        Map<String, ChainNode> nodes = new java.util.HashMap<>();
        for (MetricSample sample : recent) {
            nodes.putIfAbsent(sample.getBlockHash(),
                    new ChainNode(sample.getBlockHash(), sample.getParentHash(), sample.getBlockNumber()));
        }
        if (nodes.isEmpty()) {
            return null;
        }

        ChainSelection best = null;
        for (ChainNode nodeEntry : nodes.values()) {
            ChainSelection candidate = buildChain(nodeEntry, nodes);
            if (best == null || candidate.isBetterThan(best)) {
                best = candidate;
            }
        }
        return best;
    }

    private ChainSelection buildChain(ChainNode start, Map<String, ChainNode> nodes) {
        int maxDepth = 7;
        java.util.Set<String> hashes = new java.util.LinkedHashSet<>();
        ChainNode current = start;
        int depth = 0;
        while (current != null && depth < maxDepth) {
            hashes.add(current.hash);
            depth++;
            current = current.parentHash == null ? null : nodes.get(current.parentHash);
        }
        return new ChainSelection(start.blockNumber, depth, hashes);
    }

    private static class ChainNode {
        private final String hash;
        private final String parentHash;
        private final Long blockNumber;

        private ChainNode(String hash, String parentHash, Long blockNumber) {
            this.hash = hash;
            this.parentHash = parentHash;
            this.blockNumber = blockNumber;
        }
    }

    private static class ChainSelection {
        private final Long tipNumber;
        private final int depth;
        private final java.util.Set<String> hashes;

        private ChainSelection(Long tipNumber, int depth, java.util.Set<String> hashes) {
            this.tipNumber = tipNumber;
            this.depth = depth;
            this.hashes = hashes;
        }

        private boolean isBetterThan(ChainSelection other) {
            if (other == null) {
                return true;
            }
            if (this.depth != other.depth) {
                return this.depth > other.depth;
            }
            if (this.tipNumber == null || other.tipNumber == null) {
                return this.tipNumber != null;
            }
            return this.tipNumber > other.tipNumber;
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

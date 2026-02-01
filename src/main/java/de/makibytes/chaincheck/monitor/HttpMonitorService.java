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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.makibytes.chaincheck.config.ChainCheckProperties;
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
    private final ChainCheckProperties properties;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final Map<Long, HttpClient> httpClients = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, RpcMonitorService.NodeState> nodeStates;

    public HttpMonitorService(RpcMonitorService monitor,
                              NodeRegistry nodeRegistry,
                              InMemoryMetricsStore store,
                              ChainCheckProperties properties,
                              Map<String, RpcMonitorService.NodeState> nodeStates) {
        this.monitor = monitor;
        this.nodeRegistry = nodeRegistry;
        this.store = store;
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
                state.lastFinalizedBlockNumber = checkpointBlock.blockNumber();
                state.lastFinalizedBlockHash = checkpointBlock.blockHash();
            }

            monitor.maybeCompareToReference(node, blockTag, checkpointBlock);

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

            if (checkpointBlock.blockTimestamp() != null) {
                long checkpointDelay = Duration.between(checkpointBlock.blockTimestamp(), timestamp).toMillis();
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

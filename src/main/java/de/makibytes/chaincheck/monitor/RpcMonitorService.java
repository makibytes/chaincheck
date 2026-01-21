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
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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

    private final NodeRegistry nodeRegistry;
    private final InMemoryMetricsStore store;
    private final AnomalyDetector detector;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, NodeState> nodeStates = new ConcurrentHashMap<>();

    public RpcMonitorService(NodeRegistry nodeRegistry,
                             InMemoryMetricsStore store,
                             AnomalyDetector detector) {
        this.nodeRegistry = nodeRegistry;
        this.store = store;
        this.detector = detector;
    }

    @PostConstruct
    public void init() {
        ensureWebSocket();
    }
    @Scheduled(fixedDelay = 250, initialDelay = 500)
    public void pollNodes() {
        long now = System.currentTimeMillis();
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
        Long lastBlock = source == MetricSource.HTTP ? (state != null ? state.lastHttpBlockNumber : null) : (state != null ? state.lastWsBlockNumber : null);
        String lastHash = source == MetricSource.HTTP ? (state != null ? state.lastHttpBlockHash : null) : (state != null ? state.lastWsBlockHash : null);

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

    private boolean areErrorsSame(String error1, String error2) {
        if (java.util.Objects.equals(error1, error2)) {
            return true;
        }
        if (error1 == null || error2 == null) {
            return false;
        }
        // Normalize specific error messages that contain variable counters
        String prefix = "WebSocket not receiving newHeads events";
        if (error1.startsWith(prefix) && error2.startsWith(prefix)) {
            return true;
        }
        // Normalize rate limit errors with variable trace_ids
        if (error1.contains("call rate limit exhausted") && error2.contains("call rate limit exhausted")) {
            return true;
        }
        return false;
    }

    private void checkAndCloseAnomaly(NodeDefinition node, String currentError, MetricSource source) {
        NodeState state = nodeStates.computeIfAbsent(node.key(), key -> new NodeState());
        String previousError = source == MetricSource.HTTP ? state.lastHttpError : state.lastWsError;
        
        // If the result changed significantly (not just a counter update)
        if (!areErrorsSame(currentError, previousError)) {
            // If the previous state was an error, close the related anomaly
            if (previousError != null) {
                store.closeLastAnomaly(node.key(), source);
            }
        }
        
        // Always update state to the latest error message (even if technically "same" type)
        // so that we have the latest counter/details
        if (source == MetricSource.HTTP) {
            state.lastHttpError = currentError;
        } else {
            state.lastWsError = currentError;
        }
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
                            recordFailure(node, MetricSource.WS, "WebSocket not receiving newHeads events (no events since connection)");
                        } else {
                            // Check if last WS event is too old (no events for 10+ seconds)
                            long secondsSinceLastEvent = Duration.between(lastWsEvent, Instant.now()).toSeconds();
                            if (secondsSinceLastEvent > 10) {
                                recordFailure(node, MetricSource.WS, "WebSocket not receiving newHeads events (last event " + secondsSinceLastEvent + "s ago)");
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
            
            // Only calculate delays if WS data is fresh (received within last 30 seconds)
            boolean wsDataFresh = state.lastWsBlockTimestamp != null 
                && Duration.between(state.lastWsBlockTimestamp, Instant.now()).toSeconds() < 30;
            
            if (wsDataFresh) {
                // Head delay: how old is the WS head block?
                headDelayMs = Duration.between(state.lastWsBlockTimestamp, Instant.now()).toMillis();
                
                // Checkpoint delay: how far behind is the checkpoint compared to head?
                if (checkpointBlock.blockTimestamp() != null) {
                    long checkpointDelay = Duration.between(
                        checkpointBlock.blockTimestamp(), 
                        state.lastWsBlockTimestamp
                    ).toMillis();
                    
                    // Only record positive delays (checkpoint must be older than head)
                    if (checkpointDelay >= 0) {
                        if ("safe".equals(blockTag)) {
                            safeDelayMs = checkpointDelay;
                        } else {
                            finalizedDelayMs = checkpointDelay;
                        }
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
            HttpConnectionTracker httpTracker = nodeRegistry.getHttpTracker(node.key());
            if (httpTracker != null) {
                httpTracker.onError(ex);
            }
            recordFailure(node, MetricSource.HTTP, ex.getMessage());
        } catch (IOException | InterruptedException ex) {
            recordFailure(node, MetricSource.HTTP, ex.getMessage());
        } catch (RuntimeException ex) {
            logger.error("HTTP RPC failure ({}): {}", node.name(), ex.getMessage());
            HttpConnectionTracker httpTracker = nodeRegistry.getHttpTracker(node.key());
            if (httpTracker != null) {
                httpTracker.onError(ex);
            }
            recordFailure(node, MetricSource.HTTP, ex.getMessage());
        }
    }

    @Scheduled(fixedDelay = 250, initialDelay = 500)
    public void ensureWebSocket() {
        long now = System.currentTimeMillis();
        for (NodeDefinition node : nodeRegistry.getNodes()) {
            if (node.ws() == null || node.ws().isBlank()) {
                continue;
            }
            NodeState state = nodeStates.computeIfAbsent(node.key(), key -> new NodeState());
            WebSocket existing = state.webSocketRef.get();
            if (existing != null) {
                continue;
            }
            if (now - state.lastWsAttemptEpochMs < node.pollIntervalMs()) {
                continue;
            }
            state.lastWsAttemptEpochMs = now;
            httpClient.newWebSocketBuilder()
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

    private Long fetchBlockNumber(NodeDefinition node) throws IOException, InterruptedException {
        JsonNode response = sendRpc(node.http(), "eth_blockNumber", mapper.createArrayNode());
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
        JsonNode params = mapper.createArrayNode()
                .add(blockTag)
                .add(false);
        JsonNode response = sendRpc(node.http(), "eth_getBlockByNumber", params);
        if (response.has("error")) {
            throw new IOException(response.get("error").toString());
        }
        JsonNode result = response.get("result");
        if (result == null || result.isNull()) {
            return null;
        }
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
        return new BlockInfo(blockHash, parentHash, txCount, gasPriceWei, blockTimestamp);
    }

    private JsonNode sendRpc(String httpUrl, String method, JsonNode params) throws IOException, InterruptedException {
        JsonNode body = mapper.createObjectNode()
                .put("jsonrpc", JSONRPC_VERSION)
                .put("id", 1)
                .put("method", method)
                .set("params", params);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(httpUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
                handleWsMessage(payload);
            }
            return WebSocket.Listener.super.onText(webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            return WebSocket.Listener.super.onBinary(webSocket, data, last);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            logger.error("WebSocket error ({} / ws): {}", node.name(), error.getMessage());
            WsConnectionTracker tracker = nodeRegistry.getWsTracker(node.key());
            if (tracker != null) {
                tracker.onError(error);
            }
            recordFailure(node, MetricSource.WS, error.getMessage());
            state.webSocketRef.set(null);
            WebSocket.Listener.super.onError(webSocket, error);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            WsConnectionTracker tracker = nodeRegistry.getWsTracker(node.key());
            if (tracker != null) {
                tracker.onDisconnect();
            }
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

    private record BlockInfo(String blockHash, String parentHash, Integer transactionCount, Long gasPriceWei, Instant blockTimestamp) {
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

    private static class NodeState {
        final AtomicReference<WebSocket> webSocketRef = new AtomicReference<>();
        long lastPollEpochMs = 0;
        long lastWsAttemptEpochMs = 0;
        Long lastHttpBlockNumber;
        String lastHttpBlockHash;
        Long lastWsBlockNumber;
        String lastWsBlockHash;
        Instant lastWsBlockTimestamp;
        Instant lastWsEventReceivedAt;  // Timestamp when last WS newHeads event was received
        int pollCounter = 0;
        String lastHttpError;
        String lastWsError;
    }
}

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
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

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
import de.makibytes.chaincheck.monitor.ReferenceBlocks.Confidence;
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

                    Instant now = Instant.now();
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
                    monitor.resetWsBackoff(state);

                    WsConnectionTracker tracker = nodeRegistry.getWsTracker(node.key());
                    if (tracker != null) {
                        tracker.clearLastError();
                    }

                    List<AnomalyEvent> anomalies = detector.detect(
                            node.key(),
                            sample,
                            node.anomalyDelayMs(),
                            state.lastWsBlockNumber,
                            state.lastWsBlockHash,
                            state.lastHttpBlockNumber);
                    for (AnomalyEvent anomaly : anomalies) {
                        store.addAnomaly(node.key(), anomaly);
                        // Track block height decreased anomalies for auto-closing
                        if (anomaly.getType() == AnomalyType.REORG && "Block height decreased".equals(anomaly.getMessage())) {
                            state.hasOpenBlockHeightDecreasedAnomaly = true;
                            state.consecutiveIncreasingBlocksAfterDecrease = 0;
                        }
                    }

                    // Handle auto-closing of block height decreased anomalies
                    if (state.hasOpenBlockHeightDecreasedAnomaly && blockNumber != null && state.lastWsBlockNumber != null) {
                        if (blockNumber > state.lastWsBlockNumber) {
                            state.consecutiveIncreasingBlocksAfterDecrease++;
                            if (state.consecutiveIncreasingBlocksAfterDecrease >= 3) {
                                store.closeLastAnomaly(node.key(), MetricSource.WS, AnomalyType.REORG);
                                state.hasOpenBlockHeightDecreasedAnomaly = false;
                                state.consecutiveIncreasingBlocksAfterDecrease = 0;
                            }
                        } else {
                            // Reset counter if block number didn't increase
                            state.consecutiveIncreasingBlocksAfterDecrease = 0;
                        }
                    }

                    state.lastWsBlockNumber = blockNumber;
                    if (blockHash != null) {
                        state.lastWsBlockHash = blockHash;
                    }

                    if (blockNumber != null && blockHash != null) {
                        monitor.recordBlock(node.key(), blockNumber, blockHash, Confidence.NEW);
                    }
                }
            } catch (IOException | RuntimeException ex) {
                logger.error("WebSocket message handling failure ({}): {}", node.name(), ex.getMessage(), ex);
                monitor.recordFailure(node, MetricSource.WS, ex.getMessage());
            }
        }
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
}

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

import java.net.http.HttpTimeoutException;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import de.makibytes.chaincheck.config.ChainCheckProperties;
import de.makibytes.chaincheck.model.AnomalyEvent;
import de.makibytes.chaincheck.model.MetricSample;
import de.makibytes.chaincheck.model.MetricSource;
import de.makibytes.chaincheck.monitor.NodeRegistry.NodeDefinition;
import de.makibytes.chaincheck.store.InMemoryMetricsStore;
import jakarta.annotation.PostConstruct;

@Service
public class RpcMonitorService {

    private static final Logger logger = LoggerFactory.getLogger(RpcMonitorService.class);
    static final long WS_FRESH_SECONDS = 30;
    static final long WS_DEAD_SECONDS = 120;
    static final long WS_PING_INTERVAL_SECONDS = 30;
    private static final long WS_BACKOFF_BASE_SECONDS = 5;
    private static final long WS_BACKOFF_MAX_SECONDS = 120;
    private static final String WS_NO_NEW_HEADS_PREFIX = "WebSocket not receiving newHeads events";

    private final NodeRegistry nodeRegistry;
    private final InMemoryMetricsStore store;
    private final AnomalyDetector detector;
    private final ChainCheckProperties properties;
    private final Map<String, NodeState> nodeStates = new ConcurrentHashMap<>();
    private final AtomicReference<ReferenceState> referenceState = new AtomicReference<>();
    private static final int REFERENCE_SELECTION_WINDOW = 20;
    private static final int REFERENCE_SWITCH_THRESHOLD = 15;
    private final ReferenceSelectionPolicy referenceSelectionPolicy = new ReferenceSelectionPolicy(
            REFERENCE_SELECTION_WINDOW, REFERENCE_SWITCH_THRESHOLD);
    private Long referenceFirstSetAtBlock = null;
    private String currentReferenceNodeKey;
    private final HttpMonitorService httpMonitorService;
    private final WsMonitorService wsMonitorService;

    @Autowired
    public RpcMonitorService(NodeRegistry nodeRegistry,
            InMemoryMetricsStore store,
            AnomalyDetector detector,
            ChainCheckProperties properties) {
        this.nodeRegistry = nodeRegistry;
        this.store = store;
        this.detector = detector;
        this.properties = properties;
        this.httpMonitorService = new HttpMonitorService(this, nodeRegistry, store, properties, nodeStates);
        this.wsMonitorService = new WsMonitorService(this, nodeRegistry, store, detector, properties, nodeStates, httpMonitorService);
    }

    /**
     * Backwards-compatible constructor used by older tests that did not wire
     * properties.
     */
    public RpcMonitorService(NodeRegistry nodeRegistry,
            InMemoryMetricsStore store,
            AnomalyDetector detector) {
        this(nodeRegistry, store, detector, new ChainCheckProperties());
    }

    @PostConstruct
    public void init() {
        wsMonitorService.ensureWebSocket();
    }

    /**
     * Returns the node key of the current reference node, or null if no reference
     * is set.
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
     * Returns whether the given node matches the current reference head, or empty
     * if reference state is unavailable.
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
        httpMonitorService.pollNodes();
    }

    void recordFailure(NodeDefinition node, MetricSource source, String errorMessage) {
        Instant now = Instant.now();
        NodeState state = nodeStates.computeIfAbsent(node.key(), key -> new NodeState());
        if (source == MetricSource.WS && !shouldRecordWsFailureSample(state, now)) {
            setLastError(state, source, errorMessage);
            return;
        }

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
            scheduleWsBackoff(state, now);
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
        state = nodeStates.get(node.key());
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

    boolean areErrorsSame(String error1, String error2) {
        String norm1 = normalizeError(error1);
        String norm2 = normalizeError(error2);
        return java.util.Objects.equals(norm1, norm2);
    }

    String normalizeError(String error) {
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

    String classifyError(Throwable error) {
        if (error == null) {
            return "Unknown error";
        }
        if (error instanceof HttpTimeoutException) {
            return "HTTP timeout: " + error.getMessage();
        }
        if (error instanceof java.net.ConnectException) {
            return "Connect error: " + error.getMessage();
        }
        if (error instanceof HttpMonitorService.HttpStatusException statusEx) {
            return "HTTP " + statusEx.getStatusCode() + ": " + statusEx.getMessage();
        }
        return error.getMessage();
    }

    void checkAndCloseAnomaly(NodeDefinition node, String currentError, MetricSource source) {
        NodeState state = nodeStates.computeIfAbsent(node.key(), key -> new NodeState());
        String previousError = getLastError(state, source);

        // If the result changed significantly (not just a counter update)
        if (!areErrorsSame(currentError, previousError)) {
            // If the previous state was an error, close the related anomaly
            if (previousError != null) {
                store.closeLastAnomaly(node.key(), source);
            }
        }

        // Always update state to the latest error message (even if technically "same"
        // type)
        // so that we have the latest counter/details
        setLastError(state, source, currentError);
    }

    void maybeCompareToReference(NodeDefinition node, String blockTag, BlockInfo checkpointBlock) {
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

        // Skip comparisons until a reference node exists and at least 10 newHead events
        // were observed
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
        if (referenceCheckpointNumber == null || referenceCheckpointHash == null || checkpointNumber == null
                || checkpointBlock.blockHash() == null) {
            return;
        }

        if (referenceCheckpointNumber.equals(checkpointNumber)
                && !referenceCheckpointHash.equals(checkpointBlock.blockHash())) {
            AnomalyEvent anomaly = detector.wrongHead(
                    node.key(),
                    Instant.now(),
                    MetricSource.HTTP,
                    checkpointNumber,
                    checkpointBlock.blockHash(),
                    "Hash mismatch at " + blockTag + " height " + checkpointNumber + " (reference "
                            + referenceCheckpointHash + ")");
            store.addAnomaly(node.key(), anomaly);
        }
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 1000)
    public void ensureWebSocket() {
        wsMonitorService.ensureWebSocket();
    }

    void refreshReferenceFromNodes() {
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
                candidates
                        .add(new ReferenceCandidate(node.key(), state.lastWsBlockNumber, state.lastWsBlockHash, true));
                maxHeight = Math.max(maxHeight, state.lastWsBlockNumber);
                continue;
            }

            if (state.lastHttpBlockNumber != null && state.lastHttpBlockHash != null) {
                candidates.add(
                        new ReferenceCandidate(node.key(), state.lastHttpBlockNumber, state.lastHttpBlockHash, false));
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

    boolean isWsFresh(NodeState state, Instant now) {
        return state.lastWsEventReceivedAt != null
                && Duration.between(state.lastWsEventReceivedAt, now).toSeconds() < WS_FRESH_SECONDS;
    }

    boolean isWsNoNewHeadsError(String error) {
        return error != null && error.startsWith(WS_NO_NEW_HEADS_PREFIX);
    }

    boolean shouldRecordWsFailureSample(NodeState state, Instant now) {
        if (state == null) {
            return true;
        }
        return state.wsNextFailureSampleAt == null || !now.isBefore(state.wsNextFailureSampleAt);
    }

    void scheduleWsBackoff(NodeState state, Instant now) {
        if (state == null) {
            return;
        }
        long nextDelay = state.wsFailureBackoffSeconds <= 0
                ? WS_BACKOFF_BASE_SECONDS
                : Math.min(WS_BACKOFF_MAX_SECONDS, state.wsFailureBackoffSeconds * 2L);
        state.wsFailureBackoffSeconds = nextDelay;
        state.wsNextFailureSampleAt = now.plusSeconds(nextDelay);
        state.wsNextConnectAttemptAt = now.plusSeconds(nextDelay);
    }

    void resetWsBackoff(NodeState state) {
        if (state == null) {
            return;
        }
        state.wsFailureBackoffSeconds = 0;
        state.wsNextFailureSampleAt = null;
        state.wsNextConnectAttemptAt = null;
    }

    void closeWebSocket(NodeDefinition node, NodeState state, String reason) {
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

    String wsNoNewHeadsSinceConnectionMessage() {
        return WS_NO_NEW_HEADS_PREFIX + " (no events since connection)";
    }

    String wsNoNewHeadsForSecondsMessage(long secondsSinceLastEvent) {
        return WS_NO_NEW_HEADS_PREFIX + " (last event " + secondsSinceLastEvent + "s ago)";
    }

    String getLastError(NodeState state, MetricSource source) {
        if (state == null) {
            return null;
        }
        return source == MetricSource.HTTP ? state.lastHttpError : state.lastWsError;
    }

    void setLastError(NodeState state, MetricSource source, String currentError) {
        if (state == null) {
            return;
        }
        if (source == MetricSource.HTTP) {
            state.lastHttpError = currentError;
        } else {
            state.lastWsError = currentError;
        }
    }

    Long getLastBlock(NodeState state, MetricSource source) {
        if (state == null) {
            return null;
        }
        return source == MetricSource.HTTP ? state.lastHttpBlockNumber : state.lastWsBlockNumber;
    }

    String getLastHash(NodeState state, MetricSource source) {
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
                .filter(candidate -> winningKey
                        .equals(new ReferenceKey(candidate.blockNumber(), candidate.blockHash())))
                .sorted((left, right) -> Boolean.compare(right.wsFresh(), left.wsFresh()))
                .findFirst()
                .orElseGet(() -> new ReferenceCandidate("majority", winningKey.blockNumber(), winningKey.blockHash(),
                        winningCounter.wsFreshCount > 0));
    }

    record BlockInfo(Long blockNumber, String blockHash, String parentHash, Integer transactionCount,
            Long gasPriceWei, Instant blockTimestamp) {
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
        Instant lastWsEventReceivedAt; // Timestamp when last WS newHeads event was received
        Instant lastWsConnectedAt;
        Instant lastWsMessageReceivedAt;
        Instant lastWsPingSentAt;
        Instant lastWsPongReceivedAt;
        long wsFailureBackoffSeconds = 0;
        Instant wsNextFailureSampleAt;
        Instant wsNextConnectAttemptAt;
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

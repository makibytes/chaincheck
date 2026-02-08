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
import java.util.ArrayDeque;
import java.util.Deque;
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
import de.makibytes.chaincheck.monitor.ReferenceBlocks.Confidence;
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
    private final BlockVotingService blockVotingService;
    private final NodeScorer nodeScorer;
    private final ReferenceNodeSelector referenceNodeSelector;

    @Autowired
    public RpcMonitorService(NodeRegistry nodeRegistry,
            InMemoryMetricsStore store,
            AnomalyDetector detector,
            ChainCheckProperties properties,
            BlockVotingService blockVotingService,
            NodeScorer nodeScorer,
            ReferenceNodeSelector referenceNodeSelector) {
        this.nodeRegistry = nodeRegistry;
        this.store = store;
        this.detector = detector;
        this.properties = properties;
        this.blockVotingService = blockVotingService;
        this.nodeScorer = nodeScorer;
        this.referenceNodeSelector = referenceNodeSelector;
        this.httpMonitorService = new HttpMonitorService(this, nodeRegistry, store, detector, properties, nodeStates);
        this.wsMonitorService = new WsMonitorService(this, nodeRegistry, store, detector, properties, nodeStates, httpMonitorService);
    }

    /**
     * Backwards-compatible constructor used by older tests that did not wire
     * properties.
     */
    public RpcMonitorService(NodeRegistry nodeRegistry,
            InMemoryMetricsStore store,
            AnomalyDetector detector) {
        this(nodeRegistry, store != null ? store : new InMemoryMetricsStore(), detector, new ChainCheckProperties(), new BlockVotingService(), new NodeScorer(), new ReferenceNodeSelector());
    }

    /**
     * Backwards-compatible constructor used by tests.
     */
    public RpcMonitorService(NodeRegistry nodeRegistry,
            InMemoryMetricsStore store,
            AnomalyDetector detector,
            ChainCheckProperties properties) {
        this(nodeRegistry, store, detector, properties, new BlockVotingService(), new NodeScorer(), new ReferenceNodeSelector());
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
        if (ref == null || ref.headNumber() == null || ref.headHash() == null) {
            return null;
        }

        // Find which node matches the reference block
        for (Map.Entry<String, NodeState> entry : nodeStates.entrySet()) {
            NodeState state = entry.getValue();

            // Check WebSocket head first (preferred)
            if (state.lastWsBlockNumber != null && state.lastWsBlockNumber.equals(ref.headNumber())
                    && state.lastWsBlockHash != null && state.lastWsBlockHash.equals(ref.headHash())) {
                return entry.getKey();
            }

            // Check HTTP head
            if (state.lastHttpBlockNumber != null && state.lastHttpBlockNumber.equals(ref.headNumber())
                    && state.lastHttpBlockHash != null && state.lastHttpBlockHash.equals(ref.headHash())) {
                return entry.getKey();
            }
        }

        return null; // No node currently matches reference
    }

    /**
     * Returns the current reference block number, or null if no reference is set.
     */
    public Long getReferenceBlockNumber() {
        ReferenceState ref = referenceState.get();
        return ref != null ? ref.headNumber() : null;
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
        if (ref == null || ref.headNumber() == null) {
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
        return Optional.of(nodeBlockNumber.equals(ref.headNumber()));
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
                lastHash,
                state != null ? state.lastHttpBlockNumber : null);
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
        if (ref == null || ref.headNumber() == null) {
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

        long lag = ref.headNumber() - blockNumber;

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
                    "Node behind reference: reference head " + ref.headNumber() + " vs node " + blockNumber);
            store.addAnomaly(node.key(), anomaly);
        }

        if (!blockVotingService.getReferenceBlocks().isEstablished()) {
            return;
        }

        if (checkpointBlock == null) {
            return;
        }

        if (checkpointBlock.blockNumber() < ref.headNumber() - 20) {
            return;
        }

        Confidence confidence = "safe".equals(blockTag) ? Confidence.SAFE : Confidence.FINALIZED;
        String referenceCheckpointHash = blockVotingService.getReferenceBlocks().getHash(checkpointBlock.blockNumber(), confidence);

        if (referenceCheckpointHash != null && !referenceCheckpointHash.equals(checkpointBlock.blockHash())) {
            AnomalyEvent anomaly = detector.wrongHead(
                    node.key(),
                    Instant.now(),
                    MetricSource.HTTP,
                    checkpointBlock.blockNumber(),
                    checkpointBlock.blockHash(),
                    "Hash mismatch at " + blockTag + " height " + checkpointBlock.blockNumber() + " (reference "
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
            blockVotingService.getReferenceBlocks().clear();
            return;
        }

        // Clear and collect current blocks
        blockVotingService.clearVotes();
        for (NodeDefinition node : nodes) {
            NodeState state = nodeStates.get(node.key());
            if (state == null) continue;

            if (state.lastHttpBlockNumber != null && state.lastHttpBlockHash != null) {
                blockVotingService.recordBlock(node.key(), state.lastHttpBlockNumber, state.lastHttpBlockHash, Confidence.NEW);
            }
            if (state.lastWsBlockNumber != null && state.lastWsBlockHash != null) {
                blockVotingService.recordBlock(node.key(), state.lastWsBlockNumber, state.lastWsBlockHash, Confidence.NEW);
            }
            if (state.lastSafeBlockNumber != null && state.lastSafeBlockHash != null) {
                blockVotingService.recordBlock(node.key(), state.lastSafeBlockNumber, state.lastSafeBlockHash, Confidence.SAFE);
            }
            if (state.lastFinalizedBlockNumber != null && state.lastFinalizedBlockHash != null) {
                blockVotingService.recordBlock(node.key(), state.lastFinalizedBlockNumber, state.lastFinalizedBlockHash, Confidence.FINALIZED);
            }
        }

        // Save old blocks for comparison
        Map<Long, Map<Confidence, String>> oldBlocks = new HashMap<>();
        for (Map.Entry<Long, Map<Confidence, String>> e : blockVotingService.getReferenceBlocks().getBlocks().entrySet()) {
            oldBlocks.put(e.getKey(), new HashMap<>(e.getValue()));
        }

        // Perform voting
        blockVotingService.performVoting(currentReferenceNodeKey);

        // Penalize for invalidated blocks
        nodeScorer.penalizeForInvalidBlocks(oldBlocks, blockVotingService.getReferenceBlocks(), blockVotingService.getBlockVotes());

        // Award points for correct blocks
        nodeScorer.awardPointsForCorrectBlocks(oldBlocks, blockVotingService.getReferenceBlocks(), blockVotingService.getBlockVotes());

        // Find reference head: highest NEW block
        long referenceHeadNumber = -1;
        String referenceHeadHash = null;
        for (Map.Entry<Long, Map<Confidence, String>> entry : blockVotingService.getReferenceBlocks().getBlocks().entrySet()) {
            long num = entry.getKey();
            String hash = entry.getValue().get(Confidence.NEW);
            if (hash != null && num > referenceHeadNumber) {
                referenceHeadNumber = num;
                referenceHeadHash = hash;
            }
        }

        if (referenceHeadNumber == -1) {
            referenceState.set(null);
            return;
        }

        // Update reference state
        ReferenceState oldRef = referenceState.get();
        Instant now = Instant.now();
        referenceState.set(new ReferenceState(referenceHeadNumber, referenceHeadHash, now));

        if (oldRef == null || !oldRef.headHash.equals(referenceHeadHash)) {
            // Possible reorg, handle later
        }

        // Select reference node
        String oldReferenceNodeKey = currentReferenceNodeKey;
        currentReferenceNodeKey = referenceNodeSelector.selectReferenceNode(nodeStates, blockVotingService.getReferenceBlocks(), store, now, nodeScorer, currentReferenceNodeKey);
        
        if (currentReferenceNodeKey != null) {
            String newNodeName = nodeRegistry.getNode(currentReferenceNodeKey).name();
            if (!currentReferenceNodeKey.equals(oldReferenceNodeKey)) {
                String oldNodeName = oldReferenceNodeKey != null ? nodeRegistry.getNode(oldReferenceNodeKey).name() : "none";
                logger.info("Reference node switched to {}", newNodeName);
            } else {
                logger.debug("Reference node remains {}", newNodeName);
            }
        } else {
            logger.info("No reference node selected");
        }
    }

    boolean isWsFresh(NodeState state, Instant now) {
        return state.webSocketRef.get() != null
                && state.lastWsEventReceivedAt != null
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
        Instant lastFinalizedFetchAt;
        Instant lastLatestFetchAt;
        Long lastReorgBlockNumber;
        String lastReorgBlockHash;
        Deque<BlockInfo> finalizedHistory = new ArrayDeque<>();
        long wsFailureBackoffSeconds = 0;
        Instant wsNextFailureSampleAt;
        Instant wsNextConnectAttemptAt;
        int wsNewHeadCount = 0;
        int pollCounter = 0;
        String lastHttpError;
        String lastWsError;
        boolean hasOpenBlockHeightDecreasedAnomaly = false;
        int consecutiveIncreasingBlocksAfterDecrease = 0;
    }

    void recordBlock(String nodeKey, long blockNumber, String blockHash, Confidence confidence) {
        blockVotingService.recordBlock(nodeKey, blockNumber, blockHash, confidence);
    }

    private void performVoting() {
        blockVotingService.performVoting(currentReferenceNodeKey);
    }

    private void awardPointsForCorrectBlocks(Map<Long, Map<Confidence, String>> oldBlocks) {
        nodeScorer.awardPointsForCorrectBlocks(oldBlocks, blockVotingService.getReferenceBlocks(), blockVotingService.getBlockVotes());
    }

    private void penalizeForInvalidBlocks(Map<Long, Map<Confidence, String>> oldBlocks) {
        nodeScorer.penalizeForInvalidBlocks(oldBlocks, blockVotingService.getReferenceBlocks(), blockVotingService.getBlockVotes());
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

    private record ReferenceState(Long headNumber, String headHash, Instant fetchedAt) {
    }
}

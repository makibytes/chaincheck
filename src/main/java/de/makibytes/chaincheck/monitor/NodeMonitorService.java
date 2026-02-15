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
public class NodeMonitorService {

    private static final Logger logger = LoggerFactory.getLogger(NodeMonitorService.class);
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
    private Long referenceFirstSetAtBlock = null;
    private String currentReferenceNodeKey;
    private final HttpMonitorService httpMonitorService;
    private final WsMonitorService wsMonitorService;
    private final BlockVotingService blockVotingService;
    private final ConsensusNodeService consensusNodeService;
    private final ReferenceNodeCoordinator referenceNodeCoordinator;
    private final ReferenceBlockCoordinator referenceBlockCoordinator;
    private final boolean configuredReferenceMode;
    private final String configuredReferenceNodeKey;
    private Instant lastConfiguredSafePollAt;
    private Instant lastConfiguredFinalizedPollAt;
    private final Instant warmupStartedAt = Instant.now();
    private volatile boolean warmupComplete = false;
    private String lastWarmupWaitingReason;

    @Autowired
    public NodeMonitorService(NodeRegistry nodeRegistry,
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
        this.configuredReferenceMode = properties.getConsensus() != null && properties.getConsensus().hasConfiguredReferenceNode();
        this.configuredReferenceNodeKey = properties.getConsensus() != null ? properties.getConsensus().getNodeKey() : null;
        this.consensusNodeService = new ConsensusNodeService(nodeRegistry, blockVotingService, nodeStates, properties, configuredReferenceNodeKey);
        this.referenceNodeCoordinator = new ReferenceNodeCoordinator(nodeRegistry, referenceNodeSelector, nodeScorer, store);
        this.referenceBlockCoordinator = new ReferenceBlockCoordinator(nodeRegistry, blockVotingService, nodeScorer, store, detector);
        this.httpMonitorService = new HttpMonitorService(this, nodeRegistry, store, detector, properties, nodeStates);
        this.wsMonitorService = new WsMonitorService(this, nodeRegistry, store, detector, properties, nodeStates, httpMonitorService);
    }

    /**
     * Backwards-compatible constructor used by older tests that did not wire
     * properties.
     */
    public NodeMonitorService(NodeRegistry nodeRegistry,
            InMemoryMetricsStore store,
            AnomalyDetector detector) {
        this(nodeRegistry, store != null ? store : new InMemoryMetricsStore(), detector, new ChainCheckProperties(), new BlockVotingService(), new NodeScorer(), new ReferenceNodeSelector());
    }

    /**
     * Backwards-compatible constructor used by tests.
     */
    public NodeMonitorService(NodeRegistry nodeRegistry,
            InMemoryMetricsStore store,
            AnomalyDetector detector,
            ChainCheckProperties properties) {
        this(nodeRegistry, store, detector, properties, new BlockVotingService(), new NodeScorer(), new ReferenceNodeSelector());
    }

    @PostConstruct
    public void init() {
        wsMonitorService.ensureWebSocket();
        if (configuredReferenceMode) {
            consensusNodeService.ensureEventStream();
        }
        logWarmupStart();
        updateWarmupState();
    }

    boolean isWarmupComplete() {
        return warmupComplete;
    }

    /**
     * Returns the node key of the current reference node, or null if no reference
     * is set.
     */
    public String getReferenceNodeKey() {
        if (configuredReferenceMode && configuredReferenceNodeKey != null && !configuredReferenceNodeKey.isBlank()) {
            if (nodeRegistry != null && nodeRegistry.getNode(configuredReferenceNodeKey) != null) {
                return configuredReferenceNodeKey;
            }
        }
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
        if (configuredReferenceMode && configuredReferenceNodeKey != null && !configuredReferenceNodeKey.isBlank()) {
            return Optional.of(configuredReferenceNodeKey.equals(nodeKey));
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

    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    public void refreshConfiguredReference() {
        if (!configuredReferenceMode) {
            return;
        }
        if (!consensusNodeService.isBeaconEnabled()) {
            return;
        }
        consensusNodeService.ensureEventStream();
        Instant now = Instant.now();
        boolean shouldPollSafe = false;
        boolean shouldPollFinalized = false;

        Long finalizedPollIntervalMs = consensusNodeService.getFinalizedPollIntervalMs();
        if (finalizedPollIntervalMs != null
                && (lastConfiguredFinalizedPollAt == null
                || Duration.between(lastConfiguredFinalizedPollAt, now).toMillis() >= finalizedPollIntervalMs)) {
            shouldPollFinalized = true;
            lastConfiguredFinalizedPollAt = now;
        }

        Long safePollIntervalMs = consensusNodeService.getSafePollIntervalMs();
        if (safePollIntervalMs != null) {
            boolean bothCheckpointPollsEnabled = finalizedPollIntervalMs != null;
            if (bothCheckpointPollsEnabled && lastConfiguredSafePollAt == null) {
                Instant finalizedAnchor = shouldPollFinalized ? now : lastConfiguredFinalizedPollAt;
                if (finalizedAnchor != null) {
                    long halfFinalizedIntervalMs = Math.max(250L, finalizedPollIntervalMs / 2L);
                    if (Duration.between(finalizedAnchor, now).toMillis() >= halfFinalizedIntervalMs) {
                        shouldPollSafe = true;
                        lastConfiguredSafePollAt = now;
                    }
                }
            } else if (lastConfiguredSafePollAt == null
                    || Duration.between(lastConfiguredSafePollAt, now).toMillis() >= safePollIntervalMs) {
                shouldPollSafe = true;
                lastConfiguredSafePollAt = now;
            }
        }

        consensusNodeService.refreshCheckpoints(shouldPollSafe, shouldPollFinalized);
        refreshReferenceFromNodes();
        updateWarmupState();
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
            if (!warmupComplete) {
                return;
            }
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
        if (!warmupComplete) {
            return;
        }
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

        if (configuredReferenceMode) {
            if (checkpointBlock == null) {
                return;
            }
            Confidence confidence = "safe".equals(blockTag) ? Confidence.SAFE : Confidence.FINALIZED;
            BeaconReferenceService.ReferenceObservation observation = consensusNodeService.getConfiguredObservation(confidence);
            if (observation == null || observation.blockNumber() == null || observation.blockHash() == null
                    || checkpointBlock.blockNumber() == null || checkpointBlock.blockHash() == null) {
                return;
            }
            if (observation.blockNumber().equals(checkpointBlock.blockNumber())
                    && !observation.blockHash().equalsIgnoreCase(checkpointBlock.blockHash())) {
                AnomalyEvent anomaly = detector.wrongHead(
                        node.key(),
                        Instant.now(),
                        MetricSource.HTTP,
                        checkpointBlock.blockNumber(),
                        checkpointBlock.blockHash(),
                        "Hash mismatch at " + blockTag + " height " + checkpointBlock.blockNumber() + " (reference "
                                + observation.blockHash() + ")");
                store.addAnomaly(node.key(), anomaly);
            }
            return;
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
        if (configuredReferenceMode) {
            ConsensusNodeService.ConfiguredReferenceUpdate update = consensusNodeService.refreshReferenceFromConfiguredSource();
            referenceState.set(update.referenceState());
            currentReferenceNodeKey = update.referenceNodeKey();
            updateWarmupState();
            return;
        }

        List<NodeDefinition> nodes = nodeRegistry.getNodes();
        if (nodes.isEmpty()) {
            referenceState.set(null);
            blockVotingService.getReferenceBlocks().clear();
            return;
        }

        referenceBlockCoordinator.collectVotesFromNodes(nodeStates);
        Map<Long, Map<Confidence, String>> oldBlocks = referenceBlockCoordinator.snapshotOldReferenceBlocks();
        referenceBlockCoordinator.performVotingAndScoring(oldBlocks, currentReferenceNodeKey, Instant.now(), warmupComplete, nodeStates);

        ReferenceBlockVoting.ReferenceHead referenceHead = referenceBlockCoordinator.resolveReferenceHead();
        if (referenceHead == null) {
            referenceState.set(null);
            return;
        }

        Instant now = Instant.now();
        referenceState.set(new ReferenceState(referenceHead.headNumber(), referenceHead.headHash(), now));
        currentReferenceNodeKey = referenceNodeCoordinator.selectReferenceNode(
                nodeStates,
                blockVotingService.getReferenceBlocks(),
                now,
                currentReferenceNodeKey);
        updateWarmupState();
    }

    public boolean hasConfiguredReferenceMode() {
        return configuredReferenceMode;
    }

    boolean shouldPollExecutionHttp(String nodeKey) {
        if (!configuredReferenceMode || configuredReferenceNodeKey == null || configuredReferenceNodeKey.isBlank()) {
            return true;
        }
        return !configuredReferenceNodeKey.equals(nodeKey);
    }

    Instant getReferenceObservedAt(Confidence confidence, Long blockNumber, String blockHash) {
        if (!configuredReferenceMode) {
            return null;
        }
        return consensusNodeService.getReferenceObservedAt(confidence, blockNumber, blockHash);
    }

    public List<MetricSample> getConfiguredReferenceDelaySamplesSince(Instant since) {
        if (!configuredReferenceMode) {
            return List.of();
        }
        return consensusNodeService.getConfiguredReferenceDelaySamplesSince(since);
    }

    private void logWarmupStart() {
        String waiting = currentWarmupWaitingReason();
        logger.info("Warm-up started: {}", waiting == null ? "collecting initial reference data" : "waiting for " + waiting);
    }

    private void updateWarmupState() {
        if (warmupComplete) {
            return;
        }
        String waiting = currentWarmupWaitingReason();
        if (waiting == null) {
            warmupComplete = true;
            long warmupMs = Duration.between(warmupStartedAt, Instant.now()).toMillis();
            logger.info("Warm-up complete after {} ms. Metrics/sample collection is now enabled.", warmupMs);
            return;
        }
        if (!waiting.equals(lastWarmupWaitingReason)) {
            lastWarmupWaitingReason = waiting;
            logger.info("Warm-up active: waiting for {}", waiting);
        }
    }

    private String currentWarmupWaitingReason() {
        if (configuredReferenceMode) {
            if (consensusNodeService.isBeaconEnabled()) {
                if (consensusNodeService.getConfiguredObservation(Confidence.NEW) == null) {
                    return "reference head from configured consensus node";
                }
                boolean safePollingEnabled = consensusNodeService.isSafePollingEnabled();
                boolean finalizedPollingEnabled = consensusNodeService.isFinalizedPollingEnabled();
                if (safePollingEnabled && consensusNodeService.getConfiguredObservation(Confidence.SAFE) == null) {
                    return "safe block from configured consensus node";
                }
                if (finalizedPollingEnabled && consensusNodeService.getConfiguredObservation(Confidence.FINALIZED) == null) {
                    return "finalized block from configured consensus node";
                }
                return null;
            }

            boolean safeRequired = properties.isGetSafeBlocks();
            boolean finalizedRequired = properties.isGetFinalizedBlocks();
            if (configuredReferenceNodeKey != null && !configuredReferenceNodeKey.isBlank()) {
                NodeState state = nodeStates.get(configuredReferenceNodeKey);
                if (state == null) {
                    return "configured reference execution node state";
                }
                boolean hasHead = (state.lastWsBlockNumber != null && state.lastWsBlockHash != null)
                        || (state.lastHttpBlockNumber != null && state.lastHttpBlockHash != null);
                if (!hasHead) {
                    return "head block from configured reference execution node";
                }
                if (safeRequired && (state.lastSafeBlockNumber == null || state.lastSafeBlockHash == null)) {
                    return "safe block from configured reference execution node";
                }
                if (finalizedRequired && (state.lastFinalizedBlockNumber == null || state.lastFinalizedBlockHash == null)) {
                    return "finalized block from configured reference execution node";
                }
                return null;
            }
        }

        boolean safeRequired = properties.isGetSafeBlocks();
        boolean finalizedRequired = properties.isGetFinalizedBlocks();

        ReferenceState reference = referenceState.get();
        if (reference == null || reference.headNumber() == null || reference.headHash() == null) {
            return "reference head by voting across monitored nodes";
        }
        if (getReferenceNodeKey() == null) {
            return "reference node selection by voting";
        }
        if (safeRequired) {
            boolean hasSafe = nodeStates.values().stream().anyMatch(
                    state -> state.lastSafeBlockNumber != null && state.lastSafeBlockHash != null);
            if (!hasSafe) {
                return "first safe block from monitored nodes";
            }
        }
        if (finalizedRequired) {
            boolean hasFinalized = nodeStates.values().stream().anyMatch(
                    state -> state.lastFinalizedBlockNumber != null && state.lastFinalizedBlockHash != null);
            if (!hasFinalized) {
                return "first finalized block from monitored nodes";
            }
        }
        return null;
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

    static class BlockInfo {
        private final Long blockNumber;
        private final String blockHash;
        private final String parentHash;
        private final Integer transactionCount;
        private final Long gasPriceWei;
        private final Instant blockTimestamp;

        BlockInfo(Long blockNumber, String blockHash, String parentHash, Integer transactionCount,
                Long gasPriceWei, Instant blockTimestamp) {
            this.blockNumber = blockNumber;
            this.blockHash = blockHash;
            this.parentHash = parentHash;
            this.transactionCount = transactionCount;
            this.gasPriceWei = gasPriceWei;
            this.blockTimestamp = blockTimestamp;
        }

        Long blockNumber() {
            return blockNumber;
        }

        String blockHash() {
            return blockHash;
        }

        String parentHash() {
            return parentHash;
        }

        Integer transactionCount() {
            return transactionCount;
        }

        Long gasPriceWei() {
            return gasPriceWei;
        }

        Instant blockTimestamp() {
            return blockTimestamp;
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

    static class ReferenceState {
        private final Long headNumber;
        private final String headHash;
        private final Instant fetchedAt;

        ReferenceState(Long headNumber, String headHash, Instant fetchedAt) {
            this.headNumber = headNumber;
            this.headHash = headHash;
            this.fetchedAt = fetchedAt;
        }

        Long headNumber() {
            return headNumber;
        }

        String headHash() {
            return headHash;
        }

        Instant fetchedAt() {
            return fetchedAt;
        }
    }
}

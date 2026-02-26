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
package de.makibytes.chaincheck.chain.cosmos;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import de.makibytes.chaincheck.chain.shared.BlockAgreementTracker;
import de.makibytes.chaincheck.chain.shared.Confidence;
import de.makibytes.chaincheck.model.AnomalyEvent;
import de.makibytes.chaincheck.model.AnomalyType;
import de.makibytes.chaincheck.model.MetricSource;
import de.makibytes.chaincheck.monitor.AnomalyDetector;
import de.makibytes.chaincheck.monitor.NodeRegistry;
import de.makibytes.chaincheck.monitor.NodeRegistry.NodeDefinition;
import de.makibytes.chaincheck.monitor.RpcMonitorService;
import de.makibytes.chaincheck.store.InMemoryMetricsStore;

/**
 * Coordinates block voting across all nodes.
 */
@Service
public class BlockVotingCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(BlockVotingCoordinator.class);

    private final NodeRegistry nodeRegistry;
    private final BlockVotingService blockVotingService;
    private final BlockAgreementTracker blockAgreementTracker;
    private final InMemoryMetricsStore store;
    private final AnomalyDetector detector;

    public BlockVotingCoordinator(NodeRegistry nodeRegistry,
                          BlockVotingService blockVotingService,
                          BlockAgreementTracker blockAgreementTracker,
                          InMemoryMetricsStore store,
                          AnomalyDetector detector) {
        this.nodeRegistry = nodeRegistry;
        this.blockVotingService = blockVotingService;
        this.blockAgreementTracker = blockAgreementTracker;
        this.store = store;
        this.detector = detector;
    }

    public void collectVotesFromNodes(Map<String, RpcMonitorService.NodeState> nodeStates) {
        logger.debug("Collecting votes from {} nodes", nodeStates.size());
        blockVotingService.clearVotes();
        for (NodeDefinition node : nodeRegistry.getNodes()) {
            RpcMonitorService.NodeState state = nodeStates.get(node.key());
            if (state == null) {
                logger.debug("No NodeState found for node key={}, skipping vote collection", node.key());
                continue;
            }

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
    }

    public Map<Long, Map<Confidence, String>> snapshotOldReferenceBlocks() {
        Map<Long, Map<Confidence, String>> oldBlocks = new HashMap<>();
        for (Map.Entry<Long, Map<Confidence, String>> entry : blockVotingService.getBlockConfidenceTracker().getBlocks().entrySet()) {
            oldBlocks.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }
        return oldBlocks;
    }

    public void performVotingAndScoring(Map<Long, Map<Confidence, String>> oldBlocks,
                                 String currentReferenceNodeKey,
                                 Instant now,
                                 boolean warmupComplete,
                                 Map<String, RpcMonitorService.NodeState> nodeStates) {
        blockVotingService.performVoting(currentReferenceNodeKey);
        emitWrongHeadForOrphanedWsNewHeads(now, warmupComplete);
        blockAgreementTracker.penalize(oldBlocks, blockVotingService.getBlockConfidenceTracker(), blockVotingService.getBlockVotes());
        blockAgreementTracker.awardPoints(oldBlocks, blockVotingService.getBlockConfidenceTracker(), blockVotingService.getBlockVotes());
    }

    /**
     * Emits WRONG_HEAD anomalies for WS newHead samples whose block hash is not part of the
     * canonical chain. Detection is anchored to SAFE or FINALIZED consensus: once the majority
     * of nodes agree on a canonical hash for a block at a given confidence level, any WS newHead
     * sample with a different hash at the same block number is an orphan.
     *
     * <p>This avoids false positives for nodes that are merely lagging (they will eventually catch
     * up and report the same canonical hash), while reliably flagging nodes that were on a fork.
     */
    private void emitWrongHeadForOrphanedWsNewHeads(Instant now, boolean warmupComplete) {
        if (!warmupComplete) {
            return;
        }

        Map<Long, Map<Confidence, String>> confirmedBlocks = blockVotingService.getBlockConfidenceTracker().getBlocks();
        Instant lookback = now.minus(Duration.ofHours(2));

        for (Map.Entry<Long, Map<Confidence, String>> entry : confirmedBlocks.entrySet()) {
            Long blockNumber = entry.getKey();
            Map<Confidence, String> confidenceMap = entry.getValue();

            // Only check blocks with SAFE or FINALIZED consensus — these are definitively canonical
            String canonicalHash = confidenceMap.get(Confidence.FINALIZED);
            String label = "finalized";
            if (canonicalHash == null) {
                canonicalHash = confidenceMap.get(Confidence.SAFE);
                label = "safe";
            }
            if (canonicalHash == null) {
                continue;
            }

            for (NodeDefinition node : nodeRegistry.getNodes()) {
                String nodeKey = node.key();
                String wrongHash = findWsNewHeadNonCanonicalHash(nodeKey, blockNumber, canonicalHash, lookback);
                if (wrongHash == null) {
                    continue;
                }
                if (hasExistingWrongHeadForHash(nodeKey, blockNumber, wrongHash, lookback)) {
                    continue;
                }
                String details = "Node reported newHead hash " + wrongHash
                        + " at height " + blockNumber
                        + ", " + label + " canonical hash is " + canonicalHash;
                AnomalyEvent anomaly = detector.wrongHead(
                        nodeKey,
                        now,
                        MetricSource.WS,
                        blockNumber,
                        wrongHash,
                        details);
                store.addAnomaly(nodeKey, anomaly);
            }
        }
    }

    public ReferenceHead resolveReferenceHead() {
        return blockVotingService.getBlockConfidenceTracker().getBlocks().entrySet().stream()
                .filter(entry -> entry.getValue().get(Confidence.NEW) != null)
                .max(Comparator.comparingLong(Map.Entry::getKey))
                .map(entry -> {
                    logger.debug("Resolved reference head: number={} hash= {}", entry.getKey(), entry.getValue().get(Confidence.NEW));
                    return new ReferenceHead(entry.getKey(), entry.getValue().get(Confidence.NEW));
                })
                .orElse(null);
    }

    /**
     * Returns the first WS newHead block hash for the given block number that differs from the
     * canonical hash, or null if no such sample exists.
     */
    private String findWsNewHeadNonCanonicalHash(String nodeKey, Long blockNumber, String canonicalHash, Instant since) {
        if (nodeKey == null || blockNumber == null || canonicalHash == null) {
            return null;
        }
        return store.getRawSamplesSince(nodeKey, since).stream()
                .filter(s -> s.getSource() == MetricSource.WS
                        && s.isSuccess()
                        && blockNumber.equals(s.getBlockNumber())
                        && s.getBlockHash() != null
                        && !s.getBlockHash().equalsIgnoreCase(canonicalHash))
                .map(s -> s.getBlockHash())
                .findFirst()
                .orElse(null);
    }

    private boolean hasExistingWrongHeadForHash(String nodeKey, Long blockNumber, String blockHash, Instant since) {
        if (nodeKey == null || blockNumber == null || blockHash == null) {
            return false;
        }
        return store.getRawAnomaliesSince(nodeKey, since).stream()
                .anyMatch(a -> a.getType() == AnomalyType.WRONG_HEAD
                        && a.getSource() == MetricSource.WS
                        && blockNumber.equals(a.getBlockNumber())
                        && a.getBlockHash() != null
                        && a.getBlockHash().equalsIgnoreCase(blockHash));
    }

    public record ReferenceHead(Long headNumber, String headHash) {
    }
}

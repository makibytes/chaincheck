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
        emitWrongHeadForInvalidatedWsNewHeads(oldBlocks, now, warmupComplete);
        blockAgreementTracker.penalize(oldBlocks, blockVotingService.getBlockConfidenceTracker(), blockVotingService.getBlockVotes());
        blockAgreementTracker.awardPoints(oldBlocks, blockVotingService.getBlockConfidenceTracker(), blockVotingService.getBlockVotes());
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

    private void emitWrongHeadForInvalidatedWsNewHeads(Map<Long, Map<Confidence, String>> oldBlocks,
                                                       Instant now,
                                                       boolean warmupComplete) {
        if (!warmupComplete || oldBlocks == null || oldBlocks.isEmpty()) {
            return;
        }

        Map<Long, Map<Confidence, String>> newBlocks = blockVotingService.getBlockConfidenceTracker().getBlocks();
        Instant lookback = now.minus(Duration.ofHours(2));

        for (Map.Entry<Long, Map<Confidence, String>> entry : oldBlocks.entrySet()) {
            Long blockNumber = entry.getKey();
            if (blockNumber == null) {
                continue;
            }
            String oldHash = entry.getValue() == null ? null : entry.getValue().get(Confidence.NEW);
            if (oldHash == null || oldHash.isBlank()) {
                continue;
            }
            String newHash = newBlocks.getOrDefault(blockNumber, Map.of()).get(Confidence.NEW);
            if (newHash == null || oldHash.equalsIgnoreCase(newHash)) {
                continue;
            }

            for (NodeDefinition node : nodeRegistry.getNodes()) {
                String nodeKey = node.key();
                if (!hasWsNewHeadSample(nodeKey, blockNumber, oldHash, lookback)) {
                    continue;
                }
                if (hasExistingWrongHeadForHash(nodeKey, blockNumber, oldHash, lookback)) {
                    continue;
                }
                String details = "Node reported newHead hash " + oldHash
                        + " at height " + blockNumber
                        + ", later invalidated by reference hash " + newHash;
                AnomalyEvent anomaly = detector.wrongHead(
                        nodeKey,
                        now,
                        MetricSource.WS,
                        blockNumber,
                        oldHash,
                        details);
                store.addAnomaly(nodeKey, anomaly);
            }
        }
    }

    private boolean hasWsNewHeadSample(String nodeKey, Long blockNumber, String blockHash, Instant since) {
        if (nodeKey == null || blockNumber == null || blockHash == null) {
            return false;
        }
        return store.getRawSamplesSince(nodeKey, since).stream()
                .anyMatch(s -> s.getSource() == MetricSource.WS
                        && s.isSuccess()
                        && blockNumber.equals(s.getBlockNumber())
                        && s.getBlockHash() != null
                        && s.getBlockHash().equalsIgnoreCase(blockHash));
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

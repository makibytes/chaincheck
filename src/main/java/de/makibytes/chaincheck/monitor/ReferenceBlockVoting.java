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

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.makibytes.chaincheck.model.AnomalyEvent;
import de.makibytes.chaincheck.model.AnomalyType;
import de.makibytes.chaincheck.model.MetricSample;
import de.makibytes.chaincheck.model.MetricSource;
import de.makibytes.chaincheck.monitor.NodeRegistry.NodeDefinition;
import de.makibytes.chaincheck.monitor.ReferenceBlocks.Confidence;
import de.makibytes.chaincheck.store.InMemoryMetricsStore;

class ReferenceBlockVoting {

    private final NodeRegistry nodeRegistry;
    private final BlockVotingService blockVotingService;
    private final NodeScorer nodeScorer;
    private final InMemoryMetricsStore store;
    private final AnomalyDetector detector;

    ReferenceBlockVoting(NodeRegistry nodeRegistry,
                         BlockVotingService blockVotingService,
                         NodeScorer nodeScorer,
                         InMemoryMetricsStore store,
                         AnomalyDetector detector) {
        this.nodeRegistry = nodeRegistry;
        this.blockVotingService = blockVotingService;
        this.nodeScorer = nodeScorer;
        this.store = store;
        this.detector = detector;
    }

    void collectVotesFromNodes(Map<String, NodeMonitorService.NodeState> nodeStates) {
        blockVotingService.clearVotes();
        for (NodeDefinition node : nodeRegistry.getNodes()) {
            NodeMonitorService.NodeState state = nodeStates.get(node.key());
            if (state == null) {
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

    Map<Long, Map<Confidence, String>> snapshotOldReferenceBlocks() {
        Map<Long, Map<Confidence, String>> oldBlocks = new HashMap<>();
        for (Map.Entry<Long, Map<Confidence, String>> entry : blockVotingService.getReferenceBlocks().getBlocks().entrySet()) {
            oldBlocks.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }
        return oldBlocks;
    }

    void performVotingAndScoring(Map<Long, Map<Confidence, String>> oldBlocks,
                                 String currentReferenceNodeKey,
                                 Instant now,
                                 boolean warmupComplete,
                                 Map<String, NodeMonitorService.NodeState> nodeStates) {
        blockVotingService.performVoting(currentReferenceNodeKey);
        emitWrongHeadForInvalidatedWsNewHeads(oldBlocks, now, warmupComplete);
        nodeScorer.penalizeForInvalidBlocks(oldBlocks, blockVotingService.getReferenceBlocks(), blockVotingService.getBlockVotes());
        nodeScorer.awardPointsForCorrectBlocks(oldBlocks, blockVotingService.getReferenceBlocks(), blockVotingService.getBlockVotes());
    }

    ReferenceHead resolveReferenceHead() {
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
            return null;
        }
        return new ReferenceHead(referenceHeadNumber, referenceHeadHash);
    }

    private void emitWrongHeadForInvalidatedWsNewHeads(Map<Long, Map<Confidence, String>> oldBlocks,
                                                       Instant now,
                                                       boolean warmupComplete) {
        if (!warmupComplete || oldBlocks == null || oldBlocks.isEmpty()) {
            return;
        }

        Map<Long, Map<Confidence, String>> newBlocks = blockVotingService.getReferenceBlocks().getBlocks();
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
        List<MetricSample> samples = store.getRawSamplesSince(nodeKey, since);
        for (MetricSample sample : samples) {
            if (sample.getSource() != MetricSource.WS) {
                continue;
            }
            if (!sample.isSuccess()) {
                continue;
            }
            if (sample.getBlockNumber() == null || !blockNumber.equals(sample.getBlockNumber())) {
                continue;
            }
            if (sample.getBlockHash() != null && sample.getBlockHash().equalsIgnoreCase(blockHash)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasExistingWrongHeadForHash(String nodeKey, Long blockNumber, String blockHash, Instant since) {
        if (nodeKey == null || blockNumber == null || blockHash == null) {
            return false;
        }
        List<AnomalyEvent> anomalies = store.getRawAnomaliesSince(nodeKey, since);
        for (AnomalyEvent anomaly : anomalies) {
            if (anomaly.getType() != AnomalyType.WRONG_HEAD) {
                continue;
            }
            if (anomaly.getSource() != MetricSource.WS) {
                continue;
            }
            if (!blockNumber.equals(anomaly.getBlockNumber())) {
                continue;
            }
            if (anomaly.getBlockHash() != null && anomaly.getBlockHash().equalsIgnoreCase(blockHash)) {
                return true;
            }
        }
        return false;
    }

    record ReferenceHead(Long headNumber, String headHash) {
    }
}

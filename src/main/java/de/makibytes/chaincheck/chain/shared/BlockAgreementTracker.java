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
package de.makibytes.chaincheck.chain.shared;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import org.springframework.stereotype.Service;

/**
 * Tracks node agreement with reference blocks by awarding and penalizing points.
 */
@Service
public class BlockAgreementTracker {

    /**
     * Penalty for a NEW block being invalidated.
     */
    private static final int NEW_BLOCK_PENALTY = 7;

    /**
     * Penalty for a SAFE block being invalidated.
     */
    private static final int SAFE_BLOCK_PENALTY = 50;

    private final Map<String, Integer> nodePoints = new ConcurrentHashMap<>();

    /**
     * Awards points to nodes that correctly reported blocks that became reference blocks.
     */
    public void awardPoints(Map<Long, Map<Confidence, String>> oldBlocks, BlockConfidenceTracker blockConfidenceTracker,
            Map<Long, Map<Confidence, Map<String, Set<String>>>> blockVotes) {
        forEachBlockDelta(oldBlocks, blockConfidenceTracker.getBlocks(), blockVotes,
                (nodes, confidence) -> {
                    for (String nodeKey : nodes) {
                        int current = nodePoints.getOrDefault(nodeKey, 0);
                        nodePoints.put(nodeKey, Math.min(100, current + 1));
                    }
                });
    }

    /**
     * Penalizes nodes that reported blocks that were invalidated.
     */
    public void penalize(Map<Long, Map<Confidence, String>> oldBlocks, BlockConfidenceTracker blockConfidenceTracker,
            Map<Long, Map<Confidence, Map<String, Set<String>>>> blockVotes) {
        forEachInvalidatedBlockDelta(oldBlocks, blockConfidenceTracker.getBlocks(), blockVotes,
                (nodes, confidence) -> {
                    int penalty = switch (confidence) {
                        case NEW -> NEW_BLOCK_PENALTY;
                        case SAFE -> SAFE_BLOCK_PENALTY;
                        case FINALIZED -> Integer.MAX_VALUE;
                    };
                    for (String nodeKey : nodes) {
                        int current = nodePoints.getOrDefault(nodeKey, 0);
                        nodePoints.put(nodeKey, Math.max(0, current - penalty));
                    }
                });
    }

    private void forEachBlockDelta(Map<Long, Map<Confidence, String>> oldBlocks,
                                   Map<Long, Map<Confidence, String>> currentBlocks,
                                   Map<Long, Map<Confidence, Map<String, Set<String>>>> blockVotes,
                                   BiConsumer<Set<String>, Confidence> callback) {
        for (Map.Entry<Long, Map<Confidence, String>> entry : currentBlocks.entrySet()) {
            long blockNumber = entry.getKey();
            for (Map.Entry<Confidence, String> confEntry : entry.getValue().entrySet()) {
                Confidence confidence = confEntry.getKey();
                String newHash = confEntry.getValue();
                String oldHash = oldBlocks.getOrDefault(blockNumber, Map.of()).get(confidence);
                if (!newHash.equals(oldHash)) {
                    Set<String> nodes = blockVotes.getOrDefault(blockNumber, Map.of())
                            .getOrDefault(confidence, Map.of())
                            .get(newHash);
                    if (nodes != null) {
                        callback.accept(nodes, confidence);
                    }
                }
            }
        }
    }

    private void forEachInvalidatedBlockDelta(Map<Long, Map<Confidence, String>> oldBlocks,
                                              Map<Long, Map<Confidence, String>> currentBlocks,
                                              Map<Long, Map<Confidence, Map<String, Set<String>>>> blockVotes,
                                              BiConsumer<Set<String>, Confidence> callback) {
        for (Map.Entry<Long, Map<Confidence, String>> entry : oldBlocks.entrySet()) {
            long blockNumber = entry.getKey();
            for (Map.Entry<Confidence, String> confEntry : entry.getValue().entrySet()) {
                Confidence confidence = confEntry.getKey();
                String oldHash = confEntry.getValue();
                String newHash = currentBlocks.getOrDefault(blockNumber, Map.of()).get(confidence);
                if (newHash == null || !newHash.equals(oldHash)) {
                    Set<String> nodes = blockVotes.getOrDefault(blockNumber, Map.of())
                            .getOrDefault(confidence, Map.of())
                            .get(oldHash);
                    if (nodes != null) {
                        callback.accept(nodes, confidence);
                    }
                }
            }
        }
    }

    /**
     * Gets the points for a node.
     */
    public int getPoints(String nodeKey) {
        return nodePoints.getOrDefault(nodeKey, 0);
    }

    /**
     * Gets all node points.
     */
    public Map<String, Integer> getAllPoints() {
        return nodePoints;
    }
}

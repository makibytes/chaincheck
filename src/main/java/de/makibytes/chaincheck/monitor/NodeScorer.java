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

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import de.makibytes.chaincheck.monitor.ReferenceBlocks.Confidence;

/**
 * Service responsible for scoring nodes based on their agreement with reference blocks.
 */
@Service
public class NodeScorer {

    private final Map<String, Integer> nodePoints = new ConcurrentHashMap<>();

    /**
     * Awards points to nodes that correctly reported blocks that became reference blocks.
     */
    public void awardPointsForCorrectBlocks(Map<Long, Map<Confidence, String>> oldBlocks, ReferenceBlocks referenceBlocks,
            Map<Long, Map<Confidence, Map<String, Set<String>>>> blockVotes) {
        for (Map.Entry<Long, Map<Confidence, String>> entry : referenceBlocks.getBlocks().entrySet()) {
            long blockNumber = entry.getKey();
            for (Map.Entry<Confidence, String> confEntry : entry.getValue().entrySet()) {
                Confidence confidence = confEntry.getKey();
                String newHash = confEntry.getValue();
                String oldHash = oldBlocks.getOrDefault(blockNumber, Map.of()).get(confidence);
                if (!newHash.equals(oldHash)) {
                    // New or changed block
                    Set<String> nodes = blockVotes.getOrDefault(blockNumber, Map.of())
                            .getOrDefault(confidence, Map.of())
                            .get(newHash);
                    if (nodes != null) {
                        for (String nodeKey : nodes) {
                            int current = nodePoints.getOrDefault(nodeKey, 0);
                            nodePoints.put(nodeKey, Math.min(100, current + 1));
                        }
                    }
                }
            }
        }
    }

    /**
     * Penalizes nodes that reported blocks that were invalidated.
     */
    public void penalizeForInvalidBlocks(Map<Long, Map<Confidence, String>> oldBlocks, ReferenceBlocks referenceBlocks,
            Map<Long, Map<Confidence, Map<String, Set<String>>>> blockVotes) {
        for (Map.Entry<Long, Map<Confidence, String>> entry : oldBlocks.entrySet()) {
            long blockNumber = entry.getKey();
            for (Map.Entry<Confidence, String> confEntry : entry.getValue().entrySet()) {
                Confidence confidence = confEntry.getKey();
                String oldHash = confEntry.getValue();
                String newHash = referenceBlocks.getBlocks().getOrDefault(blockNumber, Map.of()).get(confidence);
                if (newHash == null || !newHash.equals(oldHash)) {
                    // Invalidated block
                    Set<String> nodes = blockVotes.getOrDefault(blockNumber, Map.of())
                            .getOrDefault(confidence, Map.of())
                            .get(oldHash);
                    if (nodes != null) {
                        int penalty = switch (confidence) {
                            case NEW -> 7;
                            case SAFE -> 50;
                            case FINALIZED -> Integer.MAX_VALUE; // all points
                        };
                        for (String nodeKey : nodes) {
                            int current = nodePoints.getOrDefault(nodeKey, 0);
                            nodePoints.put(nodeKey, Math.max(0, current - penalty));
                        }
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
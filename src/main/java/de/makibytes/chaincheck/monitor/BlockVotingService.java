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
 * Service responsible for collecting block votes from nodes and determining reference blocks through voting.
 */
@Service
public class BlockVotingService {

    private final Map<Long, Map<Confidence, Map<String, Set<String>>>> blockVotes = new ConcurrentHashMap<>();
    private final ReferenceBlocks referenceBlocks = new ReferenceBlocks();

    /**
     * Records a vote for a block hash at a given confidence level from a node.
     */
    public void recordBlock(String nodeKey, long blockNumber, String blockHash, Confidence confidence) {
        blockVotes.computeIfAbsent(blockNumber, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(confidence, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(blockHash, k -> ConcurrentHashMap.newKeySet())
                .add(nodeKey);
    }

    /**
     * Performs voting to determine reference blocks based on collected votes.
     * Gives bonus votes to the current reference node.
     */
    public void performVoting(String currentReferenceNodeKey) {
        referenceBlocks.clear();
        for (Map.Entry<Long, Map<Confidence, Map<String, Set<String>>>> blockEntry : blockVotes.entrySet()) {
            long blockNumber = blockEntry.getKey();
            for (Map.Entry<Confidence, Map<String, Set<String>>> confEntry : blockEntry.getValue().entrySet()) {
                Confidence confidence = confEntry.getKey();
                Map<String, Set<String>> hashVotes = confEntry.getValue();
                String winningHash = null;
                int maxVotes = 0;
                for (Map.Entry<String, Set<String>> hashEntry : hashVotes.entrySet()) {
                    String hash = hashEntry.getKey();
                    Set<String> nodes = hashEntry.getValue();
                    int votes = nodes.size();
                    if (currentReferenceNodeKey != null && nodes.contains(currentReferenceNodeKey)) {
                        votes += 2; // +3 total since +1 already included
                    }
                    if (votes > maxVotes || (votes == maxVotes && currentReferenceNodeKey != null && nodes.contains(currentReferenceNodeKey))) {
                        maxVotes = votes;
                        winningHash = hash;
                    }
                }
                if (winningHash != null) {
                    referenceBlocks.setHash(blockNumber, confidence, winningHash);
                }
            }
        }
    }

    /**
     * Clears all collected votes.
     */
    public void clearVotes() {
        blockVotes.clear();
    }

    /**
     * Gets the reference blocks determined by voting.
     */
    public ReferenceBlocks getReferenceBlocks() {
        return referenceBlocks;
    }

    /**
     * Gets the block votes map.
     */
    public Map<Long, Map<Confidence, Map<String, Set<String>>>> getBlockVotes() {
        return blockVotes;
    }
}
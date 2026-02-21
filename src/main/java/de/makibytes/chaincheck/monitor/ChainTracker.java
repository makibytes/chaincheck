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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.makibytes.chaincheck.chain.shared.Confidence;

/**
 * Tracks blockchain state using hash-based identity following the Golden Rule:
 * "Never derive chain state from websocket event order. Always derive chain
 * state from block hashes and parent links."
 *
 * <p>
 * This tracker:
 * <ul>
 * <li>Stores blocks by hash as primary key (never by block number)</li>
 * <li>Maintains canonical chain via parentHash linkage</li>
 * <li>Detects reorgs by parentHash mismatch</li>
 * <li>Tolerates dropped WebSocket events by fetching missing blocks</li>
 * <li>Treats WebSocket newHeads events as wake-up signals only</li>
 * </ul>
 */
public class ChainTracker {

    private static final Logger logger = LoggerFactory.getLogger(ChainTracker.class);

    private static final int MAX_KNOWN_BLOCKS = 1024;
    private static final int MAX_CANONICAL_LENGTH = 256;

    private final Map<String, BlockNode> knownBlocks = new ConcurrentHashMap<>();
    private final NavigableMap<Long, BlockNode> canonicalChain = new ConcurrentSkipListMap<>();
    private final NavigableMap<Long, Set<String>> blocksByNumber = new ConcurrentSkipListMap<>();

    private final String nodeKey;
    private volatile String canonicalHeadHash;

    public ChainTracker(String nodeKey) {
        this.nodeKey = nodeKey;
    }

    public record BlockNode(
            String hash,
            String parentHash,
            long number,
            Instant timestamp,
            Instant observedAt,
            Confidence confidence) {
    }

    public record ReorgEvent(
            String oldHeadHash,
            long oldHeadNumber,
            String newHeadHash,
            long newHeadNumber,
            String commonAncestorHash,
            long commonAncestorNumber,
            int reorgDepth) {
    }

    public record ChainUpdate(
            BlockNode newHead,
            List<BlockNode> addedBlocks,
            ReorgEvent reorg,
            boolean isNewBlock) {
    }

    /**
     * Registers a block fetched via eth_getBlockByHash.
     * Returns a ChainUpdate describing what changed.
     */
    public ChainUpdate registerBlock(BlockNode block) {
        if (block == null || block.hash() == null || block.parentHash() == null) {
            return new ChainUpdate(null, List.of(), null, false);
        }

        String hash = block.hash().toLowerCase();
        String parentHash = block.parentHash().toLowerCase();

        BlockNode existing = knownBlocks.get(hash);
        if (existing != null) {
            return new ChainUpdate(existing, List.of(), null, false);
        }

        knownBlocks.put(hash, block);

        blocksByNumber.computeIfAbsent(block.number(), k -> ConcurrentHashMap.newKeySet()).add(hash);

        pruneOldBlocks();

        if (canonicalHeadHash == null) {
            canonicalHeadHash = hash;
            canonicalChain.put(block.number(), block);
            return new ChainUpdate(block, List.of(block), null, true);
        }

        BlockNode currentHead = knownBlocks.get(canonicalHeadHash);
        if (currentHead == null) {
            canonicalHeadHash = hash;
            canonicalChain.put(block.number(), block);
            return new ChainUpdate(block, List.of(block), null, true);
        }

        if (parentHash.equals(canonicalHeadHash.toLowerCase())) {
            canonicalHeadHash = hash;
            canonicalChain.put(block.number(), block);
            return new ChainUpdate(block, List.of(block), null, true);
        }

        if (block.number() > currentHead.number()) {
            if (knownBlocks.containsKey(parentHash)) {
                ReorgEvent reorg = detectReorg(block, currentHead);
                if (reorg != null) {
                    applyReorg(block, reorg);
                    List<BlockNode> added = buildAddedBlocks(block);
                    return new ChainUpdate(block, added, reorg, true);
                }
            }

            if (!knownBlocks.containsKey(parentHash)) {
                canonicalHeadHash = hash;
                canonicalChain.put(block.number(), block);
                return new ChainUpdate(block, List.of(block), null, true);
            }
        }

        if (block.number() == currentHead.number() && !hash.equals(canonicalHeadHash.toLowerCase())) {
            BlockNode parent = knownBlocks.get(parentHash);
            if (parent != null) {
                ReorgEvent reorg = detectReorg(block, currentHead);
                if (reorg != null) {
                    applyReorg(block, reorg);
                    List<BlockNode> added = buildAddedBlocks(block);
                    return new ChainUpdate(block, added, reorg, true);
                }
            }
            logger.warn("Conflicting blocks at height {} for node {}: {} vs {}", 
                    block.number(), nodeKey, hash, canonicalHeadHash);
        }

        return new ChainUpdate(block, List.of(), null, true);
    }

    /**
     * Detects a reorg by walking backwards via parentHash.
     */
    private ReorgEvent detectReorg(BlockNode newHead, BlockNode currentHead) {
        Set<String> oldBranch = new HashSet<>();
        BlockNode oldCurrent = currentHead;
        while (oldCurrent != null && oldBranch.size() < MAX_CANONICAL_LENGTH) {
            oldBranch.add(oldCurrent.hash().toLowerCase());
            if (oldCurrent.parentHash() == null) break;
            oldCurrent = knownBlocks.get(oldCurrent.parentHash().toLowerCase());
        }

        List<BlockNode> newBranch = new ArrayList<>();
        BlockNode newCurrent = newHead;
        while (newCurrent != null && newBranch.size() < MAX_CANONICAL_LENGTH) {
            String checkHash = newCurrent.hash().toLowerCase();
            if (oldBranch.contains(checkHash)) {
                int reorgDepth = (int) (currentHead.number() - newCurrent.number());
                return new ReorgEvent(
                        currentHead.hash(),
                        currentHead.number(),
                        newHead.hash(),
                        newHead.number(),
                        newCurrent.hash(),
                        newCurrent.number(),
                        reorgDepth);
            }
            newBranch.add(newCurrent);
            if (newCurrent.parentHash() == null) break;
            newCurrent = knownBlocks.get(newCurrent.parentHash().toLowerCase());
        }

        return null;
    }

    /**
     * Applies a reorg by updating the canonical chain.
     */
    private void applyReorg(BlockNode newHead, ReorgEvent reorg) {
        canonicalChain.tailMap(reorg.commonAncestorNumber(), false).clear();

        BlockNode current = newHead;
        while (current != null && current.number() > reorg.commonAncestorNumber()) {
            canonicalChain.put(current.number(), current);
            if (current.parentHash() == null) break;
            current = knownBlocks.get(current.parentHash().toLowerCase());
        }

        canonicalHeadHash = newHead.hash().toLowerCase();

        logger.info("Reorg detected for node {}: depth={}, oldHead={}@{}, newHead={}@{}, commonAncestor={}@{}",
                nodeKey, reorg.reorgDepth(),
                reorg.oldHeadHash(), reorg.oldHeadNumber(),
                reorg.newHeadHash(), reorg.newHeadNumber(),
                reorg.commonAncestorHash(), reorg.commonAncestorNumber());
    }

    /**
     * Builds the list of blocks added from the new head back to the common ancestor.
     */
    private List<BlockNode> buildAddedBlocks(BlockNode newHead) {
        List<BlockNode> added = new ArrayList<>();
        BlockNode current = newHead;
        while (current != null) {
            added.add(current);
            if (!canonicalChain.containsKey(current.number())) break;
            if (current.parentHash() == null) break;
            current = knownBlocks.get(current.parentHash().toLowerCase());
        }
        Collections.reverse(added);
        return added;
    }

    /**
     * Returns block numbers that are missing between the given block and the current canonical head.
     * Used to fetch missing blocks when WebSocket events are dropped.
     */
    public List<Long> findMissingBlockNumbers(BlockNode block) {
        if (canonicalHeadHash == null) {
            return List.of();
        }

        BlockNode head = knownBlocks.get(canonicalHeadHash);
        if (head == null) {
            return List.of();
        }

        if (block.number() <= head.number() + 1) {
            return List.of();
        }

        List<Long> missing = new ArrayList<>();
        for (long num = head.number() + 1; num < block.number(); num++) {
            if (!canonicalChain.containsKey(num)) {
                missing.add(num);
            }
        }
        return missing;
    }

    /**
     * Returns the parent hash of the canonical head, or null if no head is set.
     */
    public String getCanonicalHeadParentHash() {
        if (canonicalHeadHash == null) {
            return null;
        }
        BlockNode head = knownBlocks.get(canonicalHeadHash);
        return head != null ? head.parentHash() : null;
    }

    public BlockNode getCanonicalHead() {
        if (canonicalHeadHash == null) {
            return null;
        }
        return knownBlocks.get(canonicalHeadHash);
    }

    public BlockNode getBlockByHash(String hash) {
        if (hash == null) {
            return null;
        }
        return knownBlocks.get(hash.toLowerCase());
    }

    public BlockNode getBlockByNumber(long number) {
        BlockNode canonical = canonicalChain.get(number);
        if (canonical != null) {
            return canonical;
        }
        Set<String> hashes = blocksByNumber.get(number);
        if (hashes == null || hashes.isEmpty()) {
            return null;
        }
        return knownBlocks.get(hashes.iterator().next());
    }

    public List<BlockNode> getConflictingBlocks(long number) {
        Set<String> hashes = blocksByNumber.get(number);
        if (hashes == null || hashes.size() <= 1) {
            return List.of();
        }
        List<BlockNode> blocks = new ArrayList<>();
        for (String hash : hashes) {
            BlockNode block = knownBlocks.get(hash);
            if (block != null) {
                blocks.add(block);
            }
        }
        blocks.sort(Comparator.comparing(BlockNode::observedAt));
        return blocks;
    }

    public String getCanonicalHeadHash() {
        return canonicalHeadHash;
    }

    public Long getCanonicalHeadNumber() {
        BlockNode head = getCanonicalHead();
        return head != null ? head.number() : null;
    }

    private void pruneOldBlocks() {
        if (knownBlocks.size() <= MAX_KNOWN_BLOCKS) {
            return;
        }

        Long lowestCanonical = canonicalChain.isEmpty() ? null : canonicalChain.firstKey();
        if (lowestCanonical == null) {
            return;
        }

        long pruneBelow = lowestCanonical - 64;
        if (pruneBelow <= 0) {
            return;
        }

        blocksByNumber.headMap(pruneBelow).clear();
        knownBlocks.entrySet().removeIf(entry -> entry.getValue().number() < pruneBelow);

        while (canonicalChain.size() > MAX_CANONICAL_LENGTH) {
            Long first = canonicalChain.firstKey();
            canonicalChain.remove(first);
        }
    }

    public void updateConfidence(String blockHash, Confidence confidence) {
        if (blockHash == null) {
            return;
        }
        BlockNode existing = knownBlocks.get(blockHash.toLowerCase());
        if (existing == null) {
            return;
        }
        BlockNode updated = new BlockNode(
                existing.hash(),
                existing.parentHash(),
                existing.number(),
                existing.timestamp(),
                existing.observedAt(),
                confidence);
        knownBlocks.put(blockHash.toLowerCase(), updated);
        if (canonicalChain.containsKey(existing.number())) {
            canonicalChain.put(existing.number(), updated);
        }
    }

    public int getKnownBlockCount() {
        return knownBlocks.size();
    }

    public int getCanonicalChainLength() {
        return canonicalChain.size();
    }
}

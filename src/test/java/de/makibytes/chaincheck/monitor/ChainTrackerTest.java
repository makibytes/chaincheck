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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.makibytes.chaincheck.chain.shared.Confidence;
import de.makibytes.chaincheck.monitor.ChainTracker.BlockNode;
import de.makibytes.chaincheck.monitor.ChainTracker.ChainUpdate;

@DisplayName("ChainTracker Tests (Golden Rule)")
class ChainTrackerTest {

    private ChainTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new ChainTracker("test-node");
    }

    @Test
    @DisplayName("first block becomes canonical head")
    void firstBlockBecomesCanonicalHead() {
        BlockNode block = block(100, "0xaaa", "0xparent");
        ChainUpdate update = tracker.registerBlock(block);

        assertTrue(update.isNewBlock());
        assertNotNull(update.newHead());
        assertEquals("0xaaa", update.newHead().hash());
        assertNull(update.reorg());
        assertEquals("0xaaa", tracker.getCanonicalHeadHash());
        assertEquals(100L, tracker.getCanonicalHeadNumber());
    }

    @Test
    @DisplayName("block with parent == canonical head extends chain")
    void blockExtendsChain() {
        tracker.registerBlock(block(100, "0xaaa", "0xparent"));
        
        BlockNode block = block(101, "0xbbb", "0xaaa");
        ChainUpdate update = tracker.registerBlock(block);

        assertTrue(update.isNewBlock());
        assertNull(update.reorg());
        assertEquals("0xbbb", tracker.getCanonicalHeadHash());
        assertEquals(101L, tracker.getCanonicalHeadNumber());
    }

    @Test
    @DisplayName("duplicate block is not added again")
    void duplicateBlockNotAdded() {
        BlockNode block = block(100, "0xaaa", "0xparent");
        tracker.registerBlock(block);
        
        ChainUpdate update = tracker.registerBlock(block);

        assertFalse(update.isNewBlock());
    }

    @Test
    @DisplayName("reorg detected when parent does not match canonical head")
    void reorgDetected() {
        tracker.registerBlock(block(100, "0xaaa", "0xparent"));
        tracker.registerBlock(block(101, "0xbbb", "0xaaa"));
        tracker.registerBlock(block(102, "0xccc", "0xbbb"));

        BlockNode reorgBlock = block(102, "0xreorg", "0xaaa");
        ChainUpdate update = tracker.registerBlock(reorgBlock);

        assertTrue(update.isNewBlock());
        assertNotNull(update.reorg());
        assertEquals(2, update.reorg().reorgDepth());
        assertEquals("0xaaa", update.reorg().commonAncestorHash());
        assertEquals(100L, update.reorg().commonAncestorNumber());
        assertEquals("0xreorg", tracker.getCanonicalHeadHash());
    }

    @Test
    @DisplayName("conflicting blocks at same height are tracked")
    void conflictingBlocksTracked() {
        tracker.registerBlock(block(100, "0xaaa", "0xparent"));
        
        BlockNode block1 = block(100, "0xbbb", "0xparent2");
        tracker.registerBlock(block1);

        var conflicts = tracker.getConflictingBlocks(100);
        assertEquals(2, conflicts.size());
    }

    @Test
    @DisplayName("get block by hash returns correct block")
    void getBlockByHash() {
        BlockNode block = block(100, "0xaaa", "0xparent");
        tracker.registerBlock(block);

        BlockNode retrieved = tracker.getBlockByHash("0xaaa");
        assertNotNull(retrieved);
        assertEquals(100L, retrieved.number());

        assertNull(tracker.getBlockByHash("0xnonexistent"));
    }

    @Test
    @DisplayName("get block by number returns canonical block")
    void getBlockByNumber() {
        tracker.registerBlock(block(100, "0xaaa", "0xparent"));
        tracker.registerBlock(block(100, "0xbbb", "0xparent2"));

        BlockNode canonical = tracker.getBlockByNumber(100);
        assertNotNull(canonical);
        assertEquals("0xaaa", canonical.hash());
    }

    @Test
    @DisplayName("missing block numbers detected correctly")
    void missingBlocksDetected() {
        tracker.registerBlock(block(100, "0xaaa", "0xparent"));

        BlockNode newBlock = block(105, "0xfff", "0xeee");
        var missing = tracker.findMissingBlockNumbers(newBlock);

        assertEquals(4, missing.size());
        assertTrue(missing.contains(101L));
        assertTrue(missing.contains(102L));
        assertTrue(missing.contains(103L));
        assertTrue(missing.contains(104L));
    }

    @Test
    @DisplayName("no missing blocks when sequential")
    void noMissingBlocksWhenSequential() {
        tracker.registerBlock(block(100, "0xaaa", "0xparent"));
        
        BlockNode newBlock = block(101, "0xbbb", "0xaaa");
        var missing = tracker.findMissingBlockNumbers(newBlock);

        assertTrue(missing.isEmpty());
    }

    @Test
    @DisplayName("confidence updates applied to existing blocks")
    void confidenceUpdateApplied() {
        tracker.registerBlock(block(100, "0xaaa", "0xparent"));
        
        tracker.updateConfidence("0xaaa", Confidence.FINALIZED);
        
        BlockNode updated = tracker.getBlockByHash("0xaaa");
        assertEquals(Confidence.FINALIZED, updated.confidence());
    }

    @Test
    @DisplayName("chain state derived from hashes not event order")
    void chainStateFromHashesNotOrder() {
        tracker.registerBlock(block(100, "0xaaa", "0xparent"));
        tracker.registerBlock(block(101, "0xbbb", "0xaaa"));
        tracker.registerBlock(block(102, "0xccc", "0xbbb"));

        tracker.registerBlock(block(103, "0xddd", "0xccc"));

        assertEquals("0xddd", tracker.getCanonicalHeadHash());
        assertEquals(103L, tracker.getCanonicalHeadNumber());

        BlockNode reorgBlock = block(103, "0xreorg", "0xaaa");
        tracker.registerBlock(reorgBlock);

        assertEquals("0xreorg", tracker.getCanonicalHeadHash());
        assertEquals("0xaaa", tracker.getCanonicalHeadParentHash());
    }

    @Test
    @DisplayName("deep reorg detected correctly")
    void deepReorgDetected() {
        tracker.registerBlock(block(100, "0xgen", "0xzero"));
        tracker.registerBlock(block(101, "0xaaa", "0xgen"));
        tracker.registerBlock(block(102, "0xbbb", "0xaaa"));
        tracker.registerBlock(block(103, "0xccc", "0xbbb"));
        tracker.registerBlock(block(104, "0xddd", "0xccc"));

        BlockNode reorgBlock = block(104, "0xreorg", "0xaaa");
        ChainUpdate update = tracker.registerBlock(reorgBlock);

        assertTrue(update.isNewBlock());
        assertNotNull(update.reorg());
        assertEquals(3, update.reorg().reorgDepth());
        assertEquals("0xaaa", update.reorg().commonAncestorHash());
        assertEquals(101L, update.reorg().commonAncestorNumber());
    }

    private BlockNode block(long number, String hash, String parentHash) {
        return new BlockNode(hash, parentHash, number, Instant.now(), Instant.now(), Confidence.NEW);
    }
}

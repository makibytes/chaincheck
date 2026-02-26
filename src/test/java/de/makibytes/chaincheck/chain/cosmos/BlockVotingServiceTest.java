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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.makibytes.chaincheck.chain.shared.Confidence;

@DisplayName("BlockVotingService Tests")
class BlockVotingServiceTest {

    private BlockVotingService service;

    @BeforeEach
    void setUp() {
        service = new BlockVotingService();
    }

    @Test
    @DisplayName("majority wins over minority reference node (4 nodes, 3 vs 1)")
    void majority_beats_reference_minority_with_4_nodes() {
        // 3 nodes agree on hash B, reference node is the 1 on hash A
        service.recordBlock("node1", 100L, "0xAAA", Confidence.NEW);
        service.recordBlock("node2", 100L, "0xBBB", Confidence.NEW);
        service.recordBlock("node3", 100L, "0xBBB", Confidence.NEW);
        service.recordBlock("node4", 100L, "0xBBB", Confidence.NEW);

        service.performVoting("node1"); // node1 (minority hash A) is reference

        assertEquals("0xBBB", service.getBlockConfidenceTracker().getHash(100L, Confidence.NEW),
                "Majority of 3 should win over minority reference node");
    }

    @Test
    @DisplayName("majority wins over minority reference node (3 nodes, 2 vs 1)")
    void majority_beats_reference_minority_with_3_nodes() {
        // reference is on hash A (1 node), majority on hash B (2 nodes)
        service.recordBlock("ref",   100L, "0xAAA", Confidence.NEW);
        service.recordBlock("node2", 100L, "0xBBB", Confidence.NEW);
        service.recordBlock("node3", 100L, "0xBBB", Confidence.NEW);

        service.performVoting("ref");

        assertEquals("0xBBB", service.getBlockConfidenceTracker().getHash(100L, Confidence.NEW),
                "2-node majority should win over 1-node reference");
    }

    @Test
    @DisplayName("reference node breaks a genuine tie (2 vs 2)")
    void reference_breaks_tie() {
        service.recordBlock("node1", 100L, "0xAAA", Confidence.NEW);
        service.recordBlock("node2", 100L, "0xAAA", Confidence.NEW);
        service.recordBlock("ref",   100L, "0xBBB", Confidence.NEW);
        service.recordBlock("node4", 100L, "0xBBB", Confidence.NEW);

        service.performVoting("ref"); // reference is on hash B

        assertEquals("0xBBB", service.getBlockConfidenceTracker().getHash(100L, Confidence.NEW),
                "Reference node should break a genuine tie in its favour");
    }

    @Test
    @DisplayName("unanimous vote picks the only hash regardless of reference")
    void unanimous_vote() {
        service.recordBlock("node1", 200L, "0xCCC", Confidence.NEW);
        service.recordBlock("node2", 200L, "0xCCC", Confidence.NEW);
        service.recordBlock("node3", 200L, "0xCCC", Confidence.NEW);

        service.performVoting("node1");

        assertEquals("0xCCC", service.getBlockConfidenceTracker().getHash(200L, Confidence.NEW));
    }

    @Test
    @DisplayName("no reference node: strict majority wins")
    void no_reference_node_majority_wins() {
        service.recordBlock("node1", 300L, "0xDDD", Confidence.NEW);
        service.recordBlock("node2", 300L, "0xDDD", Confidence.NEW);
        service.recordBlock("node3", 300L, "0xEEE", Confidence.NEW);

        service.performVoting(null);

        assertEquals("0xDDD", service.getBlockConfidenceTracker().getHash(300L, Confidence.NEW));
    }

    @Test
    @DisplayName("clearVotes resets state so old votes do not affect next round")
    void clearVotes_resets_state() {
        service.recordBlock("node1", 100L, "0xAAA", Confidence.NEW);
        service.performVoting(null);
        assertEquals("0xAAA", service.getBlockConfidenceTracker().getHash(100L, Confidence.NEW));

        service.clearVotes();
        service.performVoting(null);

        assertNull(service.getBlockConfidenceTracker().getHash(100L, Confidence.NEW),
                "After clearVotes no block should remain in the tracker");
    }

    @Test
    @DisplayName("SAFE and FINALIZED confidences are tracked independently")
    void confidence_levels_tracked_independently() {
        service.recordBlock("node1", 50L, "0xSAFE", Confidence.SAFE);
        service.recordBlock("node2", 50L, "0xSAFE", Confidence.SAFE);
        service.recordBlock("node1", 40L, "0xFIN",  Confidence.FINALIZED);
        service.recordBlock("node2", 40L, "0xFIN",  Confidence.FINALIZED);

        service.performVoting(null);

        assertEquals("0xSAFE", service.getBlockConfidenceTracker().getHash(50L, Confidence.SAFE));
        assertEquals("0xFIN",  service.getBlockConfidenceTracker().getHash(40L, Confidence.FINALIZED));
        assertNull(service.getBlockConfidenceTracker().getHash(50L, Confidence.NEW));
    }
}

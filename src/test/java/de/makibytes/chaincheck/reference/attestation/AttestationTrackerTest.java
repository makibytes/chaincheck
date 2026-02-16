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
package de.makibytes.chaincheck.reference.attestation;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.makibytes.chaincheck.model.AttestationConfidence;

class AttestationTrackerTest {

    private AttestationTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new AttestationTracker();
    }

    // --- SSZ bitlist parsing tests ---

    @Test
    @DisplayName("Empty bitlist returns 0 attesting and 0 expected")
    void emptyBitlist() {
        assertEquals(0, AttestationTracker.countSetBits(""));
        assertEquals(0, AttestationTracker.bitlistLength(""));
        assertEquals(0, AttestationTracker.countSetBits(null));
        assertEquals(0, AttestationTracker.bitlistLength(null));
    }

    @Test
    @DisplayName("Single byte bitlist with sentinel only (0x01) — 0 attesting, 0 expected")
    void singleByteSentinelOnly() {
        // 0x01 = 0b00000001 — sentinel at bit 0, committee size = 0
        assertEquals(0, AttestationTracker.countSetBits("0x01"));
        assertEquals(0, AttestationTracker.bitlistLength("0x01"));
    }

    @Test
    @DisplayName("Small committee of 4, all attesting")
    void smallCommitteeAllAttesting() {
        // Committee size 4: sentinel at bit 4 → 0b00011111 = 0x1f
        // All 4 bits set before sentinel
        assertEquals(4, AttestationTracker.countSetBits("0x1f"));
        assertEquals(4, AttestationTracker.bitlistLength("0x1f"));
    }

    @Test
    @DisplayName("Small committee of 4, partial attesting")
    void smallCommitteePartialAttesting() {
        // Committee size 4: sentinel at bit 4 → 0b00010101 = 0x15
        // Bits 0 and 2 set (2 attesting out of 4)
        assertEquals(2, AttestationTracker.countSetBits("0x15"));
        assertEquals(4, AttestationTracker.bitlistLength("0x15"));
    }

    @Test
    @DisplayName("Small committee of 7, all attesting")
    void committeeOf7AllAttesting() {
        // Committee size 7: sentinel at bit 7 → 0b11111111 = 0xff
        // All 7 bits set before sentinel
        assertEquals(7, AttestationTracker.countSetBits("0xff"));
        assertEquals(7, AttestationTracker.bitlistLength("0xff"));
    }

    @Test
    @DisplayName("Two-byte bitlist, committee size 8, all attesting")
    void twoByteAllAttesting() {
        // Committee size 8: 8 bits in first byte, sentinel at bit 0 of second byte
        // First byte: 0xff (all 8 set), Second byte: 0x01 (sentinel)
        assertEquals(8, AttestationTracker.countSetBits("0xff01"));
        assertEquals(8, AttestationTracker.bitlistLength("0xff01"));
    }

    @Test
    @DisplayName("Two-byte bitlist, committee size 8, partial attesting")
    void twoBytePartialAttesting() {
        // Committee size 8, 5 attesting
        // First byte: 0b01010111 = 0x57 (bits 0,1,2,4,6 → 5 set), Second byte: 0x01 (sentinel)
        assertEquals(5, AttestationTracker.countSetBits("0x5701"));
        assertEquals(8, AttestationTracker.bitlistLength("0x5701"));
    }

    @Test
    @DisplayName("Multi-byte bitlist for typical committee size 128")
    void typicalCommittee128() {
        // Committee size 128 = 16 bytes of data + sentinel at bit 0 of 17th byte
        // All attesting: 16 bytes of 0xff + 0x01
        StringBuilder hex = new StringBuilder("0x");
        for (int i = 0; i < 16; i++) {
            hex.append("ff");
        }
        hex.append("01");
        assertEquals(128, AttestationTracker.countSetBits(hex.toString()));
        assertEquals(128, AttestationTracker.bitlistLength(hex.toString()));
    }

    @Test
    @DisplayName("Without 0x prefix")
    void withoutPrefix() {
        assertEquals(4, AttestationTracker.countSetBits("1f"));
        assertEquals(4, AttestationTracker.bitlistLength("1f"));
    }

    @Test
    @DisplayName("With 0X prefix (uppercase)")
    void withUppercasePrefix() {
        assertEquals(4, AttestationTracker.countSetBits("0X1f"));
        assertEquals(4, AttestationTracker.bitlistLength("0X1f"));
    }

    @Test
    @DisplayName("Odd-length hex returns 0")
    void oddLengthHex() {
        assertEquals(0, AttestationTracker.countSetBits("0xf"));
        assertEquals(0, AttestationTracker.bitlistLength("0xf"));
    }

    // --- Tracking update tests ---

    @Test
    @DisplayName("Tracking update stores confidence by block number and hash")
    void trackingUpdateStoresByBlockAndHash() {
        Instant now = Instant.now();
        tracker.updateTrackingResult(1000L, "0xhash1000", 99L, 2, now);

        AttestationConfidence byNumber = tracker.getConfidence(1000L);
        AttestationConfidence byHash = tracker.getConfidenceByHash("0xhash1000");

        assertNotNull(byNumber);
        assertNotNull(byHash);
        assertEquals(1000L, byNumber.getExecutionBlockNumber());
        assertEquals("0xhash1000", byNumber.getExecutionBlockHash());
        assertEquals(99L, byNumber.getSlot());
        assertEquals(2, byNumber.getAttestingValidators());
        assertEquals(3, byNumber.getExpectedValidators());
        assertEquals(66.666, byNumber.getConfidencePercent(), 0.01);
        assertEquals(2, byNumber.getAttestationRound());
    }

    @Test
    @DisplayName("Tracking update clamps round to 3")
    void trackingUpdateClampsRound() {
        tracker.updateTrackingResult(1000L, "0xhash1000", 99L, 7, Instant.now());
        AttestationConfidence confidence = tracker.getConfidence(1000L);
        assertNotNull(confidence);
        assertEquals(3, confidence.getAttestationRound());
        assertEquals(100.0, confidence.getConfidencePercent(), 0.001);
    }

    @Test
    @DisplayName("getRecentConfidencesByHash returns all tracked hashes")
    void recentConfidencesByHashReturnsAll() {
        tracker.updateTrackingResult(999L, "0xhash999", 98L, 1, Instant.now());
        tracker.updateTrackingResult(1000L, "0xhash1000", 99L, 2, Instant.now());

        Map<String, AttestationConfidence> recent = tracker.getRecentConfidencesByHash();
        assertEquals(2, recent.size());
        assertNotNull(recent.get("0xhash999"));
        assertNotNull(recent.get("0xhash1000"));
    }
}

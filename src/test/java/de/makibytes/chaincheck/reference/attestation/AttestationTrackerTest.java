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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.makibytes.chaincheck.model.AttestationConfidence;

class AttestationTrackerTest {

    private AttestationTracker tracker;
    private final ObjectMapper mapper = new ObjectMapper();

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

    // --- Confidence computation tests ---

    @Test
    @DisplayName("Process attestations computes correct confidence")
    void processAttestationsCorrectConfidence() throws Exception {
        // Simulate attestations JSON for a block at slot 100, attesting for slot 99
        // Committee of 4, all attesting: aggregation_bits = 0x1f
        String json = """
                {
                    "data": [
                        {
                            "aggregation_bits": "0x1f",
                            "data": {
                                "slot": "99",
                                "index": "0",
                                "beacon_block_root": "0xabc",
                                "source": {"epoch": "10", "root": "0x111"},
                                "target": {"epoch": "10", "root": "0x222"}
                            }
                        }
                    ]
                }
                """;
        JsonNode attestationsJson = mapper.readTree(json);

        Map<Long, AttestationTracker.SlotBlock> slotCache = Map.of(
                99L, new AttestationTracker.SlotBlock(1000L, "0xblockhash99"));

        tracker.processBlockAttestations(100, attestationsJson, slotCache);

        AttestationConfidence confidence = tracker.getConfidence(1000L);
        assertNotNull(confidence);
        assertEquals(1000L, confidence.getExecutionBlockNumber());
        assertEquals("0xblockhash99", confidence.getExecutionBlockHash());
        assertEquals(99L, confidence.getSlot());
        assertEquals(4, confidence.getAttestingValidators());
        assertEquals(4, confidence.getExpectedValidators());
        assertEquals(100.0, confidence.getConfidencePercent(), 0.001);
    }

    @Test
    @DisplayName("Multiple attestations for same slot are aggregated")
    void multipleAttestationsAggregated() throws Exception {
        // Two committees attesting for slot 99
        String json = """
                {
                    "data": [
                        {
                            "aggregation_bits": "0x15",
                            "data": {"slot": "99", "index": "0", "beacon_block_root": "0xabc",
                                     "source": {"epoch": "10", "root": "0x1"}, "target": {"epoch": "10", "root": "0x2"}}
                        },
                        {
                            "aggregation_bits": "0x1f",
                            "data": {"slot": "99", "index": "1", "beacon_block_root": "0xabc",
                                     "source": {"epoch": "10", "root": "0x1"}, "target": {"epoch": "10", "root": "0x2"}}
                        }
                    ]
                }
                """;
        JsonNode attestationsJson = mapper.readTree(json);

        Map<Long, AttestationTracker.SlotBlock> slotCache = Map.of(
                99L, new AttestationTracker.SlotBlock(1000L, "0xhash99"));

        tracker.processBlockAttestations(100, attestationsJson, slotCache);

        AttestationConfidence confidence = tracker.getConfidence(1000L);
        assertNotNull(confidence);
        // First attestation: 2 of 4, Second: 4 of 4 → total: 6 of 8
        assertEquals(6, confidence.getAttestingValidators());
        assertEquals(8, confidence.getExpectedValidators());
        assertEquals(75.0, confidence.getConfidencePercent(), 0.001);
    }

    @Test
    @DisplayName("Attestations for slots without block mapping are ignored")
    void missingSlotMappingIgnored() throws Exception {
        String json = """
                {
                    "data": [
                        {
                            "aggregation_bits": "0x1f",
                            "data": {"slot": "99", "index": "0", "beacon_block_root": "0xabc",
                                     "source": {"epoch": "10", "root": "0x1"}, "target": {"epoch": "10", "root": "0x2"}}
                        }
                    ]
                }
                """;
        JsonNode attestationsJson = mapper.readTree(json);

        // No slot cache entry for slot 99
        Map<Long, AttestationTracker.SlotBlock> slotCache = Map.of();

        tracker.processBlockAttestations(100, attestationsJson, slotCache);

        assertNull(tracker.getConfidence(1000L));
        assertTrue(tracker.getRecentConfidences().isEmpty());
    }

    @Test
    @DisplayName("Higher attesting count updates existing confidence")
    void higherCountUpdates() throws Exception {
        String json1 = """
                {
                    "data": [
                        {
                            "aggregation_bits": "0x15",
                            "data": {"slot": "99", "index": "0", "beacon_block_root": "0xabc",
                                     "source": {"epoch": "10", "root": "0x1"}, "target": {"epoch": "10", "root": "0x2"}}
                        }
                    ]
                }
                """;
        String json2 = """
                {
                    "data": [
                        {
                            "aggregation_bits": "0x1f",
                            "data": {"slot": "99", "index": "0", "beacon_block_root": "0xabc",
                                     "source": {"epoch": "10", "root": "0x1"}, "target": {"epoch": "10", "root": "0x2"}}
                        }
                    ]
                }
                """;

        Map<Long, AttestationTracker.SlotBlock> slotCache = Map.of(
                99L, new AttestationTracker.SlotBlock(1000L, "0xhash99"));

        tracker.processBlockAttestations(100, mapper.readTree(json1), slotCache);
        assertEquals(2, tracker.getConfidence(1000L).getAttestingValidators());

        tracker.processBlockAttestations(101, mapper.readTree(json2), slotCache);
        assertEquals(4, tracker.getConfidence(1000L).getAttestingValidators());
    }

    @Test
    @DisplayName("Null or empty attestations array is handled gracefully")
    void nullAttestationsHandled() {
        tracker.processBlockAttestations(100, null, Map.of());
        assertTrue(tracker.getRecentConfidences().isEmpty());
    }

    @Test
    @DisplayName("getRecentConfidences returns all tracked blocks")
    void recentConfidencesReturnsAll() throws Exception {
        String json = """
                {
                    "data": [
                        {
                            "aggregation_bits": "0x1f",
                            "data": {"slot": "98", "index": "0", "beacon_block_root": "0xabc",
                                     "source": {"epoch": "10", "root": "0x1"}, "target": {"epoch": "10", "root": "0x2"}}
                        },
                        {
                            "aggregation_bits": "0x1f",
                            "data": {"slot": "99", "index": "0", "beacon_block_root": "0xabc",
                                     "source": {"epoch": "10", "root": "0x1"}, "target": {"epoch": "10", "root": "0x2"}}
                        }
                    ]
                }
                """;

        Map<Long, AttestationTracker.SlotBlock> slotCache = Map.of(
                98L, new AttestationTracker.SlotBlock(999L, "0xhash98"),
                99L, new AttestationTracker.SlotBlock(1000L, "0xhash99"));

        tracker.processBlockAttestations(100, mapper.readTree(json), slotCache);

        Map<Long, AttestationConfidence> recent = tracker.getRecentConfidences();
        assertEquals(2, recent.size());
        assertNotNull(recent.get(999L));
        assertNotNull(recent.get(1000L));
    }
}

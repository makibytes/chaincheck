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

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

import de.makibytes.chaincheck.model.AttestationConfidence;

public class AttestationTracker {

    private static final Logger logger = LoggerFactory.getLogger(AttestationTracker.class);
    private static final Duration RETENTION = Duration.ofHours(2);

    private final ConcurrentSkipListMap<Long, AttestationConfidence> confidenceBySlot = new ConcurrentSkipListMap<>();
    private final ConcurrentHashMap<Long, AttestationConfidence> confidenceByBlock = new ConcurrentHashMap<>();

    public void processBlockAttestations(long headSlot,
                                         JsonNode attestationsResponse,
                                         Map<Long, SlotBlock> slotBlockCache) {
        if (attestationsResponse == null) {
            return;
        }

        // The response may be wrapped in a "data" field or be the array directly
        JsonNode attestations = attestationsResponse.has("data") ? attestationsResponse.get("data") : attestationsResponse;
        if (!attestations.isArray() || attestations.isEmpty()) {
            return;
        }

        // Group attestations by target slot, accumulating attesting and expected counts
        Map<Long, int[]> countsBySlot = new HashMap<>(); // [attesting, expected]

        for (JsonNode attestation : attestations) {
            JsonNode data = attestation.path("data");
            long targetSlot = data.path("slot").asLong(-1);
            if (targetSlot < 0) {
                continue;
            }
            String aggregationBits = attestation.path("aggregation_bits").asText(null);
            if (aggregationBits == null || aggregationBits.isBlank()) {
                continue;
            }

            int attesting = countSetBits(aggregationBits);
            int total = bitlistLength(aggregationBits);
            if (total <= 0) {
                continue;
            }

            countsBySlot.merge(targetSlot, new int[]{attesting, total},
                    (existing, incoming) -> new int[]{existing[0] + incoming[0], existing[1] + incoming[1]});
        }

        Instant now = Instant.now();
        for (Map.Entry<Long, int[]> entry : countsBySlot.entrySet()) {
            long targetSlot = entry.getKey();
            int[] counts = entry.getValue();
            int attesting = counts[0];
            int expected = counts[1];

            SlotBlock slotBlock = slotBlockCache.get(targetSlot);
            long blockNumber = slotBlock != null ? slotBlock.blockNumber() : -1;
            String blockHash = slotBlock != null ? slotBlock.blockHash() : null;

            double confidence = expected > 0 ? (attesting * 100.0) / expected : 0.0;

            AttestationConfidence ac = new AttestationConfidence(
                    blockNumber, blockHash, targetSlot,
                    attesting, expected, confidence, now);

            // Only store if we have a valid block mapping
            if (blockNumber >= 0) {
                AttestationConfidence existing = confidenceBySlot.get(targetSlot);
                if (existing == null || ac.getAttestingValidators() > existing.getAttestingValidators()) {
                    confidenceBySlot.put(targetSlot, ac);
                    confidenceByBlock.put(blockNumber, ac);
                }
            }
        }

        prune();
    }

    public AttestationConfidence getConfidence(long executionBlockNumber) {
        return confidenceByBlock.get(executionBlockNumber);
    }

    public Map<Long, AttestationConfidence> getRecentConfidences() {
        return Map.copyOf(confidenceByBlock);
    }

    private void prune() {
        Instant cutoff = Instant.now().minus(RETENTION);
        confidenceBySlot.entrySet().removeIf(entry -> entry.getValue().getComputedAt().isBefore(cutoff));
        confidenceByBlock.entrySet().removeIf(entry -> entry.getValue().getComputedAt().isBefore(cutoff));
    }

    /**
     * Counts the number of set bits in an SSZ bitlist.
     * The bitlist has a sentinel bit (the highest set bit in the last byte)
     * which marks the length boundary. This bit is not counted as an attesting validator.
     */
    static int countSetBits(String aggregationBitsHex) {
        byte[] bytes = decodeHex(aggregationBitsHex);
        if (bytes.length == 0) {
            return 0;
        }

        int count = 0;
        // Count all bits except the sentinel in the last byte
        for (int i = 0; i < bytes.length - 1; i++) {
            count += Integer.bitCount(bytes[i] & 0xFF);
        }

        // Handle last byte: clear the sentinel bit (highest set bit)
        int lastByte = bytes[bytes.length - 1] & 0xFF;
        if (lastByte != 0) {
            int sentinel = Integer.highestOneBit(lastByte);
            int cleared = lastByte & ~sentinel;
            count += Integer.bitCount(cleared);
        }
        return count;
    }

    /**
     * Returns the total number of bits in the SSZ bitlist (= committee size).
     * The sentinel bit position determines the length.
     */
    static int bitlistLength(String aggregationBitsHex) {
        byte[] bytes = decodeHex(aggregationBitsHex);
        if (bytes.length == 0) {
            return 0;
        }

        int lastByte = bytes[bytes.length - 1] & 0xFF;
        if (lastByte == 0) {
            return 0;
        }

        // Position of sentinel bit within the last byte (0-based from LSB)
        int sentinelPos = 31 - Integer.numberOfLeadingZeros(lastByte);
        // Total bits = full bytes before last byte * 8 + bits before sentinel in last byte
        return (bytes.length - 1) * 8 + sentinelPos;
    }

    private static byte[] decodeHex(String hex) {
        if (hex == null || hex.isBlank()) {
            return new byte[0];
        }
        String normalized = hex.trim();
        if (normalized.startsWith("0x") || normalized.startsWith("0X")) {
            normalized = normalized.substring(2);
        }
        if (normalized.isEmpty() || normalized.length() % 2 != 0) {
            return new byte[0];
        }
        byte[] bytes = new byte[normalized.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int high = Character.digit(normalized.charAt(i * 2), 16);
            int low = Character.digit(normalized.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                return new byte[0];
            }
            bytes[i] = (byte) ((high << 4) | low);
        }
        return bytes;
    }

    public record SlotBlock(long blockNumber, String blockHash) {
    }
}

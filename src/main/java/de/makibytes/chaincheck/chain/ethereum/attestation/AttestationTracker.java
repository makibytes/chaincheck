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
package de.makibytes.chaincheck.chain.ethereum.attestation;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.makibytes.chaincheck.model.AttestationConfidence;
import de.makibytes.chaincheck.monitor.EthHex;

/**
 * Tracks attestation confidence for blocks based on committee data.
 * Computes confidence percentages based on observed attestation rounds.
 */
public class AttestationTracker {

    private static final Logger logger = LoggerFactory.getLogger(AttestationTracker.class);
    
    /**
     * Default retention period for attestation data.
     */
    private static final Duration RETENTION = Duration.ofHours(2);
    
    /**
     * Maximum number of attestation rounds to track per block.
     */
    private static final int MAX_ATTESTATION_ROUNDS = 3;

    private final ConcurrentHashMap<String, AttestationConfidence> confidenceByHash = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AttestationConfidence> confidenceByBlock = new ConcurrentHashMap<>();

    public void updateTrackingResult(long blockNumber,
                                     String blockHash,
                                     long slot,
                                     int attestationRound,
                                     Instant computedAt) {
        if (blockHash == null || blockHash.isBlank()) {
            return;
        }
        int boundedRound = Math.max(0, Math.min(MAX_ATTESTATION_ROUNDS, attestationRound));
        double confidence = (boundedRound / (double) MAX_ATTESTATION_ROUNDS) * 100.0;
        AttestationConfidence value = new AttestationConfidence(
                blockNumber,
                blockHash,
                slot,
                boundedRound,
                MAX_ATTESTATION_ROUNDS,
                confidence,
                computedAt == null ? Instant.now() : computedAt,
                boundedRound);
        confidenceByHash.put(blockHash, value);
        confidenceByBlock.put(blockNumber, value);
        logger.debug("Updated attestation tracking for block {} slot {}: round {}/{}",
                blockNumber, slot, boundedRound, MAX_ATTESTATION_ROUNDS);
        prune();
    }

    public AttestationConfidence getConfidence(long executionBlockNumber) {
        return confidenceByBlock.get(executionBlockNumber);
    }

    public AttestationConfidence getConfidenceByHash(String blockHash) {
        if (blockHash == null || blockHash.isBlank()) {
            return null;
        }
        return confidenceByHash.get(blockHash);
    }

    public Map<Long, AttestationConfidence> getRecentConfidences() {
        return Map.copyOf(confidenceByBlock);
    }

    public Map<String, AttestationConfidence> getRecentConfidencesByHash() {
        return Map.copyOf(confidenceByHash);
    }

    private void prune() {
        Instant cutoff = Instant.now().minus(RETENTION);
        confidenceByHash.entrySet().removeIf(entry -> entry.getValue().getComputedAt().isBefore(cutoff));
        confidenceByBlock.entrySet().removeIf(entry -> entry.getValue().getComputedAt().isBefore(cutoff));
    }

    /**
     * Counts the number of set bits in an SSZ bitlist.
     * The bitlist has a sentinel bit (the highest set bit in the last byte)
     * which marks the length boundary. This bit is not counted as an attesting validator.
     */
    static int countSetBits(String aggregationBitsHex) {
        byte[] bytes = EthHex.decodeHex(aggregationBitsHex);
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
        byte[] bytes = EthHex.decodeHex(aggregationBitsHex);
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

}

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
package de.makibytes.chaincheck.model;

import java.time.Instant;

public class AttestationConfidence {

    private final long executionBlockNumber;
    private final String executionBlockHash;
    private final long slot;
    private final int attestingValidators;
    private final int expectedValidators;
    private final double confidencePercent;
    private final Instant computedAt;
    private final int attestationRound;

    public AttestationConfidence(long executionBlockNumber,
                                 String executionBlockHash,
                                 long slot,
                                 int attestingValidators,
                                 int expectedValidators,
                                 double confidencePercent,
                                 Instant computedAt) {
        this(executionBlockNumber, executionBlockHash, slot, attestingValidators,
             expectedValidators, confidencePercent, computedAt, 1);
    }

    public AttestationConfidence(long executionBlockNumber,
                                 String executionBlockHash,
                                 long slot,
                                 int attestingValidators,
                                 int expectedValidators,
                                 double confidencePercent,
                                 Instant computedAt,
                                 int attestationRound) {
        this.executionBlockNumber = executionBlockNumber;
        this.executionBlockHash = executionBlockHash;
        this.slot = slot;
        this.attestingValidators = attestingValidators;
        this.expectedValidators = expectedValidators;
        this.confidencePercent = confidencePercent;
        this.computedAt = computedAt;
        this.attestationRound = attestationRound;
    }

    public long getExecutionBlockNumber() {
        return executionBlockNumber;
    }

    public String getExecutionBlockHash() {
        return executionBlockHash;
    }

    public long getSlot() {
        return slot;
    }

    public int getAttestingValidators() {
        return attestingValidators;
    }

    public int getExpectedValidators() {
        return expectedValidators;
    }

    public double getConfidencePercent() {
        return confidencePercent;
    }

    public Instant getComputedAt() {
        return computedAt;
    }

    public int getAttestationRound() {
        return attestationRound;
    }
}

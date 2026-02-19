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
package de.makibytes.chaincheck.web;

import java.util.List;

public record SampleRow(
    String time,
    List<String> sources,
    String status,
    Long latencyMs,
    Long blockNumber,
    String blockHash,
    String parentHash,
    String blockTime,
    boolean safe,
    boolean finalized,
    boolean invalid,
    boolean conflict,
    Integer transactionCount,
    Long gasPriceWei,
    Double attestationConfidence,
    Long attestationSlot
) {
    public SampleRow {
        sources = sources != null ? List.copyOf(sources) : List.of();
    }

    public SampleRow(String time,
                     List<String> sources,
                     String status,
                     Long latencyMs,
                     Long blockNumber,
                     String blockHash,
                     String parentHash,
                     String blockTime,
                     boolean safe,
                     boolean finalized,
                     boolean invalid,
                     boolean conflict,
                     Integer transactionCount,
                     Long gasPriceWei) {
        this(time, sources, status, latencyMs, blockNumber, blockHash, parentHash,
             blockTime, safe, finalized, invalid, conflict, transactionCount, gasPriceWei, null, null);
    }

    public SampleRow withStatus(String newStatus) {
        return new SampleRow(
            time,
            sources,
            newStatus,
            latencyMs,
            blockNumber,
            blockHash,
            parentHash,
            blockTime,
            safe,
            finalized,
            invalid,
            conflict,
            transactionCount,
            gasPriceWei,
            attestationConfidence,
            attestationSlot
        );
    }

    public SampleRow withFinalized(boolean newFinalized) {
        return new SampleRow(
            time,
            sources,
            status,
            latencyMs,
            blockNumber,
            blockHash,
            parentHash,
            blockTime,
            safe,
            newFinalized,
            invalid,
            conflict,
            transactionCount,
            gasPriceWei,
            attestationConfidence,
            attestationSlot
        );
    }

    public SampleRow withSafe(boolean newSafe) {
        return new SampleRow(
            time,
            sources,
            status,
            latencyMs,
            blockNumber,
            blockHash,
            parentHash,
            blockTime,
            newSafe,
            finalized,
            invalid,
            conflict,
            transactionCount,
            gasPriceWei,
            attestationConfidence,
            attestationSlot
        );
    }

    public SampleRow withConflict(boolean newConflict) {
        return new SampleRow(
            time,
            sources,
            status,
            latencyMs,
            blockNumber,
            blockHash,
            parentHash,
            blockTime,
            safe,
            finalized,
            invalid,
            newConflict,
            transactionCount,
            gasPriceWei,
            attestationConfidence,
            attestationSlot
        );
    }

    public SampleRow withInvalid(boolean newInvalid) {
        return new SampleRow(
            time,
            sources,
            status,
            latencyMs,
            blockNumber,
            blockHash,
            parentHash,
            blockTime,
            safe,
            finalized,
            newInvalid,
            conflict,
            transactionCount,
            gasPriceWei,
            attestationConfidence,
            attestationSlot
        );
    }
}

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

public class SampleRow {

    private final String time;
    private final List<String> sources;
    private final String status;
    private final Long latencyMs;
    private final Long blockNumber;
    private final String blockHash;
    private final String parentHash;
    private final String blockTime;
    private final boolean safe;
    private final boolean finalized;
    private final boolean invalid;
    private final Integer transactionCount;
    private final Long gasPriceWei;

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
                     Integer transactionCount,
                     Long gasPriceWei) {
        this.time = time;
        this.sources = sources;
        this.status = status;
        this.latencyMs = latencyMs;
        this.blockNumber = blockNumber;
        this.blockHash = blockHash;
        this.parentHash = parentHash;
        this.blockTime = blockTime;
        this.safe = safe;
        this.finalized = finalized;
        this.invalid = invalid;
        this.transactionCount = transactionCount;
        this.gasPriceWei = gasPriceWei;
    }

    public String getTime() {
        return time;
    }

    public List<String> getSources() {
        return sources;
    }

    public String getStatus() {
        return status;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public Long getBlockNumber() {
        return blockNumber;
    }

    public String getBlockHash() {
        return blockHash;
    }

    public String getParentHash() {
        return parentHash;
    }

    public String getBlockTime() {
        return blockTime;
    }

    public boolean isSafe() {
        return safe;
    }

    public boolean isFinalized() {
        return finalized;
    }

    public boolean isInvalid() {
        return invalid;
    }

    public Integer getTransactionCount() {
        return transactionCount;
    }

    public Long getGasPriceWei() {
        return gasPriceWei;
    }
}
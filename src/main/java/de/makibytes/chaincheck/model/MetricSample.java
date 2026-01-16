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

public class MetricSample {

    private final Instant timestamp;
    private final MetricSource source;
    private final boolean success;
    private final long latencyMs;
    private final Long blockNumber;
    private final Instant blockTimestamp;
    private final String blockHash;
    private final String parentHash;
    private final Integer transactionCount;
    private final Long gasPriceWei;
    private final String error;

    public MetricSample(Instant timestamp,
                        MetricSource source,
                        boolean success,
                        long latencyMs,
                        Long blockNumber,
                        Instant blockTimestamp,
                        String blockHash,
                        String parentHash,
                        Integer transactionCount,
                        Long gasPriceWei,
                        String error) {
        this.timestamp = timestamp;
        this.source = source;
        this.success = success;
        this.latencyMs = latencyMs;
        this.blockNumber = blockNumber;
        this.blockTimestamp = blockTimestamp;
        this.blockHash = blockHash;
        this.parentHash = parentHash;
        this.transactionCount = transactionCount;
        this.gasPriceWei = gasPriceWei;
        this.error = error;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public MetricSource getSource() {
        return source;
    }

    public boolean isSuccess() {
        return success;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public Long getBlockNumber() {
        return blockNumber;
    }

    public Instant getBlockTimestamp() {
        return blockTimestamp;
    }

    public String getBlockHash() {
        return blockHash;
    }

    public String getParentHash() {
        return parentHash;
    }

    public Integer getTransactionCount() {
        return transactionCount;
    }

    public Long getGasPriceWei() {
        return gasPriceWei;
    }

    public String getError() {
        return error;
    }
}

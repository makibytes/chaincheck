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
    private final Long headDelayMs;
    private final Long safeDelayMs;
    private final Long finalizedDelayMs;

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
                        String error,
                        Long headDelayMs,
                        Long safeDelayMs,
                        Long finalizedDelayMs) {
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
        this.headDelayMs = headDelayMs;
        this.safeDelayMs = safeDelayMs;
        this.finalizedDelayMs = finalizedDelayMs;
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

    public Long getHeadDelayMs() {
        return headDelayMs;
    }

    public Long getSafeDelayMs() {
        return safeDelayMs;
    }

    public Long getFinalizedDelayMs() {
        return finalizedDelayMs;
    }

    public static Builder builder(Instant timestamp, MetricSource source) {
        return new Builder(timestamp, source);
    }

    public static class Builder {
        private final Instant timestamp;
        private final MetricSource source;
        private boolean success;
        private long latencyMs = -1;
        private Long blockNumber;
        private Instant blockTimestamp;
        private String blockHash;
        private String parentHash;
        private Integer transactionCount;
        private Long gasPriceWei;
        private String error;
        private Long headDelayMs;
        private Long safeDelayMs;
        private Long finalizedDelayMs;

        private Builder(Instant timestamp, MetricSource source) {
            this.timestamp = timestamp;
            this.source = source;
        }

        public Builder success(boolean success) { this.success = success; return this; }
        public Builder latencyMs(long latencyMs) { this.latencyMs = latencyMs; return this; }
        public Builder blockNumber(Long blockNumber) { this.blockNumber = blockNumber; return this; }
        public Builder blockTimestamp(Instant blockTimestamp) { this.blockTimestamp = blockTimestamp; return this; }
        public Builder blockHash(String blockHash) { this.blockHash = blockHash; return this; }
        public Builder parentHash(String parentHash) { this.parentHash = parentHash; return this; }
        public Builder transactionCount(Integer transactionCount) { this.transactionCount = transactionCount; return this; }
        public Builder gasPriceWei(Long gasPriceWei) { this.gasPriceWei = gasPriceWei; return this; }
        public Builder error(String error) { this.error = error; return this; }
        public Builder headDelayMs(Long headDelayMs) { this.headDelayMs = headDelayMs; return this; }
        public Builder safeDelayMs(Long safeDelayMs) { this.safeDelayMs = safeDelayMs; return this; }
        public Builder finalizedDelayMs(Long finalizedDelayMs) { this.finalizedDelayMs = finalizedDelayMs; return this; }

        public MetricSample build() {
            return new MetricSample(timestamp, source, success, latencyMs, blockNumber,
                    blockTimestamp, blockHash, parentHash, transactionCount, gasPriceWei,
                    error, headDelayMs, safeDelayMs, finalizedDelayMs);
        }
    }
}

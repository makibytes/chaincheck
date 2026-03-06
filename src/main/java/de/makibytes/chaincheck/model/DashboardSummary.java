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

public record DashboardSummary(long totalSamples,
                               long httpSamples,
                               long wsSamples,
                               long successCount,
                               long errorCount,
                               double avgLatencyMs,
                               long maxLatencyMs,
                               double p5LatencyMs,
                               double p25LatencyMs,
                               double p75LatencyMs,
                               double p95LatencyMs,
                               double p99LatencyMs,
                               double uptimePercent,
                               double errorRatePercent,
                               double avgNewBlockPropagationMs,
                               double p95NewBlockPropagationMs,
                               double p99NewBlockPropagationMs,
                               double avgSafeBlockPropagationMs,
                               double p95SafeBlockPropagationMs,
                               double p99SafeBlockPropagationMs,
                               double avgFinalizedBlockPropagationMs,
                               double p95FinalizedBlockPropagationMs,
                               double p99FinalizedBlockPropagationMs,
                               long staleBlockCount,
                               long blockLagBlocks,
                               long delayCount,
                               long reorgCount,
                               long blockGapCount,
                               long rateLimitCount,
                               long timeoutCount,
                               long wrongHeadCount,
                               long conflictCount,
                               long errorAnomalyCount,
                               double canonicalRatePercent,
                               long invalidBlockCount,
                               double wrongHeadRatePercent,
                               long maxReorgDepth,
                               long maxGapSize,
                               double avgFirstSeenDeltaMs,
                               double p95FirstSeenDeltaMs,
                               int healthScore) {

    public long getTotalSamples() {
        return totalSamples;
    }

    public long getHttpSamples() {
        return httpSamples;
    }

    public long getWsSamples() {
        return wsSamples;
    }

    public long getSuccessCount() {
        return successCount;
    }

    public long getErrorCount() {
        return errorCount;
    }

    public double getAvgLatencyMs() {
        return avgLatencyMs;
    }

    public long getMaxLatencyMs() {
        return maxLatencyMs;
    }

    public double getP5LatencyMs() {
        return p5LatencyMs;
    }

    public double getP25LatencyMs() {
        return p25LatencyMs;
    }

    public double getP75LatencyMs() {
        return p75LatencyMs;
    }

    public double getP95LatencyMs() {
        return p95LatencyMs;
    }

    public double getP99LatencyMs() {
        return p99LatencyMs;
    }

    public double getUptimePercent() {
        return uptimePercent;
    }

    public double getErrorRatePercent() {
        return errorRatePercent;
    }

    public double getAvgNewBlockPropagationMs() {
        return avgNewBlockPropagationMs;
    }

    public double getP95NewBlockPropagationMs() {
        return p95NewBlockPropagationMs;
    }

    public double getP99NewBlockPropagationMs() {
        return p99NewBlockPropagationMs;
    }

    public double getAvgSafeBlockPropagationMs() {
        return avgSafeBlockPropagationMs;
    }

    public double getP95SafeBlockPropagationMs() {
        return p95SafeBlockPropagationMs;
    }

    public double getP99SafeBlockPropagationMs() {
        return p99SafeBlockPropagationMs;
    }

    public double getAvgFinalizedBlockPropagationMs() {
        return avgFinalizedBlockPropagationMs;
    }

    public double getP95FinalizedBlockPropagationMs() {
        return p95FinalizedBlockPropagationMs;
    }

    public double getP99FinalizedBlockPropagationMs() {
        return p99FinalizedBlockPropagationMs;
    }

    public long getStaleBlockCount() {
        return staleBlockCount;
    }

    public long getBlockLagBlocks() {
        return blockLagBlocks;
    }

    public long getDelayCount() {
        return delayCount;
    }

    public long getReorgCount() {
        return reorgCount;
    }

    public long getBlockGapCount() {
        return blockGapCount;
    }

    public long getRateLimitCount() {
        return rateLimitCount;
    }

    public long getTimeoutCount() {
        return timeoutCount;
    }

    public long getWrongHeadCount() {
        return wrongHeadCount;
    }

    public long getConflictCount() {
        return conflictCount;
    }

    public long getErrorAnomalyCount() {
        return errorAnomalyCount;
    }

    public double getCanonicalRatePercent() {
        return canonicalRatePercent;
    }

    public long getInvalidBlockCount() {
        return invalidBlockCount;
    }

    public double getWrongHeadRatePercent() {
        return wrongHeadRatePercent;
    }

    public long getMaxReorgDepth() {
        return maxReorgDepth;
    }

    public long getMaxGapSize() {
        return maxGapSize;
    }

    public double getAvgFirstSeenDeltaMs() {
        return avgFirstSeenDeltaMs;
    }

    public double getP95FirstSeenDeltaMs() {
        return p95FirstSeenDeltaMs;
    }

    public int getHealthScore() {
        return healthScore;
    }
}

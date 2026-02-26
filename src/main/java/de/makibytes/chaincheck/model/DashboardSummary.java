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

public class DashboardSummary {

    private final long totalSamples;
    private final long httpSamples;
    private final long wsSamples;
    private final long successCount;
    private final long errorCount;
    private final double avgLatencyMs;
    private final long maxLatencyMs;
    private final double p5LatencyMs;
    private final double p25LatencyMs;
    private final double p75LatencyMs;
    private final double p95LatencyMs;
    private final double p99LatencyMs;
    private final double uptimePercent;
    private final double errorRatePercent;
    private final double avgNewBlockPropagationMs;
    private final double p95NewBlockPropagationMs;
    private final double p99NewBlockPropagationMs;
    private final double avgSafeBlockPropagationMs;
    private final double p95SafeBlockPropagationMs;
    private final double p99SafeBlockPropagationMs;
    private final double avgFinalizedBlockPropagationMs;
    private final double p95FinalizedBlockPropagationMs;
    private final double p99FinalizedBlockPropagationMs;
    private final long staleBlockCount;
    private final long blockLagBlocks;
    private final long delayCount;
    private final long reorgCount;
    private final long blockGapCount;
    private final long rateLimitCount;
    private final long timeoutCount;
    private final long wrongHeadCount;
    private final long conflictCount;
    private final long errorAnomalyCount;
    private final double canonicalRatePercent;
    private final long invalidBlockCount;
    private final double wrongHeadRatePercent;
    private final long maxReorgDepth;
    private final long maxGapSize;
    private final double avgFirstSeenDeltaMs;
    private final double p95FirstSeenDeltaMs;
    private final int healthScore;

    public DashboardSummary(long totalSamples,
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
        this.totalSamples = totalSamples;
        this.httpSamples = httpSamples;
        this.wsSamples = wsSamples;
        this.successCount = successCount;
        this.errorCount = errorCount;
        this.avgLatencyMs = avgLatencyMs;
        this.maxLatencyMs = maxLatencyMs;
        this.p5LatencyMs = p5LatencyMs;
        this.p25LatencyMs = p25LatencyMs;
        this.p75LatencyMs = p75LatencyMs;
        this.p95LatencyMs = p95LatencyMs;
        this.p99LatencyMs = p99LatencyMs;
        this.uptimePercent = uptimePercent;
        this.errorRatePercent = errorRatePercent;
        this.avgNewBlockPropagationMs = avgNewBlockPropagationMs;
        this.p95NewBlockPropagationMs = p95NewBlockPropagationMs;
        this.p99NewBlockPropagationMs = p99NewBlockPropagationMs;
        this.avgSafeBlockPropagationMs = avgSafeBlockPropagationMs;
        this.p95SafeBlockPropagationMs = p95SafeBlockPropagationMs;
        this.p99SafeBlockPropagationMs = p99SafeBlockPropagationMs;
        this.avgFinalizedBlockPropagationMs = avgFinalizedBlockPropagationMs;
        this.p95FinalizedBlockPropagationMs = p95FinalizedBlockPropagationMs;
        this.p99FinalizedBlockPropagationMs = p99FinalizedBlockPropagationMs;
        this.staleBlockCount = staleBlockCount;
        this.blockLagBlocks = blockLagBlocks;
        this.delayCount = delayCount;
        this.reorgCount = reorgCount;
        this.blockGapCount = blockGapCount;
        this.rateLimitCount = rateLimitCount;
        this.timeoutCount = timeoutCount;
        this.wrongHeadCount = wrongHeadCount;
        this.conflictCount = conflictCount;
        this.errorAnomalyCount = errorAnomalyCount;
        this.canonicalRatePercent = canonicalRatePercent;
        this.invalidBlockCount = invalidBlockCount;
        this.wrongHeadRatePercent = wrongHeadRatePercent;
        this.maxReorgDepth = maxReorgDepth;
        this.maxGapSize = maxGapSize;
        this.avgFirstSeenDeltaMs = avgFirstSeenDeltaMs;
        this.p95FirstSeenDeltaMs = p95FirstSeenDeltaMs;
        this.healthScore = healthScore;
    }

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

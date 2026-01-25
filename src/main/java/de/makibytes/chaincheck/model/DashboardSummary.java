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
    private final long p95LatencyMs;
    private final long p99LatencyMs;
    private final double httpRps;
    private final double wsEventsPerMinute;
    private final double uptimePercent;
    private final double errorRatePercent;
    private final double avgNewBlockPropagationMs;
    private final long p95NewBlockPropagationMs;
    private final long p99NewBlockPropagationMs;
    private final double avgSafeBlockPropagationMs;
    private final long p95SafeBlockPropagationMs;
    private final long p99SafeBlockPropagationMs;
    private final double avgFinalizedBlockPropagationMs;
    private final long p95FinalizedBlockPropagationMs;
    private final long p99FinalizedBlockPropagationMs;
    private final long staleBlockCount;
    private final long blockLagBlocks;
    private final long delayCount;
    private final long reorgCount;
    private final long blockGapCount;
    private final long wrongHeadCount;

    public DashboardSummary(long totalSamples,
                            long httpSamples,
                            long wsSamples,
                            long successCount,
                            long errorCount,
                            double avgLatencyMs,
                            long maxLatencyMs,
                            long p95LatencyMs,
                            long p99LatencyMs,
                            double httpRps,
                            double wsEventsPerMinute,
                            double uptimePercent,
                            double errorRatePercent,
                            double avgNewBlockPropagationMs,
                            long p95NewBlockPropagationMs,
                            long p99NewBlockPropagationMs,
                            double avgSafeBlockPropagationMs,
                            long p95SafeBlockPropagationMs,
                            long p99SafeBlockPropagationMs,
                            double avgFinalizedBlockPropagationMs,
                            long p95FinalizedBlockPropagationMs,
                            long p99FinalizedBlockPropagationMs,
                            long staleBlockCount,
                            long blockLagBlocks,
                            long delayCount,
                            long reorgCount,
                            long blockGapCount,
                            long wrongHeadCount) {
        this.totalSamples = totalSamples;
        this.httpSamples = httpSamples;
        this.wsSamples = wsSamples;
        this.successCount = successCount;
        this.errorCount = errorCount;
        this.avgLatencyMs = avgLatencyMs;
        this.maxLatencyMs = maxLatencyMs;
        this.p95LatencyMs = p95LatencyMs;
        this.p99LatencyMs = p99LatencyMs;
        this.httpRps = httpRps;
        this.wsEventsPerMinute = wsEventsPerMinute;
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
        this.wrongHeadCount = wrongHeadCount;
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

    public long getP95LatencyMs() {
        return p95LatencyMs;
    }

    public long getP99LatencyMs() {
        return p99LatencyMs;
    }

    public double getHttpRps() {
        return httpRps;
    }

    public double getWsEventsPerMinute() {
        return wsEventsPerMinute;
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

    public long getP95NewBlockPropagationMs() {
        return p95NewBlockPropagationMs;
    }

    public long getP99NewBlockPropagationMs() {
        return p99NewBlockPropagationMs;
    }

    public double getAvgSafeBlockPropagationMs() {
        return avgSafeBlockPropagationMs;
    }

    public long getP95SafeBlockPropagationMs() {
        return p95SafeBlockPropagationMs;
    }

    public long getP99SafeBlockPropagationMs() {
        return p99SafeBlockPropagationMs;
    }

    public double getAvgFinalizedBlockPropagationMs() {
        return avgFinalizedBlockPropagationMs;
    }

    public long getP95FinalizedBlockPropagationMs() {
        return p95FinalizedBlockPropagationMs;
    }

    public long getP99FinalizedBlockPropagationMs() {
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

    public long getWrongHeadCount() {
        return wrongHeadCount;
    }
}

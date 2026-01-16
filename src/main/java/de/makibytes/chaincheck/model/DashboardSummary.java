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
    private final double avgPropagationDelayMs;
    private final long p95PropagationDelayMs;
    private final long maxPropagationDelayMs;
    private final long staleBlockCount;
    private final long blockLagBlocks;
    private final long delayCount;
    private final long reorgCount;
    private final long blockGapCount;

    public DashboardSummary(long totalSamples,
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
                            double avgPropagationDelayMs,
                            long p95PropagationDelayMs,
                            long maxPropagationDelayMs,
                            long staleBlockCount,
                            long blockLagBlocks,
                            long delayCount,
                            long reorgCount,
                            long blockGapCount) {
        this.totalSamples = totalSamples;
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
        this.avgPropagationDelayMs = avgPropagationDelayMs;
        this.p95PropagationDelayMs = p95PropagationDelayMs;
        this.maxPropagationDelayMs = maxPropagationDelayMs;
        this.staleBlockCount = staleBlockCount;
        this.blockLagBlocks = blockLagBlocks;
        this.delayCount = delayCount;
        this.reorgCount = reorgCount;
        this.blockGapCount = blockGapCount;
    }

    public long getTotalSamples() {
        return totalSamples;
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

    public double getAvgPropagationDelayMs() {
        return avgPropagationDelayMs;
    }

    public long getP95PropagationDelayMs() {
        return p95PropagationDelayMs;
    }

    public long getMaxPropagationDelayMs() {
        return maxPropagationDelayMs;
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
}

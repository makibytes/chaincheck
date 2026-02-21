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
package de.makibytes.chaincheck.store;

import java.time.Duration;
import java.time.Instant;

import de.makibytes.chaincheck.model.MetricSample;
import de.makibytes.chaincheck.model.MetricSource;

public class SampleAggregate {

    private final Instant bucketStart;
    private long totalCount;
    private long successCount;
    private long errorCount;
    private long latencySumMs;
    private long latencyCount;
    private long minLatencyMs = Long.MAX_VALUE;
    private long maxLatencyMs;
    private long httpCount;
    private long wsCount;
    private long wsErrorCount;
    private long propagationDelaySumMs;
    private long propagationDelayCount;
    private long maxPropagationDelayMs;
    private long staleBlockCount;
    private long headDelaySumMs;
    private long headDelayCount;
    private long minHeadDelayMs = Long.MAX_VALUE;
    private long maxHeadDelayMs;
    private long safeDelaySumMs;
    private long safeDelayCount;
    private long minSafeDelayMs = Long.MAX_VALUE;
    private long maxSafeDelayMs;
    private long finalizedDelaySumMs;
    private long finalizedDelayCount;
    private long minFinalizedDelayMs = Long.MAX_VALUE;
    private long maxFinalizedDelayMs;
    private long[] latencyHistogram;
    private long[] headDelayHistogram;
    private long[] safeDelayHistogram;
    private long[] finalizedDelayHistogram;

    public SampleAggregate(Instant bucketStart) {
        this.bucketStart = bucketStart;
    }

    public void addSample(MetricSample sample) {
        totalCount++;
        if (sample.isSuccess()) {
            successCount++;
        } else {
            errorCount++;
        }
        if (sample.getLatencyMs() >= 0) {
            long lat = sample.getLatencyMs();
            latencySumMs += lat;
            latencyCount++;
            minLatencyMs = Math.min(minLatencyMs, lat);
            maxLatencyMs = Math.max(maxLatencyMs, lat);
            latencyHistogram = recordToHistogram(latencyHistogram, lat);
        }
        if (sample.getSource() == MetricSource.HTTP) {
            httpCount++;
        } else if (sample.getSource() == MetricSource.WS) {
            wsCount++;
            if (!sample.isSuccess()) {
                wsErrorCount++;
            }
        }
        if (sample.getSource() == MetricSource.HTTP && sample.getBlockTimestamp() != null) {
            long delay = Duration.between(sample.getBlockTimestamp(), sample.getTimestamp()).toMillis();
            if (delay >= 0) {
                propagationDelaySumMs += delay;
                propagationDelayCount++;
                if (delay > maxPropagationDelayMs) {
                    maxPropagationDelayMs = delay;
                }
                if (delay > 30000) {
                    staleBlockCount++;
                }
            }
        }
        if (sample.getSource() == MetricSource.WS && sample.getHeadDelayMs() != null) {
            long hd = sample.getHeadDelayMs();
            headDelaySumMs += hd;
            headDelayCount++;
            minHeadDelayMs = Math.min(minHeadDelayMs, hd);
            maxHeadDelayMs = Math.max(maxHeadDelayMs, hd);
            headDelayHistogram = recordToHistogram(headDelayHistogram, hd);
        }
        if (sample.getSafeDelayMs() != null) {
            long sd = sample.getSafeDelayMs();
            safeDelaySumMs += sd;
            safeDelayCount++;
            minSafeDelayMs = Math.min(minSafeDelayMs, sd);
            maxSafeDelayMs = Math.max(maxSafeDelayMs, sd);
            safeDelayHistogram = recordToHistogram(safeDelayHistogram, sd);
        }
        if (sample.getFinalizedDelayMs() != null) {
            long fd = sample.getFinalizedDelayMs();
            finalizedDelaySumMs += fd;
            finalizedDelayCount++;
            minFinalizedDelayMs = Math.min(minFinalizedDelayMs, fd);
            maxFinalizedDelayMs = Math.max(maxFinalizedDelayMs, fd);
            finalizedDelayHistogram = recordToHistogram(finalizedDelayHistogram, fd);
        }
    }

    public void addAggregate(SampleAggregate aggregate) {
        totalCount += aggregate.totalCount;
        successCount += aggregate.successCount;
        errorCount += aggregate.errorCount;
        latencySumMs += aggregate.latencySumMs;
        latencyCount += aggregate.latencyCount;
        if (aggregate.latencyCount > 0) {
            minLatencyMs = Math.min(minLatencyMs, aggregate.minLatencyMs);
        }
        maxLatencyMs = Math.max(maxLatencyMs, aggregate.maxLatencyMs);
        httpCount += aggregate.httpCount;
        wsCount += aggregate.wsCount;
        wsErrorCount += aggregate.wsErrorCount;
        propagationDelaySumMs += aggregate.propagationDelaySumMs;
        propagationDelayCount += aggregate.propagationDelayCount;
        maxPropagationDelayMs = Math.max(maxPropagationDelayMs, aggregate.maxPropagationDelayMs);
        staleBlockCount += aggregate.staleBlockCount;
        headDelaySumMs += aggregate.headDelaySumMs;
        headDelayCount += aggregate.headDelayCount;
        if (aggregate.headDelayCount > 0) {
            minHeadDelayMs = Math.min(minHeadDelayMs, aggregate.minHeadDelayMs);
        }
        maxHeadDelayMs = Math.max(maxHeadDelayMs, aggregate.maxHeadDelayMs);
        safeDelaySumMs += aggregate.safeDelaySumMs;
        safeDelayCount += aggregate.safeDelayCount;
        if (aggregate.safeDelayCount > 0) {
            minSafeDelayMs = Math.min(minSafeDelayMs, aggregate.minSafeDelayMs);
        }
        maxSafeDelayMs = Math.max(maxSafeDelayMs, aggregate.maxSafeDelayMs);
        finalizedDelaySumMs += aggregate.finalizedDelaySumMs;
        finalizedDelayCount += aggregate.finalizedDelayCount;
        if (aggregate.finalizedDelayCount > 0) {
            minFinalizedDelayMs = Math.min(minFinalizedDelayMs, aggregate.minFinalizedDelayMs);
        }
        maxFinalizedDelayMs = Math.max(maxFinalizedDelayMs, aggregate.maxFinalizedDelayMs);
        latencyHistogram = mergeHistograms(latencyHistogram, aggregate.latencyHistogram);
        headDelayHistogram = mergeHistograms(headDelayHistogram, aggregate.headDelayHistogram);
        safeDelayHistogram = mergeHistograms(safeDelayHistogram, aggregate.safeDelayHistogram);
        finalizedDelayHistogram = mergeHistograms(finalizedDelayHistogram, aggregate.finalizedDelayHistogram);
    }

    public Instant getBucketStart() {
        return bucketStart;
    }

    public long getTotalCount() {
        return totalCount;
    }

    public long getSuccessCount() {
        return successCount;
    }

    public long getErrorCount() {
        return errorCount;
    }

    public long getLatencySumMs() {
        return latencySumMs;
    }

    public long getLatencyCount() {
        return latencyCount;
    }

    public long getMinLatencyMs() {
        return minLatencyMs;
    }

    public long getMaxLatencyMs() {
        return maxLatencyMs;
    }

    public long getHttpCount() {
        return httpCount;
    }

    public long getWsCount() {
        return wsCount;
    }

    public long getWsErrorCount() {
        return wsErrorCount;
    }

    public long getPropagationDelaySumMs() {
        return propagationDelaySumMs;
    }

    public long getPropagationDelayCount() {
        return propagationDelayCount;
    }

    public long getMaxPropagationDelayMs() {
        return maxPropagationDelayMs;
    }

    public long getStaleBlockCount() {
        return staleBlockCount;
    }

    public long getHeadDelaySumMs() {
        return headDelaySumMs;
    }

    public long getHeadDelayCount() {
        return headDelayCount;
    }

    public long getMinHeadDelayMs() {
        return minHeadDelayMs;
    }

    public long getMaxHeadDelayMs() {
        return maxHeadDelayMs;
    }

    public long getSafeDelaySumMs() {
        return safeDelaySumMs;
    }

    public long getSafeDelayCount() {
        return safeDelayCount;
    }

    public long getMinSafeDelayMs() {
        return minSafeDelayMs;
    }

    public long getMaxSafeDelayMs() {
        return maxSafeDelayMs;
    }

    public long getFinalizedDelaySumMs() {
        return finalizedDelaySumMs;
    }

    public long getFinalizedDelayCount() {
        return finalizedDelayCount;
    }

    public long getMinFinalizedDelayMs() {
        return minFinalizedDelayMs;
    }

    public long getMaxFinalizedDelayMs() {
        return maxFinalizedDelayMs;
    }

    public long[] getLatencyHistogram() {
        return latencyHistogram;
    }

    public long[] getHeadDelayHistogram() {
        return headDelayHistogram;
    }

    public long[] getSafeDelayHistogram() {
        return safeDelayHistogram;
    }

    public long[] getFinalizedDelayHistogram() {
        return finalizedDelayHistogram;
    }

    private static long[] recordToHistogram(long[] histogram, long value) {
        if (histogram == null) {
            histogram = new long[HistogramAccumulator.BUCKET_COUNT];
        }
        HistogramAccumulator.recordInto(histogram, value);
        return histogram;
    }

    private static long[] mergeHistograms(long[] target, long[] source) {
        if (source == null) {
            return target;
        }
        if (target == null) {
            long[] copy = new long[source.length];
            System.arraycopy(source, 0, copy, 0, source.length);
            return copy;
        }
        for (int i = 0; i < target.length && i < source.length; i++) {
            target[i] += source[i];
        }
        return target;
    }
}
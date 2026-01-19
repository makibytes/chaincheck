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
    private long maxHeadDelayMs;
    private long safeDelaySumMs;
    private long safeDelayCount;
    private long maxSafeDelayMs;
    private long finalizedDelaySumMs;
    private long finalizedDelayCount;
    private long maxFinalizedDelayMs;

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
            latencySumMs += sample.getLatencyMs();
            latencyCount++;
            if (sample.getLatencyMs() > maxLatencyMs) {
                maxLatencyMs = sample.getLatencyMs();
            }
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
        if (sample.getHeadDelayMs() != null) {
            headDelaySumMs += sample.getHeadDelayMs();
            headDelayCount++;
            if (sample.getHeadDelayMs() > maxHeadDelayMs) {
                maxHeadDelayMs = sample.getHeadDelayMs();
            }
        }
        if (sample.getSafeDelayMs() != null) {
            safeDelaySumMs += sample.getSafeDelayMs();
            safeDelayCount++;
            if (sample.getSafeDelayMs() > maxSafeDelayMs) {
                maxSafeDelayMs = sample.getSafeDelayMs();
            }
        }
        if (sample.getFinalizedDelayMs() != null) {
            finalizedDelaySumMs += sample.getFinalizedDelayMs();
            finalizedDelayCount++;
            if (sample.getFinalizedDelayMs() > maxFinalizedDelayMs) {
                maxFinalizedDelayMs = sample.getFinalizedDelayMs();
            }
        }
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

    public long getMaxHeadDelayMs() {
        return maxHeadDelayMs;
    }

    public long getSafeDelaySumMs() {
        return safeDelaySumMs;
    }

    public long getSafeDelayCount() {
        return safeDelayCount;
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

    public long getMaxFinalizedDelayMs() {
        return maxFinalizedDelayMs;
    }
}
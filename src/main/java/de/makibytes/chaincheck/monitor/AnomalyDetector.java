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
package de.makibytes.chaincheck.monitor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import de.makibytes.chaincheck.model.AnomalyEvent;
import de.makibytes.chaincheck.model.AnomalyType;
import de.makibytes.chaincheck.model.MetricSample;
import de.makibytes.chaincheck.model.MetricSource;

@Component
public class AnomalyDetector {

    private final AtomicLong idSequence = new AtomicLong(1);

    public AnomalyEvent wrongHead(String nodeKey,
                                  Instant timestamp,
                                  MetricSource source,
                                  Long blockNumber,
                                  String blockHash,
                                  String details) {
        return new AnomalyEvent(
                idSequence.getAndIncrement(),
                nodeKey,
                timestamp,
                source,
                AnomalyType.WRONG_HEAD,
                "Head differs from reference",
                blockNumber,
                blockHash,
                null,
                details);
    }

    public AnomalyEvent referenceDelay(String nodeKey,
                                       Instant timestamp,
                                       MetricSource source,
                                       Long blockNumber,
                                       String blockHash,
                                       String details) {
        return new AnomalyEvent(
                idSequence.getAndIncrement(),
                nodeKey,
                timestamp,
                source,
                AnomalyType.DELAY,
                "Node behind reference",
                blockNumber,
                blockHash,
                null,
                details);
    }

    public AnomalyEvent reorgFinalized(String nodeKey,
                                       Instant timestamp,
                                       MetricSource source,
                                       Long blockNumber,
                                       String blockHash,
                                       String details) {
        return new AnomalyEvent(
                idSequence.getAndIncrement(),
                nodeKey,
                timestamp,
                source,
                AnomalyType.REORG,
                "Finalized block invalidated",
                blockNumber,
                blockHash,
                null,
                details);
    }

    public List<AnomalyEvent> detect(String nodeKey,
                                     MetricSample sample,
                                     long anomalyDelayMs,
                                     long staleBlockThresholdMs,
                                     Long previousBlockNumber,
                                     String previousBlockHash,
                                     Long currentNodeHttpBlockNumber) {
        List<AnomalyEvent> anomalies = new ArrayList<>();
        Instant now = sample.getTimestamp();

        if (!sample.isSuccess()) {
            anomalies.add(createErrorAnomaly(nodeKey, sample, now));
            return anomalies;
        }

        anomalies.addAll(detectLatencyAnomalies(nodeKey, sample, anomalyDelayMs, now));
        anomalies.addAll(detectStaleBlockAnomalies(nodeKey, sample, staleBlockThresholdMs, now));
        anomalies.addAll(detectBlockAnomalies(nodeKey, sample, previousBlockNumber, previousBlockHash, currentNodeHttpBlockNumber, now));

        return anomalies;
    }

    private AnomalyEvent createErrorAnomaly(String nodeKey, MetricSample sample, Instant now) {
        String rawError = sample.getError();
        String shortError = rawError != null && rawError.length() > 50
                ? rawError.substring(0, 50) + "..."
                : rawError;
        String effectiveMessage = (shortError == null || shortError.isBlank()) ? "RPC error" : shortError;
        String details = (rawError == null || rawError.isBlank()) ? effectiveMessage : rawError;
        AnomalyType errorType = classifyErrorType(rawError);
        return new AnomalyEvent(
                idSequence.getAndIncrement(),
                nodeKey,
                now,
                sample.getSource(),
                errorType,
                effectiveMessage,
                sample.getBlockNumber(),
                sample.getBlockHash(),
                sample.getParentHash(),
                details);
    }

    private List<AnomalyEvent> detectStaleBlockAnomalies(String nodeKey, MetricSample sample, long staleBlockThresholdMs, Instant now) {
        List<AnomalyEvent> anomalies = new ArrayList<>();
        // Only HTTP latest-block samples: skip WS, and skip safe/finalized checkpoint samples
        // (those are intentionally old and tracked via safeDelayMs / finalizedDelayMs).
        if (sample.getSource() != MetricSource.HTTP) return anomalies;
        if (sample.getBlockTimestamp() == null) return anomalies;
        if (sample.getSafeDelayMs() != null || sample.getFinalizedDelayMs() != null) return anomalies;
        long ageMs = Duration.between(sample.getBlockTimestamp(), now).toMillis();
        if (ageMs > staleBlockThresholdMs) {
            anomalies.add(new AnomalyEvent(
                    idSequence.getAndIncrement(),
                    nodeKey,
                    now,
                    MetricSource.HTTP,
                    AnomalyType.STALE,
                    "Stale block",
                    sample.getBlockNumber(),
                    sample.getBlockHash(),
                    sample.getParentHash(),
                    "Block age " + ageMs + "ms exceeds threshold " + staleBlockThresholdMs + "ms"));
        }
        return anomalies;
    }

    private List<AnomalyEvent> detectLatencyAnomalies(String nodeKey, MetricSample sample, long anomalyDelayMs, Instant now) {
        List<AnomalyEvent> anomalies = new ArrayList<>();
        if (sample.getLatencyMs() >= anomalyDelayMs && sample.getLatencyMs() >= 0) {
            anomalies.add(new AnomalyEvent(
                    idSequence.getAndIncrement(),
                    nodeKey,
                    now,
                    sample.getSource(),
                    AnomalyType.DELAY,
                    "High latency",
                    sample.getBlockNumber(),
                    sample.getBlockHash(),
                    sample.getParentHash(),
                    "Latency " + sample.getLatencyMs() + "ms"));
        }
        return anomalies;
    }

    private List<AnomalyEvent> detectBlockAnomalies(String nodeKey, MetricSample sample, Long previousBlockNumber, String previousBlockHash, Long currentNodeHttpBlockNumber, Instant now) {
        List<AnomalyEvent> anomalies = new ArrayList<>();
        MetricSource source = sample.getSource();
        Long currentBlockNumber = sample.getBlockNumber();
        String currentBlockHash = sample.getBlockHash();
        String currentParentHash = sample.getParentHash();

        boolean allowReorgDetection = !(source == MetricSource.HTTP && sample.getHeadDelayMs() != null);
        if (currentBlockNumber != null && previousBlockNumber != null) {
            if (currentBlockNumber < previousBlockNumber) {
                if (allowReorgDetection) {
                    long reorgDepth = previousBlockNumber - currentBlockNumber;
                    anomalies.add(createReorgAnomaly(nodeKey, sample, now, "Block height decreased",
                            "Previous height " + previousBlockNumber + ", current " + currentBlockNumber, reorgDepth));
                }
            } else if (source == MetricSource.WS && currentNodeHttpBlockNumber != null && currentNodeHttpBlockNumber - currentBlockNumber >= 5) {
                anomalies.add(createBlockGapAnomaly(nodeKey, sample, now, currentNodeHttpBlockNumber - currentBlockNumber));
            } else if (currentBlockNumber.equals(previousBlockNumber)) {
                detectSameHeightAnomalies(nodeKey, sample, previousBlockHash, currentBlockHash, currentParentHash, allowReorgDetection, anomalies, now);
            } else if (currentBlockNumber.equals(previousBlockNumber + 1)) {
                detectParentMismatchAnomalies(nodeKey, sample, previousBlockHash, currentParentHash, allowReorgDetection, anomalies, now);
            }
        }
        return anomalies;
    }

    private void detectSameHeightAnomalies(String nodeKey, MetricSample sample, String previousBlockHash, String currentBlockHash,
            String currentParentHash, boolean allowReorgDetection, List<AnomalyEvent> anomalies, Instant now) {
        if (allowReorgDetection && previousBlockHash != null && currentBlockHash != null && !previousBlockHash.equals(currentBlockHash)) {
            anomalies.add(createReorgAnomaly(nodeKey, sample, now, "Block hash changed at same height",
                    "Previous hash " + previousBlockHash + ", current " + currentBlockHash, 1L));
        }
    }

    private void detectParentMismatchAnomalies(String nodeKey, MetricSample sample, String previousBlockHash, String currentParentHash,
            boolean allowReorgDetection, List<AnomalyEvent> anomalies, Instant now) {
        if (allowReorgDetection && previousBlockHash != null && currentParentHash != null && !previousBlockHash.equals(currentParentHash)) {
            anomalies.add(createReorgAnomaly(nodeKey, sample, now, "Parent hash mismatch",
                    "Expected parent " + previousBlockHash + ", got " + currentParentHash, 1L));
        }
    }

    private AnomalyEvent createReorgAnomaly(String nodeKey, MetricSample sample, Instant now, String message, String details, Long depth) {
        return new AnomalyEvent(
                idSequence.getAndIncrement(),
                nodeKey,
                now,
                sample.getSource(),
                AnomalyType.REORG,
                message,
                sample.getBlockNumber(),
                sample.getBlockHash(),
                sample.getParentHash(),
                details,
                depth);
    }

    private AnomalyEvent createBlockGapAnomaly(String nodeKey, MetricSample sample, Instant now, long gap) {
        return new AnomalyEvent(
                idSequence.getAndIncrement(),
                nodeKey,
                now,
                sample.getSource(),
                AnomalyType.BLOCK_GAP,
                "Block gap detected",
                sample.getBlockNumber(),
                sample.getBlockHash(),
                sample.getParentHash(),
                "Gap of " + gap + " blocks",
                gap);
    }

    private AnomalyType classifyErrorType(String error) {
        if (error == null) {
            return AnomalyType.ERROR;
        }
        String lower = error.toLowerCase();
        if (lower.contains("rate limit") || lower.contains("rate-limit") || lower.contains("http 429")) {
            return AnomalyType.RATE_LIMIT;
        }
        if (lower.contains("timeout") || lower.contains("timed out") || lower.contains("http 408")) {
            return AnomalyType.TIMEOUT;
        }
        return AnomalyType.ERROR;
    }
}

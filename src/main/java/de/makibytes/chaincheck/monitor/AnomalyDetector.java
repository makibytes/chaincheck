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
                                     Long previousBlockNumber,
                                     String previousBlockHash) {
        List<AnomalyEvent> anomalies = new ArrayList<>();
        Instant now = sample.getTimestamp();
        MetricSource source = sample.getSource();

        if (!sample.isSuccess()) {
            String shortError = sample.getError() != null && sample.getError().length() > 50
                    ? sample.getError().substring(0, 50) + "..."
                    : sample.getError();
            AnomalyType errorType = classifyErrorType(sample.getError());
            anomalies.add(new AnomalyEvent(
                    idSequence.getAndIncrement(),
                    nodeKey,
                    now,
                    source,
                errorType,
                    shortError == null ? "RPC error" : shortError,
                    sample.getBlockNumber(),
                    sample.getBlockHash(),
                    sample.getParentHash(),
                    sample.getError()));
            return anomalies;
        }

        if (sample.getLatencyMs() >= anomalyDelayMs && sample.getLatencyMs() >= 0) {
            anomalies.add(new AnomalyEvent(
                    idSequence.getAndIncrement(),
                    nodeKey,
                    now,
                    source,
                    AnomalyType.DELAY,
                    "High latency",
                    sample.getBlockNumber(),
                    sample.getBlockHash(),
                    sample.getParentHash(),
                    "Latency " + sample.getLatencyMs() + "ms"));
        }

        Long currentBlockNumber = sample.getBlockNumber();
        String currentBlockHash = sample.getBlockHash();
        String currentParentHash = sample.getParentHash();

        boolean allowReorgDetection = !(source == MetricSource.HTTP && sample.getHeadDelayMs() != null);
        if (currentBlockNumber != null && previousBlockNumber != null) {
            if (currentBlockNumber < previousBlockNumber) {
                if (allowReorgDetection) {
                    anomalies.add(new AnomalyEvent(
                            idSequence.getAndIncrement(),
                            nodeKey,
                            now,
                            source,
                            AnomalyType.REORG,
                            "Block height decreased",
                            currentBlockNumber,
                            currentBlockHash,
                            currentParentHash,
                            "Previous height " + previousBlockNumber + ", current " + currentBlockNumber));
                }
            } else if (source == MetricSource.WS && currentBlockNumber > previousBlockNumber + 1) {
                anomalies.add(new AnomalyEvent(
                        idSequence.getAndIncrement(),
                    nodeKey,
                        now,
                        source,
                        AnomalyType.BLOCK_GAP,
                        "Block gap detected",
                        currentBlockNumber,
                        currentBlockHash,
                        currentParentHash,
                        "Gap of " + (currentBlockNumber - previousBlockNumber) + " blocks"));
            } else if (currentBlockNumber.equals(previousBlockNumber)) {
                if (allowReorgDetection
                        && previousBlockHash != null
                        && currentBlockHash != null
                        && !previousBlockHash.equals(currentBlockHash)) {
                    anomalies.add(new AnomalyEvent(
                            idSequence.getAndIncrement(),
                            nodeKey,
                            now,
                            source,
                            AnomalyType.REORG,
                            "Block hash changed at same height",
                            currentBlockNumber,
                            currentBlockHash,
                            currentParentHash,
                            "Previous hash " + previousBlockHash + ", current " + currentBlockHash));
                }
            } else if (currentBlockNumber.equals(previousBlockNumber + 1)) {
                if (allowReorgDetection
                        && previousBlockHash != null
                        && currentParentHash != null
                        && !previousBlockHash.equals(currentParentHash)) {
                    anomalies.add(new AnomalyEvent(
                            idSequence.getAndIncrement(),
                            nodeKey,
                            now,
                            source,
                            AnomalyType.REORG,
                            "Parent hash mismatch",
                            currentBlockNumber,
                            currentBlockHash,
                            currentParentHash,
                            "Expected parent " + previousBlockHash + ", got " + currentParentHash));
                }
            }
        }

        return anomalies;
    }

    private AnomalyType classifyErrorType(String error) {
        if (error == null) {
            return AnomalyType.ERROR;
        }
        String lower = error.toLowerCase();
        if (lower.contains("rate limit") || lower.contains("rate-limit") || lower.contains("http 429")) {
            return AnomalyType.RATE_LIMIT;
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return AnomalyType.TIMEOUT;
        }
        return AnomalyType.ERROR;
    }
}

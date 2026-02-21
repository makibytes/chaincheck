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
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import de.makibytes.chaincheck.model.AnomalyEvent;
import de.makibytes.chaincheck.model.AnomalyType;
import de.makibytes.chaincheck.model.MetricSample;
import de.makibytes.chaincheck.model.MetricSource;

@Component
@Primary
@Profile("mock")
public class MockMetricsStoreSeeded extends InMemoryMetricsStore {

    public static final String NODE_KEY = "mock-node";
    public static final String NODE_KEY_SECONDARY = "mock-node-two";
    private static final int WS_OUTAGE_COUNT = 5;
    private static final int WS_OUTAGE_MINUTES = 180;
    private static final int HTTP_OUTAGE_MINUTES = 25;
    private static final int HTTP_OUTAGE_LOOKBACK_MINUTES = 120;
    private static final int SECONDARY_HTTP_OUTAGE_MINUTES = 18;
    private static final int SECONDARY_HTTP_OUTAGE_LOOKBACK_MINUTES = 180;
    private static final int WS_FIXED_OUTAGE_LOOKBACK_HOURS = 13;
    private static final int WS_FIXED_OUTAGE_MINUTES = 75;
    private static final Duration DATA_RANGE = Duration.ofDays(21);

    public MockMetricsStoreSeeded(de.makibytes.chaincheck.config.ChainCheckProperties properties) {
        super(properties);
        seedLargeDataset();
        super.aggregateOldData();
    }

    @Override
    public void aggregateOldData() {
        super.aggregateOldData();
    }

    private void seedLargeDataset() {
        Instant now = Instant.now();
        Instant start = now.minus(DATA_RANGE);
        int totalMinutes = Math.toIntExact(Duration.between(start, now).toMinutes());
        Random random = new Random(20260122L);

        int httpOutageStart = totalMinutes - HTTP_OUTAGE_LOOKBACK_MINUTES
                + random.nextInt(Math.max(1, HTTP_OUTAGE_LOOKBACK_MINUTES - HTTP_OUTAGE_MINUTES + 1));
        int httpOutageEnd = Math.min(totalMinutes, httpOutageStart + HTTP_OUTAGE_MINUTES);

        List<int[]> wsOutages = buildWsOutageWindows(random, totalMinutes, true);
        List<int[]> wsOutagesSecondary = buildWsOutageWindows(new Random(20260123L), totalMinutes, false);
        List<int[]> httpErrorWindows = buildHttpErrorWindows(totalMinutes, new int[] { 6, 18, 30 }, new int[] { 6, 5, 7 });
        List<int[]> httpErrorWindowsSecondary = buildHttpErrorWindows(totalMinutes, new int[] { 8, 20, 34 }, new int[] { 4, 6, 5 });
        String[] httpErrorMessages = {
            "HTTP 429 rate limit",
            "timeout",
            "HTTP 502 Bad Gateway",
            "HTTP 503 Service Unavailable"
        };

        int httpOutageStartSecondary = totalMinutes - SECONDARY_HTTP_OUTAGE_LOOKBACK_MINUTES
                + random.nextInt(Math.max(1, SECONDARY_HTTP_OUTAGE_LOOKBACK_MINUTES - SECONDARY_HTTP_OUTAGE_MINUTES + 1));
        int httpOutageEndSecondary = Math.min(totalMinutes, httpOutageStartSecondary + SECONDARY_HTTP_OUTAGE_MINUTES);

        long blockBase = 12_000_000L;
        for (int minute = 0; minute < totalMinutes; minute++) {
            Instant timestamp = start.plusSeconds(minute * 60L);

            if (!isInRange(minute, httpOutageStart, httpOutageEnd)) {
                boolean staleBlock = minute % 720 == 0;
                addHttpSample(NODE_KEY, timestamp, blockBase + minute, true, staleBlock, 30, 2000L, 2500L);
                addHttpSample(NODE_KEY, timestamp.plusSeconds(10), blockBase + minute, false, staleBlock, 30, 2000L, 2500L);
                if (isInAnyRange(minute, httpErrorWindows)) {
                    String errorMessage = httpErrorMessages[minute % httpErrorMessages.length];
                    addHttpErrorSample(NODE_KEY, timestamp.plusSeconds(25), blockBase + minute, errorMessage);
                }
            }

            if (!isInRange(minute, httpOutageStartSecondary, httpOutageEndSecondary)) {
                boolean staleBlockSecondary = minute % 540 == 0;
                long secondaryBlock = blockBase + 80_000 + minute;
                addHttpSample(NODE_KEY_SECONDARY, timestamp.plusSeconds(5), secondaryBlock, true, staleBlockSecondary, 45, 2600L, 3200L);
                addHttpSample(NODE_KEY_SECONDARY, timestamp.plusSeconds(15), secondaryBlock, false, staleBlockSecondary, 45, 2600L, 3200L);
                if (isInAnyRange(minute, httpErrorWindowsSecondary)) {
                    String errorMessage = httpErrorMessages[(minute + 1) % httpErrorMessages.length];
                    addHttpErrorSample(NODE_KEY_SECONDARY, timestamp.plusSeconds(35), secondaryBlock, errorMessage);
                }
            }

            if (!isInAnyRange(minute, wsOutages)) {
                addWsSample(NODE_KEY, timestamp.plusSeconds(20), 1000L);
            }
            if (!isInAnyRange(minute, wsOutagesSecondary)) {
                addWsSample(NODE_KEY_SECONDARY, timestamp.plusSeconds(30), 1600L);
            }
        }

        seedAnomalies(now, NODE_KEY, 1L);
        seedAnomalies(now.minus(Duration.ofHours(6)), NODE_KEY_SECONDARY, 1000L);
    }

    private List<int[]> buildWsOutageWindows(Random random, int totalMinutes, boolean includeFixedOutage) {
        List<int[]> outages = new ArrayList<>();
        int lastThreeDaysMinutes = 3 * 24 * 60;

        if (includeFixedOutage) {
            int fixedStart = totalMinutes - (WS_FIXED_OUTAGE_LOOKBACK_HOURS * 60);
            int fixedEnd = Math.min(totalMinutes, fixedStart + WS_FIXED_OUTAGE_MINUTES);
            if (fixedStart >= 0 && fixedEnd > fixedStart) {
                outages.add(new int[] { fixedStart, fixedEnd });
            }
        }

        if (totalMinutes > lastThreeDaysMinutes + WS_OUTAGE_MINUTES) {
            int recentStart = totalMinutes - (2 * 24 * 60);
            recentStart = Math.max(0, (recentStart / 60) * 60);
            int recentEnd = Math.min(totalMinutes, recentStart + WS_OUTAGE_MINUTES);
            if (!overlaps(outages, recentStart, recentEnd)) {
                outages.add(new int[] { recentStart, recentEnd });
            }
        }

        while (outages.size() < WS_OUTAGE_COUNT) {
            int startMinute = random.nextInt(Math.max(1, totalMinutes - WS_OUTAGE_MINUTES));
            startMinute = (startMinute / 60) * 60;
            int endMinute = Math.min(totalMinutes, startMinute + WS_OUTAGE_MINUTES);
            if (overlaps(outages, startMinute, endMinute)) {
                continue;
            }
            outages.add(new int[] { startMinute, endMinute });
        }
        return outages;
    }

    private List<int[]> buildHttpErrorWindows(int totalMinutes, int[] lookbackHours, int[] lengthsMinutes) {
        List<int[]> windows = new ArrayList<>();
        int count = Math.min(lookbackHours.length, lengthsMinutes.length);
        for (int i = 0; i < count; i++) {
            int startMinute = Math.max(0, totalMinutes - (lookbackHours[i] * 60));
            int endMinute = Math.min(totalMinutes, startMinute + lengthsMinutes[i]);
            if (endMinute > startMinute) {
                windows.add(new int[] { startMinute, endMinute });
            }
        }
        return windows;
    }

    private boolean overlaps(List<int[]> outages, int startMinute, int endMinute) {
        for (int[] range : outages) {
            if (startMinute < range[1] && endMinute > range[0]) {
                return true;
            }
        }
        return false;
    }

    private boolean isInRange(int minute, int startMinute, int endMinute) {
        return minute >= startMinute && minute < endMinute;
    }

    private boolean isInAnyRange(int minute, List<int[]> ranges) {
        for (int[] range : ranges) {
            if (isInRange(minute, range[0], range[1])) {
                return true;
            }
        }
        return false;
    }

    private void addHttpSample(String nodeKey,
                               Instant timestamp,
                               long blockNumber,
                               boolean safeSample,
                               boolean staleBlock,
                               long latencyOffsetMs,
                               long safeDelayBaseMs,
                               long finalizedDelayBaseMs) {
        boolean success = true;
        long latencyMs = latencyOffsetMs + (blockNumber % 120);
        Instant blockTimestamp = staleBlock ? timestamp.minusSeconds(90) : timestamp.minusSeconds(12);
        String blockHash = "0x" + Long.toHexString(blockNumber);
        String parentHash = "0x" + Long.toHexString(blockNumber - 1);
        Integer transactionCount = (int) (blockNumber % 350);
        Long gasPriceWei = 1_000_000_000L + (blockNumber % 500_000);
        Long safeDelayMs = safeSample ? safeDelayBaseMs + (blockNumber % 4000) : null;
        Long finalizedDelayMs = safeSample ? null : finalizedDelayBaseMs + (blockNumber % 4500);

        MetricSample sample = MetricSample.builder(timestamp, MetricSource.HTTP)
                .success(success)
                .latencyMs(latencyMs)
                .blockNumber(blockNumber)
                .blockTimestamp(blockTimestamp)
                .blockHash(blockHash)
                .parentHash(parentHash)
                .transactionCount(transactionCount)
                .gasPriceWei(gasPriceWei)
                .safeDelayMs(safeDelayMs)
                .finalizedDelayMs(finalizedDelayMs)
                .build();
        addSample(nodeKey, sample);
    }

    private void addHttpErrorSample(String nodeKey, Instant timestamp, long blockNumber, String errorMessage) {
        MetricSample sample = MetricSample.builder(timestamp, MetricSource.HTTP)
                .success(false)
                .latencyMs(-1)
                .blockNumber(blockNumber)
                .error(errorMessage)
                .build();
        addSample(nodeKey, sample);
    }

    private void addWsSample(String nodeKey, Instant timestamp, long baseDelayMs) {
        long latencyMs = 0;
        Long headDelayMs = baseDelayMs + (timestamp.getEpochSecond() % 4000);
        MetricSample sample = MetricSample.builder(timestamp, MetricSource.WS)
                .success(true)
                .latencyMs(latencyMs)
                .headDelayMs(headDelayMs)
                .build();
        addSample(nodeKey, sample);
    }

    private void seedAnomalies(Instant now, String nodeKey, long startingId) {
        long id = startingId;
        AnomalyType[] types = AnomalyType.values();
        for (int i = 0; i < 3; i++) {
            for (AnomalyType type : types) {
                Instant timestamp = now
                        .minus(Duration.ofDays(2 + (i * 4L)))
                        .minus(Duration.ofMinutes(type.ordinal() * 10L));
                MetricSource source = type == AnomalyType.ERROR || type == AnomalyType.DELAY
                        ? MetricSource.HTTP
                        : MetricSource.WS;
                addAnomaly(nodeKey, new AnomalyEvent(
                        id++,
                        nodeKey,
                        timestamp,
                        source,
                        type,
                        "Synthetic " + type.name() + " anomaly",
                        source == MetricSource.HTTP ? 12_000_000L + id : null,
                        source == MetricSource.HTTP ? "0x" + Long.toHexString(12_000_000L + id) : null,
                        source == MetricSource.HTTP ? "0x" + Long.toHexString(11_999_999L + id) : null,
                        "Synthetic details"));
            }
        }
    }
}

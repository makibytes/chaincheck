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
@Profile("test")
public class MockMetricsStore extends InMemoryMetricsStore {

    public static final String NODE_KEY = "mock-node";
    private static final int WS_OUTAGE_COUNT = 5;
    private static final int WS_OUTAGE_MINUTES = 180;
    private static final int HTTP_OUTAGE_MINUTES = 25;
    private static final int HTTP_OUTAGE_LOOKBACK_MINUTES = 120;
    private static final Duration DATA_RANGE = Duration.ofDays(21);

    public MockMetricsStore() {
        seedLargeDataset();
    }

    @Override
    public void aggregateOldData() {
        // No-op for integration tests: keep raw samples/anomalies for charting.
    }

    private void seedLargeDataset() {
        Instant now = Instant.now();
        Instant start = now.minus(DATA_RANGE);
        int totalMinutes = Math.toIntExact(Duration.between(start, now).toMinutes());
        Random random = new Random(20260122L);

        int httpOutageStart = totalMinutes - HTTP_OUTAGE_LOOKBACK_MINUTES
            + random.nextInt(Math.max(1, HTTP_OUTAGE_LOOKBACK_MINUTES - HTTP_OUTAGE_MINUTES + 1));
        int httpOutageEnd = Math.min(totalMinutes, httpOutageStart + HTTP_OUTAGE_MINUTES);

        List<int[]> wsOutages = buildWsOutageWindows(random, totalMinutes);

        long blockBase = 12_000_000L;
        for (int minute = 0; minute < totalMinutes; minute++) {
            Instant timestamp = start.plusSeconds(minute * 60L);

            if (!isInRange(minute, httpOutageStart, httpOutageEnd)) {
                boolean staleBlock = minute % 720 == 0;
                addHttpSample(timestamp, blockBase + minute, true, staleBlock);
                addHttpSample(timestamp.plusSeconds(10), blockBase + minute, false, staleBlock);
            }

            if (!isInAnyRange(minute, wsOutages)) {
                addWsSample(timestamp.plusSeconds(20));
            }
        }

        seedAnomalies(now);
    }

    private List<int[]> buildWsOutageWindows(Random random, int totalMinutes) {
        List<int[]> outages = new ArrayList<>();
        int lastThreeDaysMinutes = 3 * 24 * 60;

        if (totalMinutes > lastThreeDaysMinutes + WS_OUTAGE_MINUTES) {
            int recentStart = totalMinutes - (2 * 24 * 60);
            recentStart = Math.max(0, (recentStart / 60) * 60);
            int recentEnd = Math.min(totalMinutes, recentStart + WS_OUTAGE_MINUTES);
            outages.add(new int[] { recentStart, recentEnd });
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

    private void addHttpSample(Instant timestamp, long blockNumber, boolean safeSample, boolean staleBlock) {
        boolean success = true;
        long latencyMs = 30 + (blockNumber % 120);
        Instant blockTimestamp = staleBlock ? timestamp.minusSeconds(90) : timestamp.minusSeconds(12);
        String blockHash = "0x" + Long.toHexString(blockNumber);
        String parentHash = "0x" + Long.toHexString(blockNumber - 1);
        Integer transactionCount = (int) (blockNumber % 350);
        Long gasPriceWei = 1_000_000_000L + (blockNumber % 500_000);
        Long safeDelayMs = safeSample ? 2000L + (blockNumber % 4000) : null;
        Long finalizedDelayMs = safeSample ? null : 2500L + (blockNumber % 4500);

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
        addSample(NODE_KEY, sample);
    }

    private void addWsSample(Instant timestamp) {
        long latencyMs = 0;
        Long headDelayMs = 1000L + (timestamp.getEpochSecond() % 4000);
        MetricSample sample = MetricSample.builder(timestamp, MetricSource.WS)
                .success(true)
                .latencyMs(latencyMs)
                .headDelayMs(headDelayMs)
                .build();
        addSample(NODE_KEY, sample);
    }

    private void seedAnomalies(Instant now) {
        long id = 1L;
        AnomalyType[] types = AnomalyType.values();
        for (int i = 0; i < 3; i++) {
            for (AnomalyType type : types) {
                Instant timestamp = now
                        .minus(Duration.ofDays(2 + (i * 4L)))
                        .minus(Duration.ofMinutes(type.ordinal() * 10L));
                MetricSource source = type == AnomalyType.ERROR || type == AnomalyType.DELAY
                        ? MetricSource.HTTP
                        : MetricSource.WS;
                addAnomaly(NODE_KEY, new AnomalyEvent(
                        id++,
                        NODE_KEY,
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

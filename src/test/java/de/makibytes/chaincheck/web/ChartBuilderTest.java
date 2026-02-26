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
package de.makibytes.chaincheck.web;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import de.makibytes.chaincheck.model.MetricSample;
import de.makibytes.chaincheck.model.MetricSource;
import de.makibytes.chaincheck.model.TimeRange;
import de.makibytes.chaincheck.store.SampleAggregate;

@DisplayName("ChartBuilder Tests")
class ChartBuilderTest {

    private static MetricSample httpSample(Instant timestamp, long latencyMs, Long blockNumber) {
        return MetricSample.builder(timestamp, MetricSource.HTTP)
                .success(true)
                .latencyMs(latencyMs)
                .blockNumber(blockNumber)
                .blockTimestamp(timestamp.minus(Duration.ofSeconds(1)))
                .blockHash("0xhash")
                .parentHash("0xparent")
                .build();
    }

    private static MetricSample httpError(Instant timestamp) {
        return MetricSample.builder(timestamp, MetricSource.HTTP)
                .success(false)
                .latencyMs(-1)
                .error("error")
                .build();
    }

    private static MetricSample wsSample(Instant timestamp, Long headDelayMs, Long safeDelayMs, Long finalizedDelayMs) {
        return MetricSample.builder(timestamp, MetricSource.WS)
                .success(true)
                .latencyMs(-1)
                .blockNumber(100L)
                .blockTimestamp(timestamp)
                .blockHash("0xhash")
                .parentHash("0xparent")
                .headDelayMs(headDelayMs)
                .safeDelayMs(safeDelayMs)
                .finalizedDelayMs(finalizedDelayMs)
                .build();
    }

    @Nested
    @DisplayName("percentile")
    class PercentileTest {

        @Test
        @DisplayName("empty list returns 0")
        void emptyList() {
            assertEquals(0, ChartBuilder.percentile(List.of(), 0.95));
        }

        @Test
        @DisplayName("null list returns 0")
        void nullList() {
            assertEquals(0, ChartBuilder.percentile(null, 0.95));
        }

        @Test
        @DisplayName("single value returns that value")
        void singleValue() {
            assertEquals(42.0, ChartBuilder.percentile(List.of(42L), 0.5));
            assertEquals(42.0, ChartBuilder.percentile(List.of(42L), 0.95));
        }

        @Test
        @DisplayName("p50 of two values interpolates to midpoint")
        void twoValuesP50() {
            assertEquals(50.0, ChartBuilder.percentile(List.of(0L, 100L), 0.50));
        }

        @Test
        @DisplayName("p95 of sorted list interpolates correctly")
        void p95Interpolation() {
            List<Long> values = List.of(10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L, 100L);
            double result = ChartBuilder.percentile(values, 0.95);
            // position = 0.95 * 9 = 8.55, lower=90, upper=100, fraction=0.55
            assertEquals(95.5, result, 0.001);
        }

        @Test
        @DisplayName("p0 returns first value")
        void p0() {
            assertEquals(10.0, ChartBuilder.percentile(List.of(10L, 50L, 100L), 0.0));
        }

        @Test
        @DisplayName("p100 returns last value")
        void p100() {
            assertEquals(100.0, ChartBuilder.percentile(List.of(10L, 50L, 100L), 1.0));
        }
    }

    @Nested
    @DisplayName("buildLatencyChart")
    class BuildLatencyChartTest {

        @Test
        @DisplayName("empty data returns empty chart")
        void emptyData() {
            ChartBuilder.ChartData result = ChartBuilder.buildLatencyChart(
                    List.of(), List.of(), TimeRange.HOURS_2, Instant.now());
            assertTrue(result.timestamps().isEmpty());
            assertTrue(result.labels().isEmpty());
            assertTrue(result.latencies().isEmpty());
        }

        @Test
        @DisplayName("raw samples produce correct bucketing")
        void rawSamples() {
            Instant end = Instant.now();
            Instant start = end.minus(Duration.ofMinutes(10));
            List<MetricSample> samples = new ArrayList<>();
            // Put 3 samples in the middle of the range
            Instant mid = start.plus(Duration.ofMinutes(5));
            samples.add(httpSample(mid, 100, 1000L));
            samples.add(httpSample(mid.plusSeconds(1), 200, 1001L));
            samples.add(httpSample(mid.plusSeconds(2), 300, 1002L));

            ChartBuilder.ChartData result = ChartBuilder.buildLatencyChart(
                    samples, List.of(), TimeRange.HOURS_2, end);

            assertFalse(result.timestamps().isEmpty());
            assertFalse(result.labels().isEmpty());
            assertEquals(result.timestamps().size(), result.latencies().size());
            // At least one bucket should have a non-null latency
            assertTrue(result.latencies().stream().anyMatch(v -> v != null));
        }

        @Test
        @DisplayName("past end parameter produces chart timestamps ending before now")
        void pastEndUsesEndNotNow() {
            Instant end = Instant.now().minus(Duration.ofHours(6));
            Instant start = end.minus(Duration.ofHours(2));
            Instant mid = start.plus(Duration.ofMinutes(60));

            List<MetricSample> samples = List.of(
                    httpSample(mid, 100, 1000L),
                    httpSample(mid.plusSeconds(30), 150, 1001L));

            ChartBuilder.ChartData result = ChartBuilder.buildLatencyChart(
                    samples, List.of(), TimeRange.HOURS_2, end);

            assertFalse(result.timestamps().isEmpty());
            // All timestamps should be before end
            long endMs = end.toEpochMilli();
            for (Long ts : result.timestamps()) {
                assertTrue(ts <= endMs, "Chart timestamp " + ts + " should be <= end " + endMs);
            }
            // And they should be well in the past, not near Instant.now()
            long nowMs = Instant.now().toEpochMilli();
            long lastTs = result.timestamps().get(result.timestamps().size() - 1);
            assertTrue(lastTs < nowMs - Duration.ofHours(3).toMillis(),
                    "Last chart timestamp should be well before current time");
        }

        @Test
        @DisplayName("aggregate samples produce correct bucketing")
        void aggregateSamples() {
            Instant end = Instant.now();
            Instant aggStart = end.minus(Duration.ofMinutes(30));
            SampleAggregate agg = new SampleAggregate(aggStart);
            MetricSample sample = httpSample(aggStart.plusSeconds(10), 200, 100L);
            agg.addSample(sample, 30000);

            ChartBuilder.ChartData result = ChartBuilder.buildLatencyChart(
                    List.of(), List.of(agg), TimeRange.HOURS_2, end);

            assertFalse(result.timestamps().isEmpty());
            assertTrue(result.latencies().stream().anyMatch(v -> v != null));
        }

        @Test
        @DisplayName("mixed raw and aggregate data")
        void mixedData() {
            Instant end = Instant.now();
            Instant rawTime = end.minus(Duration.ofMinutes(5));
            Instant aggTime = end.minus(Duration.ofMinutes(30));

            List<MetricSample> raw = List.of(httpSample(rawTime, 100, 1000L));
            SampleAggregate agg = new SampleAggregate(aggTime);
            agg.addSample(httpSample(aggTime.plusSeconds(5), 300, 999L), 30000);

            ChartBuilder.ChartData result = ChartBuilder.buildLatencyChart(
                    raw, List.of(agg), TimeRange.HOURS_2, end);

            assertFalse(result.timestamps().isEmpty());
            long nonNullCount = result.latencies().stream().filter(v -> v != null).count();
            assertTrue(nonNullCount >= 2, "Should have at least 2 non-null latency buckets");
        }

        @Test
        @DisplayName("error rates are computed per bucket")
        void errorRates() {
            Instant end = Instant.now();
            Instant t = end.minus(Duration.ofMinutes(5));
            List<MetricSample> samples = List.of(
                    httpSample(t, 100, 1000L),
                    httpError(t.plusSeconds(1)));

            ChartBuilder.ChartData result = ChartBuilder.buildLatencyChart(
                    samples, List.of(), TimeRange.HOURS_2, end);

            // At least one bucket should have a non-null error rate
            assertTrue(result.errorRates().stream().anyMatch(v -> v != null && v > 0));
            assertTrue(result.httpErrorBuckets().stream().anyMatch(v -> v));
        }
    }

    @Nested
    @DisplayName("buildDelayChart")
    class BuildDelayChartTest {

        @Test
        @DisplayName("empty data returns empty chart")
        void emptyData() {
            ChartBuilder.DelayChartData result = ChartBuilder.buildDelayChart(
                    List.of(), List.of(), TimeRange.HOURS_2, Instant.now());
            assertTrue(result.timestamps().isEmpty());
            assertTrue(result.headDelays().isEmpty());
        }

        @Test
        @DisplayName("WS samples with delays produce correct bucketing")
        void wsSamplesWithDelays() {
            Instant end = Instant.now();
            Instant t = end.minus(Duration.ofMinutes(5));

            List<MetricSample> samples = List.of(
                    wsSample(t, 500L, 2000L, 5000L),
                    wsSample(t.plusSeconds(1), 600L, 2200L, 5500L));

            ChartBuilder.DelayChartData result = ChartBuilder.buildDelayChart(
                    samples, List.of(), TimeRange.HOURS_2, end);

            assertFalse(result.timestamps().isEmpty());
            assertTrue(result.headDelays().stream().anyMatch(v -> v != null));
            assertTrue(result.safeDelays().stream().anyMatch(v -> v != null));
            assertTrue(result.finalizedDelays().stream().anyMatch(v -> v != null));
        }

        @Test
        @DisplayName("past end parameter produces chart timestamps ending before now")
        void pastEndUsesEndNotNow() {
            Instant end = Instant.now().minus(Duration.ofHours(6));
            Instant t = end.minus(Duration.ofMinutes(30));

            List<MetricSample> samples = List.of(wsSample(t, 500L, null, null));

            ChartBuilder.DelayChartData result = ChartBuilder.buildDelayChart(
                    samples, List.of(), TimeRange.HOURS_2, end);

            assertFalse(result.timestamps().isEmpty());
            long endMs = end.toEpochMilli();
            for (Long ts : result.timestamps()) {
                assertTrue(ts <= endMs, "Chart timestamp should be <= end");
            }
        }
    }

    @Nested
    @DisplayName("buildDelayChartAligned")
    class BuildDelayChartAlignedTest {

        @Test
        @DisplayName("empty timestamps returns empty chart")
        void emptyTimestamps() {
            ChartBuilder.DelayChartData result = ChartBuilder.buildDelayChartAligned(
                    List.of(), List.of(), List.of());
            assertTrue(result.timestamps().isEmpty());
        }

        @Test
        @DisplayName("null timestamps returns empty chart")
        void nullTimestamps() {
            ChartBuilder.DelayChartData result = ChartBuilder.buildDelayChartAligned(
                    List.of(), List.of(), null);
            assertTrue(result.timestamps().isEmpty());
        }

        @Test
        @DisplayName("aligns samples to base timestamps")
        void alignsToBaseTimestamps() {
            Instant base = Instant.now().minus(Duration.ofMinutes(10));
            List<Long> baseTimestamps = List.of(
                    base.toEpochMilli(),
                    base.plusSeconds(60).toEpochMilli(),
                    base.plusSeconds(120).toEpochMilli());

            // Put a sample in the second bucket
            Instant sampleTime = base.plusSeconds(65);
            List<MetricSample> samples = List.of(wsSample(sampleTime, 300L, 1000L, 4000L));

            ChartBuilder.DelayChartData result = ChartBuilder.buildDelayChartAligned(
                    samples, List.of(), baseTimestamps);

            assertEquals(3, result.timestamps().size());
            assertEquals(baseTimestamps, result.timestamps());
            // First bucket should be null (no data)
            assertNull(result.headDelays().get(0));
            // Second bucket should have the sample
            assertNotNull(result.headDelays().get(1));
            assertEquals(300L, result.headDelays().get(1));
            // Third bucket should be null
            assertNull(result.headDelays().get(2));
        }
    }

    @Nested
    @DisplayName("ChartData.empty and DelayChartData.empty")
    class EmptyFactoryTest {

        @Test
        @DisplayName("ChartData.empty returns all empty lists")
        void chartDataEmpty() {
            ChartBuilder.ChartData empty = ChartBuilder.ChartData.empty();
            assertTrue(empty.timestamps().isEmpty());
            assertTrue(empty.labels().isEmpty());
            assertTrue(empty.latencies().isEmpty());
            assertTrue(empty.latencyMins().isEmpty());
            assertTrue(empty.latencyMaxs().isEmpty());
            assertTrue(empty.errorRates().isEmpty());
            assertTrue(empty.wsErrorRates().isEmpty());
            assertTrue(empty.httpErrorBuckets().isEmpty());
            assertTrue(empty.wsErrorBuckets().isEmpty());
        }

        @Test
        @DisplayName("DelayChartData.empty returns all empty lists")
        void delayChartDataEmpty() {
            ChartBuilder.DelayChartData empty = ChartBuilder.DelayChartData.empty();
            assertTrue(empty.timestamps().isEmpty());
            assertTrue(empty.headDelays().isEmpty());
            assertTrue(empty.headDelayMins().isEmpty());
            assertTrue(empty.headDelayMaxs().isEmpty());
            assertTrue(empty.safeDelays().isEmpty());
            assertTrue(empty.safeDelayMins().isEmpty());
            assertTrue(empty.safeDelayMaxs().isEmpty());
            assertTrue(empty.finalizedDelays().isEmpty());
            assertTrue(empty.finalizedDelayMins().isEmpty());
            assertTrue(empty.finalizedDelayMaxs().isEmpty());
        }
    }
}

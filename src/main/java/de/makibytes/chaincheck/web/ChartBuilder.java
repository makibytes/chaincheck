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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import de.makibytes.chaincheck.model.MetricSample;
import de.makibytes.chaincheck.model.MetricSource;
import de.makibytes.chaincheck.model.TimeRange;
import de.makibytes.chaincheck.store.SampleAggregate;

class ChartBuilder {

    private static final int TARGET_POINTS = 120;

    record ChartData(List<Long> timestamps,
                     List<String> labels,
                     List<Long> latencies,
                     List<Long> latencyMins,
                     List<Long> latencyMaxs,
                     List<Double> errorRates,
                     List<Double> wsErrorRates,
                     List<Boolean> httpErrorBuckets,
                     List<Boolean> wsErrorBuckets) {

        static ChartData empty() {
            return new ChartData(List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }

    record DelayChartData(List<Long> timestamps,
                          List<Long> headDelays,
                          List<Long> headDelayMins,
                          List<Long> headDelayMaxs,
                          List<Long> safeDelays,
                          List<Long> safeDelayMins,
                          List<Long> safeDelayMaxs,
                          List<Long> finalizedDelays,
                          List<Long> finalizedDelayMins,
                          List<Long> finalizedDelayMaxs) {

        static DelayChartData empty() {
            return new DelayChartData(List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }

    private record BucketGrid(Instant chartStart, Instant chartEnd,
                               long bucketMs, int bucketCount,
                               DateTimeFormatter formatter) {
    }

    private ChartBuilder() {
    }

    static double percentile(List<Long> sortedValues, double percentile) {
        if (sortedValues == null || sortedValues.isEmpty()) {
            return 0;
        }
        int size = sortedValues.size();
        if (size == 1) {
            return sortedValues.get(0);
        }
        double position = percentile * (size - 1);
        int lowerIndex = (int) Math.floor(position);
        int upperIndex = (int) Math.ceil(position);
        double lowerValue = sortedValues.get(lowerIndex);
        double upperValue = sortedValues.get(upperIndex);
        if (lowerIndex == upperIndex) {
            return lowerValue;
        }
        double fraction = position - lowerIndex;
        return lowerValue + (upperValue - lowerValue) * fraction;
    }

    static ChartData buildLatencyChart(List<MetricSample> rawSamples,
                                       List<SampleAggregate> aggregateSamples,
                                       TimeRange range, Instant end) {
        List<MetricSample> sortedSamples = rawSamples.stream()
                .sorted(Comparator.comparing(MetricSample::getTimestamp))
                .toList();
        if (sortedSamples.isEmpty() && aggregateSamples.isEmpty()) {
            return ChartData.empty();
        }

        Instant earliest = findEarliest(sortedSamples, aggregateSamples);
        if (earliest == null) {
            return ChartData.empty();
        }

        BucketGrid grid = computeGrid(end.minus(range.getDuration()), end, earliest);
        if (grid == null) {
            return ChartData.empty();
        }

        List<Long> timestamps = new ArrayList<>(grid.bucketCount());
        List<String> labels = new ArrayList<>(grid.bucketCount());
        List<Long> latencies = new ArrayList<>(grid.bucketCount());
        List<Long> latencyMins = new ArrayList<>(grid.bucketCount());
        List<Long> latencyMaxs = new ArrayList<>(grid.bucketCount());
        List<Double> errorRates = new ArrayList<>(grid.bucketCount());
        List<Double> wsErrorRates = new ArrayList<>(grid.bucketCount());
        List<Boolean> httpErrorBuckets = new ArrayList<>(grid.bucketCount());
        List<Boolean> wsErrorBuckets = new ArrayList<>(grid.bucketCount());

        int sampleIndex = 0;
        int aggregateIndex = 0;

        for (int i = 0; i < grid.bucketCount(); i++) {
            Instant bucketStart = grid.chartStart().plusMillis(grid.bucketMs() * i);
            Instant bucketEnd = bucketStart.plusMillis(grid.bucketMs());
            if (bucketEnd.isAfter(grid.chartEnd())) {
                bucketEnd = grid.chartEnd();
            }

            long latencySum = 0;
            long latencyCount = 0;
            long latencyMin = Long.MAX_VALUE;
            long latencyMax = 0;
            int totalBucket = 0;
            int errorBucket = 0;
            int wsBucket = 0;
            int wsErrorBucket = 0;

            while (sampleIndex < sortedSamples.size()) {
                MetricSample sample = sortedSamples.get(sampleIndex);
                Instant ts = sample.getTimestamp();
                if (ts.isBefore(bucketStart)) {
                    sampleIndex++;
                    continue;
                }
                if (!ts.isBefore(bucketEnd)) {
                    break;
                }

                if (sample.getSource() == MetricSource.HTTP) {
                    totalBucket++;
                    if (!sample.isSuccess()) {
                        errorBucket++;
                    }
                    if (sample.getLatencyMs() >= 0) {
                        latencySum += sample.getLatencyMs();
                        latencyCount++;
                        latencyMin = Math.min(latencyMin, sample.getLatencyMs());
                        latencyMax = Math.max(latencyMax, sample.getLatencyMs());
                    }
                } else if (sample.getSource() == MetricSource.WS) {
                    wsBucket++;
                    if (!sample.isSuccess()) {
                        wsErrorBucket++;
                    }
                }
                sampleIndex++;
            }

            while (aggregateIndex < aggregateSamples.size()) {
                SampleAggregate aggregate = aggregateSamples.get(aggregateIndex);
                Instant ts = aggregate.getBucketStart();
                if (ts.isBefore(bucketStart)) {
                    aggregateIndex++;
                    continue;
                }
                if (!ts.isBefore(bucketEnd)) {
                    break;
                }
                latencySum += aggregate.getLatencySumMs();
                latencyCount += aggregate.getLatencyCount();
                if (aggregate.getLatencyCount() > 0) {
                    latencyMin = Math.min(latencyMin, aggregate.getMinLatencyMs());
                    latencyMax = Math.max(latencyMax, aggregate.getMaxLatencyMs());
                }

                totalBucket += aggregate.getHttpCount();
                long httpErrors = aggregate.getErrorCount() - aggregate.getWsErrorCount();
                errorBucket += Math.max(0, httpErrors);

                wsBucket += aggregate.getWsCount();
                wsErrorBucket += aggregate.getWsErrorCount();
                aggregateIndex++;
            }

            labels.add(grid.formatter().format(bucketStart));
            timestamps.add(bucketStart.toEpochMilli());
            latencies.add(latencyCount == 0 ? null : Math.round((double) latencySum / latencyCount));
            latencyMins.add(latencyCount == 0 ? null : latencyMin);
            latencyMaxs.add(latencyCount == 0 ? null : latencyMax);
            errorRates.add(totalBucket == 0 ? null : (double) errorBucket / totalBucket);
            wsErrorRates.add(wsBucket == 0 ? null : (double) wsErrorBucket / wsBucket);
            httpErrorBuckets.add(errorBucket > 0);
            wsErrorBuckets.add(wsErrorBucket > 0);

            if (bucketEnd.equals(grid.chartEnd())) {
                break;
            }
        }

        return new ChartData(timestamps, labels, latencies, latencyMins, latencyMaxs,
                errorRates, wsErrorRates, httpErrorBuckets, wsErrorBuckets);
    }

    static DelayChartData buildDelayChart(List<MetricSample> rawSamples,
                                          List<SampleAggregate> aggregateSamples,
                                          TimeRange range, Instant end) {
        List<MetricSample> sortedSamples = rawSamples.stream()
                .sorted(Comparator.comparing(MetricSample::getTimestamp))
                .toList();
        if (sortedSamples.isEmpty() && aggregateSamples.isEmpty()) {
            return DelayChartData.empty();
        }

        Instant earliest = findEarliest(sortedSamples, aggregateSamples);
        if (earliest == null) {
            return DelayChartData.empty();
        }

        BucketGrid grid = computeGrid(end.minus(range.getDuration()), end, earliest);
        if (grid == null) {
            return DelayChartData.empty();
        }

        List<Long> timestamps = new ArrayList<>(grid.bucketCount());
        List<Long> headDelays = new ArrayList<>(grid.bucketCount());
        List<Long> headDelayMins = new ArrayList<>(grid.bucketCount());
        List<Long> headDelayMaxs = new ArrayList<>(grid.bucketCount());
        List<Long> safeDelays = new ArrayList<>(grid.bucketCount());
        List<Long> safeDelayMins = new ArrayList<>(grid.bucketCount());
        List<Long> safeDelayMaxs = new ArrayList<>(grid.bucketCount());
        List<Long> finalizedDelays = new ArrayList<>(grid.bucketCount());
        List<Long> finalizedDelayMins = new ArrayList<>(grid.bucketCount());
        List<Long> finalizedDelayMaxs = new ArrayList<>(grid.bucketCount());

        int sampleIndex = 0;
        int aggregateIndex = 0;

        for (int i = 0; i < grid.bucketCount(); i++) {
            Instant bucketStart = grid.chartStart().plusMillis(grid.bucketMs() * i);
            Instant bucketEnd = bucketStart.plusMillis(grid.bucketMs());
            if (bucketEnd.isAfter(grid.chartEnd())) {
                bucketEnd = grid.chartEnd();
            }

            long headDelaySum = 0;
            long headDelayCount = 0;
            long headDelayMin = Long.MAX_VALUE;
            long headDelayMax = 0;
            long safeDelaySum = 0;
            long safeDelayCount = 0;
            long safeDelayMin = Long.MAX_VALUE;
            long safeDelayMax = 0;
            long finalizedDelaySum = 0;
            long finalizedDelayCount = 0;
            long finalizedDelayMin = Long.MAX_VALUE;
            long finalizedDelayMax = 0;

            while (sampleIndex < sortedSamples.size()) {
                MetricSample sample = sortedSamples.get(sampleIndex);
                Instant ts = sample.getTimestamp();
                if (ts.isBefore(bucketStart)) {
                    sampleIndex++;
                    continue;
                }
                if (!ts.isBefore(bucketEnd)) {
                    break;
                }

                if (sample.getSource() == MetricSource.WS && sample.getHeadDelayMs() != null) {
                    headDelaySum += sample.getHeadDelayMs();
                    headDelayCount++;
                    headDelayMin = Math.min(headDelayMin, sample.getHeadDelayMs());
                    headDelayMax = Math.max(headDelayMax, sample.getHeadDelayMs());
                }
                if (sample.getSafeDelayMs() != null) {
                    safeDelaySum += sample.getSafeDelayMs();
                    safeDelayCount++;
                    safeDelayMin = Math.min(safeDelayMin, sample.getSafeDelayMs());
                    safeDelayMax = Math.max(safeDelayMax, sample.getSafeDelayMs());
                }
                if (sample.getFinalizedDelayMs() != null) {
                    finalizedDelaySum += sample.getFinalizedDelayMs();
                    finalizedDelayCount++;
                    finalizedDelayMin = Math.min(finalizedDelayMin, sample.getFinalizedDelayMs());
                    finalizedDelayMax = Math.max(finalizedDelayMax, sample.getFinalizedDelayMs());
                }
                sampleIndex++;
            }

            while (aggregateIndex < aggregateSamples.size()) {
                SampleAggregate aggregate = aggregateSamples.get(aggregateIndex);
                Instant ts = aggregate.getBucketStart();
                if (ts.isBefore(bucketStart)) {
                    aggregateIndex++;
                    continue;
                }
                if (!ts.isBefore(bucketEnd)) {
                    break;
                }
                headDelaySum += aggregate.getHeadDelaySumMs();
                headDelayCount += aggregate.getHeadDelayCount();
                if (aggregate.getHeadDelayCount() > 0) {
                    headDelayMin = Math.min(headDelayMin, aggregate.getMinHeadDelayMs());
                    headDelayMax = Math.max(headDelayMax, aggregate.getMaxHeadDelayMs());
                }
                safeDelaySum += aggregate.getSafeDelaySumMs();
                safeDelayCount += aggregate.getSafeDelayCount();
                if (aggregate.getSafeDelayCount() > 0) {
                    safeDelayMin = Math.min(safeDelayMin, aggregate.getMinSafeDelayMs());
                    safeDelayMax = Math.max(safeDelayMax, aggregate.getMaxSafeDelayMs());
                }
                finalizedDelaySum += aggregate.getFinalizedDelaySumMs();
                finalizedDelayCount += aggregate.getFinalizedDelayCount();
                if (aggregate.getFinalizedDelayCount() > 0) {
                    finalizedDelayMin = Math.min(finalizedDelayMin, aggregate.getMinFinalizedDelayMs());
                    finalizedDelayMax = Math.max(finalizedDelayMax, aggregate.getMaxFinalizedDelayMs());
                }
                aggregateIndex++;
            }

            headDelays.add(headDelayCount == 0 ? null : Math.round((double) headDelaySum / headDelayCount));
            headDelayMins.add(headDelayCount == 0 ? null : headDelayMin);
            headDelayMaxs.add(headDelayCount == 0 ? null : headDelayMax);
            timestamps.add(bucketStart.toEpochMilli());
            safeDelays.add(safeDelayCount == 0 ? null : Math.round((double) safeDelaySum / safeDelayCount));
            safeDelayMins.add(safeDelayCount == 0 ? null : safeDelayMin);
            safeDelayMaxs.add(safeDelayCount == 0 ? null : safeDelayMax);
            finalizedDelays.add(finalizedDelayCount == 0 ? null : Math.round((double) finalizedDelaySum / finalizedDelayCount));
            finalizedDelayMins.add(finalizedDelayCount == 0 ? null : finalizedDelayMin);
            finalizedDelayMaxs.add(finalizedDelayCount == 0 ? null : finalizedDelayMax);

            if (bucketEnd.equals(grid.chartEnd())) {
                break;
            }
        }

        return new DelayChartData(timestamps, headDelays, headDelayMins, headDelayMaxs,
                safeDelays, safeDelayMins, safeDelayMaxs,
                finalizedDelays, finalizedDelayMins, finalizedDelayMaxs);
    }

    static DelayChartData buildDelayChartAligned(List<MetricSample> rawSamples,
                                                  List<SampleAggregate> aggregateSamples,
                                                  List<Long> baseTimestamps) {
        if (baseTimestamps == null || baseTimestamps.isEmpty()) {
            return DelayChartData.empty();
        }

        List<MetricSample> sortedSamples = rawSamples.stream()
                .sorted(Comparator.comparing(MetricSample::getTimestamp))
                .toList();

        long bucketMs = baseTimestamps.size() > 1
                ? Math.max(1000, baseTimestamps.get(1) - baseTimestamps.get(0))
                : 1000;

        List<Long> headDelays = new ArrayList<>(baseTimestamps.size());
        List<Long> headDelayMins = new ArrayList<>(baseTimestamps.size());
        List<Long> headDelayMaxs = new ArrayList<>(baseTimestamps.size());
        List<Long> safeDelays = new ArrayList<>(baseTimestamps.size());
        List<Long> safeDelayMins = new ArrayList<>(baseTimestamps.size());
        List<Long> safeDelayMaxs = new ArrayList<>(baseTimestamps.size());
        List<Long> finalizedDelays = new ArrayList<>(baseTimestamps.size());
        List<Long> finalizedDelayMins = new ArrayList<>(baseTimestamps.size());
        List<Long> finalizedDelayMaxs = new ArrayList<>(baseTimestamps.size());

        int sampleIndex = 0;
        int aggregateIndex = 0;

        for (Long tsMillis : baseTimestamps) {
            Instant bucketStart = Instant.ofEpochMilli(tsMillis);
            Instant bucketEnd = bucketStart.plusMillis(bucketMs);

            long headDelaySum = 0;
            long headDelayCount = 0;
            long headDelayMin = Long.MAX_VALUE;
            long headDelayMax = 0;
            long safeDelaySum = 0;
            long safeDelayCount = 0;
            long safeDelayMin = Long.MAX_VALUE;
            long safeDelayMax = 0;
            long finalizedDelaySum = 0;
            long finalizedDelayCount = 0;
            long finalizedDelayMin = Long.MAX_VALUE;
            long finalizedDelayMax = 0;

            while (sampleIndex < sortedSamples.size()) {
                MetricSample sample = sortedSamples.get(sampleIndex);
                Instant ts = sample.getTimestamp();
                if (ts.isBefore(bucketStart)) {
                    sampleIndex++;
                    continue;
                }
                if (!ts.isBefore(bucketEnd)) {
                    break;
                }
                if (sample.getSource() == MetricSource.WS && sample.getHeadDelayMs() != null) {
                    headDelaySum += sample.getHeadDelayMs();
                    headDelayCount++;
                    headDelayMin = Math.min(headDelayMin, sample.getHeadDelayMs());
                    headDelayMax = Math.max(headDelayMax, sample.getHeadDelayMs());
                }
                if (sample.getSafeDelayMs() != null) {
                    safeDelaySum += sample.getSafeDelayMs();
                    safeDelayCount++;
                    safeDelayMin = Math.min(safeDelayMin, sample.getSafeDelayMs());
                    safeDelayMax = Math.max(safeDelayMax, sample.getSafeDelayMs());
                }
                if (sample.getFinalizedDelayMs() != null) {
                    finalizedDelaySum += sample.getFinalizedDelayMs();
                    finalizedDelayCount++;
                    finalizedDelayMin = Math.min(finalizedDelayMin, sample.getFinalizedDelayMs());
                    finalizedDelayMax = Math.max(finalizedDelayMax, sample.getFinalizedDelayMs());
                }
                sampleIndex++;
            }

            while (aggregateIndex < aggregateSamples.size()) {
                SampleAggregate aggregate = aggregateSamples.get(aggregateIndex);
                Instant ts = aggregate.getBucketStart();
                if (ts.isBefore(bucketStart)) {
                    aggregateIndex++;
                    continue;
                }
                if (!ts.isBefore(bucketEnd)) {
                    break;
                }
                headDelaySum += aggregate.getHeadDelaySumMs();
                headDelayCount += aggregate.getHeadDelayCount();
                if (aggregate.getHeadDelayCount() > 0) {
                    headDelayMin = Math.min(headDelayMin, aggregate.getMinHeadDelayMs());
                    headDelayMax = Math.max(headDelayMax, aggregate.getMaxHeadDelayMs());
                }
                safeDelaySum += aggregate.getSafeDelaySumMs();
                safeDelayCount += aggregate.getSafeDelayCount();
                if (aggregate.getSafeDelayCount() > 0) {
                    safeDelayMin = Math.min(safeDelayMin, aggregate.getMinSafeDelayMs());
                    safeDelayMax = Math.max(safeDelayMax, aggregate.getMaxSafeDelayMs());
                }
                finalizedDelaySum += aggregate.getFinalizedDelaySumMs();
                finalizedDelayCount += aggregate.getFinalizedDelayCount();
                if (aggregate.getFinalizedDelayCount() > 0) {
                    finalizedDelayMin = Math.min(finalizedDelayMin, aggregate.getMinFinalizedDelayMs());
                    finalizedDelayMax = Math.max(finalizedDelayMax, aggregate.getMaxFinalizedDelayMs());
                }
                aggregateIndex++;
            }

            headDelays.add(headDelayCount == 0 ? null : Math.round((double) headDelaySum / headDelayCount));
            headDelayMins.add(headDelayCount == 0 ? null : headDelayMin);
            headDelayMaxs.add(headDelayCount == 0 ? null : headDelayMax);
            safeDelays.add(safeDelayCount == 0 ? null : Math.round((double) safeDelaySum / safeDelayCount));
            safeDelayMins.add(safeDelayCount == 0 ? null : safeDelayMin);
            safeDelayMaxs.add(safeDelayCount == 0 ? null : safeDelayMax);
            finalizedDelays.add(finalizedDelayCount == 0 ? null : Math.round((double) finalizedDelaySum / finalizedDelayCount));
            finalizedDelayMins.add(finalizedDelayCount == 0 ? null : finalizedDelayMin);
            finalizedDelayMaxs.add(finalizedDelayCount == 0 ? null : finalizedDelayMax);
        }

        return new DelayChartData(baseTimestamps, headDelays, headDelayMins, headDelayMaxs,
                safeDelays, safeDelayMins, safeDelayMaxs,
                finalizedDelays, finalizedDelayMins, finalizedDelayMaxs);
    }

    private static Instant findEarliest(List<MetricSample> sortedSamples,
                                         List<SampleAggregate> aggregates) {
        Instant earliest = null;
        if (!sortedSamples.isEmpty()) {
            earliest = sortedSamples.get(0).getTimestamp();
        }
        if (!aggregates.isEmpty()) {
            Instant aggStart = aggregates.get(0).getBucketStart();
            if (earliest == null || aggStart.isBefore(earliest)) {
                earliest = aggStart;
            }
        }
        return earliest;
    }

    private static BucketGrid computeGrid(Instant rangeStart, Instant end,
                                           Instant earliestData) {
        Instant chartStart = earliestData.isAfter(rangeStart) ? earliestData : rangeStart;
        Instant chartEnd = end;
        if (chartStart.isAfter(chartEnd)) {
            return null;
        }
        long spanMs = Math.max(1, Duration.between(chartStart, chartEnd).toMillis());
        long bucketMs = Math.max(1000, spanMs / TARGET_POINTS);
        int bucketCount = (int) Math.min(TARGET_POINTS,
                Math.max(1, Math.ceil((double) spanMs / bucketMs)));

        DateTimeFormatter formatter;
        if (spanMs <= Duration.ofHours(6).toMillis()) {
            formatter = DateTimeFormatter.ofPattern("HH:mm");
        } else if (spanMs > Duration.ofDays(10).toMillis()) {
            formatter = DateTimeFormatter.ofPattern("MM-dd");
        } else {
            formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm");
        }
        formatter = formatter.withZone(ZoneId.systemDefault());

        return new BucketGrid(chartStart, chartEnd, bucketMs, bucketCount, formatter);
    }
}

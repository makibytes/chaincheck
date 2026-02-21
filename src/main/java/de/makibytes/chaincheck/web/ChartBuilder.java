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
import de.makibytes.chaincheck.store.HistogramAccumulator;
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
                     List<Boolean> wsErrorBuckets,
                     boolean hasBounds) {

        static ChartData empty() {
            return new ChartData(List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), List.of(), false);
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
                          List<Long> finalizedDelayMaxs,
                          boolean hasBounds) {

        static DelayChartData empty() {
            return new DelayChartData(List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), false);
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
        boolean hasBounds = false;

        int sampleIndex = 0;
        int aggregateIndex = 0;

        for (int i = 0; i < grid.bucketCount(); i++) {
            Instant bucketStart = grid.chartStart().plusMillis(grid.bucketMs() * i);
            Instant bucketEnd = bucketStart.plusMillis(grid.bucketMs());
            if (bucketEnd.isAfter(grid.chartEnd())) {
                bucketEnd = grid.chartEnd();
            }

            List<Long> rawLatencies = new ArrayList<>();
            HistogramAccumulator histAcc = new HistogramAccumulator();
            boolean hasHistogram = false;
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
                        rawLatencies.add(sample.getLatencyMs());
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
                if (aggregate.getLatencyHistogram() != null) {
                    histAcc.merge(aggregate.getLatencyHistogram());
                    hasHistogram = true;
                }
                totalBucket += aggregate.getHttpCount();
                long httpErrors = aggregate.getErrorCount() - aggregate.getWsErrorCount();
                errorBucket += Math.max(0, httpErrors);

                wsBucket += aggregate.getWsCount();
                wsErrorBucket += aggregate.getWsErrorCount();
                aggregateIndex++;
            }

            // Feed raw values into histogram too
            for (Long val : rawLatencies) {
                histAcc.record(val);
            }
            boolean hasRaw = !rawLatencies.isEmpty();
            long totalPoints = histAcc.totalCount();

            Long p95Val = null;
            Long p5Val = null;
            Long p99Val = null;
            if (totalPoints > 0) {
                if (hasRaw && !hasHistogram) {
                    // Pure raw data: use exact percentile calculation
                    rawLatencies.sort(Long::compareTo);
                    p95Val = Math.round(percentile(rawLatencies, 0.95));
                    p5Val = Math.round(percentile(rawLatencies, 0.05));
                    p99Val = Math.round(percentile(rawLatencies, 0.99));
                } else {
                    // Has histogram data (possibly mixed with raw): use histogram
                    p95Val = Math.round(histAcc.percentile(0.95));
                    p5Val = Math.round(histAcc.percentile(0.05));
                    p99Val = Math.round(histAcc.percentile(0.99));
                }
                if (totalPoints >= 2) {
                    hasBounds = true;
                }
            }

            labels.add(grid.formatter().format(bucketStart));
            timestamps.add(bucketStart.toEpochMilli());
            latencies.add(p95Val);
            latencyMins.add(p5Val);
            latencyMaxs.add(p99Val);
            errorRates.add(totalBucket == 0 ? null : (double) errorBucket / totalBucket);
            wsErrorRates.add(wsBucket == 0 ? null : (double) wsErrorBucket / wsBucket);
            httpErrorBuckets.add(errorBucket > 0);
            wsErrorBuckets.add(wsErrorBucket > 0);

            if (bucketEnd.equals(grid.chartEnd())) {
                break;
            }
        }

        return new ChartData(timestamps, labels, latencies, latencyMins, latencyMaxs,
                errorRates, wsErrorRates, httpErrorBuckets, wsErrorBuckets, hasBounds);
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
        boolean hasBounds = false;

        int sampleIndex = 0;
        int aggregateIndex = 0;

        for (int i = 0; i < grid.bucketCount(); i++) {
            Instant bucketStart = grid.chartStart().plusMillis(grid.bucketMs() * i);
            Instant bucketEnd = bucketStart.plusMillis(grid.bucketMs());
            if (bucketEnd.isAfter(grid.chartEnd())) {
                bucketEnd = grid.chartEnd();
            }

            List<Long> rawHead = new ArrayList<>();
            List<Long> rawSafe = new ArrayList<>();
            List<Long> rawFinalized = new ArrayList<>();
            HistogramAccumulator headHist = new HistogramAccumulator();
            HistogramAccumulator safeHist = new HistogramAccumulator();
            HistogramAccumulator finalizedHist = new HistogramAccumulator();
            boolean hasHeadHist = false;
            boolean hasSafeHist = false;
            boolean hasFinalizedHist = false;

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
                    rawHead.add(sample.getHeadDelayMs());
                }
                if (sample.getSafeDelayMs() != null) {
                    rawSafe.add(sample.getSafeDelayMs());
                }
                if (sample.getFinalizedDelayMs() != null) {
                    rawFinalized.add(sample.getFinalizedDelayMs());
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
                if (aggregate.getHeadDelayHistogram() != null) {
                    headHist.merge(aggregate.getHeadDelayHistogram());
                    hasHeadHist = true;
                }
                if (aggregate.getSafeDelayHistogram() != null) {
                    safeHist.merge(aggregate.getSafeDelayHistogram());
                    hasSafeHist = true;
                }
                if (aggregate.getFinalizedDelayHistogram() != null) {
                    finalizedHist.merge(aggregate.getFinalizedDelayHistogram());
                    hasFinalizedHist = true;
                }
                aggregateIndex++;
            }

            PercentileResult headResult = computePercentiles(rawHead, headHist, hasHeadHist);
            PercentileResult safeResult = computePercentiles(rawSafe, safeHist, hasSafeHist);
            PercentileResult finalizedResult = computePercentiles(rawFinalized, finalizedHist, hasFinalizedHist);

            if (headResult.hasBounds || safeResult.hasBounds || finalizedResult.hasBounds) {
                hasBounds = true;
            }

            headDelays.add(headResult.p95);
            headDelayMins.add(headResult.p5);
            headDelayMaxs.add(headResult.p99);
            timestamps.add(bucketStart.toEpochMilli());
            safeDelays.add(safeResult.p95);
            safeDelayMins.add(safeResult.p5);
            safeDelayMaxs.add(safeResult.p99);
            finalizedDelays.add(finalizedResult.p95);
            finalizedDelayMins.add(finalizedResult.p5);
            finalizedDelayMaxs.add(finalizedResult.p99);

            if (bucketEnd.equals(grid.chartEnd())) {
                break;
            }
        }

        return new DelayChartData(timestamps, headDelays, headDelayMins, headDelayMaxs,
                safeDelays, safeDelayMins, safeDelayMaxs,
                finalizedDelays, finalizedDelayMins, finalizedDelayMaxs, hasBounds);
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
        boolean hasBounds = false;

        int sampleIndex = 0;
        int aggregateIndex = 0;

        for (Long tsMillis : baseTimestamps) {
            Instant bucketStart = Instant.ofEpochMilli(tsMillis);
            Instant bucketEnd = bucketStart.plusMillis(bucketMs);

            List<Long> rawHead = new ArrayList<>();
            List<Long> rawSafe = new ArrayList<>();
            List<Long> rawFinalized = new ArrayList<>();
            HistogramAccumulator headHist = new HistogramAccumulator();
            HistogramAccumulator safeHist = new HistogramAccumulator();
            HistogramAccumulator finalizedHist = new HistogramAccumulator();
            boolean hasHeadHist = false;
            boolean hasSafeHist = false;
            boolean hasFinalizedHist = false;

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
                    rawHead.add(sample.getHeadDelayMs());
                }
                if (sample.getSafeDelayMs() != null) {
                    rawSafe.add(sample.getSafeDelayMs());
                }
                if (sample.getFinalizedDelayMs() != null) {
                    rawFinalized.add(sample.getFinalizedDelayMs());
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
                if (aggregate.getHeadDelayHistogram() != null) {
                    headHist.merge(aggregate.getHeadDelayHistogram());
                    hasHeadHist = true;
                }
                if (aggregate.getSafeDelayHistogram() != null) {
                    safeHist.merge(aggregate.getSafeDelayHistogram());
                    hasSafeHist = true;
                }
                if (aggregate.getFinalizedDelayHistogram() != null) {
                    finalizedHist.merge(aggregate.getFinalizedDelayHistogram());
                    hasFinalizedHist = true;
                }
                aggregateIndex++;
            }

            PercentileResult headResult = computePercentiles(rawHead, headHist, hasHeadHist);
            PercentileResult safeResult = computePercentiles(rawSafe, safeHist, hasSafeHist);
            PercentileResult finalizedResult = computePercentiles(rawFinalized, finalizedHist, hasFinalizedHist);

            if (headResult.hasBounds || safeResult.hasBounds || finalizedResult.hasBounds) {
                hasBounds = true;
            }

            headDelays.add(headResult.p95);
            headDelayMins.add(headResult.p5);
            headDelayMaxs.add(headResult.p99);
            safeDelays.add(safeResult.p95);
            safeDelayMins.add(safeResult.p5);
            safeDelayMaxs.add(safeResult.p99);
            finalizedDelays.add(finalizedResult.p95);
            finalizedDelayMins.add(finalizedResult.p5);
            finalizedDelayMaxs.add(finalizedResult.p99);
        }

        return new DelayChartData(baseTimestamps, headDelays, headDelayMins, headDelayMaxs,
                safeDelays, safeDelayMins, safeDelayMaxs,
                finalizedDelays, finalizedDelayMins, finalizedDelayMaxs, hasBounds);
    }

    private record PercentileResult(Long p95, Long p5, Long p99, boolean hasBounds) {}

    private static PercentileResult computePercentiles(List<Long> rawValues,
                                                        HistogramAccumulator histAcc,
                                                        boolean hasHistogram) {
        for (Long val : rawValues) {
            histAcc.record(val);
        }
        boolean hasRaw = !rawValues.isEmpty();
        long totalPoints = histAcc.totalCount();

        if (totalPoints == 0) {
            return new PercentileResult(null, null, null, false);
        }

        Long p95;
        Long p5;
        Long p99;
        if (hasRaw && !hasHistogram) {
            rawValues.sort(Long::compareTo);
            p95 = Math.round(percentile(rawValues, 0.95));
            p5 = Math.round(percentile(rawValues, 0.05));
            p99 = Math.round(percentile(rawValues, 0.99));
        } else {
            p95 = Math.round(histAcc.percentile(0.95));
            p5 = Math.round(histAcc.percentile(0.05));
            p99 = Math.round(histAcc.percentile(0.99));
        }
        return new PercentileResult(p95, p5, p99, totalPoints >= 2);
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
        if (chartStart.isAfter(end)) {
            return null;
        }
        long spanMs = Math.max(1, Duration.between(chartStart, end).toMillis());
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

        return new BucketGrid(chartStart, end, bucketMs, bucketCount, formatter);
    }
}

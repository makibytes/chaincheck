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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import de.makibytes.chaincheck.model.AnomalyEvent;
import de.makibytes.chaincheck.model.AnomalyType;
import de.makibytes.chaincheck.model.DashboardSummary;
import de.makibytes.chaincheck.model.MetricSample;
import de.makibytes.chaincheck.model.MetricSource;
import de.makibytes.chaincheck.model.TimeRange;
import de.makibytes.chaincheck.monitor.NodeRegistry;
import de.makibytes.chaincheck.monitor.WsConnectionTracker;
import de.makibytes.chaincheck.store.AnomalyAggregate;
import de.makibytes.chaincheck.store.InMemoryMetricsStore;
import de.makibytes.chaincheck.store.SampleAggregate;

@Service
public class DashboardService {

    private final InMemoryMetricsStore store;
    private final MetricsCache cache;
    private final NodeRegistry nodeRegistry;
    private static final int MAX_SAMPLES = 1000;
    private static final int PAGE_SIZE = 50;
    private static final int MAX_PAGES = 20;
    private static final int MAX_ANOMALIES = 1000;

    public DashboardService(InMemoryMetricsStore store, MetricsCache cache, NodeRegistry nodeRegistry) {
        this.store = store;
        this.cache = cache;
        this.nodeRegistry = nodeRegistry;
    }

    public DashboardView getDashboard(String nodeKey, TimeRange range) {
        DashboardView cached = cache.get(nodeKey, range);
        if (cached != null) {
            return cached;
        }

        Instant now = Instant.now();
        Instant since = now.minus(range.getDuration());
        Instant rawCutoff = now.minus(Duration.ofHours(24));
        Instant rawSince = since.isAfter(rawCutoff) ? since : rawCutoff;

        List<MetricSample> rawSamples = store.getRawSamplesSince(nodeKey, rawSince);
        List<SampleAggregate> aggregateSamples = store.getAggregatedSamplesSince(nodeKey, since).stream()
                .filter(aggregate -> aggregate.getBucketStart().isBefore(rawCutoff))
                .toList();

        List<AnomalyEvent> anomalies = store.getRawAnomaliesSince(nodeKey, rawSince)
                .stream()
                .sorted(Comparator.comparing(AnomalyEvent::getTimestamp).reversed())
                .collect(Collectors.toList());
        List<AnomalyAggregate> aggregatedAnomalies = store.getAggregatedAnomaliesSince(nodeKey, since).stream()
                .filter(aggregate -> aggregate.getBucketStart().isBefore(rawCutoff))
                .toList();

        long rawTotal = rawSamples.size();
        long rawSuccess = rawSamples.stream().filter(MetricSample::isSuccess).count();
        long rawErrors = rawTotal - rawSuccess;
        long aggTotal = aggregateSamples.stream().mapToLong(SampleAggregate::getTotalCount).sum();
        long aggSuccess = aggregateSamples.stream().mapToLong(SampleAggregate::getSuccessCount).sum();
        long aggErrors = aggregateSamples.stream().mapToLong(SampleAggregate::getErrorCount).sum();

        long total = rawTotal + aggTotal;
        long success = rawSuccess + aggSuccess;
        long errors = rawErrors + aggErrors;

        long rawLatencySum = rawSamples.stream()
                .filter(sample -> sample.getLatencyMs() >= 0)
                .mapToLong(MetricSample::getLatencyMs)
                .sum();
        long rawLatencyCount = rawSamples.stream().filter(sample -> sample.getLatencyMs() >= 0).count();
        long rawMaxLatency = rawSamples.stream().filter(sample -> sample.getLatencyMs() >= 0)
                .mapToLong(MetricSample::getLatencyMs).max().orElse(0);
        long aggLatencySum = aggregateSamples.stream().mapToLong(SampleAggregate::getLatencySumMs).sum();
        long aggLatencyCount = aggregateSamples.stream().mapToLong(SampleAggregate::getLatencyCount).sum();
        long aggMaxLatency = aggregateSamples.stream().mapToLong(SampleAggregate::getMaxLatencyMs).max().orElse(0);

        long latencySum = rawLatencySum + aggLatencySum;
        long latencyCount = rawLatencyCount + aggLatencyCount;
        double avgLatency = latencyCount == 0 ? 0 : (double) latencySum / latencyCount;
        long maxLatency = Math.max(rawMaxLatency, aggMaxLatency);

                List<Long> latencyValues = rawSamples.stream()
                                .filter(sample -> sample.getLatencyMs() >= 0)
                                .map(MetricSample::getLatencyMs)
                                .collect(Collectors.toCollection(java.util.ArrayList::new));
                for (SampleAggregate aggregate : aggregateSamples) {
                        if (aggregate.getLatencyCount() > 0) {
                                latencyValues.add(Math.round((double) aggregate.getLatencySumMs() / aggregate.getLatencyCount()));
                        }
                }
        latencyValues = latencyValues.stream().sorted().toList();
        long p95Latency = percentile(latencyValues, 0.95);
        long p99Latency = percentile(latencyValues, 0.99);

        long httpCount = rawSamples.stream().filter(sample -> sample.getSource() == MetricSource.HTTP).count()
                + aggregateSamples.stream().mapToLong(SampleAggregate::getHttpCount).sum();
        long wsCount = rawSamples.stream().filter(sample -> sample.getSource() == MetricSource.WS).count()
                + aggregateSamples.stream().mapToLong(SampleAggregate::getWsCount).sum();
        double rangeSeconds = Math.max(1, range.getDuration().toSeconds());
        double httpRps = httpCount / rangeSeconds;
        double wsEventsPerMinute = (wsCount / rangeSeconds) * 60.0;
        double uptimePercent = total == 0 ? 0 : (success * 100.0) / total;
        double errorRatePercent = total == 0 ? 0 : (errors * 100.0) / total;

        List<Long> propagationDelays = rawSamples.stream()
                .filter(sample -> sample.getSource() == MetricSource.HTTP)
                .filter(sample -> sample.getBlockTimestamp() != null)
                .map(sample -> Duration.between(sample.getBlockTimestamp(), sample.getTimestamp()).toMillis())
                .filter(delay -> delay >= 0)
                .toList();
        long rawPropagationSum = propagationDelays.stream().mapToLong(Long::longValue).sum();
        long rawPropagationCount = propagationDelays.size();
        long rawPropagationMax = propagationDelays.stream().mapToLong(Long::longValue).max().orElse(0);
        long aggPropagationSum = aggregateSamples.stream().mapToLong(SampleAggregate::getPropagationDelaySumMs).sum();
        long aggPropagationCount = aggregateSamples.stream().mapToLong(SampleAggregate::getPropagationDelayCount).sum();
        long aggPropagationMax = aggregateSamples.stream().mapToLong(SampleAggregate::getMaxPropagationDelayMs).max().orElse(0);
        long propagationSum = rawPropagationSum + aggPropagationSum;
        long propagationCount = rawPropagationCount + aggPropagationCount;
        double avgPropagationDelay = propagationCount == 0 ? 0 : (double) propagationSum / propagationCount;

                List<Long> propagationValues = new java.util.ArrayList<>(propagationDelays);
                for (SampleAggregate aggregate : aggregateSamples) {
                        if (aggregate.getPropagationDelayCount() > 0) {
                                propagationValues.add(Math.round((double) aggregate.getPropagationDelaySumMs() / aggregate.getPropagationDelayCount()));
                        }
                }
        propagationValues = propagationValues.stream().sorted().toList();
        long p95PropagationDelay = percentile(propagationValues, 0.95);
        long maxPropagationDelay = Math.max(rawPropagationMax, aggPropagationMax);
        long staleBlockCount = propagationValues.stream().filter(delay -> delay > 30000).count()
                + aggregateSamples.stream().mapToLong(SampleAggregate::getStaleBlockCount).sum();

        Map<AnomalyType, Long> anomalyCounts = anomalies.stream()
                .collect(Collectors.groupingBy(AnomalyEvent::getType, Collectors.counting()));
        aggregatedAnomalies.forEach(aggregate -> {
            anomalyCounts.merge(AnomalyType.DELAY, aggregate.getDelayCount(), Long::sum);
            anomalyCounts.merge(AnomalyType.REORG, aggregate.getReorgCount(), Long::sum);
            anomalyCounts.merge(AnomalyType.BLOCK_GAP, aggregate.getBlockGapCount(), Long::sum);
            anomalyCounts.merge(AnomalyType.ERROR, aggregate.getErrorCount(), Long::sum);
        });

        long maxBlockNumber = 0;
        for (var node : nodeRegistry.getNodes()) {
            Long latest = store.getLatestBlockNumber(node.key());
            if (latest != null && latest > maxBlockNumber) {
                maxBlockNumber = latest;
            }
        }
        Long currentLatest = store.getLatestBlockNumber(nodeKey);
        long blockLagBlocks = currentLatest == null ? 0 : Math.max(0, maxBlockNumber - currentLatest);

        DashboardSummary summary = new DashboardSummary(
                total,
                success,
                errors,
                avgLatency,
                maxLatency,
                p95Latency,
                p99Latency,
                httpRps,
                wsEventsPerMinute,
                uptimePercent,
                errorRatePercent,
                avgPropagationDelay,
                p95PropagationDelay,
                maxPropagationDelay,
                staleBlockCount,
                blockLagBlocks,
                anomalyCounts.getOrDefault(AnomalyType.DELAY, 0L),
                anomalyCounts.getOrDefault(AnomalyType.REORG, 0L),
                anomalyCounts.getOrDefault(AnomalyType.BLOCK_GAP, 0L));

        List<MetricSample> sortedSamples = rawSamples.stream()
                .filter(sample -> sample.getSource() == MetricSource.HTTP)
                .sorted(Comparator.comparing(MetricSample::getTimestamp).reversed())
                .limit(MAX_SAMPLES)
                .toList();

        int totalSamples = sortedSamples.size();
        int totalPages = Math.max(1, Math.min(MAX_PAGES, (int) Math.ceil(totalSamples / (double) PAGE_SIZE)));

        ChartData chartData = buildLatencyChart(rawSamples, aggregateSamples, range);
        List<String> chartLabels = chartData.labels();
        List<Long> chartLatencies = chartData.latencies();
        List<Double> chartErrorRates = chartData.errorRates();
        List<Double> chartWsErrorRates = chartData.wsErrorRates();

        DateTimeFormatter rowFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
        List<SampleRow> sampleRows = sortedSamples.stream()
                .map(sample -> new SampleRow(
                        rowFormatter.format(sample.getTimestamp()),
                        sample.isSuccess() ? "OK" : "ERROR",
                        sample.getLatencyMs() >= 0 ? sample.getLatencyMs() : null,
                        sample.getBlockNumber(),
                        sample.getTransactionCount(),
                        sample.getGasPriceWei()))
                .toList();

        List<AnomalyEvent> pagedAnomalies = anomalies.stream()
                .limit(MAX_ANOMALIES)
                .toList();
        int totalAnomalies = pagedAnomalies.size();
        int anomalyTotalPages = Math.max(1, Math.min(MAX_PAGES, (int) Math.ceil(totalAnomalies / (double) PAGE_SIZE)));
        DateTimeFormatter anomalyFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
        List<AnomalyRow> anomalyRows = pagedAnomalies.stream()
                .map(event -> new AnomalyRow(
                        event.getId(),
                        anomalyFormatter.format(event.getTimestamp()),
                        event.getType().name(),
                        event.getSource().name(),
                        event.getMessage()))
                .toList();

        WsConnectionTracker wsTracker = nodeRegistry.getWsTracker(nodeKey);
        WsStatus wsStatus = wsTracker == null
                ? new WsStatus(false, null, null, 0, 0, 0, null)
                : new WsStatus(
                        wsTracker.getConnectedSince() != null,
                        wsTracker.getConnectedSince(),
                        wsTracker.getLastDisconnectedAt(),
                        wsTracker.getConnectCount(),
                        wsTracker.getDisconnectCount(),
                        wsTracker.getConnectFailureCount(),
                        wsTracker.getLastError());

        DashboardView view = new DashboardView(
                range,
                summary,
                anomalies,
                anomalyRows,
                sampleRows,
                chartLabels,
                chartLatencies,
                chartErrorRates,
                chartWsErrorRates,
                wsStatus,
                totalPages,
                PAGE_SIZE,
                totalSamples,
                anomalyTotalPages,
                PAGE_SIZE,
                totalAnomalies,
                Instant.now());
        cache.put(nodeKey, range, view);
        return view;
    }

    public AnomalyEvent getAnomaly(long id) {
        return store.getAnomaly(id);
    }
    private static long percentile(List<Long> sortedValues, double percentile) {
        if (sortedValues == null || sortedValues.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(percentile * sortedValues.size()) - 1;
        int safeIndex = Math.min(Math.max(index, 0), sortedValues.size() - 1);
        return sortedValues.get(safeIndex);
    }

    private ChartData buildLatencyChart(List<MetricSample> rawSamples,
                                        List<SampleAggregate> aggregateSamples,
                                        TimeRange range) {
        List<MetricSample> sortedSamples = rawSamples.stream()
                .sorted(Comparator.comparing(MetricSample::getTimestamp))
                .toList();
        if (sortedSamples.isEmpty() && aggregateSamples.isEmpty()) {
            return new ChartData(List.of(), List.of(), List.of(), List.of());
        }

        Instant now = Instant.now();
        Instant rangeStart = now.minus(range.getDuration());
        Instant earliest = null;
        if (!sortedSamples.isEmpty()) {
            earliest = sortedSamples.get(0).getTimestamp();
        } else if (!aggregateSamples.isEmpty()) {
            earliest = aggregateSamples.get(0).getBucketStart();
        }
        if (earliest == null) {
            return new ChartData(List.of(), List.of(), List.of(), List.of());
        }
        Instant chartStart = earliest.isAfter(rangeStart) ? earliest : rangeStart;
        Instant chartEnd = now;
        if (chartStart.isAfter(chartEnd)) {
            return new ChartData(List.of(), List.of(), List.of(), List.of());
        }

        long spanMs = Math.max(1, Duration.between(chartStart, chartEnd).toMillis());
        int targetPoints = 120;
        long bucketMs = Math.max(1000, spanMs / targetPoints);
        int bucketCount = (int) Math.min(targetPoints, Math.max(1, Math.ceil((double) spanMs / bucketMs)));

        DateTimeFormatter formatter = spanMs > Duration.ofDays(2).toMillis()
                ? DateTimeFormatter.ofPattern("MM-dd")
                : DateTimeFormatter.ofPattern("HH:mm");
        formatter = formatter.withZone(ZoneId.systemDefault());

        List<String> labels = new java.util.ArrayList<>(bucketCount);
        List<Long> latencies = new java.util.ArrayList<>(bucketCount);
        List<Double> errorRates = new java.util.ArrayList<>(bucketCount);
        List<Double> wsErrorRates = new java.util.ArrayList<>(bucketCount);

        int sampleIndex = 0;
        int aggregateIndex = 0;

        for (int i = 0; i < bucketCount; i++) {
            Instant bucketStart = chartStart.plusMillis(bucketMs * i);
            Instant bucketEnd = bucketStart.plusMillis(bucketMs);
            if (bucketEnd.isAfter(chartEnd)) {
                bucketEnd = chartEnd;
            }

            long latencySum = 0;
            long latencyCount = 0;
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
                
                // For HTTP error rates, we need only HTTP samples
                totalBucket += aggregate.getHttpCount();
                // errorCount contains total errors (HTTP + WS). wsErrorCount is known.
                // Assuming only HTTP and WS exist.
                long httpErrors = aggregate.getErrorCount() - aggregate.getWsErrorCount();
                errorBucket += Math.max(0, httpErrors);
                
                wsBucket += aggregate.getWsCount();
                wsErrorBucket += aggregate.getWsErrorCount();
                aggregateIndex++;
            }

            labels.add(formatter.format(bucketStart));
            latencies.add(latencyCount == 0 ? null : Math.round((double) latencySum / latencyCount));
            errorRates.add(totalBucket == 0 ? null : (double) errorBucket / totalBucket);
            wsErrorRates.add(wsBucket == 0 ? null : (double) wsErrorBucket / wsBucket);

            if (bucketEnd.equals(chartEnd)) {
                break;
            }
        }

        return new ChartData(labels, latencies, errorRates, wsErrorRates);
    }

    private record ChartData(List<String> labels, List<Long> latencies, List<Double> errorRates, List<Double> wsErrorRates) {
    }

}

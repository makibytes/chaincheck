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
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import de.makibytes.chaincheck.config.ChainCheckProperties;
import de.makibytes.chaincheck.model.AnomalyEvent;
import de.makibytes.chaincheck.model.AnomalyType;
import de.makibytes.chaincheck.model.DashboardSummary;
import de.makibytes.chaincheck.model.MetricSample;
import de.makibytes.chaincheck.model.MetricSource;
import de.makibytes.chaincheck.model.TimeRange;
import de.makibytes.chaincheck.monitor.NodeRegistry;
import de.makibytes.chaincheck.monitor.RpcMonitorService;
import de.makibytes.chaincheck.monitor.WsConnectionTracker;
import de.makibytes.chaincheck.store.AnomalyAggregate;
import de.makibytes.chaincheck.store.InMemoryMetricsStore;
import de.makibytes.chaincheck.store.SampleAggregate;

@Service
public class DashboardService {

    private final InMemoryMetricsStore store;
    private final MetricsCache cache;
    private final NodeRegistry nodeRegistry;
    private final RpcMonitorService nodeMonitorService;
    private final ChainCheckProperties properties;
    private static final int MAX_SAMPLES = 10000;
    private static final int PAGE_SIZE = 50;
    private static final int MAX_PAGES = 20;
    private static final int MAX_ANOMALIES = 10000;

    public DashboardService(InMemoryMetricsStore store,
                            MetricsCache cache,
                            NodeRegistry nodeRegistry,
                            RpcMonitorService nodeMonitorService,
                            ChainCheckProperties properties) {
        this.store = store;
        this.cache = cache;
        this.nodeRegistry = nodeRegistry;
        this.nodeMonitorService = nodeMonitorService;
        this.properties = properties;
    }

    public DashboardView getDashboard(String nodeKey, TimeRange range, Instant end) {
        DashboardView cached = cache.get(nodeKey, range, end);
        if (cached != null) {
            return cached;
        }

        NodeRegistry.NodeDefinition nodeDefinition = nodeRegistry.getNode(nodeKey);
        boolean httpConfigured = nodeDefinition != null
            && nodeDefinition.http() != null
            && !nodeDefinition.http().isBlank();
        boolean wsConfigured = nodeDefinition != null
            && nodeDefinition.ws() != null
            && !nodeDefinition.ws().isBlank();

        Instant now = end == null ? Instant.now() : end;
        Instant since = now.minus(range.getDuration());

        List<MetricSample> rawSamples = store.getRawSamplesSince(nodeKey, since);
        // Get all aggregates since the requested time range (they may contain data within range even if started before)
        List<SampleAggregate> aggregateSamples = store.getAggregatedSamplesSince(nodeKey, since).stream()
                .toList();

        List<AnomalyEvent> anomalies = store.getRawAnomaliesSince(nodeKey, since)
                .stream()
                .sorted(Comparator.comparing(AnomalyEvent::getTimestamp).reversed())
                .collect(Collectors.toList());
        // Get all aggregated anomalies - they may contain data within range even if started before
        List<AnomalyAggregate> aggregatedAnomalies = store.getAggregatedAnomaliesSince(nodeKey, since).stream()
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
        if (latencyValues.isEmpty()) {
            for (SampleAggregate aggregate : aggregateSamples) {
                if (aggregate.getLatencyCount() > 0) {
                    latencyValues.add(Math.round((double) aggregate.getLatencySumMs() / aggregate.getLatencyCount()));
                }
            }
        }
        latencyValues = latencyValues.stream().sorted().toList();
        double p95Latency = ChartBuilder.percentile(latencyValues, 0.95);
        double p99Latency = ChartBuilder.percentile(latencyValues, 0.99);

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
        List<Long> newBlockDelays = rawSamples.stream()
                .filter(sample -> sample.getSource() == MetricSource.WS)
                .map(MetricSample::getHeadDelayMs)
                .filter(delay -> delay != null && delay >= 0)
                .map(Long::longValue)
                .toList();
        long rawNewBlockSum = newBlockDelays.stream().mapToLong(Long::longValue).sum();
        long rawNewBlockCount = newBlockDelays.size();
        long aggNewBlockSum = aggregateSamples.stream().mapToLong(SampleAggregate::getHeadDelaySumMs).sum();
        long aggNewBlockCount = aggregateSamples.stream().mapToLong(SampleAggregate::getHeadDelayCount).sum();
        long newBlockSum = rawNewBlockSum + aggNewBlockSum;
        long newBlockCount = rawNewBlockCount + aggNewBlockCount;
        double avgNewBlockPropagation = newBlockCount == 0 ? 0 : (double) newBlockSum / newBlockCount;

        List<Long> newBlockValues = new ArrayList<>(newBlockDelays);
        if (newBlockValues.isEmpty()) {
            for (SampleAggregate aggregate : aggregateSamples) {
                if (aggregate.getHeadDelayCount() > 0) {
                    newBlockValues.add(Math.round((double) aggregate.getHeadDelaySumMs() / aggregate.getHeadDelayCount()));
                }
            }
        }
        newBlockValues = newBlockValues.stream().sorted().toList();
        double p95NewBlockPropagation = ChartBuilder.percentile(newBlockValues, 0.95);
        double p99NewBlockPropagation = ChartBuilder.percentile(newBlockValues, 0.99);

        List<Long> safeDelays = rawSamples.stream()
                .map(MetricSample::getSafeDelayMs)
                .filter(delay -> delay != null && delay >= 0)
                .map(Long::longValue)
                .toList();
        long rawSafeSum = safeDelays.stream().mapToLong(Long::longValue).sum();
        long rawSafeCount = safeDelays.size();
        long aggSafeSum = aggregateSamples.stream().mapToLong(SampleAggregate::getSafeDelaySumMs).sum();
        long aggSafeCount = aggregateSamples.stream().mapToLong(SampleAggregate::getSafeDelayCount).sum();
        long safeSum = rawSafeSum + aggSafeSum;
        long safeCount = rawSafeCount + aggSafeCount;
        double avgSafePropagation = safeCount == 0 ? 0 : (double) safeSum / safeCount;

        List<Long> safeValues = new ArrayList<>(safeDelays);
        if (safeValues.isEmpty()) {
            for (SampleAggregate aggregate : aggregateSamples) {
                if (aggregate.getSafeDelayCount() > 0) {
                    safeValues.add(Math.round((double) aggregate.getSafeDelaySumMs() / aggregate.getSafeDelayCount()));
                }
            }
        }
        safeValues = safeValues.stream().sorted().toList();
        double p95SafePropagation = ChartBuilder.percentile(safeValues, 0.95);
        double p99SafePropagation = ChartBuilder.percentile(safeValues, 0.99);

        List<Long> finalizedDelays = rawSamples.stream()
                .map(MetricSample::getFinalizedDelayMs)
                .filter(delay -> delay != null && delay >= 0)
                .map(Long::longValue)
                .toList();
        long rawFinalizedSum = finalizedDelays.stream().mapToLong(Long::longValue).sum();
        long rawFinalizedCount = finalizedDelays.size();
        long aggFinalizedSum = aggregateSamples.stream().mapToLong(SampleAggregate::getFinalizedDelaySumMs).sum();
        long aggFinalizedCount = aggregateSamples.stream().mapToLong(SampleAggregate::getFinalizedDelayCount).sum();
        long finalizedSum = rawFinalizedSum + aggFinalizedSum;
        long finalizedCount = rawFinalizedCount + aggFinalizedCount;
        double avgFinalizedPropagation = finalizedCount == 0 ? 0 : (double) finalizedSum / finalizedCount;

        List<Long> finalizedValues = new ArrayList<>(finalizedDelays);
        if (finalizedValues.isEmpty()) {
            for (SampleAggregate aggregate : aggregateSamples) {
                if (aggregate.getFinalizedDelayCount() > 0) {
                    finalizedValues.add(Math.round((double) aggregate.getFinalizedDelaySumMs() / aggregate.getFinalizedDelayCount()));
                }
            }
        }
        finalizedValues = finalizedValues.stream().sorted().toList();
        double p95FinalizedPropagation = ChartBuilder.percentile(finalizedValues, 0.95);
        double p99FinalizedPropagation = ChartBuilder.percentile(finalizedValues, 0.99);

        List<Long> propagationValues = new ArrayList<>(propagationDelays);
        for (SampleAggregate aggregate : aggregateSamples) {
            if (aggregate.getPropagationDelayCount() > 0) {
                propagationValues.add(Math.round((double) aggregate.getPropagationDelaySumMs() / aggregate.getPropagationDelayCount()));
            }
        }
        long staleBlockCount = propagationValues.stream().filter(delay -> delay > 30000).count()
                + aggregateSamples.stream().mapToLong(SampleAggregate::getStaleBlockCount).sum();

        Map<AnomalyType, Long> anomalyCounts = anomalies.stream()
                .collect(Collectors.groupingBy(AnomalyEvent::getType, Collectors.counting()));
        aggregatedAnomalies.forEach(aggregate -> {
            anomalyCounts.put(AnomalyType.DELAY,
                anomalyCounts.getOrDefault(AnomalyType.DELAY, 0L) + aggregate.getDelayCount());
            anomalyCounts.put(AnomalyType.REORG,
                anomalyCounts.getOrDefault(AnomalyType.REORG, 0L) + aggregate.getReorgCount());
            anomalyCounts.put(AnomalyType.CONFLICT,
                anomalyCounts.getOrDefault(AnomalyType.CONFLICT, 0L) + aggregate.getConflictCount());
            anomalyCounts.put(AnomalyType.BLOCK_GAP,
                anomalyCounts.getOrDefault(AnomalyType.BLOCK_GAP, 0L) + aggregate.getBlockGapCount());
            anomalyCounts.put(AnomalyType.ERROR,
                anomalyCounts.getOrDefault(AnomalyType.ERROR, 0L) + aggregate.getErrorCount());
            anomalyCounts.put(AnomalyType.RATE_LIMIT,
                anomalyCounts.getOrDefault(AnomalyType.RATE_LIMIT, 0L) + aggregate.getRateLimitCount());
            anomalyCounts.put(AnomalyType.TIMEOUT,
                anomalyCounts.getOrDefault(AnomalyType.TIMEOUT, 0L) + aggregate.getTimeoutCount());
            anomalyCounts.put(AnomalyType.WRONG_HEAD,
                anomalyCounts.getOrDefault(AnomalyType.WRONG_HEAD, 0L) + aggregate.getWrongHeadCount());
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

        ChartBuilder.ChartData chartData = ChartBuilder.buildLatencyChart(rawSamples, aggregateSamples, range, now);
        ChartBuilder.DelayChartData delayChartData = ChartBuilder.buildDelayChart(rawSamples, aggregateSamples, range, now);

        List<Long> headDelaySeries = delayChartData.headDelays().stream()
                .filter(value -> value != null && value >= 0)
                .toList();
        if (!headDelaySeries.isEmpty()) {
            long headDelaySum = headDelaySeries.stream().mapToLong(Long::longValue).sum();
            avgNewBlockPropagation = (double) headDelaySum / headDelaySeries.size();
            List<Long> sortedHeadDelaySeries = headDelaySeries.stream().sorted().toList();
            p95NewBlockPropagation = ChartBuilder.percentile(sortedHeadDelaySeries, 0.95);
            p99NewBlockPropagation = ChartBuilder.percentile(sortedHeadDelaySeries, 0.99);
        }

        List<Long> safeDelaySeries = delayChartData.safeDelays().stream()
                .filter(value -> value != null && value >= 0)
                .toList();
        if (!safeDelaySeries.isEmpty()) {
            long safeDelaySum = safeDelaySeries.stream().mapToLong(Long::longValue).sum();
            avgSafePropagation = (double) safeDelaySum / safeDelaySeries.size();
            List<Long> sortedSafeDelaySeries = safeDelaySeries.stream().sorted().toList();
            p95SafePropagation = ChartBuilder.percentile(sortedSafeDelaySeries, 0.95);
            p99SafePropagation = ChartBuilder.percentile(sortedSafeDelaySeries, 0.99);
        }

        List<Long> finalizedDelaySeries = delayChartData.finalizedDelays().stream()
                .filter(value -> value != null && value >= 0)
                .toList();
        if (!finalizedDelaySeries.isEmpty()) {
            long finalizedDelaySum = finalizedDelaySeries.stream().mapToLong(Long::longValue).sum();
            avgFinalizedPropagation = (double) finalizedDelaySum / finalizedDelaySeries.size();
            List<Long> sortedFinalizedDelaySeries = finalizedDelaySeries.stream().sorted().toList();
            p95FinalizedPropagation = ChartBuilder.percentile(sortedFinalizedDelaySeries, 0.95);
            p99FinalizedPropagation = ChartBuilder.percentile(sortedFinalizedDelaySeries, 0.99);
        }

        boolean hasAggregatedLatencies = aggregateSamples.stream().anyMatch(sample -> sample.getLatencyCount() > 0);
        boolean hasAggregatedDelays = aggregateSamples.stream().anyMatch(sample ->
            sample.getHeadDelayCount() > 0
                || sample.getSafeDelayCount() > 0
                || sample.getFinalizedDelayCount() > 0);

        java.util.Set<String> consensusSafeHashes = java.util.Set.of();
        java.util.Set<String> consensusFinalizedHashes = java.util.Set.of();
        if (nodeMonitorService.hasConfiguredReferenceMode()) {
            List<MetricSample> consensusReferenceSamples = nodeMonitorService.getConfiguredReferenceDelaySamplesSince(since);
            consensusSafeHashes = consensusReferenceSamples.stream()
                .filter(sample -> sample.getSafeDelayMs() != null)
                .map(MetricSample::getBlockHash)
                .filter(hash -> hash != null && !hash.isBlank())
                .collect(Collectors.toSet());
            consensusFinalizedHashes = consensusReferenceSamples.stream()
                .filter(sample -> sample.getFinalizedDelayMs() != null)
                .map(MetricSample::getBlockHash)
                .filter(hash -> hash != null && !hash.isBlank())
                .collect(Collectors.toSet());
        }
            final java.util.Set<String> effectiveConsensusSafeHashes = consensusSafeHashes;
            final java.util.Set<String> effectiveConsensusFinalizedHashes = consensusFinalizedHashes;

        // Group samples by blockhash and merge them
        Map<String, List<MetricSample>> samplesByHash = rawSamples.stream()
                .filter(s -> s.getBlockHash() != null && !s.getBlockHash().isBlank())
                .collect(Collectors.groupingBy(MetricSample::getBlockHash));

        DateTimeFormatter rowFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

        // First pass: create intermediate rows with block number and initial finalized/safe status
        Map<Long, java.util.Set<String>> finalizedHashesByNumber = new HashMap<>();
        Map<String, Instant> sampleTimestampByHash = new HashMap<>();
        
        List<SampleRow> intermediateRows = samplesByHash.entrySet().stream()
                .map(entry -> {
                    List<MetricSample> samples = entry.getValue();
                    samples.sort(Comparator.comparing(MetricSample::getTimestamp));
                    MetricSample first = samples.get(0);
                    
                    List<String> sources = samples.stream()
                            .map(s -> s.getSource() == MetricSource.HTTP ? "HTTP" : "WS")
                            .distinct()
                            .sorted()
                            .toList();
                    
                        Long avgLatencyForRow = null;
                    List<Long> validLatencies = samples.stream()
                            .filter(s -> s.getLatencyMs() >= 0)
                            .map(MetricSample::getLatencyMs)
                            .toList();
                    if (!validLatencies.isEmpty()) {
                        avgLatencyForRow = Math.round(validLatencies.stream()
                                .mapToLong(Long::longValue)
                                .average()
                                .orElse(-1));
                    }
                    
                    boolean allSuccess = samples.stream().allMatch(MetricSample::isSuccess);
                        String blockHash = samples.stream()
                            .map(MetricSample::getBlockHash)
                            .filter(hash -> hash != null && !hash.isBlank())
                            .findFirst()
                            .orElse(null);
                    // Determine if this block is safe/finalized either from node-local queries
                    // or from configured consensus reference observations.
                    boolean hasFinalized = samples.stream().anyMatch(s -> s.getFinalizedDelayMs() != null)
                            || (blockHash != null && effectiveConsensusFinalizedHashes.contains(blockHash));
                    boolean hasSafe = samples.stream().anyMatch(s -> s.getSafeDelayMs() != null)
                            || (blockHash != null && effectiveConsensusSafeHashes.contains(blockHash));
                        String parentHash = samples.stream()
                            .map(MetricSample::getParentHash)
                            .filter(hash -> hash != null && !hash.isBlank())
                            .findFirst()
                            .orElse(null);
                        Instant blockTimestamp = samples.stream()
                            .map(MetricSample::getBlockTimestamp)
                            .filter(ts -> ts != null)
                            .findFirst()
                            .orElse(null);
                        String blockTime = blockTimestamp == null ? null : rowFormatter.format(blockTimestamp);
                    Integer transactionCount = samples.stream()
                            .map(MetricSample::getTransactionCount)
                            .filter(tc -> tc != null)
                            .findFirst()
                            .orElse(null);
                    Long gasPriceWei = samples.stream()
                            .map(MetricSample::getGasPriceWei)
                            .filter(gp -> gp != null)
                            .findFirst()
                            .orElse(null);
                    
                    if (blockHash != null && !blockHash.isBlank()) {
                        sampleTimestampByHash.putIfAbsent(blockHash, first.getTimestamp());
                    }

                    if (first.getBlockNumber() != null) {
                        if (hasFinalized && blockHash != null && !blockHash.isBlank()) {
                            finalizedHashesByNumber
                                    .computeIfAbsent(first.getBlockNumber(), key -> new java.util.HashSet<>())
                                    .add(blockHash);
                        }
                    }
                    
                            return new SampleRow(
                            rowFormatter.format(first.getTimestamp()),
                            sources,
                            allSuccess ? "NEW" : "ERROR",
                            avgLatencyForRow,
                            first.getBlockNumber(),
                            blockHash,
                            parentHash,
                            blockTime,
                            hasSafe,
                            hasFinalized,
                                        false,
                                        false,
                            transactionCount,
                            gasPriceWei);
                })
                .collect(Collectors.toList());
        
        // Backward propagation: tag parent blocks as safe/finalized
        Map<String, String> blockHashToParentHash = new HashMap<>();
        Map<String, SampleRow> rowByHash = new HashMap<>();
        for (SampleRow row : intermediateRows) {
            if (row.getBlockHash() != null && !row.getBlockHash().isBlank()) {
                rowByHash.put(row.getBlockHash(), row);
                if (row.getParentHash() != null && !row.getParentHash().isBlank()) {
                    blockHashToParentHash.put(row.getBlockHash(), row.getParentHash());
                }
            }
        }
        // When consensus node is configured, enrich parent hash chain from all nodes' samples
        if (nodeMonitorService.hasConfiguredReferenceMode()) {
            for (NodeRegistry.NodeDefinition otherNode : nodeRegistry.getNodes()) {
                if (otherNode.key().equals(nodeKey)) {
                    continue;
                }
                for (MetricSample sample : store.getRawSamplesSince(otherNode.key(), since)) {
                    String hash = sample.getBlockHash();
                    String parent = sample.getParentHash();
                    if (hash != null && !hash.isBlank() && parent != null && !parent.isBlank()) {
                        blockHashToParentHash.putIfAbsent(hash, parent);
                    }
                }
            }
        }

        Map<Long, java.util.Set<String>> safeHashesByNumber = new HashMap<>();

        // Propagate finalized status backwards through parent chain
        java.util.Set<String> finalized = new java.util.HashSet<>(
            intermediateRows.stream()
                .filter(row -> row.isFinalized() && row.getBlockHash() != null && !row.getBlockHash().isBlank())
                .map(SampleRow::getBlockHash)
                .collect(Collectors.toSet())
        );
        for (SampleRow row : intermediateRows) {
            if (row.isFinalized() && row.getBlockHash() != null && !row.getBlockHash().isBlank()) {
                String currentHash = blockHashToParentHash.get(row.getBlockHash());
                java.util.Set<String> visited = new java.util.HashSet<>();
                while (currentHash != null && !finalized.contains(currentHash) && !visited.contains(currentHash)) {
                    visited.add(currentHash);
                    SampleRow currentRow = rowByHash.get(currentHash);
                    if (currentRow != null && currentRow.getBlockNumber() != null) {
                        finalizedHashesByNumber
                            .computeIfAbsent(currentRow.getBlockNumber(), k -> new java.util.HashSet<>())
                            .add(currentHash);
                    }
                    finalized.add(currentHash);
                    currentHash = blockHashToParentHash.get(currentHash);
                }
            }
        }

        // Propagate safe status backwards through parent chain
        java.util.Set<String> safe = new java.util.HashSet<>(
            intermediateRows.stream()
                .filter(row -> row.isSafe() && row.getBlockHash() != null && !row.getBlockHash().isBlank())
                .map(SampleRow::getBlockHash)
                .collect(Collectors.toSet())
        );
        for (SampleRow row : intermediateRows) {
            if (row.isSafe() && row.getBlockHash() != null && !row.getBlockHash().isBlank()) {
                String currentHash = blockHashToParentHash.get(row.getBlockHash());
                java.util.Set<String> visited = new java.util.HashSet<>();
                while (currentHash != null && !safe.contains(currentHash) && !finalized.contains(currentHash) && !visited.contains(currentHash)) {
                    visited.add(currentHash);
                    SampleRow currentRow = rowByHash.get(currentHash);
                    if (currentRow != null && currentRow.getBlockNumber() != null) {
                        safeHashesByNumber
                            .computeIfAbsent(currentRow.getBlockNumber(), k -> new java.util.HashSet<>())
                            .add(currentHash);
                    }
                    safe.add(currentHash);
                    currentHash = blockHashToParentHash.get(currentHash);
                }
            }
        }
        
        // Second pass: update rows with explicit tags and conflict/invalid detection
        boolean referenceSelected = nodeMonitorService.getReferenceNodeKey() != null;
        Map<Long, java.util.Set<String>> hashesByNumber = intermediateRows.stream()
                .filter(row -> row.getBlockNumber() != null && row.getBlockHash() != null && !row.getBlockHash().isBlank())
                .collect(Collectors.groupingBy(
                        SampleRow::getBlockNumber,
                        Collectors.mapping(SampleRow::getBlockHash, Collectors.toSet())));
        Map<Long, java.util.Set<String>> conflictHashesByNumber = hashesByNumber.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Map<String, Long> blockNumberByHash = new HashMap<>();
        for (SampleRow row : intermediateRows) {
            if (row.getBlockHash() != null && row.getBlockNumber() != null) {
                blockNumberByHash.put(row.getBlockHash(), row.getBlockNumber());
            }
        }

        Map<Long, String> resolvedFinalizedHashByNumber = new HashMap<>();
        Map<Long, java.util.Set<String>> resolvedInvalidHashesByNumber = new HashMap<>();
        Map<Long, Instant> resolvedAtByNumber = new HashMap<>();
        for (MetricSample sample : rawSamples) {
            if (sample.getHeadDelayMs() == null || sample.getParentHash() == null) {
                continue;
            }
            Long parentNumber = blockNumberByHash.get(sample.getParentHash());
            if (parentNumber == null) {
                continue;
            }
            java.util.Set<String> conflicts = conflictHashesByNumber.get(parentNumber);
            if (conflicts == null || conflicts.isEmpty()) {
                continue;
            }
            Instant resolvedAt = resolvedAtByNumber.get(parentNumber);
            if (resolvedAt != null && !sample.getTimestamp().isAfter(resolvedAt)) {
                continue;
            }
            resolvedAtByNumber.put(parentNumber, sample.getTimestamp());
            resolvedFinalizedHashByNumber.put(parentNumber, sample.getParentHash());
            java.util.Set<String> invalidHashes = new java.util.HashSet<>(conflicts);
            invalidHashes.remove(sample.getParentHash());
            resolvedInvalidHashesByNumber.put(parentNumber, invalidHashes);
        }

            Long oldestBlockNumber = hashesByNumber.keySet().stream().min(Long::compareTo).orElse(null);
            List<AnomalyEvent> conflictEvents = new ArrayList<>();
            for (Map.Entry<Long, java.util.Set<String>> entry : conflictHashesByNumber.entrySet()) {
                Long blockNumber = entry.getKey();
                if (oldestBlockNumber != null && blockNumber <= oldestBlockNumber + 3) {
                    // the earliest 3 blocks are unreliable if getBlock(latest) is used instead of ws
                    continue;
                }
                java.util.Set<String> hashes = entry.getValue();
                java.util.Set<String> finalizedHashes = finalizedHashesByNumber.get(blockNumber);
                if (finalizedHashes == null || finalizedHashes.isEmpty()) {
                continue;
                }
                boolean alreadyPresent = anomalies.stream()
                    .anyMatch(event -> event.getType() == AnomalyType.CONFLICT
                        && Objects.equals(event.getBlockNumber(), blockNumber));
                if (alreadyPresent) {
                continue;
                }
                Instant conflictTimestamp = hashes.stream()
                    .map(sampleTimestampByHash::get)
                    .filter(Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .orElse(now);
                boolean hasWs = hashes.stream()
                    .flatMap(hash -> samplesByHash.getOrDefault(hash, List.of()).stream())
                    .anyMatch(sample -> sample.getSource() == MetricSource.WS);
                MetricSource source = hasWs ? MetricSource.WS : MetricSource.HTTP;
                String message = "Finalized block conflict at height " + blockNumber;
                StringBuilder detailsBuilder = new StringBuilder();
                detailsBuilder.append("Block number: ").append(blockNumber).append("\n");
                detailsBuilder.append("Conflicting hashes: ").append(String.join(", ", hashes));
                String resolvedHash = resolvedFinalizedHashByNumber.get(blockNumber);
                java.util.Set<String> invalidated = resolvedInvalidHashesByNumber.get(blockNumber);
                if (resolvedHash != null) {
                    detailsBuilder.append("\nResolved by parent hash: ").append(resolvedHash);
                }
                if (invalidated != null && !invalidated.isEmpty()) {
                    detailsBuilder.append("\nInvalidated hashes: ").append(String.join(", ", invalidated));
                }
                String details = detailsBuilder.toString();
                conflictEvents.add(new AnomalyEvent(
                    -blockNumber - 1,
                    nodeKey,
                    conflictTimestamp,
                    source,
                    AnomalyType.CONFLICT,
                    message,
                    blockNumber,
                    finalizedHashes.iterator().next(),
                    null,
                        details));
            }
            if (!conflictEvents.isEmpty()) {
                anomalies.addAll(conflictEvents);
                anomalies.sort(Comparator.comparing(AnomalyEvent::getTimestamp).reversed());
                anomalyCounts.put(AnomalyType.CONFLICT,
                    anomalyCounts.getOrDefault(AnomalyType.CONFLICT, 0L) + conflictEvents.size());
            }

        ChainSelection latestChain = selectLatestChain(intermediateRows, finalizedHashesByNumber);
        Long chainTipNumber = latestChain == null ? null : latestChain.tipNumber();
        java.util.Set<String> chainHashes = latestChain == null ? java.util.Set.of() : latestChain.hashes();
        java.util.Set<Long> chainNumbers = latestChain == null ? java.util.Set.of() : latestChain.numbers();
        int chainDepth = latestChain == null ? 0 : latestChain.depth();
        Long latestFinalizedNumber = finalizedHashesByNumber.keySet().stream()
            .max(Long::compareTo)
            .orElse(null);

        List<SampleRow> sampleRows = intermediateRows.stream()
                .map(row -> {
                    if (row.getBlockNumber() == null) return row;

                    boolean isFinalized = row.isFinalized() 
                        || (row.getBlockHash() != null && finalizedHashesByNumber.getOrDefault(row.getBlockNumber(), java.util.Set.of()).contains(row.getBlockHash()));
                    boolean isSafe = (row.isSafe() 
                        || (row.getBlockHash() != null && safeHashesByNumber.getOrDefault(row.getBlockNumber(), java.util.Set.of()).contains(row.getBlockHash()))) 
                        && !isFinalized;
                    boolean isInvalid = false;
                    boolean isConflict = false;

                    if (row.getBlockNumber() != null && row.getBlockHash() != null) {
                        java.util.Set<String> conflicts = conflictHashesByNumber.get(row.getBlockNumber());
                        if (conflicts != null && !conflicts.isEmpty()) {
                            String resolvedHash = resolvedFinalizedHashByNumber.get(row.getBlockNumber());
                            if (resolvedHash != null) {
                                if (resolvedHash.equals(row.getBlockHash())) {
                                    isConflict = false;
                                } else if (resolvedInvalidHashesByNumber.getOrDefault(row.getBlockNumber(), java.util.Set.of())
                                        .contains(row.getBlockHash())) {
                                    isInvalid = true;
                                }
                            } else {
                                isConflict = true;
                            }
                        }

                        if (!isConflict && !isInvalid && !isFinalized && !isSafe
                                && referenceSelected
                                && chainTipNumber != null
                                && chainDepth >= 7
                                && latestFinalizedNumber != null
                                && chainTipNumber >= latestFinalizedNumber + 3) {
                            if (chainNumbers.contains(row.getBlockNumber()) && !chainHashes.contains(row.getBlockHash())) {
                                isInvalid = true;
                                isFinalized = false;
                                isSafe = false;
                            }
                        }
                    }

                    return new SampleRow(
                            row.getTime(),
                            row.getSources(),
                            row.getStatus(),
                            row.getLatencyMs(),
                            row.getBlockNumber(),
                            row.getBlockHash(),
                            row.getParentHash(),
                            row.getBlockTime(),
                            isSafe,
                            isFinalized,
                            isInvalid,
                            isConflict,
                            row.getTransactionCount(),
                            row.getGasPriceWei());
                })
                .sorted(Comparator.comparing(SampleRow::getTime).reversed()
                        .thenComparing(SampleRow::getBlockNumber, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAX_SAMPLES)
                .toList();

        int totalSamples = sampleRows.size();
        int totalPages = Math.max(1, Math.min(MAX_PAGES, (int) Math.ceil(totalSamples / (double) PAGE_SIZE)));

        String referenceNodeKey = nodeMonitorService.getReferenceNodeKey();
        boolean isReferenceNode = referenceNodeKey != null && referenceNodeKey.equals(nodeKey);
        List<Long> chartReferenceHeadDelays = List.of();
        List<Long> chartReferenceSafeDelays = List.of();
        List<Long> chartReferenceFinalizedDelays = List.of();
        if (referenceNodeKey != null && !isReferenceNode && !delayChartData.timestamps().isEmpty()) {
            List<MetricSample> refRawSamples;
            List<SampleAggregate> refAggregateSamples;
            if (nodeMonitorService.hasConfiguredReferenceMode()) {
                refRawSamples = nodeMonitorService.getConfiguredReferenceDelaySamplesSince(since);
                refAggregateSamples = List.of();
            } else {
                refRawSamples = store.getRawSamplesSince(referenceNodeKey, since);
                refAggregateSamples = store.getAggregatedSamplesSince(referenceNodeKey, since).stream()
                        .toList();
            }
            ChartBuilder.DelayChartData refDelayChart = ChartBuilder.buildDelayChartAligned(refRawSamples, refAggregateSamples, delayChartData.timestamps());
            chartReferenceHeadDelays = refDelayChart.headDelays();
            chartReferenceSafeDelays = refDelayChart.safeDelays();
            chartReferenceFinalizedDelays = refDelayChart.finalizedDelays();
        }

        List<AnomalyEvent> pagedAnomalies = anomalies.stream()
                .limit(MAX_ANOMALIES)
                .toList();

        boolean httpErrorOngoing = anomalies.stream()
            .anyMatch(event -> !event.isClosed()
                && event.getSource() == MetricSource.HTTP
                && ERROR_ANOMALY_TYPES.contains(event.getType()));
        boolean wsErrorOngoing = anomalies.stream()
            .anyMatch(event -> !event.isClosed()
                && event.getSource() == MetricSource.WS
                && ERROR_ANOMALY_TYPES.contains(event.getType()));

        DateTimeFormatter anomalyFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
        
        // Group consecutive anomalies of the same type
        List<AnomalyRow> anomalyRows = new ArrayList<>();
        EnumSet<MetricSource> ongoingSources = EnumSet.noneOf(MetricSource.class);
        if (!pagedAnomalies.isEmpty()) {
            AnomalyEvent firstInGroup = pagedAnomalies.get(0);
            AnomalyEvent lastInGroup = firstInGroup;
            int groupCount = 1;
            
            for (int i = 1; i < pagedAnomalies.size(); i++) {
                AnomalyEvent current = pagedAnomalies.get(i);
                
                // Check if this anomaly continues the current group
                if (current.getType() == firstInGroup.getType() && 
                    current.getSource() == firstInGroup.getSource() &&
                    !lastInGroup.isClosed()) {
                    // Same type and source and previous not closed - extend the group
                    lastInGroup = current;
                    groupCount++;
                } else {
                    // Different type or source or previous closed - finish current group and start new one
                    boolean isFirstRowForSource = ongoingSources.add(firstInGroup.getSource());
                    anomalyRows.add(createAnomalyRow(firstInGroup, lastInGroup, groupCount, anomalyFormatter, isFirstRowForSource));
                    firstInGroup = current;
                    lastInGroup = current;
                    groupCount = 1;
                }
            }
            // Add the last group
            boolean isFirstRowForSource = ongoingSources.add(firstInGroup.getSource());
            anomalyRows.add(createAnomalyRow(firstInGroup, lastInGroup, groupCount, anomalyFormatter, isFirstRowForSource));
        }

        int totalAnomalies = anomalyRows.size();
        int anomalyTotalPages = Math.max(1, Math.min(MAX_PAGES, (int) Math.ceil(totalAnomalies / (double) PAGE_SIZE)));

        MetricSample latestHttpSample = rawSamples.stream()
            .filter(sample -> sample.getSource() == MetricSource.HTTP)
            .max(Comparator.comparing(MetricSample::getTimestamp))
            .orElse(null);
        boolean httpUp = httpConfigured && latestHttpSample != null && latestHttpSample.isSuccess();

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
        boolean wsUp = wsConfigured && wsStatus.isConnected();
        Long latestBlockNumber = store.getLatestKnownBlockNumber(nodeKey);

        de.makibytes.chaincheck.monitor.HttpConnectionTracker httpTracker = nodeRegistry.getHttpTracker(nodeKey);
        HttpStatus httpStatus = httpTracker == null
            ? new HttpStatus(null, 0, null)
            : new HttpStatus(httpTracker.getConnectedSince(), httpTracker.getErrorCount(), httpTracker.getLastError());

        ReferenceComparison referenceComparison = calculateReferenceComparison(nodeKey);
        int scaleChangeMs = properties.getScaleChangeMs();
        int scaleMaxMs = properties.getScaleMaxMs();
        Instant oldestAggregate = store.getOldestAggregateTimestamp(nodeKey);
        boolean hasOlderAggregates = oldestAggregate != null && oldestAggregate.isBefore(since);

        DashboardSummary summary = new DashboardSummary(
            total,
            httpCount,
            wsCount,
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
            avgNewBlockPropagation,
            p95NewBlockPropagation,
            p99NewBlockPropagation,
            avgSafePropagation,
            p95SafePropagation,
            p99SafePropagation,
            avgFinalizedPropagation,
            p95FinalizedPropagation,
            p99FinalizedPropagation,
            staleBlockCount,
            blockLagBlocks,
            anomalyCounts.getOrDefault(AnomalyType.DELAY, 0L),
            anomalyCounts.getOrDefault(AnomalyType.REORG, 0L),
            anomalyCounts.getOrDefault(AnomalyType.BLOCK_GAP, 0L),
            anomalyCounts.getOrDefault(AnomalyType.RATE_LIMIT, 0L),
            anomalyCounts.getOrDefault(AnomalyType.TIMEOUT, 0L),
            anomalyCounts.getOrDefault(AnomalyType.WRONG_HEAD, 0L),
            anomalyCounts.getOrDefault(AnomalyType.CONFLICT, 0L));

        DashboardView view = DashboardView.create(
                range, summary, anomalies, anomalyRows, sampleRows,
                chartData, delayChartData,
                chartReferenceHeadDelays, chartReferenceSafeDelays, chartReferenceFinalizedDelays,
                hasAggregatedLatencies, hasAggregatedDelays,
                httpErrorOngoing, wsErrorOngoing,
                httpConfigured, wsConfigured,
                nodeDefinition != null && nodeDefinition.safeBlocksEnabled(),
                httpUp, wsUp, latestBlockNumber, httpStatus, wsStatus,
                totalPages, PAGE_SIZE, totalSamples,
                anomalyTotalPages, PAGE_SIZE, totalAnomalies,
                scaleChangeMs, scaleMaxMs, hasOlderAggregates,
                now, referenceComparison, isReferenceNode);
            cache.put(nodeKey, range, now, view);
        return view;
    }

    public AnomalyEvent getAnomaly(long id) {
        return store.getAnomaly(id);
    }
    private AnomalyRow createAnomalyRow(AnomalyEvent firstEvent, AnomalyEvent lastEvent, int count, DateTimeFormatter formatter, boolean isFirstRowForSource) {
        String details = resolveAnomalyDetails(lastEvent);
        if (count == 1) {
            // Single anomaly - use standard format
            return new AnomalyRow(
                    firstEvent.getId(),
                    formatter.format(firstEvent.getTimestamp()),
                    firstEvent.getType().name(),
                    firstEvent.getSource().name(),
                    firstEvent.getMessage(),
                    firstEvent.getBlockNumber(),
                    firstEvent.getBlockHash(),
                    firstEvent.getParentHash(),
                    details);
        } else {
            // Multiple anomalies - show time range and use newest ID
            // Only show "(ongoing)" for the first row per source if not closed
                String endLabel = (isFirstRowForSource && !lastEvent.isClosed())
                    ? "(ongoing)"
                    : formatter.format(firstEvent.getTimestamp());
                String timeRange = formatter.format(lastEvent.getTimestamp()) + " ↔ " + endLabel;
            String message = lastEvent.getMessage(); // Use the latest message
            return new AnomalyRow(
                    lastEvent.getId(), // Use newest ID for details link
                    timeRange,
                    firstEvent.getType().name(),
                    firstEvent.getSource().name(),
                    message,
                    lastEvent.getBlockNumber(),
                    lastEvent.getBlockHash(),
                    lastEvent.getParentHash(),
                    details,
                    count,
                    true);
        }
    }

    private String resolveAnomalyDetails(AnomalyEvent event) {
        if (event == null) {
            return null;
        }
        String details = event.getDetails();
        if (details != null && !details.isBlank()) {
            return details;
        }
        StringBuilder fallback = new StringBuilder();
        if (event.getMessage() != null && !event.getMessage().isBlank()) {
            fallback.append(event.getMessage());
        }
        if (event.getBlockNumber() != null) {
            if (!fallback.isEmpty()) {
                fallback.append("\n");
            }
            fallback.append("Block number: ").append(event.getBlockNumber());
        }
        if (event.getBlockHash() != null && !event.getBlockHash().isBlank()) {
            if (!fallback.isEmpty()) {
                fallback.append("\n");
            }
            fallback.append("Block hash: ").append(event.getBlockHash());
        }
        if (event.getParentHash() != null && !event.getParentHash().isBlank()) {
            if (!fallback.isEmpty()) {
                fallback.append("\n");
            }
            fallback.append("Parent hash: ").append(event.getParentHash());
        }
        return fallback.isEmpty() ? null : fallback.toString();
    }


    private static final EnumSet<AnomalyType> ERROR_ANOMALY_TYPES =
            EnumSet.of(AnomalyType.ERROR, AnomalyType.RATE_LIMIT, AnomalyType.TIMEOUT);

    private ReferenceComparison calculateReferenceComparison(String nodeKey) {
        // Reference comparison is optional feature - only show if multiple nodes are configured
        List<NodeRegistry.NodeDefinition> allNodes = nodeRegistry.getNodes();
        if (allNodes == null || allNodes.size() < 2) {
            return null; // Single node setup - no reference to compare against
        }

        return nodeMonitorService.isReferenceNode(nodeKey)
            .map(isRef -> new ReferenceComparison(Boolean.TRUE.equals(isRef)))
                .orElse(null);
    }

    private ChainSelection selectLatestChain(List<SampleRow> rows,
                                             Map<Long, java.util.Set<String>> finalizedHashesByNumber) {
        Map<String, ChainNode> nodes = new HashMap<>();
        java.util.Set<String> finalizedHashes = finalizedHashesByNumber.values().stream()
                .flatMap(java.util.Set::stream)
                .collect(Collectors.toSet());
        for (SampleRow row : rows) {
            if (row.getBlockHash() == null || row.getParentHash() == null || row.getBlockNumber() == null) {
                continue;
            }
            nodes.putIfAbsent(row.getBlockHash(), new ChainNode(row.getBlockHash(), row.getParentHash(), row.getBlockNumber()));
        }
        if (nodes.isEmpty()) {
            return null;
        }

        ChainSelection best = null;
        for (ChainNode node : nodes.values()) {
            ChainSelection candidate = buildChain(node, nodes, finalizedHashes);
            if (best == null || candidate.isBetterThan(best)) {
                best = candidate;
            }
        }
        return best;
    }

    private ChainSelection buildChain(ChainNode start,
                                      Map<String, ChainNode> nodes,
                                      java.util.Set<String> finalizedHashes) {
        int maxDepth = 7;
        java.util.Set<String> hashes = new java.util.LinkedHashSet<>();
        java.util.Set<Long> numbers = new java.util.LinkedHashSet<>();
        ChainNode current = start;
        int depth = 0;
        int finalizedMatches = 0;
        while (current != null && depth < maxDepth) {
            hashes.add(current.hash());
            if (current.blockNumber() != null) {
                numbers.add(current.blockNumber());
            }
            if (finalizedHashes.contains(current.hash())) {
                finalizedMatches++;
            }
            depth++;
            current = current.parentHash() == null ? null : nodes.get(current.parentHash());
        }
        return new ChainSelection(start.blockNumber(), depth, finalizedMatches, hashes, numbers);
    }

    private record ChainNode(String hash, String parentHash, Long blockNumber) {
    }

    private record ChainSelection(Long tipNumber, int depth, int finalizedMatches,
                                   java.util.Set<String> hashes, java.util.Set<Long> numbers) {

        boolean isBetterThan(ChainSelection other) {
            if (other == null) {
                return true;
            }
            if (this.depth != other.depth) {
                return this.depth > other.depth;
            }
            if (this.finalizedMatches != other.finalizedMatches) {
                return this.finalizedMatches > other.finalizedMatches;
            }
            if (this.tipNumber == null || other.tipNumber == null) {
                return this.tipNumber != null;
            }
            return this.tipNumber > other.tipNumber;
        }
    }

}

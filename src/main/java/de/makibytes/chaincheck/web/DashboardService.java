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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import de.makibytes.chaincheck.config.ChainCheckProperties;
import de.makibytes.chaincheck.model.AnomalyEvent;
import de.makibytes.chaincheck.model.AnomalyType;
import de.makibytes.chaincheck.model.AttestationConfidence;
import de.makibytes.chaincheck.model.DashboardSummary;
import de.makibytes.chaincheck.model.MetricSample;
import de.makibytes.chaincheck.model.MetricSource;
import de.makibytes.chaincheck.model.TimeRange;
import de.makibytes.chaincheck.monitor.HttpConnectionTracker;
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
        List<SampleAggregate> aggregateSamples = store.getAggregatedSamplesSince(nodeKey, since);

        List<AnomalyEvent> anomalies = store.getRawAnomaliesSince(nodeKey, since)
                .stream()
                .sorted(Comparator.comparing(AnomalyEvent::getTimestamp).reversed())
                .toList();
        // Get all aggregated anomalies - they may contain data within range even if started before
        List<AnomalyAggregate> aggregatedAnomalies = store.getAggregatedAnomaliesSince(nodeKey, since);

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
                .collect(Collectors.toCollection(ArrayList::new));
        if (latencyValues.isEmpty()) {
            for (SampleAggregate aggregate : aggregateSamples) {
                if (aggregate.getLatencyCount() > 0) {
                    latencyValues.add(Math.round((double) aggregate.getLatencySumMs() / aggregate.getLatencyCount()));
                }
            }
        }
        latencyValues = latencyValues.stream().sorted().toList();
        double p5Latency = ChartBuilder.percentile(latencyValues, 0.05);
        double p25Latency = ChartBuilder.percentile(latencyValues, 0.25);
        double p75Latency = ChartBuilder.percentile(latencyValues, 0.75);
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
                .filter(sample -> sample.getSource() == MetricSource.HTTP && sample.getBlockTimestamp() != null)
                .map(sample -> Duration.between(sample.getBlockTimestamp(), sample.getTimestamp()).toMillis())
                .filter(delay -> delay >= 0)
                .toList();

        DelayStats newBlockStats = computeDelayStats(rawSamples, aggregateSamples,
                MetricSample::getHeadDelayMs, SampleAggregate::getHeadDelaySumMs, SampleAggregate::getHeadDelayCount);
        DelayStats safeStats = computeDelayStats(rawSamples, aggregateSamples,
                MetricSample::getSafeDelayMs, SampleAggregate::getSafeDelaySumMs, SampleAggregate::getSafeDelayCount);
        DelayStats finalizedStats = computeDelayStats(rawSamples, aggregateSamples,
                MetricSample::getFinalizedDelayMs, SampleAggregate::getFinalizedDelaySumMs, SampleAggregate::getFinalizedDelayCount);

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
            anomalyCounts.merge(AnomalyType.DELAY, aggregate.getDelayCount(), Long::sum);
            anomalyCounts.merge(AnomalyType.REORG, aggregate.getReorgCount(), Long::sum);
            anomalyCounts.merge(AnomalyType.CONFLICT, aggregate.getConflictCount(), Long::sum);
            anomalyCounts.merge(AnomalyType.BLOCK_GAP, aggregate.getBlockGapCount(), Long::sum);
            anomalyCounts.merge(AnomalyType.ERROR, aggregate.getErrorCount(), Long::sum);
            anomalyCounts.merge(AnomalyType.RATE_LIMIT, aggregate.getRateLimitCount(), Long::sum);
            anomalyCounts.merge(AnomalyType.TIMEOUT, aggregate.getTimeoutCount(), Long::sum);
            anomalyCounts.merge(AnomalyType.WRONG_HEAD, aggregate.getWrongHeadCount(), Long::sum);
        });

        long maxBlockNumber = nodeRegistry.getNodes().stream()
                .map(node -> store.getLatestBlockNumber(node.key()))
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .max()
                .orElse(0L);
        Long currentLatest = store.getLatestBlockNumber(nodeKey);
        long blockLagBlocks = currentLatest == null ? 0 : Math.max(0, maxBlockNumber - currentLatest);

        ChartBuilder.ChartData chartData = ChartBuilder.buildLatencyChart(rawSamples, aggregateSamples, range, now);
        ChartBuilder.DelayChartData delayChartData = ChartBuilder.buildDelayChart(rawSamples, aggregateSamples, range, now);

        newBlockStats = overrideFromChartSeries(delayChartData.headDelays(), newBlockStats);
        safeStats = overrideFromChartSeries(delayChartData.safeDelays(), safeStats);
        finalizedStats = overrideFromChartSeries(delayChartData.finalizedDelays(), finalizedStats);

        boolean hasAggregatedLatencies = chartData.hasBounds();
        boolean hasAggregatedDelays = delayChartData.hasBounds();

        Set<String> consensusSafeHashes = Set.of();
        Set<String> consensusFinalizedHashes = Set.of();
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
            final Set<String> effectiveConsensusSafeHashes = consensusSafeHashes;
            final Set<String> effectiveConsensusFinalizedHashes = consensusFinalizedHashes;

        Map<String, AttestationConfidence> attestationConfidences = nodeMonitorService.getRecentAttestationConfidences();

        List<MetricSample> allExecutionNodeSamples = new ArrayList<>();
        for (NodeRegistry.NodeDefinition node : nodeRegistry.getNodes()) {
            allExecutionNodeSamples.addAll(store.getRawSamplesSince(node.key(), since));
        }

        boolean multiNodeConfigured = nodeRegistry.getNodes().size() > 1;

        // Global first-seen per hash across all nodes
        Map<String, Instant> globalFirstSeen = allExecutionNodeSamples.stream()
                .filter(s -> s.getBlockHash() != null && !s.getBlockHash().isBlank())
                .collect(Collectors.toMap(
                        MetricSample::getBlockHash,
                        MetricSample::getTimestamp,
                        (a, b) -> a.isBefore(b) ? a : b));

        // This node's first-seen per hash
        Map<String, Instant> thisNodeFirstSeen = rawSamples.stream()
                .filter(s -> s.getBlockHash() != null && !s.getBlockHash().isBlank())
                .collect(Collectors.toMap(
                        MetricSample::getBlockHash,
                        MetricSample::getTimestamp,
                        (a, b) -> a.isBefore(b) ? a : b));

        Map<String, List<MetricSample>> samplesByHash = allExecutionNodeSamples.stream()
                .filter(s -> s.getBlockHash() != null && !s.getBlockHash().isBlank())
                .collect(Collectors.groupingBy(MetricSample::getBlockHash));

        DateTimeFormatter rowFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

        // First pass: create intermediate rows
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
                            .filter(h -> h != null && !h.isBlank())
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
                            .filter(h -> h != null && !h.isBlank())
                            .findFirst()
                            .orElse(null);
                        Instant blockTimestamp = samples.stream()
                            .map(MetricSample::getBlockTimestamp)
                            .filter(Objects::nonNull)
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
                    
                    Double attConf = null;
                    Integer attRound = null;
                    if (blockHash != null && !blockHash.isBlank()) {
                        AttestationConfidence ac = attestationConfidences.get(blockHash);
                        if (ac != null) {
                            attConf = ac.getConfidencePercent();
                            attRound = ac.getAttestationRound();
                        }
                    }

                    // First-seen delta: how many ms behind the first node to see this block
                    Long firstSeenDelta = null;
                    if (multiNodeConfigured && blockHash != null && !blockHash.isBlank()) {
                        Instant globalFirst = globalFirstSeen.get(blockHash);
                        Instant thisFirst = thisNodeFirstSeen.get(blockHash);
                        if (globalFirst != null && thisFirst != null) {
                            firstSeenDelta = Math.max(0, Duration.between(globalFirst, thisFirst).toMillis());
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
                            gasPriceWei,
                            attConf,
                            attRound != null ? attRound.longValue() : null,
                            firstSeenDelta);
                })
                .collect(Collectors.toList());
        
        // Backward propagation: tag parent blocks as safe/finalized
        Map<String, String> blockHashToParentHash = new HashMap<>();
        Map<String, SampleRow> rowByHash = new HashMap<>();
        for (SampleRow row : intermediateRows) {
            if (row.blockHash() != null && !row.blockHash().isBlank()) {
                rowByHash.put(row.blockHash(), row);
                if (row.parentHash() != null && !row.parentHash().isBlank()) {
                    blockHashToParentHash.put(row.blockHash(), row.parentHash());
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

        // Propagate finalized status backwards through parent chain
        Set<String> finalized = intermediateRows.stream()
                .filter(row -> row.finalized() && row.blockHash() != null && !row.blockHash().isBlank())
                .map(SampleRow::blockHash)
                .collect(Collectors.toCollection(HashSet::new));
        propagateStatusBackward(intermediateRows, rowByHash, blockHashToParentHash,
                SampleRow::finalized, finalized, Set.of());

        // Propagate safe status backwards through parent chain
        Set<String> safe = intermediateRows.stream()
                .filter(row -> row.safe() && row.blockHash() != null && !row.blockHash().isBlank())
                .map(SampleRow::blockHash)
                .collect(Collectors.toCollection(HashSet::new));
        propagateStatusBackward(intermediateRows, rowByHash, blockHashToParentHash,
                SampleRow::safe, safe, finalized);

        // Build 3-attestation canonical set by walking parentHash backward from confirmed blocks
        Set<String> attest3Canonical = new HashSet<>();
        for (SampleRow row : intermediateRows) {
            if (row.blockHash() == null || row.blockHash().isBlank()) {
                continue;
            }
            if (row.attestationSlot() != null && row.attestationSlot() >= 3) {
                String hash = row.blockHash();
                if (attest3Canonical.add(hash)) {
                    String current = blockHashToParentHash.get(hash);
                    while (current != null && attest3Canonical.add(current)) {
                        if (rowByHash.get(current) == null) {
                            break;
                        }
                        current = blockHashToParentHash.get(current);
                    }
                }
            }
        }

        // Build parentHash → canonical-child maps for orphan detection.
        // A block is an orphan if its parentHash has a different canonical child.
        Map<String, String> finalizedChildByParent = buildChildByParentMap(finalized, blockHashToParentHash);
        Map<String, String> safeChildByParent = buildChildByParentMap(safe, blockHashToParentHash);
        Map<String, String> attest3ChildByParent = buildChildByParentMap(attest3Canonical, blockHashToParentHash);

        List<SampleRow> sampleRows = intermediateRows.stream()
                .map(row -> {
                    boolean isFinalized = row.finalized() || finalized.contains(row.blockHash());
                    boolean isSafe = (row.safe() || safe.contains(row.blockHash())) && !isFinalized;
                    boolean isInvalid = false;

                    if (!isFinalized && !isSafe
                            && row.blockHash() != null && !row.blockHash().isBlank()
                            && row.parentHash() != null) {
                        String ph = row.parentHash();
                        String canonicalChild = firstNonNull(
                                finalizedChildByParent.get(ph),
                                safeChildByParent.get(ph),
                                attest3ChildByParent.get(ph));
                        isInvalid = canonicalChild != null && !canonicalChild.equals(row.blockHash());
                    }

                    return row.withFinalized(isFinalized)
                            .withSafe(isSafe)
                            .withInvalid(isInvalid)
                            .withConflict(false);
                })
                .sorted(Comparator.comparing(SampleRow::time).reversed()
                        .thenComparing(SampleRow::blockNumber, Comparator.nullsLast(Comparator.reverseOrder())))
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
            refRawSamples = store.getRawSamplesSince(referenceNodeKey, since);
            refAggregateSamples = store.getAggregatedSamplesSince(referenceNodeKey, since);
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

        HttpConnectionTracker httpTracker = nodeRegistry.getHttpTracker(nodeKey);
        HttpStatus httpStatus = httpTracker == null
            ? new HttpStatus(null, 0, null)
            : new HttpStatus(httpTracker.getConnectedSince(), httpTracker.getErrorCount(), httpTracker.getLastError());

        ReferenceComparison referenceComparison = calculateReferenceComparison(nodeKey);
        int scaleChangeMs = properties.getScaleChangeMs();
        int scaleMaxMs = properties.getScaleMaxMs();
        Instant oldestAggregate = store.getOldestAggregateTimestamp(nodeKey);
        boolean hasOlderAggregates = oldestAggregate != null && oldestAggregate.isBefore(since);

        // Canonical rate and invalid block count
        long invalidBlockCount = sampleRows.stream().filter(SampleRow::invalid).count();
        long totalBlocks = sampleRows.size();
        double canonicalRatePercent = totalBlocks == 0 ? 100.0 : (totalBlocks - invalidBlockCount) * 100.0 / totalBlocks;

        // Wrong head rate
        long wh = anomalyCounts.getOrDefault(AnomalyType.WRONG_HEAD, 0L);
        double wrongHeadRatePercent = httpCount == 0 ? 0.0 : wh * 100.0 / httpCount;

        // Reorg depth and gap size from raw anomalies
        long maxReorgDepth = anomalies.stream()
                .filter(e -> e.getType() == AnomalyType.REORG && e.getDepth() != null)
                .mapToLong(AnomalyEvent::getDepth).max().orElse(0L);
        long maxGapSize = anomalies.stream()
                .filter(e -> e.getType() == AnomalyType.BLOCK_GAP && e.getDepth() != null)
                .mapToLong(AnomalyEvent::getDepth).max().orElse(0L);

        // First-seen delta stats (only positive deltas, i.e. when this node was behind)
        List<Long> firstSeenDeltas = sampleRows.stream()
                .map(SampleRow::firstSeenDeltaMs)
                .filter(d -> d != null && d > 0)
                .sorted()
                .toList();
        double avgFirstSeenDeltaMs = firstSeenDeltas.isEmpty() ? 0.0
                : firstSeenDeltas.stream().mapToLong(Long::longValue).average().orElse(0.0);
        double p95FirstSeenDeltaMs = ChartBuilder.percentile(firstSeenDeltas, 0.95);

        // Health score — detect if node has been continuously down in the last 3 minutes
        Instant threeMinAgo = now.minusSeconds(180);
        long recentHttp = rawSamples.stream()
                .filter(s -> s.getSource() == MetricSource.HTTP && s.getTimestamp().isAfter(threeMinAgo))
                .count();
        long recentHttpSuccess = rawSamples.stream()
                .filter(s -> s.getSource() == MetricSource.HTTP && s.getTimestamp().isAfter(threeMinAgo) && s.isSuccess())
                .count();
        boolean isCurrentlyDown = httpConfigured && recentHttp > 0 && recentHttpSuccess == 0;
        int healthScore = computeHealthScore(uptimePercent, p95Latency, newBlockStats.p95(), errors, total, isCurrentlyDown);

        // Last block age
        Instant lastBlockTs = store.getLatestBlockTimestamp(nodeKey);
        Long lastBlockAgeMs = lastBlockTs == null ? null : Duration.between(lastBlockTs, now).toMillis();

        DashboardSummary summary = new DashboardSummary(
            total,
            httpCount,
            wsCount,
            success,
            errors,
            avgLatency,
            maxLatency,
            p5Latency,
            p25Latency,
            p75Latency,
            p95Latency,
            p99Latency,
            httpRps,
            wsEventsPerMinute,
            uptimePercent,
            errorRatePercent,
            newBlockStats.average(),
            newBlockStats.p95(),
            newBlockStats.p99(),
            safeStats.average(),
            safeStats.p95(),
            safeStats.p99(),
            finalizedStats.average(),
            finalizedStats.p95(),
            finalizedStats.p99(),
            staleBlockCount,
            blockLagBlocks,
            anomalyCounts.getOrDefault(AnomalyType.DELAY, 0L),
            anomalyCounts.getOrDefault(AnomalyType.REORG, 0L),
            anomalyCounts.getOrDefault(AnomalyType.BLOCK_GAP, 0L),
            anomalyCounts.getOrDefault(AnomalyType.RATE_LIMIT, 0L),
            anomalyCounts.getOrDefault(AnomalyType.TIMEOUT, 0L),
            anomalyCounts.getOrDefault(AnomalyType.WRONG_HEAD, 0L),
            anomalyCounts.getOrDefault(AnomalyType.CONFLICT, 0L),
            canonicalRatePercent,
            invalidBlockCount,
            wrongHeadRatePercent,
            maxReorgDepth,
            maxGapSize,
            avgFirstSeenDeltaMs,
            p95FirstSeenDeltaMs,
            healthScore);

        DashboardView view = DashboardView.create(
                range, summary, anomalies, anomalyRows, sampleRows,
                chartData, delayChartData,
                chartReferenceHeadDelays, chartReferenceSafeDelays, chartReferenceFinalizedDelays,
                hasAggregatedLatencies, hasAggregatedDelays,
                httpErrorOngoing, wsErrorOngoing,
                httpConfigured, wsConfigured,
                nodeDefinition != null && nodeDefinition.safeBlocksEnabled(),
                nodeDefinition != null && nodeDefinition.finalizedBlocksEnabled(),
                httpUp, wsUp, latestBlockNumber, httpStatus, wsStatus,
                totalPages, PAGE_SIZE, totalSamples,
                anomalyTotalPages, PAGE_SIZE, totalAnomalies,
                scaleChangeMs, scaleMaxMs, hasOlderAggregates,
                now, referenceComparison, isReferenceNode,
                lastBlockAgeMs, multiNodeConfigured);
            cache.put(nodeKey, range, now, view);
        return view;
    }

    private static int computeHealthScore(double uptime, double p95Lat, double p95Head, long errors, long total,
                                          boolean isCurrentlyDown) {
        if (isCurrentlyDown || (total > 0 && uptime <= 0.0)) return 0;
        double uptimeScore = uptime * 0.40;
        double latScore = 25.0 * Math.max(0, 1.0 - Math.min(1.0, p95Lat / 2000.0));
        double headScore = p95Head <= 0 ? 20.0
                : 20.0 * Math.max(0, 1.0 - Math.min(1.0, p95Head / 10000.0));
        double errRate = total == 0 ? 0 : (double) errors / total;
        double anomalyScore = 15.0 * Math.max(0, 1.0 - Math.min(1.0, errRate * 10));
        return (int) Math.round(uptimeScore + latScore + headScore + anomalyScore);
    }

    private static String buildSparklinePoints(List<MetricSample> raw, Instant now) {
        int numBuckets = 60;
        long windowSecs = 60 * 60L;
        long bucketSecs = windowSecs / numBuckets;
        Instant start = now.minusSeconds(windowSecs);

        double[] bucketSum = new double[numBuckets];
        int[] bucketCount = new int[numBuckets];

        for (MetricSample s : raw) {
            if (s.getLatencyMs() < 0) continue;
            long offset = Duration.between(start, s.getTimestamp()).toSeconds();
            if (offset < 0 || offset >= windowSecs) continue;
            int idx = (int) (offset / bucketSecs);
            if (idx >= numBuckets) idx = numBuckets - 1;
            bucketSum[idx] += s.getLatencyMs();
            bucketCount[idx]++;
        }

        Double[] avgs = new Double[numBuckets];
        double maxVal = 0;
        for (int i = 0; i < numBuckets; i++) {
            if (bucketCount[i] > 0) {
                avgs[i] = bucketSum[i] / bucketCount[i];
                maxVal = Math.max(maxVal, avgs[i]);
            }
        }
        if (maxVal == 0) return "";

        int svgWidth = 60;
        int svgHeight = 28;
        int padding = 2;
        double xStep = (double) svgWidth / numBuckets;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < numBuckets; i++) {
            if (avgs[i] == null) continue;
            double x = i * xStep + xStep / 2.0;
            double y = (svgHeight - padding) - (avgs[i] / maxVal) * (svgHeight - 2 * padding);
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("%.1f,%.1f", x, y));
        }
        return sb.toString();
    }

    public AnomalyEvent getAnomaly(long id) {
        return store.getAnomaly(id);
    }

    public FleetView getFleetView(TimeRange range, Instant end) {
        Instant now = end == null ? Instant.now() : end;
        Instant since = now.minus(range.getDuration());
        String referenceNodeKey = nodeMonitorService.getReferenceNodeKey();
        List<FleetNodeSummary> summaries = new ArrayList<>();

        long maxBlock = nodeRegistry.getNodes().stream()
                .map(n -> store.getLatestBlockNumber(n.key()))
                .filter(Objects::nonNull)
                .mapToLong(Long::longValue)
                .max().orElse(0L);

        for (NodeRegistry.NodeDefinition node : nodeRegistry.getNodes()) {
            String nk = node.key();
            List<MetricSample> raw = store.getRawSamplesSince(nk, since);
            List<SampleAggregate> agg = store.getAggregatedSamplesSince(nk, since);

            long total = raw.size() + agg.stream().mapToLong(SampleAggregate::getTotalCount).sum();
            long success = raw.stream().filter(MetricSample::isSuccess).count()
                    + agg.stream().mapToLong(SampleAggregate::getSuccessCount).sum();
            long errors = total - success;
            double uptime = total == 0 ? 0 : success * 100.0 / total;

            // Calculate p95 latency using same logic as details view (includes aggregates)
            List<Long> latVals = raw.stream()
                    .filter(s -> s.getLatencyMs() >= 0)
                    .map(MetricSample::getLatencyMs)
                    .collect(Collectors.toCollection(ArrayList::new));
            if (latVals.isEmpty()) {
                for (SampleAggregate aggregate : agg) {
                    if (aggregate.getLatencyCount() > 0) {
                        latVals.add(Math.round((double) aggregate.getLatencySumMs() / aggregate.getLatencyCount()));
                    }
                }
            }
            latVals = latVals.stream().sorted().toList();
            double p95Lat = ChartBuilder.percentile(latVals, 0.95);

            // Calculate p95 head delay using same logic as details view (includes aggregates)
            List<Long> headVals = raw.stream()
                    .map(MetricSample::getHeadDelayMs)
                    .filter(d -> d != null && d >= 0)
                    .collect(Collectors.toCollection(ArrayList::new));
            if (headVals.isEmpty()) {
                for (SampleAggregate aggregate : agg) {
                    if (aggregate.getHeadDelayCount() > 0) {
                        headVals.add(Math.round((double) aggregate.getHeadDelaySumMs() / aggregate.getHeadDelayCount()));
                    }
                }
            }
            headVals = headVals.stream().sorted().toList();
            double p95Head = ChartBuilder.percentile(headVals, 0.95);

            Long latestBlock = store.getLatestBlockNumber(nk);
            long blockLag = latestBlock == null ? 0 : Math.max(0, maxBlock - latestBlock);

            Instant lastTs = store.getLatestBlockTimestamp(nk);
            Long lastBlockAgeMs = lastTs == null ? null : Duration.between(lastTs, now).toMillis();

            List<AnomalyEvent> nodeAnomalies = store.getRawAnomaliesSince(nk, since);
            long anomalyCount = nodeAnomalies.size();

            WsConnectionTracker wsTracker = nodeRegistry.getWsTracker(nk);
            long wsDisconnects = wsTracker == null ? 0 : wsTracker.getDisconnectCount();
            boolean wsUp = wsTracker != null && wsTracker.getConnectedSince() != null;

            MetricSample latestHttp = raw.stream()
                    .filter(s -> s.getSource() == MetricSource.HTTP)
                    .max(Comparator.comparing(MetricSample::getTimestamp)).orElse(null);
            boolean httpUp = node.http() != null && !node.http().isBlank()
                    && latestHttp != null && latestHttp.isSuccess();
            boolean httpConfigured = node.http() != null && !node.http().isBlank();
            boolean wsConfigured = node.ws() != null && !node.ws().isBlank();

            Instant threeMinAgo = now.minusSeconds(180);
            long recentHttp = raw.stream()
                    .filter(s -> s.getSource() == MetricSource.HTTP && s.getTimestamp().isAfter(threeMinAgo))
                    .count();
            long recentHttpSuccess = raw.stream()
                    .filter(s -> s.getSource() == MetricSource.HTTP && s.getTimestamp().isAfter(threeMinAgo) && s.isSuccess())
                    .count();
            boolean isCurrentlyDown = httpConfigured && recentHttp > 0 && recentHttpSuccess == 0;
            int healthScore = computeHealthScore(uptime, p95Lat, p95Head, errors, total, isCurrentlyDown);
            String healthLabel = FleetNodeSummary.labelForScore(healthScore);
            String sparkline = buildSparklinePoints(raw, now);

            summaries.add(new FleetNodeSummary(
                    nk, node.name(), httpConfigured, wsConfigured, httpUp, wsUp,
                    healthScore, healthLabel, uptime, p95Lat, p95Head, blockLag,
                    latestBlock, lastBlockAgeMs, anomalyCount, wsDisconnects,
                    nk.equals(referenceNodeKey), sparkline));
        }

        return new FleetView(summaries, referenceNodeKey, range);
    }

    private record DelayStats(double average, double p95, double p99) {}

    private DelayStats computeDelayStats(List<MetricSample> rawSamples,
                                         List<SampleAggregate> aggregates,
                                         Function<MetricSample, Long> rawDelayGetter,
                                         ToLongFunction<SampleAggregate> aggSumGetter,
                                         ToLongFunction<SampleAggregate> aggCountGetter) {
        List<Long> rawDelays = rawSamples.stream()
                .map(rawDelayGetter)
                .filter(delay -> delay != null && delay >= 0)
                .toList();
        long rawSum = rawDelays.stream().mapToLong(Long::longValue).sum();
        long rawCount = rawDelays.size();
        long aggSum = aggregates.stream().mapToLong(aggSumGetter).sum();
        long aggCount = aggregates.stream().mapToLong(aggCountGetter).sum();
        long totalSum = rawSum + aggSum;
        long totalCount = rawCount + aggCount;
        double average = totalCount == 0 ? 0 : (double) totalSum / totalCount;

        List<Long> values = new ArrayList<>(rawDelays);
        if (values.isEmpty()) {
            for (SampleAggregate agg : aggregates) {
                if (aggCountGetter.applyAsLong(agg) > 0) {
                    values.add(Math.round((double) aggSumGetter.applyAsLong(agg) / aggCountGetter.applyAsLong(agg)));
                }
            }
        }
        values = values.stream().sorted().toList();
        return new DelayStats(average,
                ChartBuilder.percentile(values, 0.95),
                ChartBuilder.percentile(values, 0.99));
    }

    private DelayStats overrideFromChartSeries(List<Long> delaySeries, DelayStats fallback) {
        List<Long> filtered = delaySeries.stream()
                .filter(value -> value != null && value >= 0)
                .toList();
        if (filtered.isEmpty()) {
            return fallback;
        }
        long sum = filtered.stream().mapToLong(Long::longValue).sum();
        double avg = (double) sum / filtered.size();
        List<Long> sorted = filtered.stream().sorted().toList();
        return new DelayStats(avg,
                ChartBuilder.percentile(sorted, 0.95),
                ChartBuilder.percentile(sorted, 0.99));
    }

    private void propagateStatusBackward(List<SampleRow> rows,
                                         Map<String, SampleRow> rowByHash,
                                         Map<String, String> blockHashToParentHash,
                                         Predicate<SampleRow> sourceFilter,
                                         Set<String> targetSet,
                                         Set<String> stopSet) {
        for (SampleRow row : rows) {
            if (!sourceFilter.test(row) || row.blockHash() == null || row.blockHash().isBlank()) {
                continue;
            }
            String currentHash = blockHashToParentHash.get(row.blockHash());
            Set<String> visited = new HashSet<>();
            while (currentHash != null
                    && !targetSet.contains(currentHash)
                    && !stopSet.contains(currentHash)
                    && !visited.contains(currentHash)) {
                visited.add(currentHash);
                SampleRow currentRow = rowByHash.get(currentHash);
                if (currentRow == null) {
                    break;
                }
                targetSet.add(currentHash);
                currentHash = blockHashToParentHash.get(currentHash);
            }
        }
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
        List<String> parts = new ArrayList<>();
        if (event.getMessage() != null && !event.getMessage().isBlank()) {
            parts.add(event.getMessage());
        }
        if (event.getBlockNumber() != null) {
            parts.add("Block number: " + event.getBlockNumber());
        }
        if (event.getBlockHash() != null && !event.getBlockHash().isBlank()) {
            parts.add("Block hash: " + event.getBlockHash());
        }
        if (event.getParentHash() != null && !event.getParentHash().isBlank()) {
            parts.add("Parent hash: " + event.getParentHash());
        }
        return parts.isEmpty() ? null : String.join("\n", parts);
    }

    private static Map<String, String> buildChildByParentMap(Set<String> hashes,
                                                              Map<String, String> parentMap) {
        Map<String, String> result = new HashMap<>();
        for (String hash : hashes) {
            String parent = parentMap.get(hash);
            if (parent != null) {
                result.putIfAbsent(parent, hash);
            }
        }
        return result;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) {
            if (v != null) return v;
        }
        return null;
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

}

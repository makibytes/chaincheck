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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentSkipListMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.makibytes.chaincheck.config.ChainCheckProperties;
import de.makibytes.chaincheck.model.AnomalyEvent;
import de.makibytes.chaincheck.model.MetricSample;
import de.makibytes.chaincheck.model.MetricSource;
import de.makibytes.chaincheck.model.TimeRange;

@Component
public class InMemoryMetricsStore {
    private static final Logger logger = LoggerFactory.getLogger(InMemoryMetricsStore.class);

    private final Map<String, Deque<MetricSample>> rawSamplesByNode = new ConcurrentHashMap<>();
    private final Map<String, Deque<AnomalyEvent>> rawAnomaliesByNode = new ConcurrentHashMap<>();
    private final Map<String, NavigableMap<Instant, SampleAggregate>> sampleAggregatesByNode = new ConcurrentHashMap<>();
    private final Map<String, NavigableMap<Instant, SampleAggregate>> sampleDailyAggregatesByNode = new ConcurrentHashMap<>();
    private final Map<String, NavigableMap<Instant, AnomalyAggregate>> anomalyAggregatesByNode = new ConcurrentHashMap<>();
    private final Map<String, NavigableMap<Instant, AnomalyAggregate>> anomalyDailyAggregatesByNode = new ConcurrentHashMap<>();
    private final Map<Long, AnomalyEvent> anomalyById = new ConcurrentHashMap<>();
    private final Map<String, Long> latestHttpBlockNumber = new ConcurrentHashMap<>();
    private final Map<String, Long> latestBlockNumber = new ConcurrentHashMap<>();
    private final Duration rawRetention = Duration.ofHours(2);
    private final Duration hourlyRetention = Duration.ofDays(3);
    private final Duration aggregateRetention = TimeRange.MONTH_1.getDuration();
    private final boolean persistenceEnabled;
    private final Path persistenceFile;
    private final Duration flushInterval;
    private final ObjectMapper objectMapper;
    private volatile Instant lastFlush = Instant.EPOCH;

    public InMemoryMetricsStore() {
        this(defaultProperties());
    }

    public InMemoryMetricsStore(ChainCheckProperties properties) {
        ChainCheckProperties.Persistence persistence = properties.getPersistence();
        this.persistenceEnabled = persistence.isEnabled();
        this.persistenceFile = Path.of(persistence.getFile());
        this.flushInterval = Duration.ofSeconds(Math.max(5, persistence.getFlushIntervalSeconds()));
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
        if (persistenceEnabled) {
            loadSnapshot();
        }
    }

    private static ChainCheckProperties defaultProperties() {
        ChainCheckProperties props = new ChainCheckProperties();
        props.getPersistence().setEnabled(false);
        return props;
    }

    public void addSample(String nodeKey, MetricSample sample) {
        rawSamplesByNode.computeIfAbsent(nodeKey, key -> new ConcurrentLinkedDeque<>()).add(sample);
        if (sample.getBlockNumber() != null) {
            latestBlockNumber.put(nodeKey, sample.getBlockNumber());
            if (sample.getSource() == MetricSource.HTTP) {
                latestHttpBlockNumber.put(nodeKey, sample.getBlockNumber());
            }
        }
    }

    public void addAnomaly(String nodeKey, AnomalyEvent event) {
        rawAnomaliesByNode.computeIfAbsent(nodeKey, key -> new ConcurrentLinkedDeque<>()).add(event);
        anomalyById.put(event.getId(), event);
    }

    public void closeLastAnomaly(String nodeKey, de.makibytes.chaincheck.model.MetricSource source) {
        java.util.Deque<AnomalyEvent> anomalies = rawAnomaliesByNode.get(nodeKey);
        if (anomalies == null || anomalies.isEmpty()) {
            return;
        }
        java.util.Iterator<AnomalyEvent> it = anomalies.descendingIterator();
        while (it.hasNext()) {
            AnomalyEvent event = it.next();
            if (event.getSource() == source) {
                if (!event.isClosed()) {
                    event.setClosed(true);
                }
                break;
            }
        }
    }

    public List<MetricSample> getRawSamplesSince(String nodeKey, Instant since) {
        Deque<MetricSample> samples = rawSamplesByNode.get(nodeKey);
        if (samples == null || samples.isEmpty()) {
            return Collections.emptyList();
        }
        List<MetricSample> result = new ArrayList<>();
        for (MetricSample sample : samples) {
            if (!sample.getTimestamp().isBefore(since)) {
                result.add(sample);
            }
        }
        return result;
    }

    public List<SampleAggregate> getAggregatedSamplesSince(String nodeKey, Instant since) {
        NavigableMap<Instant, SampleAggregate> hourlyAggregates = sampleAggregatesByNode.get(nodeKey);
        NavigableMap<Instant, SampleAggregate> dailyAggregates = sampleDailyAggregatesByNode.get(nodeKey);
        if ((hourlyAggregates == null || hourlyAggregates.isEmpty())
                && (dailyAggregates == null || dailyAggregates.isEmpty())) {
            return Collections.emptyList();
        }
        List<SampleAggregate> result = new ArrayList<>();
        if (hourlyAggregates != null && !hourlyAggregates.isEmpty()) {
            result.addAll(hourlyAggregates.tailMap(since, true).values());
        }
        if (dailyAggregates != null && !dailyAggregates.isEmpty()) {
            result.addAll(dailyAggregates.tailMap(since, true).values());
        }
        result.sort(java.util.Comparator.comparing(SampleAggregate::getBucketStart));
        return result;
    }

    public List<AnomalyEvent> getRawAnomaliesSince(String nodeKey, Instant since) {
        Deque<AnomalyEvent> anomalies = rawAnomaliesByNode.get(nodeKey);
        if (anomalies == null || anomalies.isEmpty()) {
            return Collections.emptyList();
        }
        List<AnomalyEvent> result = new ArrayList<>();
        for (AnomalyEvent event : anomalies) {
            if (!event.getTimestamp().isBefore(since)) {
                result.add(event);
            }
        }
        return result;
    }

    public List<AnomalyAggregate> getAggregatedAnomaliesSince(String nodeKey, Instant since) {
        NavigableMap<Instant, AnomalyAggregate> hourlyAggregates = anomalyAggregatesByNode.get(nodeKey);
        NavigableMap<Instant, AnomalyAggregate> dailyAggregates = anomalyDailyAggregatesByNode.get(nodeKey);
        if ((hourlyAggregates == null || hourlyAggregates.isEmpty())
                && (dailyAggregates == null || dailyAggregates.isEmpty())) {
            return Collections.emptyList();
        }
        List<AnomalyAggregate> result = new ArrayList<>();
        if (hourlyAggregates != null && !hourlyAggregates.isEmpty()) {
            result.addAll(hourlyAggregates.tailMap(since, true).values());
        }
        if (dailyAggregates != null && !dailyAggregates.isEmpty()) {
            result.addAll(dailyAggregates.tailMap(since, true).values());
        }
        result.sort(java.util.Comparator.comparing(AnomalyAggregate::getBucketStart));
        return result;
    }

    public AnomalyEvent getAnomaly(long id) {
        return anomalyById.get(id);
    }

    public Long getLatestBlockNumber(String nodeKey) {
        return latestHttpBlockNumber.get(nodeKey);
    }

    public Long getLatestKnownBlockNumber(String nodeKey) {
        return latestBlockNumber.get(nodeKey);
    }

    @Scheduled(fixedDelay = 5000)
    public void flushIfNeeded() {
        if (!persistenceEnabled) {
            return;
        }
        Instant now = Instant.now();
        if (lastFlush.plus(flushInterval).isAfter(now)) {
            return;
        }
        writeSnapshot();
        lastFlush = now;
    }

    @Scheduled(fixedDelay = 300000)
    public void aggregateOldData() {
        Instant now = Instant.now();
        Instant rawCutoff = now.minus(rawRetention);
        Instant hourlyCutoff = now.minus(hourlyRetention);
        Instant aggregateCutoff = now.minus(aggregateRetention);

        for (String nodeKey : rawSamplesByNode.keySet()) {
            Deque<MetricSample> samples = rawSamplesByNode.get(nodeKey);
            if (samples != null) {
                while (true) {
                    MetricSample sample = samples.peekFirst();
                    if (sample == null || !sample.getTimestamp().isBefore(rawCutoff)) {
                        break;
                    }
                    samples.pollFirst();
                    Instant bucketStart = truncateToHour(sample.getTimestamp());
                    SampleAggregate aggregate = sampleAggregatesByNode
                            .computeIfAbsent(nodeKey, key -> new ConcurrentSkipListMap<>())
                            .computeIfAbsent(bucketStart, SampleAggregate::new);
                    aggregate.addSample(sample);
                }
            }

            Deque<AnomalyEvent> anomalies = rawAnomaliesByNode.get(nodeKey);
            if (anomalies != null) {
                while (true) {
                    AnomalyEvent event = anomalies.peekFirst();
                    if (event == null || !event.getTimestamp().isBefore(rawCutoff)) {
                        break;
                    }
                    anomalies.pollFirst();
                    anomalyById.remove(event.getId());
                    Instant bucketStart = truncateToHour(event.getTimestamp());
                    AnomalyAggregate aggregate = anomalyAggregatesByNode
                            .computeIfAbsent(nodeKey, key -> new ConcurrentSkipListMap<>())
                            .computeIfAbsent(bucketStart, AnomalyAggregate::new);
                    aggregate.addEvent(event);
                }
            }

            NavigableMap<Instant, SampleAggregate> sampleAggregates = sampleAggregatesByNode.get(nodeKey);
            if (sampleAggregates != null) {
                NavigableMap<Instant, SampleAggregate> expiredHourly = sampleAggregates.headMap(hourlyCutoff, false);
                if (!expiredHourly.isEmpty()) {
                    NavigableMap<Instant, SampleAggregate> dailyAggregates = sampleDailyAggregatesByNode
                            .computeIfAbsent(nodeKey, key -> new ConcurrentSkipListMap<>());
                    for (SampleAggregate aggregate : new ArrayList<>(expiredHourly.values())) {
                        Instant dayStart = truncateToDay(aggregate.getBucketStart());
                        dailyAggregates
                                .computeIfAbsent(dayStart, SampleAggregate::new)
                                .addAggregate(aggregate);
                    }
                    expiredHourly.clear();
                }
            }
            NavigableMap<Instant, AnomalyAggregate> anomalyAggregates = anomalyAggregatesByNode.get(nodeKey);
            if (anomalyAggregates != null) {
                NavigableMap<Instant, AnomalyAggregate> expiredHourly = anomalyAggregates.headMap(hourlyCutoff, false);
                if (!expiredHourly.isEmpty()) {
                    NavigableMap<Instant, AnomalyAggregate> dailyAggregates = anomalyDailyAggregatesByNode
                            .computeIfAbsent(nodeKey, key -> new ConcurrentSkipListMap<>());
                    for (AnomalyAggregate aggregate : new ArrayList<>(expiredHourly.values())) {
                        Instant dayStart = truncateToDay(aggregate.getBucketStart());
                        dailyAggregates
                                .computeIfAbsent(dayStart, AnomalyAggregate::new)
                                .addAggregate(aggregate);
                    }
                    expiredHourly.clear();
                }
            }

            NavigableMap<Instant, SampleAggregate> dailySampleAggregates = sampleDailyAggregatesByNode.get(nodeKey);
            if (dailySampleAggregates != null) {
                dailySampleAggregates.headMap(aggregateCutoff, false).clear();
            }
            NavigableMap<Instant, AnomalyAggregate> dailyAnomalyAggregates = anomalyDailyAggregatesByNode.get(nodeKey);
            if (dailyAnomalyAggregates != null) {
                dailyAnomalyAggregates.headMap(aggregateCutoff, false).clear();
            }
        }
    }

    private void loadSnapshot() {
        try {
            if (!Files.exists(persistenceFile)) {
                return;
            }
            byte[] data = Files.readAllBytes(persistenceFile);
            Snapshot snapshot = objectMapper.readValue(data, Snapshot.class);
            if (snapshot.rawSamples() != null) {
                snapshot.rawSamples().forEach((node, samples) -> {
                    Deque<MetricSample> deque = rawSamplesByNode.computeIfAbsent(node, key -> new ConcurrentLinkedDeque<>());
                    deque.addAll(samples);
                });
            }
            if (snapshot.rawAnomalies() != null) {
                snapshot.rawAnomalies().forEach((node, anomalies) -> {
                    Deque<AnomalyEvent> deque = rawAnomaliesByNode.computeIfAbsent(node, key -> new ConcurrentLinkedDeque<>());
                    deque.addAll(anomalies);
                    anomalies.forEach(anomaly -> anomalyById.put(anomaly.getId(), anomaly));
                });
            }
            if (snapshot.latestBlockNumber() != null) {
                latestBlockNumber.putAll(snapshot.latestBlockNumber());
            }
            if (snapshot.latestHttpBlockNumber() != null) {
                latestHttpBlockNumber.putAll(snapshot.latestHttpBlockNumber());
            }
            aggregateOldData();
        } catch (java.io.IOException ex) {
            // Persistence is optional; log as warning
            logger.warn("Failed to load persistence snapshot: {}", ex.getMessage());
        }
    }

    private void writeSnapshot() {
        try {
            Snapshot snapshot = new Snapshot(
                    toMetricListCopy(rawSamplesByNode),
                    toAnomalyListCopy(rawAnomaliesByNode),
                    new ConcurrentHashMap<>(latestBlockNumber),
                    new ConcurrentHashMap<>(latestHttpBlockNumber));
            byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(snapshot);
            if (persistenceFile.getParent() != null) {
                Files.createDirectories(persistenceFile.getParent());
            }
            Files.writeString(persistenceFile, new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException ex) {
            logger.warn("Failed to write persistence snapshot: {}", ex.getMessage());
        }
    }

    private Map<String, List<MetricSample>> toMetricListCopy(Map<String, Deque<MetricSample>> source) {
        Map<String, List<MetricSample>> copy = new ConcurrentHashMap<>();
        source.forEach((node, deque) -> copy.put(node, new ArrayList<>(deque)));
        return copy;
    }

    private Map<String, List<AnomalyEvent>> toAnomalyListCopy(Map<String, Deque<AnomalyEvent>> source) {
        Map<String, List<AnomalyEvent>> copy = new ConcurrentHashMap<>();
        source.forEach((node, deque) -> copy.put(node, new ArrayList<>(deque)));
        return copy;
    }

    private record Snapshot(Map<String, List<MetricSample>> rawSamples,
                            Map<String, List<AnomalyEvent>> rawAnomalies,
                            Map<String, Long> latestBlockNumber,
                            Map<String, Long> latestHttpBlockNumber) {
    }

    private Instant truncateToHour(Instant timestamp) {
        long epochSeconds = timestamp.getEpochSecond();
        long truncatedSeconds = (epochSeconds / 3600) * 3600;
        return Instant.ofEpochSecond(truncatedSeconds);
    }

    private Instant truncateToDay(Instant timestamp) {
        long epochSeconds = timestamp.getEpochSecond();
        long truncatedSeconds = (epochSeconds / 86400) * 86400;
        return Instant.ofEpochSecond(truncatedSeconds);
    }
}

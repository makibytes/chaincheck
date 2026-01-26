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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.makibytes.chaincheck.model.AnomalyEvent;
import de.makibytes.chaincheck.model.AnomalyType;
import de.makibytes.chaincheck.model.MetricSample;
import de.makibytes.chaincheck.model.MetricSource;

@DisplayName("InMemoryMetricsStore Aggregation Tests")
class InMemoryMetricsStoreAggregationTest {

    private static final String NODE_KEY = "node-1";

    @Test
    @DisplayName("aggregateOldData: raw older than 2h becomes minutely")
    void aggregatesRawToMinutelyWithinThreeDays() {
        InMemoryMetricsStore store = new InMemoryMetricsStore();
        Instant now = Instant.now();
        Instant sampleTime = now.minus(Duration.ofDays(2)).minus(Duration.ofHours(1));

        store.addSample(NODE_KEY, sampleAt(sampleTime));
        store.aggregateOldData();

        assertTrue(store.getRawSamplesSince(NODE_KEY, now.minus(Duration.ofDays(10))).isEmpty(),
                "Raw samples should be pruned after aggregation");
        List<SampleAggregate> aggregates = store.getAggregatedSamplesSince(NODE_KEY, now.minus(Duration.ofDays(10)));
        assertEquals(1, aggregates.size(), "Expected one minutely aggregate within 3 days");
        assertEquals(1, aggregates.get(0).getTotalCount());
    }

    @Test
    @DisplayName("aggregateOldData: minutely older than 3 days becomes hourly")
    void aggregatesMinutelyToHourlyAfterThreeDays() {
        InMemoryMetricsStore store = new InMemoryMetricsStore();
        Instant now = Instant.now();
        Instant sampleTime = now.minus(Duration.ofDays(5)).minus(Duration.ofHours(1));

        store.addSample(NODE_KEY, sampleAt(sampleTime));
        store.aggregateOldData();

        assertTrue(store.getRawSamplesSince(NODE_KEY, now.minus(Duration.ofDays(10))).isEmpty());
        List<SampleAggregate> aggregates = store.getAggregatedSamplesSince(NODE_KEY, now.minus(Duration.ofDays(10)));
        assertEquals(1, aggregates.size(), "Expected hourly aggregate after 3 days");
        assertEquals(1, aggregates.get(0).getTotalCount());
    }

    @Test
    @DisplayName("aggregateOldData: data older than 1 month is deleted")
    void deletesAggregatesAfterOneMonth() {
        InMemoryMetricsStore store = new InMemoryMetricsStore();
        Instant now = Instant.now();
        Instant sampleTime = now.minus(Duration.ofDays(40));
        Instant anomalyTime = now.minus(Duration.ofDays(40)).minus(Duration.ofHours(1));

        store.addSample(NODE_KEY, sampleAt(sampleTime));
        store.addAnomaly(NODE_KEY, anomalyAt(anomalyTime, 1L));
        store.aggregateOldData();

        List<SampleAggregate> aggregates = store.getAggregatedSamplesSince(NODE_KEY, now.minus(Duration.ofDays(60)));
        assertTrue(aggregates.isEmpty(), "Aggregates older than 1 month should be deleted");

        List<AnomalyAggregate> anomalyAggregates = store.getAggregatedAnomaliesSince(NODE_KEY, now.minus(Duration.ofDays(60)));
        assertTrue(anomalyAggregates.isEmpty(), "Anomaly aggregates older than 1 month should be deleted");

        assertNull(store.getAnomaly(1L), "Old anomalies should be purged from index");
    }

    private MetricSample sampleAt(Instant timestamp) {
        return new MetricSample(
                timestamp,
                MetricSource.HTTP,
                true,
                120,
                1000L,
                timestamp.minusSeconds(12),
                "0xblock",
                "0xparent",
                null,
                null,
                null,
                null,
                2000L,
                2500L);
    }

    private AnomalyEvent anomalyAt(Instant timestamp, long id) {
        return new AnomalyEvent(
                id,
                NODE_KEY,
                timestamp,
                MetricSource.HTTP,
                AnomalyType.ERROR,
                "Error",
                1000L,
                "0xblock",
                "0xparent",
                "details");
    }
}

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

import de.makibytes.chaincheck.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("InMemoryMetricsStore Tests")
class InMemoryMetricsStoreTest {

    private InMemoryMetricsStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryMetricsStore();
    }

    @Test
    @DisplayName("addSample and getRawSamplesSince: should store and retrieve metric samples")
    void testStoreAndRetrieveSample() {
        Instant now = Instant.now();
        MetricSample sample = new MetricSample(
            now, MetricSource.HTTP, true, 100, 1000L,
            Instant.now(), "0xblock", "0xparent", null, null, null, null, null, null
        );

        store.addSample("node1", sample);
        var samples = store.getRawSamplesSince("node1", now.minusSeconds(60));

        assertFalse(samples.isEmpty(), "Should retrieve stored sample");
        assertEquals(MetricSource.HTTP, samples.get(0).getSource());
    }

    @Test
    @DisplayName("addAnomaly: should store anomaly events")
    void testStoreAnomaly() {
        Instant now = Instant.now();
        AnomalyEvent anomaly = new AnomalyEvent(
            1L, "node1", now, MetricSource.HTTP, AnomalyType.ERROR, "Connection failed",
            null, null, null, "RPC error"
        );

        store.addAnomaly("node1", anomaly);
        var anomalies = store.getRawAnomaliesSince("node1", now.minusSeconds(60));

        assertEquals(1, anomalies.size(), "Should store and retrieve anomaly");
        assertEquals(AnomalyType.ERROR, anomalies.get(0).getType());
    }

    @Test
    @DisplayName("closeLastAnomaly: should mark last anomaly as closed")
    void testCloseLastAnomaly() {
        Instant now = Instant.now();
        AnomalyEvent anomaly = new AnomalyEvent(
            1L, "node1", now, MetricSource.HTTP, AnomalyType.ERROR, "Connection failed",
            null, null, null, "RPC error"
        );

        store.addAnomaly("node1", anomaly);
        store.closeLastAnomaly("node1", MetricSource.HTTP);

        var anomalies = store.getRawAnomaliesSince("node1", now.minusSeconds(60));
        assertTrue(anomalies.get(0).isClosed(), "Last anomaly should be marked as closed");
    }

    @Test
    @DisplayName("closeLastAnomaly: should only close correct source type")
    void testCloseLastAnomaly_SourceSpecific() {
        Instant now = Instant.now();
        AnomalyEvent httpAnomaly = new AnomalyEvent(
            1L, "node1", now, MetricSource.HTTP, AnomalyType.ERROR, "HTTP error",
            null, null, null, "error"
        );
        AnomalyEvent wsAnomaly = new AnomalyEvent(
            2L, "node1", now, MetricSource.WS, AnomalyType.ERROR, "WS error",
            null, null, null, "error"
        );

        store.addAnomaly("node1", httpAnomaly);
        store.addAnomaly("node1", wsAnomaly);
        store.closeLastAnomaly("node1", MetricSource.HTTP);

        var anomalies = store.getRawAnomaliesSince("node1", now.minusSeconds(60));
        assertTrue(anomalies.stream().anyMatch(a -> a.isClosed() && a.getSource() == MetricSource.HTTP));
        assertTrue(anomalies.stream().anyMatch(a -> !a.isClosed() && a.getSource() == MetricSource.WS));
    }

    @Test
    @DisplayName("getAggregatedSamplesSince: should retrieve aggregated data")
    void testGetAggregates() {
        Instant now = Instant.now();
        var samples = store.getAggregatedSamplesSince("node1", now.minusSeconds(3600));
        
        // Should return empty list for no data
        assertNotNull(samples, "Should return non-null list");
    }

    @Test
    @DisplayName("getLatestBlockNumber: should retrieve latest block")
    void testGetLatestBlockNumber() {
        Instant now = Instant.now();
        MetricSample sample1 = new MetricSample(
            now.minusSeconds(30), MetricSource.HTTP, true, 100, 1000L,
            Instant.now(), "0xblock", "0xparent", null, null, null, null, null, null
        );
        MetricSample sample2 = new MetricSample(
            now, MetricSource.HTTP, true, 100, 1001L,
            Instant.now(), "0xblock2", "0xblock", null, null, null, null, null, null
        );

        store.addSample("node1", sample1);
        store.addSample("node1", sample2);

        Long latest = store.getLatestBlockNumber("node1");
        assertEquals(1001L, latest, "Should return latest block number");
    }

    @Test
    @DisplayName("concurrent access: should handle concurrent operations safely")
    void testConcurrentOperations() throws InterruptedException {
        Thread thread1 = new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                MetricSample sample = new MetricSample(
                    Instant.now(), MetricSource.HTTP, true, i, (long) i,
                    Instant.now(), "0xblock", "0xparent", null, null, null, null, null, null
                );
                store.addSample("node1", sample);
            }
        });

        Thread thread2 = new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                store.getRawSamplesSince("node1", Instant.now().minusSeconds(3600));
            }
        });

        thread1.start();
        thread2.start();
        thread1.join();
        thread2.join();

        var samples = store.getRawSamplesSince("node1", Instant.now().minusSeconds(3600));
        assertEquals(50, samples.size(), "Should correctly handle concurrent operations");
    }

    @Test
    @DisplayName("getAnomaly: should retrieve specific anomaly by ID")
    void testGetAnomalyById() {
        Instant now = Instant.now();
        AnomalyEvent anomaly = new AnomalyEvent(
            42L, "node1", now, MetricSource.HTTP, AnomalyType.ERROR, "Test error",
            null, null, null, "error details"
        );

        store.addAnomaly("node1", anomaly);
        AnomalyEvent retrieved = store.getAnomaly(42L);

        assertNotNull(retrieved, "Should retrieve anomaly by ID");
        assertEquals(42L, retrieved.getId());
        assertEquals("Test error", retrieved.getMessage());
    }
}

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
package de.makibytes.chaincheck.model;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AnomalyEvent Tests")
class AnomalyEventTest {

    @Test
    @DisplayName("create anomaly: should construct with all fields")
    void testCreateAnomaly() {
        Instant now = Instant.now();
        AnomalyEvent event = new AnomalyEvent(
            1L, "node1", now, MetricSource.HTTP, AnomalyType.ERROR,
            "Connection failed", 1000L, "0xhash", "0xparent", "RPC error"
        );

        assertEquals(1L, event.getId());
        assertEquals("node1", event.getNodeKey());
        assertEquals(now, event.getTimestamp());
        assertEquals(MetricSource.HTTP, event.getSource());
        assertEquals(AnomalyType.ERROR, event.getType());
        assertEquals("Connection failed", event.getMessage());
        assertEquals(1000L, event.getBlockNumber());
        assertEquals("0xhash", event.getBlockHash());
        assertEquals("0xparent", event.getParentHash());
        assertEquals("RPC error", event.getDetails());
    }

    @Test
    @DisplayName("closed state: should track anomaly closure")
    void testClosedState() {
        AnomalyEvent event = new AnomalyEvent(
            1L, "node1", Instant.now(), MetricSource.HTTP, AnomalyType.ERROR,
            "Error", null, null, null, "details"
        );

        assertFalse(event.isClosed(), "New anomaly should not be closed");

        event.setClosed(true);

        assertTrue(event.isClosed(), "Anomaly should be closed after setClosed(true)");
    }

    @Test
    @DisplayName("anomaly types: should support all anomaly types")
    void testAnomalyTypes() {
        Instant now = Instant.now();

        AnomalyEvent error = new AnomalyEvent(
            1L, "node", now, MetricSource.HTTP, AnomalyType.ERROR, "error", null, null, null, null
        );
        AnomalyEvent delay = new AnomalyEvent(
            2L, "node", now, MetricSource.HTTP, AnomalyType.DELAY, "delay", null, null, null, null
        );
        AnomalyEvent reorg = new AnomalyEvent(
            3L, "node", now, MetricSource.HTTP, AnomalyType.REORG, "reorg", null, null, null, null
        );
        AnomalyEvent gap = new AnomalyEvent(
            4L, "node", now, MetricSource.HTTP, AnomalyType.BLOCK_GAP, "gap", null, null, null, null
        );

        assertEquals(AnomalyType.ERROR, error.getType());
        assertEquals(AnomalyType.DELAY, delay.getType());
        assertEquals(AnomalyType.REORG, reorg.getType());
        assertEquals(AnomalyType.BLOCK_GAP, gap.getType());
    }

    @Test
    @DisplayName("metric sources: should support both HTTP and WS sources")
    void testMetricSources() {
        Instant now = Instant.now();

        AnomalyEvent httpEvent = new AnomalyEvent(
            1L, "node", now, MetricSource.HTTP, AnomalyType.ERROR, "http", null, null, null, null
        );
        AnomalyEvent wsEvent = new AnomalyEvent(
            2L, "node", now, MetricSource.WS, AnomalyType.ERROR, "ws", null, null, null, null
        );

        assertEquals(MetricSource.HTTP, httpEvent.getSource());
        assertEquals(MetricSource.WS, wsEvent.getSource());
    }

    @Test
    @DisplayName("timestamp ordering: anomalies can be ordered by timestamp")
    void testTimestampOrdering() {
        Instant time1 = Instant.now();
        Instant time2 = time1.plus(Duration.ofSeconds(10));

        AnomalyEvent early = new AnomalyEvent(
            1L, "node", time1, MetricSource.HTTP, AnomalyType.ERROR, "early", null, null, null, null
        );
        AnomalyEvent late = new AnomalyEvent(
            2L, "node", time2, MetricSource.HTTP, AnomalyType.ERROR, "late", null, null, null, null
        );

        assertTrue(early.getTimestamp().isBefore(late.getTimestamp()));
    }

    @Test
    @DisplayName("block chain data: should handle blockchain metadata")
    void testBlockchainData() {
        AnomalyEvent event = new AnomalyEvent(
            1L, "node", Instant.now(), MetricSource.HTTP, AnomalyType.REORG,
            "Reorg detected", 5000L, "0xabcd1234", "0xdefg5678", "Previous block hash mismatch"
        );

        assertEquals(5000L, event.getBlockNumber());
        assertEquals("0xabcd1234", event.getBlockHash());
        assertEquals("0xdefg5678", event.getParentHash());
        assertEquals("Previous block hash mismatch", event.getDetails());
    }

    @Test
    @DisplayName("null fields: should handle optional null fields")
    void testNullFields() {
        AnomalyEvent event = new AnomalyEvent(
            1L, "node", Instant.now(), MetricSource.HTTP, AnomalyType.ERROR,
            "Error", null, null, null, null
        );

        assertNull(event.getBlockNumber());
        assertNull(event.getBlockHash());
        assertNull(event.getParentHash());
        assertNull(event.getDetails());
    }
}

@DisplayName("MetricSample Tests")
class MetricSampleTest {

    @Test
    @DisplayName("create sample: should construct with all fields")
    void testCreateSample() {
        Instant now = Instant.now();
        MetricSample sample = MetricSample.builder(now, MetricSource.HTTP)
            .success(true)
            .latencyMs(500)
            .blockNumber(1000L)
            .blockTimestamp(now)
            .blockHash("0xblock")
            .parentHash("0xparent")
            .transactionCount(100)
            .gasPriceWei(50000000L)
            .build();

        assertEquals(now, sample.getTimestamp());
        assertEquals(MetricSource.HTTP, sample.getSource());
        assertTrue(sample.isSuccess());
        assertEquals(500, sample.getLatencyMs());
        assertEquals(1000L, sample.getBlockNumber());
        assertEquals("0xblock", sample.getBlockHash());
        assertEquals("0xparent", sample.getParentHash());
        assertEquals(100, sample.getTransactionCount());
        assertEquals(50000000L, sample.getGasPriceWei());
    }

    @Test
    @DisplayName("failed sample: should represent RPC errors")
    void testFailedSample() {
        MetricSample sample = MetricSample.builder(Instant.now(), MetricSource.HTTP)
            .success(false)
            .latencyMs(-1)
            .blockTimestamp(Instant.now())
            .error("Connection timeout")
            .build();

        assertFalse(sample.isSuccess());
        assertEquals(-1, sample.getLatencyMs());
        assertNull(sample.getBlockNumber());
        assertEquals("Connection timeout", sample.getError());
    }

    @Test
    @DisplayName("successful sample: should represent healthy RPC calls")
    void testSuccessfulSample() {
        MetricSample sample = MetricSample.builder(Instant.now(), MetricSource.HTTP)
            .success(true)
            .latencyMs(100)
            .blockNumber(1000L)
            .blockTimestamp(Instant.now())
            .blockHash("0x123")
            .parentHash("0x456")
            .transactionCount(50)
            .gasPriceWei(25000000L)
            .build();

        assertTrue(sample.isSuccess());
        assertEquals(100, sample.getLatencyMs());
        assertNull(sample.getError());
    }

    @Test
    @DisplayName("metric sources: should differentiate HTTP and WS samples")
    void testMetricSourceDifferentiation() {
        Instant now = Instant.now();
        MetricSample httpSample = MetricSample.builder(now, MetricSource.HTTP)
            .success(true)
            .latencyMs(100)
            .blockNumber(1000L)
            .blockTimestamp(now)
            .blockHash("0x123")
            .parentHash("0x456")
            .build();
        MetricSample wsSample = MetricSample.builder(now, MetricSource.WS)
            .success(true)
            .latencyMs(50)
            .blockNumber(1000L)
            .blockTimestamp(now)
            .blockHash("0x123")
            .parentHash("0x456")
            .build();

        assertEquals(MetricSource.HTTP, httpSample.getSource());
        assertEquals(MetricSource.WS, wsSample.getSource());
    }
}

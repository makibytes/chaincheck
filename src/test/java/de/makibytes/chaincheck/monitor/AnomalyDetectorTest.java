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
package de.makibytes.chaincheck.monitor;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.makibytes.chaincheck.model.AnomalyType;
import de.makibytes.chaincheck.model.MetricSample;
import de.makibytes.chaincheck.model.MetricSource;

@DisplayName("AnomalyDetector Tests")
class AnomalyDetectorTest {

    private AnomalyDetector detector;

    @BeforeEach
    void setUp() {
        detector = new AnomalyDetector();
    }

    @Test
    @DisplayName("detect: should detect RPC errors")
    void testDetect_RPCError() {
        MetricSample sample = MetricSample.builder(Instant.now(), MetricSource.HTTP)
            .success(false)
            .latencyMs(-1)
            .blockTimestamp(Instant.now())
            .error("Connection timeout")
            .build();

        var anomalies = detector.detect("node1", sample, 1000, null, null, null);

        assertEquals(1, anomalies.size());
        assertEquals(AnomalyType.TIMEOUT, anomalies.get(0).getType());
        assertEquals("Connection timeout", anomalies.get(0).getMessage());
    }

    @Test
    @DisplayName("detect: should truncate long error messages")
    void testDetect_TruncateLongError() {
        String longError = "a".repeat(100);
        MetricSample sample = MetricSample.builder(Instant.now(), MetricSource.HTTP)
            .success(false)
            .latencyMs(-1)
            .blockTimestamp(Instant.now())
            .error(longError)
            .build();

        var anomalies = detector.detect("node1", sample, 1000, null, null, null);

        assertEquals(1, anomalies.size());
        assertTrue(anomalies.get(0).getMessage().length() <= 53, "Message should be truncated to ~50 chars + '...'");
    }

    @Test
    @DisplayName("detect: should detect high latency")
    void testDetect_HighLatency() {
        MetricSample sample = MetricSample.builder(Instant.now(), MetricSource.HTTP)
            .success(true)
            .latencyMs(1500)
            .blockNumber(1234L)
            .blockTimestamp(Instant.now())
            .blockHash("0xblock")
            .parentHash("0xparent")
            .build();

        var anomalies = detector.detect("node1", sample, 1000, 1233L, "0xprevblock", null);

        assertTrue(anomalies.stream().anyMatch(a -> a.getType() == AnomalyType.DELAY),
                "Should detect high latency anomaly");
    }

    @Test
    @DisplayName("detect: should not detect latency below threshold")
    void testDetect_LatencyBelowThreshold() {
        MetricSample sample = MetricSample.builder(Instant.now(), MetricSource.HTTP)
            .success(true)
            .latencyMs(500)
            .blockNumber(1234L)
            .blockTimestamp(Instant.now())
            .blockHash("0xblock")
            .parentHash("0xparent")
            .build();

        var anomalies = detector.detect("node1", sample, 1000, 1233L, "0xprevblock", null);

        assertTrue(anomalies.stream().noneMatch(a -> a.getType() == AnomalyType.DELAY),
                "Should not detect latency below threshold");
    }

    @Test
    @DisplayName("detect: should detect block reorg (height decreased)")
    void testDetect_BlockReorg() {
        MetricSample sample = MetricSample.builder(Instant.now(), MetricSource.HTTP)
            .success(true)
            .latencyMs(100)
            .blockNumber(1230L)
            .blockTimestamp(Instant.now())
            .blockHash("0xblock")
            .parentHash("0xparent")
            .build();

        var anomalies = detector.detect("node1", sample, 1000, 1233L, "0xprevblock", null);

        assertTrue(anomalies.stream().anyMatch(a -> a.getType() == AnomalyType.REORG),
                "Should detect block reorg");
    }

    @Test
    @DisplayName("detect: should detect block gap on WebSocket")
    void testDetect_BlockGap_WebSocket() {
        MetricSample sample = MetricSample.builder(Instant.now(), MetricSource.WS)
            .success(true)
            .latencyMs(100)
            .blockNumber(1235L)
            .blockTimestamp(Instant.now())
            .blockHash("0xblock")
            .parentHash("0xparent")
            .build();

        var anomalies = detector.detect("node1", sample, 1000, 1233L, "0xprevblock", 1240L);

        assertTrue(anomalies.stream().anyMatch(a -> a.getType() == AnomalyType.BLOCK_GAP),
                "WebSocket should detect block gap when WS is behind HTTP");
    }

    @Test
    @DisplayName("detect: HTTP should not detect block gap")
    void testDetect_BlockGap_HTTPIgnored() {
        MetricSample sample = MetricSample.builder(Instant.now(), MetricSource.HTTP)
            .success(true)
            .latencyMs(100)
            .blockNumber(1235L)
            .blockTimestamp(Instant.now())
            .blockHash("0xblock")
            .parentHash("0xparent")
            .build();

        var anomalies = detector.detect("node1", sample, 1000, 1233L, "0xprevblock", null);

        assertTrue(anomalies.stream().noneMatch(a -> a.getType() == AnomalyType.BLOCK_GAP),
                "HTTP should not detect block gaps");
    }

    @Test
    @DisplayName("detect: should not generate anomalies for successful normal block")
    void testDetect_SuccessfulNormalBlock() {
        MetricSample sample = MetricSample.builder(Instant.now(), MetricSource.HTTP)
            .success(true)
            .latencyMs(100)
            .blockNumber(1234L)
            .blockTimestamp(Instant.now())
            .blockHash("0xblock")
            .parentHash("0xprevblock")
            .build();

        var anomalies = detector.detect("node1", sample, 1000, 1233L, "0xprevblock", null);

        assertEquals(0, anomalies.size(), "Successful normal block should not generate anomalies");
    }
}

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
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.makibytes.chaincheck.model.AnomalyEvent;
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

        var anomalies = detector.detect("node1", sample, 1000, 30000L, null, null, null);

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

        var anomalies = detector.detect("node1", sample, 1000, 30000L, null, null, null);

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

        var anomalies = detector.detect("node1", sample, 1000, 30000L, 1233L, "0xprevblock", null);

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

        var anomalies = detector.detect("node1", sample, 1000, 30000L, 1233L, "0xprevblock", null);

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

        var anomalies = detector.detect("node1", sample, 1000, 30000L, 1233L, "0xprevblock", null);

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

        var anomalies = detector.detect("node1", sample, 1000, 30000L, 1233L, "0xprevblock", 1240L);

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

        var anomalies = detector.detect("node1", sample, 1000, 30000L, 1233L, "0xprevblock", null);

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

        var anomalies = detector.detect("node1", sample, 1000, 30000L, 1233L, "0xprevblock", null);

        assertEquals(0, anomalies.size(), "Successful normal block should not generate anomalies");
    }

    @Test
    @DisplayName("detect: should detect reorg when block hash changes at same height")
    void testDetect_ReorgSameHeight_HashMismatch() {
        MetricSample sample = MetricSample.builder(Instant.now(), MetricSource.WS)
            .success(true)
            .latencyMs(100)
            .blockNumber(1233L)
            .blockTimestamp(Instant.now())
            .blockHash("0xnewblock")
            .parentHash("0xparent")
            .build();

        List<AnomalyEvent> anomalies = detector.detect("node1", sample, 1000, 30000L, 1233L, "0xprevblock", null);

        assertEquals(1, anomalies.size());
        AnomalyEvent reorg = anomalies.get(0);
        assertEquals(AnomalyType.REORG, reorg.getType());
        assertNotNull(reorg.getDepth(), "Depth should be set");
        assertEquals(1L, reorg.getDepth());
    }

    @Test
    @DisplayName("detect: should detect reorg when parent hash does not match previous block")
    void testDetect_ReorgParentHashMismatch() {
        MetricSample sample = MetricSample.builder(Instant.now(), MetricSource.WS)
            .success(true)
            .latencyMs(100)
            .blockNumber(1234L)
            .blockTimestamp(Instant.now())
            .blockHash("0xnewblock")
            .parentHash("0xdifferentparent")
            .build();

        List<AnomalyEvent> anomalies = detector.detect("node1", sample, 1000, 30000L, 1233L, "0xprevblock", null);

        assertEquals(1, anomalies.size());
        AnomalyEvent reorg = anomalies.get(0);
        assertEquals(AnomalyType.REORG, reorg.getType());
        assertEquals(1L, reorg.getDepth());
    }

    @Test
    @DisplayName("detect: should classify rate limit errors as RATE_LIMIT")
    void testDetect_RateLimitClassification() {
        MetricSample sample = MetricSample.builder(Instant.now(), MetricSource.HTTP)
            .success(false)
            .error("http 429 rate limit exceeded")
            .build();

        List<AnomalyEvent> anomalies = detector.detect("node1", sample, 1000, 30000L, null, null, null);

        assertEquals(1, anomalies.size());
        assertEquals(AnomalyType.RATE_LIMIT, anomalies.get(0).getType());
    }

    @Test
    @DisplayName("detect: reorg depth equals number of blocks dropped")
    void testDetect_ReorgDepth() {
        MetricSample sample = MetricSample.builder(Instant.now(), MetricSource.HTTP)
            .success(true)
            .latencyMs(100)
            .blockNumber(1228L)
            .blockTimestamp(Instant.now())
            .blockHash("0xblock")
            .parentHash("0xparent")
            .build();

        List<AnomalyEvent> anomalies = detector.detect("node1", sample, 1000, 30000L, 1233L, "0xprevblock", null);

        AnomalyEvent reorg = anomalies.stream()
                .filter(a -> a.getType() == AnomalyType.REORG)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected REORG anomaly"));
        assertEquals(5L, reorg.getDepth(), "Depth should equal previousBlockNumber - currentBlockNumber");
    }

    @Test
    @DisplayName("detect: block gap depth equals gap size in blocks")
    void testDetect_BlockGapDepth() {
        MetricSample sample = MetricSample.builder(Instant.now(), MetricSource.WS)
            .success(true)
            .latencyMs(100)
            .blockNumber(1230L)
            .blockTimestamp(Instant.now())
            .blockHash("0xblock")
            .parentHash("0xparent")
            .build();

        // HTTP is at 1240, WS is at 1230 — gap = 10 blocks
        List<AnomalyEvent> anomalies = detector.detect("node1", sample, 1000, 30000L, 1220L, "0xprevblock", 1240L);

        AnomalyEvent gap = anomalies.stream()
                .filter(a -> a.getType() == AnomalyType.BLOCK_GAP)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected BLOCK_GAP anomaly"));
        assertEquals(10L, gap.getDepth(), "Depth should equal HTTP block number minus WS block number");
    }

    @Test
    @DisplayName("detect: blank error string falls back to 'RPC error'")
    void testDetect_BlankErrorFallback() {
        MetricSample sample = MetricSample.builder(Instant.now(), MetricSource.HTTP)
            .success(false)
            .error("")
            .build();

        List<AnomalyEvent> anomalies = detector.detect("node1", sample, 1000, 30000L, null, null, null);

        assertEquals(1, anomalies.size());
        assertEquals("RPC error", anomalies.get(0).getMessage(),
                "Blank error should fall back to 'RPC error'");
    }

    @Test
    @DisplayName("detect: null error string falls back to 'RPC error'")
    void testDetect_NullErrorFallback() {
        MetricSample sample = MetricSample.builder(Instant.now(), MetricSource.HTTP)
            .success(false)
            .build();

        List<AnomalyEvent> anomalies = detector.detect("node1", sample, 1000, 30000L, null, null, null);

        assertEquals(1, anomalies.size());
        assertEquals("RPC error", anomalies.get(0).getMessage(),
                "Null error should fall back to 'RPC error'");
    }

    @Test
    @DisplayName("wrongHead: factory method sets correct type and message")
    void testWrongHead_FactoryMethod() {
        AnomalyEvent event = detector.wrongHead("node1", Instant.now(), MetricSource.HTTP,
                100L, "0xhash", "Expected 0xexpected");

        assertEquals(AnomalyType.WRONG_HEAD, event.getType());
        assertEquals("Head differs from reference", event.getMessage());
        assertEquals("node1", event.getNodeKey());
    }

    @Test
    @DisplayName("referenceDelay: factory method sets correct type and message")
    void testReferenceDelay_FactoryMethod() {
        AnomalyEvent event = detector.referenceDelay("node1", Instant.now(), MetricSource.WS,
                200L, "0xhash", "Behind by 3 blocks");

        assertEquals(AnomalyType.DELAY, event.getType());
        assertEquals("Node behind reference", event.getMessage());
    }

    @Test
    @DisplayName("reorgFinalized: factory method sets correct type and message")
    void testReorgFinalized_FactoryMethod() {
        AnomalyEvent event = detector.reorgFinalized("node1", Instant.now(), MetricSource.HTTP,
                300L, "0xhash", "Finalized block 0xfin replaced");

        assertEquals(AnomalyType.REORG, event.getType());
        assertEquals("Finalized block invalidated", event.getMessage());
    }

    @Test
    @DisplayName("detect: stale HTTP block triggers STALE anomaly")
    void testDetect_StaleBlock() {
        Instant oldBlockTimestamp = Instant.now().minus(60, ChronoUnit.SECONDS);
        MetricSample sample = MetricSample.builder(Instant.now(), MetricSource.HTTP)
            .success(true)
            .latencyMs(100)
            .blockNumber(1234L)
            .blockHash("0xblock")
            .parentHash("0xprevblock")
            .blockTimestamp(oldBlockTimestamp)
            .build();

        List<AnomalyEvent> anomalies = detector.detect("node1", sample, 10000, 30000L, 1233L, "0xprevblock", null);

        assertTrue(anomalies.stream().anyMatch(a -> a.getType() == AnomalyType.STALE && "Stale block".equals(a.getMessage())),
                "Old HTTP block should trigger stale block STALE anomaly");
    }

    @Test
    @DisplayName("detect: fresh HTTP block does not trigger stale block anomaly")
    void testDetect_NoStaleBlock_FreshBlock() {
        MetricSample sample = MetricSample.builder(Instant.now(), MetricSource.HTTP)
            .success(true)
            .latencyMs(100)
            .blockNumber(1234L)
            .blockHash("0xblock")
            .parentHash("0xprevblock")
            .blockTimestamp(Instant.now())
            .build();

        List<AnomalyEvent> anomalies = detector.detect("node1", sample, 10000, 30000L, 1233L, "0xprevblock", null);

        assertTrue(anomalies.stream().noneMatch(a -> "Stale block".equals(a.getMessage())),
                "Fresh block should not trigger stale block anomaly");
    }

    @Test
    @DisplayName("detect: safe checkpoint sample does not trigger stale block anomaly")
    void testDetect_NoStaleBlock_SafeSample() {
        Instant oldBlockTimestamp = Instant.now().minus(600, ChronoUnit.SECONDS);
        MetricSample sample = MetricSample.builder(Instant.now(), MetricSource.HTTP)
            .success(true)
            .latencyMs(100)
            .blockNumber(1200L)
            .blockHash("0xsafeblock")
            .parentHash("0xparent")
            .blockTimestamp(oldBlockTimestamp)
            .safeDelayMs(5000L)
            .build();

        List<AnomalyEvent> anomalies = detector.detect("node1", sample, 10000, 30000L, null, null, null);

        assertTrue(anomalies.stream().noneMatch(a -> "Stale block".equals(a.getMessage())),
                "Safe checkpoint sample should not trigger stale block anomaly");
    }

    @Test
    @DisplayName("detect: WS sample does not trigger stale block anomaly")
    void testDetect_NoStaleBlock_WsSample() {
        Instant oldBlockTimestamp = Instant.now().minus(60, ChronoUnit.SECONDS);
        MetricSample sample = MetricSample.builder(Instant.now(), MetricSource.WS)
            .success(true)
            .latencyMs(100)
            .blockNumber(1234L)
            .blockHash("0xblock")
            .parentHash("0xprevblock")
            .blockTimestamp(oldBlockTimestamp)
            .build();

        List<AnomalyEvent> anomalies = detector.detect("node1", sample, 10000, 30000L, null, null, null);

        assertTrue(anomalies.stream().noneMatch(a -> "Stale block".equals(a.getMessage())),
                "WS sample should not trigger stale block anomaly");
    }

    @Test
    @DisplayName("detect: HTTP 408 error is classified as TIMEOUT")
    void testDetect_Http408ClassifiedAsTimeout() {
        MetricSample sample = MetricSample.builder(Instant.now(), MetricSource.HTTP)
            .success(false)
            .error("HTTP 408 Request Timeout")
            .build();

        List<AnomalyEvent> anomalies = detector.detect("node1", sample, 1000, 30000L, null, null, null);

        assertEquals(1, anomalies.size());
        assertEquals(AnomalyType.TIMEOUT, anomalies.get(0).getType(),
                "HTTP 408 error should be classified as TIMEOUT, not ERROR");
    }
}

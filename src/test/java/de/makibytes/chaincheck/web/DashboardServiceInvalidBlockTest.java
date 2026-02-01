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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.makibytes.chaincheck.config.ChainCheckProperties;
import de.makibytes.chaincheck.model.MetricSample;
import de.makibytes.chaincheck.model.MetricSource;
import de.makibytes.chaincheck.model.TimeRange;
import de.makibytes.chaincheck.monitor.AnomalyDetector;
import de.makibytes.chaincheck.monitor.NodeRegistry;
import de.makibytes.chaincheck.monitor.RpcMonitorService;
import de.makibytes.chaincheck.store.InMemoryMetricsStore;

@DisplayName("DashboardService invalid block tagging")
class DashboardServiceInvalidBlockTest {

    @Test
    @DisplayName("marks blocks invalid when finalized hash differs")
    void marksInvalidWhenFinalizedHashDiffers() {
        ChainCheckProperties properties = new ChainCheckProperties();
        ChainCheckProperties.RpcNodeProperties node = new ChainCheckProperties.RpcNodeProperties();
        node.setName("Test Node");
        node.setHttp("http://localhost");
        node.setWs("ws://localhost");
        properties.getNodes().add(node);

        NodeRegistry registry = new NodeRegistry(properties);
        InMemoryMetricsStore store = new InMemoryMetricsStore(properties);
        MetricsCache cache = new MetricsCache();
        RpcMonitorService rpcMonitorService = new RpcMonitorService(registry, store, new AnomalyDetector(), properties);
        DashboardService service = new DashboardService(store, cache, registry, rpcMonitorService, properties);

        String nodeKey = registry.getDefaultNodeKey();
        assertNotNull(nodeKey, "Expected default node key");

        Instant now = Instant.now();
        Instant blockTimestamp = now.minusSeconds(30);

        MetricSample wsSampleMatching = new MetricSample(
                now.minusSeconds(10),
                MetricSource.WS,
                true,
                -1,
                100L,
                blockTimestamp,
                "0xaaa",
                "0xparent1",
                null,
                null,
                null,
                120L,
                null,
                null);

        MetricSample wsSampleOther = new MetricSample(
                now.minusSeconds(9),
                MetricSource.WS,
                true,
                -1,
                100L,
                blockTimestamp,
                "0xbbb",
                "0xparent2",
                null,
                null,
                null,
                130L,
                null,
                null);

        MetricSample httpFinalized = new MetricSample(
                now.minusSeconds(8),
                MetricSource.HTTP,
                true,
                120,
                100L,
                blockTimestamp,
                "0xaaa",
                "0xparent1",
                12,
                2_000_000_000L,
                null,
                null,
                null,
                2500L);

        store.addSample(nodeKey, wsSampleMatching);
        store.addSample(nodeKey, wsSampleOther);
        store.addSample(nodeKey, httpFinalized);

        DashboardView view = service.getDashboard(nodeKey, TimeRange.HOURS_2, now);
        List<SampleRow> rows = view.getSampleRows().stream()
                .filter(row -> row.getBlockNumber() != null && row.getBlockNumber() == 100L)
                .toList();

        assertEquals(2, rows.size(), "Expected two rows (hash-joined only for matching hash)");

        SampleRow rowMatching = rows.stream()
                .filter(row -> "0xaaa".equals(row.getBlockHash()))
                .findFirst()
                .orElseThrow();
        SampleRow rowOther = rows.stream()
                .filter(row -> "0xbbb".equals(row.getBlockHash()))
                .findFirst()
                .orElseThrow();

        assertTrue(rowMatching.getSources().contains("HTTP"));
        assertTrue(rowMatching.getSources().contains("WS"));
        assertFalse(rowMatching.isFinalized());
        assertTrue(rowMatching.isInvalid());

        assertEquals(1, rowOther.getSources().size());
        assertTrue(rowOther.getSources().contains("WS"));
        assertTrue(rowOther.isInvalid());
        assertFalse(rowOther.isFinalized());
    }
}

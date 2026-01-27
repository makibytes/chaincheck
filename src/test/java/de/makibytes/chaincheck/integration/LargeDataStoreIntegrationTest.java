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
package de.makibytes.chaincheck.integration;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import de.makibytes.chaincheck.ChainCheckApplication;
import de.makibytes.chaincheck.model.AnomalyEvent;
import de.makibytes.chaincheck.model.AnomalyType;
import de.makibytes.chaincheck.model.TimeRange;
import de.makibytes.chaincheck.monitor.NodeRegistry;
import de.makibytes.chaincheck.store.InMemoryMetricsStore;
import de.makibytes.chaincheck.store.MockMetricsStore;
import de.makibytes.chaincheck.web.DashboardService;
import de.makibytes.chaincheck.web.DashboardView;

@SpringBootTest(classes = ChainCheckApplication.class)
@ActiveProfiles("test")
@DisplayName("Integration test with large mock data store")
class LargeDataStoreIntegrationTest {

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private NodeRegistry nodeRegistry;

    @Autowired
    private InMemoryMetricsStore store;

    @Test
    @DisplayName("Anomaly counts are visible in 1m range")
    void anomaliesArePresentInMonthlyRange() {
        String nodeKey = requireNodeKey();

        DashboardView view = dashboardService.getDashboard(nodeKey, TimeRange.MONTH_1);

        assertNotNull(view, "Dashboard view should be created");
        assertNotNull(view.getSummary(), "Dashboard summary should be available");

        Map<AnomalyType, Long> counts = countByType(view.getAnomalies());
        for (AnomalyType type : AnomalyType.values()) {
            assertEquals(3L, counts.getOrDefault(type, 0L),
                "Expected exactly 3 anomalies of type " + type);
        }

        Map<String, Long> rowCounts = view.getAnomalyRows().stream()
                .collect(Collectors.groupingBy(row -> row.getType(), Collectors.summingLong(row -> row.getCount())));
        for (AnomalyType type : AnomalyType.values()) {
            assertEquals(3L, rowCounts.getOrDefault(type.name(), 0L),
                "Expected anomaly rows to total 3 for type " + type);
        }

        assertEquals(3L, view.getSummary().getDelayCount(), "Delay count should be 3");
        assertEquals(3L, view.getSummary().getReorgCount(), "Reorg count should be 3");
        assertEquals(3L, view.getSummary().getBlockGapCount(), "Block gap count should be 3");
        assertTrue(view.getSummary().getStaleBlockCount() > 0, "Stale block count should be present");
    }

    @Test
    @DisplayName("HTTP outage is visible in 2h chart")
    void httpOutageVisibleInTwoHourChart() {
        String nodeKey = requireNodeKey();

        DashboardView view = dashboardService.getDashboard(nodeKey, TimeRange.HOURS_2);

        List<Double> httpErrorRates = view.getChartErrorRates();
        assertTrue(httpErrorRates.stream().anyMatch(rate -> rate == null),
                "Expected at least one HTTP chart bucket with no samples during outage");
    }

    @Test
    @DisplayName("WebSocket outage is visible in 3d chart")
    void wsOutageVisibleInThreeDayChart() {
        String nodeKey = requireNodeKey();

        DashboardView view = dashboardService.getDashboard(nodeKey, TimeRange.DAYS_3);

        List<Double> wsErrorRates = view.getChartWsErrorRates();
        assertTrue(wsErrorRates.stream().anyMatch(rate -> rate == null),
                "Expected at least one WS chart bucket with no samples during outage");
    }

    @Test
    @DisplayName("Chart includes min/max series and error flags")
    void chartMinMaxAndErrorFlagsAreAvailable() {
        String nodeKey = requireNodeKey();

        DashboardView view = dashboardService.getDashboard(nodeKey, TimeRange.HOURS_2);

        assertEquals(view.getChartLatencies().size(), view.getChartLatencyMins().size());
        assertEquals(view.getChartLatencies().size(), view.getChartLatencyMaxs().size());
        assertEquals(view.getChartLatencies().size(), view.getChartHttpErrors().size());
        assertEquals(view.getChartLatencies().size(), view.getChartWsErrors().size());

        for (int i = 0; i < view.getChartLatencies().size(); i++) {
            Long min = view.getChartLatencyMins().get(i);
            Long max = view.getChartLatencyMaxs().get(i);
            if (min != null && max != null) {
                assertTrue(min <= max, "Expected latency min to be <= max");
            }
        }
    }

    private String requireNodeKey() {
        String nodeKey = nodeRegistry.getDefaultNodeKey();
        assertNotNull(nodeKey, "Default node key should be available in test profile");
        assertTrue(store instanceof MockMetricsStore, "Mock store should be active for test profile");
        return nodeKey;
    }

    private Map<AnomalyType, Long> countByType(List<AnomalyEvent> anomalies) {
        if (anomalies == null || anomalies.isEmpty()) {
            return new EnumMap<>(AnomalyType.class);
        }
        return anomalies.stream()
                .collect(Collectors.groupingBy(AnomalyEvent::getType,
                        () -> new EnumMap<>(AnomalyType.class),
                        Collectors.counting()));
    }
}

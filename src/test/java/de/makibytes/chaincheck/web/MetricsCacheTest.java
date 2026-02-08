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

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.makibytes.chaincheck.model.DashboardSummary;
import de.makibytes.chaincheck.model.TimeRange;

@DisplayName("MetricsCache Tests")
class MetricsCacheTest {

    private static DashboardView createView(Instant generatedAt) {
        DashboardSummary summary = new DashboardSummary(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        return DashboardView.create(
                TimeRange.HOURS_2, summary, List.of(), List.of(), List.of(),
                ChartBuilder.ChartData.empty(), ChartBuilder.DelayChartData.empty(),
                List.of(), List.of(), List.of(),
                false, false, false, false, false, false, false,
                false, false, null, null, null,
                1, 50, 0, 1, 50, 0,
                500, 30000, false,
                generatedAt, null, false);
    }

    @Test
    @DisplayName("get within TTL returns cached value")
    void getWithinTtl() {
        MetricsCache cache = new MetricsCache();
        Instant now = Instant.now();
        DashboardView view = createView(now);

        cache.put("node1", TimeRange.HOURS_2, now, view);
        DashboardView result = cache.get("node1", TimeRange.HOURS_2, now);

        assertNotNull(result);
        assertEquals(now, result.getGeneratedAt());
    }

    @Test
    @DisplayName("get after TTL returns null")
    void getAfterTtl() {
        MetricsCache cache = new MetricsCache();
        Instant past = Instant.now().minusSeconds(10);
        DashboardView view = createView(past);

        cache.put("node1", TimeRange.HOURS_2, past, view);
        DashboardView result = cache.get("node1", TimeRange.HOURS_2, past);

        assertNull(result, "Should return null for expired entry");
    }

    @Test
    @DisplayName("different keys are independent")
    void differentKeys() {
        MetricsCache cache = new MetricsCache();
        Instant now = Instant.now();

        cache.put("node1", TimeRange.HOURS_2, now, createView(now));
        cache.put("node2", TimeRange.HOURS_2, now, createView(now));

        assertNotNull(cache.get("node1", TimeRange.HOURS_2, now));
        assertNotNull(cache.get("node2", TimeRange.HOURS_2, now));
        assertNull(cache.get("node3", TimeRange.HOURS_2, now));
    }

    @Test
    @DisplayName("put exceeding MAX_ENTRIES triggers eviction")
    void evictionOnOverflow() {
        MetricsCache cache = new MetricsCache();
        Instant now = Instant.now();

        // Fill cache beyond MAX_ENTRIES
        for (int i = 0; i <= MetricsCache.MAX_ENTRIES + 5; i++) {
            Instant end = now.minusSeconds(i * 60);
            cache.put("node1", TimeRange.HOURS_2, end, createView(now));
        }

        assertTrue(cache.size() <= MetricsCache.MAX_ENTRIES + 1,
                "Cache size should be bounded after eviction");
    }
}

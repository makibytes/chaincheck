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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import de.makibytes.chaincheck.model.AnomalyEvent;
import de.makibytes.chaincheck.model.AnomalyType;
import de.makibytes.chaincheck.model.MetricSource;

@DisplayName("DashboardService")
class DashboardServiceTest {

    private final DashboardService service = new DashboardService(null, null, null, null, null, null, null);

    @Test
    @DisplayName("grouped anomaly row uses newest event for identity and ongoing state")
    void groupedAnomalyRowUsesNewestEvent() {
        Instant now = Instant.parse("2026-04-28T12:00:00Z");
        AnomalyEvent newest = anomaly(2L, now, "Current error", 101L, "0xnew");
        AnomalyEvent oldest = anomaly(1L, now.minusSeconds(60), "Old error", 100L, "0xold");
        oldest.setClosed(true);

        AnomalyRow row = ReflectionTestUtils.invokeMethod(service, "createAnomalyRow", newest, oldest, 2, true);

        assertEquals(2L, row.getId());
        assertEquals("Current error", row.getMessage());
        assertEquals(101L, row.getBlockNumber());
        assertEquals("0xnew", row.getBlockHash());
        assertTrue(row.getTime().contains("(ongoing)"));
        assertEquals(2, row.getCount());
        assertTrue(row.isGrouped());
    }

    private AnomalyEvent anomaly(long id, Instant timestamp, String message, Long blockNumber, String blockHash) {
        return new AnomalyEvent(
                id,
                "node-1",
                timestamp,
                MetricSource.HTTP,
                AnomalyType.ERROR,
                message,
                blockNumber,
                blockHash,
                "0xparent",
                message);
    }
}

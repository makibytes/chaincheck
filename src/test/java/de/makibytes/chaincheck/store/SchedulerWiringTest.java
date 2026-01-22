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

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import de.makibytes.chaincheck.ChainCheckApplication;

@DisplayName("Scheduler annotations")
class SchedulerWiringTest {

    @Test
    @DisplayName("aggregateOldData has @Scheduled")
    void aggregateOldDataIsScheduled() throws Exception {
        Method method = InMemoryMetricsStore.class.getDeclaredMethod("aggregateOldData");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        assertNotNull(scheduled, "Expected aggregateOldData to have @Scheduled annotation");
    }

    @Test
    @DisplayName("application enables scheduling")
    void applicationEnablesScheduling() {
        assertTrue(ChainCheckApplication.class.isAnnotationPresent(EnableScheduling.class),
                "Expected @EnableScheduling on the main application");
    }
}

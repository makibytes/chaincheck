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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ReferenceSelectionPolicy Tests")
class ReferenceSelectionPolicyTest {

    @Test
    @DisplayName("switches only when a node wins 15 of last 20 selections")
    void switchesOnlyAfterThreshold() {
        ReferenceSelectionPolicy policy = new ReferenceSelectionPolicy(20, 15);

        addSelections(policy, "A", 8);
        addSelections(policy, "B", 12);
        assertFalse(policy.shouldSwitchTo("B"));

        policy.reset();
        addSelections(policy, "A", 4);
        addSelections(policy, "B", 16);
        assertTrue(policy.shouldSwitchTo("B"));

        policy.reset();
        addSelections(policy, "A", 12);
        addSelections(policy, "B", 8);
        assertFalse(policy.shouldSwitchTo("A"));

        policy.reset();
        addSelections(policy, "A", 5);
        addSelections(policy, "B", 15);
        assertFalse(policy.shouldSwitchTo("A"));

        policy.reset();
        addSelections(policy, "A", 16);
        addSelections(policy, "B", 4);
        assertTrue(policy.shouldSwitchTo("A"));
    }

    private void addSelections(ReferenceSelectionPolicy policy, String key, int count) {
        for (int i = 0; i < count; i++) {
            policy.registerSelection(key);
        }
    }
}

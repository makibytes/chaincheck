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
package de.makibytes.chaincheck.chain.shared;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class NodeSwitchPolicyTest {

    @Test
    void shouldSwitchAfterThreshold() {
        NodeSwitchPolicy policy = new NodeSwitchPolicy(10, 5);
        
        // Register the same node 5 times
        for (int i = 0; i < 5; i++) {
            policy.registerSelection("node1");
        }
        
        assertTrue(policy.shouldSwitchTo("node1"));
    }

    @Test
    void shouldNotSwitchBeforeThreshold() {
        NodeSwitchPolicy policy = new NodeSwitchPolicy(10, 5);
        
        // Register the same node only 4 times
        for (int i = 0; i < 4; i++) {
            policy.registerSelection("node1");
        }
        
        assertFalse(policy.shouldSwitchTo("node1"));
    }

    @Test
    void respectsWindowSize() {
        NodeSwitchPolicy policy = new NodeSwitchPolicy(5, 3);
        
        // Register "node1" 5 times to fill window
        for (int i = 0; i < 5; i++) {
            policy.registerSelection("node1");
        }
        
        assertTrue(policy.shouldSwitchTo("node1"));
        
        // Now register "node2" 3 times - "node1" should start falling out of window
        for (int i = 0; i < 3; i++) {
            policy.registerSelection("node2");
        }
        
        // After 3 more selections, window only has 2 "node1" entries
        assertFalse(policy.shouldSwitchTo("node1"));
    }

    @Test
    void resetClearsSelections() {
        NodeSwitchPolicy policy = new NodeSwitchPolicy(10, 5);
        
        for (int i = 0; i < 5; i++) {
            policy.registerSelection("node1");
        }
        
        assertTrue(policy.shouldSwitchTo("node1"));
        
        policy.reset();
        
        assertFalse(policy.shouldSwitchTo("node1"));
    }

    @Test
    void usesDefaultValues() {
        NodeSwitchPolicy policy = new NodeSwitchPolicy();
        
        // Default is window=20, threshold=15
        for (int i = 0; i < 15; i++) {
            policy.registerSelection("node1");
        }
        
        assertTrue(policy.shouldSwitchTo("node1"));
    }
}

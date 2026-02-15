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
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.makibytes.chaincheck.store.InMemoryMetricsStore;

class ReferenceNodeVoting {

    private static final Logger logger = LoggerFactory.getLogger(ReferenceNodeVoting.class);

    private final NodeRegistry nodeRegistry;
    private final ReferenceNodeSelector referenceNodeSelector;
    private final NodeScorer nodeScorer;
    private final InMemoryMetricsStore store;

    ReferenceNodeVoting(NodeRegistry nodeRegistry,
                        ReferenceNodeSelector referenceNodeSelector,
                        NodeScorer nodeScorer,
                        InMemoryMetricsStore store) {
        this.nodeRegistry = nodeRegistry;
        this.referenceNodeSelector = referenceNodeSelector;
        this.nodeScorer = nodeScorer;
        this.store = store;
    }

    String selectReferenceNode(Map<String, NodeMonitorService.NodeState> nodeStates,
                               ReferenceBlocks referenceBlocks,
                               Instant now,
                               String currentReferenceNodeKey) {
        String oldReferenceNodeKey = currentReferenceNodeKey;
        String nextReferenceNodeKey = referenceNodeSelector.selectReferenceNode(
                nodeStates,
                referenceBlocks,
                store,
                now,
                nodeScorer,
                currentReferenceNodeKey);

        if (nextReferenceNodeKey != null) {
            String newNodeName = nodeRegistry.getNode(nextReferenceNodeKey).name();
            if (!nextReferenceNodeKey.equals(oldReferenceNodeKey)) {
                logger.info("Reference node switched to {}", newNodeName);
            } else {
                logger.debug("Reference node remains {}", newNodeName);
            }
        } else {
            logger.info("No reference node selected");
        }
        return nextReferenceNodeKey;
    }
}

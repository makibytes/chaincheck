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
package de.makibytes.chaincheck.chain.ethereum;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import de.makibytes.chaincheck.chain.shared.BlockConfidenceTracker;
import de.makibytes.chaincheck.chain.shared.Confidence;
import de.makibytes.chaincheck.chain.shared.ReferenceObservation;
import de.makibytes.chaincheck.config.ChainCheckProperties;
import de.makibytes.chaincheck.monitor.NodeRegistry;
import de.makibytes.chaincheck.monitor.RpcMonitorService;

@DisplayName("ConfiguredReferenceSource")
class ConfiguredReferenceSourceTest {

    @Test
    @DisplayName("NEW observation does not combine partial WS state with HTTP hash")
    void newObservationFallsBackWhenWsStateIsPartial() {
        ChainCheckProperties properties = propertiesWithReferenceNode();
        NodeRegistry registry = new NodeRegistry(properties);
        Map<String, RpcMonitorService.NodeState> states = new HashMap<>();

        RpcMonitorService.NodeState state = new RpcMonitorService.NodeState();
        state.lastHttpBlockNumber = 190L;
        state.lastHttpBlockHash = "0xhttp";
        state.lastWsBlockNumber = 200L;
        states.put("alpha", state);

        ConfiguredReferenceSource source = new ConfiguredReferenceSource(
                registry,
                new BlockConfidenceTracker(),
                states,
                properties,
                "alpha");

        ReferenceObservation observation = source.getObservation(Confidence.NEW);

        assertEquals(190L, observation.blockNumber());
        assertEquals("0xhttp", observation.blockHash());
    }

    private ChainCheckProperties propertiesWithReferenceNode() {
        ChainCheckProperties properties = new ChainCheckProperties();
        properties.getConsensus().setNodeKey("alpha");

        ChainCheckProperties.RpcNodeProperties alpha = new ChainCheckProperties.RpcNodeProperties();
        alpha.setName("alpha");
        alpha.setHttp("http://alpha.example");
        properties.setNodes(List.of(alpha));

        return properties;
    }
}

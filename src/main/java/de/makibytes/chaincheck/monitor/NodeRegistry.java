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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import de.makibytes.chaincheck.config.ChainCheckProperties;

@Component
public class NodeRegistry {

    private final List<NodeDefinition> nodes;
    private final Map<String, NodeDefinition> nodesByKey;
    private final Map<String, WsConnectionTracker> wsTrackers;

    public NodeRegistry(ChainCheckProperties properties) {
        List<NodeDefinition> temp = new ArrayList<>();
        Map<String, NodeDefinition> byKey = new HashMap<>();
        Map<String, WsConnectionTracker> trackers = new HashMap<>();
        Set<String> usedKeys = new HashSet<>();
        int index = 1;
        for (ChainCheckProperties.RpcNodeProperties node : properties.getNodes()) {
            String baseKey = node.getName() != null && !node.getName().isBlank()
                    ? node.getName().trim().toLowerCase().replaceAll("[^a-z0-9]+", "-")
                    : "node-" + index;
            String key = baseKey;
            int suffix = 2;
            while (usedKeys.contains(key)) {
                key = baseKey + "-" + suffix++;
            }
            usedKeys.add(key);
            NodeDefinition definition = new NodeDefinition(
                    key,
                    node.getName() == null || node.getName().isBlank() ? "Node " + index : node.getName(),
                    node.getHttp(),
                    node.getWs(),
                    node.getPollIntervalMs(),
                    node.getDelayThresholdMs());
            temp.add(definition);
            byKey.put(key, definition);
            trackers.put(key, new WsConnectionTracker());
            index++;
        }
        this.nodes = List.copyOf(temp);
        this.nodesByKey = Map.copyOf(byKey);
        this.wsTrackers = trackers;
    }

    public List<NodeDefinition> getNodes() {
        return nodes;
    }

    public NodeDefinition getNode(String key) {
        return nodesByKey.get(key);
    }

    public String getDefaultNodeKey() {
        return nodes.isEmpty() ? null : nodes.get(0).key();
    }

    public WsConnectionTracker getWsTracker(String key) {
        return wsTrackers.get(key);
    }

    public record NodeDefinition(String key,
                                 String name,
                                 String http,
                                 String ws,
                                 long pollIntervalMs,
                                 long delayThresholdMs) {
    }
}
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
    private final Map<String, HttpConnectionTracker> httpTrackers;

    public NodeRegistry(ChainCheckProperties properties) {
        List<NodeDefinition> temp = new ArrayList<>();
        Map<String, NodeDefinition> byKey = new HashMap<>();
        Map<String, WsConnectionTracker> trackers = new HashMap<>();
        Map<String, HttpConnectionTracker> httpTrackerMap = new HashMap<>();
        Set<String> usedKeys = new HashSet<>();
        int index = 1;
        ChainCheckProperties.Defaults defaults = properties.getDefaults();
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
            long connectTimeoutMs = node.getConnectTimeoutMs() > 0 ? node.getConnectTimeoutMs() : defaults.getConnectTimeoutMs();
            long readTimeoutMs = node.getReadTimeoutMs() > 0 ? node.getReadTimeoutMs() : defaults.getReadTimeoutMs();
            int maxRetries = node.getMaxRetries() >= 0 ? node.getMaxRetries() : defaults.getMaxRetries();
            long retryBackoffMs = node.getRetryBackoffMs() > 0 ? node.getRetryBackoffMs() : defaults.getRetryBackoffMs();
            Map<String, String> headers = new HashMap<>(defaults.getHeaders());
            headers.putAll(node.getHeaders());
                long nodeHighLatencyMs = node.getAnomalyDetection() != null
                    ? node.getAnomalyDetection().getHighLatencyMs()
                    : -1;
                long anomalyDelayMs = nodeHighLatencyMs > 0
                    ? nodeHighLatencyMs
                    : properties.getAnomalyDetection().getHighLatencyMs();
            boolean safeBlocksEnabled = properties.isGetSafeBlocks();
            boolean finalizedBlocksEnabled = properties.isGetFinalizedBlocks();
            String nodeName = node.getName() == null || node.getName().isBlank()
                    ? "Node " + index
                    : node.getName();
            NodeDefinition definition = new NodeDefinition(
                    key,
                    nodeName,
                    node.getHttp(),
                    node.getWs(),
                    node.getPollIntervalMs(),
                    anomalyDelayMs,
                    safeBlocksEnabled,
                    finalizedBlocksEnabled,
                    connectTimeoutMs,
                    readTimeoutMs,
                    maxRetries,
                    retryBackoffMs,
                    Map.copyOf(headers));
            temp.add(definition);
            byKey.put(key, definition);
            if (node.getWs() != null && !node.getWs().isBlank()) {
                trackers.put(key, new WsConnectionTracker());
            }
            if (node.getHttp() != null && !node.getHttp().isBlank()) {
                httpTrackerMap.put(key, new HttpConnectionTracker());
            }
            index++;
        }
        this.nodes = List.copyOf(temp);
        this.nodesByKey = Map.copyOf(byKey);
        this.wsTrackers = trackers;
        this.httpTrackers = httpTrackerMap;
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

    public HttpConnectionTracker getHttpTracker(String key) {
        return httpTrackers.get(key);
    }

    public record NodeDefinition(String key,
                                 String name,
                                 String http,
                                 String ws,
                                 long pollIntervalMs,
                                 long anomalyDelayMs,
                                 boolean safeBlocksEnabled,
                                 boolean finalizedBlocksEnabled,
                                 long connectTimeoutMs,
                                 long readTimeoutMs,
                                 int maxRetries,
                                 long retryBackoffMs,
                                 Map<String, String> headers) {
    }
}
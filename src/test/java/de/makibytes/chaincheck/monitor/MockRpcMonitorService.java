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

import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import de.makibytes.chaincheck.config.ChainCheckProperties;
import de.makibytes.chaincheck.store.InMemoryMetricsStore;

@Service
@Primary
@Profile("mock")
public class MockRpcMonitorService extends NodeMonitorService {

    private final NodeRegistry nodeRegistry;
    private final ChainCheckProperties properties;

    public MockRpcMonitorService(NodeRegistry nodeRegistry,
                                 InMemoryMetricsStore store,
                                 AnomalyDetector detector,
                                 ChainCheckProperties properties) {
        super(nodeRegistry, store, detector, properties);
        this.nodeRegistry = nodeRegistry;
        this.properties = properties;
    }

    @Override
    public String getReferenceNodeKey() {
        String configured = properties.getConsensus() != null
            ? properties.getConsensus().getNodeKey()
                : null;
        if (configured != null && nodeRegistry.getNode(configured) != null) {
            return configured;
        }
        List<NodeRegistry.NodeDefinition> nodes = nodeRegistry.getNodes();
        return nodes.size() > 1 ? nodes.get(0).key() : null;
    }

    @Override
    public Optional<Boolean> isReferenceNode(String nodeKey) {
        if (nodeKey == null || nodeKey.isBlank()) {
            return Optional.empty();
        }
        String referenceKey = getReferenceNodeKey();
        if (referenceKey == null) {
            return Optional.empty();
        }
        return Optional.of(referenceKey.equals(nodeKey));
    }
}

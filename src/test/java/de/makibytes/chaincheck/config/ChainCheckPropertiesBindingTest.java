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
package de.makibytes.chaincheck.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import de.makibytes.chaincheck.monitor.NodeRegistry;

@DisplayName("ChainCheck configuration binding")
class ChainCheckPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Test
    @DisplayName("node request and latency aliases bind from existing profile YAML")
    void bindsExistingNodeAliases() {
        contextRunner
                .withPropertyValues(
                        "rpc.mode-type=ethereum",
                        "rpc.requests.optimal-poll-interval-ms=12000",
                        "rpc.requests.sparse-poll-interval-ms=60000",
                        "rpc.nodes[0].name=Sparse Node",
                        "rpc.nodes[0].http=http://node.example",
                        "rpc.nodes[0].requests=sparse",
                        "rpc.nodes[0].anomaly-detection.high-latency=2600")
                .run(context -> {
                    ChainCheckProperties properties = context.getBean(ChainCheckProperties.class);
                    NodeRegistry registry = new NodeRegistry(properties);
                    NodeRegistry.NodeDefinition node = registry.getNode("sparse-node");

                    assertEquals(ChainCheckProperties.RequestProfile.SPARSE,
                            properties.getNodes().getFirst().getRequestProfile());
                    assertEquals(60_000L, node.pollIntervalMs());
                    assertEquals(2_600L, node.anomalyDelayMs());
                });
    }

    @EnableConfigurationProperties(ChainCheckProperties.class)
    static class Config {
    }
}

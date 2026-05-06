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
package de.makibytes.chaincheck.chain.cosmos;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.http.WebSocket;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import de.makibytes.chaincheck.chain.shared.BlockAgreementTracker;
import de.makibytes.chaincheck.chain.shared.BlockConfidenceTracker;
import de.makibytes.chaincheck.model.AnomalyEvent;
import de.makibytes.chaincheck.model.AnomalyType;
import de.makibytes.chaincheck.model.MetricSample;
import de.makibytes.chaincheck.model.MetricSource;
import de.makibytes.chaincheck.monitor.RpcMonitorService;
import de.makibytes.chaincheck.store.InMemoryMetricsStore;

class ReferenceNodeSelectorTest {

    private ReferenceNodeSelector selector;
    private InMemoryMetricsStore store;
    private BlockConfidenceTracker confidenceTracker;
    private BlockAgreementTracker agreementTracker;
    private Map<String, RpcMonitorService.NodeState> nodeStates;
    private Instant now;

    @BeforeEach
    void setUp() {
        selector = new ReferenceNodeSelector();
        store = new InMemoryMetricsStore();
        confidenceTracker = new BlockConfidenceTracker();
        agreementTracker = new BlockAgreementTracker();
        nodeStates = new HashMap<>();
        now = Instant.now();
    }

    @Test
    void shouldPreferHealthyStableNodeOverNodeWithLowUptimeAndManyAnomalies() {
        addHealthyNode("good", 20, 90, 120, 0);
        addUnhealthyNode("bad", 20, 3, 900, 80, 0, 40);

        String selected = selector.select(nodeStates, confidenceTracker, store, now, agreementTracker, null);

        assertEquals("good", selected);
    }

    @Test
    void shouldSwitchAwayImmediatelyWhenCurrentReferenceBecomesIneligible() {
        addHealthyNode("good", 20, 100, 80, 0);
        addUnhealthyNode("bad", 20, 4, 700, 60, 0, 30);

        String selected = selector.select(nodeStates, confidenceTracker, store, now, agreementTracker, "bad");

        assertEquals("good", selected);
    }

    @Test
    void shouldPenalizeLatencyAndRecentAnomaliesEvenWhenHeadDelayIsFast() {
        addNode("steady", 20, 20, 70, 220, 0, 0);
        addNode("spiky", 20, 20, 1200, 40, 0, 12);

        String selected = selector.select(nodeStates, confidenceTracker, store, now, agreementTracker, null);

        assertEquals("steady", selected);
    }

    private void addHealthyNode(String nodeKey, int totalSamples, long latencyMs, long headDelayMs, int anomalies) {
        addNode(nodeKey, totalSamples, totalSamples, latencyMs, headDelayMs, 0, anomalies);
    }

    private void addUnhealthyNode(String nodeKey, int totalSamples, int successfulSamples, long latencyMs, long headDelayMs, int wrongHeads, int anomalies) {
        addNode(nodeKey, totalSamples, successfulSamples, latencyMs, headDelayMs, wrongHeads, anomalies);
    }

    private void addNode(String nodeKey, int totalSamples, int successfulSamples, long latencyMs, long headDelayMs, int wrongHeads, int anomalies) {
        RpcMonitorService.NodeState state = new RpcMonitorService.NodeState();
        state.webSocketRef.set(Mockito.mock(WebSocket.class));
        state.lastWsEventReceivedAt = now.minusSeconds(5);
        nodeStates.put(nodeKey, state);

        for (int i = 0; i < totalSamples; i++) {
            boolean success = i < successfulSamples;
            Instant timestamp = now.minusSeconds(i * 120L);
            MetricSample sample = MetricSample.builder(timestamp, MetricSource.HTTP)
                    .success(success)
                    .latencyMs(success ? latencyMs : -1)
                    .headDelayMs(headDelayMs)
                    .build();
            store.addSample(nodeKey, sample);
        }

        for (int i = 0; i < wrongHeads; i++) {
            store.addAnomaly(nodeKey, anomaly(nodeKey, AnomalyType.WRONG_HEAD, i));
        }
        for (int i = 0; i < anomalies; i++) {
            store.addAnomaly(nodeKey, anomaly(nodeKey, AnomalyType.ERROR, 1_000 + i));
        }
    }

    private AnomalyEvent anomaly(String nodeKey, AnomalyType type, long id) {
        return new AnomalyEvent(
                id,
                nodeKey,
                now.minusSeconds(id % 300),
                MetricSource.HTTP,
                type,
                type.name(),
                null,
                null,
                null,
                null);
    }
}

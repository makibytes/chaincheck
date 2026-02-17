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

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import de.makibytes.chaincheck.chain.cosmos.BlockVotingService;
import de.makibytes.chaincheck.chain.shared.BlockConfidenceTracker;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import de.makibytes.chaincheck.config.ChainCheckProperties;
import de.makibytes.chaincheck.model.AnomalyEvent;
import de.makibytes.chaincheck.model.AnomalyType;
import de.makibytes.chaincheck.model.MetricSample;
import de.makibytes.chaincheck.model.MetricSource;
import de.makibytes.chaincheck.monitor.NodeRegistry.NodeDefinition;
import de.makibytes.chaincheck.store.InMemoryMetricsStore;

@DisplayName("RpcMonitorService Tests")
class RpcMonitorServiceTest {

    private final RpcMonitorService service = new RpcMonitorService(null, null, null);

    @Test
    @DisplayName("areErrorsSame: identical errors should be same")
    void testAreErrorsSame_IdenticalErrors() {
        String error = "Connection timeout";
        boolean result = (boolean) ReflectionTestUtils.invokeMethod(service, "areErrorsSame", error, error);
        assertTrue(result);
    }

    @Test
    @DisplayName("areErrorsSame: null errors should be considered same (no error state)")
    void testAreErrorsSame_NullErrors() {
        boolean result = (boolean) ReflectionTestUtils.invokeMethod(service, "areErrorsSame", null, null);
        assertTrue(result);
    }

    @Test
    @DisplayName("areErrorsSame: one null, one non-null should not be same")
    void testAreErrorsSame_OneNull() {
        boolean result = (boolean) ReflectionTestUtils.invokeMethod(service, "areErrorsSame", "error", null);
        assertFalse(result);
    }

    @Test
    @DisplayName("areErrorsSame: different WebSocket errors with changing counter should be same")
    void testAreErrorsSame_WebSocketCounterDifference() {
        String error1 = "WebSocket not receiving newHeads events (last event 10s ago)";
        String error2 = "WebSocket not receiving newHeads events (last event 144s ago)";
        boolean result = (boolean) ReflectionTestUtils.invokeMethod(service, "areErrorsSame", error1, error2);
        assertTrue(result, "WebSocket timeout errors with different counters should be treated as same");
    }

    @Test
    @DisplayName("areErrorsSame: WebSocket errors without counter should be same")
    void testAreErrorsSame_WebSocketNoCounter() {
        String error1 = "WebSocket not receiving newHeads events (no events since connection)";
        String error2 = "WebSocket not receiving newHeads events (last event 50s ago)";
        boolean result = (boolean) ReflectionTestUtils.invokeMethod(service, "areErrorsSame", error1, error2);
        assertTrue(result, "Both WebSocket timeout variants should be same");
    }

    @Test
    @DisplayName("areErrorsSame: rate limit errors with different trace_ids should be same")
    void testAreErrorsSame_RateLimitDifferentTraceId() {
        String error1 = "{\"code\":-32090,\"message\":\"Too many requests, reason: call rate limit exhausted, retry in 10s\",\"data\":{\"trace_id\":\"309d24716c1342b93405302fcd4ac6e4\"}}";
        String error2 = "{\"code\":-32090,\"message\":\"Too many requests, reason: call rate limit exhausted, retry in 10s\",\"data\":{\"trace_id\":\"abcdef1234567890abcdef1234567890\"}}";
        boolean result = (boolean) ReflectionTestUtils.invokeMethod(service, "areErrorsSame", error1, error2);
        assertTrue(result, "Rate limit errors with different trace_ids should be same");
    }

    @Test
    @DisplayName("areErrorsSame: completely different errors should not be same")
    void testAreErrorsSame_CompletelyDifferent() {
        String error1 = "Connection timeout";
        String error2 = "Invalid JSON response";
        boolean result = (boolean) ReflectionTestUtils.invokeMethod(service, "areErrorsSame", error1, error2);
        assertFalse(result);
    }

    @Test
    @DisplayName("areErrorsSame: different error prefix should not be same")
    void testAreErrorsSame_DifferentPrefix() {
        String error1 = "WebSocket connection failed";
        String error2 = "WebSocket not receiving newHeads events (last event 10s ago)";
        boolean result = (boolean) ReflectionTestUtils.invokeMethod(service, "areErrorsSame", error1, error2);
        assertFalse(result, "Different WebSocket error types should not be same");
    }

    @Test
    @DisplayName("reference uses majority of available nodes")
    void referenceFollowsMajorityAcrossNodes() {
        NodeRegistry registry = registryWithNodes("alpha", "beta", "gamma");
        RpcMonitorService svc = new RpcMonitorService(registry, null, null);
        Map<String, RpcMonitorService.NodeState> states = nodeStates(svc);

        for (NodeDefinition def : registry.getNodes()) {
            RpcMonitorService.NodeState state = new RpcMonitorService.NodeState();
            if (def.key().contains("gamma")) {
                state.lastHttpBlockNumber = 99L;
                state.lastHttpBlockHash = "0xdeadbeef";
            } else {
                state.lastHttpBlockNumber = 100L;
                state.lastHttpBlockHash = "0xaaa";
            }
            states.put(def.key(), state);
        }

        ReflectionTestUtils.invokeMethod(svc, "refreshReferenceFromNodes");
        ReferenceSnapshot reference = reference(svc);

        assertEquals(100L, reference.headNumber());
        assertEquals("0xaaa", reference.headHash());
    }

    @Test
    @DisplayName("configured reference node disables automatic reference node selection")
    void configuredReferenceNodeDisablesAutoSelection() {
        ChainCheckProperties properties = new ChainCheckProperties();
        properties.setMode(ChainCheckProperties.Mode.ETHEREUM);
        properties.getConsensus().setNodeKey("alpha");

        List<ChainCheckProperties.RpcNodeProperties> nodeProps = new ArrayList<>();
        ChainCheckProperties.RpcNodeProperties alpha = new ChainCheckProperties.RpcNodeProperties();
        alpha.setName("alpha");
        alpha.setHttp("http://alpha.example");
        nodeProps.add(alpha);
        ChainCheckProperties.RpcNodeProperties beta = new ChainCheckProperties.RpcNodeProperties();
        beta.setName("beta");
        beta.setHttp("http://beta.example");
        nodeProps.add(beta);
        properties.setNodes(nodeProps);

        NodeRegistry registry = new NodeRegistry(properties);
        RpcMonitorService svc = new RpcMonitorService(registry, null, null, properties);
        Map<String, RpcMonitorService.NodeState> states = nodeStates(svc);

        RpcMonitorService.NodeState alphaState = new RpcMonitorService.NodeState();
        alphaState.lastHttpBlockNumber = 100L;
        alphaState.lastHttpBlockHash = "0xaaa";
        states.put("alpha", alphaState);

        RpcMonitorService.NodeState betaState = new RpcMonitorService.NodeState();
        betaState.lastHttpBlockNumber = 120L;
        betaState.lastHttpBlockHash = "0xbbb";
        states.put("beta", betaState);

        ReflectionTestUtils.invokeMethod(svc, "refreshReferenceFromNodes");

        assertEquals("alpha", svc.getReferenceNodeKey());
    }

    @Test
    @DisplayName("configured reference node is excluded from execution HTTP polling")
    void configuredReferenceNodeExcludedFromExecutionPolling() {
        ChainCheckProperties properties = new ChainCheckProperties();
        properties.setMode(ChainCheckProperties.Mode.ETHEREUM);
        properties.getConsensus().setNodeKey("alpha");

        List<ChainCheckProperties.RpcNodeProperties> nodeProps = new ArrayList<>();
        ChainCheckProperties.RpcNodeProperties alpha = new ChainCheckProperties.RpcNodeProperties();
        alpha.setName("alpha");
        alpha.setHttp("http://alpha.example");
        nodeProps.add(alpha);

        ChainCheckProperties.RpcNodeProperties beta = new ChainCheckProperties.RpcNodeProperties();
        beta.setName("beta");
        beta.setHttp("http://beta.example");
        nodeProps.add(beta);
        properties.setNodes(nodeProps);

        NodeRegistry registry = new NodeRegistry(properties);
        RpcMonitorService svc = new RpcMonitorService(registry, null, null, properties);

        boolean alphaEligible = (boolean) ReflectionTestUtils.invokeMethod(svc, "shouldPollExecutionHttp", "alpha");
        boolean betaEligible = (boolean) ReflectionTestUtils.invokeMethod(svc, "shouldPollExecutionHttp", "beta");

        assertFalse(alphaEligible);
        assertTrue(betaEligible);
    }

    @Test
    @DisplayName("reference prefers healthy WebSocket nodes when others stall")
    void referencePrefersHealthyWebSocketsWhenOthersStall() {
        NodeRegistry registry = registryWithNodes("a", "b", "c", "d", "e");
        RpcMonitorService svc = new RpcMonitorService(registry, null, null);
        Map<String, RpcMonitorService.NodeState> states = nodeStates(svc);
        Instant now = Instant.now();

        for (NodeDefinition def : registry.getNodes()) {
            RpcMonitorService.NodeState state = new RpcMonitorService.NodeState();
            if (def.key().equals("a") || def.key().equals("b")) {
                state.lastWsBlockNumber = 120L;
                state.lastWsBlockHash = "0xhead";
                state.lastWsEventReceivedAt = now;
                state.webSocketRef.set(dummyWebSocket());
            } else {
                state.lastHttpBlockNumber = 110L;
                state.lastHttpBlockHash = "0xstale";
            }
            states.put(def.key(), state);
        }

        ReflectionTestUtils.invokeMethod(svc, "refreshReferenceFromNodes");
        ReferenceSnapshot reference = reference(svc);

        assertEquals(120L, reference.headNumber());
        assertEquals("0xhead", reference.headHash());
    }

    @Test
    @DisplayName("reference lag triggers delay anomaly, not wrong head")
    void referenceLagCreatesDelayAnomaly() {
        ChainCheckProperties properties = new ChainCheckProperties();
        properties.getAnomalyDetection().setLongDelayBlockCount(15);
        NodeRegistry registry = registryWithNodes("mock-node", "mock-node-two");
        InMemoryMetricsStore store = new InMemoryMetricsStore(properties);
        RpcMonitorService svc = new RpcMonitorService(registry, store, new AnomalyDetector(), properties);
        ReflectionTestUtils.setField(svc, "warmupComplete", true);

        Map<String, RpcMonitorService.NodeState> states = nodeStates(svc);
        RpcMonitorService.NodeState referenceState = new RpcMonitorService.NodeState();
        referenceState.lastWsBlockNumber = 100L;
        referenceState.lastWsBlockHash = "0xref";
        referenceState.lastWsEventReceivedAt = Instant.now();
        referenceState.wsNewHeadCount = 20;
        referenceState.webSocketRef.set(dummyWebSocket());
        states.put("mock-node-two", referenceState);

        RpcMonitorService.NodeState targetState = new RpcMonitorService.NodeState();
        targetState.lastWsBlockNumber = 80L;
        targetState.lastWsBlockHash = "0xtarget";
        targetState.lastWsEventReceivedAt = Instant.now();
        targetState.wsNewHeadCount = 20;
        targetState.webSocketRef.set(dummyWebSocket());
        states.put("mock-node", targetState);

        setReferenceState(svc, 100L, "0xref");

        // Establish reference blocks
        BlockConfidenceTracker refBlocks = new BlockConfidenceTracker();
        refBlocks.setHash(70L, de.makibytes.chaincheck.chain.shared.Confidence.FINALIZED, "0xsafe");
        BlockVotingService votingService = (BlockVotingService) ReflectionTestUtils.getField(svc, "blockVotingService");
        ReflectionTestUtils.setField(votingService, "blockConfidenceTracker", refBlocks);

        Object checkpoint = createBlockInfo(70L, "0xsafe", "0xparent", 10, 1_000L, Instant.now());
        ReflectionTestUtils.invokeMethod(svc, "maybeCompareToReference", registry.getNode("mock-node"), "finalized", checkpoint);

        List<AnomalyEvent> anomalies = store.getRawAnomaliesSince("mock-node", Instant.EPOCH);
        assertEquals(1, anomalies.size());
        assertEquals(AnomalyType.DELAY, anomalies.get(0).getType());
        assertEquals(MetricSource.WS, anomalies.get(0).getSource());
    }

    @Test
    @DisplayName("wrong head only triggers on safe/finalized hash mismatch")
    void wrongHeadOnlyOnCheckpointHashMismatch() {
        ChainCheckProperties properties = new ChainCheckProperties();
        properties.getAnomalyDetection().setLongDelayBlockCount(15);
        NodeRegistry registry = registryWithNodes("mock-node", "mock-node-two");
        InMemoryMetricsStore store = new InMemoryMetricsStore(properties);
        RpcMonitorService svc = new RpcMonitorService(registry, store, new AnomalyDetector(), properties);
        ReflectionTestUtils.setField(svc, "warmupComplete", true);

        Map<String, RpcMonitorService.NodeState> states = nodeStates(svc);
        RpcMonitorService.NodeState referenceState = new RpcMonitorService.NodeState();
        referenceState.lastWsBlockNumber = 210L;
        referenceState.lastWsBlockHash = "0xhead";
        referenceState.lastWsEventReceivedAt = Instant.now();
        referenceState.wsNewHeadCount = 20;
        referenceState.webSocketRef.set(dummyWebSocket());
        referenceState.lastSafeBlockNumber = 200L;
        referenceState.lastSafeBlockHash = "0xbbb";
        states.put("mock-node-two", referenceState);

        RpcMonitorService.NodeState targetState = new RpcMonitorService.NodeState();
        targetState.lastWsBlockNumber = 210L;
        targetState.lastWsBlockHash = "0xhead-target";
        targetState.lastWsEventReceivedAt = Instant.now();
        targetState.wsNewHeadCount = 20;
        targetState.webSocketRef.set(dummyWebSocket());
        states.put("mock-node", targetState);

        setReferenceState(svc, 210L, "0xhead");


        // Establish reference blocks
        BlockConfidenceTracker refBlocks = new BlockConfidenceTracker();
        refBlocks.setHash(200L, de.makibytes.chaincheck.chain.shared.Confidence.SAFE, "0xbbb");
        BlockVotingService votingService = (BlockVotingService) ReflectionTestUtils.getField(svc, "blockVotingService");
        ReflectionTestUtils.setField(votingService, "blockConfidenceTracker", refBlocks);

        Object checkpoint = createBlockInfo(200L, "0xaaa", "0xparent", 12, 1_000L, Instant.now());
        ReflectionTestUtils.invokeMethod(svc, "maybeCompareToReference", registry.getNode("mock-node"), "safe", checkpoint);

        List<AnomalyEvent> anomalies = store.getRawAnomaliesSince("mock-node", Instant.EPOCH);
        assertEquals(1, anomalies.size());
        assertEquals(AnomalyType.WRONG_HEAD, anomalies.get(0).getType());
        assertEquals(200L, anomalies.get(0).getBlockNumber());
    }

    @Test
    @DisplayName("wrong head anomaly when WS newHead later becomes invalid")
    void wrongHeadWhenWsNewHeadLaterInvalidated() {
        ChainCheckProperties properties = new ChainCheckProperties();
        NodeRegistry registry = registryWithNodes("node-a", "node-b", "node-c");
        InMemoryMetricsStore store = new InMemoryMetricsStore(properties);
        RpcMonitorService svc = new RpcMonitorService(registry, store, new AnomalyDetector(), properties);
        ReflectionTestUtils.setField(svc, "warmupComplete", true);

        Map<String, RpcMonitorService.NodeState> states = nodeStates(svc);

        RpcMonitorService.NodeState stateA = new RpcMonitorService.NodeState();
        stateA.lastWsBlockNumber = 100L;
        stateA.lastWsBlockHash = "0xaaa";
        stateA.lastWsEventReceivedAt = Instant.now();
        stateA.webSocketRef.set(dummyWebSocket());
        states.put("node-a", stateA);

        RpcMonitorService.NodeState stateB = new RpcMonitorService.NodeState();
        stateB.lastWsBlockNumber = 100L;
        stateB.lastWsBlockHash = "0xaaa";
        stateB.lastWsEventReceivedAt = Instant.now();
        stateB.webSocketRef.set(dummyWebSocket());
        states.put("node-b", stateB);

        RpcMonitorService.NodeState stateC = new RpcMonitorService.NodeState();
        stateC.lastWsBlockNumber = 100L;
        stateC.lastWsBlockHash = "0xaaa";
        stateC.lastWsEventReceivedAt = Instant.now();
        stateC.webSocketRef.set(dummyWebSocket());
        states.put("node-c", stateC);

        ReflectionTestUtils.invokeMethod(svc, "refreshReferenceFromNodes");

        store.addSample("node-a", new MetricSample(
                Instant.now().minusSeconds(5),
                MetricSource.WS,
                true,
                -1,
                100L,
                null,
                "0xaaa",
                "0xparent",
                null,
                null,
                null,
                null,
                null,
                null));

        stateA.lastWsBlockHash = "0xaaa";
        stateB.lastWsBlockHash = "0xbbb";
        stateC.lastWsBlockHash = "0xbbb";
        ReflectionTestUtils.setField(svc, "currentReferenceNodeKey", "node-b");

        ReflectionTestUtils.invokeMethod(svc, "refreshReferenceFromNodes");

        List<AnomalyEvent> anomaliesA = store.getRawAnomaliesSince("node-a", Instant.EPOCH);
        assertTrue(anomaliesA.stream().anyMatch(a -> a.getType() == AnomalyType.WRONG_HEAD
                && a.getSource() == MetricSource.WS
                && Long.valueOf(100L).equals(a.getBlockNumber())
                && "0xaaa".equalsIgnoreCase(a.getBlockHash())));

        List<AnomalyEvent> anomaliesB = store.getRawAnomaliesSince("node-b", Instant.EPOCH);
        assertTrue(anomaliesB.stream().noneMatch(a -> a.getType() == AnomalyType.WRONG_HEAD));
    }

    private NodeRegistry registryWithNodes(String... names) {
        ChainCheckProperties properties = new ChainCheckProperties();
        List<ChainCheckProperties.RpcNodeProperties> nodeProps = new ArrayList<>();
        for (String name : names) {
            ChainCheckProperties.RpcNodeProperties rpc = new ChainCheckProperties.RpcNodeProperties();
            rpc.setName(name);
            rpc.setHttp("http://" + name + ".example");
            nodeProps.add(rpc);
        }
        properties.setNodes(nodeProps);
        return new NodeRegistry(properties);
    }

    @SuppressWarnings("unchecked")
    private Map<String, RpcMonitorService.NodeState> nodeStates(RpcMonitorService svc) {
        return (Map<String, RpcMonitorService.NodeState>) ReflectionTestUtils.getField(svc, "nodeStates");
    }

    private ReferenceSnapshot reference(RpcMonitorService svc) {
        AtomicReference<?> ref = (AtomicReference<?>) ReflectionTestUtils.getField(svc, "referenceState");
        Object value = ref.get();
        if (value == null) {
            return new ReferenceSnapshot(null, null);
        }
        Long headNumber = (Long) ReflectionTestUtils.getField(value, "headNumber");
        String headHash = (String) ReflectionTestUtils.getField(value, "headHash");
        return new ReferenceSnapshot(headNumber, headHash);
    }

    private WebSocket dummyWebSocket() {
        return (WebSocket) Proxy.newProxyInstance(
                WebSocket.class.getClassLoader(),
                new Class<?>[] { WebSocket.class },
                (proxy, method, args) -> null);
    }

    private record ReferenceSnapshot(Long headNumber, String headHash) {
    }

    private void setReferenceState(RpcMonitorService svc, Long headNumber, String headHash) {
        AtomicReference<?> ref = (AtomicReference<?>) ReflectionTestUtils.getField(svc, "referenceState");
        Object state = createReferenceState(headNumber, headHash, Instant.now());
        @SuppressWarnings("rawtypes")
        AtomicReference rawRef = (AtomicReference) ref;
        rawRef.set(state);
    }

    private Object createReferenceState(Long headNumber, String headHash, Instant fetchedAt) {
        try {
            Class<?> refClass = Class.forName("de.makibytes.chaincheck.monitor.RpcMonitorService$ReferenceState");
            Constructor<?> ctor = refClass.getDeclaredConstructor(Long.class, String.class, Instant.class);
            ctor.setAccessible(true);
            return ctor.newInstance(headNumber, headHash, fetchedAt);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to construct reference state", ex);
        }
    }

    private Object createBlockInfo(Long blockNumber,
                                   String blockHash,
                                   String parentHash,
                                   Integer transactionCount,
                                   Long gasPriceWei,
                                   Instant blockTimestamp) {
        try {
            Class<?> infoClass = Class.forName("de.makibytes.chaincheck.monitor.RpcMonitorService$BlockInfo");
            Constructor<?> ctor = infoClass.getDeclaredConstructor(Long.class, String.class, String.class, Integer.class, Long.class, Instant.class);
            ctor.setAccessible(true);
            return ctor.newInstance(blockNumber, blockHash, parentHash, transactionCount, gasPriceWei, blockTimestamp);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to construct block info", ex);
        }
    }

}

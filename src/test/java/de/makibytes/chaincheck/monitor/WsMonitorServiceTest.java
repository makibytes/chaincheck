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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.makibytes.chaincheck.config.ChainCheckProperties;
import de.makibytes.chaincheck.monitor.NodeRegistry.NodeDefinition;
import de.makibytes.chaincheck.monitor.protocol.ChainProtocol;
import de.makibytes.chaincheck.monitor.protocol.EvmProtocol;
import de.makibytes.chaincheck.monitor.protocol.SolanaProtocol;

/**
 * Covers {@link WsMonitorService#fetchBlockForWsEvent}: the protocol-classified retry/skip/fail
 * handling of the follow-up fetch after a WS event. On Solana, slotSubscribe notifies at
 * processed commitment while getBlock serves confirmed blocks, so transient -32004 errors are
 * expected on nearly every slot and must not be recorded as node failures.
 */
@DisplayName("WsMonitorService Tests")
class WsMonitorServiceTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    private final NodeDefinition node = new NodeDefinition(
            "test-node", "Test Node", "http://localhost:18899", "ws://localhost:18900",
            400, 2000, false, false, 1000, 1000, 0, 0, Map.of(), false, 5);

    private final RpcMonitorService.BlockInfo block = new RpcMonitorService.BlockInfo(
            12345L, "8fJx3b", "7hGw2a", 42, null, Instant.now());

    private WsMonitorService service(ChainProtocol protocol, HttpMonitorService http) {
        return new WsMonitorService(mock(RpcMonitorService.class), null, null, null,
                new ChainCheckProperties(), new HashMap<>(), http, protocol);
    }

    private static JsonRpcErrorException rpcError(int code, String message) {
        return new JsonRpcErrorException(code, message,
                "{\"code\":" + code + ",\"message\":\"" + message + "\"}");
    }

    @Test
    @DisplayName("retries transient -32004 (block not yet confirmed) and returns the block")
    void retriesTransientErrorThenSucceeds() throws Exception {
        HttpMonitorService http = mock(HttpMonitorService.class);
        when(http.fetchBlockByHash(eq(node), anyString()))
                .thenThrow(rpcError(-32004, "Block not available for slot 12345"))
                .thenThrow(rpcError(-32004, "Block not available for slot 12345"))
                .thenReturn(block);

        RpcMonitorService.BlockInfo result =
                service(new SolanaProtocol(mapper), http).fetchBlockForWsEvent(node, "12345");

        assertNotNull(result);
        assertEquals("8fJx3b", result.blockHash());
        verify(http, times(3)).fetchBlockByHash(eq(node), anyString());
    }

    @Test
    @DisplayName("returns null immediately for a skipped slot (-32007) without recording a failure")
    void skipsSkippedSlot() throws Exception {
        HttpMonitorService http = mock(HttpMonitorService.class);
        when(http.fetchBlockByHash(eq(node), anyString()))
                .thenThrow(rpcError(-32007, "Slot 12345 was skipped"));

        RpcMonitorService.BlockInfo result =
                service(new SolanaProtocol(mapper), http).fetchBlockForWsEvent(node, "12345");

        assertNull(result);
        verify(http, times(1)).fetchBlockByHash(eq(node), anyString());
    }

    @Test
    @DisplayName("propagates unrecognised RPC errors so callers record the failure")
    void propagatesUnclassifiedError() throws Exception {
        HttpMonitorService http = mock(HttpMonitorService.class);
        when(http.fetchBlockByHash(eq(node), anyString()))
                .thenThrow(rpcError(-32602, "Invalid params"));

        WsMonitorService service = service(new SolanaProtocol(mapper), http);
        assertThrows(JsonRpcErrorException.class, () -> service.fetchBlockForWsEvent(node, "12345"));
        verify(http, times(1)).fetchBlockByHash(eq(node), anyString());
    }

    @Test
    @DisplayName("treats exhausted retries as skipped (old nodes answer -32004 for skipped slots forever)")
    void exhaustedRetriesReturnNull() throws Exception {
        HttpMonitorService http = mock(HttpMonitorService.class);
        when(http.fetchBlockByHash(eq(node), anyString()))
                .thenThrow(rpcError(-32004, "Block not available for slot 12345"));

        RpcMonitorService.BlockInfo result =
                service(new SolanaProtocol(mapper), http).fetchBlockForWsEvent(node, "12345");

        assertNull(result);
        verify(http, times(4)).fetchBlockByHash(eq(node), anyString());
    }

    @Test
    @DisplayName("EVM keeps fail-fast semantics: any RPC error propagates on the first attempt")
    void evmFailsFast() throws Exception {
        HttpMonitorService http = mock(HttpMonitorService.class);
        when(http.fetchBlockByHash(eq(node), anyString()))
                .thenThrow(rpcError(-32000, "header not found"));

        WsMonitorService service = service(
                new EvmProtocol(mapper, ChainCheckProperties.ModeType.ETHEREUM), http);
        assertThrows(JsonRpcErrorException.class, () -> service.fetchBlockForWsEvent(node, "0xabc"));
        verify(http, times(1)).fetchBlockByHash(eq(node), anyString());
    }
}

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
package de.makibytes.chaincheck.monitor.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.makibytes.chaincheck.config.ChainCheckProperties.ModeType;
import de.makibytes.chaincheck.monitor.RpcMonitorService;

@DisplayName("EvmProtocol Tests")
class EvmProtocolTest {

    private ObjectMapper mapper;
    private EvmProtocol ethereumProtocol;
    private EvmProtocol cosmosEvmProtocol;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        ethereumProtocol = new EvmProtocol(mapper, ModeType.ETHEREUM);
        cosmosEvmProtocol = new EvmProtocol(mapper, ModeType.COSMOS);
    }

    @Test
    @DisplayName("buildBlockNumberRequest produces eth_blockNumber")
    void blockNumberRequest() {
        RpcRequest req = ethereumProtocol.buildBlockNumberRequest();
        assertEquals("eth_blockNumber", req.method());
        assertTrue(req.params().isArray());
    }

    @Test
    @DisplayName("parseBlockNumberResponse parses hex block number")
    void parseBlockNumber() throws IOException {
        JsonNode result = mapper.readTree("\"0x1234\"");
        Long number = ethereumProtocol.parseBlockNumberResponse(result);
        assertEquals(0x1234L, number);
    }

    @Test
    @DisplayName("buildBlockByTagRequest produces eth_getBlockByNumber with tag")
    void blockByTagRequest() {
        RpcRequest req = ethereumProtocol.buildBlockByTagRequest("latest");
        assertEquals("eth_getBlockByNumber", req.method());
        assertEquals("latest", req.params().get(0).asText());
        assertFalse(req.params().get(1).asBoolean());
    }

    @Test
    @DisplayName("buildBlockByNumberRequest formats number as hex")
    void blockByNumberRequest() {
        RpcRequest req = ethereumProtocol.buildBlockByNumberRequest(100L);
        assertEquals("eth_getBlockByNumber", req.method());
        assertEquals("0x64", req.params().get(0).asText());
    }

    @Test
    @DisplayName("parseBlockByTagResponse parses standard EVM block")
    void parseEvmBlock() throws IOException {
        String json = """
                {
                  "number": "0x10d4f",
                  "hash": "0xabc123",
                  "parentHash": "0xdef456",
                  "timestamp": "0x64a12345",
                  "baseFeePerGas": "0x3b9aca00",
                  "transactions": ["0xtx1", "0xtx2"]
                }
                """;
        JsonNode result = mapper.readTree(json);
        RpcMonitorService.BlockInfo block = ethereumProtocol.parseBlockByTagResponse(result, "latest");
        assertNotNull(block);
        assertEquals(0x10d4fL, block.blockNumber());
        assertEquals("0xabc123", block.blockHash());
        assertEquals("0xdef456", block.parentHash());
        assertEquals(2, block.transactionCount());
        assertEquals(1000000000L, block.gasPriceWei());
    }

    @Test
    @DisplayName("Ethereum requiresHttpFetchAfterWsEvent is true; other EVM chains false")
    void requiresHttpFetch() {
        assertTrue(ethereumProtocol.requiresHttpFetchAfterWsEvent());
        assertFalse(cosmosEvmProtocol.requiresHttpFetchAfterWsEvent());
    }

    @Test
    @DisplayName("subscribeMessage produces valid eth_subscribe newHeads JSON")
    void subscribeMessage() throws IOException {
        String msg = ethereumProtocol.subscribeMessage();
        JsonNode parsed = mapper.readTree(msg);
        assertEquals("eth_subscribe", parsed.get("method").asText());
        assertEquals("newHeads", parsed.get("params").get(0).asText());
    }

    @Test
    @DisplayName("isNewBlockNotification recognises eth_subscription messages")
    void isNewBlockNotification() throws IOException {
        JsonNode notification = mapper.readTree(
                "{\"method\":\"eth_subscription\",\"params\":{\"result\":{}}}");
        assertTrue(ethereumProtocol.isNewBlockNotification(notification));

        JsonNode ack = mapper.readTree("{\"id\":1,\"result\":\"0xsub\"}");
        assertFalse(ethereumProtocol.isNewBlockNotification(ack));
    }

    @Test
    @DisplayName("buildFetchAfterWsEventRequest builds eth_getBlockByHash")
    void fetchAfterWsEvent() {
        BlockEvent event = new BlockEvent("0xabc", 100L, "0xabc", null, null, false);
        RpcRequest req = ethereumProtocol.buildFetchAfterWsEventRequest(event);
        assertEquals("eth_getBlockByHash", req.method());
        assertEquals("0xabc", req.params().get(0).asText());
    }

    @Test
    @DisplayName("supportsParentHash is true for EVM")
    void supportsParentHash() {
        assertTrue(ethereumProtocol.supportsParentHash());
    }

    @Test
    @DisplayName("httpMethod returns POST")
    void httpMethod() {
        assertEquals("POST", ethereumProtocol.httpMethod());
    }

    // ── eth_syncing health probe ──────────────────────────────────────────

    @Test
    @DisplayName("buildHealthRequest produces eth_syncing")
    void healthRequest() {
        assertTrue(ethereumProtocol.buildHealthRequest().isPresent());
        assertEquals("eth_syncing", ethereumProtocol.buildHealthRequest().get().method());
    }

    @Test
    @DisplayName("parseHealthSlotsBehind: synced node (false) returns 0")
    void healthSynced() throws Exception {
        JsonNode envelope = mapper.readTree("{\"id\":6,\"result\":false}");
        assertEquals(0, ethereumProtocol.parseHealthSlotsBehind(envelope));
    }

    @Test
    @DisplayName("parseHealthSlotsBehind: syncing object yields highest - current")
    void healthSyncing() throws Exception {
        // currentBlock 0x100 (256), highestBlock 0x1F4 (500) => 244 behind
        String json = "{\"id\":6,\"result\":{\"startingBlock\":\"0x0\","
                + "\"currentBlock\":\"0x100\",\"highestBlock\":\"0x1f4\"}}";
        assertEquals(244, ethereumProtocol.parseHealthSlotsBehind(mapper.readTree(json)));
    }

    @Test
    @DisplayName("parseHealthSlotsBehind: current >= highest clamps to 0")
    void healthCaughtUp() throws Exception {
        String json = "{\"id\":6,\"result\":{\"currentBlock\":\"0x1f4\",\"highestBlock\":\"0x1f4\"}}";
        assertEquals(0, ethereumProtocol.parseHealthSlotsBehind(mapper.readTree(json)));
    }

    @Test
    @DisplayName("parseHealthSlotsBehind: error or unparseable returns -1, absent returns null")
    void healthEdgeCases() throws Exception {
        assertEquals(-1, ethereumProtocol.parseHealthSlotsBehind(
                mapper.readTree("{\"id\":6,\"error\":{\"code\":-32000,\"message\":\"unavailable\"}}")));
        assertEquals(-1, ethereumProtocol.parseHealthSlotsBehind(
                mapper.readTree("{\"id\":6,\"result\":true}")));
        assertEquals(-1, ethereumProtocol.parseHealthSlotsBehind(
                mapper.readTree("{\"id\":6,\"result\":{\"currentBlock\":\"bad\"}}")));
        assertNull(ethereumProtocol.parseHealthSlotsBehind(null));
    }

    // ── web3_clientVersion probe ───────────────────────────────────────────

    @Test
    @DisplayName("buildVersionRequest produces web3_clientVersion")
    void versionRequest() {
        assertTrue(ethereumProtocol.buildVersionRequest().isPresent());
        assertEquals("web3_clientVersion", ethereumProtocol.buildVersionRequest().get().method());
    }

    @Test
    @DisplayName("parseVersion condenses the full client string to client/version")
    void versionParsing() throws Exception {
        assertEquals("Geth/v1.13.5", ethereumProtocol.parseVersion(
                mapper.readTree("\"Geth/v1.13.5-stable-916d6a44/linux-amd64/go1.21.3\"")));
        assertEquals("erigon/v2.60.0", ethereumProtocol.parseVersion(
                mapper.readTree("\"erigon/v2.60.0/linux-amd64/go1.22.1\"")));
        // Nethermind uses a '+' build suffix
        assertEquals("Nethermind/v1.25.4", ethereumProtocol.parseVersion(
                mapper.readTree("\"Nethermind/v1.25.4+8d234f1/linux-x64/dotnet8.0\"")));
        assertNull(ethereumProtocol.parseVersion(mapper.readTree("\"\"")));
        assertNull(ethereumProtocol.parseVersion(null));
    }
}

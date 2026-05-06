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

import de.makibytes.chaincheck.monitor.RpcMonitorService;

@DisplayName("StarknetProtocol Tests")
class StarknetProtocolTest {

    private ObjectMapper mapper;
    private StarknetProtocol protocol;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        protocol = new StarknetProtocol(mapper);
    }

    @Test
    @DisplayName("buildBlockNumberRequest produces starknet_blockNumber")
    void blockNumberRequest() {
        RpcRequest req = protocol.buildBlockNumberRequest();
        assertEquals("starknet_blockNumber", req.method());
    }

    @Test
    @DisplayName("parseBlockNumberResponse parses plain integer (not hex)")
    void parseBlockNumber() throws IOException {
        JsonNode result = mapper.readTree("12345");
        Long number = protocol.parseBlockNumberResponse(result);
        assertEquals(12345L, number);
    }

    @Test
    @DisplayName("buildBlockByTagRequest produces starknet_getBlockWithTxHashes with latest")
    void blockByTagRequest() {
        RpcRequest latest = protocol.buildBlockByTagRequest("latest");
        assertEquals("starknet_getBlockWithTxHashes", latest.method());
        assertEquals("latest", latest.params().get(0).path("block_id").asText());

        // safe and finalized also map to latest (no safe concept in Starknet)
        RpcRequest safe = protocol.buildBlockByTagRequest("safe");
        assertEquals("starknet_getBlockWithTxHashes", safe.method());
    }

    @Test
    @DisplayName("buildBlockByNumberRequest uses block_number object as block_id")
    void blockByNumberRequest() {
        RpcRequest req = protocol.buildBlockByNumberRequest(12345L);
        assertEquals("starknet_getBlockWithTxHashes", req.method());
        assertEquals(12345L, req.params().get(0).path("block_id").path("block_number").asLong());
    }

    @Test
    @DisplayName("parseBlockByTagResponse parses starknet_getBlockWithTxHashes result")
    void parseStarknetBlock() throws IOException {
        String json = """
                {
                  "block_number": 600000,
                  "block_hash": "0xabcdef1234567890",
                  "parent_hash": "0xfedcba0987654321",
                  "timestamp": 1704067200,
                  "status": "ACCEPTED_ON_L2",
                  "transactions": ["0xtx1", "0xtx2", "0xtx3"],
                  "l1_gas_price": { "price_in_wei": "0x3b9aca00" }
                }
                """;
        JsonNode result = mapper.readTree(json);
        RpcMonitorService.BlockInfo block = protocol.parseBlockByTagResponse(result, "latest");
        assertNotNull(block);
        assertEquals(600000L, block.blockNumber());
        assertEquals("0xabcdef1234567890", block.blockHash());
        assertEquals("0xfedcba0987654321", block.parentHash());
        assertEquals(3, block.transactionCount());
        assertEquals(1000000000L, block.gasPriceWei());
        assertEquals(1704067200L, block.blockTimestamp().getEpochSecond());
    }

    @Test
    @DisplayName("subscribeMessage produces starknet_subscribeNewHeads JSON")
    void subscribeMessage() throws IOException {
        String msg = protocol.subscribeMessage();
        JsonNode parsed = mapper.readTree(msg);
        assertEquals("starknet_subscribeNewHeads", parsed.get("method").asText());
    }

    @Test
    @DisplayName("isNewBlockNotification recognises starknet_subscriptionNewHeads")
    void isNewBlockNotification() throws IOException {
        JsonNode notification = mapper.readTree(
                "{\"method\":\"starknet_subscriptionNewHeads\",\"params\":{\"result\":{}}}");
        assertTrue(protocol.isNewBlockNotification(notification));

        JsonNode ack = mapper.readTree("{\"id\":1,\"result\":{\"subscription_id\":42}}");
        assertFalse(protocol.isNewBlockNotification(ack));
    }

    @Test
    @DisplayName("parseWsNotification extracts block header from subscribeNewHeads event")
    void parseWsNotification() throws IOException {
        String json = """
                {
                  "method": "starknet_subscriptionNewHeads",
                  "params": {
                    "result": {
                      "block_number": 600000,
                      "block_hash": "0xabcdef",
                      "parent_hash": "0xfedcba",
                      "timestamp": 1704067200,
                      "status": "ACCEPTED_ON_L2",
                      "transactions": []
                    }
                  }
                }
                """;
        BlockEvent event = protocol.parseWsNotification(mapper.readTree(json));
        assertEquals("0xabcdef", event.blockHash());
        assertEquals(600000L, event.blockNumber());
        assertEquals("0xfedcba", event.parentHash());
        assertTrue(event.hasFullData());
        assertNotNull(event.blockTimestamp());
    }

    @Test
    @DisplayName("requiresHttpFetchAfterWsEvent is false")
    void requiresHttpFetch() {
        assertFalse(protocol.requiresHttpFetchAfterWsEvent());
    }

    @Test
    @DisplayName("supportsParentHash is true")
    void supportsParentHash() {
        assertTrue(protocol.supportsParentHash());
    }

    @Test
    @DisplayName("httpMethod returns POST")
    void httpMethod() {
        assertEquals("POST", protocol.httpMethod());
    }
}

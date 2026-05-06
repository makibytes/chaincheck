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

@DisplayName("SolanaProtocol Tests")
class SolanaProtocolTest {

    private ObjectMapper mapper;
    private SolanaProtocol protocol;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        protocol = new SolanaProtocol(mapper);
    }

    @Test
    @DisplayName("buildBlockNumberRequest produces getSlot with processed commitment")
    void blockNumberRequest() {
        RpcRequest req = protocol.buildBlockNumberRequest();
        assertEquals("getSlot", req.method());
        // Must explicitly request processed commitment; default is finalized (~32 slots behind)
        assertEquals("processed", req.params().get(0).path("commitment").asText());
    }

    @Test
    @DisplayName("parseBlockNumberResponse parses integer slot directly")
    void parseBlockNumber() throws IOException {
        JsonNode result = mapper.readTree("12345678");
        Long slot = protocol.parseBlockNumberResponse(result);
        assertEquals(12345678L, slot);
    }

    @Test
    @DisplayName("buildBlockByTagRequest maps tags to commitment levels")
    void blockByTagRequest() {
        RpcRequest latest = protocol.buildBlockByTagRequest("latest");
        assertEquals("getLatestBlockhash", latest.method());
        assertEquals("processed", latest.params().get(0).path("commitment").asText());

        RpcRequest safe = protocol.buildBlockByTagRequest("safe");
        assertEquals("confirmed", safe.params().get(0).path("commitment").asText());

        RpcRequest finalized = protocol.buildBlockByTagRequest("finalized");
        assertEquals("finalized", finalized.params().get(0).path("commitment").asText());
    }

    @Test
    @DisplayName("parseBlockByTagResponse parses getLatestBlockhash result")
    void parseBlockByTagResponse() throws IOException {
        String json = """
                {
                  "context": { "slot": 300000000 },
                  "value": { "blockhash": "5w7HbDFqEZ9r8HN5FYLt...", "lastValidBlockHeight": 295000000 }
                }
                """;
        JsonNode result = mapper.readTree(json);
        RpcMonitorService.BlockInfo block = protocol.parseBlockByTagResponse(result, "safe");
        assertNotNull(block);
        assertEquals(300000000L, block.blockNumber());
        assertEquals("5w7HbDFqEZ9r8HN5FYLt...", block.blockHash());
        assertNull(block.parentHash());
        assertNull(block.blockTimestamp());
    }

    @Test
    @DisplayName("buildBlockByNumberRequest produces getBlock with slot")
    void blockByNumberRequest() {
        RpcRequest req = protocol.buildBlockByNumberRequest(12345L);
        assertEquals("getBlock", req.method());
        assertEquals(12345L, req.params().get(0).asLong());
        assertEquals("json", req.params().get(1).path("encoding").asText());
    }

    @Test
    @DisplayName("parseBlockByNumberResponse parses getBlock result")
    void parseGetBlockResponse() throws IOException {
        String json = """
                {
                  "blockhash": "AaBbCcDdEe...",
                  "previousBlockhash": "ZzYyXxWwVv...",
                  "parentSlot": 299999999,
                  "blockTime": 1700000000,
                  "transactions": [{"meta":{}}, {"meta":{}}]
                }
                """;
        JsonNode result = mapper.readTree(json);
        RpcMonitorService.BlockInfo block = protocol.parseBlockByNumberResponse(result);
        assertNotNull(block);
        assertEquals(300000000L, block.blockNumber()); // parentSlot + 1
        assertEquals("AaBbCcDdEe...", block.blockHash());
        assertEquals("ZzYyXxWwVv...", block.parentHash());
        assertEquals(2, block.transactionCount());
        assertNotNull(block.blockTimestamp());
        assertEquals(1700000000L, block.blockTimestamp().getEpochSecond());
    }

    @Test
    @DisplayName("subscribeMessage produces slotSubscribe JSON")
    void subscribeMessage() throws IOException {
        String msg = protocol.subscribeMessage();
        JsonNode parsed = mapper.readTree(msg);
        assertEquals("slotSubscribe", parsed.get("method").asText());
    }

    @Test
    @DisplayName("isNewBlockNotification recognises slotNotification method")
    void isNewBlockNotification() throws IOException {
        JsonNode notification = mapper.readTree(
                "{\"method\":\"slotNotification\",\"params\":{\"result\":{\"slot\":300000000,\"parent\":299999999,\"root\":299999968}}}");
        assertTrue(protocol.isNewBlockNotification(notification));

        JsonNode ack = mapper.readTree("{\"id\":1,\"result\":0}");
        assertFalse(protocol.isNewBlockNotification(ack));
    }

    @Test
    @DisplayName("parseWsNotification extracts slot as identifier")
    void parseWsNotification() throws IOException {
        String json = """
                {
                  "method": "slotNotification",
                  "params": {
                    "result": { "slot": 300000000, "parent": 299999999, "root": 299999968 }
                  }
                }
                """;
        JsonNode message = mapper.readTree(json);
        BlockEvent event = protocol.parseWsNotification(message);
        assertEquals("300000000", event.identifier());
        assertEquals(300000000L, event.blockNumber());
        assertNull(event.blockHash());
        assertFalse(event.hasFullData());
    }

    @Test
    @DisplayName("requiresHttpFetchAfterWsEvent is true")
    void requiresHttpFetch() {
        assertTrue(protocol.requiresHttpFetchAfterWsEvent());
    }

    @Test
    @DisplayName("buildFetchAfterWsEventRequest builds getBlock for the slot")
    void fetchAfterWsEvent() {
        BlockEvent event = new BlockEvent("300000000", 300000000L, null, null, null, false);
        RpcRequest req = protocol.buildFetchAfterWsEventRequest(event);
        assertEquals("getBlock", req.method());
        assertEquals(300000000L, req.params().get(0).asLong());
    }

    @Test
    @DisplayName("httpMethod returns POST")
    void httpMethod() {
        assertEquals("POST", protocol.httpMethod());
    }

    @Test
    @DisplayName("supportsParentHash is true (previousBlockhash)")
    void supportsParentHash() {
        assertTrue(protocol.supportsParentHash());
    }

    @Test
    @DisplayName("parseBlockByNumberResponse yields parentSlot+1 even when slots were skipped")
    void parseGetBlockResponseSkippedSlots() throws IOException {
        // Slot 305 is produced after 4 skipped slots (300-304); parentSlot = 299.
        // The protocol returns parentSlot+1 = 300, which is WRONG for slot 305.
        // WsMonitorService.handleBlockWithHttpFetch and recoverMissingBlockWithRetry
        // override the block number with the authoritative slot from the WS event / request.
        String json = """
                {
                  "blockhash": "SlotFiveHash...",
                  "previousBlockhash": "SlotTwoNineNine...",
                  "parentSlot": 299,
                  "blockTime": 1700000000,
                  "transactions": []
                }
                """;
        JsonNode result = mapper.readTree(json);
        RpcMonitorService.BlockInfo block = protocol.parseBlockByNumberResponse(result);
        assertNotNull(block);
        // Protocol sees parentSlot=299, so returns 300 (not 305).
        // The caller (WsMonitorService) is responsible for replacing this with the actual slot.
        assertEquals(300L, block.blockNumber());
        assertEquals("SlotFiveHash...", block.blockHash());
        assertEquals("SlotTwoNineNine...", block.parentHash());
    }

    @Test
    @DisplayName("parseBlockByTagResponse returns null for missing context slot")
    void parseBlockByTagResponseMissingSlot() throws IOException {
        // If the node returns an empty/malformed context, we get null
        JsonNode result = mapper.readTree("{\"context\":{},\"value\":{\"blockhash\":\"abc\"}}");
        // context has no "slot" field → slot defaults to 0 but also has("slot") is false
        assertNull(protocol.parseBlockByTagResponse(result, "latest"));
    }
}

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
        // Header-only monitoring: avoid multi-MB full-transaction payloads on mainnet
        assertEquals("signatures", req.params().get(1).path("transactionDetails").asText());
        assertFalse(req.params().get(1).path("rewards").asBoolean(true));
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
    @DisplayName("parseBlockByNumberResponse counts transactions from signatures array")
    void parseGetBlockResponseSignaturesOnly() throws IOException {
        // Response shape when transactionDetails="signatures" is requested
        String json = """
                {
                  "blockhash": "AaBbCcDdEe...",
                  "previousBlockhash": "ZzYyXxWwVv...",
                  "parentSlot": 299999999,
                  "blockTime": 1700000000,
                  "signatures": ["sig1", "sig2", "sig3"]
                }
                """;
        JsonNode result = mapper.readTree(json);
        RpcMonitorService.BlockInfo block = protocol.parseBlockByNumberResponse(result);
        assertNotNull(block);
        assertEquals("AaBbCcDdEe...", block.blockHash());
        assertEquals(3, block.transactionCount());
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

    @Test
    @DisplayName("buildHealthRequest produces a parameterless getHealth call")
    void buildHealthRequest() {
        assertTrue(protocol.buildHealthRequest().isPresent());
        RpcRequest req = protocol.buildHealthRequest().get();
        assertEquals("getHealth", req.method());
        assertEquals(0, req.params().size());
    }

    @Test
    @DisplayName("parseHealthSlotsBehind: healthy node returns 0")
    void parseHealthHealthy() throws IOException {
        JsonNode envelope = mapper.readTree("{\"id\":6,\"result\":\"ok\"}");
        assertEquals(0, protocol.parseHealthSlotsBehind(envelope));
    }

    @Test
    @DisplayName("parseHealthSlotsBehind: reads numSlotsBehind from the error data")
    void parseHealthBehindWithData() throws IOException {
        String json = "{\"id\":6,\"error\":{\"code\":-32005,"
                + "\"message\":\"Node is behind by 213 slots\","
                + "\"data\":{\"numSlotsBehind\":213}}}";
        assertEquals(213, protocol.parseHealthSlotsBehind(mapper.readTree(json)));
    }

    @Test
    @DisplayName("parseHealthSlotsBehind: falls back to the error message when data is absent")
    void parseHealthBehindFromMessage() throws IOException {
        String json = "{\"id\":6,\"error\":{\"code\":-32005,"
                + "\"message\":\"Node is behind by 77 slots\"}}";
        assertEquals(77, protocol.parseHealthSlotsBehind(mapper.readTree(json)));
    }

    @Test
    @DisplayName("parseHealthSlotsBehind: unhealthy with no parseable count returns -1")
    void parseHealthBehindUnknown() throws IOException {
        JsonNode envelope = mapper.readTree("{\"id\":6,\"error\":{\"code\":-32005,\"message\":\"unhealthy\"}}");
        assertEquals(-1, protocol.parseHealthSlotsBehind(envelope));
    }

    @Test
    @DisplayName("parseHealthSlotsBehind: no health info returns null")
    void parseHealthUnknown() throws IOException {
        JsonNode envelope = mapper.readTree("{\"id\":6,\"result\":42}");
        assertNull(protocol.parseHealthSlotsBehind(envelope));
        assertNull(protocol.parseHealthSlotsBehind(null));
    }

    @Test
    @DisplayName("buildVersionRequest / parseVersion reads solana-core")
    void parseVersion() throws IOException {
        assertTrue(protocol.buildVersionRequest().isPresent());
        assertEquals("getVersion", protocol.buildVersionRequest().get().method());
        JsonNode result = mapper.readTree("{\"solana-core\":\"2.1.21\",\"feature-set\":1416569292}");
        assertEquals("2.1.21", protocol.parseVersion(result));
        assertNull(protocol.parseVersion(mapper.readTree("{}")));
        assertNull(protocol.parseVersion(null));
    }

    @Test
    @DisplayName("buildPerformanceRequest requests a single sample")
    void buildPerformanceRequest() {
        assertTrue(protocol.buildPerformanceRequest().isPresent());
        RpcRequest req = protocol.buildPerformanceRequest().get();
        assertEquals("getRecentPerformanceSamples", req.method());
        assertEquals(1, req.params().get(0).asInt());
    }

    @Test
    @DisplayName("parsePerformance prefers non-vote TPS and derives slot time")
    void parsePerformance() throws IOException {
        // 30000 non-vote tx over 60s = 500 TPS (raw 120000 incl. votes would be 2000);
        // 150 slots over 60s = 400ms/slot
        String json = "[{\"slot\":348125,\"numTransactions\":120000,"
                + "\"numNonVoteTransactions\":30000,\"samplePeriodSecs\":60,\"numSlots\":150}]";
        double[] perf = protocol.parsePerformance(mapper.readTree(json));
        assertNotNull(perf);
        assertEquals(500.0, perf[0], 0.001);
        assertEquals(400.0, perf[1], 0.001);
    }

    @Test
    @DisplayName("parsePerformance falls back to numTransactions when numNonVoteTransactions is absent")
    void parsePerformanceVoteFallback() throws IOException {
        // Pre-1.15 nodes don't report numNonVoteTransactions
        String json = "[{\"slot\":348125,\"numTransactions\":120000,"
                + "\"samplePeriodSecs\":60,\"numSlots\":150}]";
        double[] perf = protocol.parsePerformance(mapper.readTree(json));
        assertNotNull(perf);
        assertEquals(2000.0, perf[0], 0.001);
    }

    @Test
    @DisplayName("parsePerformance returns null for an empty or malformed result")
    void parsePerformanceEmpty() throws IOException {
        assertNull(protocol.parsePerformance(mapper.readTree("[]")));
        assertNull(protocol.parsePerformance(null));
        // samplePeriodSecs == 0 cannot yield a rate
        assertNull(protocol.parsePerformance(mapper.readTree(
                "[{\"numTransactions\":10,\"numSlots\":5,\"samplePeriodSecs\":0}]")));
    }

    @Test
    @DisplayName("classifyFetchAfterWsEventError retries block-not-yet-available errors")
    void classifyFetchErrorRetry() {
        // -32004 BLOCK_NOT_AVAILABLE: slotSubscribe fires at processed commitment,
        // getBlock serves confirmed blocks — the first fetch routinely races confirmation
        assertEquals(ChainProtocol.FetchRetryAction.RETRY,
                protocol.classifyFetchAfterWsEventError(-32004, "Block not available for slot 12345"));
        assertEquals(ChainProtocol.FetchRetryAction.RETRY,
                protocol.classifyFetchAfterWsEventError(0, "Block not available for slot 12345"));
    }

    @Test
    @DisplayName("classifyFetchAfterWsEventError skips skipped or purged slots")
    void classifyFetchErrorSkip() {
        assertEquals(ChainProtocol.FetchRetryAction.SKIP,
                protocol.classifyFetchAfterWsEventError(-32007,
                        "Slot 12345 was skipped, or missing due to ledger jump to recent snapshot"));
        assertEquals(ChainProtocol.FetchRetryAction.SKIP,
                protocol.classifyFetchAfterWsEventError(-32009,
                        "Slot 12345 was skipped, or missing in long-term storage"));
        assertEquals(ChainProtocol.FetchRetryAction.SKIP,
                protocol.classifyFetchAfterWsEventError(0, "slot was skipped"));
        assertEquals(ChainProtocol.FetchRetryAction.SKIP,
                protocol.classifyFetchAfterWsEventError(0, "block purged from ledger"));
    }

    @Test
    @DisplayName("classifyFetchAfterWsEventError fails on unrecognised errors")
    void classifyFetchErrorFail() {
        assertEquals(ChainProtocol.FetchRetryAction.FAIL,
                protocol.classifyFetchAfterWsEventError(-32602, "Invalid params"));
        assertEquals(ChainProtocol.FetchRetryAction.FAIL,
                protocol.classifyFetchAfterWsEventError(0, null));
    }
}

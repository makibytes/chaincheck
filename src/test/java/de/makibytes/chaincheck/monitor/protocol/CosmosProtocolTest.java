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

@DisplayName("CosmosProtocol Tests")
class CosmosProtocolTest {

    private ObjectMapper mapper;
    private CosmosProtocol protocol;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        protocol = new CosmosProtocol(mapper);
    }

    @Test
    @DisplayName("httpMethod returns GET")
    void httpMethod() {
        assertEquals("GET", protocol.httpMethod());
    }

    @Test
    @DisplayName("buildBlockNumberRequest targets /status path")
    void blockNumberRequest() {
        RpcRequest req = protocol.buildBlockNumberRequest();
        assertEquals("/status", req.method());
    }

    @Test
    @DisplayName("parseBlockNumberResponse reads sync_info.latest_block_height")
    void parseBlockNumber() throws IOException {
        // Transport unwraps the outer "result" envelope; here we pass the inner object
        String json = """
                {
                  "sync_info": {
                    "latest_block_height": "12345678",
                    "latest_block_hash": "ABCDEF"
                  }
                }
                """;
        JsonNode result = mapper.readTree(json);
        Long height = protocol.parseBlockNumberResponse(result);
        assertEquals(12345678L, height);
    }

    @Test
    @DisplayName("buildBlockByTagRequest targets /block path")
    void blockByTagRequest() {
        RpcRequest req = protocol.buildBlockByTagRequest("latest");
        assertEquals("/block", req.method());
    }

    @Test
    @DisplayName("buildBlockByNumberRequest includes height in params")
    void blockByNumberRequest() {
        RpcRequest req = protocol.buildBlockByNumberRequest(12345L);
        assertEquals("/block", req.method());
        assertEquals("12345", req.params().path("height").asText());
    }

    @Test
    @DisplayName("parseBlockByTagResponse parses CometBFT block structure")
    void parseBlockByTagResponse() throws IOException {
        // After GET transport unwraps the outer "result" envelope
        String json = """
                {
                  "block_id": { "hash": "ABCDEF1234567890ABCDEF1234567890ABCDEF1234567890ABCDEF1234567890" },
                  "block": {
                    "header": {
                      "height": "12345678",
                      "time": "2024-01-01T00:00:00.000000000Z",
                      "last_block_id": {
                        "hash": "FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210FEDCBA9876543210"
                      }
                    },
                    "data": { "txs": ["tx1_base64", "tx2_base64"] }
                  }
                }
                """;
        JsonNode result = mapper.readTree(json);
        RpcMonitorService.BlockInfo block = protocol.parseBlockByTagResponse(result, "latest");
        assertNotNull(block);
        assertEquals(12345678L, block.blockNumber());
        // Hashes are normalised to lowercase
        assertEquals("abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890", block.blockHash());
        assertEquals("fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210", block.parentHash());
        assertEquals(2, block.transactionCount());
        assertNotNull(block.blockTimestamp());
        assertNull(block.gasPriceWei()); // no gas fee model
    }

    @Test
    @DisplayName("subscribeMessage produces CometBFT subscribe JSON")
    void subscribeMessage() throws IOException {
        String msg = protocol.subscribeMessage();
        JsonNode parsed = mapper.readTree(msg);
        assertEquals("subscribe", parsed.get("method").asText());
        assertTrue(parsed.path("params").path("query").asText().contains("NewBlock"));
    }

    @Test
    @DisplayName("isNewBlockNotification recognises NewBlock event type")
    void isNewBlockNotification() throws IOException {
        String newBlockJson = """
                {
                  "id": 0,
                  "result": {
                    "data": {
                      "type": "tendermint/event/NewBlock",
                      "value": { "block_id": {}, "block": { "header": {}, "data": {} } }
                    }
                  }
                }
                """;
        assertTrue(protocol.isNewBlockNotification(mapper.readTree(newBlockJson)));

        JsonNode ack = mapper.readTree("{\"id\":1,\"result\":{}}");
        assertFalse(protocol.isNewBlockNotification(ack));
    }

    @Test
    @DisplayName("parseWsNotification extracts block from NewBlock event")
    void parseWsNotification() throws IOException {
        String json = """
                {
                  "result": {
                    "data": {
                      "type": "tendermint/event/NewBlock",
                      "value": {
                        "block_id": { "hash": "AABBCC" },
                        "block": {
                          "header": {
                            "height": "100",
                            "time": "2024-06-01T12:00:00.000000000Z",
                            "last_block_id": { "hash": "DDEEFF" }
                          },
                          "data": { "txs": [] }
                        }
                      }
                    }
                  }
                }
                """;
        BlockEvent event = protocol.parseWsNotification(mapper.readTree(json));
        assertEquals("aabbcc", event.blockHash()); // lowercase normalised
        assertEquals(100L, event.blockNumber());
        assertEquals("ddeeff", event.parentHash());
        assertTrue(event.hasFullData());
    }

    @Test
    @DisplayName("requiresHttpFetchAfterWsEvent is false")
    void requiresHttpFetch() {
        assertFalse(protocol.requiresHttpFetchAfterWsEvent());
    }

    @Test
    @DisplayName("supportsParentHash is true (last_block_id)")
    void supportsParentHash() {
        assertTrue(protocol.supportsParentHash());
    }

    @Test
    @DisplayName("parseBlockByTagResponse treats empty last_block_id.hash as null (genesis block)")
    void parseGenesisBlockParentHash() throws IOException {
        // CometBFT height-1 block has last_block_id.hash = "" (no parent)
        String json = """
                {
                  "block_id": { "hash": "GENESIS1234567890GENESIS1234567890GENESIS1234567890GENESIS12345" },
                  "block": {
                    "header": {
                      "height": "1",
                      "time": "2024-01-01T00:00:00.000000000Z",
                      "last_block_id": { "hash": "" }
                    },
                    "data": { "txs": [] }
                  }
                }
                """;
        JsonNode result = mapper.readTree(json);
        RpcMonitorService.BlockInfo block = protocol.parseBlockByTagResponse(result, "latest");
        assertNotNull(block);
        assertEquals(1L, block.blockNumber());
        assertNotNull(block.blockHash());
        assertNull(block.parentHash()); // empty string → null
    }

    @Test
    @DisplayName("parseWsNotification treats empty last_block_id.hash as null (genesis NewBlock event)")
    void parseWsNotificationGenesisParentHash() throws IOException {
        String json = """
                {
                  "result": {
                    "data": {
                      "type": "tendermint/event/NewBlock",
                      "value": {
                        "block_id": { "hash": "GENESIS001" },
                        "block": {
                          "header": {
                            "height": "1",
                            "time": "2024-06-01T12:00:00.000000000Z",
                            "last_block_id": { "hash": "" }
                          },
                          "data": { "txs": [] }
                        }
                      }
                    }
                  }
                }
                """;
        BlockEvent event = protocol.parseWsNotification(mapper.readTree(json));
        assertEquals("genesis001", event.blockHash());
        assertEquals(1L, event.blockNumber());
        assertNull(event.parentHash()); // empty string → null
    }
}

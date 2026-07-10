/*
 * Copyright (c) 2026 MakiBytes.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package de.makibytes.chaincheck.monitor.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.makibytes.chaincheck.monitor.RpcMonitorService;

@DisplayName("NearProtocol Tests")
class NearProtocolTest {

    private ObjectMapper mapper;
    private NearProtocol protocol;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        protocol = new NearProtocol(mapper);
    }

    @Test
    @DisplayName("buildBlockNumberRequest uses status")
    void blockNumberRequest() {
        RpcRequest req = protocol.buildBlockNumberRequest();
        assertEquals("status", req.method());
        assertTrue(req.params().isArray());
    }

    @Test
    @DisplayName("parseBlockNumberResponse reads latest_block_height")
    void parseBlockNumber() throws IOException {
        JsonNode result = mapper.readTree("{\"sync_info\":{\"latest_block_height\":1234}}" );
        Long number = protocol.parseBlockNumberResponse(result);
        assertEquals(1234L, number);
    }

    @Test
    @DisplayName("buildBlockByTagRequest maps finality tags")
    void blockByTagRequest() {
        RpcRequest latest = protocol.buildBlockByTagRequest("latest");
        assertEquals("block", latest.method());
        assertEquals("optimistic", latest.params().get(0).path("finality").asText());

        RpcRequest finalized = protocol.buildBlockByTagRequest("finalized");
        assertEquals("final", finalized.params().get(0).path("finality").asText());
    }

    @Test
    @DisplayName("parseBlockByTagResponse parses block headers")
    void parseBlockResponse() throws IOException {
        String json = """
                {
                  "header": {
                    "height": 42,
                    "hash": "abc",
                    "prev_hash": "def",
                    "timestamp": 1717521123546789000
                  }
                }
                """;
        RpcMonitorService.BlockInfo block = protocol.parseBlockByTagResponse(mapper.readTree(json), "latest");
        assertNotNull(block);
        assertEquals(42L, block.blockNumber());
        assertEquals("abc", block.blockHash());
        assertEquals("def", block.parentHash());
        assertEquals(Instant.ofEpochMilli(1717521123546789000L / 1_000_000L), block.blockTimestamp());
    }

    @Test
    @DisplayName("WS notification parsing uses block header values")
    void parseWsNotification() throws IOException {
        JsonNode message = mapper.readTree("""
                {
                  "method": "EXPERIMENTAL_subscription",
                  "params": {
                    "subscriptionType": "blocks",
                    "result": {
                      "header": {
                        "height": 7,
                        "hash": "block-hash",
                        "prev_hash": "parent-hash",
                        "timestamp": 1717521123546789000
                      }
                    }
                  }
                }
                """);
        BlockEvent event = protocol.parseWsNotification(message);
        assertEquals("block-hash", event.identifier());
        assertEquals(7L, event.blockNumber());
        assertEquals("parent-hash", event.parentHash());
    }

    @Test
    @DisplayName("subscription detection only matches blocks subscriptions")
    void isNewBlockNotification() throws IOException {
        JsonNode notification = mapper.readTree("{\"method\":\"EXPERIMENTAL_subscription\",\"params\":{\"subscriptionType\":\"blocks\"}}" );
        assertTrue(protocol.isNewBlockNotification(notification));

        JsonNode other = mapper.readTree("{\"method\":\"EXPERIMENTAL_subscription\",\"params\":{\"subscriptionType\":\"transactions\"}}" );
        assertFalse(protocol.isNewBlockNotification(other));
    }

    @Test
    @DisplayName("health probe reports syncing and catches up state")
    void healthProbe() throws IOException {
        JsonNode synced = mapper.readTree("{\"result\":{\"sync_info\":{\"syncing\":false}}}");
        assertEquals(0, protocol.parseHealthSlotsBehind(synced));

        JsonNode syncing = mapper.readTree("{\"result\":{\"sync_info\":{\"syncing\":true,\"latest_block_height\":30,\"earliest_block_height\":10}}}");
        assertEquals(20, protocol.parseHealthSlotsBehind(syncing));

        JsonNode overflow = mapper.readTree("{\"result\":{\"sync_info\":{\"syncing\":true,\"latest_block_height\":9223372036854775807,\"earliest_block_height\":0}}}");
        assertEquals(Integer.MAX_VALUE, protocol.parseHealthSlotsBehind(overflow));

        assertNull(protocol.parseHealthSlotsBehind(mapper.readTree("{\"result\":{\"sync_info\":{}}}")));
    }

    @Test
    @DisplayName("supportsParentHash and no extra HTTP fetch after WS")
    void capabilities() {
        assertTrue(protocol.supportsParentHash());
        assertFalse(protocol.requiresHttpFetchAfterWsEvent());
    }
}

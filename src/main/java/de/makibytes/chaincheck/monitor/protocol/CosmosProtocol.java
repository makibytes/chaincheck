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

import java.io.IOException;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.makibytes.chaincheck.monitor.RpcMonitorService;

/**
 * {@link ChainProtocol} implementation for Cosmos SDK chains using the CometBFT RPC interface.
 *
 * <h3>HTTP transport</h3>
 * CometBFT uses HTTP GET requests rather than JSON-RPC POST.
 * {@link #httpMethod()} returns {@code "GET"}.  The transport layer in
 * {@link de.makibytes.chaincheck.monitor.HttpMonitorService} routes GET-method protocols to a
 * dedicated GET helper that appends the path ({@code RpcRequest.method()}) to the base URL and
 * encodes {@code RpcRequest.params()} as query-string key/value pairs.
 *
 * <ul>
 *   <li>{@code buildBlockNumberRequest()} → {@code GET /status}
 *       → {@code result.sync_info.latest_block_height}</li>
 *   <li>{@code buildBlockByTagRequest("latest")} → {@code GET /block} (latest)</li>
 *   <li>{@code buildBlockByNumberRequest(N)} → {@code GET /block?height=N}</li>
 * </ul>
 *
 * <h3>WebSocket</h3>
 * CometBFT WS lives at {@code /websocket} (users include this in the {@code ws:} YAML field).
 * Subscribes to {@code tm.event='NewBlock'} events; each event contains a full block.
 *
 * <h3>Finality</h3>
 * BFT consensus — every committed block is instantly final.  {@code get-safe-blocks} and
 * {@code get-finalized-blocks} should both be {@code false}.
 *
 * <h3>Block hash encoding</h3>
 * CometBFT hashes are uppercase hex strings without a {@code 0x} prefix
 * (e.g. {@code "ABCDEF1234..."}). The hash is normalised to lowercase when stored so that
 * case differences across nodes don't fragment the chain tracker.
 */
public class CosmosProtocol implements ChainProtocol {

    private final ObjectMapper mapper;

    public CosmosProtocol(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // ── HTTP transport ────────────────────────────────────────────────────

    @Override
    public String httpMethod() {
        return "GET";
    }

    // ── HTTP polling ──────────────────────────────────────────────────────

    @Override
    public RpcRequest buildBlockNumberRequest() {
        // GET /status — transport appends /status to the base URL
        return new RpcRequest("/status", mapper.createObjectNode());
    }

    @Override
    public Long parseBlockNumberResponse(JsonNode result) throws IOException {
        if (result == null || result.isNull()) {
            return null;
        }
        // result.sync_info.latest_block_height is a string
        String heightStr = result.path("sync_info").path("latest_block_height").asText(null);
        if (heightStr == null || heightStr.isBlank()) {
            return null;
        }
        return Long.parseLong(heightStr);
    }

    @Override
    public RpcRequest buildBlockByTagRequest(String tag) {
        // CometBFT has no safe/finalized distinction; "latest" omits the height param.
        // Ignore "safe" and "finalized" tags — callers should disable those in YAML.
        return new RpcRequest("/block", mapper.createObjectNode());
    }

    @Override
    public RpcMonitorService.BlockInfo parseBlockByTagResponse(JsonNode result, String tag) throws IOException {
        return parseBlockResult(result);
    }

    @Override
    public RpcRequest buildBlockByNumberRequest(long number) {
        ObjectNode params = mapper.createObjectNode().put("height", String.valueOf(number));
        return new RpcRequest("/block", params);
    }

    @Override
    public RpcMonitorService.BlockInfo parseBlockByNumberResponse(JsonNode result) throws IOException {
        return parseBlockResult(result);
    }

    // ── WebSocket ─────────────────────────────────────────────────────────

    @Override
    public String subscribeMessage() {
        // CometBFT WS uses method:"subscribe" with a query param (not eth_subscribe)
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"subscribe\",\"params\":{\"query\":\"tm.event='NewBlock'\"}}";
    }

    @Override
    public boolean isNewBlockNotification(JsonNode message) {
        // Subscription ack: { id:1, result:{} }
        // NewBlock events: { id:0, result:{ data:{ type:"tendermint/event/NewBlock", value:{...} } } }
        JsonNode dataType = message.path("result").path("data").path("type");
        return !dataType.isMissingNode() && dataType.asText("").contains("NewBlock");
    }

    @Override
    public BlockEvent parseWsNotification(JsonNode message) throws IOException {
        // Full block lives at result.data.value.block (same shape as GET /block response)
        JsonNode value = message.path("result").path("data").path("value");

        // block_id is at result.data.value.block_id
        String blockHash = value.path("block_id").path("hash").asText(null);
        JsonNode header = value.path("block").path("header");
        String heightStr = header.path("height").asText(null);
        Long height = (heightStr != null && !heightStr.isBlank()) ? Long.parseLong(heightStr) : null;
        String parentHash = header.path("last_block_id").path("hash").asText(null);
        Instant timestamp = parseRfc3339(header.path("time").asText(null));

        if (blockHash != null) {
            blockHash = blockHash.isBlank() ? null : blockHash.toLowerCase();
        }
        if (parentHash != null) {
            parentHash = parentHash.isBlank() ? null : parentHash.toLowerCase();
        }

        return new BlockEvent(
                blockHash != null ? blockHash : (heightStr != null ? heightStr : ""),
                height,
                blockHash,
                parentHash,
                timestamp,
                true);
    }

    @Override
    public boolean requiresHttpFetchAfterWsEvent() {
        return false; // full block is in the WS event
    }

    @Override
    public RpcRequest buildFetchAfterWsEventRequest(BlockEvent event) {
        // Not called since requiresHttpFetchAfterWsEvent() == false
        throw new UnsupportedOperationException("CosmosProtocol WS events carry full block data");
    }

    @Override
    public RpcMonitorService.BlockInfo parseFetchAfterWsEventResponse(JsonNode result) throws IOException {
        throw new UnsupportedOperationException("CosmosProtocol WS events carry full block data");
    }

    // ── Capabilities ──────────────────────────────────────────────────────

    @Override
    public boolean supportsParentHash() {
        return true; // last_block_id.hash serves as parentHash
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private RpcMonitorService.BlockInfo parseBlockResult(JsonNode result) throws IOException {
        if (result == null || result.isNull()) {
            return null;
        }
        // GET /block response: { result: { block_id: { hash }, block: { header: { height, time, last_block_id: { hash } }, data: { txs:[] } } } }
        // The transport unwraps the outer "result" envelope, so `result` here is already the inner object.
        String blockHash = result.path("block_id").path("hash").asText(null);
        JsonNode header = result.path("block").path("header");
        String heightStr = header.path("height").asText(null);
        if (heightStr == null || heightStr.isBlank()) {
            return null;
        }
        Long height = Long.parseLong(heightStr);
        String parentHash = header.path("last_block_id").path("hash").asText(null);
        Instant timestamp = parseRfc3339(header.path("time").asText(null));
        Integer txCount = null;
        JsonNode txs = result.path("block").path("data").path("txs");
        if (txs.isArray()) {
            txCount = txs.size();
        }

        if (blockHash != null) {
            blockHash = blockHash.isBlank() ? null : blockHash.toLowerCase();
        }
        if (parentHash != null) {
            parentHash = parentHash.isBlank() ? null : parentHash.toLowerCase();
        }

        return new RpcMonitorService.BlockInfo(height, blockHash, parentHash, txCount, null, timestamp);
    }

    private static Instant parseRfc3339(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (Exception ex) {
            return null;
        }
    }
}

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
 * {@link ChainProtocol} implementation for Solana.
 *
 * <h3>HTTP mapping</h3>
 * <ul>
 *   <li>{@code buildBlockNumberRequest()} → {@code getSlot} (processed slot)</li>
 *   <li>{@code buildBlockByTagRequest("latest")} → {@code getLatestBlockhash({commitment:"processed"})}</li>
 *   <li>{@code buildBlockByTagRequest("safe")}    → {@code getLatestBlockhash({commitment:"confirmed"})}</li>
 *   <li>{@code buildBlockByTagRequest("finalized")} → {@code getLatestBlockhash({commitment:"finalized"})}</li>
 *   <li>{@code buildBlockByNumberRequest(slot)} → {@code getBlock(slot, ...)}</li>
 * </ul>
 *
 * <h3>WebSocket</h3>
 * Uses {@code slotSubscribe} (lightweight slot notifications).  Each notification triggers a
 * follow-up {@code getBlock} HTTP call for the confirmed block data.
 *
 * <h3>Block identity</h3>
 * Block hashes are base58-encoded strings (not hex).  {@code previousBlockhash} serves as the
 * parent-hash equivalent.  {@link de.makibytes.chaincheck.monitor.ChainTracker} treats hash
 * values as opaque strings, so it works without modification.
 *
 * <h3>Finality</h3>
 * <ul>
 *   <li>{@code processed} ≈ latest (~400 ms)</li>
 *   <li>{@code confirmed} ≈ safe  (~1.6 s, &gt;⅔ supermajority)</li>
 *   <li>{@code finalized} ≈ finalized (~13 s, 32-slot max-lockout)</li>
 * </ul>
 */
public class SolanaProtocol implements ChainProtocol {

    private final ObjectMapper mapper;

    public SolanaProtocol(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // ── HTTP polling ──────────────────────────────────────────────────────

    @Override
    public RpcRequest buildBlockNumberRequest() {
        // Explicitly request the processed slot (highest slot seen by this node).
        // Without a commitment param the Solana RPC defaults to "finalized", which lags
        // ~32 slots (~13 s) behind the actual head — unsuitable for head-delay monitoring.
        return new RpcRequest("getSlot",
                mapper.createArrayNode().add(mapper.createObjectNode().put("commitment", "processed")));
    }

    @Override
    public Long parseBlockNumberResponse(JsonNode result) throws IOException {
        if (result == null || result.isNull()) {
            return null;
        }
        return result.asLong();
    }

    @Override
    public RpcRequest buildBlockByTagRequest(String tag) {
        String commitment = switch (tag) {
            case "safe"      -> "confirmed";
            case "finalized" -> "finalized";
            default          -> "processed";
        };
        ObjectNode params = mapper.createObjectNode().put("commitment", commitment);
        return new RpcRequest("getLatestBlockhash", mapper.createArrayNode().add(params));
    }

    @Override
    public RpcMonitorService.BlockInfo parseBlockByTagResponse(JsonNode result, String tag) throws IOException {
        if (result == null || result.isNull()) {
            return null;
        }
        // getLatestBlockhash returns: { context: { slot }, value: { blockhash, lastValidBlockHeight } }
        Long slot = result.path("context").path("slot").longValue();
        if (slot == 0 && !result.path("context").has("slot")) {
            return null;
        }
        String blockhash = result.path("value").path("blockhash").asText(null);
        if (blockhash == null || blockhash.isBlank()) {
            return null;
        }
        return new RpcMonitorService.BlockInfo(slot, blockhash, null, null, null, null);
    }

    @Override
    public RpcRequest buildBlockByNumberRequest(long slot) {
        ObjectNode config = mapper.createObjectNode()
                .put("encoding", "json")
                .put("commitment", "confirmed")
                .put("maxSupportedTransactionVersion", 0);
        return new RpcRequest("getBlock", mapper.createArrayNode().add(slot).add(config));
    }

    @Override
    public RpcMonitorService.BlockInfo parseBlockByNumberResponse(JsonNode result) throws IOException {
        return parseGetBlockResult(result);
    }

    // ── WebSocket ─────────────────────────────────────────────────────────

    @Override
    public String subscribeMessage() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"slotSubscribe\",\"params\":[]}";
    }

    @Override
    public boolean isNewBlockNotification(JsonNode message) {
        // slotSubscribe notifications have: { jsonrpc, method:"slotNotification", params:{result:{slot,parent,root}} }
        return message.has("method")
                && "slotNotification".equals(message.get("method").asText());
    }

    @Override
    public BlockEvent parseWsNotification(JsonNode message) throws IOException {
        JsonNode slotInfo = message.path("params").path("result");
        long slot = slotInfo.path("slot").asLong();
        if (slot == 0 && !slotInfo.has("slot")) {
            throw new IOException("Solana slotNotification missing slot field");
        }
        // identifier = slot number as string; full block data fetched via HTTP next
        return new BlockEvent(String.valueOf(slot), slot, null, null, null, false);
    }

    @Override
    public boolean requiresHttpFetchAfterWsEvent() {
        return true;
    }

    @Override
    public RpcRequest buildFetchAfterWsEventRequest(BlockEvent event) {
        long slot = Long.parseLong(event.identifier());
        ObjectNode config = mapper.createObjectNode()
                .put("encoding", "json")
                .put("commitment", "confirmed")
                .put("maxSupportedTransactionVersion", 0);
        return new RpcRequest("getBlock", mapper.createArrayNode().add(slot).add(config));
    }

    @Override
    public RpcMonitorService.BlockInfo parseFetchAfterWsEventResponse(JsonNode result) throws IOException {
        return parseGetBlockResult(result);
    }

    // ── Capabilities ──────────────────────────────────────────────────────

    @Override
    public boolean supportsParentHash() {
        return true; // previousBlockhash serves as parentHash
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private RpcMonitorService.BlockInfo parseGetBlockResult(JsonNode result) throws IOException {
        if (result == null || result.isNull()) {
            return null;
        }
        // getBlock result: { blockhash, parentSlot, previousBlockhash, blockTime, transactions:[] }
        String blockhash = result.path("blockhash").asText(null);
        if (blockhash == null || blockhash.isBlank()) {
            return null;
        }
        String previousBlockhash = result.path("previousBlockhash").asText(null);
        long parentSlot = result.path("parentSlot").asLong(0);
        // blockNumber = parentSlot + 1 (this slot's number)
        Long blockNumber = parentSlot + 1;

        Instant blockTime = null;
        JsonNode blockTimeNode = result.path("blockTime");
        if (!blockTimeNode.isMissingNode() && !blockTimeNode.isNull()) {
            blockTime = Instant.ofEpochSecond(blockTimeNode.asLong());
        }

        Integer txCount = null;
        JsonNode txs = result.path("transactions");
        if (txs.isArray()) {
            txCount = txs.size();
        }

        return new RpcMonitorService.BlockInfo(blockNumber, blockhash, previousBlockhash, txCount, null, blockTime);
    }
}

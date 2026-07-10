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

import de.makibytes.chaincheck.monitor.EthHex;
import de.makibytes.chaincheck.monitor.RpcMonitorService;

/**
 * {@link ChainProtocol} implementation for Starknet.
 *
 * <h3>HTTP JSON-RPC</h3>
 * Starknet uses JSON-RPC POST (same transport as EVM) but with {@code starknet_} prefixed methods
 * and a different response shape.  Notably, block numbers are plain integers (not hex).
 *
 * <ul>
 *   <li>{@code buildBlockNumberRequest()} → {@code starknet_blockNumber}</li>
 *   <li>{@code buildBlockByTagRequest("latest")} → {@code starknet_getBlockWithTxHashes(["latest"])}</li>
 *   <li>{@code buildBlockByNumberRequest(N)} → {@code starknet_getBlockWithTxHashes([{block_number:N}])}</li>
 * </ul>
 *
 * <h3>WebSocket</h3>
 * Supported by Pathfinder and Juno nodes via {@code starknet_subscribeNewHeads}.  Not all public
 * endpoints expose WS; ChainCheck falls back to HTTP polling gracefully when WS is not configured.
 *
 * <h3>Finality</h3>
 * <ul>
 *   <li>{@code PENDING} — block being assembled by sequencer</li>
 *   <li>{@code ACCEPTED_ON_L2} — included in Starknet block (≈ "latest")</li>
 *   <li>{@code ACCEPTED_ON_L1} — settled on Ethereum (the {@code l1_accepted} block_id,
 *       JSON-RPC spec ≥ 0.8; lags hours behind L2)</li>
 * </ul>
 * {@code get-safe-blocks} stays {@code false} (no Starknet equivalent). {@code get-finalized-blocks}
 * may be enabled against spec ≥ 0.8 nodes and maps to {@code l1_accepted}; it is off by default
 * because public gateways still run older specs.
 */
public class StarknetProtocol implements ChainProtocol {

    private final ObjectMapper mapper;

    public StarknetProtocol(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // ── HTTP polling ──────────────────────────────────────────────────────

    @Override
    public RpcRequest buildBlockNumberRequest() {
        return new RpcRequest("starknet_blockNumber", mapper.createArrayNode());
    }

    @Override
    public Long parseBlockNumberResponse(JsonNode result) throws IOException {
        if (result == null || result.isNull()) {
            return null;
        }
        // starknet_blockNumber returns a plain integer, not hex
        return result.asLong();
    }

    @Override
    public RpcRequest buildBlockByTagRequest(String tag) {
        // "finalized" maps to l1_accepted (JSON-RPC spec >= 0.8): the newest block settled on
        // Ethereum. Starknet has no "safe" equivalent, so safe falls back to "latest".
        // Positional params: element 0 IS the BLOCK_ID (a bare tag string here) — wrapping it
        // in {"block_id": ...} is rejected by spec-strict gateways ("cannot unmarshal block id").
        String blockId = "finalized".equals(tag) ? "l1_accepted" : "latest";
        return new RpcRequest("starknet_getBlockWithTxHashes", mapper.createArrayNode().add(blockId));
    }

    @Override
    public RpcMonitorService.BlockInfo parseBlockByTagResponse(JsonNode result, String tag) throws IOException {
        return parseStarknetBlock(result);
    }

    @Override
    public RpcRequest buildBlockByNumberRequest(long number) {
        // Positional BLOCK_ID by number: {"block_number": N} directly, not {"block_id": {...}}.
        ObjectNode blockId = mapper.createObjectNode().put("block_number", number);
        return new RpcRequest("starknet_getBlockWithTxHashes", mapper.createArrayNode().add(blockId));
    }

    @Override
    public RpcMonitorService.BlockInfo parseBlockByNumberResponse(JsonNode result) throws IOException {
        return parseStarknetBlock(result);
    }

    // ── WebSocket ─────────────────────────────────────────────────────────

    @Override
    public String subscribeMessage() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"starknet_subscribeNewHeads\",\"params\":{}}";
    }

    @Override
    public boolean isNewBlockNotification(JsonNode message) {
        // Starknet WS sends: { jsonrpc, method:"starknet_subscriptionNewHeads", params:{result:{...}} }
        return message.has("method")
                && "starknet_subscriptionNewHeads".equals(message.get("method").asText());
    }

    @Override
    public BlockEvent parseWsNotification(JsonNode message) throws IOException {
        JsonNode header = message.path("params").path("result");
        RpcMonitorService.BlockInfo info = parseStarknetBlock(header);
        if (info == null) {
            throw new IOException("Cannot parse Starknet subscribeNewHeads payload");
        }
        return new BlockEvent(
                info.blockHash(),
                info.blockNumber(),
                info.blockHash(),
                info.parentHash(),
                info.blockTimestamp(),
                true);
    }

    @Override
    public boolean requiresHttpFetchAfterWsEvent() {
        return false; // full header is in the WS notification
    }

    @Override
    public RpcRequest buildFetchAfterWsEventRequest(BlockEvent event) {
        throw new UnsupportedOperationException("StarknetProtocol WS events carry full block header");
    }

    @Override
    public RpcMonitorService.BlockInfo parseFetchAfterWsEventResponse(JsonNode result) throws IOException {
        throw new UnsupportedOperationException("StarknetProtocol WS events carry full block header");
    }

    // ── Capabilities ──────────────────────────────────────────────────────

    @Override
    public boolean supportsParentHash() {
        return true; // parent_hash field is present in every Starknet block
    }

    // ── Node health probe ─────────────────────────────────────────────────

    @Override
    public java.util.Optional<RpcRequest> buildHealthRequest() {
        // starknet_syncing returns false when synced, or a status object with
        // current_block_num / highest_block_num while catching up.
        return java.util.Optional.of(new RpcRequest("starknet_syncing", mapper.createArrayNode()));
    }

    @Override
    public Integer parseHealthSlotsBehind(JsonNode envelope) {
        if (envelope == null) {
            return null;
        }
        JsonNode error = envelope.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            // Many public Starknet gateways don't implement starknet_syncing; treating
            // "method not found" as unhealthy would open a permanent false SYNC_LAG.
            if (error.path("code").asInt(0) == -32601) {
                return null;
            }
            return -1;
        }
        JsonNode result = envelope.path("result");
        if (result.isMissingNode() || result.isNull()) {
            return null;
        }
        if (result.isBoolean()) {
            return result.asBoolean() ? -1 : 0; // false = fully synced
        }
        if (result.isObject()) {
            Long current = parseBlockNum(result.path("current_block_num"));
            Long highest = parseBlockNum(result.path("highest_block_num"));
            if (current == null || highest == null) {
                return -1; // syncing, but the margin is unreadable
            }
            long behind = highest - current;
            if (behind <= 0) {
                return 0;
            }
            return behind > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) behind;
        }
        return null;
    }

    /** Sync-status block numbers are integers per spec, but some nodes serve hex strings. */
    private static Long parseBlockNum(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isTextual()) {
            return EthHex.parseDecimalOrHexLong(node.asText());
        }
        return null;
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private RpcMonitorService.BlockInfo parseStarknetBlock(JsonNode result) throws IOException {
        if (result == null || result.isNull()) {
            return null;
        }
        // starknet_getBlockWithTxHashes result:
        // { block_number, block_hash, parent_hash, timestamp, status, transactions:[], l1_gas_price:{price_in_wei} }
        JsonNode blockNumberNode = result.path("block_number");
        if (blockNumberNode.isMissingNode() || blockNumberNode.isNull()) {
            return null;
        }
        Long blockNumber = blockNumberNode.asLong();
        String blockHash = result.path("block_hash").asText(null);
        String parentHash = result.path("parent_hash").asText(null);

        Instant timestamp = null;
        JsonNode tsNode = result.path("timestamp");
        if (!tsNode.isMissingNode() && !tsNode.isNull()) {
            timestamp = Instant.ofEpochSecond(tsNode.asLong());
        }

        Integer txCount = null;
        JsonNode txs = result.path("transactions");
        if (txs.isArray()) {
            txCount = txs.size();
        }

        Long gasPriceWei = null;
        String priceHex = result.path("l1_gas_price").path("price_in_wei").asText(null);
        if (priceHex != null && !priceHex.isBlank()) {
            gasPriceWei = EthHex.parseLong(priceHex);
        }

        return new RpcMonitorService.BlockInfo(blockNumber, blockHash, parentHash, txCount, gasPriceWei, timestamp);
    }
}

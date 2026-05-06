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
 *   <li>{@code buildBlockByTagRequest("latest")} → {@code starknet_getBlockWithTxHashes({block_id:"latest"})}</li>
 *   <li>{@code buildBlockByNumberRequest(N)} → {@code starknet_getBlockWithTxHashes({block_id:{block_number:N}})}</li>
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
 *   <li>{@code ACCEPTED_ON_L1} — settled on Ethereum (takes hours; not tracked by default)</li>
 * </ul>
 * Set {@code get-safe-blocks: false} and {@code get-finalized-blocks: false} in YAML.
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
        // Starknet has no safe tag; all queries use "latest"
        ObjectNode params = mapper.createObjectNode().put("block_id", "latest");
        return new RpcRequest("starknet_getBlockWithTxHashes", mapper.createArrayNode().add(params));
    }

    @Override
    public RpcMonitorService.BlockInfo parseBlockByTagResponse(JsonNode result, String tag) throws IOException {
        return parseStarknetBlock(result);
    }

    @Override
    public RpcRequest buildBlockByNumberRequest(long number) {
        ObjectNode blockId = mapper.createObjectNode().put("block_number", number);
        ObjectNode params = mapper.createObjectNode().set("block_id", blockId);
        return new RpcRequest("starknet_getBlockWithTxHashes", mapper.createArrayNode().add(params));
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

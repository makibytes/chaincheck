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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.makibytes.chaincheck.config.ChainCheckProperties.ModeType;
import de.makibytes.chaincheck.monitor.EthHex;
import de.makibytes.chaincheck.monitor.RpcMonitorService;

/**
 * {@link ChainProtocol} implementation for all EVM-compatible chains.
 *
 * <p>Two sub-modes exist within EVM:
 * <ul>
 *   <li>{@link ModeType#ETHEREUM} — verifies each newHeads event with a follow-up
 *       {@code eth_getBlockByHash} call to obtain a reliable block number.</li>
 *   <li>All other EVM modes (COSMOS, OPTIMISM, ZK, AVALANCHE, TRON) — trust the WS event
 *       payload directly; no follow-up HTTP fetch is needed.</li>
 * </ul>
 */
public class EvmProtocol implements ChainProtocol {

    private final ObjectMapper mapper;
    private final boolean verifyViaHttpAfterWsEvent;

    public EvmProtocol(ObjectMapper mapper, ModeType modeType) {
        this.mapper = mapper;
        this.verifyViaHttpAfterWsEvent = (modeType == ModeType.ETHEREUM);
    }

    // ── HTTP polling ──────────────────────────────────────────────────────

    @Override
    public RpcRequest buildBlockNumberRequest() {
        return new RpcRequest("eth_blockNumber", mapper.createArrayNode());
    }

    @Override
    public Long parseBlockNumberResponse(JsonNode result) throws IOException {
        if (result == null || result.isNull()) {
            return null;
        }
        return EthHex.parseLong(result.asText());
    }

    @Override
    public RpcRequest buildBlockByTagRequest(String tag) {
        return new RpcRequest("eth_getBlockByNumber",
                mapper.createArrayNode().add(tag).add(false));
    }

    @Override
    public RpcMonitorService.BlockInfo parseBlockByTagResponse(JsonNode result, String tag) throws IOException {
        return EthHex.parseBlockFields(result);
    }

    @Override
    public RpcRequest buildBlockByNumberRequest(long number) {
        return new RpcRequest("eth_getBlockByNumber",
                mapper.createArrayNode().add("0x" + Long.toHexString(number)).add(false));
    }

    @Override
    public RpcMonitorService.BlockInfo parseBlockByNumberResponse(JsonNode result) throws IOException {
        return EthHex.parseBlockFields(result);
    }

    // ── WebSocket ─────────────────────────────────────────────────────────

    @Override
    public String subscribeMessage() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"eth_subscribe\",\"params\":[\"newHeads\"]}";
    }

    @Override
    public boolean isNewBlockNotification(JsonNode message) {
        return message.has("method")
                && "eth_subscription".equals(message.get("method").asText());
    }

    @Override
    public BlockEvent parseWsNotification(JsonNode message) throws IOException {
        JsonNode result = message.path("params").path("result");
        RpcMonitorService.BlockInfo info = EthHex.parseBlockFields(result);
        if (info == null) {
            throw new IOException("Cannot parse EVM newHeads payload");
        }
        // For Ethereum: identifier = block hash (passed to eth_getBlockByHash)
        // For other EVM: hasFullData = true (we use the parsed values directly)
        return new BlockEvent(
                info.blockHash(),
                info.blockNumber(),
                info.blockHash(),
                info.parentHash(),
                info.blockTimestamp(),
                !verifyViaHttpAfterWsEvent);
    }

    @Override
    public boolean requiresHttpFetchAfterWsEvent() {
        return verifyViaHttpAfterWsEvent;
    }

    @Override
    public RpcRequest buildFetchAfterWsEventRequest(BlockEvent event) {
        return new RpcRequest("eth_getBlockByHash",
                mapper.createArrayNode().add(event.identifier()).add(false));
    }

    @Override
    public RpcMonitorService.BlockInfo parseFetchAfterWsEventResponse(JsonNode result) throws IOException {
        return EthHex.parseBlockFields(result);
    }

    // ── Capabilities ──────────────────────────────────────────────────────

    @Override
    public boolean supportsParentHash() {
        return true;
    }
}

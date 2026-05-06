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

import de.makibytes.chaincheck.monitor.RpcMonitorService;

/**
 * Abstracts all protocol-specific RPC mechanics so that {@link de.makibytes.chaincheck.monitor.HttpMonitorService}
 * and {@link de.makibytes.chaincheck.monitor.WsMonitorService} are protocol-agnostic.
 *
 * <p>Implementations exist for: EVM ({@link EvmProtocol}), Solana ({@link SolanaProtocol}),
 * Cosmos SDK / CometBFT ({@link CosmosProtocol}), and Starknet ({@link StarknetProtocol}).
 */
public interface ChainProtocol {

    // ── HTTP transport hint ───────────────────────────────────────────────

    /**
     * HTTP method used for RPC calls.  {@code "POST"} for JSON-RPC protocols (EVM, Solana,
     * Starknet); {@code "GET"} for CometBFT REST.
     */
    default String httpMethod() { return "POST"; }

    // ── HTTP polling ──────────────────────────────────────────────────────

    /** RPC call that returns the current block height / slot number. */
    RpcRequest buildBlockNumberRequest();
    Long parseBlockNumberResponse(JsonNode result) throws IOException;

    /**
     * RPC call for a block by commitment/tag.
     * Recognised tag values: {@code "latest"}, {@code "safe"}, {@code "finalized"}.
     * Implementations map these to chain-specific equivalents.
     */
    RpcRequest buildBlockByTagRequest(String tag);

    /** Parses the response to {@link #buildBlockByTagRequest}. */
    RpcMonitorService.BlockInfo parseBlockByTagResponse(JsonNode result, String tag) throws IOException;

    /** RPC call for a specific block by number / slot (used by gap-recovery). */
    RpcRequest buildBlockByNumberRequest(long number);
    RpcMonitorService.BlockInfo parseBlockByNumberResponse(JsonNode result) throws IOException;

    // ── WebSocket ─────────────────────────────────────────────────────────

    /** Subscription frame sent to the node on WebSocket connect. */
    String subscribeMessage();

    /**
     * Returns {@code true} if {@code message} is a new-block notification (not an ack,
     * heartbeat, or unrelated subscription message).
     */
    boolean isNewBlockNotification(JsonNode message);

    /**
     * Parses a WS notification into a normalised {@link BlockEvent}.
     * When {@link #requiresHttpFetchAfterWsEvent()} is {@code true} the event's
     * {@code identifier} field carries the value needed to build the follow-up HTTP fetch
     * (block hash for EVM, slot string for Solana).
     */
    BlockEvent parseWsNotification(JsonNode message) throws IOException;

    /**
     * Whether an extra HTTP fetch is needed after each WS notification to obtain full
     * block data.
     * <ul>
     *   <li>{@code true}  — Ethereum (getBlockByHash after newHeads), Solana (getBlock after slotSubscribe)</li>
     *   <li>{@code false} — Cosmos SDK (full block in NewBlock event), Starknet (full header in subscribeNewHeads)</li>
     * </ul>
     */
    boolean requiresHttpFetchAfterWsEvent();

    /**
     * Builds the follow-up HTTP fetch triggered by a WS notification.
     * Only called when {@link #requiresHttpFetchAfterWsEvent()} is {@code true}.
     */
    RpcRequest buildFetchAfterWsEventRequest(BlockEvent event);

    /** Parses the response to {@link #buildFetchAfterWsEventRequest}. */
    RpcMonitorService.BlockInfo parseFetchAfterWsEventResponse(JsonNode result) throws IOException;

    // ── Capabilities ──────────────────────────────────────────────────────

    /** Whether blocks carry a parent-block identifier for chain-linkage (reorg detection). */
    boolean supportsParentHash();
}

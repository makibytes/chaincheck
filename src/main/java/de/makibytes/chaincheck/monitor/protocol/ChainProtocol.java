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

    /** How a JSON-RPC error from the follow-up fetch after a WS event should be handled. */
    enum FetchRetryAction {
        /** Transient — the block is expected to become available shortly; retry the fetch. */
        RETRY,
        /** Permanent but benign (e.g. a skipped Solana slot); drop the event silently. */
        SKIP,
        /** A real failure; record it against the node (the default). */
        FAIL
    }

    /**
     * Classifies a JSON-RPC error returned by the follow-up fetch after a WS event.
     * Solana's {@code slotSubscribe} notifies at <em>processed</em> commitment while
     * {@code getBlock} serves <em>confirmed</em> blocks, so the first fetch attempt commonly
     * races confirmation ({@code -32004}) and slots may be skipped outright ({@code -32007}).
     * The default treats every error as a genuine failure, which matches EVM semantics where
     * a node must be able to serve a block it just announced.
     */
    default FetchRetryAction classifyFetchAfterWsEventError(int errorCode, String errorMessage) {
        return FetchRetryAction.FAIL;
    }

    // ── Capabilities ──────────────────────────────────────────────────────

    /** Whether blocks carry a parent-block identifier for chain-linkage (reorg detection). */
    boolean supportsParentHash();

    // ── Node health probe (optional) ──────────────────────────────────────

    /**
     * Optional per-node self-health probe. Solana exposes {@code getHealth}, which reports
     * whether the node is within {@code HEALTH_CHECK_SLOT_DISTANCE} of the cluster tip — a
     * direct "is this node keeping up?" signal that other chains can only approximate via
     * cross-node comparison. Returns empty for chains without such a probe (the default).
     */
    default java.util.Optional<RpcRequest> buildHealthRequest() {
        return java.util.Optional.empty();
    }

    /**
     * Parses the health-probe response. Receives the full JSON-RPC envelope
     * ({@code {id,result}} or {@code {id,error}}) because some chains report the lag inside
     * the error object (Solana returns code {@code -32005} with {@code data.numSlotsBehind}).
     *
     * @return slots/blocks behind the cluster tip: {@code 0} when healthy, a positive count
     *         when behind, {@code -1} when the node reports unhealthy without a parseable
     *         count, or {@code null} when no health information is available.
     */
    default Integer parseHealthSlotsBehind(JsonNode envelope) {
        return null;
    }

    // ── Node metadata probes (optional, polled infrequently) ──────────────

    /**
     * Optional node software-version probe (Solana {@code getVersion}). Returns empty for
     * chains where ChainCheck does not track a version. Polled on a slow cadence since the
     * value changes only across node restarts/upgrades.
     */
    default java.util.Optional<RpcRequest> buildVersionRequest() {
        return java.util.Optional.empty();
    }

    /** Parses the version-probe {@code result} into a display string, or {@code null}. */
    default String parseVersion(JsonNode result) {
        return null;
    }

    /**
     * Optional network-performance probe (Solana {@code getRecentPerformanceSamples}). Each
     * node answers from its own view, so divergent readings are themselves a signal. Polled
     * on a slow cadence. Returns empty for chains without such a method.
     */
    default java.util.Optional<RpcRequest> buildPerformanceRequest() {
        return java.util.Optional.empty();
    }

    /**
     * Parses the performance-probe {@code result} into observed network throughput.
     * @return {@code [tps, meanSlotTimeMs]}, either element nullable, or {@code null} if
     *         the response carries no usable samples.
     */
    default double[] parsePerformance(JsonNode result) {
        return null;
    }
}

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

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.makibytes.chaincheck.monitor.RpcMonitorService;

/**
 * {@link ChainProtocol} implementation for NEAR Protocol.
 *
 * <h3>HTTP JSON-RPC</h3>
 * NEAR exposes JSON-RPC over HTTP and WebSocket. The monitoring path uses the standard
 * {@code status} call for the current height and {@code block} for block-by-height/finality lookups.
 *
 * <h3>WebSocket</h3>
 * NEAR WebSocket subscriptions are exposed via the experimental {@code EXPERIMENTAL_subscription}
 * RPC, which can deliver new block headers as they are produced.
 *
 * <h3>Finality</h3>
 * NEAR uses two practical finality levels: {@code optimistic} (latest / safe-ish) and {@code final}
 * (finalized). ChainCheck maps {@code latest} to {@code optimistic}, {@code safe} to the same
 * optimistic level, and {@code finalized} to {@code final}.
 *
 * <h3>Metadata probes</h3>
 * Node version is extracted from the {@code status} response ({@code version.version} field).
 * Since the health probe already calls {@code status}, the version probe reuses the same method
 * — the batch layer deduplicates identical requests when they share the same id-in-batch.
 */
public class NearProtocol implements ChainProtocol {

    private final ObjectMapper mapper;

    public NearProtocol(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public RpcRequest buildBlockNumberRequest() {
        return new RpcRequest("status", mapper.createArrayNode());
    }

    @Override
    public Long parseBlockNumberResponse(JsonNode result) throws IOException {
        if (result == null || result.isNull()) {
            return null;
        }
        JsonNode syncInfo = result.path("sync_info");
        if (syncInfo.isMissingNode() || syncInfo.isNull()) {
            return null;
        }
        JsonNode latestHeight = syncInfo.path("latest_block_height");
        return parseLongValue(latestHeight);
    }

    @Override
    public RpcRequest buildBlockByTagRequest(String tag) {
        String finality = switch (tag) {
            case "finalized" -> "final";
            case "safe" -> "optimistic";
            default -> "optimistic";
        };
        // NEAR block method expects named params: {"finality": "..."} — not positional.
        ObjectNode params = mapper.createObjectNode().put("finality", finality);
        return new RpcRequest("block", params);
    }

    @Override
    public RpcMonitorService.BlockInfo parseBlockByTagResponse(JsonNode result, String tag) throws IOException {
        return parseBlock(result);
    }

    @Override
    public RpcRequest buildBlockByNumberRequest(long number) {
        // NEAR block method expects named params: {"block_id": N} — not positional.
        ObjectNode params = mapper.createObjectNode().put("block_id", number);
        return new RpcRequest("block", params);
    }

    @Override
    public RpcMonitorService.BlockInfo parseBlockByNumberResponse(JsonNode result) throws IOException {
        return parseBlock(result);
    }

    @Override
    public String subscribeMessage() {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"subscribe-blocks\",\"method\":\"EXPERIMENTAL_subscription\",\"params\":{\"subscriptionType\":\"blocks\"}}";
    }

    @Override
    public boolean isNewBlockNotification(JsonNode message) {
        if (message == null || message.isNull() || !message.has("method")) {
            return false;
        }
        return "EXPERIMENTAL_subscription".equals(message.get("method").asText())
                && "blocks".equals(message.path("params").path("subscriptionType").asText(null));
    }

    @Override
    public BlockEvent parseWsNotification(JsonNode message) throws IOException {
        JsonNode result = message.path("params").path("result");
        JsonNode header = result.path("header");
        String blockHash = header.path("hash").asText(null);
        if (blockHash == null || blockHash.isBlank()) {
            throw new IOException("NEAR subscription payload missing block hash");
        }
        Long blockNumber = parseLongValue(header.path("height"));
        String parentHash = header.path("prev_hash").asText(null);
        Instant timestamp = parseTimestamp(header.path("timestamp"));
        return new BlockEvent(blockHash, blockNumber, blockHash, parentHash, timestamp, true);
    }

    @Override
    public boolean requiresHttpFetchAfterWsEvent() {
        return false;
    }

    @Override
    public RpcRequest buildFetchAfterWsEventRequest(BlockEvent event) {
        throw new UnsupportedOperationException("NEAR subscription payloads already carry the block header");
    }

    @Override
    public RpcMonitorService.BlockInfo parseFetchAfterWsEventResponse(JsonNode result) throws IOException {
        throw new UnsupportedOperationException("NEAR subscription payloads already carry the block header");
    }

    @Override
    public boolean supportsParentHash() {
        return true;
    }

    @Override
    public Optional<RpcRequest> buildHealthRequest() {
        return Optional.of(new RpcRequest("status", mapper.createArrayNode()));
    }

    @Override
    public Integer parseHealthSlotsBehind(JsonNode envelope) {
        if (envelope == null || envelope.isNull()) {
            return null;
        }
        JsonNode error = envelope.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            return -1;
        }
        JsonNode result = envelope.path("result");
        if (result.isMissingNode() || result.isNull()) {
            return null;
        }
        JsonNode syncInfo = result.path("sync_info");
        if (syncInfo.isMissingNode() || syncInfo.isNull()) {
            return null;
        }
        if (!syncInfo.has("syncing")) {
            return null;
        }
        if (!syncInfo.path("syncing").asBoolean(false)) {
            return 0;
        }
        Long latest = parseLongValue(syncInfo.path("latest_block_height"));
        Long earliest = parseLongValue(syncInfo.path("earliest_block_height"));
        if (latest == null || earliest == null) {
            return -1;
        }
        long behind = latest - earliest;
        return behind > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) behind;
    }

    // ── Node metadata probes ──────────────────────────────────────────────

    @Override
    public Optional<RpcRequest> buildVersionRequest() {
        // Reuses the status endpoint — the batch layer sends a single request when the same
        // method+params appears multiple times (block-number, health, and version all call status).
        return Optional.of(new RpcRequest("status", mapper.createArrayNode()));
    }

    @Override
    public String parseVersion(JsonNode result) {
        if (result == null || result.isNull()) {
            return null;
        }
        // NEAR status result: { "version": { "version": "1.35.0", "build": "...", "rustc_version": "..." }, ... }
        JsonNode versionNode = result.path("version").path("version");
        if (versionNode.isMissingNode() || versionNode.isNull()) {
            return null;
        }
        String version = versionNode.asText(null);
        if (version == null || version.isBlank()) {
            return null;
        }
        return "nearcore/" + version;
    }

    private RpcMonitorService.BlockInfo parseBlock(JsonNode result) throws IOException {
        if (result == null || result.isNull()) {
            return null;
        }
        JsonNode header = result.path("header");
        if (header.isMissingNode() || header.isNull()) {
            return null;
        }
        Long blockNumber = parseLongValue(header.path("height"));
        if (blockNumber == null) {
            return null;
        }
        String blockHash = header.path("hash").asText(null);
        if (blockHash == null || blockHash.isBlank()) {
            return null;
        }
        String parentHash = header.path("prev_hash").asText(null);
        Instant timestamp = parseTimestamp(header.path("timestamp"));

        // NEAR blocks include a chunks array with per-shard chunk headers.  When the full
        // block result is returned (not just the header), sum gas_used across chunks as an
        // activity indicator — ChainCheck stores this in the txCount field.  Chunks with
        // gas_used == 0 are empty shards.
        Integer txCount = null;
        JsonNode chunks = result.path("chunks");
        if (chunks.isArray() && !chunks.isEmpty()) {
            int activeChunks = 0;
            for (JsonNode chunk : chunks) {
                long gas = chunk.path("gas_used").asLong(0);
                if (gas > 0) {
                    activeChunks++;
                }
            }
            // Use active-chunk count as a transaction-activity proxy.  A more precise count
            // would require fetching each chunk individually (too expensive for monitoring).
            txCount = activeChunks;
        }

        return new RpcMonitorService.BlockInfo(blockNumber, blockHash, parentHash, txCount, null, timestamp);
    }

    private static Long parseLongValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isTextual()) {
            return Long.parseLong(node.asText());
        }
        return null;
    }

    private static Instant parseTimestamp(JsonNode node) {
        Long raw = parseLongValue(node);
        if (raw == null) {
            return null;
        }
        // NEAR block timestamps are expected to be in nanoseconds since the Unix epoch, but the
        // thresholds below defensively accept millisecond- or second-based values from non-compliant
        // or partially parsed payloads.
        // Values above 1e15 are treated as nanoseconds (e.g. 1717521123546789000).
        if (raw > 1_000_000_000_000_000L) {
            return Instant.ofEpochMilli(raw / 1_000_000L);
        }
        // Values above 1e12 are treated as milliseconds (e.g. 1717521123546).
        if (raw > 1_000_000_000_000L) {
            return Instant.ofEpochMilli(raw);
        }
        // Values below that are treated as whole seconds since the Unix epoch.
        return Instant.ofEpochSecond(raw);
    }
}

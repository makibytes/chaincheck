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
        ObjectNode config = blockFetchConfig();
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
        ObjectNode config = blockFetchConfig();
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

    // ── Node health probe ─────────────────────────────────────────────────

    @Override
    public java.util.Optional<RpcRequest> buildHealthRequest() {
        // getHealth takes no params; a healthy node returns "ok", an unhealthy one returns
        // error -32005 with data.numSlotsBehind. This is the canonical Solana liveness check.
        return java.util.Optional.of(new RpcRequest("getHealth", mapper.createArrayNode()));
    }

    @Override
    public Integer parseHealthSlotsBehind(JsonNode envelope) {
        if (envelope == null) {
            return null;
        }
        JsonNode error = envelope.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            // Unhealthy. Preferred: { code:-32005, data:{ numSlotsBehind:N } }.
            JsonNode numBehind = error.path("data").path("numSlotsBehind");
            if (numBehind.isIntegralNumber()) {
                return numBehind.asInt();
            }
            // Fallback: parse "...behind by N slots" from the message text.
            Integer fromMessage = extractSlotsBehind(error.path("message").asText(null));
            if (fromMessage != null) {
                return fromMessage;
            }
            // Errored but no count available — unhealthy by an unknown margin.
            return -1;
        }
        JsonNode result = envelope.path("result");
        if (result.isTextual() && "ok".equalsIgnoreCase(result.asText())) {
            return 0;
        }
        // Some nodes answer "behind"/"unknown" as a plain string instead of an error.
        if (result.isTextual()) {
            String text = result.asText();
            Integer fromText = extractSlotsBehind(text);
            if (fromText != null) {
                return fromText;
            }
            if ("behind".equalsIgnoreCase(text) || "unknown".equalsIgnoreCase(text)) {
                return -1;
            }
        }
        return null;
    }

    private static Integer extractSlotsBehind(String message) {
        if (message == null) {
            return null;
        }
        java.util.regex.Matcher m = SLOTS_BEHIND_PATTERN.matcher(message);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private static final java.util.regex.Pattern SLOTS_BEHIND_PATTERN =
            java.util.regex.Pattern.compile("behind by (\\d+) slots?");

    // ── Node metadata probes ──────────────────────────────────────────────

    @Override
    public java.util.Optional<RpcRequest> buildVersionRequest() {
        return java.util.Optional.of(new RpcRequest("getVersion", mapper.createArrayNode()));
    }

    @Override
    public String parseVersion(JsonNode result) {
        if (result == null || result.isNull()) {
            return null;
        }
        // { "solana-core": "2.1.21", "feature-set": 1416569292 }
        String core = result.path("solana-core").asText(null);
        if (core == null || core.isBlank()) {
            return null;
        }
        return core;
    }

    @Override
    public java.util.Optional<RpcRequest> buildPerformanceRequest() {
        // Request a single most-recent 60s sample (the result is in reverse-slot order).
        return java.util.Optional.of(
                new RpcRequest("getRecentPerformanceSamples", mapper.createArrayNode().add(1)));
    }

    @Override
    public double[] parsePerformance(JsonNode result) {
        if (result == null || !result.isArray() || result.isEmpty()) {
            return null;
        }
        JsonNode sample = result.get(0); // most recent
        double periodSecs = sample.path("samplePeriodSecs").asDouble(0);
        if (periodSecs <= 0) {
            return null;
        }
        long numTransactions = sample.path("numTransactions").asLong(0);
        long numSlots = sample.path("numSlots").asLong(0);
        // Headline network throughput (includes vote transactions, the conventional TPS figure).
        Double tps = numTransactions > 0 ? numTransactions / periodSecs : null;
        // Mean slot time over the window: period / slots produced.
        Double slotTimeMs = numSlots > 0 ? (periodSecs * 1000.0) / numSlots : null;
        if (tps == null && slotTimeMs == null) {
            return null;
        }
        return new double[] {
                tps == null ? Double.NaN : tps,
                slotTimeMs == null ? Double.NaN : slotTimeMs
        };
    }

    // ── Internal ──────────────────────────────────────────────────────────

    /**
     * Shared {@code getBlock} config.  ChainCheck only needs the block header, so
     * {@code transactionDetails: "signatures"} and {@code rewards: false} are used to keep the
     * response to a few KB instead of the multi-MB payload that full transaction JSON produces
     * on mainnet — important at Solana's ~400 ms slot cadence and for provider quotas.
     * The block-level {@code signatures} array still yields the transaction count.
     */
    private ObjectNode blockFetchConfig() {
        return mapper.createObjectNode()
                .put("encoding", "json")
                .put("commitment", "confirmed")
                .put("transactionDetails", "signatures")
                .put("rewards", false)
                .put("maxSupportedTransactionVersion", 0);
    }

    private RpcMonitorService.BlockInfo parseGetBlockResult(JsonNode result) throws IOException {
        if (result == null || result.isNull()) {
            return null;
        }
        // getBlock result with transactionDetails="signatures":
        // { blockhash, parentSlot, previousBlockhash, blockTime, signatures:[] }
        // (a "transactions" array appears instead when full details are requested)
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
        JsonNode sigs = result.path("signatures");
        JsonNode txs = result.path("transactions");
        if (sigs.isArray()) {
            txCount = sigs.size();
        } else if (txs.isArray()) {
            txCount = txs.size();
        }

        return new RpcMonitorService.BlockInfo(blockNumber, blockhash, previousBlockhash, txCount, null, blockTime);
    }
}

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
package de.makibytes.chaincheck.reference.node;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.makibytes.chaincheck.config.ChainCheckProperties;
import de.makibytes.chaincheck.model.AttestationConfidence;
import de.makibytes.chaincheck.reference.attestation.AttestationTracker;
import de.makibytes.chaincheck.reference.block.ReferenceBlocks.Confidence;

public class ConsensusNodeClient {

    private static final Logger logger = LoggerFactory.getLogger(ConsensusNodeClient.class);

    private final String baseUrl;
    private final String eventsPath;
    private final String finalityCheckpointsPath;
    private final String committeesPath;
    private final long attestationTrackingIntervalMs;
    private final int attestationTrackingMaxAttempts;
    private final Long safePollIntervalMs;
    private final Long finalizedPollIntervalMs;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean streamRunning = new AtomicBoolean(false);
    private final Map<String, ExecutionBlock> executionBlockCache = new ConcurrentHashMap<>();
    private final Map<Long, ExecutionBlock> executionBlockBySlot = new ConcurrentHashMap<>();
    private final AttestationTracker attestationTracker;
    private final Deque<AttestationTracking> attestationQueue = new ConcurrentLinkedDeque<>();
    private final Map<Long, AttestationTracking> attestationTrackingBySlot = new ConcurrentHashMap<>();

    private final AtomicReference<ReferenceObservation> head = new AtomicReference<>();
    private final AtomicReference<ReferenceObservation> safe = new AtomicReference<>();
    private final AtomicReference<ReferenceObservation> finalized = new AtomicReference<>();
    private final Map<Confidence, Deque<ReferenceObservation>> historyByConfidence = new ConcurrentHashMap<>();
    private static final Duration HISTORY_RETENTION = Duration.ofDays(35);

    private static final long CHECKPOINT_TIMEOUT_MS = 60_000; // 1 minute
    private final AtomicReference<Instant> safeFirstRequestAt = new AtomicReference<>();
    private final AtomicReference<Instant> finalizedFirstRequestAt = new AtomicReference<>();

    ConsensusNodeClient(ChainCheckProperties.Consensus referenceNode) {
        this(referenceNode, null);
    }

    ConsensusNodeClient(ChainCheckProperties.Consensus referenceNode, AttestationTracker attestationTracker) {
        this.baseUrl = trimTrailingSlash(referenceNode == null ? null : referenceNode.getHttp());
        this.eventsPath = sanitizePath(referenceNode == null ? null : referenceNode.getEventsPath(), null);
        this.finalityCheckpointsPath = sanitizePath(referenceNode == null ? null : referenceNode.getFinalityCheckpointsPath(), null);
        this.committeesPath = sanitizePath(referenceNode == null ? null : referenceNode.getCommitteesPath(), "/eth/v1/beacon/states/head/committees");
        this.attestationTrackingIntervalMs = normalizeTrackingInterval(referenceNode == null ? null : referenceNode.getAttestationTrackingIntervalMs());
        this.attestationTrackingMaxAttempts = normalizeTrackingAttempts(referenceNode == null ? null : referenceNode.getAttestationTrackingMaxAttempts());
        this.safePollIntervalMs = normalizePollInterval(referenceNode == null ? null : referenceNode.getSafePollIntervalMs());
        this.finalizedPollIntervalMs = normalizePollInterval(referenceNode == null ? null : referenceNode.getFinalizedPollIntervalMs());
        this.attestationTracker = attestationTracker;
        long timeoutMs = Math.max(500, referenceNode == null ? 2000 : referenceNode.getTimeoutMs());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
        historyByConfidence.put(Confidence.NEW, new ConcurrentLinkedDeque<>());
        historyByConfidence.put(Confidence.SAFE, new ConcurrentLinkedDeque<>());
        historyByConfidence.put(Confidence.FINALIZED, new ConcurrentLinkedDeque<>());
    }

    boolean isEnabled() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    Long getSafePollIntervalMs() {
        return safePollIntervalMs;
    }

    Long getFinalizedPollIntervalMs() {
        return finalizedPollIntervalMs;
    }

    boolean isSafePollingEnabled() {
        return safePollIntervalMs != null;
    }

    boolean isFinalizedPollingEnabled() {
        return finalizedPollIntervalMs != null;
    }

    void ensureEventStream() {
        if (!isEnabled()) {
            return;
        }
        if (!streamRunning.compareAndSet(false, true)) {
            return;
        }
        executor.submit(this::runEventLoop);
        if (attestationTracker != null) {
            executor.submit(this::runAttestationLoop);
        }
    }

    boolean refreshCheckpoints(boolean refreshSafe, boolean refreshFinalized) {
        if (!isEnabled()) {
            return false;
        }
        if (!refreshSafe && !refreshFinalized) {
            return false;
        }

        Instant now = Instant.now();

        // Track first request time for each checkpoint type
        if (refreshSafe && safe.get() == null) {
            safeFirstRequestAt.compareAndSet(null, now);
            Instant firstRequest = safeFirstRequestAt.get();
            if (Duration.between(firstRequest, now).toMillis() > CHECKPOINT_TIMEOUT_MS) {
                String errorMsg = String.format(
                        "Timeout fetching safe checkpoint from consensus node %s after %d ms. " +
                        "Endpoint: %s%s. Check that the consensus node is running and accessible.",
                        baseUrl, CHECKPOINT_TIMEOUT_MS, baseUrl, finalityCheckpointsPath);
                logger.error(errorMsg);
                throw new RuntimeException(errorMsg);
            }
        }

        if (refreshFinalized && finalized.get() == null) {
            finalizedFirstRequestAt.compareAndSet(null, now);
            Instant firstRequest = finalizedFirstRequestAt.get();
            if (Duration.between(firstRequest, now).toMillis() > CHECKPOINT_TIMEOUT_MS) {
                String errorMsg = String.format(
                        "Timeout fetching finalized checkpoint from consensus node %s after %d ms. " +
                        "Endpoint: %s%s. Check that the consensus node is running and accessible.",
                        baseUrl, CHECKPOINT_TIMEOUT_MS, baseUrl, finalityCheckpointsPath);
                logger.error(errorMsg);
                throw new RuntimeException(errorMsg);
            }
        }

        boolean changed = false;
        try {
            JsonNode response = sendGet(finalityCheckpointsPath, "application/json");
            JsonNode data = response.path("data");
            if (data.isMissingNode() || data.isNull()) {
                String errorMsg = String.format(
                        "Consensus node %s returned invalid response for finality checkpoints: missing 'data' field. " +
                        "Response: %s", baseUrl, response.toString());
                logger.error(errorMsg);
                return false;
            }
            if (refreshSafe) {
                changed |= updateCheckpointFromRoot(data.path("current_justified").path("root").asText(null), safe, Confidence.SAFE);
            }
            if (refreshFinalized) {
                changed |= updateCheckpointFromRoot(data.path("finalized").path("root").asText(null), finalized, Confidence.FINALIZED);
            }
        } catch (IOException | InterruptedException ex) {
            String errorMsg = String.format(
                    "Consensus finality checkpoint fetch failed for %s%s: %s: %s",
                    baseUrl, finalityCheckpointsPath, ex.getClass().getSimpleName(), ex.getMessage());
            logger.error(errorMsg, ex);
        }
        return changed;
    }

    ReferenceObservation getObservation(Confidence confidence) {
        return switch (confidence) {
            case NEW -> head.get();
            case SAFE -> safe.get();
            case FINALIZED -> finalized.get();
        };
    }

    List<ReferenceObservation> getObservationHistorySince(Confidence confidence, Instant since) {
        Deque<ReferenceObservation> history = historyByConfidence.get(confidence);
        if (history.isEmpty()) {
            return List.of();
        }
        List<ReferenceObservation> result = new ArrayList<>();
        for (ReferenceObservation observation : history) {
            if (!observation.observedAt().isBefore(since)) {
                result.add(observation);
            }
        }
        return result;
    }

    AttestationConfidence getAttestationConfidence(long blockNumber) {
        return attestationTracker != null ? attestationTracker.getConfidence(blockNumber) : null;
    }

    Map<String, AttestationConfidence> getRecentAttestationConfidences() {
        return attestationTracker != null ? attestationTracker.getRecentConfidencesByHash() : Map.of();
    }

    private void registerAttestationTracking(long slot, ExecutionBlock executionBlock) {
        if (attestationTracker == null || executionBlock == null
                || executionBlock.blockHash() == null || executionBlock.blockHash().isBlank()
                || executionBlock.blockNumber() == null) {
            return;
        }
        AttestationTracking next = new AttestationTracking(slot, executionBlock.blockNumber(), executionBlock.blockHash(), 0, 0);
        AttestationTracking current = attestationTrackingBySlot.put(slot, next);
        if (current == null || !current.blockHash().equalsIgnoreCase(next.blockHash())) {
            attestationQueue.addLast(next);
        }
    }

    private void runAttestationLoop() {
        try {
            while (streamRunning.get()) {
                processAttestationTracking();
                Thread.sleep(attestationTrackingIntervalMs);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void processAttestationTracking() {
        AttestationTracking tracking = attestationQueue.pollFirst();
        if (tracking == null) {
            return;
        }
        AttestationTracking latest = attestationTrackingBySlot.get(tracking.slot());
        if (latest != tracking) {
            return;
        }

        int observedAttestations = tracking.observedAttestations();
        try {
            CommitteeStats stats = fetchCommitteeStats(tracking.slot());
            if (stats.hasData()) {
                observedAttestations = Math.min(3, observedAttestations + 1);
            }
            attestationTracker.updateTrackingResult(
                    tracking.blockNumber(),
                    tracking.blockHash(),
                    tracking.slot(),
                    observedAttestations,
                    Instant.now());
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return;
            }
            logger.debug("Committee fetch failed for slot {}: {}", tracking.slot(), ex.getMessage());
        }

        if (observedAttestations >= 3) {
            attestationTrackingBySlot.remove(tracking.slot(), tracking);
            return;
        }

        int nextAttempts = tracking.attempts() + 1;
        if (nextAttempts >= attestationTrackingMaxAttempts) {
            attestationTrackingBySlot.remove(tracking.slot(), tracking);
            return;
        }

        AttestationTracking next = new AttestationTracking(
                tracking.slot(),
                tracking.blockNumber(),
                tracking.blockHash(),
                nextAttempts,
                observedAttestations);
        attestationTrackingBySlot.put(tracking.slot(), next);
        attestationQueue.addLast(next);
    }

    private CommitteeStats fetchCommitteeStats(long slot) throws IOException, InterruptedException {
        String separator = committeesPath.contains("?") ? "&" : "?";
        String path = committeesPath + separator + "slot=" + slot;
        JsonNode response = sendGet(path, "application/json");
        JsonNode data = response.path("data");
        if (!data.isArray() || data.isEmpty()) {
            return new CommitteeStats(0, 0);
        }
        int committeeCount = data.size();
        int validatorCount = 0;
        for (JsonNode committee : data) {
            JsonNode validators = committee.path("validators");
            if (validators.isArray()) {
                validatorCount += validators.size();
            }
        }
        return new CommitteeStats(committeeCount, validatorCount);
    }

    private void pruneSlotCache() {
        if (executionBlockBySlot.size() > 256) {
            long minSlot = executionBlockBySlot.keySet().stream()
                    .mapToLong(Long::longValue)
                    .min()
                    .orElse(0);
            long cutoff = minSlot + (executionBlockBySlot.size() - 256);
            executionBlockBySlot.entrySet().removeIf(entry -> entry.getKey() < cutoff);
        }
    }

    private void runEventLoop() {
        try {
            while (true) {
                readEventStreamOnce();
                Thread.sleep(1000);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            streamRunning.set(false);
        }
    }

    private void readEventStreamOnce() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + eventsPath))
                .header("Accept", "text/event-stream")
                .GET()
                .build();

        try {
            HttpResponse<java.io.InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                logger.debug("Consensus SSE returned status {}", response.statusCode());
                return;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String eventType = null;
                StringBuilder dataBuilder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        dispatchEvent(eventType, dataBuilder.toString());
                        eventType = null;
                        dataBuilder.setLength(0);
                        continue;
                    }
                    if (line.startsWith("event:")) {
                        eventType = line.substring("event:".length()).trim();
                        continue;
                    }
                    if (line.startsWith("data:")) {
                        if (!dataBuilder.isEmpty()) {
                            dataBuilder.append('\n');
                        }
                        dataBuilder.append(line.substring("data:".length()).trim());
                    }
                }
            }
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(interruptedException);
            }
            logger.debug("Consensus SSE stream disconnected: {}", ex.getMessage());
        }
    }

    private void dispatchEvent(String eventType, String data) {
        if (eventType == null || data.isBlank()) {
            return;
        }

        try {
            JsonNode payload = mapper.readTree(data);
            String root = payload.path("block").asText(null);
            if (root == null || root.isBlank() || isZeroRoot(root)) {
                return;
            }
            ExecutionBlock executionBlock = resolveExecutionBlock(root);
            if (executionBlock == null) {
                return;
            }
            Instant observedAt = Instant.now();
            if ("head".equals(eventType)) {
                long headSlot = payload.path("slot").asLong(-1);
                if (headSlot >= 0) {
                    executionBlockBySlot.put(headSlot, executionBlock);
                        registerAttestationTracking(headSlot, executionBlock);
                    pruneSlotCache();
                }
                Instant knowledgeAt = executionBlock.blockTimestamp() != null ? executionBlock.blockTimestamp() : observedAt;
                Long delayMs = computeDelayMs(executionBlock.blockTimestamp(), knowledgeAt);
                updateObservation(Confidence.NEW, head, new ReferenceObservation(
                        executionBlock.blockNumber(),
                        executionBlock.blockHash(),
                        executionBlock.blockTimestamp(),
                        observedAt,
                        knowledgeAt,
                        delayMs));
            } else if ("finalized_checkpoint".equals(eventType)) {
                Instant knowledgeAt = resolveKnowledgeTimestamp(observedAt);
                Long delayMs = computeDelayMs(executionBlock.blockTimestamp(), knowledgeAt);
                updateObservation(Confidence.FINALIZED, finalized, new ReferenceObservation(
                        executionBlock.blockNumber(),
                        executionBlock.blockHash(),
                        executionBlock.blockTimestamp(),
                        observedAt,
                        knowledgeAt,
                        delayMs));
            }
        } catch (IOException ex) {
            logger.debug("Failed to parse consensus SSE event {}: {}", eventType, ex.getMessage());
        }
    }

    private boolean updateCheckpointFromRoot(String root, AtomicReference<ReferenceObservation> destination, Confidence confidence) {
        if (root == null || root.isBlank() || isZeroRoot(root)) {
            logger.debug("Skipping {} checkpoint update: root is null, blank, or zero ({})", confidence, root);
            return false;
        }
        ExecutionBlock executionBlock = resolveExecutionBlock(root);
        if (executionBlock == null) {
            logger.warn("Failed to update {} checkpoint: could not resolve execution block from beacon root {}", confidence, root);
            return false;
        }
        Instant observedAt = Instant.now();
        Instant knowledgeAt = resolveKnowledgeTimestamp(observedAt);
        Long delayMs = computeDelayMs(executionBlock.blockTimestamp(), knowledgeAt);
        boolean changed = updateObservation(confidence, destination, new ReferenceObservation(
                executionBlock.blockNumber(),
                executionBlock.blockHash(),
                executionBlock.blockTimestamp(),
                observedAt,
                knowledgeAt,
                delayMs));

        // Reset timeout tracking when we successfully receive a checkpoint
        if (confidence == Confidence.SAFE) {
            safeFirstRequestAt.set(null);
            logger.debug("Successfully received safe checkpoint: block #{} ({})", executionBlock.blockNumber(), executionBlock.blockHash());
        } else if (confidence == Confidence.FINALIZED) {
            finalizedFirstRequestAt.set(null);
            logger.debug("Successfully received finalized checkpoint: block #{} ({})", executionBlock.blockNumber(), executionBlock.blockHash());
        }

        if (confidence == Confidence.FINALIZED) {
            updateObservation(Confidence.FINALIZED, finalized, destination.get());
        }
        return changed;
    }

    private boolean updateObservation(Confidence confidence,
                                   AtomicReference<ReferenceObservation> destination,
                                   ReferenceObservation next) {
        ReferenceObservation current = destination.get();
        if (current != null
                && current.blockNumber().equals(next.blockNumber())
                && current.blockHash().equalsIgnoreCase(next.blockHash())) {
            return false;
        }
        destination.set(next);
        appendHistory(confidence, next);
        return true;
    }

    private ExecutionBlock resolveExecutionBlock(String blockRoot) {
        ExecutionBlock cached = executionBlockCache.get(blockRoot);
        if (cached != null) {
            return cached;
        }

        String encodedRoot = URLEncoder.encode(blockRoot, StandardCharsets.UTF_8);
        String path = "/eth/v2/beacon/blocks/" + encodedRoot;
        try {
            JsonNode json = sendGet(path, "application/json");
            JsonNode executionPayload = json.path("data").path("message").path("body").path("execution_payload");
            if (executionPayload.isMissingNode() || executionPayload.isNull()) {
                logger.error("Failed to resolve execution block from consensus root {}: missing execution_payload in response from {}{}",
                        blockRoot, baseUrl, path);
                return null;
            }
            String blockHash = executionPayload.path("block_hash").asText(null);
            Long blockNumber = parseDecimalOrHexLong(executionPayload.path("block_number").asText(null));
            Instant blockTimestamp = parseExecutionPayloadTimestamp(executionPayload.path("timestamp").asText(null));
            if (blockHash == null || blockHash.isBlank() || blockNumber == null) {
                logger.error("Failed to resolve execution block from consensus root {}: invalid block data (hash={}, number={}) from {}{}",
                        blockRoot, blockHash, blockNumber, baseUrl, path);
                return null;
            }
            ExecutionBlock result = new ExecutionBlock(blockNumber, blockHash, blockTimestamp);
            executionBlockCache.put(blockRoot, result);
            return result;
        } catch (IOException | InterruptedException ex) {
            logger.error("Failed to resolve execution block from consensus root {} at {}{}: {}: {}",
                    blockRoot, baseUrl, path, ex.getClass().getSimpleName(), ex.getMessage(), ex);
            return null;
        }
    }

    private JsonNode sendGet(String path, String accept) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Accept", accept)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " from " + path);
        }
        return mapper.readTree(response.body());
    }

    private static String trimTrailingSlash(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String sanitizePath(String value, String fallback) {
        String path = (value == null || value.isBlank()) ? fallback : value.trim();
        return path.startsWith("/") ? path : "/" + path;
    }

    private static Long normalizePollInterval(Long value) {
        if (value == null || value <= 0) {
            return null;
        }
        return Math.max(250L, value);
    }

    private static long normalizeTrackingInterval(long value) {
        if (value <= 0) {
            return 3000L;
        }
        return Math.max(250L, value);
    }

    private static int normalizeTrackingAttempts(int value) {
        if (value <= 0) {
            return 100;
        }
        return value;
    }

    private static boolean isZeroRoot(String root) {
        if (root == null) {
            return true;
        }
        String normalized = root.trim().toLowerCase();
        return normalized.matches("0x0+");
    }

    private Long parseDecimalOrHexLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.startsWith("0x") || normalized.startsWith("0X")) {
            return new BigInteger(normalized.substring(2), 16).longValue();
        }
        return new BigInteger(normalized, 10).longValue();
    }

    private Instant parseExecutionPayloadTimestamp(String timestamp) {
        Long seconds = parseDecimalOrHexLong(timestamp);
        return seconds == null ? null : Instant.ofEpochSecond(seconds);
    }

    private Instant resolveKnowledgeTimestamp(Instant fallback) {
        ReferenceObservation headObservation = head.get();
        if (headObservation != null && headObservation.blockTimestamp() != null) {
            return headObservation.blockTimestamp();
        }
        return fallback;
    }

    private Long computeDelayMs(Instant blockTimestamp, Instant knowledgeTimestamp) {
        if (blockTimestamp == null || knowledgeTimestamp == null) {
            return null;
        }
        long delayMs = Duration.between(blockTimestamp, knowledgeTimestamp).toMillis();
        return delayMs < 0 ? null : delayMs;
    }

    private void appendHistory(Confidence confidence, ReferenceObservation observation) {
        Deque<ReferenceObservation> history = historyByConfidence.get(confidence);
        history.addLast(observation);
        Instant cutoff = Instant.now().minus(HISTORY_RETENTION);
        while (true) {
            ReferenceObservation first = history.peekFirst();
            if (first == null || !first.observedAt().isBefore(cutoff)) {
                break;
            }
            history.pollFirst();
        }
    }

    private record ExecutionBlock(Long blockNumber, String blockHash, Instant blockTimestamp) {
    }

    private record AttestationTracking(long slot,
                                       long blockNumber,
                                       String blockHash,
                                       int attempts,
                                       int observedAttestations) {
    }

    private record CommitteeStats(int committeeCount, int validatorCount) {
        boolean hasData() {
            return committeeCount > 0 || validatorCount > 0;
        }
    }
}

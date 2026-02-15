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
package de.makibytes.chaincheck.monitor;

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
import de.makibytes.chaincheck.monitor.ReferenceBlocks.Confidence;

class BeaconReferenceService {

    private static final Logger logger = LoggerFactory.getLogger(BeaconReferenceService.class);

    private final String baseUrl;
    private final String eventsPath;
    private final String finalityCheckpointsPath;
    private final Long safePollIntervalMs;
    private final Long finalizedPollIntervalMs;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean streamRunning = new AtomicBoolean(false);
    private final Map<String, ExecutionBlock> executionBlockCache = new ConcurrentHashMap<>();

    private final AtomicReference<ReferenceObservation> head = new AtomicReference<>();
    private final AtomicReference<ReferenceObservation> safe = new AtomicReference<>();
    private final AtomicReference<ReferenceObservation> finalized = new AtomicReference<>();
    private final Map<Confidence, Deque<ReferenceObservation>> historyByConfidence = new ConcurrentHashMap<>();
    private static final Duration HISTORY_RETENTION = Duration.ofDays(35);

    BeaconReferenceService(ChainCheckProperties.Consensus referenceNode) {
        this.baseUrl = trimTrailingSlash(referenceNode == null ? null : referenceNode.getHttp());
        this.eventsPath = sanitizePath(referenceNode == null ? null : referenceNode.getEventsPath(),
                "/eth/v1/events?topics=head&topics=finalized_checkpoint");
        this.finalityCheckpointsPath = sanitizePath(referenceNode == null ? null : referenceNode.getFinalityCheckpointsPath(),
                "/eth/v1/beacon/states/head/finality_checkpoints");
        this.safePollIntervalMs = normalizePollInterval(referenceNode == null ? null : referenceNode.getSafePollIntervalMs());
        this.finalizedPollIntervalMs = normalizePollInterval(referenceNode == null ? null : referenceNode.getFinalizedPollIntervalMs());
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
    }

    void refreshCheckpoints(boolean refreshSafe, boolean refreshFinalized) {
        if (!isEnabled()) {
            return;
        }
        if (!refreshSafe && !refreshFinalized) {
            return;
        }
        try {
            JsonNode response = sendGet(finalityCheckpointsPath, "application/json");
            JsonNode data = response.path("data");
            if (refreshSafe) {
                updateCheckpointFromRoot(data.path("current_justified").path("root").asText(null), safe, Confidence.SAFE);
            }
            if (refreshFinalized) {
                updateCheckpointFromRoot(data.path("finalized").path("root").asText(null), finalized, Confidence.FINALIZED);
            }
        } catch (IOException | InterruptedException ex) {
            logger.debug("Beacon finality checkpoint fetch failed: {}", ex.getMessage());
        }
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
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        Instant effectiveSince = since == null ? Instant.EPOCH : since;
        List<ReferenceObservation> result = new ArrayList<>();
        for (ReferenceObservation observation : history) {
            if (observation.observedAt() != null && !observation.observedAt().isBefore(effectiveSince)) {
                result.add(observation);
            }
        }
        return result;
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
                logger.debug("Beacon SSE returned status {}", response.statusCode());
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
            logger.debug("Beacon SSE stream disconnected: {}", ex.getMessage());
        }
    }

    private void dispatchEvent(String eventType, String data) {
        if (eventType == null || data == null || data.isBlank()) {
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
            logger.debug("Failed to parse beacon SSE event {}: {}", eventType, ex.getMessage());
        }
    }

    private void updateCheckpointFromRoot(String root, AtomicReference<ReferenceObservation> destination, Confidence confidence) {
        if (root == null || root.isBlank() || isZeroRoot(root)) {
            return;
        }
        ExecutionBlock executionBlock = resolveExecutionBlock(root);
        if (executionBlock == null) {
            return;
        }
        Instant observedAt = Instant.now();
        Instant knowledgeAt = resolveKnowledgeTimestamp(observedAt);
        Long delayMs = computeDelayMs(executionBlock.blockTimestamp(), knowledgeAt);
        updateObservation(confidence, destination, new ReferenceObservation(
                executionBlock.blockNumber(),
                executionBlock.blockHash(),
                executionBlock.blockTimestamp(),
                observedAt,
                knowledgeAt,
                delayMs));
        if (confidence == Confidence.FINALIZED) {
            updateObservation(Confidence.FINALIZED, finalized, destination.get());
        }
    }

    private void updateObservation(Confidence confidence,
                                   AtomicReference<ReferenceObservation> destination,
                                   ReferenceObservation next) {
        if (next == null || next.blockNumber() == null || next.blockHash() == null) {
            return;
        }
        ReferenceObservation current = destination.get();
        if (current != null
                && current.blockNumber().equals(next.blockNumber())
                && current.blockHash().equalsIgnoreCase(next.blockHash())) {
            return;
        }
        destination.set(next);
        appendHistory(confidence, next);
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
                return null;
            }
            String blockHash = executionPayload.path("block_hash").asText(null);
            Long blockNumber = parseDecimalOrHexLong(executionPayload.path("block_number").asText(null));
            Instant blockTimestamp = parseExecutionPayloadTimestamp(executionPayload.path("timestamp").asText(null));
            if (blockHash == null || blockHash.isBlank() || blockNumber == null) {
                return null;
            }
            ExecutionBlock result = new ExecutionBlock(blockNumber, blockHash, blockTimestamp);
            executionBlockCache.put(blockRoot, result);
            return result;
        } catch (IOException | InterruptedException ex) {
            logger.debug("Failed to resolve execution block from beacon root {}: {}", blockRoot, ex.getMessage());
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
        if (history == null || observation == null || observation.observedAt() == null) {
            return;
        }
        history.addLast(observation);
        Instant cutoff = Instant.now().minus(HISTORY_RETENTION);
        while (true) {
            ReferenceObservation first = history.peekFirst();
            if (first == null || first.observedAt() == null || !first.observedAt().isBefore(cutoff)) {
                break;
            }
            history.pollFirst();
        }
    }

    record ReferenceObservation(Long blockNumber,
                                String blockHash,
                                Instant blockTimestamp,
                                Instant observedAt,
                                Instant knowledgeAt,
                                Long delayMs) {
    }

    private record ExecutionBlock(Long blockNumber, String blockHash, Instant blockTimestamp) {
    }
}

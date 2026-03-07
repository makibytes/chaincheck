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
package de.makibytes.chaincheck.chain.cosmos;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import de.makibytes.chaincheck.chain.shared.Confidence;

import org.springframework.stereotype.Service;

import de.makibytes.chaincheck.chain.shared.BlockAgreementTracker;
import de.makibytes.chaincheck.chain.shared.BlockConfidenceTracker;
import de.makibytes.chaincheck.chain.shared.NodeSwitchPolicy;
import de.makibytes.chaincheck.model.AnomalyEvent;
import de.makibytes.chaincheck.model.MetricSample;
import de.makibytes.chaincheck.monitor.RpcMonitorService;
import de.makibytes.chaincheck.store.InMemoryMetricsStore;

/**
 * Service responsible for selecting the reference node based on comprehensive metrics and stability.
 * Prioritizes nodes with lowest block delays (new/safe/finalized), then healthy WebSocket connections,
 * with connection latency being least important.
 */
@Service
public class ReferenceNodeSelector {

    static final long EVALUATION_WINDOW_SECONDS = 3600;
    static final long WS_FRESH_SECONDS = 30;
    static final int MIN_RECENT_SAMPLE_COUNT = 3;
    static final double MIN_UPTIME_RATIO = 0.85;
    static final int MAX_RECENT_ANOMALIES = 20;
    static final double MAX_ANOMALY_RATE = 0.25;

    private static final double WS_HEALTH_SCORE = 1200.0;
    private static final double UPTIME_SCORE_WEIGHT = 2400.0;
    private static final double LATENCY_SCORE_WEIGHT = 1600.0;
    private static final double HEAD_DELAY_SCORE_WEIGHT = 2200.0;
    private static final double SAFE_DELAY_SCORE_WEIGHT = 800.0;
    private static final double FINALIZED_DELAY_SCORE_WEIGHT = 900.0;
    private static final double MATCHING_SCORE_WEIGHT = 4.0;

    private static final double LATENCY_THRESHOLD_MS = 2000.0;
    private static final double HEAD_DELAY_THRESHOLD_MS = 10000.0;
    private static final double SAFE_DELAY_THRESHOLD_MS = 30000.0;
    private static final double FINALIZED_DELAY_THRESHOLD_MS = 60000.0;

    private static final double DELAY_ANOMALY_PENALTY = 25.0;
    private static final double STALE_ANOMALY_PENALTY = 120.0;
    private static final double REORG_ANOMALY_PENALTY = 250.0;
    private static final double BLOCK_GAP_ANOMALY_PENALTY = 150.0;
    private static final double ERROR_ANOMALY_PENALTY = 60.0;
    private static final double RATE_LIMIT_ANOMALY_PENALTY = 50.0;
    private static final double TIMEOUT_ANOMALY_PENALTY = 90.0;
    private static final double WRONG_HEAD_ANOMALY_PENALTY = 400.0;
    private static final double CONFLICT_ANOMALY_PENALTY = 200.0;

    private final NodeSwitchPolicy switchPolicy;

    public ReferenceNodeSelector() {
        this.switchPolicy = new NodeSwitchPolicy();
    }

    /**
     * Selects the best reference node from the available nodes based on comprehensive scoring.
     * Uses the switch policy to ensure stability.
     */
    public String select(Map<String, RpcMonitorService.NodeState> nodeStates, BlockConfidenceTracker blockConfidenceTracker, InMemoryMetricsStore store, Instant now, BlockAgreementTracker blockAgreementTracker, String currentReferenceNodeKey) {
        String bestNode = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        double currentScore = Double.NEGATIVE_INFINITY;
        for (String nodeKey : nodeStates.keySet()) {
            double score = computeNodeScore(nodeKey, blockAgreementTracker, nodeStates, blockConfidenceTracker, store, now);
            if (nodeKey.equals(currentReferenceNodeKey)) {
                currentScore = score;
            }
            if (!Double.isFinite(score)) {
                continue;
            }
            if (score > bestScore) {
                bestScore = score;
                bestNode = nodeKey;
            }
        }

        if (bestNode != null) {
            switchPolicy.registerSelection(bestNode);
            if (currentReferenceNodeKey != null && !bestNode.equals(currentReferenceNodeKey)) {
                if (!Double.isFinite(currentScore)) {
                    return bestNode;
                }
                if (!switchPolicy.shouldSwitchTo(bestNode)) {
                    return currentReferenceNodeKey;
                }
            }
            return bestNode;
        }
        return null;
    }

    double computeNodeScore(String nodeKey, BlockAgreementTracker tracker, Map<String, RpcMonitorService.NodeState> nodeStates,
            BlockConfidenceTracker blockConfidenceTracker, InMemoryMetricsStore store, Instant now) {
        RpcMonitorService.NodeState state = nodeStates.get(nodeKey);
        if (state == null) {
            return Double.NEGATIVE_INFINITY;
        }

        List<MetricSample> samples = store.getRawSamplesSince(nodeKey, now.minusSeconds(EVALUATION_WINDOW_SECONDS));
        if (samples.size() < MIN_RECENT_SAMPLE_COUNT) {
            return Double.NEGATIVE_INFINITY;
        }

        // Single-pass accumulation of latency and delay metrics
        long successCount = 0;
        long latencySum = 0, latencyCount = 0;
        long headDelaySum = 0, headDelayCount = 0;
        long safeDelaySum = 0, safeDelayCount = 0;
        long finalizedDelaySum = 0, finalizedDelayCount = 0;
        for (MetricSample sample : samples) {
            if (sample.isSuccess()) {
                successCount++;
            }
            if (sample.isSuccess() && sample.getLatencyMs() >= 0) {
                latencySum += sample.getLatencyMs();
                latencyCount++;
            }
            if (sample.getHeadDelayMs() != null) {
                headDelaySum += sample.getHeadDelayMs();
                headDelayCount++;
            }
            if (sample.getSafeDelayMs() != null) {
                safeDelaySum += sample.getSafeDelayMs();
                safeDelayCount++;
            }
            if (sample.getFinalizedDelayMs() != null) {
                finalizedDelaySum += sample.getFinalizedDelayMs();
                finalizedDelayCount++;
            }
        }

        double uptimeRatio = (double) successCount / samples.size();
        List<AnomalyEvent> anomalies = store.getRawAnomaliesSince(nodeKey, now.minusSeconds(EVALUATION_WINDOW_SECONDS));
        long totalAnomalies = anomalies.size();
        double anomalyRate = (double) totalAnomalies / Math.max(1, samples.size());
        if (uptimeRatio < MIN_UPTIME_RATIO
                || totalAnomalies > MAX_RECENT_ANOMALIES
                || anomalyRate > MAX_ANOMALY_RATE) {
            return Double.NEGATIVE_INFINITY;
        }

        double wsScore = isWsHealthy(state, now) ? WS_HEALTH_SCORE : 0.0;
        double uptimeScore = uptimeRatio * UPTIME_SCORE_WEIGHT;

        double avgLatency = latencyCount > 0 ? (double) latencySum / latencyCount : LATENCY_THRESHOLD_MS;
        double latencyScore = boundedScore(avgLatency, LATENCY_THRESHOLD_MS, LATENCY_SCORE_WEIGHT);

        double avgHeadDelay = headDelayCount > 0 ? (double) headDelaySum / headDelayCount : HEAD_DELAY_THRESHOLD_MS;
        double headDelayScore = boundedScore(avgHeadDelay, HEAD_DELAY_THRESHOLD_MS, HEAD_DELAY_SCORE_WEIGHT);

        boolean safeEstablished = blockConfidenceTracker.getBlocks().values().stream().anyMatch(m -> m.containsKey(Confidence.SAFE));
        double avgSafeDelay = safeDelayCount > 0 ? (double) safeDelaySum / safeDelayCount : SAFE_DELAY_THRESHOLD_MS;
        double safeDelayScore = safeEstablished
                ? boundedScore(avgSafeDelay, SAFE_DELAY_THRESHOLD_MS, SAFE_DELAY_SCORE_WEIGHT)
                : SAFE_DELAY_SCORE_WEIGHT / 2.0;

        boolean finalizedEstablished = blockConfidenceTracker.getBlocks().values().stream().anyMatch(m -> m.containsKey(Confidence.FINALIZED));
        double avgFinalizedDelay = finalizedDelayCount > 0 ? (double) finalizedDelaySum / finalizedDelayCount : FINALIZED_DELAY_THRESHOLD_MS;
        double finalizedDelayScore = finalizedEstablished
                ? boundedScore(avgFinalizedDelay, FINALIZED_DELAY_THRESHOLD_MS, FINALIZED_DELAY_SCORE_WEIGHT)
                : FINALIZED_DELAY_SCORE_WEIGHT / 2.0;

        double matchingScore = tracker.getPoints(nodeKey) * MATCHING_SCORE_WEIGHT;
        double anomalyPenalty = computeAnomalyPenalty(anomalies);

        return wsScore + uptimeScore + latencyScore + headDelayScore + safeDelayScore + finalizedDelayScore + matchingScore - anomalyPenalty;
    }

    private boolean isWsHealthy(RpcMonitorService.NodeState state, Instant now) {
        return state.webSocketRef.get() != null
                && state.lastWsEventReceivedAt != null
                && state.lastWsEventReceivedAt.isAfter(now.minusSeconds(WS_FRESH_SECONDS)); // fresh within 30s
    }

    private double computeAnomalyPenalty(List<AnomalyEvent> anomalies) {
        double penalty = 0.0;
        for (AnomalyEvent anomaly : anomalies) {
            penalty += switch (anomaly.getType()) {
                case DELAY -> DELAY_ANOMALY_PENALTY;
                case STALE -> STALE_ANOMALY_PENALTY;
                case REORG -> REORG_ANOMALY_PENALTY;
                case BLOCK_GAP -> BLOCK_GAP_ANOMALY_PENALTY;
                case ERROR -> ERROR_ANOMALY_PENALTY;
                case RATE_LIMIT -> RATE_LIMIT_ANOMALY_PENALTY;
                case TIMEOUT -> TIMEOUT_ANOMALY_PENALTY;
                case WRONG_HEAD -> WRONG_HEAD_ANOMALY_PENALTY;
                case CONFLICT -> CONFLICT_ANOMALY_PENALTY;
            };
        }
        return penalty;
    }

    private double boundedScore(double value, double thresholdMs, double maxScore) {
        double ratio = Math.min(1.0, Math.max(0.0, value) / thresholdMs);
        return maxScore * (1.0 - ratio);
    }

    /**
     * Resets the switch policy.
     */
    public void reset() {
        switchPolicy.reset();
    }
}

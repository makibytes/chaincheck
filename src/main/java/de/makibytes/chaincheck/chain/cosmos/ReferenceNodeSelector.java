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
import de.makibytes.chaincheck.model.MetricSample;
import de.makibytes.chaincheck.monitor.RpcMonitorService;
import de.makibytes.chaincheck.store.AnomalyAggregate;
import de.makibytes.chaincheck.store.InMemoryMetricsStore;

/**
 * Service responsible for selecting the reference node based on comprehensive metrics and stability.
 * Prioritizes nodes with lowest block delays (new/safe/finalized), then healthy WebSocket connections,
 * with connection latency being least important.
 */
@Service
public class ReferenceNodeSelector {

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
        double bestScore = -1;
        for (String nodeKey : nodeStates.keySet()) {
            double score = computeNodeScore(nodeKey, blockAgreementTracker, nodeStates, blockConfidenceTracker, store, now);
            if (score > bestScore) {
                bestScore = score;
                bestNode = nodeKey;
            }
        }

        if (bestNode != null) {
            switchPolicy.registerSelection(bestNode);
            if (currentReferenceNodeKey != null && !bestNode.equals(currentReferenceNodeKey)) {
                if (!switchPolicy.shouldSwitchTo(bestNode)) {
                    return currentReferenceNodeKey;
                }
            }
            return bestNode;
        }
        return null;
    }

    private double computeNodeScore(String nodeKey, BlockAgreementTracker tracker, Map<String, RpcMonitorService.NodeState> nodeStates,
            BlockConfidenceTracker blockConfidenceTracker, InMemoryMetricsStore store, Instant now) {
        RpcMonitorService.NodeState state = nodeStates.get(nodeKey);

        // WebSocket health: 0 or 1000 points (most important after delays)
        double wsScore = isWsHealthy(state, now) ? 1000 : 0;

        // Get recent samples (last 5 minutes)
        List<MetricSample> samples = store.getRawSamplesSince(nodeKey, now.minusSeconds(300));

        // Single-pass accumulation of latency and delay metrics
        long latencySum = 0, latencyCount = 0;
        long headDelaySum = 0, headDelayCount = 0;
        long safeDelaySum = 0, safeDelayCount = 0;
        long finalizedDelaySum = 0, finalizedDelayCount = 0;
        for (MetricSample sample : samples) {
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

        double avgLatency = latencyCount > 0 ? (double) latencySum / latencyCount : 10000;
        double latencyScore = 50 / (1 + avgLatency / 1000.0);

        double avgHeadDelay = headDelayCount > 0 ? (double) headDelaySum / headDelayCount : 10000;
        double headDelayScore = 1000 / (1 + avgHeadDelay / 1000.0);

        double avgSafeDelay = safeDelayCount > 0 ? (double) safeDelaySum / safeDelayCount : 10000;
        double safeDelayScore = blockConfidenceTracker.getBlocks().values().stream().anyMatch(m -> m.containsKey(Confidence.SAFE))
                ? 1000 / (1 + avgSafeDelay / 1000.0) : 500;

        double avgFinalizedDelay = finalizedDelayCount > 0 ? (double) finalizedDelaySum / finalizedDelayCount : 10000;
        double finalizedDelayScore = 1000 / (1 + avgFinalizedDelay / 1000.0);

        // Matching score: points from agreement with reference
        double matchingScore = tracker.getPoints(nodeKey);

        // Wrong head penalty: significant penalty for nodes that frequently disagree with majority
        // Each wrong head reduces score by 200 points (10 wrong heads = -2000, effectively disqualifying)
        List<AnomalyAggregate> anomalyAggregates = store.getAggregatedAnomaliesSince(nodeKey, now.minusSeconds(300));
        long wrongHeadCount = anomalyAggregates.stream()
                .mapToLong(AnomalyAggregate::getWrongHeadCount)
                .sum();
        double wrongHeadPenalty = wrongHeadCount * 200.0;

        // Total score: block delays are now the dominant factor, WS health is secondary, latency is least important
        // Wrong heads are penalized heavily to prevent unreliable nodes from becoming reference
        return wsScore + latencyScore + headDelayScore + safeDelayScore + finalizedDelayScore + matchingScore - wrongHeadPenalty;
    }

    private boolean isWsHealthy(RpcMonitorService.NodeState state, Instant now) {
        return state.webSocketRef.get() != null
                && state.lastWsEventReceivedAt != null
                && state.lastWsEventReceivedAt.isAfter(now.minusSeconds(30)); // fresh within 30s
    }

    /**
     * Resets the switch policy.
     */
    public void reset() {
        switchPolicy.reset();
    }
}

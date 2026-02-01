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

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import de.makibytes.chaincheck.model.MetricSample;
import de.makibytes.chaincheck.store.InMemoryMetricsStore;

/**
 * Service responsible for selecting the reference node based on comprehensive metrics and stability.
 * Prioritizes nodes with lowest block delays (new/safe/finalized), then healthy WebSocket connections,
 * with connection latency being least important.
 */
@Service
public class ReferenceNodeSelector {

    private final ReferenceSelectionPolicy referenceSelectionPolicy;

    public ReferenceNodeSelector() {
        this.referenceSelectionPolicy = new ReferenceSelectionPolicy(20, 15);
    }

    /**
     * Selects the best reference node from the available nodes based on comprehensive scoring.
     * Uses the selection policy to ensure stability.
     */
    public String selectReferenceNode(Map<String, RpcMonitorService.NodeState> nodeStates, ReferenceBlocks referenceBlocks, InMemoryMetricsStore store, Instant now, NodeScorer nodeScorer, String currentReferenceNodeKey) {
        String bestNode = null;
        double bestScore = -1;
        for (String nodeKey : nodeStates.keySet()) {
            double score = computeNodeScore(nodeKey, nodeScorer, nodeStates, referenceBlocks, store, now);
            if (score > bestScore) {
                bestScore = score;
                bestNode = nodeKey;
            }
        }

        if (bestNode != null) {
            referenceSelectionPolicy.registerSelection(bestNode);
            // For now, always switch if better score, but could use policy for threshold
            return bestNode;
        }
        return null;
    }

    private double computeNodeScore(String nodeKey, NodeScorer scorer, Map<String, RpcMonitorService.NodeState> nodeStates,
            ReferenceBlocks referenceBlocks, InMemoryMetricsStore store, Instant now) {
        RpcMonitorService.NodeState state = nodeStates.get(nodeKey);
        if (state == null) {
            return 0;
        }

        // WebSocket health: 0 or 1000 points (most important after delays)
        double wsScore = isWsHealthy(state, now) ? 1000 : 0;

        // Get recent samples (last 5 minutes)
        List<MetricSample> samples = store.getRawSamplesSince(nodeKey, now.minusSeconds(300));

        // Latency score: inverse of average latency (much less important)
        double avgLatency = samples.stream()
                .filter(MetricSample::isSuccess)
                .mapToLong(MetricSample::getLatencyMs)
                .filter(l -> l >= 0)
                .average()
                .orElse(10000); // default high latency if no data
        double latencyScore = 50 / (1 + avgLatency / 1000.0); // 0-50, much lower weight

        // Block delay scores: much more important than latency
        double avgHeadDelay = samples.stream()
                .filter(s -> s.getHeadDelayMs() != null)
                .mapToLong(MetricSample::getHeadDelayMs)
                .average()
                .orElse(10000);
        double headDelayScore = 1000 / (1 + avgHeadDelay / 1000.0); // 0-1000

        double avgSafeDelay = samples.stream()
                .filter(s -> s.getSafeDelayMs() != null)
                .mapToLong(MetricSample::getSafeDelayMs)
                .average()
                .orElse(10000);
        double safeDelayScore = referenceBlocks.getBlocks().values().stream().anyMatch(m -> m.containsKey(ReferenceBlocks.Confidence.SAFE))
                ? 1000 / (1 + avgSafeDelay / 1000.0) : 500; // half if not available

        double avgFinalizedDelay = samples.stream()
                .filter(s -> s.getFinalizedDelayMs() != null)
                .mapToLong(MetricSample::getFinalizedDelayMs)
                .average()
                .orElse(10000);
        double finalizedDelayScore = 1000 / (1 + avgFinalizedDelay / 1000.0); // 0-1000

        // Matching score: points from agreement with reference
        double matchingScore = scorer.getPoints(nodeKey);

        // Total score: block delays are now the dominant factor, WS health is secondary, latency is least important
        return wsScore + latencyScore + headDelayScore + safeDelayScore + finalizedDelayScore + matchingScore;
    }

    private boolean isWsHealthy(RpcMonitorService.NodeState state, Instant now) {
        return state.webSocketRef.get() != null
                && state.lastWsEventReceivedAt != null
                && state.lastWsEventReceivedAt.isAfter(now.minusSeconds(30)); // fresh within 30s
    }

    /**
     * Resets the selection policy.
     */
    public void reset() {
        referenceSelectionPolicy.reset();
    }
}
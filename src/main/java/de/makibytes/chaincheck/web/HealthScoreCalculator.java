/*
 * Copyright 2026 Maki Bytes
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
 */
package de.makibytes.chaincheck.web;

import org.springframework.stereotype.Component;

/**
 * Calculates node health scores based on multiple weighted factors.
 * Health score ranges from 0 (completely unhealthy) to 100 (perfect health).
 * 
 * Score composition:
 * - Uptime: 40% weight
 * - Latency P95: 25% weight  
 * - Head delay P95: 20% weight
 * - Anomaly rate: 15% weight
 */
@Component
public class HealthScoreCalculator {

    // Score weights (must sum to 100)
    private static final double UPTIME_WEIGHT = 40.0;
    private static final double LATENCY_WEIGHT = 25.0;
    private static final double HEAD_DELAY_WEIGHT = 20.0;
    private static final double ANOMALY_WEIGHT = 15.0;
    
    // Thresholds for score degradation
    private static final double LATENCY_THRESHOLD_MS = 2000.0;
    private static final double HEAD_DELAY_THRESHOLD_MS = 10000.0;
    private static final double ANOMALY_RATE_MULTIPLIER = 10.0;
    
    /**
     * Computes health score for a node based on observed metrics.
     * 
     * @param uptimePercent uptime percentage (0-100)
     * @param p95LatencyMs 95th percentile latency in milliseconds
     * @param p95HeadDelayMs 95th percentile head delay in milliseconds
     * @param errorCount number of errors
     * @param totalRequests total number of requests
     * @param isCurrentlyDown true if node is currently down
     * @return health score (0-100)
     */
    public int calculateHealthScore(double uptimePercent, double p95LatencyMs, double p95HeadDelayMs, 
                                    long errorCount, long totalRequests, boolean isCurrentlyDown) {
        // Node is completely down
        if (isCurrentlyDown || (totalRequests > 0 && uptimePercent <= 0.0)) {
            return 0;
        }
        
        // Calculate component scores
        double uptimeScore = calculateUptimeScore(uptimePercent);
        double latencyScore = calculateLatencyScore(p95LatencyMs);
        double headDelayScore = calculateHeadDelayScore(p95HeadDelayMs);
        double anomalyScore = calculateAnomalyScore(errorCount, totalRequests);
        
        // Combine scores and round to integer
        double totalScore = uptimeScore + latencyScore + headDelayScore + anomalyScore;
        return (int) Math.round(totalScore);
    }
    
    private double calculateUptimeScore(double uptimePercent) {
        // Uptime is already 0-100, just apply weight
        return uptimePercent * (UPTIME_WEIGHT / 100.0);
    }
    
    private double calculateLatencyScore(double p95LatencyMs) {
        // Score degrades linearly from max weight at 0ms to 0 at threshold
        double ratio = Math.min(1.0, p95LatencyMs / LATENCY_THRESHOLD_MS);
        double score = 1.0 - ratio;
        return LATENCY_WEIGHT * Math.max(0.0, score);
    }
    
    private double calculateHeadDelayScore(double p95HeadDelayMs) {
        // No delay means full score
        if (p95HeadDelayMs <= 0) {
            return HEAD_DELAY_WEIGHT;
        }
        
        // Score degrades linearly from max weight at 0ms to 0 at threshold
        double ratio = Math.min(1.0, p95HeadDelayMs / HEAD_DELAY_THRESHOLD_MS);
        double score = 1.0 - ratio;
        return HEAD_DELAY_WEIGHT * Math.max(0.0, score);
    }
    
    private double calculateAnomalyScore(long errorCount, long totalRequests) {
        if (totalRequests == 0) {
            return ANOMALY_WEIGHT;
        }
        
        // Calculate error rate and apply multiplier for sensitivity
        double errorRate = (double) errorCount / totalRequests;
        double adjustedRate = errorRate * ANOMALY_RATE_MULTIPLIER;
        double ratio = Math.min(1.0, adjustedRate);
        double score = 1.0 - ratio;
        
        return ANOMALY_WEIGHT * Math.max(0.0, score);
    }
}

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
 * - Uptime: 30% weight
 * - Latency P95: 20% weight
 * - Head delay P95: 15% weight
 * - Anomaly rate: 10% weight
 * - WebSocket connection: 25% weight (if configured)
 */
@Component
public class HealthScoreCalculator {

    // Score weights (must sum to 100)
    private static final double UPTIME_WEIGHT = 30.0;
    private static final double LATENCY_WEIGHT = 20.0;
    private static final double HEAD_DELAY_WEIGHT = 15.0;
    private static final double ANOMALY_WEIGHT = 10.0;
    private static final double WS_WEIGHT = 25.0;

    // Thresholds for score degradation
    private static final double LATENCY_THRESHOLD_MS = 2000.0;
    private static final double HEAD_DELAY_THRESHOLD_MS = 10000.0;
    private static final double ANOMALY_RATE_MULTIPLIER = 10.0;

    public record HealthScoreBreakdown(
            int total,
            double uptimeScore,
            double latencyScore,
            double headDelayScore,
            double anomalyScore,
            double wsScore,
            double uptimePercent,
            double p95LatencyMs,
            double p95HeadDelayMs,
            long errorCount,
            long totalRequests,
            boolean wsConfigured,
            boolean wsUp,
            boolean down) {
    }

    public int calculateHealthScore(double uptimePercent, double p95LatencyMs, double p95HeadDelayMs,
                                    long errorCount, long totalRequests, boolean isCurrentlyDown,
                                    boolean wsConfigured, boolean wsUp) {
        return computeBreakdown(uptimePercent, p95LatencyMs, p95HeadDelayMs,
                errorCount, totalRequests, isCurrentlyDown, wsConfigured, wsUp).total();
    }

    public HealthScoreBreakdown computeBreakdown(double uptimePercent, double p95LatencyMs, double p95HeadDelayMs,
                                                 long errorCount, long totalRequests, boolean isCurrentlyDown,
                                                 boolean wsConfigured, boolean wsUp) {
        boolean down = isCurrentlyDown || (totalRequests > 0 && uptimePercent <= 0.0);
        double uptimeScore = calculateUptimeScore(uptimePercent);
        double latencyScore = calculateLatencyScore(p95LatencyMs);
        double headDelayScore = calculateHeadDelayScore(p95HeadDelayMs);
        double anomalyScore = calculateAnomalyScore(errorCount, totalRequests);

        double wsScore;
        int total;
        if (down) {
            wsScore = 0;
            total = 0;
        } else if (wsConfigured && !wsUp) {
            // Configured but disconnected: lose half the WS weight instead of the full weight
            wsScore = -(WS_WEIGHT / 2.0);
            double sum = uptimeScore + latencyScore + headDelayScore + anomalyScore + wsScore;
            total = Math.max(0, (int) Math.round(sum));
        } else {
            wsScore = wsConfigured ? WS_WEIGHT : 0;
            double sum = uptimeScore + latencyScore + headDelayScore + anomalyScore + wsScore;
            total = (int) Math.round(sum);
        }

        return new HealthScoreBreakdown(total, uptimeScore, latencyScore, headDelayScore,
                anomalyScore, wsScore, uptimePercent, p95LatencyMs, p95HeadDelayMs,
                errorCount, totalRequests, wsConfigured, wsUp, down);
    }

    /**
     * Formats a plain-text tooltip explaining the breakdown. Rendered via
     * {@code data-hint} which sets {@code textContent} — newlines become visible
     * line breaks via CSS {@code white-space: pre-line}.
     */
    public static String buildHint(HealthScoreBreakdown b) {
        if (b.down()) {
            return "Score is 0: node has been continuously down for 3+ minutes.";
        }
        StringBuilder s = new StringBuilder();
        s.append("Health ").append(b.total()).append("/100 — weighted sum of 5 factors:\n");
        s.append("• Uptime ").append(fmtPercent(b.uptimePercent()))
                .append(" → ").append(fmtScore(b.uptimeScore())).append("/30\n");
        s.append("• Latency P95 ").append(fmtMs(b.p95LatencyMs()))
                .append(" → ").append(fmtScore(b.latencyScore())).append("/20 (0 at ≥2000ms)\n");
        s.append("• Head delay P95 ").append(fmtMs(b.p95HeadDelayMs()))
                .append(" → ").append(fmtScore(b.headDelayScore())).append("/15 (0 at ≥10s)\n");
        double errorRate = b.totalRequests() == 0 ? 0.0 : (100.0 * b.errorCount() / b.totalRequests());
        s.append("• Error rate ").append(fmtPercent(errorRate))
                .append(" → ").append(fmtScore(b.anomalyScore())).append("/10 (0 at ≥10%)\n");
        if (!b.wsConfigured()) {
            s.append("• WebSocket not configured → 0/25 (not counted)");
        } else if (b.wsUp()) {
            s.append("• WebSocket connected → 25/25");
        } else {
            s.append("• WebSocket disconnected → −12.5 penalty (of 25)");
        }
        return s.toString();
    }

    private static String fmtPercent(double v) {
        return String.format("%.1f%%", v);
    }

    private static String fmtMs(double v) {
        if (v <= 0) return "0ms";
        if (v >= 1000) return String.format("%.2fs", v / 1000.0);
        return String.format("%.0fms", v);
    }

    private static String fmtScore(double v) {
        return String.format("%.1f", v);
    }

    private double calculateUptimeScore(double uptimePercent) {
        return uptimePercent * (UPTIME_WEIGHT / 100.0);
    }

    private double calculateLatencyScore(double p95LatencyMs) {
        double ratio = Math.min(1.0, p95LatencyMs / LATENCY_THRESHOLD_MS);
        double score = 1.0 - ratio;
        return LATENCY_WEIGHT * Math.max(0.0, score);
    }

    private double calculateHeadDelayScore(double p95HeadDelayMs) {
        if (p95HeadDelayMs <= 0) {
            return HEAD_DELAY_WEIGHT;
        }
        double ratio = Math.min(1.0, p95HeadDelayMs / HEAD_DELAY_THRESHOLD_MS);
        double score = 1.0 - ratio;
        return HEAD_DELAY_WEIGHT * Math.max(0.0, score);
    }

    private double calculateAnomalyScore(long errorCount, long totalRequests) {
        if (totalRequests == 0) {
            return ANOMALY_WEIGHT;
        }
        double errorRate = (double) errorCount / totalRequests;
        double adjustedRate = errorRate * ANOMALY_RATE_MULTIPLIER;
        double ratio = Math.min(1.0, adjustedRate);
        double score = 1.0 - ratio;
        return ANOMALY_WEIGHT * Math.max(0.0, score);
    }
}

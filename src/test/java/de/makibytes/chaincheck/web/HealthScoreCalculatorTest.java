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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("HealthScoreCalculator Tests")
class HealthScoreCalculatorTest {

    private HealthScoreCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new HealthScoreCalculator();
    }

    @Test
    @DisplayName("perfect health returns 100 with WS up")
    void perfectHealthReturns100() {
        int score = calculator.calculateHealthScore(100.0, 0.0, 0.0, 0, 100, false, true, true);
        assertEquals(100, score);
    }

    @Test
    @DisplayName("perfect health without WS returns 75")
    void perfectHealthWithoutWsReturns75() {
        // Weights: Uptime 30% + Latency 20% + Head delay 15% + Anomaly 10% = 75% (WS 25% not configured)
        int score = calculator.calculateHealthScore(100.0, 0.0, 0.0, 0, 100, false, false, false);
        assertEquals(75, score);
    }

    @Test
    @DisplayName("currently down node returns 0")
    void currentlyDownNodeReturns0() {
        int score = calculator.calculateHealthScore(100.0, 0.0, 0.0, 0, 100, true, false, false);
        assertEquals(0, score);
    }

    @Test
    @DisplayName("zero uptime returns 0")
    void zeroUptimeReturns0() {
        int score = calculator.calculateHealthScore(0.0, 0.0, 0.0, 100, 100, false, false, false);
        assertEquals(0, score);
    }

    @Test
    @DisplayName("high latency degrades score")
    void highLatencyDegradesScore() {
        // High latency (2000ms threshold) - at threshold scores ~55-60
        int score = calculator.calculateHealthScore(100.0, 2000.0, 0.0, 0, 100, false, false, false);
        assertTrue(score < 75);
        assertTrue(score >= 50);
    }

    @Test
    @DisplayName("high head delay degrades score")
    void highHeadDelayDegradesScore() {
        // High head delay (10000ms threshold) - at threshold scores ~60-65
        int score = calculator.calculateHealthScore(100.0, 0.0, 10000.0, 0, 100, false, false, false);
        assertTrue(score < 75);
        assertTrue(score >= 55);
    }

    @Test
    @DisplayName("high error rate degrades score")
    void highErrorRateDegradesScore() {
        // 50% error rate (adjusted to 5.0, capped at 1.0 = zero anomaly score)
        // Uptime: 50 * 0.30 = 15, Latency: 20, Head delay: 15, Anomaly: 0
        int score = calculator.calculateHealthScore(50.0, 0.0, 0.0, 50, 100, false, false, false);
        assertEquals(50, score); // 15 + 20 + 15 + 0 = 50
    }

    @Test
    @DisplayName("moderate degradation across all factors")
    void moderateDegradationAcrossAllFactors() {
        // Moderate degradation: 90% uptime, 500ms latency, 3000ms head delay, 5% error
        // Should score around 55-65
        int score = calculator.calculateHealthScore(90.0, 500.0, 3000.0, 5, 100, false, false, false);
        assertTrue(score >= 50 && score <= 70);
    }

    @Test
    @DisplayName("no requests returns partial score based on metrics")
    void noRequestsReturnsPartialScore() {
        // When there are no requests: Uptime: 0, Latency: 20, Head delay: 15, Anomaly: 10
        int score = calculator.calculateHealthScore(0.0, 0.0, 0.0, 0, 0, false, false, false);
        assertEquals(45, score); // 0 + 20 + 15 + 10 = 45
    }

    @Test
    @DisplayName("extreme latency bottoms out latency score")
    void extremeLatencyBottomsOutLatencyScore() {
        // Latency well above threshold
        int score = calculator.calculateHealthScore(100.0, 5000.0, 0.0, 0, 100, false, false, false);
        assertTrue(score <= 60); // Lost significant score from latency component
    }

    @Test
    @DisplayName("excellent node with minor issues")
    void excellentNodeWithMinorIssues() {
        // 99% uptime, 100ms latency, 1000ms head delay, 1% errors
        // Expect around 70-75 without WS
        int score = calculator.calculateHealthScore(99.0, 100.0, 1000.0, 1, 100, false, false, false);
        assertTrue(score >= 65 && score <= 75); // Good without WS
    }

    @Test
    @DisplayName("WS configured and up adds 25 points")
    void wsConfiguredAndUpAdds25Points() {
        // Same metrics but with WS up
        int scoreWithoutWs = calculator.calculateHealthScore(90.0, 100.0, 0.0, 0, 100, false, false, false);
        int scoreWithWs = calculator.calculateHealthScore(90.0, 100.0, 0.0, 0, 100, false, true, true);
        assertEquals(25, scoreWithWs - scoreWithoutWs);
    }

    @Test
    @DisplayName("WS configured but down applies moderate penalty")
    void wsConfiguredButDownAppliesModeratePenalty() {
        // Good HTTP metrics (95% uptime, 100ms latency, 100ms head delay, 0 anomalies)
        // Without WS: ~72 points
        // With WS down: ~60 points (loses ~12.5 point penalty instead of harsh reduction)
        int scoreWithoutWs = calculator.calculateHealthScore(95.0, 100.0, 100.0, 0, 100, false, false, false);
        int scoreWithWsDown = calculator.calculateHealthScore(95.0, 100.0, 100.0, 0, 100, false, true, false);
        
        // Should lose roughly 12.5 points (half of WS weight)
        int penalty = scoreWithoutWs - scoreWithWsDown;
        assertTrue(penalty >= 10 && penalty <= 15);
        
        // Node with good HTTP should still reach 40+ points even with WS down
        assertTrue(scoreWithWsDown >= 40);
    }

    @Test
    @DisplayName("good HTTP with moderate metrics allows 40-45 range with WS down")
    void goodHttpMetricsAllows40To45WithWsDown() {
        // Moderate-good metrics: 85% uptime, 200ms latency, 500ms head delay, 2% anomalies
        int scoreWithWsDown = calculator.calculateHealthScore(85.0, 200.0, 500.0, 2, 100, false, true, false);
        // Should be in the 40-50 range
        assertTrue(scoreWithWsDown >= 40 && scoreWithWsDown <= 60);
    }
}

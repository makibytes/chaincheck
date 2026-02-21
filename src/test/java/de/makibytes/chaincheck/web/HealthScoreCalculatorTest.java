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
    @DisplayName("perfect health returns 100")
    void perfectHealthReturns100() {
        int score = calculator.calculateHealthScore(100.0, 0.0, 0.0, 0, 100, false);
        assertEquals(100, score);
    }

    @Test
    @DisplayName("currently down node returns 0")
    void currentlyDownNodeReturns0() {
        int score = calculator.calculateHealthScore(100.0, 0.0, 0.0, 0, 100, true);
        assertEquals(0, score);
    }

    @Test
    @DisplayName("zero uptime returns 0")
    void zeroUptimeReturns0() {
        int score = calculator.calculateHealthScore(0.0, 0.0, 0.0, 100, 100, false);
        assertEquals(0, score);
    }

    @Test
    @DisplayName("high latency degrades score")
    void highLatencyDegradesScore() {
        // High latency (2000ms threshold)
        int score = calculator.calculateHealthScore(100.0, 2000.0, 0.0, 0, 100, false);
        assertTrue(score < 100);
        assertTrue(score >= 75); // Should still be reasonably high
    }

    @Test
    @DisplayName("high head delay degrades score")
    void highHeadDelayDegradesScore() {
        // High head delay (10000ms threshold)
        int score = calculator.calculateHealthScore(100.0, 0.0, 10000.0, 0, 100, false);
        assertTrue(score < 100);
        assertTrue(score >= 80); // Should still be reasonably high
    }

    @Test
    @DisplayName("high error rate degrades score")
    void highErrorRateDegradesScore() {
        // 50% error rate (adjusted to 5.0, capped at 1.0 = zero anomaly score)
        // Uptime: 50 * 0.4 = 20, Latency: 25, Head delay: 20, Anomaly: 0
        int score = calculator.calculateHealthScore(50.0, 0.0, 0.0, 50, 100, false);
        assertEquals(65, score); // 20 + 25 + 20 + 0 = 65
    }

    @Test
    @DisplayName("moderate degradation across all factors")
    void moderateDegradationAcrossAllFactors() {
        // Moderate degradation: 90% uptime, 500ms latency, 3000ms head delay, 5% error
        int score = calculator.calculateHealthScore(90.0, 500.0, 3000.0, 5, 100, false);
        assertTrue(score >= 70 && score <= 90);
    }

    @Test
    @DisplayName("no requests returns partial score based on metrics")
    void noRequestsReturnsPartialScore() {
        // When there are no requests: Uptime: 0, Latency: 25, Head delay: 20, Anomaly: 15
        int score = calculator.calculateHealthScore(0.0, 0.0, 0.0, 0, 0, false);
        assertEquals(60, score); // 0 + 25 + 20 + 15 = 60
    }

    @Test
    @DisplayName("extreme latency bottoms out latency score")
    void extremeLatencyBottomsOutLatencyScore() {
        // Latency well above threshold
        int score = calculator.calculateHealthScore(100.0, 5000.0, 0.0, 0, 100, false);
        assertTrue(score <= 75); // Lost 25% of score from latency component
    }

    @Test
    @DisplayName("excellent node with minor issues")
    void excellentNodeWithMinorIssues() {
        // 99% uptime, 100ms latency, 1000ms head delay, 1% errors
        int score = calculator.calculateHealthScore(99.0, 100.0, 1000.0, 1, 100, false);
        assertTrue(score >= 90); // Should be in excellent range
    }
}

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
    @DisplayName("perfect health without WS is rescaled to 100")
    void perfectHealthWithoutWsReturns100() {
        // WS not configured and no head-delay data: the measured factors are rescaled
        // to /100, so a flawless HTTP-only node reaches the Excellent badge (>= 80).
        int score = calculator.calculateHealthScore(100.0, 0.0, 0.0, 0, 100, false, false, false);
        assertEquals(100, score);
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
        // Latency at the 2000ms threshold zeroes the latency factor; head delay has
        // no data (p95 = 0) so it is excluded: (30 + 0 + 10) / 60 * 100 = 67
        int score = calculator.calculateHealthScore(100.0, 2000.0, 0.0, 0, 100, false, false, false);
        assertEquals(67, score);
    }

    @Test
    @DisplayName("high head delay degrades score")
    void highHeadDelayDegradesScore() {
        // Head delay at the 10s threshold zeroes the head-delay factor:
        // (30 + 20 + 0 + 10) / 75 * 100 = 80
        int score = calculator.calculateHealthScore(100.0, 0.0, 10000.0, 0, 100, false, false, false);
        assertEquals(80, score);
    }

    @Test
    @DisplayName("high error rate degrades score")
    void highErrorRateDegradesScore() {
        // 50% error rate (adjusted to 5.0, capped at 1.0 = zero anomaly score)
        // Uptime: 50 * 0.30 = 15, Latency: 20, Head delay: 15, Anomaly: 0
        int score = calculator.calculateHealthScore(50.0, 0.0, 0.0, 50, 100, false, false, false);
        assertEquals(58, score); // head delay excluded: (15 + 20 + 0) / 60 * 100 = 58.3 -> 58
    }

    @Test
    @DisplayName("moderate degradation across all factors")
    void moderateDegradationAcrossAllFactors() {
        // Moderate degradation: 90% uptime, 500ms latency, 3000ms head delay, 5% error
        // (27 + 15 + 10.5 + 5) / 75 * 100 = 76.7 -> 77
        int score = calculator.calculateHealthScore(90.0, 500.0, 3000.0, 5, 100, false, false, false);
        assertTrue(score >= 70 && score <= 85);
    }

    @Test
    @DisplayName("no requests returns partial score based on metrics")
    void noRequestsReturnsPartialScore() {
        // When there are no requests: Uptime: 0, Latency: 20, Anomaly: 10 (head delay excluded)
        int score = calculator.calculateHealthScore(0.0, 0.0, 0.0, 0, 0, false, false, false);
        assertEquals(50, score); // (0 + 20 + 10) / 60 * 100 = 50
    }

    @Test
    @DisplayName("extreme latency bottoms out latency score")
    void extremeLatencyBottomsOutLatencyScore() {
        // Latency well above threshold: (30 + 0 + 10) / 60 * 100 = 67
        int score = calculator.calculateHealthScore(100.0, 5000.0, 0.0, 0, 100, false, false, false);
        assertTrue(score <= 75); // Lost the entire latency component
    }

    @Test
    @DisplayName("excellent node with minor issues")
    void excellentNodeWithMinorIssues() {
        // 99% uptime, 100ms latency, 1000ms head delay, 1% errors
        // (29.7 + 19 + 13.5 + 9) / 75 * 100 = 94.9 -> 95
        int score = calculator.calculateHealthScore(99.0, 100.0, 1000.0, 1, 100, false, false, false);
        assertTrue(score >= 90 && score <= 100); // Excellent without WS
    }

    @Test
    @DisplayName("HTTP-only node is not penalized vs node with WS up")
    void httpOnlyNodeScoresOnParWithWsUpNode() {
        // Renormalization parity: identical HTTP metrics should yield near-identical
        // scores whether WS is absent (rescaled /75 -> /100) or configured and up.
        int scoreWithoutWs = calculator.calculateHealthScore(90.0, 100.0, 0.0, 0, 100, false, false, false);
        int scoreWithWs = calculator.calculateHealthScore(90.0, 100.0, 0.0, 0, 100, false, true, true);
        assertTrue(Math.abs(scoreWithWs - scoreWithoutWs) <= 5);
    }

    @Test
    @DisplayName("WS configured but down applies moderate penalty")
    void wsConfiguredButDownAppliesModeratePenalty() {
        // Good HTTP metrics (95% uptime, 100ms latency, 100ms head delay, 0 anomalies)
        // With WS up: ~97. With WS down: raw sum (~72) minus 12.5 penalty -> ~60.
        int scoreWithWsUp = calculator.calculateHealthScore(95.0, 100.0, 100.0, 0, 100, false, true, true);
        int scoreWithWsDown = calculator.calculateHealthScore(95.0, 100.0, 100.0, 0, 100, false, true, false);

        // Disconnect costs the 25 WS points plus a 12.5 penalty (~37 total)
        int penalty = scoreWithWsUp - scoreWithWsDown;
        assertTrue(penalty >= 30 && penalty <= 45);

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

    // buildHint tests

    @Test
    @DisplayName("buildHint returns down message when node is down")
    void buildHintReturnsDownMessageWhenDown() {
        HealthScoreCalculator.HealthScoreBreakdown b = calculator.computeBreakdown(
                0.0, 0.0, 0.0, 100, 100, true, false, false);
        String hint = HealthScoreCalculator.buildHint(b);
        assertTrue(hint.contains("0"));
        assertTrue(hint.toLowerCase().contains("down"));
    }

    @Test
    @DisplayName("buildHint includes all five factor lines for healthy node")
    void buildHintIncludesAllFiveFactors() {
        HealthScoreCalculator.HealthScoreBreakdown b = calculator.computeBreakdown(
                100.0, 50.0, 500.0, 0, 100, false, true, true);
        String hint = HealthScoreCalculator.buildHint(b);
        assertTrue(hint.contains("Uptime"));
        assertTrue(hint.contains("Latency P95"));
        assertTrue(hint.contains("Head delay P95"));
        assertTrue(hint.contains("Error rate"));
        assertTrue(hint.contains("WebSocket"));
    }

    @Test
    @DisplayName("buildHint shows WS not configured message")
    void buildHintShowsWsNotConfigured() {
        HealthScoreCalculator.HealthScoreBreakdown b = calculator.computeBreakdown(
                100.0, 0.0, 0.0, 0, 100, false, false, false);
        String hint = HealthScoreCalculator.buildHint(b);
        assertTrue(hint.contains("not configured"));
    }

    @Test
    @DisplayName("buildHint shows WS disconnected penalty")
    void buildHintShowsWsDisconnectedPenalty() {
        HealthScoreCalculator.HealthScoreBreakdown b = calculator.computeBreakdown(
                100.0, 0.0, 0.0, 0, 100, false, true, false);
        String hint = HealthScoreCalculator.buildHint(b);
        assertTrue(hint.contains("disconnected"));
        assertTrue(hint.contains("penalty") || hint.contains("−"));
    }

    @Test
    @DisplayName("buildHint shows WS connected full score")
    void buildHintShowsWsConnectedFullScore() {
        HealthScoreCalculator.HealthScoreBreakdown b = calculator.computeBreakdown(
                100.0, 0.0, 0.0, 0, 100, false, true, true);
        String hint = HealthScoreCalculator.buildHint(b);
        assertTrue(hint.contains("25/25") || hint.contains("connected"));
    }

    @Test
    @DisplayName("buildHint formats latency in seconds when above 1000ms")
    void buildHintFormatsLargeLatencyInSeconds() {
        HealthScoreCalculator.HealthScoreBreakdown b = calculator.computeBreakdown(
                90.0, 1500.0, 0.0, 0, 100, false, false, false);
        String hint = HealthScoreCalculator.buildHint(b);
        assertTrue(hint.contains("s"), "Expected latency formatted as seconds: " + hint);
    }

    @Test
    @DisplayName("buildHint total score matches computeBreakdown total")
    void buildHintTotalMatchesBreakdownTotal() {
        HealthScoreCalculator.HealthScoreBreakdown b = calculator.computeBreakdown(
                85.0, 300.0, 2000.0, 3, 100, false, true, true);
        String hint = HealthScoreCalculator.buildHint(b);
        assertTrue(hint.contains("Health " + b.total() + "/100"));
    }

    @Test
    @DisplayName("missing head-delay data is excluded, not granted for free")
    void missingHeadDelayIsExcludedNotGranted() {
        // Same imperfect node (90% uptime, 100ms latency), once with head-delay data
        // and once without. Without data the factor must not be a free 15 points:
        // tracked:   (27 + 19 + 13.5*... ) — with a tiny 50ms delay, nearly full factor
        // untracked: (27 + 19 + 10) / 60 * 100 = 93
        int untracked = calculator.calculateHealthScore(90.0, 100.0, 0.0, 0, 100, false, false, false);
        int trackedTiny = calculator.calculateHealthScore(90.0, 100.0, 50.0, 0, 100, false, false, false);
        assertEquals(93, untracked);
        // A node with excellent measured head delay may score slightly higher than one
        // with no data at all — the unmeasured factor is no longer an automatic win.
        assertTrue(trackedTiny >= untracked);
    }

    @Test
    @DisplayName("buildHint marks head delay as not counted when no data exists")
    void buildHintShowsHeadDelayNotCounted() {
        HealthScoreCalculator.HealthScoreBreakdown b = calculator.computeBreakdown(
                100.0, 50.0, 0.0, 0, 100, false, false, false);
        String hint = HealthScoreCalculator.buildHint(b);
        assertTrue(hint.contains("no data"));
        assertTrue(hint.contains("rescaled"));
    }
}

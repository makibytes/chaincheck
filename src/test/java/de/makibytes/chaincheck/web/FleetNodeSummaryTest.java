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
package de.makibytes.chaincheck.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("FleetNodeSummary label and CSS helpers")
class FleetNodeSummaryTest {

    @ParameterizedTest(name = "score {0} → label {1}")
    @CsvSource({
        "100, Excellent",
        "80,  Excellent",
        "79,  Good",
        "50,  Good",
        "49,  Degraded",
        "20,  Degraded",
        "19,  Down",
        "0,   Down"
    })
    void labelForScore(int score, String expected) {
        assertEquals(expected.trim(), FleetNodeSummary.labelForScore(score));
    }

    @ParameterizedTest(name = "score {0} → css {1}")
    @CsvSource({
        "100, health-excellent",
        "80,  health-excellent",
        "79,  health-good",
        "50,  health-good",
        "49,  health-degraded",
        "20,  health-degraded",
        "19,  health-down",
        "0,   health-down"
    })
    void cssClassForScore(int score, String expected) {
        assertEquals(expected.trim(), FleetNodeSummary.cssClassForScore(score));
    }

    @Test
    @DisplayName("label thresholds are boundary-inclusive at 80 and 50 and 20")
    void labelBoundaryValues() {
        assertEquals("Excellent", FleetNodeSummary.labelForScore(80));
        assertEquals("Good",      FleetNodeSummary.labelForScore(50));
        assertEquals("Degraded",  FleetNodeSummary.labelForScore(20));
        assertEquals("Down",      FleetNodeSummary.labelForScore(19));
    }

    @Test
    @DisplayName("label and cssClass agree at all thresholds")
    void labelAndCssAgreement() {
        int[] scores = {0, 19, 20, 49, 50, 79, 80, 100};
        for (int score : scores) {
            String label = FleetNodeSummary.labelForScore(score);
            String css   = FleetNodeSummary.cssClassForScore(score);
            String expectedCss = "health-" + label.toLowerCase();
            assertEquals(expectedCss, css,
                    "CSS class does not match label for score " + score);
        }
    }
}

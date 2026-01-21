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
package de.makibytes.chaincheck.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TimeRange Tests")
class TimeRangeTest {

    @Test
    @DisplayName("fromKey: should return HOURS_2 for '2h'")
    void testFromKey_2h() {
        TimeRange result = TimeRange.fromKey("2h");
        assertEquals(TimeRange.HOURS_2, result);
    }

    @Test
    @DisplayName("fromKey: should return DAYS_3 for '3d'")
    void testFromKey_3d() {
        TimeRange result = TimeRange.fromKey("3d");
        assertEquals(TimeRange.DAYS_3, result);
    }

    @Test
    @DisplayName("fromKey: should return MONTH_1 for '1m'")
    void testFromKey_1m() {
        TimeRange result = TimeRange.fromKey("1m");
        assertEquals(TimeRange.MONTH_1, result);
    }

    @Test
    @DisplayName("fromKey: should handle case-insensitive input")
    void testFromKey_CaseInsensitive() {
        assertEquals(TimeRange.HOURS_2, TimeRange.fromKey("2H"));
        assertEquals(TimeRange.DAYS_3, TimeRange.fromKey("3D"));
        assertEquals(TimeRange.MONTH_1, TimeRange.fromKey("1M"));
    }

    @Test
    @DisplayName("fromKey: should handle whitespace")
    void testFromKey_WithWhitespace() {
        assertEquals(TimeRange.HOURS_2, TimeRange.fromKey("  2h  "));
        assertEquals(TimeRange.DAYS_3, TimeRange.fromKey("\t3d\t"));
    }

    @Test
    @DisplayName("fromKey: should return default (HOURS_2) for null")
    void testFromKey_Null() {
        TimeRange result = TimeRange.fromKey(null);
        assertEquals(TimeRange.HOURS_2, result);
    }

    @Test
    @DisplayName("fromKey: should return default (HOURS_2) for unknown key")
    void testFromKey_UnknownKey() {
        TimeRange result = TimeRange.fromKey("5x");
        assertEquals(TimeRange.HOURS_2, result);
    }

    @Test
    @DisplayName("fromKey: should return default (HOURS_2) for empty string")
    void testFromKey_EmptyString() {
        TimeRange result = TimeRange.fromKey("");
        assertEquals(TimeRange.HOURS_2, result);
    }

    @Test
    @DisplayName("getKey: should return correct string representation")
    void testGetKey() {
        assertEquals("2h", TimeRange.HOURS_2.getKey());
        assertEquals("3d", TimeRange.DAYS_3.getKey());
        assertEquals("1m", TimeRange.MONTH_1.getKey());
    }

    @Test
    @DisplayName("getDuration: should return correct duration")
    void testGetDuration() {
        assertEquals(java.time.Duration.ofHours(2), TimeRange.HOURS_2.getDuration());
        assertEquals(java.time.Duration.ofDays(3), TimeRange.DAYS_3.getDuration());
        assertEquals(java.time.Duration.ofDays(30), TimeRange.MONTH_1.getDuration());
    }
}

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

import java.time.Duration;
import java.util.Locale;

public enum TimeRange {
    HOURS_24("24h", Duration.ofHours(24)),
    DAYS_7("7d", Duration.ofDays(7)),
    DAYS_30("30d", Duration.ofDays(30)),
    DAYS_365("365d", Duration.ofDays(365));

    private final String key;
    private final Duration duration;

    TimeRange(String key, Duration duration) {
        this.key = key;
        this.duration = duration;
    }

    public String getKey() {
        return key;
    }

    public Duration getDuration() {
        return duration;
    }

    public static TimeRange fromKey(String key) {
        if (key == null) {
            return HOURS_24;
        }
        String normalized = key.toLowerCase(Locale.ROOT).trim();
        for (TimeRange range : values()) {
            if (range.key.equals(normalized)) {
                return range;
            }
        }
        return HOURS_24;
    }
}

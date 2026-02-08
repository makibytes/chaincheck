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

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import de.makibytes.chaincheck.model.TimeRange;

@Component
public class MetricsCache {

    private static final Duration TTL = Duration.ofSeconds(2);
    static final int MAX_ENTRIES = 50;

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public DashboardView get(String nodeKey, TimeRange range, Instant end) {
        CacheEntry entry = cache.get(key(nodeKey, range, end));
        if (entry == null) {
            return null;
        }
        if (entry.generatedAt.plus(TTL).isBefore(Instant.now())) {
            return null;
        }
        return entry.view;
    }

    public void put(String nodeKey, TimeRange range, Instant end, DashboardView view) {
        cache.put(key(nodeKey, range, end), new CacheEntry(view, view.getGeneratedAt()));
        if (cache.size() > MAX_ENTRIES) {
            evict();
        }
    }

    private void evict() {
        Instant now = Instant.now();
        cache.entrySet().removeIf(entry -> entry.getValue().generatedAt.plus(TTL).isBefore(now));
        if (cache.size() > MAX_ENTRIES) {
            cache.entrySet().stream()
                    .min(Map.Entry.comparingByValue(java.util.Comparator.comparing(CacheEntry::generatedAt)))
                    .ifPresent(oldest -> cache.remove(oldest.getKey()));
        }
    }

    int size() {
        return cache.size();
    }

    private String key(String nodeKey, TimeRange range, Instant end) {
        long endEpochMs = end == null ? 0 : end.toEpochMilli();
        return nodeKey + ":" + range.getKey() + ":" + endEpochMs;
    }

    private record CacheEntry(DashboardView view, Instant generatedAt) {
    }
}

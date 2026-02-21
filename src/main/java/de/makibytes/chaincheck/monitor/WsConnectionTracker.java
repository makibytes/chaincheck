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
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class WsConnectionTracker {

    private final AtomicLong connectCount = new AtomicLong();
    private final AtomicLong disconnectCount = new AtomicLong();
    private final AtomicLong connectFailureCount = new AtomicLong();
    private final AtomicReference<Instant> connectedSince = new AtomicReference<>();
    private final AtomicReference<Instant> lastDisconnectedAt = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private final ConcurrentLinkedDeque<Instant> disconnectTimestamps = new ConcurrentLinkedDeque<>();

    public void onConnect() {
        connectCount.incrementAndGet();
        connectedSince.set(Instant.now());
        lastError.set(null);
    }

    public void setLastError(String message) {
        if (message == null || message.isBlank()) {
            lastError.set(null);
        } else {
            lastError.set(message);
        }
    }

    public void clearLastError() {
        lastError.set(null);
    }

    public void onDisconnect() {
        disconnectCount.incrementAndGet();
        connectedSince.set(null);
        Instant now = Instant.now();
        lastDisconnectedAt.set(now);
        disconnectTimestamps.add(now);
        
        // Clean up old timestamps (keep last 30 days to limit memory)
        Instant cutoff = now.minusSeconds(30L * 24 * 60 * 60);
        while (!disconnectTimestamps.isEmpty() && disconnectTimestamps.peekFirst().isBefore(cutoff)) {
            disconnectTimestamps.pollFirst();
        }
    }

    public void onConnectFailure(Throwable error) {
        connectFailureCount.incrementAndGet();
        if (error != null) {
            lastError.set(error.getMessage());
        }
    }

    public void onError(Throwable error) {
        if (error != null) {
            lastError.set(error.getMessage());
        }
    }

    public long getConnectCount() {
        return connectCount.get();
    }

    public long getDisconnectCount() {
        return disconnectCount.get();
    }

    public long getDisconnectCountSince(Instant since) {
        return disconnectTimestamps.stream()
                .filter(timestamp -> timestamp.isAfter(since))
                .count();
    }

    public long getConnectFailureCount() {
        return connectFailureCount.get();
    }

    public Instant getConnectedSince() {
        return connectedSince.get();
    }

    public Instant getLastDisconnectedAt() {
        return lastDisconnectedAt.get();
    }

    public String getLastError() {
        return lastError.get();
    }
}
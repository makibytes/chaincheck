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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class HttpConnectionTracker {

    private final AtomicLong errorCount = new AtomicLong();
    private final AtomicReference<Instant> connectedSince = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>();

    public void onSuccess() {
        connectedSince.set(Instant.now());
        lastError.set(null);
    }

    public void onErrorMessage(String message) {
        errorCount.incrementAndGet();
        if (message == null || message.isBlank()) {
            lastError.set(null);
        } else {
            lastError.set(message);
        }
    }

    public long getErrorCount() {
        return errorCount.get();
    }

    public Instant getConnectedSince() {
        return connectedSince.get();
    }

    public String getLastError() {
        return lastError.get();
    }
}

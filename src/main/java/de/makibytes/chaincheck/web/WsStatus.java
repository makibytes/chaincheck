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

import java.time.Instant;

public class WsStatus {

    private final boolean connected;
    private final Instant connectedSince;
    private final Instant lastDisconnectedAt;
    private final long connectCount;
    private final long disconnectCount;
    private final long connectFailureCount;
    private final String lastError;

    public WsStatus(boolean connected,
                    Instant connectedSince,
                    Instant lastDisconnectedAt,
                    long connectCount,
                    long disconnectCount,
                    long connectFailureCount,
                    String lastError) {
        this.connected = connected;
        this.connectedSince = connectedSince;
        this.lastDisconnectedAt = lastDisconnectedAt;
        this.connectCount = connectCount;
        this.disconnectCount = disconnectCount;
        this.connectFailureCount = connectFailureCount;
        this.lastError = lastError;
    }

    public boolean isConnected() {
        return connected;
    }

    public Instant getConnectedSince() {
        return connectedSince;
    }

    public Instant getLastDisconnectedAt() {
        return lastDisconnectedAt;
    }

    public long getConnectCount() {
        return connectCount;
    }

    public long getDisconnectCount() {
        return disconnectCount;
    }

    public long getConnectFailureCount() {
        return connectFailureCount;
    }

    public String getLastError() {
        return lastError;
    }
}
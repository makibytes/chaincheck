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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

class WsConnectionTrackerTest {

    @Test
    void clearsLastErrorOnConnectAndManualClear() {
        WsConnectionTracker tracker = new WsConnectionTracker();

        tracker.setLastError("ws failed");
        assertEquals("ws failed", tracker.getLastError());

        tracker.clearLastError();
        assertNull(tracker.getLastError());

        tracker.setLastError("ws failed again");
        tracker.onConnect();
        assertNull(tracker.getLastError());
    }

    @Test
    void ignoresBlankErrorMessages() {
        WsConnectionTracker tracker = new WsConnectionTracker();

        tracker.setLastError(" ");
        assertNull(tracker.getLastError());
    }

    @Test
    void tracksDisconnectsWithinTimeWindow() {
        WsConnectionTracker tracker = new WsConnectionTracker();
        
        // Record some disconnects
        tracker.onDisconnect();
        tracker.onDisconnect();
        tracker.onDisconnect();
        
        // Should count all disconnects
        assertEquals(3, tracker.getDisconnectCount());
        
        // Should count all recent disconnects (within the last minute)
        Instant oneMinuteAgo = Instant.now().minusSeconds(60);
        assertEquals(3, tracker.getDisconnectCountSince(oneMinuteAgo));
        
        // Should not count disconnects from the future
        Instant future = Instant.now().plusSeconds(60);
        assertEquals(0, tracker.getDisconnectCountSince(future));
    }
}

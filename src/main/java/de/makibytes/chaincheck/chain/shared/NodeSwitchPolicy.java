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
package de.makibytes.chaincheck.chain.shared;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Policy for managing reference node switches with hysteresis.
 * Prevents rapid switching between nodes.
 */
public class NodeSwitchPolicy {

    /**
     * Default window size for tracking recent selections.
     */
    public static final int DEFAULT_WINDOW_SIZE = 20;

    /**
     * Default threshold for allowing a node switch.
     */
    public static final int DEFAULT_SWITCH_THRESHOLD = 15;

    private final int windowSize;
    private final int switchThreshold;
    private final Deque<String> selections = new ArrayDeque<>();

    public NodeSwitchPolicy() {
        this(DEFAULT_WINDOW_SIZE, DEFAULT_SWITCH_THRESHOLD);
    }

    public NodeSwitchPolicy(int windowSize, int switchThreshold) {
        this.windowSize = Math.max(1, windowSize);
        this.switchThreshold = Math.max(1, switchThreshold);
    }

    public void registerSelection(String nodeKey) {
        selections.addLast(nodeKey);
        while (selections.size() > windowSize) {
            selections.removeFirst();
        }
    }

    public boolean shouldSwitchTo(String nodeKey) {
        int count = 0;
        for (String key : selections) {
            if (nodeKey.equals(key)) {
                count++;
            }
        }
        return count >= switchThreshold;
    }

    public void reset() {
        selections.clear();
    }

    /**
     * Gets the current window size.
     */
    public int getWindowSize() {
        return windowSize;
    }

    /**
     * Gets the current switch threshold.
     */
    public int getSwitchThreshold() {
        return switchThreshold;
    }
}

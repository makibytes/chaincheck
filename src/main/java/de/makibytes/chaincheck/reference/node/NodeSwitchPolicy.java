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
package de.makibytes.chaincheck.reference.node;

import java.util.ArrayDeque;
import java.util.Deque;

public class NodeSwitchPolicy {

    private final int windowSize;
    private final int switchThreshold;
    private final Deque<String> selections = new ArrayDeque<>();

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
}

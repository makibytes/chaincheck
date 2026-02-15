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
package de.makibytes.chaincheck.reference.block;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents the reference blocks determined by voting across all nodes.
 * Maps block numbers to confidence levels and their voted hashes.
 */
public class ReferenceBlocks {

    public enum Confidence {
        NEW, SAFE, FINALIZED
    }

    private final Map<Long, Map<Confidence, String>> blocks = new HashMap<>();

    /**
     * Sets the voted hash for a given block number and confidence level.
     */
    public void setHash(long blockNumber, Confidence confidence, String hash) {
        blocks.computeIfAbsent(blockNumber, k -> new HashMap<>()).put(confidence, hash);
    }

    /**
     * Gets the voted hash for a given block number and confidence level.
     */
    public String getHash(long blockNumber, Confidence confidence) {
        Map<Confidence, String> confMap = blocks.get(blockNumber);
        return confMap != null ? confMap.get(confidence) : null;
    }

    /**
     * Checks if reference blocks are established (have at least some blocks).
     */
    public boolean isEstablished() {
        return !blocks.isEmpty();
    }

    /**
     * Clears all reference blocks.
     */
    public void clear() {
        blocks.clear();
    }

    /**
     * Gets the internal blocks map.
     */
    public Map<Long, Map<Confidence, String>> getBlocks() {
        return blocks;
    }
}
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
package de.makibytes.chaincheck.store;

import java.time.Instant;

import de.makibytes.chaincheck.model.AnomalyEvent;

public class AnomalyAggregate {

    private final Instant bucketStart;
    private long totalCount;
    private long delayCount;
    private long reorgCount;
    private long blockGapCount;
    private long errorCount;

    public AnomalyAggregate(Instant bucketStart) {
        this.bucketStart = bucketStart;
    }

    public void addEvent(AnomalyEvent event) {
        totalCount++;
        switch (event.getType()) {
            case DELAY -> delayCount++;
            case REORG -> reorgCount++;
            case BLOCK_GAP -> blockGapCount++;
            case ERROR -> errorCount++;
        }
    }

    public Instant getBucketStart() {
        return bucketStart;
    }

    public long getTotalCount() {
        return totalCount;
    }

    public long getDelayCount() {
        return delayCount;
    }

    public long getReorgCount() {
        return reorgCount;
    }

    public long getBlockGapCount() {
        return blockGapCount;
    }

    public long getErrorCount() {
        return errorCount;
    }
}
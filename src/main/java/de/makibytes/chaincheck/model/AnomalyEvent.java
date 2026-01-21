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

import java.time.Instant;

public class AnomalyEvent {

    private final long id;
    private final String nodeKey;
    private final Instant timestamp;
    private final MetricSource source;
    private final AnomalyType type;
    private final String message;
    private final Long blockNumber;
    private final String blockHash;
    private final String parentHash;
    private final String details;
    private volatile boolean closed = false;

    public AnomalyEvent(long id,
                        String nodeKey,
                        Instant timestamp,
                        MetricSource source,
                        AnomalyType type,
                        String message,
                        Long blockNumber,
                        String blockHash,
                        String parentHash,
                        String details) {
        this.id = id;
        this.nodeKey = nodeKey;
        this.timestamp = timestamp;
        this.source = source;
        this.type = type;
        this.message = message;
        this.blockNumber = blockNumber;
        this.blockHash = blockHash;
        this.parentHash = parentHash;
        this.details = details;
    }

    public long getId() {
        return id;
    }

    public String getNodeKey() {
        return nodeKey;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public MetricSource getSource() {
        return source;
    }

    public AnomalyType getType() {
        return type;
    }

    public String getMessage() {
        return message;
    }

    public Long getBlockNumber() {
        return blockNumber;
    }

    public String getBlockHash() {
        return blockHash;
    }

    public String getParentHash() {
        return parentHash;
    }

    public String getDetails() {
        return details;
    }

    public boolean isClosed() {
        return closed;
    }

    public void setClosed(boolean closed) {
        this.closed = closed;
    }
}

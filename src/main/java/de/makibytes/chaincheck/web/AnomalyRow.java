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

public class AnomalyRow {

    private final long id;
    private final String time;
    private final String type;
    private final String source;
    private final String message;
    private final Long blockNumber;
    private final String blockHash;
    private final String parentHash;
    private final String details;
    private final int count;
    private final boolean isGrouped;

    public AnomalyRow(long id, String time, String type, String source, String message) {
        this(id, time, type, source, message, null, null, null, null, 1, false);
    }

    public AnomalyRow(long id,
                      String time,
                      String type,
                      String source,
                      String message,
                      Long blockNumber,
                      String blockHash,
                      String parentHash,
                      String details) {
        this(id, time, type, source, message, blockNumber, blockHash, parentHash, details, 1, false);
    }

    public AnomalyRow(long id,
                      String time,
                      String type,
                      String source,
                      String message,
                      Long blockNumber,
                      String blockHash,
                      String parentHash,
                      String details,
                      int count,
                      boolean isGrouped) {
        this.id = id;
        this.time = time;
        this.type = type;
        this.source = source;
        this.message = message;
        this.blockNumber = blockNumber;
        this.blockHash = blockHash;
        this.parentHash = parentHash;
        this.details = details;
        this.count = count;
        this.isGrouped = isGrouped;
    }

    public long getId() {
        return id;
    }

    public String getTime() {
        return time;
    }

    public String getType() {
        return type;
    }

    public String getSource() {
        return source;
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

    public int getCount() {
        return count;
    }

    public boolean isGrouped() {
        return isGrouped;
    }
}
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

import java.util.List;

import de.makibytes.chaincheck.model.TimeRange;

public class FleetView {

    private final List<FleetNodeSummary> nodes;
    private final String referenceNodeKey;
    private final TimeRange range;
    private final long maxBlockNumber;

    public FleetView(List<FleetNodeSummary> nodes, String referenceNodeKey, TimeRange range, long maxBlockNumber) {
        this.nodes = nodes != null ? List.copyOf(nodes) : List.of();
        this.referenceNodeKey = referenceNodeKey;
        this.range = range;
        this.maxBlockNumber = maxBlockNumber;
    }

    public List<FleetNodeSummary> getNodes() {
        return nodes;
    }

    public String getReferenceNodeKey() {
        return referenceNodeKey;
    }

    public TimeRange getRange() {
        return range;
    }

    public long getMaxBlockNumber() {
        return maxBlockNumber;
    }
}

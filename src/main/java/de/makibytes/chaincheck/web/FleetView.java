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

public record FleetView(List<FleetNodeSummary> nodes,
                        String referenceNodeKey,
                        TimeRange range,
                        long maxBlockNumber) {

    public FleetView {
        nodes = nodes != null ? List.copyOf(nodes) : List.of();
    }

    public long onlineCount() {
        return nodes.stream().filter(n -> n.healthScore() > 0).count();
    }

    public long healthyCount() {
        return nodes.stream().filter(n -> n.healthScore() >= 80).count();
    }

    public long attentionCount() {
        return nodes.stream().filter(n -> n.healthScore() < 50).count();
    }

    public long totalAnomalies() {
        return nodes.stream().mapToLong(FleetNodeSummary::anomalyCount).sum();
    }
}

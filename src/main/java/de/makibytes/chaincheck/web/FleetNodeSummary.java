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

public record FleetNodeSummary(
    String nodeKey,
    String nodeName,
    boolean httpConfigured,
    boolean wsConfigured,
    boolean httpUp,
    boolean wsUp,
    int healthScore,
    String healthLabel,
    double uptimePercent,
    double p95LatencyMs,
    double p95HeadDelayMs,
    long blockLagBlocks,
    Long latestBlockNumber,
    Long lastBlockAgeMs,
    long anomalyCount,
    long wsDisconnectCount,
    boolean referenceNode,
    String latencySparklinePoints,
    String latencySparklineColoredPaths
) {
    public static String labelForScore(int score) {
        if (score >= 80) return "Excellent";
        if (score >= 50) return "Good";
        if (score >= 20) return "Degraded";
        return "Down";
    }

    public static String cssClassForScore(int score) {
        if (score >= 80) return "health-excellent";
        if (score >= 50) return "health-good";
        if (score >= 20) return "health-degraded";
        return "health-down";
    }
}

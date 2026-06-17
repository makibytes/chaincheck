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

public record DashboardSummary(long totalSamples,
                               long httpSamples,
                               long wsSamples,
                               long successCount,
                               long errorCount,
                               double avgLatencyMs,
                               long maxLatencyMs,
                               double p5LatencyMs,
                               double p25LatencyMs,
                               double p75LatencyMs,
                               double p95LatencyMs,
                               double p99LatencyMs,
                               double uptimePercent,
                               double errorRatePercent,
                               double avgNewBlockPropagationMs,
                               double p95NewBlockPropagationMs,
                               double p99NewBlockPropagationMs,
                               double avgSafeBlockPropagationMs,
                               double p95SafeBlockPropagationMs,
                               double p99SafeBlockPropagationMs,
                               double avgFinalizedBlockPropagationMs,
                               double p95FinalizedBlockPropagationMs,
                               double p99FinalizedBlockPropagationMs,
                               long staleBlockCount,
                               long blockLagBlocks,
                               long delayCount,
                               long reorgCount,
                               long blockGapCount,
                               long rateLimitCount,
                               long timeoutCount,
                               long wrongHeadCount,
                               long conflictCount,
                               long errorAnomalyCount,
                               double canonicalRatePercent,
                               long invalidBlockCount,
                               double wrongHeadRatePercent,
                               long maxReorgDepth,
                               long maxGapSize,
                               double avgFirstSeenDeltaMs,
                               double p95FirstSeenDeltaMs,
                               int healthScore,
                               String healthScoreHint) {
}

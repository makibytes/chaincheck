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

import java.time.Instant;
import java.util.List;

import de.makibytes.chaincheck.model.AnomalyEvent;
import de.makibytes.chaincheck.model.DashboardSummary;
import de.makibytes.chaincheck.model.TimeRange;

public class DashboardView {

    private final TimeRange range;
    private final DashboardSummary summary;
    private final List<AnomalyEvent> anomalies;
    private final List<AnomalyRow> anomalyRows;
    private final List<SampleRow> sampleRows;
    private final List<Long> chartTimestamps;
    private final List<String> chartLabels;
    private final List<Long> chartLatencies;
    private final List<Long> chartLatencyMins;
    private final List<Long> chartLatencyMaxs;
    private final List<Double> chartErrorRates;
    private final List<Double> chartWsErrorRates;
    private final List<Boolean> chartHttpErrors;
    private final List<Boolean> chartWsErrors;
    private final List<Long> chartHeadDelays;
    private final List<Long> chartHeadDelayMins;
    private final List<Long> chartHeadDelayMaxs;
    private final List<Long> chartSafeDelays;
    private final List<Long> chartSafeDelayMins;
    private final List<Long> chartSafeDelayMaxs;
    private final List<Long> chartFinalizedDelays;
    private final List<Long> chartFinalizedDelayMins;
    private final List<Long> chartFinalizedDelayMaxs;
    private final List<Long> chartReferenceHeadDelays;
    private final List<Long> chartReferenceSafeDelays;
    private final List<Long> chartReferenceFinalizedDelays;
    private final boolean hasAggregatedLatencies;
    private final boolean hasAggregatedDelays;
    private final boolean httpErrorOngoing;
    private final boolean wsErrorOngoing;
    private final boolean httpConfigured;
    private final boolean wsConfigured;
    private final boolean safeBlocksEnabled;
    private final boolean httpUp;
    private final boolean wsUp;
    private final Long latestBlockNumber;
    private final HttpStatus httpStatus;
    private final WsStatus wsStatus;
    private final int totalPages;
    private final int pageSize;
    private final int totalSamples;
    private final int anomalyTotalPages;
    private final int anomalyPageSize;
    private final int totalAnomalies;
    private final int scaleChangeMs;
    private final Instant generatedAt;
    private final ReferenceComparison referenceComparison;
    private final boolean isReferenceNode;

    public DashboardView(TimeRange range,
                         DashboardSummary summary,
                         List<AnomalyEvent> anomalies,
                         List<AnomalyRow> anomalyRows,
                         List<SampleRow> sampleRows,
                         List<Long> chartTimestamps,
                         List<String> chartLabels,
                         List<Long> chartLatencies,
                         List<Long> chartLatencyMins,
                         List<Long> chartLatencyMaxs,
                         List<Double> chartErrorRates,
                         List<Double> chartWsErrorRates,
                         List<Boolean> chartHttpErrors,
                         List<Boolean> chartWsErrors,
                         List<Long> chartHeadDelays,
                         List<Long> chartHeadDelayMins,
                         List<Long> chartHeadDelayMaxs,
                         List<Long> chartSafeDelays,
                         List<Long> chartSafeDelayMins,
                         List<Long> chartSafeDelayMaxs,
                         List<Long> chartFinalizedDelays,
                         List<Long> chartFinalizedDelayMins,
                         List<Long> chartFinalizedDelayMaxs,
                         List<Long> chartReferenceHeadDelays,
                         List<Long> chartReferenceSafeDelays,
                         List<Long> chartReferenceFinalizedDelays,
                         boolean hasAggregatedLatencies,
                         boolean hasAggregatedDelays,
                         boolean httpErrorOngoing,
                         boolean wsErrorOngoing,
                         boolean httpConfigured,
                         boolean wsConfigured,
                         boolean safeBlocksEnabled,
                         boolean httpUp,
                         boolean wsUp,
                         Long latestBlockNumber,
                         HttpStatus httpStatus,
                         WsStatus wsStatus,
                         int totalPages,
                         int pageSize,
                         int totalSamples,
                         int anomalyTotalPages,
                         int anomalyPageSize,
                         int totalAnomalies,
                         int scaleChangeMs,
                         Instant generatedAt,
                         ReferenceComparison referenceComparison,
                         boolean isReferenceNode) {
        this.range = range;
        this.summary = summary;
        this.anomalies = anomalies;
        this.anomalyRows = anomalyRows;
        this.sampleRows = sampleRows;
        this.chartTimestamps = chartTimestamps;
        this.chartLabels = chartLabels;
        this.chartLatencies = chartLatencies;
        this.chartLatencyMins = chartLatencyMins;
        this.chartLatencyMaxs = chartLatencyMaxs;
        this.chartErrorRates = chartErrorRates;
        this.chartWsErrorRates = chartWsErrorRates;
        this.chartHttpErrors = chartHttpErrors;
        this.chartWsErrors = chartWsErrors;
        this.chartHeadDelays = chartHeadDelays;
        this.chartHeadDelayMins = chartHeadDelayMins;
        this.chartHeadDelayMaxs = chartHeadDelayMaxs;
        this.chartSafeDelays = chartSafeDelays;
        this.chartSafeDelayMins = chartSafeDelayMins;
        this.chartSafeDelayMaxs = chartSafeDelayMaxs;
        this.chartFinalizedDelays = chartFinalizedDelays;
        this.chartFinalizedDelayMins = chartFinalizedDelayMins;
        this.chartFinalizedDelayMaxs = chartFinalizedDelayMaxs;
        this.chartReferenceHeadDelays = chartReferenceHeadDelays;
        this.chartReferenceSafeDelays = chartReferenceSafeDelays;
        this.chartReferenceFinalizedDelays = chartReferenceFinalizedDelays;
        this.hasAggregatedLatencies = hasAggregatedLatencies;
        this.hasAggregatedDelays = hasAggregatedDelays;
        this.httpErrorOngoing = httpErrorOngoing;
        this.wsErrorOngoing = wsErrorOngoing;
        this.httpConfigured = httpConfigured;
        this.wsConfigured = wsConfigured;
        this.safeBlocksEnabled = safeBlocksEnabled;
        this.httpUp = httpUp;
        this.wsUp = wsUp;
        this.latestBlockNumber = latestBlockNumber;
        this.httpStatus = httpStatus;
        this.wsStatus = wsStatus;
        this.totalPages = totalPages;
        this.pageSize = pageSize;
        this.totalSamples = totalSamples;
        this.anomalyTotalPages = anomalyTotalPages;
        this.anomalyPageSize = anomalyPageSize;
        this.totalAnomalies = totalAnomalies;
        this.scaleChangeMs = scaleChangeMs;
        this.generatedAt = generatedAt;
        this.referenceComparison = referenceComparison;
        this.isReferenceNode = isReferenceNode;
    }

    public TimeRange getRange() {
        return range;
    }

    public DashboardSummary getSummary() {
        return summary;
    }

    public List<AnomalyEvent> getAnomalies() {
        return anomalies;
    }

    public List<AnomalyRow> getAnomalyRows() {
        return anomalyRows;
    }

    public List<SampleRow> getSampleRows() {
        return sampleRows;
    }

    public List<Long> getChartTimestamps() {
        return chartTimestamps;
    }

    public List<String> getChartLabels() {
        return chartLabels;
    }

    public List<Long> getChartLatencies() {
        return chartLatencies;
    }

    public List<Long> getChartLatencyMins() {
        return chartLatencyMins;
    }

    public List<Long> getChartLatencyMaxs() {
        return chartLatencyMaxs;
    }

    public List<Double> getChartErrorRates() {
        return chartErrorRates;
    }

    public List<Double> getChartWsErrorRates() {
        return chartWsErrorRates;
    }

    public List<Boolean> getChartHttpErrors() {
        return chartHttpErrors;
    }

    public List<Boolean> getChartWsErrors() {
        return chartWsErrors;
    }

    public List<Long> getChartHeadDelays() {
        return chartHeadDelays;
    }

    public List<Long> getChartHeadDelayMins() {
        return chartHeadDelayMins;
    }

    public List<Long> getChartHeadDelayMaxs() {
        return chartHeadDelayMaxs;
    }

    public List<Long> getChartReferenceHeadDelays() {
        return chartReferenceHeadDelays;
    }

    public List<Long> getChartSafeDelays() {
        return chartSafeDelays;
    }

    public List<Long> getChartSafeDelayMins() {
        return chartSafeDelayMins;
    }

    public List<Long> getChartSafeDelayMaxs() {
        return chartSafeDelayMaxs;
    }

    public List<Long> getChartReferenceSafeDelays() {
        return chartReferenceSafeDelays;
    }

    public List<Long> getChartFinalizedDelays() {
        return chartFinalizedDelays;
    }

    public List<Long> getChartFinalizedDelayMins() {
        return chartFinalizedDelayMins;
    }

    public List<Long> getChartFinalizedDelayMaxs() {
        return chartFinalizedDelayMaxs;
    }

    public List<Long> getChartReferenceFinalizedDelays() {
        return chartReferenceFinalizedDelays;
    }

    public boolean hasAggregatedLatencies() {
        return hasAggregatedLatencies;
    }

    public boolean hasAggregatedDelays() {
        return hasAggregatedDelays;
    }

    public boolean isHttpErrorOngoing() {
        return httpErrorOngoing;
    }

    public boolean isWsErrorOngoing() {
        return wsErrorOngoing;
    }

    public boolean isHttpConfigured() {
        return httpConfigured;
    }

    public boolean isWsConfigured() {
        return wsConfigured;
    }

    public boolean isSafeBlocksEnabled() {
        return safeBlocksEnabled;
    }

    public boolean isHttpUp() {
        return httpUp;
    }

    public boolean isWsUp() {
        return wsUp;
    }

    public Long getLatestBlockNumber() {
        return latestBlockNumber;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public WsStatus getWsStatus() {
        return wsStatus;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public int getPageSize() {
        return pageSize;
    }

    public int getTotalSamples() {
        return totalSamples;
    }

    public int getAnomalyTotalPages() {
        return anomalyTotalPages;
    }

    public int getAnomalyPageSize() {
        return anomalyPageSize;
    }

    public int getTotalAnomalies() {
        return totalAnomalies;
    }

    public int getScaleChangeMs() {
        return scaleChangeMs;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public ReferenceComparison getReferenceComparison() {
        return referenceComparison;
    }

    public boolean isReferenceNode() {
        return isReferenceNode;
    }
}

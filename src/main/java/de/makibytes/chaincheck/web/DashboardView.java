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
    private final List<String> chartLabels;
    private final List<Long> chartLatencies;
    private final List<Double> chartErrorRates;
    private final List<Double> chartWsErrorRates;
    private final boolean httpConfigured;
    private final boolean wsConfigured;
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
    private final Instant generatedAt;

    public DashboardView(TimeRange range,
                         DashboardSummary summary,
                         List<AnomalyEvent> anomalies,
                         List<AnomalyRow> anomalyRows,
                         List<SampleRow> sampleRows,
                         List<String> chartLabels,
                         List<Long> chartLatencies,
                         List<Double> chartErrorRates,
                         List<Double> chartWsErrorRates,
                         boolean httpConfigured,
                         boolean wsConfigured,
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
                         Instant generatedAt) {
        this.range = range;
        this.summary = summary;
        this.anomalies = anomalies;
        this.anomalyRows = anomalyRows;
        this.sampleRows = sampleRows;
        this.chartLabels = chartLabels;
        this.chartLatencies = chartLatencies;
        this.chartErrorRates = chartErrorRates;
        this.chartWsErrorRates = chartWsErrorRates;
        this.httpConfigured = httpConfigured;
        this.wsConfigured = wsConfigured;
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
        this.generatedAt = generatedAt;
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

    public List<String> getChartLabels() {
        return chartLabels;
    }

    public List<Long> getChartLatencies() {
        return chartLatencies;
    }

    public List<Double> getChartErrorRates() {
        return chartErrorRates;
    }

    public List<Double> getChartWsErrorRates() {
        return chartWsErrorRates;
    }

    public boolean isHttpConfigured() {
        return httpConfigured;
    }

    public boolean isWsConfigured() {
        return wsConfigured;
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

    public Instant getGeneratedAt() {
        return generatedAt;
    }
}

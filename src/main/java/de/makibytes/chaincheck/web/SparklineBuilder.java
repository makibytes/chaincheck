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

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Component;

import de.makibytes.chaincheck.model.MetricSample;

/**
 * Builds SVG sparkline point data from metric samples.
 * Responsible for aggregating time-series data into visual representations.
 */
@Component
public class SparklineBuilder {

    private static final int SPARKLINE_BUCKETS = 60;
    private static final long SPARKLINE_WINDOW_SECONDS = 60 * 60L; // 1 hour
    private static final int SPARKLINE_SVG_WIDTH = 60;
    private static final int SPARKLINE_SVG_HEIGHT = 28;
    private static final int SPARKLINE_PADDING = 2;

    /**
     * Builds SVG polyline points from latency samples over a 1-hour window.
     * 
     * @param rawSamples metric samples to visualize
     * @param now current timestamp
     * @return SVG polyline points string (e.g., "1.0,5.2 2.0,8.7 ...")
     */
    public String buildPoints(List<MetricSample> rawSamples, Instant now) {
        int numBuckets = SPARKLINE_BUCKETS;
        long windowSecs = SPARKLINE_WINDOW_SECONDS;
        long bucketSecs = windowSecs / numBuckets;
        Instant start = now.minusSeconds(windowSecs);

        double[] bucketSum = new double[numBuckets];
        int[] bucketCount = new int[numBuckets];

        // Aggregate samples into time buckets
        for (MetricSample s : rawSamples) {
            if (s.getLatencyMs() < 0) continue;
            long offset = Duration.between(start, s.getTimestamp()).toSeconds();
            if (offset < 0 || offset >= windowSecs) continue;
            int idx = (int) (offset / bucketSecs);
            if (idx >= numBuckets) idx = numBuckets - 1;
            bucketSum[idx] += s.getLatencyMs();
            bucketCount[idx]++;
        }

        // Compute bucket averages and find max for scaling
        Double[] avgs = new Double[numBuckets];
        double maxVal = 0;
        for (int i = 0; i < numBuckets; i++) {
            if (bucketCount[i] > 0) {
                avgs[i] = bucketSum[i] / bucketCount[i];
                maxVal = Math.max(maxVal, avgs[i]);
            }
        }
        if (maxVal == 0) return "";

        // Generate SVG points
        int svgWidth = SPARKLINE_SVG_WIDTH;
        int svgHeight = SPARKLINE_SVG_HEIGHT;
        int padding = SPARKLINE_PADDING;
        double xStep = (double) svgWidth / numBuckets;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < numBuckets; i++) {
            if (avgs[i] == null) continue;
            double x = i * xStep + xStep / 2.0;
            double y = (svgHeight - padding) - (avgs[i] / maxVal) * (svgHeight - 2 * padding);
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("%.1f,%.1f", x, y));
        }
        return sb.toString();
    }
}

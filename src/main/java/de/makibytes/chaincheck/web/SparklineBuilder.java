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

import de.makibytes.chaincheck.config.ChainCheckProperties.SparklineDataSource;
import de.makibytes.chaincheck.model.MetricSample;
import de.makibytes.chaincheck.model.MetricSource;

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
     * Defaults to latency data source.
     * 
     * @param rawSamples metric samples to visualize
     * @param now current timestamp
     * @return SVG polyline points string (e.g., "1.0,5.2 2.0,8.7 ...")
     */
    public String buildPoints(List<MetricSample> rawSamples, Instant now) {
        return buildPoints(rawSamples, now, SparklineDataSource.LATENCY);
    }

    /**
     * Builds SVG polyline points from samples over a 1-hour window using specified data source.
     * 
     * @param rawSamples metric samples to visualize
     * @param now current timestamp
     * @param dataSource which metric to use (LATENCY or NEWHEAD)
     * @return SVG polyline points string (e.g., "1.0,5.2 2.0,8.7 ...")
     */
    public String buildPoints(List<MetricSample> rawSamples, Instant now, SparklineDataSource dataSource) {
        int numBuckets = SPARKLINE_BUCKETS;
        long windowSecs = SPARKLINE_WINDOW_SECONDS;
        long bucketSecs = windowSecs / numBuckets;
        Instant start = now.minusSeconds(windowSecs);

        double[] bucketSum = new double[numBuckets];
        int[] bucketCount = new int[numBuckets];

        // Aggregate samples into time buckets
        for (MetricSample s : rawSamples) {
            long valueMs = getValueForDataSource(s, dataSource);
            if (valueMs < 0) continue;
            long offset = Duration.between(start, s.getTimestamp()).toSeconds();
            if (offset < 0 || offset >= windowSecs) continue;
            int idx = (int) (offset / bucketSecs);
            if (idx >= numBuckets) idx = numBuckets - 1;
            bucketSum[idx] += valueMs;
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

    /**
     * Builds SVG path segments with color strokes based on latency values.
     * Each segment uses a gradient color: blue < 200ms -> purple < 500ms -> red > 500ms
     * Defaults to latency data source.
     * 
     * @param rawSamples metric samples to visualize
     * @param now current timestamp
     * @return SVG path elements with colored strokes
     */
    public String buildColoredPaths(List<MetricSample> rawSamples, Instant now) {
        return buildColoredPaths(rawSamples, now, SparklineDataSource.LATENCY);
    }

    /**
     * Builds SVG path segments with color strokes using specified data source.
     * Each segment uses a gradient color based on the data source thresholds.
     * 
     * @param rawSamples metric samples to visualize
     * @param now current timestamp
     * @param dataSource which metric to use (LATENCY or NEWHEAD)
     * @return SVG path elements with colored strokes
     */
    public String buildColoredPaths(List<MetricSample> rawSamples, Instant now, SparklineDataSource dataSource) {
        int numBuckets = SPARKLINE_BUCKETS;
        long windowSecs = SPARKLINE_WINDOW_SECONDS;
        long bucketSecs = windowSecs / numBuckets;
        Instant start = now.minusSeconds(windowSecs);

        double[] bucketSum = new double[numBuckets];
        int[] bucketCount = new int[numBuckets];

        // Aggregate samples into time buckets
        for (MetricSample s : rawSamples) {
            long valueMs = getValueForDataSource(s, dataSource);
            if (valueMs < 0) continue;
            long offset = Duration.between(start, s.getTimestamp()).toSeconds();
            if (offset < 0 || offset >= windowSecs) continue;
            int idx = (int) (offset / bucketSecs);
            if (idx >= numBuckets) idx = numBuckets - 1;
            bucketSum[idx] += valueMs;
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

        // Generate colored path segments
        int svgWidth = SPARKLINE_SVG_WIDTH;
        int svgHeight = SPARKLINE_SVG_HEIGHT;
        int padding = SPARKLINE_PADDING;
        double xStep = (double) svgWidth / numBuckets;
        StringBuilder sb = new StringBuilder();
        
        Double[] points = new Double[numBuckets * 2];
        int pointIdx = 0;
        for (int i = 0; i < numBuckets; i++) {
            if (avgs[i] == null) {
                points[pointIdx++] = null;
                points[pointIdx++] = null;
            } else {
                double x = i * xStep + xStep / 2.0;
                double y = (svgHeight - padding) - (avgs[i] / maxVal) * (svgHeight - 2 * padding);
                points[pointIdx++] = x;
                points[pointIdx++] = y;
            }
        }
        
        // Build path segments with color strokes
        for (int i = 0; i < numBuckets - 1; i++) {
            int idx1 = i * 2;
            int idx2 = (i + 1) * 2;
            
            if (points[idx1] != null && points[idx2] != null) {
                double x1 = points[idx1];
                double y1 = points[idx1 + 1];
                double x2 = points[idx2];
                double y2 = points[idx2 + 1];
                
                String color = getColorForValue(avgs[i], dataSource);
                String path = String.format(
                    "<path d=\"M %.1f %.1f L %.1f %.1f\" stroke=\"%s\" stroke-width=\"1\" fill=\"none\" stroke-linecap=\"round\"/>",
                    x1, y1, x2, y2, color
                );
                sb.append(path);
            }
        }
        
        return sb.toString();
    }

    /**
     * Gets the metric value from a sample based on the data source.
     * For NEWHEAD, uses headDelayMs from WS samples, falling back to latencyMs.
     * 
     * @param sample the metric sample
     * @param dataSource which metric to extract
     * @return the value in milliseconds, or -1 if not available
     */
    private long getValueForDataSource(MetricSample sample, SparklineDataSource dataSource) {
        if (dataSource == SparklineDataSource.NEWHEAD) {
            // For newHead, prefer headDelayMs from WS samples
            Long headDelay = sample.getHeadDelayMs();
            if (headDelay != null && headDelay >= 0) {
                return headDelay;
            }
            // Fall back to latency for HTTP samples
            return sample.getLatencyMs();
        }
        // Default to latency
        return sample.getLatencyMs();
    }

    /**
     * Gets RGB color for a value based on the data source.
     * For LATENCY: Blue < 200ms -> Purple < 500ms -> Red > 500ms
     * For NEWHEAD: Green < 3s -> Orange < 10s -> Red > 10s
     * 
     * @param valueMs the value in milliseconds
     * @param dataSource which metric type for color thresholds
     * @return RGB color string
     */
    private String getColorForValue(double valueMs, SparklineDataSource dataSource) {
        int r, g, b;
        
        if (dataSource == SparklineDataSource.NEWHEAD) {
            // NEWHEAD: Green (46,199,126) -> Orange (244,183,64) -> Red (255,107,107)
            if (valueMs < 3000) {
                // Green to Orange
                double t = valueMs / 3000.0;
                r = (int) Math.round(46 + (244 - 46) * t);
                g = (int) Math.round(199 + (183 - 199) * t);
                b = (int) Math.round(126 + (64 - 126) * t);
            } else if (valueMs < 10000) {
                // Orange to Red
                double t = (valueMs - 3000) / 7000.0;
                r = (int) Math.round(244 + (255 - 244) * t);
                g = (int) Math.round(183 + (107 - 183) * t);
                b = (int) Math.round(64 + (107 - 64) * t);
            } else {
                // Red
                r = 255;
                g = 107;
                b = 107;
            }
        } else {
            // LATENCY: Blue (91,141,255) -> Purple (176,116,255) -> Red (255,107,107)
            if (valueMs < 200) {
                double t = valueMs / 200.0;
                r = (int) Math.round(91 + (176 - 91) * t);
                g = (int) Math.round(141 + (116 - 141) * t);
                b = (int) Math.round(255 + (255 - 255) * t);
            } else if (valueMs < 500) {
                double t = (valueMs - 200) / 300.0;
                r = (int) Math.round(176 + (255 - 176) * t);
                g = (int) Math.round(116 + (107 - 116) * t);
                b = (int) Math.round(255 + (107 - 255) * t);
            } else {
                r = 255;
                g = 107;
                b = 107;
            }
        }
        
        return String.format("rgb(%d,%d,%d)", r, g, b);
    }
}

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
package de.makibytes.chaincheck.store;

import java.util.Arrays;

public class HistogramAccumulator {

    static final long[] BOUNDARIES = {
        0, 5, 10, 20, 50, 100, 150, 200, 300, 500,
        750, 1000, 1500, 2000, 3000, 5000, 10000, 20000, 30000, 60000
    };

    static final int BUCKET_COUNT = BOUNDARIES.length + 1;

    private final long[] counts;

    public HistogramAccumulator() {
        this.counts = new long[BUCKET_COUNT];
    }

    private HistogramAccumulator(long[] counts) {
        this.counts = counts;
    }

    public static HistogramAccumulator fromCounts(long[] counts) {
        if (counts == null || counts.length != BUCKET_COUNT) {
            return new HistogramAccumulator();
        }
        long[] copy = new long[BUCKET_COUNT];
        System.arraycopy(counts, 0, copy, 0, BUCKET_COUNT);
        return new HistogramAccumulator(copy);
    }

    public void record(long value) {
        int bucket = findBucket(value);
        counts[bucket]++;
    }

    public void merge(long[] other) {
        if (other == null || other.length != BUCKET_COUNT) {
            return;
        }
        for (int i = 0; i < BUCKET_COUNT; i++) {
            counts[i] += other[i];
        }
    }

    public long totalCount() {
        long total = 0;
        for (long c : counts) {
            total += c;
        }
        return total;
    }

    public double percentile(double p) {
        long total = totalCount();
        if (total == 0) {
            return 0;
        }
        double target = p * total;
        long cumulative = 0;
        for (int i = 0; i < BUCKET_COUNT; i++) {
            cumulative += counts[i];
            if (cumulative >= target) {
                long low = bucketLow(i);
                long high = bucketHigh(i);
                long countInBucket = counts[i];
                if (countInBucket <= 0) {
                    return low;
                }
                long prevCumulative = cumulative - countInBucket;
                double fraction = (target - prevCumulative) / countInBucket;
                return low + fraction * (high - low);
            }
        }
        return bucketHigh(BUCKET_COUNT - 1);
    }

    public long[] getCounts() {
        long[] copy = new long[BUCKET_COUNT];
        System.arraycopy(counts, 0, copy, 0, BUCKET_COUNT);
        return copy;
    }

    static void recordInto(long[] counts, long value) {
        counts[findBucket(value)]++;
    }

    private static int findBucket(long value) {
        int idx = Arrays.binarySearch(BOUNDARIES, value);
        return idx >= 0 ? idx + 1 : -(idx + 1);
    }

    private static long bucketLow(int bucket) {
        if (bucket <= 0) {
            return 0;
        }
        if (bucket <= BOUNDARIES.length) {
            return BOUNDARIES[bucket - 1];
        }
        return BOUNDARIES[BOUNDARIES.length - 1];
    }

    private static long bucketHigh(int bucket) {
        if (bucket < BOUNDARIES.length) {
            return BOUNDARIES[bucket];
        }
        return BOUNDARIES[BOUNDARIES.length - 1] * 2;
    }
}

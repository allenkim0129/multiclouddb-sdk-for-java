// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.perf;

/**
 * One pooled statistics row: all raw samples sharing
 * {@code (provider, operation, scenario, threads, docSizeBytes, pageSize)} are
 * pooled across runs (run id is NOT part of the key), percentiles recomputed over
 * the combined sample, and {@code runCount} records how many distinct runs contributed.
 */
record StatRow(
        String provider, String operation, String scenario, int threads,
        int docSizeBytes, Integer pageSize,
        int runCount, int count, int successCount,
        double p50, double p90, double p99, double max, double mean, double stdev,
        double throughputOpsSec,
        Double costMean, Double costP99,
        double errorRate) {
}

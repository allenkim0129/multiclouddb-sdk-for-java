// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.perf;

/**
 * One measured-operation row, matching {@code multiclouddb-perf/templates/RESULT_SCHEMA.md}.
 * {@code pageSize} and {@code costValue} are nullable (rendered blank in CSV).
 */
record ResultRow(
        String runId,
        String timestampUtc,
        String provider,
        String region,
        String hostLabel,
        String jdk,
        String operation,
        String scenario,
        int docSizeBytes,
        Integer pageSize,
        int threads,
        int iteration,
        double latencyMs,
        boolean success,
        String errorCategory,
        String costUnit,
        Double costValue,
        String provisionedCapacity,
        String sdkVersion,
        String notes) {
}

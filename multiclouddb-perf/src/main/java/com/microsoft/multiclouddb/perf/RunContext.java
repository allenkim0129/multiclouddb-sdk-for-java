// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.perf;

/** Immutable run-scoped metadata stamped onto every measured {@link ResultRow}. */
record RunContext(
        String runId, String provider, String scenario,
        int threads, int warmup, int iterations, int docSize, int pageSize,
        String region, String hostLabel, String jdk,
        String sdkVersion, String provisionedCapacity) {
}

// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.perf;

/** Distinct per-provider environment metadata for the report Environment table. */
record EnvRow(
        String provider, String region, String hostLabel, String jdk,
        String provisionedCapacity, String sdkVersion) {
}

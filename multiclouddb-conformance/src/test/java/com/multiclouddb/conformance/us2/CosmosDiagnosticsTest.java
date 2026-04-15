// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us2;

import com.multiclouddb.api.ProviderId;
import org.junit.jupiter.api.Tag;

@Tag("cosmos")
@Tag("emulator")
public class CosmosDiagnosticsTest extends DiagnosticsConformanceTest {
    @Override
    protected ProviderId providerId() {
        return ProviderId.COSMOS;
    }
}

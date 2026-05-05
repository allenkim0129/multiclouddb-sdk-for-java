// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us4;

import com.multiclouddb.api.ProviderId;
import org.junit.jupiter.api.Tag;

/**
 * Spanner conformance test for
 * {@link com.multiclouddb.api.PortabilityWarning} surfacing.
 * <p>
 * Spanner currently exposes no opt-in feature flags through the SDK's
 * connection config (the {@code emulatorHost} key is a deployment switch,
 * not a non-portable feature), so only the default-config and immutability
 * tests run. Override {@link #providerSpecificOptIns()} when future opt-in
 * flags are added.
 */
@Tag("spanner")
@Tag("emulator")
public class SpannerPortabilityWarningTest extends PortabilityWarningConformanceTest {

    @Override
    protected ProviderId provider() {
        return ProviderId.SPANNER;
    }
}

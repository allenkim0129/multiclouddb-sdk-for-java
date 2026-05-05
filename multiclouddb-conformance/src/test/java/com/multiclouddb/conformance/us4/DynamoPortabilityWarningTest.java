// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us4;

import com.multiclouddb.api.ProviderId;
import org.junit.jupiter.api.Tag;

/**
 * DynamoDB conformance test for
 * {@link com.multiclouddb.api.PortabilityWarning} surfacing.
 * <p>
 * DynamoDB currently exposes no opt-in feature flags through the SDK's
 * connection config, so only the default-config and immutability tests run
 * (the parameterised "opt-ins emit warnings" test is gated by
 * {@code @EnabledIf} and skipped here). When future opt-in flags are added,
 * override {@link #providerSpecificOptIns()} to populate them.
 */
@Tag("dynamo")
@Tag("emulator")
public class DynamoPortabilityWarningTest extends PortabilityWarningConformanceTest {

    @Override
    protected ProviderId provider() {
        return ProviderId.DYNAMO;
    }
}

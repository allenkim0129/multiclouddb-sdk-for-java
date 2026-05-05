// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us4;

import com.multiclouddb.api.ProviderId;
import org.junit.jupiter.api.Tag;

import java.util.Map;

/**
 * Cosmos DB conformance test for {@link com.multiclouddb.api.PortabilityWarning}
 * surfacing.
 * <p>
 * Cosmos has two known opt-in flags that should surface a warning when set:
 * {@code consistencyLevel} and {@code connectionMode=direct}. The conformance
 * test sets {@code consistencyLevel=SESSION} (a portable-friendly choice that
 * still triggers the warning) and asserts that exactly one warning is emitted
 * with stable structure.
 */
@Tag("cosmos")
@Tag("emulator")
public class CosmosPortabilityWarningTest extends PortabilityWarningConformanceTest {

    @Override
    protected ProviderId provider() {
        return ProviderId.COSMOS;
    }

    @Override
    protected Map<String, String> providerSpecificOptIns() {
        // SESSION is the Cosmos default-flavoured choice; the warning fires
        // because the client is asserting Cosmos-specific consistency
        // semantics rather than inheriting the account default.
        return Map.of("consistencyLevel", "SESSION");
    }
}

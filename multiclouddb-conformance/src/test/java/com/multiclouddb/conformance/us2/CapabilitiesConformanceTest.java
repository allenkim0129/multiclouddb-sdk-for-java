// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us2;

import com.multiclouddb.api.*;
import com.multiclouddb.conformance.ConformanceHarness;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Abstract conformance test that verifies capability discovery across
 * providers.
 * Subclasses specify the provider; tests verify the expected capability set.
 */
public abstract class CapabilitiesConformanceTest {

    private static final Map<ProviderId, Boolean> EXTENDED_PARTIAL_UPDATE_SUPPORT = Map.of(
            ProviderId.COSMOS, false,
            ProviderId.DYNAMO, false,
            ProviderId.SPANNER, true);
    private static final Map<ProviderId, String> EXTENDED_PARTIAL_UPDATE_NOTE = Map.of(
            ProviderId.COSMOS, "100 patch operations",
            ProviderId.DYNAMO, "update expression",
            ProviderId.SPANNER, "fixed-schema");

    protected abstract ProviderId provider();

    @Test
    void capabilitiesReturnsNonEmptySet() throws Exception {
        try (MulticloudDbClient client = ConformanceHarness.createClient(provider())) {
            CapabilitySet caps = client.capabilities();
            assertNotNull(caps, "capabilities() must not return null");
            assertFalse(caps.all().isEmpty(), "capabilities() must not be empty");
        }
    }

    @Test
    void allKnownCapabilityNamesPresent() throws Exception {
        try (MulticloudDbClient client = ConformanceHarness.createClient(provider())) {
            CapabilitySet caps = client.capabilities();
            // All 19 well-known capability names must be declared
            String[] knownNames = {
                    Capability.CONTINUATION_TOKEN_PAGING,
                    Capability.CROSS_PARTITION_QUERY,
                    Capability.TRANSACTIONS,
                    Capability.BATCH_OPERATIONS,
                    Capability.STRONG_CONSISTENCY,
                    Capability.NATIVE_SQL_QUERY,
                    Capability.CHANGE_FEED,
                    Capability.EXTENDED_CHANGE_FEED_HISTORY,
                    Capability.PORTABLE_QUERY_EXPRESSION,
                    Capability.LIKE_OPERATOR,
                    Capability.ORDER_BY,
                    Capability.ENDS_WITH,
                    Capability.REGEX_MATCH,
                    Capability.CASE_FUNCTIONS,
                    Capability.RESULT_LIMIT,
                    Capability.ROW_LEVEL_TTL,
                    Capability.WRITE_TIMESTAMP,
                    Capability.PARTIAL_UPDATE,
                    Capability.PARTIAL_UPDATE_EXTENDED_PAYLOAD
            };
            for (String name : knownNames) {
                assertNotNull(caps.get(name),
                        "Provider " + provider().id() + " must declare capability: " + name);
            }
        }
    }

    @Test
    void capabilityCountIs19() throws Exception {
        try (MulticloudDbClient client = ConformanceHarness.createClient(provider())) {
            CapabilitySet caps = client.capabilities();
            assertEquals(19, caps.all().size(),
                    "Provider " + provider().id() + " should declare exactly 19 capabilities");
        }
    }

    @Test
    void partialUpdateCapabilitiesMatchProviderEnvelopes() throws Exception {
        try (MulticloudDbClient client = ConformanceHarness.createClient(provider())) {
            CapabilitySet caps = client.capabilities();
            assertTrue(caps.isSupported(Capability.PARTIAL_UPDATE),
                    "All providers must support PARTIAL_UPDATE");

            Capability extended = caps.get(Capability.PARTIAL_UPDATE_EXTENDED_PAYLOAD);
            assertNotNull(extended);
            assertEquals(EXTENDED_PARTIAL_UPDATE_SUPPORT.get(provider()).booleanValue(),
                    extended.supported(),
                    "Unexpected extended partial-update declaration for " + provider().id());
            assertNotNull(extended.notes());
            assertTrue(extended.notes().contains(EXTENDED_PARTIAL_UPDATE_NOTE.get(provider())),
                    "Extended partial-update notes must describe the provider envelope");
        }
    }

    @Test
    void portableQueryExpressionIsSupported() throws Exception {
        try (MulticloudDbClient client = ConformanceHarness.createClient(provider())) {
            assertTrue(client.capabilities().isSupported(Capability.PORTABLE_QUERY_EXPRESSION),
                    "All providers must support PORTABLE_QUERY_EXPRESSION");
        }
    }

    @Test
    void continuationTokenPagingIsSupported() throws Exception {
        try (MulticloudDbClient client = ConformanceHarness.createClient(provider())) {
            assertTrue(client.capabilities().isSupported(Capability.CONTINUATION_TOKEN_PAGING),
                    "All providers must support CONTINUATION_TOKEN_PAGING");
        }
    }

    @Test
    void providerIdMatchesConfig() throws Exception {
        try (MulticloudDbClient client = ConformanceHarness.createClient(provider())) {
            assertEquals(provider(), client.providerId());
        }
    }
}

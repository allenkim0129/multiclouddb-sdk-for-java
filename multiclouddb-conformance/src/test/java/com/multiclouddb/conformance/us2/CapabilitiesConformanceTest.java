// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us2;

import com.multiclouddb.api.*;
import com.multiclouddb.conformance.ConformanceHarness;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Abstract conformance test that verifies capability discovery across
 * providers.
 * Subclasses specify the provider; tests verify the expected capability set.
 */
public abstract class CapabilitiesConformanceTest {

    protected abstract ProviderId provider();

    protected abstract boolean expectedNestedPatchSupport();

    protected abstract boolean expectedExactFractionalIncrementSupport();

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
            // All 20 well-known capability names must be declared
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
                    Capability.PATCH,
                    Capability.NESTED_PATCH,
                    Capability.EXACT_FRACTIONAL_INCREMENT,
                    Capability.RESULT_LIMIT,
                    Capability.ROW_LEVEL_TTL,
                    Capability.WRITE_TIMESTAMP
            };
            for (String name : knownNames) {
                assertNotNull(caps.get(name),
                        "Provider " + provider().id() + " must declare capability: " + name);
            }
        }
    }

    @Test
    void capabilityCountIs20() throws Exception {
        try (MulticloudDbClient client = ConformanceHarness.createClient(provider())) {
            CapabilitySet caps = client.capabilities();
            assertEquals(20, caps.all().size(),
                    "Provider " + provider().id() + " should declare exactly 20 capabilities");
        }
    }

    @Test
    void patchIsSupported() throws Exception {
        try (MulticloudDbClient client = ConformanceHarness.createClient(provider())) {
            assertTrue(client.capabilities().isSupported(Capability.PATCH),
                    "All providers must support PATCH");
        }
    }

    @Test
    void nestedPatchCapabilityMatchesProviderContract() throws Exception {
        try (MulticloudDbClient client = ConformanceHarness.createClient(provider())) {
            assertEquals(expectedNestedPatchSupport(),
                    client.capabilities().isSupported(Capability.NESTED_PATCH),
                    "Provider " + provider().id()
                            + " must declare the expected NESTED_PATCH capability state");
        }
    }

    /**
     * Fractional INCREMENT accumulates in exact decimal arithmetic on DynamoDB
     * and in IEEE-754 binary64 on Cosmos and Spanner. The gap is portable only
     * because it is declared, so every provider must publish the state its
     * arithmetic actually delivers. The capability is informational — it never
     * causes a fractional increment to be rejected.
     */
    @Test
    void exactFractionalIncrementCapabilityMatchesProviderArithmetic() throws Exception {
        try (MulticloudDbClient client = ConformanceHarness.createClient(provider())) {
            assertNotNull(client.capabilities().get(Capability.EXACT_FRACTIONAL_INCREMENT),
                    "EXACT_FRACTIONAL_INCREMENT must be declared either way so callers can branch on it");
            assertEquals(expectedExactFractionalIncrementSupport(),
                    client.capabilities().isSupported(Capability.EXACT_FRACTIONAL_INCREMENT),
                    "Provider " + provider().id()
                            + " must declare the expected EXACT_FRACTIONAL_INCREMENT capability state");
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

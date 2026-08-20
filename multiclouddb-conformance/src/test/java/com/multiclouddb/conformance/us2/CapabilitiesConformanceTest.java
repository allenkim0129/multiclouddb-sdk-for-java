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

    protected boolean expectedPatchSupport() {
        return true;
    }

    protected abstract boolean expectedNestedPatchSupport();

    protected abstract boolean expectedExactFractionalIncrementSupport();

    protected abstract boolean expectedPatchPreservesTtlSupport();

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
            // All 21 well-known capability names must be declared
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
                    Capability.PATCH_PRESERVES_TTL,
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
    void capabilityCountIs21() throws Exception {
        try (MulticloudDbClient client = ConformanceHarness.createClient(provider())) {
            CapabilitySet caps = client.capabilities();
            assertEquals(21, caps.all().size(),
                    "Provider " + provider().id() + " should declare exactly 21 capabilities");
        }
    }

    @Test
    void patchCapabilityMatchesProviderContract() throws Exception {
        try (MulticloudDbClient client = ConformanceHarness.createClient(provider())) {
            assertEquals(expectedPatchSupport(),
                    client.capabilities().isSupported(Capability.PATCH),
                    "Provider " + provider().id()
                            + " must declare the expected PATCH capability state");
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
     * and in IEEE-754 binary64 on Cosmos DB. Spanner declares this capability
     * unsupported while PATCH itself is unavailable. Every provider publishes
     * an explicit state so callers never infer arithmetic from provider identity.
     * For providers supporting PATCH, this capability is informational and does
     * not itself reject an in-domain fractional increment.
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
    void patchPreservesTtlCapabilityMatchesProviderContract() throws Exception {
        try (MulticloudDbClient client = ConformanceHarness.createClient(provider())) {
            assertNotNull(client.capabilities().get(Capability.PATCH_PRESERVES_TTL),
                    "PATCH_PRESERVES_TTL must be declared either way so callers can branch on it");
            assertEquals(expectedPatchPreservesTtlSupport(),
                    client.capabilities().isSupported(Capability.PATCH_PRESERVES_TTL),
                    "Provider " + provider().id()
                            + " must declare the expected PATCH_PRESERVES_TTL capability state");
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

// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.azure.cosmos.ConsistencyLevel;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.ProviderId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for Cosmos DB read-consistency configuration in
 * {@link CosmosProviderClient}.
 * <p>
 * Two groups of tests:
 * <ol>
 *   <li><b>{@code buildReadOptions} helper</b> — pure-function tests that verify
 *       the correct {@link CosmosItemRequestOptions} is produced for each
 *       combination of configured / unconfigured consistency level.</li>
 *   <li><b>Constructor / builder tests</b> — verify that
 *       {@link CosmosClientBuilder#consistencyLevel} is <em>never</em> called,
 *       regardless of configuration, because consistency overrides are applied
 *       per read-request rather than at client level.</li>
 * </ol>
 */
class CosmosConsistencyTest {

    private static final String DUMMY_ENDPOINT = "https://example.documents.azure.com:443/";
    private static final String DUMMY_KEY =
            "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==";

    // ── buildReadOptions helper ───────────────────────────────────────────────

    @Test
    @DisplayName("buildReadOptions(null): options carry no consistency override")
    void buildReadOptionsNullConsistency() {
        CosmosItemRequestOptions opts = CosmosProviderClient.buildReadOptions(null);
        assertNotNull(opts);
        assertNull(opts.getConsistencyLevel(),
                "No consistency should be set when override is null");
    }

    @Test
    @DisplayName("buildReadOptions(SESSION): options carry SESSION override")
    void buildReadOptionsSession() {
        CosmosItemRequestOptions opts = CosmosProviderClient.buildReadOptions(ConsistencyLevel.SESSION);
        assertEquals(ConsistencyLevel.SESSION, opts.getConsistencyLevel());
    }

    @Test
    @DisplayName("buildReadOptions(EVENTUAL): options carry EVENTUAL override")
    void buildReadOptionsEventual() {
        CosmosItemRequestOptions opts = CosmosProviderClient.buildReadOptions(ConsistencyLevel.EVENTUAL);
        assertEquals(ConsistencyLevel.EVENTUAL, opts.getConsistencyLevel());
    }

    @Test
    @DisplayName("buildReadOptions(STRONG): options carry STRONG override")
    void buildReadOptionsStrong() {
        CosmosItemRequestOptions opts = CosmosProviderClient.buildReadOptions(ConsistencyLevel.STRONG);
        assertEquals(ConsistencyLevel.STRONG, opts.getConsistencyLevel());
    }

    @Test
    @DisplayName("buildReadOptions(BOUNDED_STALENESS): options carry BOUNDED_STALENESS override")
    void buildReadOptionsBoundedStaleness() {
        CosmosItemRequestOptions opts =
                CosmosProviderClient.buildReadOptions(ConsistencyLevel.BOUNDED_STALENESS);
        assertEquals(ConsistencyLevel.BOUNDED_STALENESS, opts.getConsistencyLevel());
    }

    @Test
    @DisplayName("buildReadOptions(CONSISTENT_PREFIX): options carry CONSISTENT_PREFIX override")
    void buildReadOptionsConsistentPrefix() {
        CosmosItemRequestOptions opts =
                CosmosProviderClient.buildReadOptions(ConsistencyLevel.CONSISTENT_PREFIX);
        assertEquals(ConsistencyLevel.CONSISTENT_PREFIX, opts.getConsistencyLevel());
    }

    // ── Constructor / CosmosClientBuilder verification ────────────────────────

    /**
     * Shared builder-mock setup. Returns the default answer that makes fluent
     * builder methods return {@code this} (the mock), avoiding NPEs on chained calls.
     */
    private MockedConstruction.MockInitializer<CosmosClientBuilder> builderDefaultAnswer() {
        return (mock, ctx) -> {
            when(mock.endpoint(anyString())).thenReturn(mock);
            when(mock.key(anyString())).thenReturn(mock);
            when(mock.contentResponseOnWriteEnabled(anyBoolean())).thenReturn(mock);
            when(mock.gatewayMode()).thenReturn(mock);
            when(mock.directMode()).thenReturn(mock);
            when(mock.userAgentSuffix(anyString())).thenReturn(mock);
            when(mock.consistencyLevel(any())).thenReturn(mock);
            // buildClient() returns null — construction-only tests don't invoke operations
        };
    }

    @Test
    @DisplayName("No consistencyLevel in config: CosmosClientBuilder.consistencyLevel() is never called")
    void noConsistencyConfigDoesNotCallBuilderConsistencyLevel() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection(CosmosConstants.CONFIG_ENDPOINT, DUMMY_ENDPOINT)
                .connection(CosmosConstants.CONFIG_KEY, DUMMY_KEY)
                .build();

        try (MockedConstruction<CosmosClientBuilder> mocked =
                     mockConstruction(CosmosClientBuilder.class, builderDefaultAnswer())) {

            new CosmosProviderClient(config);

            List<CosmosClientBuilder> builders = mocked.constructed();
            assertEquals(1, builders.size());
            verify(builders.get(0), never()).consistencyLevel(any());
        }
    }

    @Test
    @DisplayName("consistencyLevel=SESSION in config: CosmosClientBuilder.consistencyLevel() is still never called (per-request override, not client-level)")
    void consistencyConfigDoesNotSetClientLevelConsistency() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection(CosmosConstants.CONFIG_ENDPOINT, DUMMY_ENDPOINT)
                .connection(CosmosConstants.CONFIG_KEY, DUMMY_KEY)
                .connection(CosmosConstants.CONFIG_CONSISTENCY_LEVEL, "SESSION")
                .build();

        try (MockedConstruction<CosmosClientBuilder> mocked =
                     mockConstruction(CosmosClientBuilder.class, builderDefaultAnswer())) {

            new CosmosProviderClient(config);

            verify(mocked.constructed().get(0), never()).consistencyLevel(any());
        }
    }

    @Test
    @DisplayName("consistencyLevel=EVENTUAL in config: construction succeeds")
    void validConsistencyConfigEventualSucceeds() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection(CosmosConstants.CONFIG_ENDPOINT, DUMMY_ENDPOINT)
                .connection(CosmosConstants.CONFIG_KEY, DUMMY_KEY)
                .connection(CosmosConstants.CONFIG_CONSISTENCY_LEVEL, "EVENTUAL")
                .build();

        try (MockedConstruction<CosmosClientBuilder> ignored =
                     mockConstruction(CosmosClientBuilder.class, builderDefaultAnswer())) {
            assertDoesNotThrow(() -> new CosmosProviderClient(config));
        }
    }

    @Test
    @DisplayName("consistencyLevel=invalid in config: construction throws IllegalArgumentException with informative message")
    void invalidConsistencyConfigThrowsOnConstruction() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection(CosmosConstants.CONFIG_ENDPOINT, DUMMY_ENDPOINT)
                .connection(CosmosConstants.CONFIG_KEY, DUMMY_KEY)
                .connection(CosmosConstants.CONFIG_CONSISTENCY_LEVEL, "LINEARIZABLE")
                .build();

        try (MockedConstruction<CosmosClientBuilder> ignored =
                     mockConstruction(CosmosClientBuilder.class, builderDefaultAnswer())) {
            IllegalArgumentException ex = assertThrows(
                    IllegalArgumentException.class,
                    () -> new CosmosProviderClient(config));
            assertTrue(ex.getMessage().contains("LINEARIZABLE"),
                    "Error message should include the bad value; got: " + ex.getMessage());
        }
    }

    @Test
    @DisplayName("consistencyLevel config is case-insensitive (lowercase 'eventual' accepted)")
    void consistencyConfigCaseInsensitive() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection(CosmosConstants.CONFIG_ENDPOINT, DUMMY_ENDPOINT)
                .connection(CosmosConstants.CONFIG_KEY, DUMMY_KEY)
                .connection(CosmosConstants.CONFIG_CONSISTENCY_LEVEL, "eventual")
                .build();

        try (MockedConstruction<CosmosClientBuilder> ignored =
                     mockConstruction(CosmosClientBuilder.class, builderDefaultAnswer())) {
            assertDoesNotThrow(() -> new CosmosProviderClient(config));
        }
    }
}

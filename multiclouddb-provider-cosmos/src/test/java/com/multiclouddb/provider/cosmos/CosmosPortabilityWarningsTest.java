// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.azure.core.credential.TokenCredential;
import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.PortabilityWarning;
import com.multiclouddb.api.ProviderId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PortabilityWarning} emission from
 * {@link CosmosProviderClient}'s constructor.
 */
class CosmosPortabilityWarningsTest {

    private static final String DUMMY_ENDPOINT = "https://localhost:8081";
    private static final String DUMMY_KEY = "ZHVtbXkta2V5";

    private MockedConstruction.MockInitializer<CosmosClientBuilder> builderDefaultAnswer() {
        return (mock, ctx) -> {
            when(mock.endpoint(anyString())).thenReturn(mock);
            when(mock.key(anyString())).thenReturn(mock);
            when(mock.contentResponseOnWriteEnabled(anyBoolean())).thenReturn(mock);
            when(mock.gatewayMode()).thenReturn(mock);
            when(mock.directMode()).thenReturn(mock);
            when(mock.userAgentSuffix(anyString())).thenReturn(mock);
            when(mock.consistencyLevel(any())).thenReturn(mock);
            when(mock.credential(any(TokenCredential.class))).thenReturn(mock);
            when(mock.buildClient()).thenReturn(mock(CosmosClient.class));
        };
    }

    private MulticloudDbClientConfig.Builder baseConfig() {
        return MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection(CosmosConstants.CONFIG_ENDPOINT, DUMMY_ENDPOINT)
                .connection(CosmosConstants.CONFIG_KEY, DUMMY_KEY);
    }

    @Test
    @DisplayName("Default config emits zero portability warnings")
    void defaultConfigEmitsNoWarnings() {
        try (MockedConstruction<CosmosClientBuilder> ignored =
                     mockConstruction(CosmosClientBuilder.class, builderDefaultAnswer())) {
            CosmosProviderClient client = new CosmosProviderClient(baseConfig().build());

            List<PortabilityWarning> warnings = client.portabilityWarnings();
            assertNotNull(warnings, "portabilityWarnings() must not return null");
            assertTrue(warnings.isEmpty(),
                    "default config must emit zero warnings, got: " + warnings);
        }
    }

    @Test
    @DisplayName("Explicit connectionMode=gateway (the default) emits zero warnings")
    void explicitGatewayModeEmitsNoWarnings() {
        try (MockedConstruction<CosmosClientBuilder> ignored =
                     mockConstruction(CosmosClientBuilder.class, builderDefaultAnswer())) {
            CosmosProviderClient client = new CosmosProviderClient(baseConfig()
                    .connection(CosmosConstants.CONFIG_CONNECTION_MODE, "gateway")
                    .build());

            assertTrue(client.portabilityWarnings().isEmpty());
        }
    }

    @Test
    @DisplayName("consistencyLevel set emits a warning with stable code, message, scope, provider, category")
    void consistencyLevelEmitsWarning() {
        try (MockedConstruction<CosmosClientBuilder> ignored =
                     mockConstruction(CosmosClientBuilder.class, builderDefaultAnswer())) {
            CosmosProviderClient client = new CosmosProviderClient(baseConfig()
                    .connection(CosmosConstants.CONFIG_CONSISTENCY_LEVEL, "BOUNDED_STALENESS")
                    .build());

            List<PortabilityWarning> warnings = client.portabilityWarnings();
            assertEquals(1, warnings.size(), "exactly one warning expected, got: " + warnings);
            PortabilityWarning w = warnings.get(0);
            assertEquals("cosmos.consistencyLevel", w.code());
            assertEquals(ProviderId.COSMOS, w.provider());
            assertEquals(PortabilityWarning.Scope.CLIENT_CONFIG, w.scope());
            assertEquals(PortabilityWarning.Category.PROVIDER_SPECIFIC_CONFIG, w.category());
            assertTrue(w.message().contains("BOUNDED_STALENESS"),
                    "message should reference the configured value: " + w.message());
        }
    }

    @Test
    @DisplayName("connectionMode=direct emits a warning with stable code")
    void directModeEmitsWarning() {
        try (MockedConstruction<CosmosClientBuilder> ignored =
                     mockConstruction(CosmosClientBuilder.class, builderDefaultAnswer())) {
            CosmosProviderClient client = new CosmosProviderClient(baseConfig()
                    .connection(CosmosConstants.CONFIG_CONNECTION_MODE, "direct")
                    .build());

            List<PortabilityWarning> warnings = client.portabilityWarnings();
            assertEquals(1, warnings.size());
            assertEquals("cosmos.connectionMode.direct", warnings.get(0).code());
        }
    }

    @Test
    @DisplayName("Both opt-ins set: warnings are emitted independently and ordered consistently")
    void multipleOptInsEmitMultipleWarnings() {
        try (MockedConstruction<CosmosClientBuilder> ignored =
                     mockConstruction(CosmosClientBuilder.class, builderDefaultAnswer())) {
            CosmosProviderClient client = new CosmosProviderClient(baseConfig()
                    .connection(CosmosConstants.CONFIG_CONSISTENCY_LEVEL, "EVENTUAL")
                    .connection(CosmosConstants.CONFIG_CONNECTION_MODE, "DIRECT")
                    .build());

            List<PortabilityWarning> warnings = client.portabilityWarnings();
            assertEquals(2, warnings.size(),
                    "consistencyLevel + connectionMode=direct should emit 2 warnings");
            assertEquals("cosmos.consistencyLevel", warnings.get(0).code());
            assertEquals("cosmos.connectionMode.direct", warnings.get(1).code());
        }
    }

    @Test
    @DisplayName("portabilityWarnings() returns an immutable list")
    void portabilityWarningsListIsImmutable() {
        try (MockedConstruction<CosmosClientBuilder> ignored =
                     mockConstruction(CosmosClientBuilder.class, builderDefaultAnswer())) {
            CosmosProviderClient client = new CosmosProviderClient(baseConfig()
                    .connection(CosmosConstants.CONFIG_CONSISTENCY_LEVEL, "SESSION")
                    .build());

            List<PortabilityWarning> warnings = client.portabilityWarnings();
            assertThrows(UnsupportedOperationException.class,
                    () -> warnings.add(PortabilityWarning.providerConfig(
                            "x", "y", ProviderId.COSMOS)));
        }
    }
}

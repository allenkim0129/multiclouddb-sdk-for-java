// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.GatewayConnectionConfig;
import com.azure.cosmos.Http2ConnectionConfig;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.ProviderId;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CosmosTransportConfigTest {

    @Test
    void gatewayPoolAndHttp2SettingsAreApplied() {
        MulticloudDbClientConfig config = baseConfig()
                .connection(CosmosConstants.CONFIG_GATEWAY_MAX_CONNECTION_POOL_SIZE, "64")
                .connection(CosmosConstants.CONFIG_GATEWAY_HTTP2_ENABLED, "true")
                .connection(CosmosConstants.CONFIG_GATEWAY_HTTP2_MIN_CONNECTION_POOL_SIZE, "2")
                .connection(CosmosConstants.CONFIG_GATEWAY_HTTP2_MAX_CONNECTION_POOL_SIZE, "8")
                .connection(CosmosConstants.CONFIG_GATEWAY_HTTP2_MAX_CONCURRENT_STREAMS, "32")
                .build();

        GatewayConnectionConfig gateway = CosmosProviderClient.gatewayConnectionConfig(config);
        Http2ConnectionConfig http2 = gateway.getHttp2ConnectionConfig();

        assertEquals(64, gateway.getMaxConnectionPoolSize());
        assertEquals(true, http2.isEnabled());
        assertEquals(2, http2.getMinConnectionPoolSize());
        assertEquals(8, http2.getMaxConnectionPoolSize());
        assertEquals(32, http2.getMaxConcurrentStreams());
    }

    @Test
    void explicitHttp11ProfileDisablesHttp2() {
        MulticloudDbClientConfig config = baseConfig()
                .connection(CosmosConstants.CONFIG_GATEWAY_MAX_CONNECTION_POOL_SIZE, "64")
                .connection(CosmosConstants.CONFIG_GATEWAY_HTTP2_ENABLED, "false")
                .build();

        GatewayConnectionConfig gateway = CosmosProviderClient.gatewayConnectionConfig(config);

        assertEquals(64, gateway.getMaxConnectionPoolSize());
        assertFalse(gateway.getHttp2ConnectionConfig().isEnabled());
    }

    @Test
    void invalidTransportValuesFailFast() {
        MulticloudDbClientConfig badPool = baseConfig()
                .connection(CosmosConstants.CONFIG_GATEWAY_MAX_CONNECTION_POOL_SIZE, "0")
                .build();
        MulticloudDbClientConfig badBoolean = baseConfig()
                .connection(CosmosConstants.CONFIG_GATEWAY_HTTP2_ENABLED, "yes")
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> CosmosProviderClient.gatewayConnectionConfig(badPool));
        assertThrows(IllegalArgumentException.class,
                () -> CosmosProviderClient.gatewayConnectionConfig(badBoolean));
    }

    @Test
    void gatewaySettingsAreRejectedInDirectMode() {
        MulticloudDbClientConfig config = baseConfig()
                .connection(CosmosConstants.CONFIG_KEY,
                        "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==")
                .connection(CosmosConstants.CONFIG_CONNECTION_MODE, CosmosConstants.CONNECTION_MODE_DIRECT)
                .connection(CosmosConstants.CONFIG_GATEWAY_MAX_CONNECTION_POOL_SIZE, "64")
                .build();

        assertThrows(IllegalArgumentException.class, () -> new CosmosProviderClient(config));
    }

    @Test
    void contentResponseOnWriteDefaultsToEnabledAndIsConfigurable() {
        try (MockedConstruction<CosmosClientBuilder> mocked = mockConstruction(CosmosClientBuilder.class,
                (mock, ctx) -> {
                    when(mock.endpoint(anyString())).thenReturn(mock);
                    when(mock.key(anyString())).thenReturn(mock);
                    when(mock.contentResponseOnWriteEnabled(anyBoolean())).thenReturn(mock);
                    when(mock.gatewayMode(any(GatewayConnectionConfig.class))).thenReturn(mock);
                    when(mock.userAgentSuffix(anyString())).thenReturn(mock);
                    when(mock.buildClient()).thenReturn(mock(CosmosClient.class));
                })) {
            new CosmosProviderClient(writeResponseConfig(null)).close();
            verify(mocked.constructed().get(0)).contentResponseOnWriteEnabled(true);

            new CosmosProviderClient(writeResponseConfig("false")).close();
            verify(mocked.constructed().get(1)).contentResponseOnWriteEnabled(false);

            new CosmosProviderClient(writeResponseConfig("true")).close();
            verify(mocked.constructed().get(2)).contentResponseOnWriteEnabled(true);
        }
    }

    @Test
    void invalidContentResponseOnWriteFailsFast() {
        assertThrows(IllegalArgumentException.class,
                () -> new CosmosProviderClient(writeResponseConfig("sometimes")));
    }

    @Test
    void http2IsEnabledByDefault() {
        GatewayConnectionConfig gateway = CosmosProviderClient.gatewayConnectionConfig(baseConfig().build());

        assertNotNull(gateway.getHttp2ConnectionConfig(),
                "an explicit Http2ConnectionConfig must always be emitted so the effective "
                        + "protocol follows configuration, not the COSMOS.HTTP2_ENABLED system property");
        assertEquals(true, gateway.getHttp2ConnectionConfig().isEnabled());
        assertTrue(CosmosProviderClient.http2Enabled(baseConfig().build()));
    }

    @Test
    void thinClientSetsSystemPropertyOnlyWhenRequested() {
        String original = System.getProperty(CosmosConstants.THIN_CLIENT_SYSTEM_PROPERTY);
        System.clearProperty(CosmosConstants.THIN_CLIENT_SYSTEM_PROPERTY);
        try (MockedConstruction<CosmosClientBuilder> mocked = mockConstruction(CosmosClientBuilder.class,
                (mock, ctx) -> stubBuilder(mock))) {

            new CosmosProviderClient(thinClientConfig(null, null)).close();
            assertNull(System.getProperty(CosmosConstants.THIN_CLIENT_SYSTEM_PROPERTY),
                    "an unset thinClientEnabled must not write the JVM-wide property");

            new CosmosProviderClient(thinClientConfig("false", null)).close();
            assertNull(System.getProperty(CosmosConstants.THIN_CLIENT_SYSTEM_PROPERTY),
                    "thinClientEnabled=false must not write the JVM-wide property");

            new CosmosProviderClient(thinClientConfig("true", null)).close();
            assertEquals("true", System.getProperty(CosmosConstants.THIN_CLIENT_SYSTEM_PROPERTY));
        } finally {
            restore(original);
        }
    }

    @Test
    void operatorSuppliedThinClientPropertyIsNeverOverwritten() {
        String original = System.getProperty(CosmosConstants.THIN_CLIENT_SYSTEM_PROPERTY);
        System.setProperty(CosmosConstants.THIN_CLIENT_SYSTEM_PROPERTY, "false");
        try (MockedConstruction<CosmosClientBuilder> mocked = mockConstruction(CosmosClientBuilder.class,
                (mock, ctx) -> stubBuilder(mock))) {

            new CosmosProviderClient(thinClientConfig("true", null)).close();

            assertEquals("false", System.getProperty(CosmosConstants.THIN_CLIENT_SYSTEM_PROPERTY),
                    "an operator -D value must win over configuration");
        } finally {
            restore(original);
        }
    }

    @Test
    void thinClientRequiresHttp2() {
        String original = System.getProperty(CosmosConstants.THIN_CLIENT_SYSTEM_PROPERTY);
        System.clearProperty(CosmosConstants.THIN_CLIENT_SYSTEM_PROPERTY);
        try (MockedConstruction<CosmosClientBuilder> mocked = mockConstruction(CosmosClientBuilder.class,
                (mock, ctx) -> stubBuilder(mock))) {

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> new CosmosProviderClient(thinClientConfig("true", "false")));

            assertTrue(error.getMessage().contains(CosmosConstants.CONFIG_GATEWAY_HTTP2_ENABLED),
                    "the failure must name the conflicting key, got: " + error.getMessage());
            assertNull(System.getProperty(CosmosConstants.THIN_CLIENT_SYSTEM_PROPERTY),
                    "a rejected configuration must not leave the JVM-wide property set");
        } finally {
            restore(original);
        }
    }

    @Test
    void thinClientIsRejectedInDirectMode() {
        MulticloudDbClientConfig config = baseConfig()
                .connection(CosmosConstants.CONFIG_KEY,
                        "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==")
                .connection(CosmosConstants.CONFIG_CONNECTION_MODE, CosmosConstants.CONNECTION_MODE_DIRECT)
                .connection(CosmosConstants.CONFIG_THIN_CLIENT_ENABLED, "true")
                .build();

        assertThrows(IllegalArgumentException.class, () -> new CosmosProviderClient(config));
    }

    @Test
    void invalidThinClientValueFailsFast() {
        assertThrows(IllegalArgumentException.class,
                () -> new CosmosProviderClient(thinClientConfig("maybe", null)));
    }

    private static void restore(String original) {
        if (original == null) {
            System.clearProperty(CosmosConstants.THIN_CLIENT_SYSTEM_PROPERTY);
        } else {
            System.setProperty(CosmosConstants.THIN_CLIENT_SYSTEM_PROPERTY, original);
        }
    }

    private static void stubBuilder(CosmosClientBuilder mock) {
        when(mock.endpoint(anyString())).thenReturn(mock);
        when(mock.key(anyString())).thenReturn(mock);
        when(mock.contentResponseOnWriteEnabled(anyBoolean())).thenReturn(mock);
        when(mock.gatewayMode(any(GatewayConnectionConfig.class))).thenReturn(mock);
        when(mock.userAgentSuffix(anyString())).thenReturn(mock);
        when(mock.buildClient()).thenReturn(mock(CosmosClient.class));
    }

    private static MulticloudDbClientConfig thinClientConfig(String thinClient, String http2) {
        MulticloudDbClientConfig.Builder builder = baseConfig()
                .connection(CosmosConstants.CONFIG_KEY,
                        "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==");
        if (thinClient != null) {
            builder.connection(CosmosConstants.CONFIG_THIN_CLIENT_ENABLED, thinClient);
        }
        if (http2 != null) {
            builder.connection(CosmosConstants.CONFIG_GATEWAY_HTTP2_ENABLED, http2);
        }
        return builder.build();
    }

    private static MulticloudDbClientConfig writeResponseConfig(String contentResponseOnWrite) {
        MulticloudDbClientConfig.Builder builder = baseConfig()
                .connection(CosmosConstants.CONFIG_KEY,
                        "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==");
        if (contentResponseOnWrite != null) {
            builder.connection(CosmosConstants.CONFIG_CONTENT_RESPONSE_ON_WRITE_ENABLED, contentResponseOnWrite);
        }
        return builder.build();
    }

    private static MulticloudDbClientConfig.Builder baseConfig() {
        return MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection(CosmosConstants.CONFIG_ENDPOINT,
                        "https://example.documents.azure.com:443/");
    }
}

// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.GatewayConnectionConfig;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.ProviderId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class CosmosGatewayDefaultsTest {

    private static final String DUMMY_KEY =
            "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==";
    private static final String STANDARD_ENDPOINT = "https://example.documents.azure.com:443/";
    private static final String DEDICATED_GATEWAY_ENDPOINT =
            "https://example.sqlx.cosmos.azure.com:443/";

    private String originalSdkThinClientProperty;

    @BeforeEach
    void saveAndClearThinClientProperty() {
        originalSdkThinClientProperty =
                System.getProperty(CosmosConstants.SDK_THIN_CLIENT_ENABLED_PROPERTY);
        System.clearProperty(CosmosConstants.SDK_THIN_CLIENT_ENABLED_PROPERTY);
    }

    @AfterEach
    void restoreThinClientProperty() {
        if (originalSdkThinClientProperty == null) {
            System.clearProperty(CosmosConstants.SDK_THIN_CLIENT_ENABLED_PROPERTY);
        } else {
            System.setProperty(
                    CosmosConstants.SDK_THIN_CLIENT_ENABLED_PROPERTY, originalSdkThinClientProperty);
        }
    }

    @Test
    void alwaysUsesGatewayWithHttp2Enabled() {
        try (MockedConstruction<CosmosClientBuilder> mocked = mockBuilderConstruction();
             CosmosProviderClient ignored = new CosmosProviderClient(config(null, null))) {

            CosmosClientBuilder builder = mocked.constructed().get(0);
            ArgumentCaptor<GatewayConnectionConfig> configCaptor =
                    ArgumentCaptor.forClass(GatewayConnectionConfig.class);
            verify(builder).gatewayMode(configCaptor.capture());
            verify(builder, never()).gatewayMode();
            verify(builder, never()).directMode();

            GatewayConnectionConfig gatewayConfig = configCaptor.getValue();
            assertNotNull(gatewayConfig.getHttp2ConnectionConfig());
            assertEquals(Boolean.TRUE, gatewayConfig.getHttp2ConnectionConfig().isEnabled());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("gatewayV2EndpointCombinations")
    void gatewayV2EndpointCombinationUsesExpectedSettingAndLogsTransport(
            String scenario,
            String endpoint,
            String gatewayV2Enable,
            String existingSdkSetting,
            String expectedSdkSetting,
            String expectedGatewayV2Preference) {
        assumeNoSdkThinClientEnvironmentOverride();

        if (existingSdkSetting != null) {
            System.setProperty(
                    CosmosConstants.SDK_THIN_CLIENT_ENABLED_PROPERTY, existingSdkSetting);
        }

        Map<String, String> properties = new HashMap<>();
        if (gatewayV2Enable != null) {
            properties.put(CosmosConstants.CONFIG_GATEWAY_V2_ENABLE, gatewayV2Enable);
        }
        if (DEDICATED_GATEWAY_ENDPOINT.equals(endpoint)) {
            properties.put(CosmosConstants.CONFIG_CONSISTENCY_LEVEL, "EVENTUAL");
        }

        Logger logger = (Logger) LoggerFactory.getLogger(CosmosProviderClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try (MockedConstruction<CosmosClientBuilder> ignored = mockBuilderConstruction();
             CosmosProviderClient ignoredClient = new CosmosProviderClient(
                     configForEndpoint(endpoint, properties))) {
            assertEquals(
                    expectedSdkSetting,
                    System.getProperty(CosmosConstants.SDK_THIN_CLIENT_ENABLED_PROPERTY),
                    scenario);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        long transportConfigurationLogCount = appender.list.stream().filter(
                event -> event.getLevel() == Level.INFO
                        && event.getFormattedMessage().contains(
                                "Gateway mode, HTTP/2 enabled, Gateway V2 preference at client "
                                        + "creation: " + expectedGatewayV2Preference)
                        && event.getFormattedMessage().contains(
                                "accounts with Integrated Cache use Gateway V1"))
                .count();
        assertEquals(1L, transportConfigurationLogCount, scenario);

        boolean integratedCacheWarningLogged = appender.list.stream().anyMatch(
                event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("Integrated Cache"));
        assertFalse(integratedCacheWarningLogged, scenario);

        if (expectedGatewayV2Preference.startsWith("AUTO (invalid native value")) {
            boolean autoInactiveWarningLogged = appender.list.stream().anyMatch(
                    event -> event.getLevel() == Level.WARN
                            && event.getFormattedMessage().contains(
                                    "SDK AUTO probe/fallback is not active"));
            assertFalse(autoInactiveWarningLogged, scenario);
        }
    }

    @Test
    void existingSdkPropertyTakesPrecedence() {
        System.setProperty(CosmosConstants.SDK_THIN_CLIENT_ENABLED_PROPERTY, "false");

        try (MockedConstruction<CosmosClientBuilder> ignored = mockBuilderConstruction();
             CosmosProviderClient client = new CosmosProviderClient(
                     config(CosmosConstants.CONFIG_GATEWAY_V2_ENABLE, "true"))) {
            assertEquals(
                    "false", System.getProperty(CosmosConstants.SDK_THIN_CLIENT_ENABLED_PROPERTY));
        }
    }

    @Test
    void rejectsMalformedGatewayV2Value() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new CosmosProviderClient(
                        config(CosmosConstants.CONFIG_GATEWAY_V2_ENABLE, "yes")));

        assertEquals(
                "Cosmos connection property 'gatewayV2Enable' must be 'true' or 'false'",
                error.getMessage());
    }

    @Test
    void rejectsRenamedThinClientOption() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new CosmosProviderClient(config("thinClientEnabled", "false")));

        assertEquals(
                "Cosmos connection property 'thinClientEnabled' has been renamed to "
                        + "'gatewayV2Enable'",
                error.getMessage());
    }

    @Test
    void invalidConsistencyDoesNotPublishGatewayV2Preference() {
        assumeNoSdkThinClientEnvironmentOverride();

        assertThrows(
                IllegalArgumentException.class,
                () -> new CosmosProviderClient(config(Map.of(
                        CosmosConstants.CONFIG_GATEWAY_V2_ENABLE, "true",
                        CosmosConstants.CONFIG_CONSISTENCY_LEVEL, "invalid"))));

        assertNull(System.getProperty(CosmosConstants.SDK_THIN_CLIENT_ENABLED_PROPERTY));
    }

    @Test
    void rejectsRemovedConnectionModeOption() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new CosmosProviderClient(config("connectionMode", "direct")));

        assertEquals(
                "Cosmos connection property 'connectionMode' is no longer supported; "
                        + "Gateway mode is always used",
                error.getMessage());
    }

    @Test
    void rejectsGatewayHttp2Toggle() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new CosmosProviderClient(config("gatewayHttp2Enabled", "false")));

        assertEquals(
                "Cosmos connection property 'gatewayHttp2Enabled' is not supported; "
                        + "Gateway HTTP/2 is always enabled",
                error.getMessage());
    }

    private static Stream<Arguments> gatewayV2EndpointCombinations() {
        return Stream.of(
                Arguments.of(
                        "standard / AUTO",
                        STANDARD_ENDPOINT,
                        null,
                        null,
                        null,
                        "AUTO (probe/fallback)"),
                Arguments.of(
                        "standard / invalid native setting",
                        STANDARD_ENDPOINT,
                        null,
                        "yes",
                        "yes",
                        "AUTO (invalid native value is treated as unset by Azure SDK)"),
                Arguments.of(
                        "standard / disabled",
                        STANDARD_ENDPOINT,
                        "false",
                        null,
                        "false",
                        "DISABLED (Gateway V1)"),
                Arguments.of(
                        "standard / enabled",
                        STANDARD_ENDPOINT,
                        "TRUE",
                        null,
                        "true",
                        "ENABLED (probe bypassed)"),
                Arguments.of(
                        "Dedicated / AUTO",
                        DEDICATED_GATEWAY_ENDPOINT,
                        null,
                        null,
                        null,
                        "AUTO (probe/fallback)"),
                Arguments.of(
                        "Dedicated / disabled",
                        DEDICATED_GATEWAY_ENDPOINT,
                        "false",
                        null,
                        "false",
                        "DISABLED (Gateway V1)"),
                Arguments.of(
                        "Dedicated / enabled",
                        DEDICATED_GATEWAY_ENDPOINT,
                        "true",
                        null,
                        "true",
                        "ENABLED (probe bypassed)"),
                Arguments.of(
                        "Dedicated / disabled request / native enabled",
                        DEDICATED_GATEWAY_ENDPOINT,
                        "false",
                        "true",
                        "true",
                        "ENABLED (probe bypassed)"),
                Arguments.of(
                        "Dedicated / enabled request / native disabled",
                        DEDICATED_GATEWAY_ENDPOINT,
                        "true",
                        "false",
                        "false",
                        "DISABLED (Gateway V1)"));
    }

    private static MulticloudDbClientConfig config(String property, String value) {
        if (property == null) {
            return config(Map.of());
        }
        return config(Map.of(property, value));
    }

    private static MulticloudDbClientConfig config(Map<String, String> properties) {
        return configForEndpoint(STANDARD_ENDPOINT, properties);
    }

    private static MulticloudDbClientConfig configForEndpoint(
            String endpoint, Map<String, String> properties) {
        MulticloudDbClientConfig.Builder builder = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection(CosmosConstants.CONFIG_ENDPOINT, endpoint)
                .connection(CosmosConstants.CONFIG_KEY, DUMMY_KEY);
        properties.forEach(builder::connection);
        return builder.build();
    }

    private static void assumeNoSdkThinClientEnvironmentOverride() {
        String environmentValue = System.getenv(
                CosmosConstants.SDK_THIN_CLIENT_ENABLED_ENVIRONMENT_VARIABLE);
        assumeTrue(
                environmentValue == null || environmentValue.isEmpty(),
                "Test requires COSMOS_THINCLIENT_ENABLED to be unset");
    }

    private static MockedConstruction<CosmosClientBuilder> mockBuilderConstruction() {
        CosmosClient client = mock(CosmosClient.class);
        return mockConstruction(
                CosmosClientBuilder.class,
                withSettings().defaultAnswer(invocation -> {
                    if (CosmosClientBuilder.class.isAssignableFrom(
                            invocation.getMethod().getReturnType())) {
                        return invocation.getMock();
                    }
                    return null;
                }),
                (builder, context) -> when(builder.buildClient()).thenReturn(client));
    }
}

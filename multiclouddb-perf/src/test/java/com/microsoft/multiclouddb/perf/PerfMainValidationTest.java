package com.microsoft.multiclouddb.perf;

import org.junit.jupiter.api.Test;

import com.microsoft.multiclouddb.e2e.ConfigLoader;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PerfMainValidationTest {

    @Test
    void workloadParsingAndScenarioDefaultsAreStable() {
        assertEquals("query", PerfMain.workloadOpt("query"));
        assertEquals(List.of("S3", "S4", "S5"), PerfMain.resolveScenarios(new LinkedHashMap<>(), "query"));
        assertEquals(List.of("S1", "S6"), PerfMain.resolveScenarios(new LinkedHashMap<>(), "read"));
        assertEquals("all", PerfMain.workloadOpt("all"));
        assertEquals(List.of("S1", "S6", "S3", "S4", "S5"),
                PerfMain.resolveScenarios(new LinkedHashMap<>(), "all"));
        assertEquals(List.of("read", "write"), PerfMain.scenarioWorkloads("S1", "all"));
        assertEquals(List.of("query"), PerfMain.scenarioWorkloads("S3", "all"));
        assertThrows(IllegalArgumentException.class, () -> PerfMain.workloadOpt("bogus"));
    }

    @Test
    void dynamoCapacityArgsMustBePaired() {
        assertThrows(IllegalArgumentException.class, () -> PerfMain.validateDynamoCapacityArgs(100, 0));
        assertThrows(IllegalArgumentException.class, () -> PerfMain.validateDynamoCapacityArgs(0, 100));
    }

    @Test
    void cosmosTransportProfileDefaultsToHttp2AndTagsGatewayV2() {
        // The provider enables HTTP/2 by default, so an unset key must not be labelled
        // HTTP/1.1 — Statistics.aggregate would otherwise merge two incomparable runs.
        assertEquals("gateway HTTP/2 pool=sdk-default minPool=sdk-default streams=sdk-default",
                PerfMain.transportProfile("cosmos", cfg()));

        assertEquals("gateway-v2/thin-client HTTP/2 pool=64 minPool=8 streams=32",
                PerfMain.transportProfile("cosmos", cfg(
                        "multiclouddb.connection.thinClientEnabled", "true",
                        "multiclouddb.connection.gatewayHttp2MaxConnectionPoolSize", "64",
                        "multiclouddb.connection.gatewayHttp2MinConnectionPoolSize", "8",
                        "multiclouddb.connection.gatewayHttp2MaxConcurrentStreams", "32")));
    }

    @Test
    void cosmosTransportProfileDistinguishesParityAndDirectProfiles() {
        assertEquals("gateway HTTP/1.1 pool=64",
                PerfMain.transportProfile("cosmos", cfg(
                        "multiclouddb.connection.gatewayHttp2Enabled", "false",
                        "multiclouddb.connection.gatewayMaxConnectionPoolSize", "64")));

        assertEquals("direct (RNTBD)",
                PerfMain.transportProfile("cosmos", cfg(
                        "multiclouddb.connection.connectionMode", "direct")));

        assertEquals("Apache HTTP/1.1 pool=64",
                PerfMain.transportProfile("dynamo", cfg(
                        "multiclouddb.connection.maxConnections", "64")));
    }

    private static ConfigLoader.AppConfig cfg(String... keyValuePairs) {
        Properties props = new Properties();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            props.setProperty(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return new ConfigLoader.AppConfig(null, props);
    }

    @Test
    void throttleThresholdParsesPercentToFraction() {
        Map<String, String> opt = new LinkedHashMap<>();
        opt.put("invalid-throttle-rate-pct", "0.5");
        assertEquals(0.005, PerfMain.throttleThreshold(opt), 1e-9);
    }
}

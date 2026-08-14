package com.microsoft.multiclouddb.perf;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    void throttleThresholdParsesPercentToFraction() {
        Map<String, String> opt = new LinkedHashMap<>();
        opt.put("invalid-throttle-rate-pct", "0.5");
        assertEquals(0.005, PerfMain.throttleThreshold(opt), 1e-9);
    }
}

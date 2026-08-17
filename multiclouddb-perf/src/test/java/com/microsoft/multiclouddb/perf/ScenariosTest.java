package com.microsoft.multiclouddb.perf;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenariosTest {

    @Test
    void variantAcceptsOnlyTheTwoScopeTokens() {
        assertEquals("scoped", Scenarios.variant("scoped"));
        assertEquals("unscoped", Scenarios.variant(" unscoped "));
        assertNull(Scenarios.variant(null));
        assertNull(Scenarios.variant("query seeding failed: boom"));
        assertNull(Scenarios.variant("2part"));
    }

    @Test
    void scopeLabelDistinguishesCreateFromTheOtherPointOperations() {
        // create writes a unique partition key per item; the others share one.
        assertEquals("single item, unique partition key", Scenarios.scopeLabel(null, "create"));
        assertEquals("single item, shared partition key", Scenarios.scopeLabel(null, "update"));
        assertEquals("all partitions", Scenarios.scopeLabel(null, "readChanges"));
        assertEquals("single-partition", Scenarios.scopeLabel("scoped", "query"));
        assertEquals("cross-partition", Scenarios.scopeLabel("unscoped", "query"));
    }

    @Test
    void profilesSplitByOperationAndScopeSoEachRowDescribesItsOwnScope() {
        Map<String, Scenarios.Profile> profiles = Scenarios.profiles(List.of(
                statRow("S1", "write", "create", null),
                statRow("S1", "write", "update", null),
                statRow("S3", "query", "query", "scoped"),
                statRow("S3", "query", "query", "unscoped")));

        assertEquals(4, profiles.size());
        assertEquals(List.of("single item, unique partition key",
                        "single item, shared partition key",
                        "single-partition", "cross-partition"),
                profiles.values().stream().map(Scenarios.Profile::scope).toList());
    }

    @Test
    void purposesDescribeEachWorkloadOfAScenarioNotJustTheFirst() {
        Map<String, String> purposes = Scenarios.purposes(Scenarios.profiles(List.of(
                statRow("S1", "read", "read", null),
                statRow("S1", "write", "create", null))));

        assertEquals(List.of("S1 \u00b7 read", "S1 \u00b7 write"), List.copyOf(purposes.keySet()));
        assertTrue(purposes.get("S1 \u00b7 write").contains("unique partition key"),
                "write description should describe the write phases: " + purposes.get("S1 \u00b7 write"));
        assertTrue(purposes.get("S1 \u00b7 read").contains("reads them back"),
                "read description should describe the read phase: " + purposes.get("S1 \u00b7 read"));
    }

    @Test
    void duplicateScenarioNoteNamesScenariosThatRanIdenticalProfiles() {
        String note = Scenarios.duplicateScenarioNote(Scenarios.profiles(List.of(
                statRow("S3", "query", "query", "scoped"),
                statRow("S4", "query", "query", "unscoped"),
                statRow("S5", "query", "query", "unscoped"))));

        assertTrue(note.contains("S4, S5"), note);

        assertNull(Scenarios.duplicateScenarioNote(Scenarios.profiles(List.of(
                statRow("S3", "query", "query", "scoped"),
                statRow("S4", "query", "query", "unscoped")))));
    }

    @Test
    void docSizeLabelDistinguishesNoBodyFromASize() {
        assertEquals("no body", Scenarios.docSizeLabel(0));
        assertEquals("1024 B", Scenarios.docSizeLabel(1024));
    }

    private static StatRow statRow(String scenario, String workload, String operation, String variant) {
        return new StatRow("cosmos", operation, workload, scenario, variant, 8, 1024, 100,
                1, 100, 100, 10.0, 20.0, 30.0, 30.0, 15.0, 1.0,
                5.0, 5.0, 25.0, 80.0, 80.0, 80.0, 1.0,
                "RU", 1.0, 1.0, 80.0, "RU/s", 400.0, 20.0,
                0, 0.0, null, null, 0.0);
    }
}

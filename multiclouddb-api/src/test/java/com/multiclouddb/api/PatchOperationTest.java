// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PatchOperationTest {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private static final class MutablePayload {
        private String label;

        MutablePayload(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    @Test
    void snapshotsMutableValuesAtConstructionAndAccess() {
        List<String> tags = new ArrayList<>(List.of("new"));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("tags", tags);

        PatchOperation operation = PatchOperation.set("/metadata", input);
        tags.add("mutated-after-construction");
        input.put("owner", "mutated-after-construction");

        Object value = operation.value();
        if (!(value instanceof Map<?, ?> snapshot)) {
            throw new AssertionError("PatchOperation must expose a map snapshot");
        }
        assertFalse(snapshot.containsKey("owner"));
        assertEquals(List.of("new"), snapshot.get("tags"));

        assertThrows(UnsupportedOperationException.class, () -> snapshot.put(null, null));
        Object tagValue = snapshot.get("tags");
        if (!(tagValue instanceof List<?> tagSnapshot)) {
            throw new AssertionError("PatchOperation must expose a nested list snapshot");
        }
        assertThrows(UnsupportedOperationException.class, () -> tagSnapshot.add(null));
        Object secondValue = operation.value();
        if (!(secondValue instanceof Map<?, ?> secondSnapshot)) {
            throw new AssertionError("PatchOperation must expose a map snapshot on every access");
        }
        assertEquals(List.of("new"), secondSnapshot.get("tags"));
    }

    @Test
    void snapshotsAtomicNumbersArraysNestedContainersAndJacksonNodes() {
        AtomicInteger atomic = new AtomicInteger(7);
        String[] tags = { "first", "second" };
        ObjectNode json = JSON_MAPPER.createObjectNode().put("enabled", true);
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("tags", tags);
        nested.put("json", json);
        List<Object> input = new ArrayList<>(List.of(nested));

        PatchOperation atomicOperation = PatchOperation.increment("/counter", atomic);
        PatchOperation operation = PatchOperation.set("/metadata", input);
        atomic.set(8);
        tags[0] = "mutated";
        json.put("enabled", false);
        nested.put("newField", "mutated");
        input.clear();

        assertEquals(7L, atomicOperation.value());

        List<?> snapshot = org.junit.jupiter.api.Assertions.assertInstanceOf(List.class, operation.value());
        Map<?, ?> nestedSnapshot = org.junit.jupiter.api.Assertions.assertInstanceOf(Map.class, snapshot.get(0));
        assertEquals(List.of("first", "second"), nestedSnapshot.get("tags"));
        Map<?, ?> jsonSnapshot = org.junit.jupiter.api.Assertions.assertInstanceOf(
                Map.class, nestedSnapshot.get("json"));
        assertEquals(true, jsonSnapshot.get("enabled"));
        assertFalse(nestedSnapshot.containsKey("newField"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(null));
    }

    @Test
    void normalizesDatesCharSequencesAndPojoValuesToDetachedJsonForms() {
        Date created = new Date(1234L);
        StringBuilder title = new StringBuilder("before");
        MutablePayload payload = new MutablePayload("original");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("created", created);
        input.put("title", title);
        input.put("payload", payload);

        PatchOperation operation = PatchOperation.set("/metadata", input);
        created.setTime(9999L);
        title.append("-mutated");
        payload.label = "mutated";

        Map<?, ?> snapshot = org.junit.jupiter.api.Assertions.assertInstanceOf(
                Map.class, operation.value());
        assertEquals(1234L, snapshot.get("created"),
                "Date values must become an immutable epoch-millisecond JSON number");
        assertEquals("before", snapshot.get("title"),
                "mutable CharSequence values must become strings");
        Map<?, ?> payloadSnapshot = org.junit.jupiter.api.Assertions.assertInstanceOf(
                Map.class, snapshot.get("payload"));
        assertEquals("original", payloadSnapshot.get("label"),
                "POJOs must be detached through the common Jackson representation");
    }

    /**
     * The construction snapshot is the only snapshot. {@link PatchOperation#value()}
     * hands the stored graph out directly — no per-access deep copy — so the
     * detachment and unmodifiability proved above must hold for the stored graph
     * itself, and repeated reads must be identity-stable.
     */
    @Test
    void valueIsHandedOutWithoutAPerAccessDeepCopy() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("tags", new ArrayList<>(List.of("new")));
        input.put("nested", new LinkedHashMap<>(Map.of("depth", 1)));

        PatchOperation operation = PatchOperation.set("/metadata", input);

        assertSame(operation.value(), operation.value(),
                "value() must return the construction snapshot, not a fresh copy per access");

        Map<?, ?> snapshot = org.junit.jupiter.api.Assertions.assertInstanceOf(
                Map.class, operation.value());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.remove("tags"));
        List<?> tags = org.junit.jupiter.api.Assertions.assertInstanceOf(
                List.class, snapshot.get("tags"));
        assertThrows(UnsupportedOperationException.class, tags::clear);
        Map<?, ?> nested = org.junit.jupiter.api.Assertions.assertInstanceOf(
                Map.class, snapshot.get("nested"));
        assertThrows(UnsupportedOperationException.class, () -> nested.put(null, null));

        // Mutating a container reachable from a previous read must be impossible,
        // so a later read cannot observe caller-injected state either.
        assertEquals(List.of("new"), tags);
        assertEquals(1, nested.get("depth"));
    }

    @Test
    void rejectsNonJsonMapKeysAndNonFiniteValuesAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> PatchOperation.set("/metadata", Map.of(1, "not-a-json-key")));
        assertThrows(IllegalArgumentException.class,
                () -> PatchOperation.set("/value", Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> PatchOperation.set("/value", Double.POSITIVE_INFINITY));
    }}

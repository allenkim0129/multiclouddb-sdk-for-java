// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.google.cloud.spanner.DatabaseAdminClient;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.DatabaseId;
import com.google.cloud.spanner.ErrorCode;
import com.google.cloud.spanner.InstanceAdminClient;
import com.google.cloud.spanner.InstanceConfigId;
import com.google.cloud.spanner.InstanceId;
import com.google.cloud.spanner.InstanceInfo;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerException;
import com.google.cloud.spanner.SpannerOptions;
import com.fasterxml.jackson.databind.JsonNode;
import com.multiclouddb.api.DocumentResult;
import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbClientFactory;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.PatchOperation;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spanner update-replacement regression tests.
 *
 * <p>The SDK's envelope is authoritative. Full writes explicitly clear omitted
 * or runtime-incompatible physical mirrors, making replacement semantics
 * correct even for legacy rows that pre-date the envelope.
 *
 * <p>This test runs only under the {@code -Pemulator-spanner} CI profile
 * because it needs a real Spanner instance (the {@code readWriteTransaction}
 * + raw-DML inserts cannot be exercised against the in-process unit harness).
 * It is tagged with both {@code spanner} and {@code emulator} so {@code -Punit}
 * (which excludes those tags) skips it.
 */
@DisplayName("Spanner — update() replaces the authoritative document envelope")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("spanner")
@Tag("emulator")
class SpannerLegacyRowUpdateEmulatorTest {

    private static final String EMULATOR_HOST = System.getProperty("spanner.emulatorHost", "localhost:9010");
    private static final String PROJECT_ID = "test-project";
    private static final String INSTANCE_ID = "test-instance";
    private static final String DATABASE_ID = "legacyupdatetestdb";
    private static final String TABLE = "legacyupdatetests";

    private MulticloudDbClient client;
    private Spanner rawSpanner;
    private DatabaseClient rawDbClient;
    private final ResourceAddress address = new ResourceAddress(DATABASE_ID, TABLE);

    @BeforeAll
    void setUp() throws ExecutionException, InterruptedException {
        SpannerOptions options = SpannerOptions.newBuilder()
                .setEmulatorHost(EMULATOR_HOST).setProjectId(PROJECT_ID).build();
        rawSpanner = options.getService();

        InstanceAdminClient instanceAdmin = rawSpanner.getInstanceAdminClient();
        try {
            instanceAdmin.createInstance(InstanceInfo.newBuilder(InstanceId.of(PROJECT_ID, INSTANCE_ID))
                    .setInstanceConfigId(InstanceConfigId.of(PROJECT_ID, "emulator-config"))
                    .setDisplayName("Test Instance").setNodeCount(1).build()).get();
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof SpannerException se) || se.getErrorCode() != ErrorCode.ALREADY_EXISTS)
                throw e;
        }

        DatabaseAdminClient dbAdmin = rawSpanner.getDatabaseAdminClient();
        try {
            dbAdmin.createDatabase(INSTANCE_ID, DATABASE_ID, List.of(
                    "CREATE TABLE " + TABLE + " ("
                            + "  partitionKey STRING(MAX) NOT NULL,"
                            + "  sortKey STRING(MAX) NOT NULL,"
                            + "  data STRING(MAX),"
                            + "  name STRING(MAX),"
                            + "  email STRING(MAX),"
                            + "  status STRING(MAX),"
                            + "  priority INT64"
                            + ") PRIMARY KEY (partitionKey, sortKey)")).get();
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof SpannerException se) || se.getErrorCode() != ErrorCode.ALREADY_EXISTS)
                throw e;
        }

        rawDbClient = rawSpanner.getDatabaseClient(DatabaseId.of(PROJECT_ID, INSTANCE_ID, DATABASE_ID));

        client = MulticloudDbClientFactory.create(MulticloudDbClientConfig.builder()
                .provider(ProviderId.SPANNER)
                .connection("projectId", PROJECT_ID)
                .connection("instanceId", INSTANCE_ID)
                .connection("databaseId", DATABASE_ID)
                .connection("emulatorHost", EMULATOR_HOST)
                .build());
    }

    @AfterAll
    void tearDown() throws Exception {
        if (client != null) client.close();
        if (rawSpanner != null) rawSpanner.close();
    }

    /**
     * Seeds a row directly via the raw Spanner client without writing the
     * internal {@code data} (FIELD_DATA) metadata column — exactly the shape
     * a row would have if it pre-dated this SDK or was written by a sibling
     * system. Returns nothing; the row is keyed on the supplied PK / SK.
     */
    private void seedLegacyRow(String pk, String sk, Mutation.WriteBuilder extraColumns) {
        Mutation mutation = extraColumns.build();
        rawDbClient.write(List.of(mutation));
    }

    @Test
    @DisplayName("legacy row (FIELD_DATA NULL): update() exposes only the replacement document")
    void updateReplacesLegacyRowDocument() {
        String pk = "u1";
        String sk = "u1";

        // 1) Seed a row bypassing the SDK so FIELD_DATA stays NULL — mimics a
        //    row that pre-dates the metadata-column rollout or was written by
        //    a sibling system that doesn't know about FIELD_DATA.
        seedLegacyRow(pk, sk, Mutation.newInsertBuilder(TABLE)
                .set("partitionKey").to(pk)
                .set("sortKey").to(sk)
                .set("name").to("Ada")
                .set("email").to("ada@x")
                .set("status").to("active")
                .set("priority").to(5L));

        // 2) Replace the legacy document with a payload that names only email.
        client.update(address, MulticloudDbKey.of(pk, sk), Map.of("email", "ada@new"), null);

        // 3) Read back via the SDK. The replacement envelope defines the
        //    portable document and incompatible or omitted mirrors are cleared.
        DocumentResult result = client.read(address, MulticloudDbKey.of(pk, sk), null);
        assertNotNull(result, "read() must find the row that was updated");
        JsonNode doc = result.document();

        assertEquals("ada@new", doc.path("email").asText(),
                "the replacement value must round-trip");
        assertFalse(doc.has("name"),
                "legacy physical columns omitted by update() must not leak through the envelope");
        assertFalse(doc.has("status"));
        assertFalse(doc.has("priority"));
    }

    @Test
    @DisplayName("successive updates replace prior envelopes instead of merging them")
    void successiveUpdatesDoNotMergeLegacyColumns() {
        String pk = "u2";
        String sk = "u2";

        seedLegacyRow(pk, sk, Mutation.newInsertBuilder(TABLE)
                .set("partitionKey").to(pk)
                .set("sortKey").to(sk)
                .set("name").to("Bob")
                .set("email").to("bob@x")
                .set("status").to("active")
                .set("priority").to(7L));

        // Each call is a full replacement of the envelope and its compatible
        // physical mirrors.
        client.update(address, MulticloudDbKey.of(pk, sk), Map.of("email", "bob@new"), null);
        client.update(address, MulticloudDbKey.of(pk, sk), Map.of("priority", 9L), null);

        DocumentResult result = client.read(address, MulticloudDbKey.of(pk, sk), null);
        assertNotNull(result);
        JsonNode doc = result.document();
        assertEquals(9L, doc.path("priority").asLong(),
                "the second replacement must apply");
        assertFalse(doc.has("name"));
        assertFalse(doc.has("email"),
                "the first replacement must not merge into the second replacement");
        assertFalse(doc.has("status"));
    }

    @Test
    @DisplayName("SDK-written row: update() replaces the prior envelope")
    void sdkWrittenRowUpdateReplacesPriorEnvelope() {
        String pk = "u3";
        String sk = "u3";

        // Establish an envelope with two fields, then replace it with a
        // one-field update.
        client.create(address, MulticloudDbKey.of(pk, sk),
                Map.of("name", "Cara", "email", "cara@x"), null);
        client.update(address, MulticloudDbKey.of(pk, sk),
                Map.of("email", "cara@new"), null);

        DocumentResult result = client.read(address, MulticloudDbKey.of(pk, sk), null);
        assertNotNull(result);
        JsonNode doc = result.document();
        assertEquals("cara@new", doc.path("email").asText());
        assertFalse(doc.has("name"),
                "a field omitted from update() must not survive through an older envelope");
    }

    @Test
    @DisplayName("full writes preserve cross-type values in the envelope and clear stale mirrors")
    void fullWritesDoNotFailWhenAFieldChangesPhysicalType() {
        String pk = "cross-type-" + UUID.randomUUID();
        String sk = pk;
        MulticloudDbKey key = MulticloudDbKey.of(pk, sk);
        try {
            client.create(address, key, Map.of("priority", 5L, "name", "Ada"), null);

            Map<String, Object> replacement = new LinkedHashMap<>();
            replacement.put("priority", "now-text");
            replacement.put("name", 99L);
            client.update(address, key, replacement, null);

            DocumentResult updated = client.read(address, key, null);
            assertNotNull(updated);
            assertEquals("now-text", updated.document().path("priority").asText(),
                    "the envelope must preserve a value incompatible with INT64");
            assertEquals(99L, updated.document().path("name").asLong(),
                    "the envelope must preserve a value incompatible with STRING");

            com.google.cloud.spanner.Struct afterTypeChange = rawDbClient.singleUse().readRow(
                    TABLE, com.google.cloud.spanner.Key.of(pk, sk), List.of("priority", "name"));
            assertTrue(afterTypeChange.isNull("priority"),
                    "an incompatible replacement must clear the stale INT64 mirror");
            assertTrue(afterTypeChange.isNull("name"),
                    "an incompatible replacement must clear the stale STRING mirror");

            client.upsert(address, key, Map.of("priority", 12L), null);
            com.google.cloud.spanner.Struct afterUpsert = rawDbClient.singleUse().readRow(
                    TABLE, com.google.cloud.spanner.Key.of(pk, sk), List.of("priority", "name"));
            assertEquals(12L, afterUpsert.getLong("priority"));
            assertTrue(afterUpsert.isNull("name"),
                    "an omitted field in a full upsert must not retain its old physical mirror");

            client.patch(address, key, List.of(PatchOperation.set("/priority", "patched-text")), null);
            DocumentResult patched = client.read(address, key, null);
            assertNotNull(patched);
            assertEquals("patched-text", patched.document().path("priority").asText(),
                    "PATCH must retain an incompatible value in the authoritative envelope");

            com.google.cloud.spanner.Struct afterPatch = rawDbClient.singleUse().readRow(
                    TABLE, com.google.cloud.spanner.Key.of(pk, sk), List.of("priority"));
            assertTrue(afterPatch.isNull("priority"),
                    "PATCH must clear an incompatible physical mirror rather than fail or retain stale data");
        } finally {
            client.delete(address, key, null);
        }
    }
}

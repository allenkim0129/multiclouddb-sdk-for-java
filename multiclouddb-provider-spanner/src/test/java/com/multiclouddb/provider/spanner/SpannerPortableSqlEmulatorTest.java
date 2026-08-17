// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.google.cloud.spanner.DatabaseAdminClient;
import com.google.cloud.spanner.DatabaseId;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.ErrorCode;
import com.google.cloud.spanner.InstanceAdminClient;
import com.google.cloud.spanner.InstanceConfigId;
import com.google.cloud.spanner.InstanceId;
import com.google.cloud.spanner.InstanceInfo;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerException;
import com.google.cloud.spanner.SpannerOptions;
import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbClientFactory;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.PatchOperation;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.QueryPage;
import com.multiclouddb.api.QueryRequest;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.SortDirection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that the GoogleSQL this provider generates is actually accepted by
 * Spanner's query analyser.
 *
 * <p><b>Why this test exists.</b> Every portable field reference expands to
 * {@code SAFE.PARSE_JSON} + {@code JSON_QUERY} + {@code JSON_TYPE} +
 * {@code LAX_FLOAT64}/{@code LAX_BOOL}/{@code LAX_STRING} +
 * {@code TO_JSON(r)}, and ORDER BY now expands the same way.
 * Spanner analyses the whole statement regardless of which {@code CASE} branch
 * executes at runtime, so a single unsupported construct — notably the
 * {@code TO_JSON(r)} row projection used by the legacy-row fallback — would fail
 * <em>every</em> portable Spanner query, not just the legacy-row ones.
 * String assertions over the generated WHERE clause cannot catch that; only
 * execution can.
 *
 * <p>Coverage: each portable predicate form is executed against both an
 * envelope row (written through the SDK) and a legacy row (seeded through the
 * raw Spanner client with a NULL {@code data} column), plus an ORDER BY over a
 * dynamic top-level field that has no DDL column and over a field whose
 * physical mirror was cleared to typed NULL by an incompatible patch.
 *
 * <p>Tagged {@code spanner} + {@code emulator} like
 * {@link SpannerLegacyRowUpdateEmulatorTest}, so {@code -Punit} (which excludes
 * both tags) skips it; it runs under {@code -Pemulator-spanner}.
 */
@DisplayName("Spanner — generated portable GoogleSQL is accepted by the analyser")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("spanner")
@Tag("emulator")
class SpannerPortableSqlEmulatorTest {

    private static final String EMULATOR_HOST = System.getProperty("spanner.emulatorHost", "localhost:9010");
    private static final String PROJECT_ID = "test-project";
    private static final String INSTANCE_ID = "test-instance";
    private static final String DATABASE_ID = "portablesqltestdb";
    private static final String TABLE = "portablesqltests";
    private static final String PARTITION = "portable-sql";

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
                    .setInstanceConfigId(InstanceConfigId.of(PROJECT_ID,
                            SpannerConstants.EMULATOR_INSTANCE_CONFIG_ID))
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
                            + "  status STRING(MAX),"
                            + "  value INT64"
                            + ") PRIMARY KEY (partitionKey, sortKey)")).get();
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof SpannerException se) || se.getErrorCode() != ErrorCode.ALREADY_EXISTS)
                throw e;
        }

        rawDbClient = rawSpanner.getDatabaseClient(DatabaseId.of(PROJECT_ID, INSTANCE_ID, DATABASE_ID));

        client = MulticloudDbClientFactory.create(MulticloudDbClientConfig.builder()
                .provider(ProviderId.SPANNER)
                .connection(SpannerConstants.CONFIG_PROJECT_ID, PROJECT_ID)
                .connection(SpannerConstants.CONFIG_INSTANCE_ID, INSTANCE_ID)
                .connection(SpannerConstants.CONFIG_DATABASE_ID, DATABASE_ID)
                .connection(SpannerConstants.CONFIG_EMULATOR_HOST, EMULATOR_HOST)
                .build());

        // Envelope row: written through the SDK, so `data` holds the authoritative
        // document. `onSale` and `tags` are dynamic — they have no DDL column.
        client.upsert(address, MulticloudDbKey.of(PARTITION, "envelope"),
                Map.of("name", "Ada", "status", "active", "value", 9L,
                        "onSale", true, "tags", List.of("a", "b")), null);

        // Legacy row: seeded through the raw client with `data` left NULL, so every
        // portable predicate must fall through to the TO_JSON(r) physical branch.
        rawDbClient.write(List.of(Mutation.newInsertOrUpdateBuilder(TABLE)
                .set(SpannerConstants.FIELD_PARTITION_KEY).to(PARTITION)
                .set(SpannerConstants.FIELD_SORT_KEY).to("legacy")
                .set("name").to("Bob")
                .set("status").to("active")
                .set("value").to(10L)
                .build()));
    }

    @AfterAll
    void tearDown() throws Exception {
        if (client != null) client.close();
        if (rawSpanner != null) rawSpanner.close();
    }

    private QueryPage run(QueryRequest request) {
        return client.query(address, request, null);
    }

    private QueryRequest.Builder scoped() {
        return QueryRequest.builder().partitionKey(PARTITION).maxPageSize(100);
    }

    @Test
    @DisplayName("every portable predicate form is analysable over envelope and legacy rows")
    void portablePredicatesExecuteAgainstBothRowShapes() {
        // One entry per emitted construct: comparison type guards (number /
        // string / boolean), IN, BETWEEN, null comparison, and each function —
        // STARTS_WITH, STRPOS, JSON_TYPE, CHAR_LENGTH and JSON_QUERY_ARRAY.
        List<QueryRequest> requests = new ArrayList<>();
        requests.add(scoped().expression("status = @s").parameter("s", "active").build());
        requests.add(scoped().expression("value > @v").parameter("v", 1).build());
        requests.add(scoped().expression("onSale = @b").parameter("b", true).build());
        requests.add(scoped().expression("status IN (@a, @b)")
                .parameter("a", "active").parameter("b", "idle").build());
        requests.add(scoped().expression("value BETWEEN @lo AND @hi")
                .parameter("lo", 1).parameter("hi", 100).build());
        requests.add(scoped().expression("status = null").build());
        requests.add(scoped().expression("starts_with(name, @p)").parameter("p", "A").build());
        requests.add(scoped().expression("contains(name, @p)").parameter("p", "d").build());
        requests.add(scoped().expression("field_exists(onSale)").build());
        // NOTE: string_length(...) and collection_size(...) are deliberately absent.
        // Both translate to a bare scalar (CHAR_LENGTH / ARRAY_LENGTH), so they are only
        // meaningful as a comparison operand -- but ExpressionParser.parseFunctionCall
        // returns immediately without accepting a trailing comparison operator, so
        // "string_length(name) > @n" fails to parse. Until that pre-existing parser gap
        // is closed these two functions are unreachable and cannot be exercised here.
        // Nested path: exercises the multi-segment JSON path builder.
        requests.add(scoped().expression("meta.region = @r").parameter("r", "westus").build());

        for (QueryRequest request : requests) {
            QueryPage page = assertDoesNotThrow(() -> run(request),
                    "Spanner must accept the generated GoogleSQL for: " + request.expression());
            assertNotNull(page.items(), "items() must never be null for: " + request.expression());
        }
    }

    @Test
    @DisplayName("ORDER BY over a dynamic field with no DDL column is analysable and ordered")
    void orderByDynamicFieldExecutes() {
        // `onSale` has no physical column. A bare `ORDER BY onSale` fails
        // GoogleSQL analysis with "Unrecognized name: onSale", while Cosmos and
        // DynamoDB sort it without complaint — the divergence this asserts away.
        QueryPage page = assertDoesNotThrow(
                () -> run(scoped().orderBy("onSale", SortDirection.ASC).build()));
        assertNotNull(page.items());

        // Same, combined with a portable predicate (queryWithTranslation path).
        assertDoesNotThrow(() -> run(scoped()
                .expression("status = @s").parameter("s", "active")
                .orderBy("onSale", SortDirection.DESC)
                .build()));
    }

    @Test
    @DisplayName("ORDER BY sorts on the envelope after an incompatible patch clears the mirror")
    void orderBySortsOnEnvelopeNotTheClearedMirror() {
        MulticloudDbKey key = MulticloudDbKey.of(PARTITION, "cleared-mirror");
        try {
            client.upsert(address, key, Map.of("name", "Cara", "status", "active", "value", 1L), null);
            // 1 + 0.5 = 1.5 is not representable in the INT64 `value` column, so the
            // physical mirror is deliberately cleared to typed NULL. Ordering on the
            // bare column would sort this row as NULL even though read() returns 1.5.
            client.patch(address, key, List.of(PatchOperation.increment("/value", 0.5d)), null);

            QueryPage page = run(scoped().orderBy("value", SortDirection.ASC).build());
            List<Object> ordered = new ArrayList<>();
            for (Map<String, Object> item : page.items()) {
                if (item.get("value") != null) {
                    ordered.add(((Number) item.get("value")).doubleValue());
                }
            }
            assertTrue(ordered.size() >= 3, "seeded rows must be returned: " + page.items());
            assertEquals(List.of(1.5d, 9.0d, 10.0d), ordered.subList(0, 3),
                    "ORDER BY must sort numerically on the authoritative envelope value, "
                            + "not on the cleared INT64 mirror");
        } finally {
            client.delete(address, key, null);
        }
    }

    @Test
    @DisplayName("ORDER BY sorts numbers numerically, not as JSON text")
    void orderByIsNumericNotLexicographic() {
        MulticloudDbKey nine = MulticloudDbKey.of(PARTITION, "num-9");
        MulticloudDbKey ten = MulticloudDbKey.of(PARTITION, "num-10");
        try {
            client.upsert(address, nine, Map.of("rank", 9L), null);
            client.upsert(address, ten, Map.of("rank", 10L), null);

            QueryPage page = run(scoped().orderBy("rank", SortDirection.ASC).build());
            List<Long> ranks = new ArrayList<>();
            for (Map<String, Object> item : page.items()) {
                if (item.get("rank") != null) {
                    ranks.add(((Number) item.get("rank")).longValue());
                }
            }
            // Sorting the raw JSON text would yield [10, 9].
            assertEquals(List.of(9L, 10L), ranks,
                    "numeric JSON values must sort numerically");
        } finally {
            client.delete(address, nine, null);
            client.delete(address, ten, null);
        }
    }

    @Test
    @DisplayName("cross-type ORDER BY ranks null/absent < boolean < number < string, matching Cosmos")
    void orderByCrossTypeRankMatchesCosmosTotalOrder() {
        // DynamoDB declares ORDER_BY unsupported, so Cosmos and Spanner are the
        // only two providers on this surface. Cosmos NoSQL's documented total
        // order is `undefined < null < boolean < number < string`; Spanner
        // previously ranked number < string < boolean < null/absent, an ungated
        // cross-provider divergence. This executes the emitted rank so the
        // agreement is proven against the engine, not just the SQL string.
        String partition = PARTITION + "-rank";
        List<MulticloudDbKey> seeded = new ArrayList<>();
        try {
            seeded.add(seedRanked(partition, "1-absent", null, false));
            seeded.add(seedRanked(partition, "2-json-null", null, true));
            seeded.add(seedRanked(partition, "3-bool-false", false, true));
            seeded.add(seedRanked(partition, "4-bool-true", true, true));
            seeded.add(seedRanked(partition, "5-number-9", 9L, true));
            seeded.add(seedRanked(partition, "6-number-10", 10L, true));
            seeded.add(seedRanked(partition, "7-string-a", "a", true));

            List<String> expected = List.of("1-absent", "2-json-null", "3-bool-false",
                    "4-bool-true", "5-number-9", "6-number-10", "7-string-a");
            assertEquals(expected, rankedLabels(partition, SortDirection.ASC),
                    "ASC must follow Cosmos's total order; absent and JSON null tie on the "
                            + "lowest rank and are separated by the appended sortKey tiebreaker");

            // DESC is the exact reverse of ASC apart from the rank-1 tie, whose
            // members are still separated by the ASC sortKey tiebreaker.
            List<String> desc = new ArrayList<>(expected.subList(2, expected.size()));
            java.util.Collections.reverse(desc);
            desc.addAll(expected.subList(0, 2));
            assertEquals(desc, rankedLabels(partition, SortDirection.DESC),
                    "the rank must carry the direction so DESC reverses ASC");
        } finally {
            for (MulticloudDbKey key : seeded) {
                client.delete(address, key, null);
            }
        }
    }

    /** Seeds one cross-type ORDER BY row; {@code present=false} omits `mixed` entirely. */
    private MulticloudDbKey seedRanked(String partition, String label, Object mixed, boolean present) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("label", label);
        if (present) {
            // A LinkedHashMap (unlike Map.of) can carry an explicit JSON null.
            document.put("mixed", mixed);
        }
        MulticloudDbKey key = MulticloudDbKey.of(partition, label);
        client.upsert(address, key, document, null);
        return key;
    }

    private List<String> rankedLabels(String partition, SortDirection direction) {
        QueryPage page = client.query(address, QueryRequest.builder()
                .partitionKey(partition).maxPageSize(100)
                .orderBy("mixed", direction).build(), null);
        List<String> labels = new ArrayList<>();
        for (Map<String, Object> item : page.items()) {
            labels.add((String) item.get("label"));
        }
        return labels;
    }
}

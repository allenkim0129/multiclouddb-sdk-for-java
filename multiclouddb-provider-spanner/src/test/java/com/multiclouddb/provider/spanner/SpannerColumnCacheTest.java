// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.ReadContext;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.Value;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.OperationNames;
import com.multiclouddb.api.OperationOptions;
import com.multiclouddb.api.ResourceAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.OngoingStubbing;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the Spanner physical-column metadata <em>cache</em>
 * and for case-insensitive physical-column mirroring.
 *
 * <p><b>Why this test exists.</b> Three defects made the mirrors silently stop
 * being written while SDK reads stayed correct through the {@code data}
 * envelope — a failure mode with no visible symptom until a non-SDK consumer
 * reads the table:
 *
 * <ol>
 *   <li>{@code loadTableColumns} returns an <em>empty map</em> (not an error)
 *       for a table that does not exist yet, and {@code create()} resolves
 *       columns before its write fails. Caching that empty result poisoned the
 *       client for its whole lifetime: after a later {@code ensureContainer()}
 *       created the table, every mirror column was still skipped.</li>
 *   <li>Nothing invalidated the entry after DDL, so a {@code CREATE TABLE} /
 *       {@code ALTER TABLE ... ADD COLUMN} was never picked up.</li>
 *   <li>The cache key was not case-folded even though the lookup query matches
 *       on {@code LOWER(TABLE_NAME) = LOWER(@_tablename)}, so {@code Todos} and
 *       {@code todos} loaded and held two independent entries.</li>
 * </ol>
 *
 * <p>Mirroring itself was case-sensitive in an otherwise case-insensitive file:
 * a column declared {@code Status} never matched a document field
 * {@code status} and was cleared to a typed NULL, and a column declared
 * {@code PartitionKey} was treated as a mirror column and set twice in one
 * mutation — which Spanner rejects with {@code Duplicate column name}.
 *
 * <p>No provider tag is set, so the {@code unit} profile runs this test.
 */
@DisplayName("Spanner — column-metadata cache and case-insensitive mirroring")
class SpannerColumnCacheTest {

    private static final MulticloudDbKey KEY = MulticloudDbKey.of("pk", "sk");

    /** Counts only the INFORMATION_SCHEMA probes, not the table-exists probe. */
    private final AtomicInteger metadataQueries = new AtomicInteger();
    private final Deque<ResultSet> metadataResponses = new ArrayDeque<>();
    private final DatabaseClient database = mock(DatabaseClient.class);
    private final ResultSet tableExistsProbe = mock(ResultSet.class);
    private final ResultSet noColumns = columnMetadata(List.of());

    SpannerColumnCacheTest() {
        ReadContext readContext = mock(ReadContext.class);
        when(database.singleUse()).thenReturn(readContext);
        // Every response is built eagerly: stubbing a fresh mock from inside an
        // Answer would run `when(...)` during another mock's invocation, which
        // Mockito reports as unfinished stubbing.
        when(readContext.executeQuery(any(Statement.class))).thenAnswer(invocation -> {
            Statement statement = invocation.getArgument(0);
            if (!statement.getSql().contains("INFORMATION_SCHEMA.COLUMNS")) {
                // ensureContainer's table-exists probe: any non-throwing result
                // set means "table exists".
                return tableExistsProbe;
            }
            metadataQueries.incrementAndGet();
            return metadataResponses.isEmpty() ? noColumns : metadataResponses.poll();
        });
    }

    /** Queues the column layout the next INFORMATION_SCHEMA probe will report. */
    private void nextLayout(String... nameThenTypePairs) {
        List<String[]> columns = new ArrayList<>();
        for (int i = 0; i < nameThenTypePairs.length; i += 2) {
            columns.add(new String[] { nameThenTypePairs[i], nameThenTypePairs[i + 1] });
        }
        metadataResponses.add(columnMetadata(columns));
    }

    private static ResultSet columnMetadata(List<String[]> columns) {
        ResultSet rs = mock(ResultSet.class);
        OngoingStubbing<Boolean> hasNext = when(rs.next());
        for (int i = 0; i < columns.size(); i++) {
            hasNext = hasNext.thenReturn(true);
        }
        hasNext.thenReturn(false);
        if (columns.isEmpty()) {
            return rs;
        }
        // Each when(...) chain must be completed before the next one starts —
        // Mockito rejects interleaved stubbing as "unfinished stubbing".
        OngoingStubbing<String> names = when(rs.getString(SpannerConstants.COLUMN_METADATA_NAME));
        for (String[] column : columns) {
            names = names.thenReturn(column[0]);
        }
        OngoingStubbing<String> types = when(rs.getString(SpannerConstants.COLUMN_METADATA_SPANNER_TYPE));
        for (String[] column : columns) {
            types = types.thenReturn(column[1]);
        }
        return rs;
    }

    private SpannerProviderClient client() {
        return new SpannerProviderClient(mock(Spanner.class), database);
    }

    /** Returns every mutation handed to {@code DatabaseClient.write}, in order. */
    private List<Mutation> writtenMutations() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<Mutation>> written = ArgumentCaptor.forClass(Iterable.class);
        verify(database, org.mockito.Mockito.atLeastOnce()).write(written.capture());
        List<Mutation> mutations = new ArrayList<>();
        for (Iterable<Mutation> batch : written.getAllValues()) {
            batch.forEach(mutations::add);
        }
        return mutations;
    }

    private static Map<String, Value> columnsOf(Mutation mutation) {
        Map<String, Value> byName = new LinkedHashMap<>();
        mutation.asMap().forEach(byName::put);
        return byName;
    }

    @Test
    @DisplayName("an empty (table-missing) metadata result is never cached")
    void emptyMetadataResultIsNotCached() {
        // First write happens before the table exists: INFORMATION_SCHEMA has no
        // rows for it. Caching that would silently disable mirroring forever.
        nextLayout();
        nextLayout("name", "STRING(MAX)");
        SpannerProviderClient client = client();

        client.create(new ResourceAddress("db", "items"), KEY, Map.of("name", "Ada"),
                OperationOptions.defaults());
        client.create(new ResourceAddress("db", "items"), KEY, Map.of("name", "Ada"),
                OperationOptions.defaults());

        assertEquals(2, metadataQueries.get(),
                "an empty metadata result must be re-probed, not cached as 'no columns'");
        List<Mutation> mutations = writtenMutations();
        assertNull(columnsOf(mutations.get(0)).get("name"),
                "no table, no mirror — the envelope alone carries the document");
        assertEquals("Ada", columnsOf(mutations.get(1)).get("name").getString(),
                "once the table exists, the mirror must be written again");
    }

    @Test
    @DisplayName("a non-empty result is cached (one probe per table)")
    void populatedMetadataResultIsCached() {
        nextLayout("name", "STRING(MAX)");
        SpannerProviderClient client = client();
        ResourceAddress address = new ResourceAddress("db", "items");

        client.create(address, KEY, Map.of("name", "Ada"), OperationOptions.defaults());
        client.create(address, KEY, Map.of("name", "Grace"), OperationOptions.defaults());

        assertEquals(1, metadataQueries.get(),
                "a valid layout must be cached — re-probing on every write is a per-write cost");
    }

    @Test
    @DisplayName("the cache entry is invalidated after ensureContainer() runs its DDL")
    void ensureContainerInvalidatesTheCachedLayout() {
        nextLayout("name", "STRING(MAX)");
        nextLayout("name", "STRING(MAX)", "status", "STRING(MAX)");
        SpannerProviderClient client = client();
        ResourceAddress address = new ResourceAddress("db", "items");

        client.create(address, KEY, Map.of("name", "Ada", "status", "active"),
                OperationOptions.defaults());
        assertNull(columnsOf(writtenMutations().get(0)).get("status"),
                "precondition: `status` has no column yet");

        client.ensureContainer(address);

        client.create(address, KEY, Map.of("name", "Ada", "status", "active"),
                OperationOptions.defaults());
        assertEquals(2, metadataQueries.get(),
                "DDL can add columns — the stale layout must be dropped");
        assertEquals("active", columnsOf(writtenMutations().get(1)).get("status").getString(),
                "a column added by DDL must start being mirrored without a client restart");
    }

    @Test
    @DisplayName("the cache key is case-folded, like the INFORMATION_SCHEMA lookup itself")
    void cacheKeyIsCaseInsensitive() {
        nextLayout("name", "STRING(MAX)");
        SpannerProviderClient client = client();

        client.create(new ResourceAddress("db", "Todos"), KEY, Map.of("name", "Ada"),
                OperationOptions.defaults());
        client.create(new ResourceAddress("db", "todos"), KEY, Map.of("name", "Ada"),
                OperationOptions.defaults());

        assertEquals(1, metadataQueries.get(),
                "Spanner table names are case-insensitive and the metadata query already "
                        + "matches on LOWER(TABLE_NAME), so `Todos` and `todos` are one entry");
    }

    @Test
    @DisplayName("a mixed-case DDL shape mirrors by case-insensitive name and never duplicates a key column")
    void mixedCaseDdlShapeMirrorsCorrectly() {
        // A customer-managed table is free to declare `PartitionKey` / `Status`:
        // Spanner resolves column identifiers case-insensitively.
        nextLayout("PartitionKey", "STRING(MAX)",
                "SortKey", "STRING(MAX)",
                "Data", "STRING(MAX)",
                "Status", "STRING(MAX)",
                "Priority", "INT64");
        SpannerProviderClient client = client();

        client.create(new ResourceAddress("db", "Todos"), KEY,
                Map.of("status", "active", "priority", 5L), OperationOptions.defaults());

        Mutation mutation = writtenMutations().get(0);
        Map<String, Value> columns = columnsOf(mutation);

        assertNotNull(columns.get("Status"), "the `Status` column must be discovered: " + columns);
        assertEquals("active", columns.get("Status").getString(),
                "a `Status` column must mirror the `status` document field, not be NULLed");
        assertEquals(5L, columns.get("Priority").getInt64(),
                "a `Priority` column must mirror the `priority` document field");

        // `PartitionKey` / `SortKey` / `Data` are key/envelope columns under a
        // different casing: treating them as mirrors would set the same Spanner
        // column twice in one mutation and fail with "Duplicate column name".
        List<String> folded = new ArrayList<>();
        for (String column : mutation.getColumns()) {
            folded.add(column.toLowerCase(Locale.ROOT));
        }
        assertEquals(folded.size(), folded.stream().distinct().count(),
                "no Spanner column may be set twice in one mutation: " + folded);
        assertTrue(folded.contains(SpannerConstants.FIELD_PARTITION_KEY.toLowerCase(Locale.ROOT)), folded.toString());
        assertTrue(folded.contains(SpannerConstants.FIELD_DATA.toLowerCase(Locale.ROOT)), folded.toString());
    }

    @Test
    @DisplayName("an unserialisable nested value fails with INVALID_REQUEST instead of storing toString()")
    void unserialisableNestedValueIsRejected() {
        nextLayout("name", "STRING(MAX)");
        SpannerProviderClient client = client();

        // A nested value Jackson cannot serialise used to be silently replaced by
        // its Java toString() (e.g. `{k=java.lang.Object@1b6d}`), corrupting the
        // stored document rather than reporting the bad request.
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("payload", Map.of("k", new Object()));

        MulticloudDbException error = assertThrows(MulticloudDbException.class,
                () -> client.create(new ResourceAddress("db", "items"), KEY, document,
                        OperationOptions.defaults()));
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, error.error().category());
        assertEquals(OperationNames.CREATE, error.error().operation());
        assertTrue(error.error().message().contains("cannot be serialised"), error.error().message());
    }
}

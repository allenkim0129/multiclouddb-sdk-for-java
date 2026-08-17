// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.ReadContext;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.Type;
import com.google.cloud.spanner.Value;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.OperationNames;
import com.multiclouddb.api.OperationOptions;
import com.multiclouddb.api.PatchOperation;
import com.multiclouddb.api.ResourceAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
 * Regression coverage for the Spanner physical-column metadata probe.
 *
 * <p><b>Why this test exists.</b> The probe used to run
 * {@code SELECT * FROM <table> LIMIT 0} and read {@link ResultSet#getType()}.
 * In {@code google-cloud-spanner} both {@code getType()} and
 * {@code getMetadata()} are guarded by
 * {@code Preconditions.checkState(..., "next() call required")} — verified by
 * disassembling {@code GrpcResultSet} in 6.62.0 — and a {@code LIMIT 0} probe
 * never calls {@code next()}. Every Spanner write (create / update / upsert /
 * patch) therefore threw a raw {@link IllegalStateException}, which is not a
 * {@code SpannerException} and so escaped the provider's error-normalization
 * handlers entirely.
 *
 * <p>The mocks below make that failure mode explicit: {@code next()} yields the
 * {@code INFORMATION_SCHEMA} rows, while {@code getType()} and
 * {@code getMetadata()} are stubbed to throw. A write that still succeeds
 * proves the column-load path no longer depends on either.
 *
 * <p>No provider tag is set, so the {@code unit} profile runs this test.
 */
@DisplayName("Spanner — column metadata loads without a next()-dependent API")
class SpannerTableColumnMetadataTest {

    private static final ResourceAddress ADDRESS = new ResourceAddress("db", "items");
    private static final MulticloudDbKey KEY = MulticloudDbKey.of("pk", "sk");

    /** Stubs the INFORMATION_SCHEMA probe with a two-column table layout. */
    private static ResultSet columnMetadataResultSet() {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getString(SpannerConstants.COLUMN_METADATA_NAME)).thenReturn("name", "priority");
        when(rs.getString(SpannerConstants.COLUMN_METADATA_SPANNER_TYPE))
                .thenReturn("STRING(MAX)", "INT64");
        // The two APIs that made every write fail before this fix.
        when(rs.getType()).thenThrow(new IllegalStateException("next() call required"));
        when(rs.getMetadata()).thenThrow(new IllegalStateException("next() call required"));
        return rs;
    }

    private static DatabaseClient databaseClientWith(ResultSet metadataRows, ReadContext readContext) {
        DatabaseClient database = mock(DatabaseClient.class);
        when(database.singleUse()).thenReturn(readContext);
        when(readContext.executeQuery(any(Statement.class))).thenReturn(metadataRows);
        return database;
    }

    @Test
    @DisplayName("create() resolves physical columns even when getType()/getMetadata() throw")
    void writePathResolvesColumnsWithoutCallingGetType() {
        ReadContext readContext = mock(ReadContext.class);
        DatabaseClient database = databaseClientWith(columnMetadataResultSet(), readContext);
        SpannerProviderClient client =
                new SpannerProviderClient(mock(Spanner.class), database);

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("name", "Ada");
        document.put("priority", 5L);
        document.put("onSale", true); // dynamic field: no physical column, envelope only

        assertDoesNotThrow(() -> client.create(ADDRESS, KEY, document, OperationOptions.defaults()),
                "the write path must not depend on ResultSet.getType()/getMetadata()");

        // The probe must be the INFORMATION_SCHEMA query, not a `LIMIT 0` scan.
        ArgumentCaptor<Statement> probe = ArgumentCaptor.forClass(Statement.class);
        verify(readContext).executeQuery(probe.capture());
        String probeSql = probe.getValue().getSql();
        assertTrue(probeSql.contains("INFORMATION_SCHEMA.COLUMNS"),
                "column metadata must come from INFORMATION_SCHEMA; got: " + probeSql);
        assertTrue(probeSql.contains(SpannerConstants.COLUMN_METADATA_SPANNER_TYPE), probeSql);
        assertEquals("items",
                probe.getValue().getParameters().get(SpannerConstants.PARAM_TABLE_NAME).getString(),
                "the probe must bind the target table name as a parameter");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<Mutation>> written =
                ArgumentCaptor.forClass(Iterable.class);
        verify(database).write(written.capture());
        Mutation mutation = written.getValue().iterator().next();
        Map<String, Value> columns = mutation.asMap();

        assertEquals("Ada", columns.get("name").getString(),
                "a STRING(MAX) physical mirror must be discovered and written");
        assertEquals(5L, columns.get("priority").getInt64(),
                "an INT64 physical mirror must be discovered and written");
        assertNull(columns.get("onSale"),
                "a dynamic field with no physical column must stay envelope-only");
        assertNotNull(columns.get(SpannerConstants.FIELD_DATA),
                "the authoritative envelope must always be written");
        assertTrue(columns.get(SpannerConstants.FIELD_DATA).getString().contains("onSale"),
                "the envelope must carry the dynamic field");
    }

    @Test
    @DisplayName("a raw JDK failure in the metadata probe surfaces as PROVIDER_ERROR")
    void rawRuntimeExceptionFromProbeIsNormalized() {
        ResultSet rs = mock(ResultSet.class);
        // Simulate any residual raw JDK failure from the metadata path.
        when(rs.next()).thenThrow(new IllegalStateException("next() call required"));
        DatabaseClient database = databaseClientWith(rs, mock(ReadContext.class));
        SpannerProviderClient client =
                new SpannerProviderClient(mock(Spanner.class), database);

        MulticloudDbException error = assertThrows(MulticloudDbException.class,
                () -> client.create(ADDRESS, KEY, Map.of("name", "Ada"),
                        OperationOptions.defaults()),
                "a raw JDK exception must never cross the portable surface");
        assertEquals(MulticloudDbErrorCategory.PROVIDER_ERROR, error.error().category());
        assertEquals(OperationNames.CREATE, error.error().operation());

        // patch() shares the same metadata path and must normalize identically.
        MulticloudDbException patchError = assertThrows(MulticloudDbException.class,
                () -> client.patch(ADDRESS, KEY, List.of(PatchOperation.set("/name", "Ada")),
                        OperationOptions.defaults()));
        assertEquals(MulticloudDbErrorCategory.PROVIDER_ERROR, patchError.error().category());
        assertEquals(OperationNames.PATCH, patchError.error().operation());
    }

    @Test
    @DisplayName("SPANNER_TYPE strings map onto the physical mirror types")
    void spannerTypeStringsMapToTypes() {
        assertEquals(Type.string(), SpannerProviderClient.parseSpannerType("STRING(MAX)"));
        assertEquals(Type.string(), SpannerProviderClient.parseSpannerType("STRING(1024)"));
        assertEquals(Type.int64(), SpannerProviderClient.parseSpannerType("INT64"));
        assertEquals(Type.float64(), SpannerProviderClient.parseSpannerType("FLOAT64"));
        assertEquals(Type.float32(), SpannerProviderClient.parseSpannerType("FLOAT32"));
        assertEquals(Type.bool(), SpannerProviderClient.parseSpannerType("BOOL"));
        assertEquals(Type.numeric(), SpannerProviderClient.parseSpannerType("NUMERIC"));
        assertEquals(Type.json(), SpannerProviderClient.parseSpannerType("JSON"));
        assertEquals(Type.bytes(), SpannerProviderClient.parseSpannerType("BYTES(MAX)"));
        assertEquals(Type.timestamp(), SpannerProviderClient.parseSpannerType("TIMESTAMP"));
        assertEquals(Type.date(), SpannerProviderClient.parseSpannerType("DATE"));
        assertEquals(Type.array(Type.int64()),
                SpannerProviderClient.parseSpannerType("ARRAY<INT64>"));

        // Unknown / unrepresentable declarations are skipped rather than guessed —
        // they are never mirrorable, so omitting them is behaviour-preserving.
        assertNull(SpannerProviderClient.parseSpannerType(null));
        assertNull(SpannerProviderClient.parseSpannerType("TOKENLIST"));
        assertNull(SpannerProviderClient.parseSpannerType("PROTO<some.Message>"));
        assertNull(SpannerProviderClient.parseSpannerType("ARRAY<TOKENLIST>"));
    }
}

// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.ReadContext;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.Struct;
import com.google.cloud.spanner.ResultSets;
import com.google.cloud.Timestamp;
import com.multiclouddb.api.OperationOptions;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.document.*;
import com.multiclouddb.api.changefeed.ChangeEvent;
import com.multiclouddb.api.changefeed.ChangeType;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.changefeed.ChangeFeedCursor;
import com.multiclouddb.api.changefeed.internal.CursorAnchor;
import com.multiclouddb.api.changefeed.internal.CursorToken;
import com.multiclouddb.api.changefeed.internal.PartitionPosition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SpannerChangeFeedReader#listCursors(ResourceAddress)}.
 * <p>
 * The success path (row decoding) is exercised by the emulator-backed
 * {@code SpannerChangeFeedConformanceTest}; mocking the deeply-nested
 * change-stream row schema in a unit test would be more fragile than
 * informative. This unit test covers the most ambiguous timing branch —
 * placeholder minting on an empty TVF result — and asserts that the
 * {@code issuedAtEpochMillis} stamped on the placeholder reflects the
 * instant the result-set was observed exhausted, matching the invariant
 * established by the Cosmos and Dynamo readers.
 */
class SpannerChangeFeedReaderTest {

    private static final ResourceAddress ADDR = new ResourceAddress("test_db", "test_collection");

    @Test
    void finalEventsPreserveSelectedValuesNullEmptyAndFieldDataFiltering() {
        String newer = "{\"partitionKey\":\"tenant\",\"sortKey\":\"record\","
                + "\"nested\":[null,{\"n\":2}],\"stale\":true,\"data\":\"[\\\"nested\\\"]\"}";
        String older = "{\"old\":true}";
        ChangeEvent inserted = readValues("INSERT", newer, older);
        assertEquals(ChangeType.CREATE, inserted.type());
        assertEquals(ObjectValue.of(Map.of("partitionKey", new StringValue("tenant"),
                "sortKey", new StringValue("record"), "nested", ArrayValue.of(List.of(
                        NullValue.INSTANCE, ObjectValue.of(Map.of("n", NumberValue.of(2))))))), inserted.data());
        assertTrue(((ObjectValue) inserted.data()).get("data").isEmpty());
        assertTrue(((ObjectValue) inserted.data()).get("stale").isEmpty());
        assertEquals(inserted.data(), readValues("UPDATE", newer, older).data());
        ChangeEvent deleted = readValues("DELETE", newer, older);
        assertEquals(ChangeType.DELETE, deleted.type());
        assertEquals(ObjectValue.of(Map.of("old", new BooleanValue(true))), deleted.data());
        assertEquals(NullValue.INSTANCE, readValues("UPDATE", "null", null).data());
        assertEquals(ObjectValue.of(Map.of("bytes", new StringValue("AQI="))),
                readValues("UPDATE", "{\"bytes\":\"AQI=\"}", null).data(),
                "native change-stream JSON text is not inferred to be binary");
        assertEquals(ObjectValue.of(Map.of()), readValues("UPDATE", null, null).data());
        assertEquals(ObjectValue.of(Map.of()), readValues("UPDATE", "{}", older).data());
        assertEquals(ObjectValue.of(Map.of("old", new BooleanValue(true))), readValues("UPDATE", null, older).data());
    }

    @Test
    void modelDomainFailuresAreSafeClassifiedFeedErrors() {
        for (String invalid : List.of("{\"private-field\":1e309}", "{\"private-field\":-1e309}",
                "{\"private-field\":" + "[".repeat(128) + "null" + "]".repeat(128) + "}")) {
            MulticloudDbException failure = assertThrows(MulticloudDbException.class,
                    () -> readValues("UPDATE", invalid, null));
            assertEquals(MulticloudDbErrorCategory.PROVIDER_ERROR, failure.error().category());
            assertEquals(ProviderId.SPANNER, failure.error().provider());
            assertEquals("readChanges", failure.error().operation());
            assertFalse(failure.error().retryable());
            assertEquals(Map.of("reason", "invalid_document_payload"), failure.error().providerDetails());
            assertEquals("Provider response cannot be represented as a Document value.", failure.error().message());
            assertNull(failure.getCause());
        }
    }

    private static ChangeEvent readValues(String operation, String newer, String older) {
        Timestamp now = Timestamp.now();
        Struct mod = Struct.newBuilder().set("keys").to("{\"partitionKey\":\"tenant\",\"sortKey\":\"record\"}")
                .set("new_values").to(newer).set("old_values").to(older).build();
        Struct record = Struct.newBuilder().set("commit_timestamp").to(now)
                .set("record_sequence").to("1").set("server_transaction_id").to("txn")
                .set("mod_type").to(operation).set("mods").toStructArray(mod.getType(), List.of(mod)).build();
        Struct outer = Struct.newBuilder().set("data_change_record")
                .toStructArray(record.getType(), List.of(record)).build();
        Struct row = Struct.newBuilder().set("ChangeRecord")
                .toStructArray(outer.getType(), List.of(outer)).build();
        DatabaseClient db = mock(DatabaseClient.class);
        ReadContext context = mock(ReadContext.class);
        when(db.singleUse()).thenReturn(context);
        when(context.executeQuery(any(Statement.class))).thenAnswer(
                call -> ResultSets.forRows(row.getType(), List.of(row)));
        ChangeFeedCursor cursor = new ChangeFeedCursor(new CursorToken(ProviderId.SPANNER, ADDR,
                System.currentTimeMillis(), CursorAnchor.CONTINUING,
                List.of(new PartitionPosition("partition", now + "|0|0"))));
        return new SpannerChangeFeedReader(ProviderId.SPANNER, db, Map.of())
                .readChanges(ADDR, cursor, OperationOptions.defaults()).events().get(0);
    }

    @Test
    @DisplayName("Empty result placeholder: one __bootstrap__ cursor with issuedAt within call window")
    void emptyResult_mintsBootstrapPlaceholderWithFreshIssuedAt() {
        DatabaseClient db = mock(DatabaseClient.class);
        ReadContext ctx = mock(ReadContext.class);
        ResultSet rs = mock(ResultSet.class);
        when(db.singleUse()).thenReturn(ctx);
        when(ctx.executeQuery(any(Statement.class))).thenReturn(rs);
        // Empty TVF result: first call to next() returns false.
        when(rs.next()).thenReturn(false);

        SpannerChangeFeedReader reader = new SpannerChangeFeedReader(
                ProviderId.SPANNER, db, Map.of());

        long preCall = System.currentTimeMillis();
        List<ChangeFeedCursor> cursors = reader.listCursors(ADDR);
        long postCall = System.currentTimeMillis();

        assertEquals(1, cursors.size(),
                "empty TVF result must mint exactly one bootstrap placeholder cursor");
        ChangeFeedCursor c = cursors.get(0);
        String partitionId = c.token().partitions().get(0).partitionId();
        assertNotNull(partitionId);
        assertEquals("__bootstrap__", partitionId,
                "placeholder partitionId must be __bootstrap__; was " + partitionId);
        long issuedAt = c.token().issuedAtEpochMillis();
        assertTrue(issuedAt >= preCall && issuedAt <= postCall,
                "placeholder issuedAt (" + issuedAt + ") must be within [preCall="
                        + preCall + ", postCall=" + postCall + "] — proving it was captured"
                        + " after the result was observed exhausted, not before the query was issued");
    }
}

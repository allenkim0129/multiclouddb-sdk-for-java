// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.google.cloud.Timestamp;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.ReadContext;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.Struct;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.OperationOptions;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.changefeed.ChangeEvent;
import com.multiclouddb.api.changefeed.ChangeFeedPage;
import com.multiclouddb.api.changefeed.ChangeFeedRequest;
import com.multiclouddb.api.changefeed.ChangeType;
import com.multiclouddb.api.changefeed.FeedScope;
import com.multiclouddb.api.changefeed.NewItemStateMode;
import com.multiclouddb.api.changefeed.StartPosition;
import com.multiclouddb.api.changefeed.internal.ContinuationTokenCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SpannerChangeFeed} that exercise the wire-protocol
 * parsing and continuation-token plumbing without requiring a live Spanner
 * instance or emulator.
 *
 * <p>Mocking strategy:
 * <ul>
 *   <li>{@link DatabaseClient}, {@link ReadContext}, {@link ResultSet} are
 *       Mockito mocks of the Spanner SDK interfaces.</li>
 *   <li>{@link Struct} is mocked too — the {@code TVF} returns a
 *       {@code List<Struct>} whose nested fields we stub per scenario.</li>
 *   <li>The {@code Statement} that the adapter sends is captured and
 *       inspected to verify SQL shape and bind parameters.</li>
 * </ul>
 */
class SpannerChangeFeedTest {

    private static final ResourceAddress ADDR = new ResourceAddress("db", "events");

    private DatabaseClient db;
    private ReadContext ctx;

    @BeforeEach
    void setUp() {
        db = mock(DatabaseClient.class);
        ctx = mock(ReadContext.class);
        when(db.singleUse()).thenReturn(ctx);
    }

    // ── stream-name resolution ─────────────────────────────────────────────

    @Test
    @DisplayName("stream name defaults to <collection>_changes when no override is configured")
    void streamNameDefault() {
        SpannerChangeFeed feed = new SpannerChangeFeed(db, Map.of());
        Statement stmt = captureTvfFor(feed, ADDR, /*partitionToken*/ null, emptyResultSet());
        assertTrue(stmt.getSql().contains("READ_events_changes"),
                "default stream name must be '<collection>_changes', got SQL: " + stmt.getSql());
    }

    @Test
    @DisplayName("stream name is taken from connection key 'changeStream.<collection>'")
    void streamNameFromConfig() {
        Map<String, String> conn = Map.of("changeStream.events", "my_custom_stream");
        SpannerChangeFeed feed = new SpannerChangeFeed(db, conn);
        Statement stmt = captureTvfFor(feed, ADDR, null, emptyResultSet());
        assertTrue(stmt.getSql().contains("READ_my_custom_stream"),
                "configured stream name must be honoured, got SQL: " + stmt.getSql());
    }

    @Test
    @DisplayName("invalid stream identifier (SQL-injection attempt) rejected with INVALID_REQUEST")
    void rejectsInvalidStreamName() {
        Map<String, String> conn = Map.of("changeStream.events", "evil; DROP TABLE x; --");
        SpannerChangeFeed feed = new SpannerChangeFeed(db, conn);
        ChangeFeedRequest req = ChangeFeedRequest.builder(ADDR).build();
        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> feed.readChanges(req, OperationOptions.defaults()));
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
        verifyNoInteractions(ctx);
    }

    // ── capability gate (defence-in-depth) ─────────────────────────────────

    @Test
    @DisplayName("FeedScope.LogicalPartition rejected with UNSUPPORTED_CAPABILITY")
    void logicalPartitionRejected() {
        SpannerChangeFeed feed = new SpannerChangeFeed(db, Map.of());
        ChangeFeedRequest req = ChangeFeedRequest.builder(ADDR)
                .scope(FeedScope.logicalPartition(MulticloudDbKey.of("pk1")))
                .build();
        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> feed.readChanges(req, OperationOptions.defaults()));
        assertEquals(MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY, ex.error().category());
        verifyNoInteractions(ctx);
    }

    // ── listPhysicalPartitions ─────────────────────────────────────────────

    @Test
    @DisplayName("listPhysicalPartitions returns child_partitions tokens from the root probe")
    void listPhysicalPartitionsReturnsChildren() {
        Struct childPartition1 = mock(Struct.class);
        when(childPartition1.getString("token")).thenReturn("p-1");
        Struct childPartition2 = mock(Struct.class);
        when(childPartition2.getString("token")).thenReturn("p-2");

        Struct cpr = mock(Struct.class);
        when(cpr.getTimestamp("start_timestamp")).thenReturn(Timestamp.now());
        when(cpr.getStructList("child_partitions"))
                .thenReturn(List.of(childPartition1, childPartition2));

        Struct outer = stubChangeRecord(/*data*/ null, /*childPartitions*/ cpr);
        ResultSet rs = singleRow(outer);
        ResultSet __rs = rs;
        when(ctx.executeQuery(any(Statement.class))).thenReturn(__rs);

        SpannerChangeFeed feed = new SpannerChangeFeed(db, Map.of());
        List<String> ids = feed.listPhysicalPartitions(ADDR);

        assertEquals(List.of("p-1", "p-2"), ids);
    }

    // ── readChanges happy path ─────────────────────────────────────────────

    @Test
    @DisplayName("readChanges maps INSERT mod -> CREATE event with key + new_values payload")
    void readChangesInsert() {
        Struct mod = mock(Struct.class);
        when(mod.isNull("keys")).thenReturn(false);
        when(mod.getJson("keys")).thenReturn("{\"partitionKey\":\"pk1\",\"sortKey\":\"sk1\"}");
        when(mod.isNull("new_values")).thenReturn(false);
        when(mod.getJson("new_values")).thenReturn("{\"score\":42}");
        when(mod.isNull("old_values")).thenReturn(true);

        Struct dcr = stubDataChangeRecord("INSERT", List.of(mod));
        Struct outer = stubChangeRecord(dcr, null);
        ResultSet __rs = singleRow(outer);
        when(ctx.executeQuery(any(Statement.class))).thenReturn(__rs);

        SpannerChangeFeed feed = new SpannerChangeFeed(db, Map.of());
        ChangeFeedRequest req = ChangeFeedRequest.builder(ADDR)
                .scope(FeedScope.physicalPartition("p-1"))
                .startPosition(StartPosition.now())
                .build();
        ChangeFeedPage page = feed.readChanges(req, OperationOptions.defaults());

        assertEquals(1, page.events().size());
        ChangeEvent ev = page.events().get(0);
        assertEquals(ProviderId.SPANNER, ev.provider());
        assertEquals(ChangeType.CREATE, ev.eventType());
        assertEquals("pk1", ev.key().partitionKey());
        assertEquals("sk1", ev.key().sortKey());
        assertNotNull(ev.data(), "INCLUDE_IF_AVAILABLE mode must surface new_values");
        assertEquals(42, ev.data().get("score").asInt());
    }

    @Test
    @DisplayName("readChanges maps UPDATE -> UPDATE and DELETE -> DELETE (data null on delete)")
    void readChangesUpdateAndDelete() {
        Struct updateMod = stubKeyMod("pk1", "sk1", "{\"v\":2}");
        Struct deleteMod = stubKeyMod("pk2", "sk2", null);
        Struct updateDcr = stubDataChangeRecord("UPDATE", List.of(updateMod));
        Struct deleteDcr = stubDataChangeRecord("DELETE", List.of(deleteMod));

        Struct row1 = stubChangeRecord(updateDcr, null);
        Struct row2 = stubChangeRecord(deleteDcr, null);
        ResultSet __rs = twoRows(row1, row2);
        when(ctx.executeQuery(any(Statement.class))).thenReturn(__rs);

        SpannerChangeFeed feed = new SpannerChangeFeed(db, Map.of());
        ChangeFeedRequest req = ChangeFeedRequest.builder(ADDR)
                .scope(FeedScope.physicalPartition("p-1"))
                .build();
        ChangeFeedPage page = feed.readChanges(req, OperationOptions.defaults());

        assertEquals(2, page.events().size());
        assertEquals(ChangeType.UPDATE, page.events().get(0).eventType());
        assertEquals(ChangeType.DELETE, page.events().get(1).eventType());
        assertNotNull(page.events().get(0).data());
        assertNull(page.events().get(1).data(),
                "DELETE events must surface with null data() — Spanner does not return new_values");
    }

    // ── continuation-token plumbing ────────────────────────────────────────

    @Test
    @DisplayName("readChanges issues a continuation token that carries the partition token forward")
    void continuationTokenRoundTrip() {
        // First call: simple non-empty page, one data record, partition stays open.
        Struct mod = stubKeyMod("pk1", "sk1", "{\"v\":1}");
        Struct dcr = stubDataChangeRecord("INSERT", List.of(mod));
        Struct row = stubChangeRecord(dcr, null);
        ResultSet __rs = singleRow(row);
        when(ctx.executeQuery(any(Statement.class))).thenReturn(__rs);

        SpannerChangeFeed feed = new SpannerChangeFeed(db, Map.of());
        ChangeFeedRequest first = ChangeFeedRequest.builder(ADDR)
                .scope(FeedScope.physicalPartition("p-1"))
                .build();
        ChangeFeedPage page = feed.readChanges(first, OperationOptions.defaults());

        String token = page.continuationToken();
        assertNotNull(token, "non-retired partition must yield a non-null continuation token");

        // The token must round-trip through the codec scoped to SPANNER + ADDR
        assertDoesNotThrow(() -> ContinuationTokenCodec.decode(token, ProviderId.SPANNER, ADDR));

        // And the next call must accept it without throwing.
        ResultSet __rs2 = emptyResultSet();
        when(ctx.executeQuery(any(Statement.class))).thenReturn(__rs2);
        ChangeFeedRequest resume = ChangeFeedRequest.builder(ADDR)
                .scope(FeedScope.physicalPartition("p-1"))
                .startPosition(StartPosition.fromContinuationToken(token))
                .build();
        ChangeFeedPage page2 = feed.readChanges(resume, OperationOptions.defaults());
        assertNotNull(page2);
        assertTrue(page2.events().isEmpty());
    }

    @Test
    @DisplayName("continuation token from a different stream name is rejected with INVALID_REQUEST")
    void continuationTokenCrossStreamRejected() {
        // Bake a token under stream name "stream-A"
        SpannerChangeFeed feedA = new SpannerChangeFeed(db,
                Map.of("changeStream.events", "stream_A"));
        Struct mod = stubKeyMod("pk1", null, null);
        Struct dcr = stubDataChangeRecord("INSERT", List.of(mod));
        Struct row = stubChangeRecord(dcr, null);
        ResultSet __rs = singleRow(row);
        when(ctx.executeQuery(any(Statement.class))).thenReturn(__rs);
        ChangeFeedPage pageA = feedA.readChanges(
                ChangeFeedRequest.builder(ADDR).scope(FeedScope.physicalPartition("p-1")).build(),
                OperationOptions.defaults());
        String tokenA = pageA.continuationToken();
        assertNotNull(tokenA);

        // Resume under a feed configured for stream_B — must be rejected.
        SpannerChangeFeed feedB = new SpannerChangeFeed(db,
                Map.of("changeStream.events", "stream_B"));
        ChangeFeedRequest resume = ChangeFeedRequest.builder(ADDR)
                .scope(FeedScope.physicalPartition("p-1"))
                .startPosition(StartPosition.fromContinuationToken(tokenA))
                .build();
        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> feedB.readChanges(resume, OperationOptions.defaults()));
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
    }

    // ── partition retirement ───────────────────────────────────────────────

    @Test
    @DisplayName("PhysicalPartition partition retirement surfaces partitionRetired + childPartitions")
    void physicalPartitionRetirement() {
        Struct child = mock(Struct.class);
        when(child.getString("token")).thenReturn("p-2");

        Struct cpr = mock(Struct.class);
        when(cpr.getTimestamp("start_timestamp")).thenReturn(Timestamp.now());
        when(cpr.getStructList("child_partitions")).thenReturn(List.of(child));

        Struct row = stubChangeRecord(null, cpr);
        ResultSet __rs = singleRow(row);
        when(ctx.executeQuery(any(Statement.class))).thenReturn(__rs);

        SpannerChangeFeed feed = new SpannerChangeFeed(db, Map.of());
        ChangeFeedRequest req = ChangeFeedRequest.builder(ADDR)
                .scope(FeedScope.physicalPartition("p-1"))
                .build();
        ChangeFeedPage page = feed.readChanges(req, OperationOptions.defaults());

        assertTrue(page.partitionRetired(),
                "a partition that emits child_partitions_record must be marked retired");
        assertEquals(List.of("p-2"), page.childPartitions());
    }

    // ── newItemStateMode ───────────────────────────────────────────────────

    @Test
    @DisplayName("NewItemStateMode.OMIT yields events with null data() even on INSERT")
    void omitMode() {
        Struct mod = stubKeyMod("pk1", "sk1", "{\"v\":1}");
        Struct dcr = stubDataChangeRecord("INSERT", List.of(mod));
        Struct row = stubChangeRecord(dcr, null);
        ResultSet __rs = singleRow(row);
        when(ctx.executeQuery(any(Statement.class))).thenReturn(__rs);

        SpannerChangeFeed feed = new SpannerChangeFeed(db, Map.of());
        ChangeFeedRequest req = ChangeFeedRequest.builder(ADDR)
                .scope(FeedScope.physicalPartition("p-1"))
                .newItemStateMode(NewItemStateMode.OMIT)
                .build();
        ChangeFeedPage page = feed.readChanges(req, OperationOptions.defaults());

        assertEquals(1, page.events().size());
        assertNull(page.events().get(0).data(), "OMIT mode must drop new_values payload");
    }

    @Test
    @DisplayName("NewItemStateMode.REQUIRE throws UNSUPPORTED_CAPABILITY when new_values are absent")
    void requireModeFailsWithoutNewValues() {
        Struct mod = stubKeyMod("pk1", "sk1", null); // no new_values
        Struct dcr = stubDataChangeRecord("INSERT", List.of(mod));
        Struct row = stubChangeRecord(dcr, null);
        ResultSet __rs = singleRow(row);
        when(ctx.executeQuery(any(Statement.class))).thenReturn(__rs);

        SpannerChangeFeed feed = new SpannerChangeFeed(db, Map.of());
        ChangeFeedRequest req = ChangeFeedRequest.builder(ADDR)
                .scope(FeedScope.physicalPartition("p-1"))
                .newItemStateMode(NewItemStateMode.REQUIRE)
                .build();
        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> feed.readChanges(req, OperationOptions.defaults()));
        assertEquals(MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY, ex.error().category());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** Captures the {@link Statement} sent for a no-op execution. */
    private Statement captureTvfFor(SpannerChangeFeed feed, ResourceAddress addr,
                                    String partitionToken, ResultSet rs) {
        ArgumentCaptor<Statement> stmtCaptor = ArgumentCaptor.forClass(Statement.class);
        when(ctx.executeQuery(stmtCaptor.capture())).thenReturn(rs);
        ChangeFeedRequest req = partitionToken == null
                ? ChangeFeedRequest.builder(addr).build()
                : ChangeFeedRequest.builder(addr)
                        .scope(FeedScope.physicalPartition(partitionToken))
                        .build();
        feed.readChanges(req, OperationOptions.defaults());
        return stmtCaptor.getValue();
    }

    /** Outer ChangeRecord stub: at most one of dataChangeRecord / childPartitionsRecord is non-null. */
    private static Struct stubChangeRecord(Struct dataChangeRecord, Struct childPartitionsRecord) {
        Struct outer = mock(Struct.class);
        when(outer.isNull("data_change_record")).thenReturn(dataChangeRecord == null);
        when(outer.isNull("child_partitions_record")).thenReturn(childPartitionsRecord == null);
        when(outer.getStructList("data_change_record")).thenReturn(
                dataChangeRecord != null ? List.of(dataChangeRecord) : Collections.emptyList());
        when(outer.getStructList("child_partitions_record")).thenReturn(
                childPartitionsRecord != null ? List.of(childPartitionsRecord) : Collections.emptyList());
        return outer;
    }

    private static Struct stubDataChangeRecord(String modType, List<Struct> mods) {
        Struct dcr = mock(Struct.class);
        when(dcr.getString("mod_type")).thenReturn(modType);
        when(dcr.getTimestamp("commit_timestamp")).thenReturn(Timestamp.now());
        when(dcr.getString("record_sequence")).thenReturn("00000001");
        when(dcr.isNull("server_transaction_id")).thenReturn(false);
        when(dcr.getString("server_transaction_id")).thenReturn("txn-1");
        when(dcr.getStructList("mods")).thenReturn(mods);
        return dcr;
    }

    private static Struct stubKeyMod(String pk, String sk, String newValuesJson) {
        Struct mod = mock(Struct.class);
        when(mod.isNull("keys")).thenReturn(false);
        StringBuilder keys = new StringBuilder("{\"partitionKey\":\"").append(pk).append("\"");
        if (sk != null) {
            keys.append(",\"sortKey\":\"").append(sk).append("\"");
        }
        keys.append("}");
        when(mod.getJson("keys")).thenReturn(keys.toString());
        if (newValuesJson != null) {
            when(mod.isNull("new_values")).thenReturn(false);
            when(mod.getJson("new_values")).thenReturn(newValuesJson);
        } else {
            when(mod.isNull("new_values")).thenReturn(true);
        }
        when(mod.isNull("old_values")).thenReturn(true);
        return mod;
    }

    private static ResultSet emptyResultSet() {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        return rs;
    }

    private static ResultSet singleRow(Struct outerChangeRecord) {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, false);
        when(rs.getStructList("ChangeRecord")).thenReturn(List.of(outerChangeRecord));
        return rs;
    }

    private static ResultSet twoRows(Struct row1, Struct row2) {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getStructList("ChangeRecord"))
                .thenReturn(List.of(row1))
                .thenReturn(List.of(row2));
        return rs;
    }
}

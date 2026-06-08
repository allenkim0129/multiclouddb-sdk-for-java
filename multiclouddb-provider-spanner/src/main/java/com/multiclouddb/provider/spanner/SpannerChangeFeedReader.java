// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.Timestamp;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.ErrorCode;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.SpannerException;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.Struct;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.OperationOptions;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.changefeed.ChangeEvent;
import com.multiclouddb.api.changefeed.ChangeFeedCursor;
import com.multiclouddb.api.changefeed.ChangeFeedPage;
import com.multiclouddb.api.changefeed.ChangeType;
import com.multiclouddb.api.changefeed.CursorExpiredException;
import com.multiclouddb.api.changefeed.internal.CursorAnchor;
import com.multiclouddb.api.changefeed.internal.CursorToken;
import com.multiclouddb.api.changefeed.internal.PartitionPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cloud Spanner change-stream backed change-feed reader.
 * <p>
 * Spanner change streams are surfaced through a table-valued function (TVF) of the
 * shape:
 * <pre>
 *   SELECT * FROM READ_&lt;stream&gt;(
 *       start_timestamp        =&gt; @start_ts,
 *       end_timestamp          =&gt; @end_ts,
 *       partition_token        =&gt; @partition_token,
 *       heartbeat_milliseconds =&gt; @heartbeat
 *   )
 * </pre>
 * Each row carries either a {@code data_change_record}, a {@code heartbeat_record},
 * or a {@code child_partitions_record}. This reader bounds every TVF call with a
 * finite {@code end_timestamp} window (default: {@link #WINDOW_MS}) so each
 * {@link #readChanges} call returns in deterministic time.
 *
 * <h3>Provisioning prerequisite</h3>
 * The Spanner database must have a change stream watching the target table:
 * <pre>
 *   CREATE CHANGE STREAM &lt;collection&gt;_changes FOR &lt;collection&gt;
 *       OPTIONS (value_capture_type = 'NEW_ROW');
 * </pre>
 * The stream name can be overridden per collection via the
 * {@code changeStream.&lt;collection&gt;} connection key; otherwise
 * {@code &lt;collection&gt;_changes} is used.
 *
 * <h3>Partition state</h3>
 * Each {@link PartitionPosition} carries the Spanner partition token as its
 * {@code partitionId} and a "{@code commit_ts|record_seq}" continuation string
 * encoding the last successfully processed mod. The next read resumes
 * <em>strictly after</em> that point.
 *
 * <h3>Split / merge absorption</h3>
 * Whenever the TVF returns a {@code child_partitions_record}, its child partition
 * tokens are appended to the next cursor and the old (parent) partition is removed
 * once its window closes naturally.
 *
 * <h3>Limitations</h3>
 * <ul>
 *   <li>Without local emulator coverage this reader is verified only by the
 *       compiler and mock-based unit tests; live behaviour is exercised by the
 *       conformance suite.</li>
 *   <li>The TVF row schema varies slightly across Spanner versions. The reader
 *       reads field-by-field using {@link Struct} introspection to stay
 *       tolerant of optional columns.</li>
 * </ul>
 */
final class SpannerChangeFeedReader {

    private static final Logger LOG = LoggerFactory.getLogger(SpannerChangeFeedReader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Default time window per readChanges call (5 seconds). */
    private static final long WINDOW_MS = 5_000L;
    /** Default heartbeat (1 second). */
    private static final long HEARTBEAT_MS = 1_000L;
    /** Connection config key prefix: {@code changeStream.<collection>} overrides default. */
    private static final String CONFIG_STREAM_PREFIX = "changeStream.";

    private final ProviderId providerId;
    private final DatabaseClient databaseClient;
    private final Map<String, String> connection;

    SpannerChangeFeedReader(ProviderId providerId, DatabaseClient databaseClient,
                            Map<String, String> connection) {
        this.providerId = providerId;
        this.databaseClient = databaseClient;
        this.connection = connection;
    }

    static SpannerChangeFeedReader create(ProviderId providerId, DatabaseClient databaseClient,
                                          MulticloudDbClientConfig config) {
        return new SpannerChangeFeedReader(providerId, databaseClient, config.connection());
    }

    String streamNameFor(String collection) {
        String key = CONFIG_STREAM_PREFIX + collection;
        return connection.getOrDefault(key, collection + "_changes");
    }

    /**
     * Bootstrap the partition tree by calling the TVF with a NULL partition token,
     * which returns the root {@code child_partitions_record} entries.
     */
    List<ChangeFeedCursor> listCursors(ResourceAddress address) {
        String streamName = streamNameFor(address.collection());
        Timestamp now = Timestamp.now();
        Timestamp end = addMillis(now, WINDOW_MS);

        Statement stmt = Statement.newBuilder(
                "SELECT * FROM READ_" + sanitize(streamName) + "("
                        + "start_timestamp => @start_ts, "
                        + "end_timestamp => @end_ts, "
                        + "partition_token => NULL, "
                        + "heartbeat_milliseconds => @heartbeat)")
                .bind("start_ts").to(now)
                .bind("end_ts").to(end)
                .bind("heartbeat").to(HEARTBEAT_MS)
                .build();

        List<PartitionPosition> partitions = new ArrayList<>();
        long mintedAt = System.currentTimeMillis();
        try (ResultSet rs = databaseClient.singleUse().executeQuery(stmt)) {
            while (rs.next()) {
                Struct row = rs.getCurrentRowAsStruct();
                for (LogicalRecord lr : decomposeRow(row)) {
                    if (lr.kind != RecordKind.CHILD_PARTITIONS) continue;
                    for (String token : extractChildPartitionTokens(lr.record)) {
                        partitions.add(new PartitionPosition(token,
                                continuation(now, 0L)));
                    }
                }
            }
        } catch (SpannerException e) {
            throw mapSpannerException(e, "listCursors");
        }

        if (partitions.isEmpty()) {
            // Edge case: TVF returned no child partitions. Mint a placeholder that
            // re-bootstraps on the next read.
            partitions.add(new PartitionPosition("__bootstrap__", continuation(now, 0L)));
        }

        List<ChangeFeedCursor> cursors = new ArrayList<>(partitions.size());
        for (PartitionPosition pos : partitions) {
            CursorToken tok = new CursorToken(
                    providerId, address, mintedAt, CursorAnchor.NOW, List.of(pos));
            cursors.add(new ChangeFeedCursor(tok));
        }
        return cursors;
    }

    /**
     * Drain one bounded TVF window across the cursor's first partition.
     */
    ChangeFeedPage readChanges(ResourceAddress address, ChangeFeedCursor cursor,
                               OperationOptions options) {
        CursorToken token;
        if (cursor.isUnhydratedSentinel()) {
            // Hydrate by re-discovering partitions.
            List<ChangeFeedCursor> all = listCursors(address);
            List<PartitionPosition> merged = new ArrayList<>();
            for (ChangeFeedCursor c : all) merged.addAll(c.token().partitions());
            token = new CursorToken(providerId, address,
                    System.currentTimeMillis(), CursorAnchor.NOW, merged);
        } else {
            token = cursor.token();
        }

        if (token.partitions().isEmpty()) {
            CursorToken refreshed = token.withIssuedAt(System.currentTimeMillis());
            return new ChangeFeedPage(List.of(), new ChangeFeedCursor(refreshed), false, false);
        }

        List<PartitionPosition> partitions = new ArrayList<>(token.partitions());
        PartitionPosition pos = partitions.get(0);
        String partitionToken = pos.partitionId();

        // Re-bootstrap if this is the placeholder.
        if ("__bootstrap__".equals(partitionToken)) {
            List<ChangeFeedCursor> all = listCursors(address);
            List<PartitionPosition> newPositions = new ArrayList<>();
            for (ChangeFeedCursor c : all) newPositions.addAll(c.token().partitions());
            if (newPositions.isEmpty()) {
                CursorToken refreshed = token.withIssuedAt(System.currentTimeMillis());
                return new ChangeFeedPage(List.of(), new ChangeFeedCursor(refreshed), false, false);
            }
            CursorToken next = token.withPartitions(newPositions, System.currentTimeMillis());
            return new ChangeFeedPage(List.of(), new ChangeFeedCursor(next), true, false);
        }

        String streamName = streamNameFor(address.collection());
        ContState state;
        try {
            state = parseContinuation(pos.continuation());
        } catch (MalformedContinuation mc) {
            throw new CursorExpiredException(new MulticloudDbError(
                    MulticloudDbErrorCategory.CURSOR_EXPIRED,
                    mc.getMessage(),
                    providerId, "readChanges", false,
                    Map.of("reason", "MALFORMED")), mc);
        }
        Timestamp end = addMillis(state.startTs, WINDOW_MS);

        Statement stmt = Statement.newBuilder(
                "SELECT * FROM READ_" + sanitize(streamName) + "("
                        + "start_timestamp => @start_ts, "
                        + "end_timestamp => @end_ts, "
                        + "partition_token => @partition_token, "
                        + "heartbeat_milliseconds => @heartbeat)")
                .bind("start_ts").to(state.startTs)
                .bind("end_ts").to(end)
                .bind("partition_token").to(partitionToken)
                .bind("heartbeat").to(HEARTBEAT_MS)
                .build();

        List<ChangeEvent> events = new ArrayList<>();
        Timestamp lastCommitTs = state.startTs;
        long lastRecordSeq = state.recordSeq;
        boolean partitionClosed = false;
        List<PartitionPosition> newChildren = new ArrayList<>();

        try (ResultSet rs = databaseClient.singleUse().executeQuery(stmt)) {
            while (rs.next()) {
                Struct row = rs.getCurrentRowAsStruct();
                for (LogicalRecord lr : decomposeRow(row)) {
                    switch (lr.kind) {
                        case DATA:
                            DataChangeBatch batch = readDataChange(lr.record, address);
                            events.addAll(batch.events);
                            if (batch.commitTs != null) lastCommitTs = batch.commitTs;
                            lastRecordSeq = batch.recordSeq;
                            break;
                        case CHILD_PARTITIONS:
                            partitionClosed = true;
                            for (String child : extractChildPartitionTokens(lr.record)) {
                                newChildren.add(new PartitionPosition(child,
                                        continuation(lastCommitTs, 0L)));
                            }
                            break;
                        case HEARTBEAT:
                            Timestamp hb = extractHeartbeatTimestamp(lr.record);
                            if (hb != null) lastCommitTs = hb;
                            break;
                        case UNKNOWN:
                        default:
                            // Silently skip; logged at debug.
                            LOG.debug("Skipping unrecognised change-stream row");
                    }
                }
            }
        } catch (SpannerException e) {
            if (e.getErrorCode() == ErrorCode.INVALID_ARGUMENT
                    || e.getErrorCode() == ErrorCode.NOT_FOUND
                    || e.getErrorCode() == ErrorCode.OUT_OF_RANGE) {
                throw new CursorExpiredException(new MulticloudDbError(
                        MulticloudDbErrorCategory.CURSOR_EXPIRED,
                        "Spanner change stream rejected the cursor (likely partition token expired or outside retention): "
                                + e.getMessage(),
                        providerId, "readChanges", false,
                        Map.of("reason", "PROVIDER_TRIMMED",
                                "errorCode", e.getErrorCode().name())), e);
            }
            throw mapSpannerException(e, "readChanges");
        }

        // Update partition state.
        if (partitionClosed) {
            // Replace this partition with its children.
            partitions.remove(0);
            partitions.addAll(0, newChildren);
        } else {
            // Advance the continuation to the end of the window so the next call
            // picks up where we left off.
            partitions.set(0, new PartitionPosition(partitionToken, continuation(end, lastRecordSeq)));
        }

        boolean hasMore = !events.isEmpty() || partitionClosed;
        CursorToken next = token.withPartitions(partitions, System.currentTimeMillis());
        return new ChangeFeedPage(events, new ChangeFeedCursor(next), hasMore, false);
    }

    // ── Record dispatch ────────────────────────────────────────────────────

    private enum RecordKind { DATA, HEARTBEAT, CHILD_PARTITIONS, UNKNOWN }

    /** One logical change record (data/heartbeat/child-partitions) carried by a TVF row. */
    private static final class LogicalRecord {
        final RecordKind kind;
        final Struct record;
        LogicalRecord(RecordKind kind, Struct record) {
            this.kind = kind;
            this.record = record;
        }
    }

    /**
     * Decompose a Spanner change-stream TVF row into the logical records it carries.
     * <p>
     * In the current schema each row has a single column {@code ChangeRecord} of
     * type {@code ARRAY<STRUCT<data_change_record ARRAY<...>, heartbeat_record
     * ARRAY<...>, child_partitions_record ARRAY<...>>>}. Typically the outer
     * array has one element, and exactly one of the three sub-arrays is
     * non-empty.
     */
    private List<LogicalRecord> decomposeRow(Struct row) {
        List<LogicalRecord> out = new ArrayList<>();
        String chgCol = canonicalField(row, "ChangeRecord");
        if (chgCol != null && !row.isNull(chgCol)) {
            for (Struct outer : row.getStructList(chgCol)) {
                for (Struct rec : getStructListOrEmpty(outer, "data_change_record")) {
                    out.add(new LogicalRecord(RecordKind.DATA, rec));
                }
                for (Struct rec : getStructListOrEmpty(outer, "heartbeat_record")) {
                    out.add(new LogicalRecord(RecordKind.HEARTBEAT, rec));
                }
                for (Struct rec : getStructListOrEmpty(outer, "child_partitions_record")) {
                    out.add(new LogicalRecord(RecordKind.CHILD_PARTITIONS, rec));
                }
            }
        }
        return out;
    }

    private static boolean hasNonNullField(Struct s, String field) {
        String canonical = canonicalField(s, field);
        return canonical != null && !s.isNull(canonical);
    }

    // ── Data change extraction ─────────────────────────────────────────────

    private static final class DataChangeBatch {
        final List<ChangeEvent> events;
        final Timestamp commitTs;
        final long recordSeq;
        DataChangeBatch(List<ChangeEvent> events, Timestamp commitTs, long recordSeq) {
            this.events = events;
            this.commitTs = commitTs;
            this.recordSeq = recordSeq;
        }
    }

    private DataChangeBatch readDataChange(Struct rec, ResourceAddress address) {
        // address is reserved for future use (table_name fallback). `rec` is the
        // inner `data_change_record` struct produced by decomposeRow(...) — all
        // field accesses below go through canonicalField(...) helpers so we
        // tolerate Spanner returning TVF column names with different casing
        // across client versions.
        Timestamp commitTs = getTimestampOrNull(rec, "commit_timestamp");
        long recordSeq = parseSequence(rec, "record_sequence");
        String txnIdRaw = getStringOrNull(rec, "server_transaction_id");
        String txnId = txnIdRaw != null ? txnIdRaw : "";
        String modTypeRaw = getStringOrNull(rec, "mod_type");
        String modType = modTypeRaw != null ? modTypeRaw : "UPDATE";
        ChangeType type = mapModType(modType);

        List<Struct> mods = getStructListOrEmpty(rec, "mods");
        List<ChangeEvent> out = new ArrayList<>(mods.size());
        int idx = 0;
        for (Struct mod : mods) {
            String eventId = txnId + ":" + (commitTs != null ? commitTs.toString() : "") + ":"
                    + recordSeq + ":" + idx;
            MulticloudDbKey key = extractKey(mod);
            JsonNode data = extractValues(mod, type);
            Instant eventInstant = commitTs != null
                    ? Instant.ofEpochSecond(commitTs.getSeconds(), commitTs.getNanos())
                    : Instant.EPOCH;
            out.add(new ChangeEvent(key, type, eventInstant, data, eventId));
            idx++;
        }
        return new DataChangeBatch(out, commitTs != null ? commitTs : Timestamp.now(), recordSeq);
    }

    private MulticloudDbKey extractKey(Struct mod) {
        // mods.keys is a JSON STRING ({ "partitionKey": "...", "sortKey": "..." }).
        String keysJson = getStringOrNull(mod, "keys");
        if (keysJson == null) return MulticloudDbKey.of("");
        try {
            JsonNode node = MAPPER.readTree(keysJson);
            String pk = node.has("partitionKey") ? node.get("partitionKey").asText() : "";
            JsonNode skNode = node.get("sortKey");
            if (skNode != null && !skNode.isNull()) return MulticloudDbKey.of(pk, skNode.asText());
            return MulticloudDbKey.of(pk);
        } catch (Exception e) {
            return MulticloudDbKey.of(keysJson);
        }
    }

    private JsonNode extractValues(Struct mod, ChangeType type) {
        // For CREATE/UPDATE prefer new_values; for DELETE fall back to old_values.
        String field;
        if (type == ChangeType.DELETE && hasNonNullField(mod, "old_values")) {
            field = "old_values";
        } else if (hasNonNullField(mod, "new_values")) {
            field = "new_values";
        } else if (hasNonNullField(mod, "old_values")) {
            field = "old_values";
        } else {
            return MAPPER.createObjectNode();
        }
        String json = getStringOrNull(mod, field);
        if (json == null) return MAPPER.createObjectNode();
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            ObjectNode wrap = MAPPER.createObjectNode();
            wrap.put("raw", json);
            return wrap;
        }
    }

    private ChangeType mapModType(String modType) {
        if (modType == null) return ChangeType.UPDATE;
        switch (modType.toUpperCase()) {
            case "INSERT": return ChangeType.CREATE;
            case "DELETE": return ChangeType.DELETE;
            case "UPDATE":
            default:        return ChangeType.UPDATE;
        }
    }

    // ── Child partitions extraction ────────────────────────────────────────

    /** Extract child partition tokens from an inner {@code child_partitions_record} struct. */
    private List<String> extractChildPartitionTokens(Struct rec) {
        List<String> tokens = new ArrayList<>();
        for (Struct c : getStructListOrEmpty(rec, "child_partitions")) {
            String tk = getStringOrNull(c, "token");
            if (tk != null) tokens.add(tk);
        }
        return tokens;
    }

    /** Extract the heartbeat timestamp from an inner {@code heartbeat_record} struct. */
    private Timestamp extractHeartbeatTimestamp(Struct rec) {
        return getTimestampOrNull(rec, "timestamp");
    }

    // ── Field name canonicalization ─────────────────────────────────────────

    /**
     * Resolve the actual (case-sensitive) field name Spanner returned for a
     * case-insensitive lookup key. Returns {@code null} if no field with that
     * name (under any casing) is present in the struct's type.
     */
    private static String canonicalField(Struct s, String field) {
        try {
            return s.getType().getStructFields().stream()
                    .map(f -> f.getName())
                    .filter(n -> n.equalsIgnoreCase(field))
                    .findFirst()
                    .orElse(null);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    // ── Continuation encoding ──────────────────────────────────────────────

    private static final class ContState {
        final Timestamp startTs;
        final long recordSeq;
        ContState(Timestamp startTs, long recordSeq) {
            this.startTs = startTs;
            this.recordSeq = recordSeq;
        }
    }

    private static String continuation(Timestamp ts, long recordSeq) {
        return ts.toString() + "|" + recordSeq;
    }

    /**
     * Parse a Spanner change-feed continuation. Returns a {@link ContState} starting
     * from "now" only when the continuation is null/blank (a freshly bootstrapped
     * cursor with no recorded position). Any non-empty continuation that fails to
     * parse is surfaced as a {@link MalformedContinuation} — silently falling back
     * to {@link Timestamp#now()} would skip history and lie about the cursor's
     * actual position.
     */
    private static ContState parseContinuation(String c) {
        if (c == null || c.isBlank()) return new ContState(Timestamp.now(), 0L);
        int bar = c.lastIndexOf('|');
        try {
            if (bar < 0) return new ContState(Timestamp.parseTimestamp(c), 0L);
            Timestamp ts = Timestamp.parseTimestamp(c.substring(0, bar));
            long seq = Long.parseLong(c.substring(bar + 1));
            return new ContState(ts, seq);
        } catch (RuntimeException e) {
            throw new MalformedContinuation(c, e);
        }
    }

    /** Marker for a continuation string we could not parse. Caught by readChanges. */
    private static final class MalformedContinuation extends RuntimeException {
        final String continuation;
        MalformedContinuation(String continuation, Throwable cause) {
            super("malformed Spanner change-feed continuation: " + continuation, cause);
            this.continuation = continuation;
        }
    }

    private static long parseSequence(Struct rec, String field) {
        String cf = canonicalField(rec, field);
        if (cf == null || rec.isNull(cf)) return 0L;
        try {
            // record_sequence is typically STRING in Spanner change streams.
            return Long.parseLong(rec.getString(cf));
        } catch (Exception e) {
            try {
                return rec.getLong(cf);
            } catch (Exception e2) {
                return 0L;
            }
        }
    }

    /** Safe string accessor that honours canonical (case-insensitive) field lookup. */
    private static String getStringOrNull(Struct s, String field) {
        String cf = canonicalField(s, field);
        return (cf != null && !s.isNull(cf)) ? s.getString(cf) : null;
    }

    /** Safe timestamp accessor that honours canonical (case-insensitive) field lookup. */
    private static Timestamp getTimestampOrNull(Struct s, String field) {
        String cf = canonicalField(s, field);
        return (cf != null && !s.isNull(cf)) ? s.getTimestamp(cf) : null;
    }

    /** Safe struct-list accessor that honours canonical (case-insensitive) field lookup. */
    private static List<Struct> getStructListOrEmpty(Struct s, String field) {
        String cf = canonicalField(s, field);
        return (cf != null && !s.isNull(cf)) ? s.getStructList(cf) : List.of();
    }

    private static Timestamp addMillis(Timestamp t, long millis) {
        long total = t.getSeconds() * 1000L + t.getNanos() / 1_000_000L + millis;
        long secs = total / 1000L;
        int nanos = (int) ((total % 1000L) * 1_000_000L);
        return Timestamp.ofTimeSecondsAndNanos(secs, nanos);
    }

    private static String sanitize(String streamName) {
        // Defensive: change-stream names follow Spanner identifier rules.
        // We refuse anything outside [A-Za-z0-9_].
        for (int i = 0; i < streamName.length(); i++) {
            char ch = streamName.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '_';
            if (!ok) {
                throw new IllegalArgumentException(
                        "Invalid Spanner change-stream name (must match [A-Za-z0-9_]+): " + streamName);
            }
        }
        return streamName;
    }

    private MulticloudDbException mapSpannerException(SpannerException e, String operation) {
        Map<String, String> details = new HashMap<>();
        details.put("errorCode", e.getErrorCode().name());
        boolean retryable = e.isRetryable()
                || e.getErrorCode() == ErrorCode.UNAVAILABLE
                || e.getErrorCode() == ErrorCode.DEADLINE_EXCEEDED;
        return new MulticloudDbException(new MulticloudDbError(
                MulticloudDbErrorCategory.PROVIDER_ERROR, e.getMessage(),
                providerId, operation, retryable, details), e);
    }
}

// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.Timestamp;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.ReadContext;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.SpannerException;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.Struct;
import com.google.cloud.spanner.Value;
import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.OperationDiagnostics;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cloud Spanner change-feed adapter — implements {@code readChanges} and
 * {@code listPhysicalPartitions} on top of Spanner Change Streams TVFs.
 *
 * <p>Provisioning prerequisite: the user MUST have created a change stream
 * via DDL out-of-band, e.g.
 * <pre>{@code
 *   CREATE CHANGE STREAM my_stream FOR my_table OPTIONS (
 *     value_capture_type = 'NEW_ROW'
 *   );
 * }</pre>
 *
 * <p>Stream-name resolution: the stream name is taken from the
 * {@code changeStream.<collection>} key in
 * {@link com.multiclouddb.api.MulticloudDbClientConfig#connection()};
 * if absent it defaults to {@code <collection>_changes}.
 *
 * <p>Caveats:
 * <ul>
 *   <li>{@link FeedScope.LogicalPartition} is rejected with
 *       {@link MulticloudDbErrorCategory#UNSUPPORTED_CAPABILITY} —
 *       Spanner exposes physical partition tokens only.</li>
 *   <li>{@link FeedScope.EntireCollection} is implemented as a
 *       partition-queue iterator: a fresh request bootstraps from the root
 *       (NULL token) and the continuation carries the work queue forward.
 *       Per-page ordering is per-partition only.</li>
 *   <li>{@link NewItemStateMode#REQUIRE} requires that the stream was
 *       created with {@code value_capture_type='NEW_ROW'} or
 *       {@code 'NEW_VALUES'}; otherwise events surface with {@code data=null}
 *       and we raise {@link MulticloudDbErrorCategory#UNSUPPORTED_CAPABILITY}
 *       on the first DELETE-or-non-NEW record we observe.</li>
 * </ul>
 */
final class SpannerChangeFeed {

    private static final Logger LOG = LoggerFactory.getLogger(SpannerChangeFeed.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Connection-config prefix: {@code changeStream.<collection> = <stream-name>}. */
    static final String CONFIG_STREAM_PREFIX = "changeStream.";

    /** TVF heartbeat interval in milliseconds (server-supplied keep-alive cadence). */
    private static final long DEFAULT_HEARTBEAT_MS = 1_000L;

    /**
     * Soft cap on partitions processed per readChanges call when scope is
     * EntireCollection — avoids unbounded latency on a large partition tree.
     * Remaining tokens are carried in the continuation token.
     */
    private static final int MAX_PARTITIONS_PER_PAGE = 4;

    private final DatabaseClient databaseClient;
    private final Map<String, String> connection;

    SpannerChangeFeed(DatabaseClient databaseClient, Map<String, String> connection) {
        this.databaseClient = databaseClient;
        this.connection = connection != null ? connection : Collections.emptyMap();
    }

    // ── Public API ─────────────────────────────────────────────────────────

    List<String> listPhysicalPartitions(ResourceAddress address) {
        try {
            String stream = resolveStreamName(address);
            // Probe the root partition (NULL token) over a tiny window: returns the
            // current set of root-level child_partitions tokens.
            Instant now = Instant.now();
            List<String> tokens = new ArrayList<>();
            queryTvf(stream, now.minusSeconds(1), now, /*partitionToken*/ null,
                    new TvfVisitor() {
                        @Override public void onChildPartitions(Timestamp ts, List<String> children) {
                            tokens.addAll(children);
                        }
                    });
            return tokens;
        } catch (SpannerException e) {
            throw mapSpannerException(e, "listPhysicalPartitions");
        }
    }

    ChangeFeedPage readChanges(ChangeFeedRequest request, OperationOptions options) {
        Instant start = Instant.now();
        try {
            if (request.scope() instanceof FeedScope.LogicalPartition) {
                throw unsupported("FeedScope.logicalPartition is not supported by Spanner "
                        + "Change Streams; use FeedScope.physicalPartition with a token from "
                        + "listPhysicalPartitions()");
            }

            String stream = resolveStreamName(request.address());
            PartitionQueue queue = resolveQueue(request, stream);
            List<ChangeEvent> events = new ArrayList<>();
            boolean partitionRetired = false;
            List<String> retiredChildren = List.of();

            int processed = 0;
            Instant readEnd = queue.readEnd != null ? queue.readEnd : Instant.now().plusSeconds(1);

            while (!queue.tokens.isEmpty() && processed < MAX_PARTITIONS_PER_PAGE) {
                PartitionCursor cursor = queue.tokens.pollFirst();
                Instant readStart = cursor.watermark != null ? cursor.watermark : queue.fallbackStart;

                Drain drain = new Drain(request);
                try {
                    queryTvf(stream, readStart, readEnd, cursor.token, drain);
                } catch (SpannerException e) {
                    throw mapSpannerException(e, "readChanges");
                }
                events.addAll(drain.events);

                if (drain.children.isEmpty()) {
                    // Partition is still active — advance its watermark
                    Instant advanced = drain.maxCommitTs != null ? drain.maxCommitTs : readEnd;
                    queue.tokens.addLast(new PartitionCursor(cursor.token, advanced));
                } else {
                    // Partition retired (split/merge). Surface for PhysicalPartition scope;
                    // for EntireCollection, fan out into children.
                    if (request.scope() instanceof FeedScope.PhysicalPartition) {
                        partitionRetired = true;
                        retiredChildren = drain.children;
                        // Don't keep this token in the queue.
                    } else {
                        for (String child : drain.children) {
                            queue.tokens.addLast(new PartitionCursor(child, drain.maxCommitTs));
                        }
                    }
                }
                processed++;
            }

            String token = encodeToken(request.address(), stream, queue);
            OperationDiagnostics diag = OperationDiagnostics
                    .builder(ProviderId.SPANNER, "readChanges",
                            Duration.between(start, Instant.now()))
                    .itemCount(events.size())
                    .build();
            return new ChangeFeedPage(events, token, partitionRetired, retiredChildren, diag);
        } catch (MulticloudDbException e) {
            throw e;
        } catch (SpannerException e) {
            throw mapSpannerException(e, "readChanges");
        }
    }

    // ── TVF query plumbing ─────────────────────────────────────────────────

    /**
     * Runs the change-stream TVF and dispatches each ChangeRecord row into the
     * supplied visitor. Exposed package-private to make unit testing tractable
     * (we mock {@link DatabaseClient} to inject result-set sequences).
     */
    void queryTvf(String stream, Instant startTs, Instant endTs, String partitionToken,
                  TvfVisitor visitor) {
        // Stream name is unparameterizable in TVF call syntax — validate before splicing.
        validateIdentifier(stream);

        String sql = "SELECT ChangeRecord FROM READ_" + stream
                + "(@start_timestamp, @end_timestamp, @partition_token, @heartbeat_milliseconds)";
        Statement stmt = Statement.newBuilder(sql)
                .bind("start_timestamp").to(toSpannerTs(startTs))
                .bind("end_timestamp").to(toSpannerTs(endTs))
                .bind("partition_token").to(partitionToken)
                .bind("heartbeat_milliseconds").to(DEFAULT_HEARTBEAT_MS)
                .build();

        try (ReadContext ctx = databaseClient.singleUse();
             ResultSet rs = ctx.executeQuery(stmt)) {
            while (rs.next()) {
                List<Struct> records = rs.getStructList("ChangeRecord");
                for (Struct rec : records) {
                    dispatch(rec, visitor);
                }
            }
        }
    }

    private static void dispatch(Struct rec, TvfVisitor visitor) {
        if (!rec.isNull("data_change_record")) {
            for (Struct dcr : rec.getStructList("data_change_record")) {
                visitor.onDataChange(dcr);
            }
        }
        if (!rec.isNull("child_partitions_record")) {
            for (Struct cpr : rec.getStructList("child_partitions_record")) {
                Timestamp ts = cpr.getTimestamp("start_timestamp");
                List<String> children = new ArrayList<>();
                for (Struct child : cpr.getStructList("child_partitions")) {
                    children.add(child.getString("token"));
                }
                visitor.onChildPartitions(ts, children);
            }
        }
        // heartbeat_record is informational; no-op.
    }

    /** Visitor pattern keeps wire-format parsing in one place. */
    interface TvfVisitor {
        default void onDataChange(Struct dataChangeRecord) { }
        default void onChildPartitions(Timestamp startTs, List<String> children) { }
    }

    /** Visitor that materializes events for a single readChanges page. */
    private final class Drain implements TvfVisitor {
        final List<ChangeEvent> events = new ArrayList<>();
        final List<String> children = new ArrayList<>();
        Instant maxCommitTs;
        private final ChangeFeedRequest request;

        Drain(ChangeFeedRequest request) { this.request = request; }

        @Override
        public void onDataChange(Struct dcr) {
            ChangeType type = parseModType(dcr.getString("mod_type"));
            if (type == null) {
                LOG.debug("Skipping Spanner record with unknown mod_type '{}'",
                        dcr.getString("mod_type"));
                return;
            }
            Timestamp commit = dcr.getTimestamp("commit_timestamp");
            String seq = dcr.getString("record_sequence");
            String txn = !dcr.isNull("server_transaction_id")
                    ? dcr.getString("server_transaction_id") : "";
            String eventId = txn + ":" + (commit != null ? commit : "0") + ":" + seq;

            for (Struct mod : dcr.getStructList("mods")) {
                ChangeEvent ev = mapMod(type, mod, eventId, commit, request);
                if (ev != null) {
                    events.add(ev);
                }
            }

            Instant ts = commit != null ? toInstant(commit) : null;
            if (ts != null && (maxCommitTs == null || ts.isAfter(maxCommitTs))) {
                maxCommitTs = ts;
            }
        }

        @Override
        public void onChildPartitions(Timestamp startTs, List<String> kids) {
            children.addAll(kids);
            Instant ts = startTs != null ? toInstant(startTs) : null;
            if (ts != null && (maxCommitTs == null || ts.isAfter(maxCommitTs))) {
                maxCommitTs = ts;
            }
        }
    }

    private ChangeEvent mapMod(ChangeType type, Struct mod, String baseEventId,
                               Timestamp commit, ChangeFeedRequest request) {
        // keys is JSON: { "<pk-col>": value, ["<sk-col>": value] }
        com.fasterxml.jackson.databind.JsonNode keys = parseJson(mod, "keys");
        if (keys == null || !keys.isObject()) {
            return null;
        }
        String pk = textOrNull(keys, SpannerConstants.FIELD_PARTITION_KEY);
        String sk = textOrNull(keys, SpannerConstants.FIELD_SORT_KEY);
        if (pk == null) {
            // Streams may emit any column as a key (composite PKs); v1 only knows about
            // our convention (partitionKey + optional sortKey). Skip records that don't
            // match — surfaced once at debug for diagnosability.
            LOG.debug("Skipping Spanner mod without partitionKey field: keys={}", keys);
            return null;
        }
        MulticloudDbKey key = (sk != null && !sk.equals(pk))
                ? MulticloudDbKey.of(pk, sk) : MulticloudDbKey.of(pk);

        ObjectNode data = null;
        if (request.newItemStateMode() != NewItemStateMode.OMIT && type != ChangeType.DELETE) {
            JsonNode newValues = parseJson(mod, "new_values");
            if (newValues instanceof ObjectNode obj && obj.size() > 0) {
                data = obj;
            }
        }
        if (request.newItemStateMode() == NewItemStateMode.REQUIRE
                && type != ChangeType.DELETE && data == null) {
            throw unsupported("newItemStateMode=REQUIRE but the change stream did not include "
                    + "new_values; recreate the stream with value_capture_type='NEW_ROW' "
                    + "or 'NEW_VALUES'");
        }

        Instant ts = commit != null ? toInstant(commit) : null;
        return new ChangeEvent(ProviderId.SPANNER, baseEventId, type,
                request.address(), key, data, ts);
    }

    // ── Continuation-token plumbing ────────────────────────────────────────

    private PartitionQueue resolveQueue(ChangeFeedRequest req, String stream) {
        Instant fallbackStart = computeStart(req.startPosition());
        if (req.startPosition() instanceof StartPosition.FromContinuationToken tok) {
            JsonNode inner = ContinuationTokenCodec.decode(tok.token(),
                    ProviderId.SPANNER, req.address());
            if (!inner.isObject()) {
                throw new MulticloudDbException(new MulticloudDbError(
                        MulticloudDbErrorCategory.INVALID_REQUEST,
                        "Malformed Spanner continuation token (object expected)",
                        ProviderId.SPANNER, "readChanges", false, Map.of()));
            }
            String tokStream = inner.path("stream").asText("");
            if (!stream.equals(tokStream)) {
                throw new MulticloudDbException(new MulticloudDbError(
                        MulticloudDbErrorCategory.INVALID_REQUEST,
                        "Continuation token issued for stream '" + tokStream
                                + "' but the request resolves to '" + stream + "'",
                        ProviderId.SPANNER, "readChanges", false, Map.of()));
            }
            Deque<PartitionCursor> cursors = new ArrayDeque<>();
            for (JsonNode n : inner.path("partitions")) {
                String token = n.path("token").asText(null);
                String wm = n.path("watermark").asText(null);
                cursors.addLast(new PartitionCursor(token,
                        wm != null ? Instant.parse(wm) : null));
            }
            return new PartitionQueue(cursors, fallbackStart, /*readEnd*/ null);
        }

        // First call — bootstrap based on scope.
        Deque<PartitionCursor> initial = new ArrayDeque<>();
        if (req.scope() instanceof FeedScope.PhysicalPartition pp) {
            initial.addLast(new PartitionCursor(pp.partitionId(), fallbackStart));
        } else {
            // EntireCollection: bootstrap from root partition (NULL token).
            initial.addLast(new PartitionCursor(null, fallbackStart));
        }
        return new PartitionQueue(initial, fallbackStart, /*readEnd*/ null);
    }

    private String encodeToken(ResourceAddress address, String stream, PartitionQueue queue) {
        if (queue.tokens.isEmpty()) {
            return null;
        }
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("stream", stream);
        ArrayNode arr = envelope.putArray("partitions");
        for (PartitionCursor c : queue.tokens) {
            ObjectNode n = MAPPER.createObjectNode();
            if (c.token != null) {
                n.put("token", c.token);
            } else {
                n.putNull("token");
            }
            if (c.watermark != null) {
                n.put("watermark", c.watermark.toString());
            }
            arr.add(n);
        }
        return ContinuationTokenCodec.encode(ProviderId.SPANNER, address, envelope);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static Instant computeStart(StartPosition sp) {
        if (sp instanceof StartPosition.AtTime at) {
            return at.timestamp();
        }
        if (sp instanceof StartPosition.Now) {
            return Instant.now();
        }
        // Beginning — Spanner default retention is 1 day; clamp conservatively.
        return Instant.now().minusSeconds(60 * 60); // 1 hour back; safe default
    }

    private String resolveStreamName(ResourceAddress address) {
        String configured = connection.get(CONFIG_STREAM_PREFIX + address.collection());
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return address.collection() + "_changes";
    }

    private static void validateIdentifier(String name) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.INVALID_REQUEST,
                    "Invalid Spanner change-stream identifier: '" + name + "'. "
                            + "Names must match [A-Za-z_][A-Za-z0-9_]*.",
                    ProviderId.SPANNER, "readChanges", false, Map.of()));
        }
    }

    private static ChangeType parseModType(String s) {
        if (s == null) return null;
        return switch (s) {
            case "INSERT" -> ChangeType.CREATE;
            case "UPDATE" -> ChangeType.UPDATE;
            case "DELETE" -> ChangeType.DELETE;
            default -> null;
        };
    }

    private static JsonNode parseJson(Struct mod, String field) {
        if (mod.isNull(field)) {
            return null;
        }
        try {
            // The TVF returns these as JSON-typed columns; getJson() yields the JSON text.
            String json = mod.getJson(field);
            return MAPPER.readTree(json);
        } catch (Exception e) {
            LOG.debug("Failed to parse Spanner mod field '{}': {}", field, e.getMessage());
            return null;
        }
    }

    private static String textOrNull(JsonNode obj, String field) {
        JsonNode n = obj.get(field);
        if (n == null || n.isNull()) return null;
        return n.asText();
    }

    private static com.google.cloud.Timestamp toSpannerTs(Instant i) {
        return com.google.cloud.Timestamp.ofTimeSecondsAndNanos(i.getEpochSecond(), i.getNano());
    }

    private static Instant toInstant(com.google.cloud.Timestamp t) {
        return Instant.ofEpochSecond(t.getSeconds(), t.getNanos());
    }

    private static MulticloudDbException unsupported(String msg) {
        return new MulticloudDbException(new MulticloudDbError(
                MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY,
                msg, ProviderId.SPANNER, "readChanges", false, Map.of()));
    }

    private static MulticloudDbException mapSpannerException(SpannerException e, String op) {
        // Spanner change-stream retention exhaustion surfaces as INVALID_ARGUMENT or
        // FAILED_PRECONDITION depending on cause; map both to CHECKPOINT_EXPIRED when the
        // message indicates retention.
        String msg = e.getMessage() != null ? e.getMessage() : "";
        boolean retention = msg.contains("retention") || msg.contains("garbage_collected")
                || msg.contains("partition is no longer available");
        MulticloudDbErrorCategory cat = retention
                ? MulticloudDbErrorCategory.CHECKPOINT_EXPIRED
                : MulticloudDbErrorCategory.PROVIDER_ERROR;
        return new MulticloudDbException(new MulticloudDbError(
                cat,
                "Spanner change-stream error: " + msg,
                ProviderId.SPANNER, op, false,
                Map.of("errorCode", String.valueOf(e.getErrorCode()))), e);
    }

    // ── Internal value types ───────────────────────────────────────────────

    /** A single partition cursor: token + last-seen watermark. */
    private record PartitionCursor(String token, Instant watermark) { }

    /** Outer queue wrapping the cursors plus per-page read window. */
    private static final class PartitionQueue {
        final Deque<PartitionCursor> tokens;
        final Instant fallbackStart;
        final Instant readEnd;

        PartitionQueue(Deque<PartitionCursor> tokens, Instant fallbackStart, Instant readEnd) {
            this.tokens = tokens;
            this.fallbackStart = fallbackStart;
            this.readEnd = readEnd;
        }
    }
}

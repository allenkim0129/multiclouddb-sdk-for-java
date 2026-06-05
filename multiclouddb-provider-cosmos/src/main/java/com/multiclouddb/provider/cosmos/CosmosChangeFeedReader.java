// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.CosmosChangeFeedRequestOptions;
import com.azure.cosmos.models.FeedRange;
import com.azure.cosmos.models.FeedResponse;
import com.azure.cosmos.util.CosmosPagedIterable;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Cosmos DB pull-mode change-feed reader.
 * <p>
 * Maps the {@link com.multiclouddb.api.MulticloudDbClient} change-feed primitives
 * onto Cosmos DB's pull-model change-feed API:
 * <ul>
 *   <li>{@link CosmosContainer#getFeedRanges()} → one cursor per feed range from
 *       {@link #listCursors}.</li>
 *   <li>{@link CosmosContainer#queryChangeFeed(CosmosChangeFeedRequestOptions, Class)}
 *       drives every {@link #readChanges} call.</li>
 *   <li>Continuation tokens flow back into the next cursor; if a request returns
 *       {@code 410 GONE} the cursor is treated as expired.</li>
 * </ul>
 *
 * <h3>CREATE / UPDATE / DELETE distinction</h3>
 * The Cosmos pull-mode change feed in its default <em>LatestVersion</em> mode does
 * not surface deletes or distinguish creates from updates — every event is the
 * latest version of a document. In this mode the reader surfaces all events as
 * {@link ChangeType#UPDATE}; DELETE events are silently absent.
 * <p>
 * To get faithful CREATE / UPDATE / DELETE semantics, the container must be
 * provisioned for All-Versions-and-Deletes (AVAD) change-feed mode and the SDK
 * caller must opt in via the {@code changeFeed.mode=allVersionsAndDeletes}
 * client connection key. This reader honours that flag if present.
 */
final class CosmosChangeFeedReader {

    private static final Logger LOG = LoggerFactory.getLogger(CosmosChangeFeedReader.class);
    private static final String CONFIG_MODE = "changeFeed.mode";
    private static final String MODE_AVAD = "allVersionsAndDeletes";
    private static final int DEFAULT_PAGE_SIZE = 100;

    /**
     * Sentinel continuation used in a hydrated-but-unread cursor for a freshly
     * minted partition. The Cosmos pull-API does not expose "live tip" as a
     * continuation token directly; we encode it as a synthetic marker and replace
     * it with {@link CosmosChangeFeedRequestOptions#createForProcessingFromNow(FeedRange)}
     * on the first read.
     */
    private static final String CONT_FROM_NOW = "@@FROM_NOW";
    /**
     * Continuation prefix anchoring a cursor to a specific point in time, captured at
     * {@code listCursors()} or {@code now()}-hydrate time. The suffix is an epoch-millis
     * timestamp. Using a point-in-time anchor (rather than the {@code CONT_FROM_NOW}
     * sentinel which re-anchors to the moment of the read) prevents silent event loss
     * for writes that happen between cursor mint and first read.
     */
    private static final String CONT_PIT_PREFIX = "@@PIT:";

    private final ProviderId providerId;
    private final String avadMode;

    CosmosChangeFeedReader(ProviderId providerId, Map<String, String> connection) {
        this.providerId = providerId;
        this.avadMode = connection.getOrDefault(CONFIG_MODE, "");
    }

    /**
     * Enumerate the container's feed ranges and mint one {@link ChangeFeedCursor}
     * per range, each positioned at the live tip.
     * <p>
     * For each range we eagerly execute a {@link CosmosChangeFeedRequestOptions#createForProcessingFromNow(FeedRange)
     * createForProcessingFromNow} query and persist the returned continuation
     * token. This "warmup" round-trip captures a real bookmark at mint time so
     * that any subsequent {@code readChanges()} resumes from that exact point —
     * not from a re-anchored {@code FROM_NOW} at read-time, which would silently
     * skip any events written between cursor mint and first read. If the warmup
     * fails (network error, no continuation returned), we fall back to a
     * {@link #CONT_PIT_PREFIX} timestamp anchor, then to the legacy
     * {@link #CONT_FROM_NOW} sentinel.
     */
    List<ChangeFeedCursor> listCursors(CosmosContainer container, ResourceAddress address) {
        try {
            List<FeedRange> ranges = container.getFeedRanges();
            if (ranges == null || ranges.isEmpty()) {
                ranges = Collections.singletonList(FeedRange.forFullRange());
            }
            List<ChangeFeedCursor> cursors = new ArrayList<>(ranges.size());
            long now = System.currentTimeMillis();
            for (FeedRange range : ranges) {
                String continuation = warmupContinuation(container, range, now);
                PartitionPosition pos = new PartitionPosition(encodeRange(range), continuation);
                CursorToken token = new CursorToken(
                        providerId, address, now, CursorAnchor.NOW, List.of(pos));
                cursors.add(new ChangeFeedCursor(token));
            }
            return cursors;
        } catch (CosmosException e) {
            throw CosmosErrorMapper.map(e, "listCursors");
        }
    }

    /**
     * Execute one {@code createForProcessingFromNow(range)} query to obtain a real
     * Cosmos continuation token that bookmarks the live tip at this instant.
     * Falls back to a {@link #CONT_PIT_PREFIX} timestamp anchor (then to
     * {@link #CONT_FROM_NOW}) if the warmup query cannot produce one.
     */
    private String warmupContinuation(CosmosContainer container, FeedRange range, long nowMs) {
        try {
            CosmosChangeFeedRequestOptions warmup =
                    CosmosChangeFeedRequestOptions.createForProcessingFromNow(range);
            if (MODE_AVAD.equalsIgnoreCase(avadMode)) {
                warmup = warmup.allVersionsAndDeletes();
            }
            warmup.setMaxItemCount(1);
            Iterator<FeedResponse<JsonNode>> it =
                    container.queryChangeFeed(warmup, JsonNode.class).iterableByPage().iterator();
            if (it.hasNext()) {
                String c = it.next().getContinuationToken();
                if (c != null && !c.isBlank()) return c;
            }
        } catch (RuntimeException e) {
            // Fall through to the timestamp anchor below.
        }
        return CONT_PIT_PREFIX + nowMs;
    }

    /**
     * Drain one page of events from the given cursor. The returned page carries
     * a fresh {@code nextCursor()} with refreshed continuation tokens.
     */
    ChangeFeedPage readChanges(CosmosContainer container, ResourceAddress address,
                               ChangeFeedCursor cursor, OperationOptions options) {
        CursorToken token;
        if (cursor.isUnhydratedSentinel()) {
            // Hydrate the sentinel by re-discovering ranges.
            List<ChangeFeedCursor> all = listCursors(container, address);
            // Merge their partition positions into a single token.
            List<PartitionPosition> positions = new ArrayList<>(all.size());
            for (ChangeFeedCursor c : all) positions.addAll(c.token().partitions());
            token = new CursorToken(providerId, address,
                    System.currentTimeMillis(), CursorAnchor.NOW, positions);
        } else {
            token = cursor.token();
        }

        if (token.partitions().isEmpty()) {
            // Nothing to read; return an empty caught-up page.
            CursorToken refreshed = token.withIssuedAt(System.currentTimeMillis());
            return new ChangeFeedPage(List.of(), new ChangeFeedCursor(refreshed), false, false);
        }

        // Round-robin: read one page from one partition per call.
        // This keeps the per-call cost predictable; callers driving many cursors
        // in parallel will get balanced throughput across partitions naturally.
        // We pick the first partition; callers can drive partitions explicitly via listCursors.
        List<PartitionPosition> partitions = new ArrayList<>(token.partitions());
        PartitionPosition pos = partitions.get(0);
        FeedRange range = decodeRange(pos.partitionId());

        CosmosChangeFeedRequestOptions opts;
        String cont = pos.continuation();
        if (cont != null && cont.startsWith(CONT_PIT_PREFIX)) {
            // Resume from the exact instant captured at mint time. A corrupted /
            // tampered token (non-numeric suffix) must surface as the portable
            // CursorExpiredException(MALFORMED) — never as an unchecked
            // NumberFormatException.
            long pitMs;
            try {
                pitMs = Long.parseLong(cont.substring(CONT_PIT_PREFIX.length()));
            } catch (NumberFormatException nfe) {
                throw new CursorExpiredException(new MulticloudDbError(
                        MulticloudDbErrorCategory.CURSOR_EXPIRED,
                        "Cosmos cursor has a malformed @@PIT continuation: " + cont,
                        providerId, "readChanges", false,
                        Map.of("reason", "MALFORMED")), nfe);
            }
            opts = CosmosChangeFeedRequestOptions.createForProcessingFromPointInTime(
                    java.time.Instant.ofEpochMilli(pitMs), range);
        } else if (CONT_FROM_NOW.equals(cont) || cont == null) {
            opts = CosmosChangeFeedRequestOptions.createForProcessingFromNow(range);
        } else {
            opts = CosmosChangeFeedRequestOptions.createForProcessingFromContinuation(cont);
        }
        if (MODE_AVAD.equalsIgnoreCase(avadMode)) {
            opts = opts.allVersionsAndDeletes();
        }
        opts.setMaxItemCount(DEFAULT_PAGE_SIZE);

        try {
            CosmosPagedIterable<JsonNode> iterable = container.queryChangeFeed(opts, JsonNode.class);
            Iterator<FeedResponse<JsonNode>> pageIter = iterable.iterableByPage().iterator();

            List<ChangeEvent> events = new ArrayList<>();
            String newContinuation = pos.continuation();
            boolean hasMore = false;

            if (pageIter.hasNext()) {
                FeedResponse<JsonNode> page = pageIter.next();
                for (JsonNode item : page.getResults()) {
                    events.add(mapEvent(item));
                }
                String c = page.getContinuationToken();
                if (c != null && !c.isBlank()) newContinuation = c;
                // hasMore is true iff the page hit the size cap; otherwise we're caught up.
                hasMore = page.getResults().size() >= DEFAULT_PAGE_SIZE;
            }

            partitions.set(0, new PartitionPosition(pos.partitionId(), newContinuation));
            CursorToken nextToken = token.withPartitions(partitions, System.currentTimeMillis());
            return new ChangeFeedPage(events, new ChangeFeedCursor(nextToken), hasMore, false);
        } catch (CosmosException e) {
            // 410 GONE → cursor expired (provider trimmed events)
            if (e.getStatusCode() == 410) {
                throw new CursorExpiredException(new MulticloudDbError(
                        MulticloudDbErrorCategory.CURSOR_EXPIRED,
                        "Cosmos returned 410 GONE; the cursor's events were trimmed",
                        providerId,
                        "readChanges",
                        false,
                        Map.of("reason", "PROVIDER_TRIMMED",
                                "statusCode", String.valueOf(e.getStatusCode()))), e);
            }
            throw CosmosErrorMapper.map(e, "readChanges");
        }
    }

    private ChangeEvent mapEvent(JsonNode item) {
        // Document identity: id + (partitionKey or pk)
        String id = textOrEmpty(item.get("id"));
        String pk = textOrEmpty(firstPresent(item, "partitionKey", "pk", "_pk"));
        if (pk.isBlank()) pk = id; // fall back to id if pk not present in the projection
        MulticloudDbKey key = MulticloudDbKey.of(pk, id);

        // _ts is seconds since epoch in Cosmos
        long tsSeconds = item.path("_ts").asLong(0L);
        Instant commitTs = tsSeconds > 0
                ? Instant.ofEpochSecond(tsSeconds)
                : Instant.now();

        // _etag is a stable per-version identifier
        String eventId = textOrEmpty(item.get("_etag"));
        if (eventId.isBlank()) eventId = id + "@" + tsSeconds;

        // In AVAD mode Cosmos surfaces an outer envelope with metadata.operationType.
        // In LatestVersion mode the items are bare documents and all events are UPDATE.
        ChangeType type = inferChangeType(item);

        return new ChangeEvent(key, type, commitTs, item, eventId);
    }

    /**
     * Infer the change type. AVAD-mode events carry a {@code metadata.operationType}
     * field; LatestVersion-mode events do not, in which case we surface them as
     * UPDATE (the most-portable safe default).
     */
    private ChangeType inferChangeType(JsonNode item) {
        JsonNode metadata = item.path("metadata");
        if (metadata.isObject()) {
            String op = metadata.path("operationType").asText("");
            switch (op.toLowerCase(java.util.Locale.ROOT)) {
                case "create": return ChangeType.CREATE;
                case "delete": return ChangeType.DELETE;
                case "replace":
                case "update":
                default: return ChangeType.UPDATE;
            }
        }
        return ChangeType.UPDATE;
    }

    private static String textOrEmpty(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("");
    }

    private static JsonNode firstPresent(JsonNode item, String... fields) {
        for (String f : fields) {
            JsonNode n = item.get(f);
            if (n != null && !n.isNull()) return n;
        }
        return null;
    }

    // ── FeedRange wire format ────────────────────────────────────────────────
    // FeedRange's toString() is documented as a stable JSON form that
    // FeedRange.fromString() can decode. We use that as our partitionId.

    private static String encodeRange(FeedRange range) {
        return range.toString();
    }

    private FeedRange decodeRange(String partitionId) {
        try {
            return FeedRange.fromString(partitionId);
        } catch (Exception e) {
            throw new CursorExpiredException(new MulticloudDbError(
                    MulticloudDbErrorCategory.CURSOR_EXPIRED,
                    "Unable to decode Cosmos FeedRange from cursor partitionId: " + partitionId,
                    providerId,
                    "readChanges",
                    false,
                    Map.of("reason", "MALFORMED")), e);
        }
    }
}

// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import com.fasterxml.jackson.databind.JsonNode;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
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
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DescribeStreamRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeStreamResponse;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.GetRecordsRequest;
import software.amazon.awssdk.services.dynamodb.model.GetRecordsResponse;
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorRequest;
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorResponse;
import software.amazon.awssdk.services.dynamodb.model.OperationType;
import software.amazon.awssdk.services.dynamodb.model.Record;
import software.amazon.awssdk.services.dynamodb.model.Shard;
import software.amazon.awssdk.services.dynamodb.model.ShardIteratorType;
import software.amazon.awssdk.services.dynamodb.model.StreamDescription;
import software.amazon.awssdk.services.dynamodb.model.StreamSpecification;
import software.amazon.awssdk.services.dynamodb.model.TrimmedDataAccessException;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DynamoDB Streams-backed change-feed reader.
 * <p>
 * Maps the portable change-feed primitives onto the DynamoDB Streams API:
 * <ul>
 *   <li>{@link DescribeStreamRequest} → enumerate open shards on the table's
 *       latest stream (one cursor per shard).</li>
 *   <li>{@link GetShardIteratorRequest} ({@link ShardIteratorType#LATEST}
 *       for the {@code now()} sentinel hydrate, {@link ShardIteratorType#TRIM_HORIZON}
 *       for child-shard absorption, {@link ShardIteratorType#AFTER_SEQUENCE_NUMBER}
 *       on sequence-numbered resume, or a persisted iterator string for
 *       {@code LATEST} resumes that have yet to see their first record) → derive a
 *       per-shard iterator.</li>
 *   <li>{@link GetRecordsRequest} → drain one page of records per
 *       {@link #readChanges} call.</li>
 *   <li>Shard splits / closes are absorbed by re-{@link DescribeStreamRequest
 *       describing} the stream and emitting child shards into the next cursor.</li>
 * </ul>
 *
 * <h3>Provisioning prerequisite</h3>
 * The table's {@link StreamSpecification#streamViewType()} must be
 * {@code NEW_AND_OLD_IMAGES} for the SDK to recover both the post-image
 * (CREATE/UPDATE) and the pre-image (DELETE) of each change.
 *
 * <h3>24-hour baseline</h3>
 * DynamoDB Streams retain records for 24 hours; this naturally matches the
 * portable client-side baseline. Provider-side expiry surfaces as
 * {@link com.multiclouddb.api.changefeed.CursorExpiredException} when
 * {@link GetRecordsRequest} returns {@code TrimmedDataAccessException}.
 */
final class DynamoChangeFeedReader {

    private static final Logger LOG = LoggerFactory.getLogger(DynamoChangeFeedReader.class);
    private static final int DEFAULT_PAGE_SIZE = 100;

    /** Carried in the partition continuation to indicate "start from TRIM_HORIZON". */
    static final String ANCHOR_BEGINNING = "@@TRIM_HORIZON";
    /** Carried in the partition continuation to indicate "start from LATEST". */
    static final String ANCHOR_NOW = "@@LATEST";
    /**
     * Carried in the partition continuation when a {@code now()}-anchored read returned
     * zero records. The suffix is a DynamoDB Streams shard iterator string returned by
     * the previous {@code GetRecords} call. Persisting the iterator (rather than
     * re-resolving {@code LATEST} on the next read) prevents silent event loss in the
     * window between two reads. DynamoDB Streams shard iterators expire after ~5 minutes
     * of inactivity; if no records arrive within that window the next read will surface
     * {@link com.multiclouddb.api.changefeed.CursorExpiredException} with reason
     * {@code ITERATOR_EXPIRED} and the caller must re-bootstrap via
     * {@code listCursors()}.
     */
    static final String ANCHOR_ITER_PREFIX = "@@ITER:";

    private final ProviderId providerId;
    private final DynamoDbStreamsClient streamsClient;

    DynamoChangeFeedReader(ProviderId providerId, DynamoDbStreamsClient streamsClient) {
        this.providerId = providerId;
        this.streamsClient = streamsClient;
    }

    /** Build from the same config used for the data-plane client. */
    static DynamoChangeFeedReader create(ProviderId providerId, MulticloudDbClientConfig config) {
        String region = config.connection().getOrDefault(DynamoConstants.CONFIG_REGION, DynamoConstants.REGION_DEFAULT);
        String endpoint = config.connection().get(DynamoConstants.CONFIG_ENDPOINT);

        var builder = DynamoDbStreamsClient.builder().region(Region.of(region));
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        String accessKey = config.auth().get(DynamoConstants.CONFIG_ACCESS_KEY_ID);
        String secretKey = config.auth().get(DynamoConstants.CONFIG_SECRET_ACCESS_KEY);
        if (accessKey != null && secretKey != null) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey)));
        }
        return new DynamoChangeFeedReader(providerId, builder.build());
    }

    /**
     * Discover the open shards on the table's stream and mint one cursor per shard,
     * each positioned at the live tip ({@link ShardIteratorType#LATEST}).
     */
    List<ChangeFeedCursor> listCursors(DynamoDbClient ddb, ResourceAddress address, String tableName) {
        String streamArn = describeStreamArn(ddb, tableName);
        if (streamArn == null) {
            throw new com.multiclouddb.api.MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY,
                    "DynamoDB table '" + tableName
                            + "' does not have a stream enabled; "
                            + "set StreamSpecification(NEW_AND_OLD_IMAGES) on the table",
                    providerId,
                    "listCursors",
                    false,
                    Map.of("table", tableName, "reason", "stream_not_enabled")));
        }
        List<Shard> shards = listOpenShards(streamArn);
        List<ChangeFeedCursor> cursors = new ArrayList<>(Math.max(shards.size(), 1));
        long now = System.currentTimeMillis();
        if (shards.isEmpty()) {
            // Edge case: no shards yet. Mint a placeholder cursor so the next read
            // re-describes the stream and picks up new shards as they appear.
            PartitionPosition placeholder = new PartitionPosition(streamArn + "::__placeholder__", ANCHOR_NOW);
            CursorToken token = new CursorToken(providerId, address, now, CursorAnchor.NOW, List.of(placeholder));
            cursors.add(new ChangeFeedCursor(token));
            return cursors;
        }
        for (Shard shard : shards) {
            // Eagerly resolve a LATEST iterator at mint time and persist it.
            // If we instead carried ANCHOR_NOW here, the next readChanges() would
            // resolve GetShardIterator(LATEST) at *that* moment, silently skipping
            // events written between listCursors() and the first read.
            String iter = resolveLatestIteratorOrAnchor(streamArn, shard.shardId());
            PartitionPosition pos = new PartitionPosition(streamArn + "::" + shard.shardId(), iter);
            CursorToken token = new CursorToken(providerId, address, now, CursorAnchor.NOW, List.of(pos));
            cursors.add(new ChangeFeedCursor(token));
        }
        return cursors;
    }

    /**
     * Resolve a {@link ShardIteratorType#LATEST} iterator string and wrap it in the
     * {@link #ANCHOR_ITER_PREFIX} envelope. Returns {@link #ANCHOR_NOW} as a fallback
     * if the iterator cannot be resolved (shard transition mid-call) — the next read
     * will retry resolution.
     */
    private String resolveLatestIteratorOrAnchor(String streamArn, String shardId) {
        try {
            GetShardIteratorResponse resp = streamsClient.getShardIterator(
                    GetShardIteratorRequest.builder()
                            .streamArn(streamArn)
                            .shardId(shardId)
                            .shardIteratorType(ShardIteratorType.LATEST)
                            .build());
            String iter = resp.shardIterator();
            return iter != null ? ANCHOR_ITER_PREFIX + iter : ANCHOR_NOW;
        } catch (DynamoDbException e) {
            LOG.debug("dynamo.changefeed: eager LATEST iterator resolution failed for "
                    + "stream={} shard={} — falling back to lazy ANCHOR_NOW", streamArn, shardId, e);
            return ANCHOR_NOW;
        }
    }

    /**
     * Drain one page of events. Reads from the first partition in the cursor.
     */
    ChangeFeedPage readChanges(DynamoDbClient ddb, ResourceAddress address, String tableName,
                               ChangeFeedCursor cursor, OperationOptions options) {
        CursorToken token = cursor.isUnhydratedSentinel()
                ? hydrateSentinel(ddb, address, tableName)
                : cursor.token();

        if (token.partitions().isEmpty()) {
            CursorToken refreshed = token.withIssuedAt(System.currentTimeMillis());
            return new ChangeFeedPage(List.of(), new ChangeFeedCursor(refreshed), false, false);
        }

        List<PartitionPosition> positions = new ArrayList<>(token.partitions());
        PartitionPosition pos = positions.get(0);

        // partitionId is "<streamArn>::<shardId>"
        int sep = pos.partitionId().indexOf("::");
        if (sep < 0) {
            throw new CursorExpiredException(new MulticloudDbError(
                    MulticloudDbErrorCategory.CURSOR_EXPIRED,
                    "Malformed DynamoDB cursor partition id: " + pos.partitionId(),
                    providerId, "readChanges", false,
                    Map.of("reason", "MALFORMED")));
        }
        String streamArn = pos.partitionId().substring(0, sep);
        String shardId = pos.partitionId().substring(sep + 2);

        // Placeholder cursor (no shards yet) — re-describe and replace ourselves.
        if (shardId.equals("__placeholder__")) {
            List<Shard> shards = listOpenShards(streamArn);
            if (shards.isEmpty()) {
                CursorToken refreshed = token.withIssuedAt(System.currentTimeMillis());
                return new ChangeFeedPage(List.of(), new ChangeFeedCursor(refreshed), false, false);
            }
            List<PartitionPosition> newPositions = new ArrayList<>();
            for (Shard s : shards) {
                String iter = resolveLatestIteratorOrAnchor(streamArn, s.shardId());
                newPositions.add(new PartitionPosition(streamArn + "::" + s.shardId(), iter));
            }
            CursorToken next = token.withPartitions(newPositions, System.currentTimeMillis());
            return new ChangeFeedPage(List.of(), new ChangeFeedCursor(next), true, false);
        }

        String iterator;
        try {
            iterator = resolveShardIterator(streamArn, shardId, pos.continuation());
        } catch (TrimmedDataAccessException e) {
            throw new CursorExpiredException(new MulticloudDbError(
                    MulticloudDbErrorCategory.CURSOR_EXPIRED,
                    "DynamoDB Streams returned TrimmedDataAccessException; "
                            + "the cursor's records are older than the 24h stream retention",
                    providerId, "readChanges", false,
                    Map.of("reason", "PROVIDER_TRIMMED")), e);
        } catch (DynamoDbException e) {
            throw maybeExpiredIterator(e, "readChanges");
        }
        if (iterator == null) {
            // Shard is closed and we are at its end — re-describe stream to find children.
            return absorbClosedShard(token, positions, streamArn, shardId);
        }

        try {
            GetRecordsResponse resp = streamsClient.getRecords(GetRecordsRequest.builder()
                    .shardIterator(iterator)
                    .limit(DEFAULT_PAGE_SIZE)
                    .build());

            List<ChangeEvent> events = new ArrayList<>(resp.records().size());
            String lastSeq = null;
            for (Record rec : resp.records()) {
                events.add(mapRecord(rec));
                if (rec.dynamodb() != null) lastSeq = rec.dynamodb().sequenceNumber();
            }

            String nextIter = resp.nextShardIterator();
            String newContinuation;
            boolean shardClosed = nextIter == null;
            if (shardClosed) {
                // Shard finished — emit child shards into the next cursor.
                return absorbClosedShard(
                        events.isEmpty()
                                ? token
                                : token.withPartitions(positions, System.currentTimeMillis()),
                        positions, streamArn, shardId, events);
            }
            // Continuation strategy:
            //   - We saw records: use the last sequence number (AFTER_SEQUENCE_NUMBER on resume).
            //   - Zero records, anchored to a sequence number already: keep the same sequence
            //     number (AFTER_SEQUENCE_NUMBER on resume is idempotent).
            //   - Zero records, anchored to ANCHOR_NOW (LATEST) or ANCHOR_ITER (a previously
            //     persisted iterator): persist the next iterator returned by GetRecords so the
            //     next read continues from exactly where this one left off. Re-resolving
            //     LATEST on every read would silently lose events that arrived between reads.
            if (lastSeq != null) {
                newContinuation = lastSeq;
            } else if (ANCHOR_NOW.equals(pos.continuation())
                    || (pos.continuation() != null
                            && pos.continuation().startsWith(ANCHOR_ITER_PREFIX))) {
                newContinuation = ANCHOR_ITER_PREFIX + nextIter;
            } else {
                newContinuation = pos.continuation();
            }
            positions.set(0, new PartitionPosition(pos.partitionId(), newContinuation));
            boolean hasMore = resp.records().size() >= DEFAULT_PAGE_SIZE;
            CursorToken next = token.withPartitions(positions, System.currentTimeMillis());
            return new ChangeFeedPage(events, new ChangeFeedCursor(next), hasMore, false);
        } catch (TrimmedDataAccessException e) {
            throw new CursorExpiredException(new MulticloudDbError(
                    MulticloudDbErrorCategory.CURSOR_EXPIRED,
                    "DynamoDB Streams returned TrimmedDataAccessException during GetRecords",
                    providerId, "readChanges", false,
                    Map.of("reason", "PROVIDER_TRIMMED")), e);
        } catch (DynamoDbException e) {
            throw maybeExpiredIterator(e, "readChanges");
        }
    }

    private com.multiclouddb.api.MulticloudDbException maybeExpiredIterator(
            DynamoDbException e, String operation) {
        // ExpiredIteratorException surfaces when a persisted shard iterator
        // (typically from an ANCHOR_ITER continuation) has aged past the ~5-minute
        // iterator-lifetime window. Map it to CursorExpiredException so callers can
        // re-bootstrap with listCursors().
        String code = e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : null;
        if ("ExpiredIteratorException".equals(code)) {
            return new CursorExpiredException(new MulticloudDbError(
                    MulticloudDbErrorCategory.CURSOR_EXPIRED,
                    "DynamoDB Streams shard iterator has expired (5-minute inactivity window). "
                            + "Re-bootstrap by calling listCursors().",
                    providerId, operation, false,
                    Map.of("reason", "ITERATOR_EXPIRED")), e);
        }
        // Defensive: if the SDK ever delivers a Trimmed exception as a generic
        // DynamoDbException with the errorCode set instead of a typed
        // TrimmedDataAccessException, surface it the same way the typed-catch does.
        if (isTrimmed(e)) {
            return new CursorExpiredException(new MulticloudDbError(
                    MulticloudDbErrorCategory.CURSOR_EXPIRED,
                    "DynamoDB Streams returned TrimmedDataAccessException; the cursor's "
                            + "records are older than the 24h stream retention",
                    providerId, operation, false,
                    Map.of("reason", "PROVIDER_TRIMMED")), e);
        }
        return mapStreamsException(e, operation);
    }

    private static com.multiclouddb.api.MulticloudDbException mapStreamsException(
            DynamoDbException e, String operation) {
        Map<String, String> details = new HashMap<>();
        if (e.awsErrorDetails() != null) {
            details.put("errorCode", e.awsErrorDetails().errorCode());
            details.put("serviceName", e.awsErrorDetails().serviceName());
        }
        if (e.requestId() != null) details.put("requestId", e.requestId());
        boolean retryable = e.statusCode() >= 500 || e.statusCode() == 429;
        return new com.multiclouddb.api.MulticloudDbException(new MulticloudDbError(
                MulticloudDbErrorCategory.PROVIDER_ERROR, e.getMessage(),
                ProviderId.DYNAMO, operation, retryable, e.statusCode(), details), e);
    }

    private CursorToken hydrateSentinel(DynamoDbClient ddb, ResourceAddress address, String tableName) {
        String streamArn = describeStreamArn(ddb, tableName);
        if (streamArn == null) {
            // Fail fast (and consistent with listCursors): surfacing the
            // misconfiguration is strictly safer than silently returning empty pages
            // for the lifetime of the cursor.
            throw new com.multiclouddb.api.MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY,
                    "DynamoDB table '" + tableName
                            + "' does not have a stream enabled; "
                            + "set StreamSpecification(NEW_AND_OLD_IMAGES) on the table",
                    providerId,
                    "readChanges",
                    false,
                    Map.of("table", tableName, "reason", "stream_not_enabled")));
        }
        List<Shard> shards = listOpenShards(streamArn);
        List<PartitionPosition> positions = new ArrayList<>();
        for (Shard s : shards) {
            // Eagerly resolve a LATEST iterator (same rationale as listCursors): anchor
            // the cursor at the stream tip *now*, not at first-read time.
            String iter = resolveLatestIteratorOrAnchor(streamArn, s.shardId());
            positions.add(new PartitionPosition(streamArn + "::" + s.shardId(), iter));
        }
        return new CursorToken(providerId, address, System.currentTimeMillis(),
                CursorAnchor.NOW, positions);
    }

    private ChangeFeedPage absorbClosedShard(CursorToken token, List<PartitionPosition> positions,
                                             String streamArn, String closedShardId) {
        return absorbClosedShard(token, positions, streamArn, closedShardId, List.of());
    }

    private ChangeFeedPage absorbClosedShard(CursorToken token, List<PartitionPosition> positions,
                                             String streamArn, String closedShardId,
                                             List<ChangeEvent> drainedEvents) {
        // Replace the closed shard with its children (if any).
        List<Shard> all = listAllShards(streamArn);
        List<PartitionPosition> updated = new ArrayList<>(positions.size());
        for (int i = 1; i < positions.size(); i++) updated.add(positions.get(i));
        for (Shard s : all) {
            if (closedShardId.equals(s.parentShardId())) {
                updated.add(new PartitionPosition(streamArn + "::" + s.shardId(), ANCHOR_BEGINNING));
            }
        }
        boolean terminal = updated.isEmpty();
        CursorToken next = terminal
                ? token.withIssuedAt(System.currentTimeMillis())
                : token.withPartitions(updated, System.currentTimeMillis());
        // hasMore must be true whenever child shards exist (regardless of whether we
        // drained events on this call) so callers immediately keep reading from them.
        return new ChangeFeedPage(drainedEvents, new ChangeFeedCursor(next),
                !terminal, terminal);
    }

    private String resolveShardIterator(String streamArn, String shardId, String continuation) {
        // A previously persisted iterator (from an earlier zero-record page off
        // ANCHOR_NOW). Use it directly — no GetShardIterator round-trip needed.
        if (continuation != null && continuation.startsWith(ANCHOR_ITER_PREFIX)) {
            return continuation.substring(ANCHOR_ITER_PREFIX.length());
        }
        GetShardIteratorRequest.Builder req = GetShardIteratorRequest.builder()
                .streamArn(streamArn)
                .shardId(shardId);
        if (ANCHOR_BEGINNING.equals(continuation) || continuation == null) {
            req.shardIteratorType(ShardIteratorType.TRIM_HORIZON);
        } else if (ANCHOR_NOW.equals(continuation)) {
            req.shardIteratorType(ShardIteratorType.LATEST);
        } else {
            req.shardIteratorType(ShardIteratorType.AFTER_SEQUENCE_NUMBER)
                    .sequenceNumber(continuation);
        }
        GetShardIteratorResponse resp = streamsClient.getShardIterator(req.build());
        return resp.shardIterator();
    }

    private List<Shard> listOpenShards(String streamArn) {
        List<Shard> all = listAllShards(streamArn);
        List<Shard> open = new ArrayList<>();
        for (Shard s : all) {
            if (s.sequenceNumberRange() == null
                    || s.sequenceNumberRange().endingSequenceNumber() == null) {
                open.add(s);
            }
        }
        return open;
    }

    private List<Shard> listAllShards(String streamArn) {
        List<Shard> shards = new ArrayList<>();
        String lastEvaluated = null;
        do {
            DescribeStreamRequest.Builder req = DescribeStreamRequest.builder()
                    .streamArn(streamArn);
            if (lastEvaluated != null) req.exclusiveStartShardId(lastEvaluated);
            DescribeStreamResponse resp = streamsClient.describeStream(req.build());
            StreamDescription desc = resp.streamDescription();
            if (desc == null || desc.shards() == null) break;
            shards.addAll(desc.shards());
            lastEvaluated = desc.lastEvaluatedShardId();
        } while (lastEvaluated != null);
        return shards;
    }

    private String describeStreamArn(DynamoDbClient ddb, String tableName) {
        DescribeTableResponse resp = ddb.describeTable(DescribeTableRequest.builder()
                .tableName(tableName).build());
        return resp.table() != null ? resp.table().latestStreamArn() : null;
    }

    private ChangeEvent mapRecord(Record rec) {
        OperationType op = rec.eventName();
        ChangeType type;
        switch (op) {
            case INSERT: type = ChangeType.CREATE; break;
            case MODIFY: type = ChangeType.UPDATE; break;
            case REMOVE: type = ChangeType.DELETE; break;
            default:     type = ChangeType.UPDATE;
        }

        java.time.Instant commitTs = rec.dynamodb() != null
                && rec.dynamodb().approximateCreationDateTime() != null
                ? rec.dynamodb().approximateCreationDateTime()
                : java.time.Instant.now();
        String eventId = rec.eventID();
        if (eventId == null) eventId = rec.dynamodb() != null
                ? rec.dynamodb().sequenceNumber() : "evt-unknown";

        Map<String, AttributeValue> keys = rec.dynamodb() != null
                ? rec.dynamodb().keys() : Map.of();
        String pk = attrToString(keys.get("partitionKey"));
        String sk = attrToString(keys.get("sortKey"));
        MulticloudDbKey key = sk != null && !sk.isEmpty()
                ? MulticloudDbKey.of(pk, sk)
                : MulticloudDbKey.of(pk);

        Map<String, AttributeValue> imageAttrs = rec.dynamodb() != null
                ? (type == ChangeType.DELETE
                        ? rec.dynamodb().oldImage()
                        : rec.dynamodb().newImage())
                : null;
        JsonNode data = imageAttrs != null ? DynamoItemMapper.attributeMapToJsonNode(imageAttrs) : null;

        return new ChangeEvent(key, type, commitTs, data, eventId);
    }

    private static String attrToString(AttributeValue v) {
        if (v == null) return "";
        if (v.s() != null) return v.s();
        if (v.n() != null) return v.n();
        return "";
    }

    private static boolean isTrimmed(DynamoDbException e) {
        // Defensive helper: most paths catch TrimmedDataAccessException directly,
        // but Dynamo SDK is known to occasionally wrap it in DynamoDbException
        // with the corresponding errorCode string. Retained for completeness.
        return e instanceof TrimmedDataAccessException
                || (e != null && e.awsErrorDetails() != null
                        && "TrimmedDataAccessException".equals(e.awsErrorDetails().errorCode()));
    }

    void close() {
        try {
            streamsClient.close();
        } catch (Exception ignored) {
        }
    }
}

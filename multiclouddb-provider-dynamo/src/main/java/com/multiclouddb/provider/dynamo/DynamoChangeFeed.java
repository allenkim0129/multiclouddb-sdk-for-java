// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.StreamSpecification;
import software.amazon.awssdk.services.dynamodb.model.StreamViewType;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeStreamRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeStreamResponse;
import software.amazon.awssdk.services.dynamodb.model.GetRecordsRequest;
import software.amazon.awssdk.services.dynamodb.model.GetRecordsResponse;
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorRequest;
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorResponse;
import software.amazon.awssdk.services.dynamodb.model.OperationType;
import software.amazon.awssdk.services.dynamodb.model.Record;
import software.amazon.awssdk.services.dynamodb.model.Shard;
import software.amazon.awssdk.services.dynamodb.model.ShardIteratorType;
import software.amazon.awssdk.services.dynamodb.model.StreamRecord;
import software.amazon.awssdk.services.dynamodb.model.TrimmedDataAccessException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DynamoDB Streams change-feed adapter.
 * <p>
 * Maps the SDK's portable change-feed contract onto Dynamo's
 * {@code DescribeStream} + {@code GetShardIterator} + {@code GetRecords}
 * primitives.
 *
 * <p>Constraints (capability-gated; see {@code DynamoCapabilities}):
 * <ul>
 *   <li>{@link StartPosition.AtTime} is rejected with
 *       {@link MulticloudDbErrorCategory#UNSUPPORTED_CAPABILITY} —
 *       Dynamo does not provide timestamp-based iterators.</li>
 *   <li>{@link FeedScope.LogicalPartition} is rejected with
 *       {@link MulticloudDbErrorCategory#UNSUPPORTED_CAPABILITY}.</li>
 *   <li>{@link NewItemStateMode#REQUIRE} requires
 *       {@code StreamViewType.NEW_IMAGE} or {@code NEW_AND_OLD_IMAGES} on
 *       the table; otherwise rejected at first call.</li>
 * </ul>
 */
final class DynamoChangeFeed {

    private static final Logger LOG = LoggerFactory.getLogger(DynamoChangeFeed.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DynamoDbClient dynamo;
    private final DynamoDbStreamsClient streams;

    DynamoChangeFeed(DynamoDbClient dynamo, DynamoDbStreamsClient streams) {
        this.dynamo = dynamo;
        this.streams = streams;
    }

    List<String> listPhysicalPartitions(ResourceAddress address) {
        try {
            String streamArn = resolveStreamArn(address);
            List<Shard> shards = describeAllShards(streamArn);
            List<String> ids = new ArrayList<>();
            for (Shard s : shards) {
                if (!isClosed(s)) {
                    ids.add(s.shardId());
                }
            }
            return ids;
        } catch (Exception e) {
            throw mapDynamoException(e, "listPhysicalPartitions");
        }
    }

    ChangeFeedPage readChanges(ChangeFeedRequest request, OperationOptions options) {
        Instant start = Instant.now();
        try {
            // Reject unsupported request shapes early (defence in depth — the default
            // client also gates these, but we may be called via the SPI directly).
            if (request.startPosition() instanceof StartPosition.AtTime) {
                throw unsupported("StartPosition.atTime is not supported by DynamoDB Streams "
                        + "(only TRIM_HORIZON / LATEST / sequence-number iterators are available)");
            }
            if (request.scope() instanceof FeedScope.LogicalPartition) {
                throw unsupported("FeedScope.logicalPartition is not supported by DynamoDB Streams; "
                        + "use FeedScope.physicalPartition with an ID from listPhysicalPartitions()");
            }

            String streamArn = resolveStreamArn(request.address());
            validateStreamViewType(request, streamArn);

            // Resolve which shards to read this call
            ShardSelection selection = resolveShards(request, streamArn);

            int maxRecordsPerShard = request.maxPageSize() > 0 ? request.maxPageSize() : 100;
            List<ChangeEvent> events = new ArrayList<>();
            Map<String, ShardCursor> nextCursors = new LinkedHashMap<>();
            boolean partitionRetired = false;
            List<String> childPartitions = List.of();

            for (Shard shard : selection.shardsToRead) {
                ShardCursor existing = selection.cursors.get(shard.shardId());
                String iterator = obtainIterator(streamArn, shard, existing, request.startPosition());
                if (iterator == null) {
                    // already closed and fully consumed — skip
                    continue;
                }
                GetRecordsResponse resp = streams.getRecords(GetRecordsRequest.builder()
                        .shardIterator(iterator)
                        .limit(maxRecordsPerShard)
                        .build());

                String lastSeq = existing != null ? existing.lastSeq : null;
                for (Record r : resp.records()) {
                    ChangeEvent ev = mapEvent(r, request);
                    if (ev != null) {
                        events.add(ev);
                    }
                    lastSeq = r.dynamodb().sequenceNumber();
                }

                if (resp.nextShardIterator() == null) {
                    // shard is closed and fully drained
                    if (selection.shardsToRead.size() == 1
                            && request.scope() instanceof FeedScope.PhysicalPartition) {
                        partitionRetired = true;
                        childPartitions = findChildren(streamArn, shard.shardId());
                    }
                    // do not include this shard in the next cursor
                } else {
                    nextCursors.put(shard.shardId(), new ShardCursor(lastSeq, false));
                }
            }

            String token = encodeToken(request.address(), streamArn, nextCursors);

            OperationDiagnostics diag = OperationDiagnostics
                    .builder(ProviderId.DYNAMO, "readChanges",
                            Duration.between(start, Instant.now()))
                    .itemCount(events.size())
                    .build();
            return new ChangeFeedPage(events, token, partitionRetired, childPartitions, diag);
        } catch (MulticloudDbException e) {
            throw e;
        } catch (Exception e) {
            throw mapDynamoException(e, "readChanges");
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private String resolveStreamArn(ResourceAddress address) {
        DescribeTableResponse resp = dynamo.describeTable(DescribeTableRequest.builder()
                .tableName(address.collection())
                .build());
        String arn = resp.table().latestStreamArn();
        if (arn == null || arn.isBlank()) {
            throw new MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.INVALID_REQUEST,
                    "DynamoDB table '" + address.collection() + "' does not have a stream enabled. "
                            + "Enable streams (StreamSpecification.StreamEnabled=true) before using "
                            + "the change feed.",
                    ProviderId.DYNAMO, "readChanges", false,
                    Map.of("table", address.collection())));
        }
        return arn;
    }

    private void validateStreamViewType(ChangeFeedRequest request, String streamArn) {
        if (request.newItemStateMode() == NewItemStateMode.OMIT) {
            return;
        }
        DescribeStreamResponse resp = streams.describeStream(DescribeStreamRequest.builder()
                .streamArn(streamArn)
                .limit(1)
                .build());
        StreamViewType view = resp.streamDescription().streamViewType();
        boolean hasNewImage = view == StreamViewType.NEW_IMAGE
                || view == StreamViewType.NEW_AND_OLD_IMAGES;
        if (request.newItemStateMode() == NewItemStateMode.REQUIRE && !hasNewImage) {
            throw unsupported("newItemStateMode=REQUIRE but the DynamoDB stream is configured "
                    + "with StreamViewType=" + view + ". Reconfigure the stream with NEW_IMAGE "
                    + "or NEW_AND_OLD_IMAGES.");
        }
    }

    private List<Shard> describeAllShards(String streamArn) {
        List<Shard> all = new ArrayList<>();
        String exclusive = null;
        while (true) {
            DescribeStreamResponse resp = streams.describeStream(DescribeStreamRequest.builder()
                    .streamArn(streamArn)
                    .exclusiveStartShardId(exclusive)
                    .build());
            all.addAll(resp.streamDescription().shards());
            exclusive = resp.streamDescription().lastEvaluatedShardId();
            if (exclusive == null || exclusive.isEmpty()) {
                break;
            }
        }
        return all;
    }

    private ShardSelection resolveShards(ChangeFeedRequest request, String streamArn) {
        Map<String, ShardCursor> cursors = decodeCursors(request, streamArn);
        List<Shard> all = describeAllShards(streamArn);

        List<Shard> toRead;
        if (request.scope() instanceof FeedScope.PhysicalPartition pp) {
            toRead = all.stream()
                    .filter(s -> s.shardId().equals(pp.partitionId()))
                    .toList();
            if (toRead.isEmpty()) {
                throw new MulticloudDbException(new MulticloudDbError(
                        MulticloudDbErrorCategory.INVALID_REQUEST,
                        "DynamoDB shard '" + pp.partitionId() + "' not found in stream " + streamArn,
                        ProviderId.DYNAMO, "readChanges", false, Map.of()));
            }
        } else {
            // EntireCollection — read all open shards (or shards still on the cursor).
            // If cursors carry shard IDs that are no longer in the stream description, drop them.
            Set<String> liveIds = new HashSet<>();
            for (Shard s : all) liveIds.add(s.shardId());
            cursors.keySet().retainAll(liveIds);

            // Read shards that are open OR have a cursor (need to drain closed-but-cursored).
            toRead = new ArrayList<>();
            for (Shard s : all) {
                if (!isClosed(s) || cursors.containsKey(s.shardId())) {
                    toRead.add(s);
                }
            }
        }
        return new ShardSelection(toRead, cursors);
    }

    private Map<String, ShardCursor> decodeCursors(ChangeFeedRequest request, String streamArn) {
        if (!(request.startPosition() instanceof StartPosition.FromContinuationToken token)) {
            return new LinkedHashMap<>();
        }
        JsonNode inner = ContinuationTokenCodec.decode(token.token(),
                ProviderId.DYNAMO, request.address());
        if (!inner.isObject()) {
            throw new MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.INVALID_REQUEST,
                    "Malformed Dynamo continuation token (inner object expected)",
                    ProviderId.DYNAMO, "readChanges", false, Map.of()));
        }
        String tokenArn = inner.path("streamArn").asText("");
        if (!streamArn.equals(tokenArn)) {
            throw new MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.INVALID_REQUEST,
                    "Continuation token was issued for stream '" + tokenArn
                            + "' but the table now points at '" + streamArn + "'. "
                            + "The stream was recreated; restart from beginning() or now().",
                    ProviderId.DYNAMO, "readChanges", false, Map.of()));
        }
        Map<String, ShardCursor> map = new LinkedHashMap<>();
        for (JsonNode shard : inner.path("shards")) {
            map.put(shard.path("shardId").asText(),
                    new ShardCursor(shard.path("lastSeq").asText(null), false));
        }
        return map;
    }

    private String encodeToken(ResourceAddress address, String streamArn,
                               Map<String, ShardCursor> cursors) {
        if (cursors.isEmpty()) {
            return null;
        }
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("streamArn", streamArn);
        ArrayNode arr = envelope.putArray("shards");
        for (Map.Entry<String, ShardCursor> e : cursors.entrySet()) {
            ObjectNode s = MAPPER.createObjectNode();
            s.put("shardId", e.getKey());
            if (e.getValue().lastSeq != null) {
                s.put("lastSeq", e.getValue().lastSeq);
            }
            arr.add(s);
        }
        return ContinuationTokenCodec.encode(ProviderId.DYNAMO, address, envelope);
    }

    private String obtainIterator(String streamArn, Shard shard, ShardCursor cursor,
                                  StartPosition initialStart) {
        GetShardIteratorRequest.Builder req = GetShardIteratorRequest.builder()
                .streamArn(streamArn)
                .shardId(shard.shardId());
        if (cursor != null && cursor.lastSeq != null) {
            req.shardIteratorType(ShardIteratorType.AFTER_SEQUENCE_NUMBER)
                    .sequenceNumber(cursor.lastSeq);
        } else if (initialStart instanceof StartPosition.Now) {
            req.shardIteratorType(ShardIteratorType.LATEST);
        } else {
            req.shardIteratorType(ShardIteratorType.TRIM_HORIZON);
        }
        try {
            GetShardIteratorResponse resp = streams.getShardIterator(req.build());
            return resp.shardIterator();
        } catch (TrimmedDataAccessException e) {
            throw new MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.CHECKPOINT_EXPIRED,
                    "DynamoDB stream cursor for shard " + shard.shardId()
                            + " has been trimmed (24-hour retention exceeded). "
                            + "Restart from StartPosition.beginning() or now().",
                    ProviderId.DYNAMO, "readChanges", false,
                    Map.of("shardId", shard.shardId())), e);
        }
    }

    private List<String> findChildren(String streamArn, String shardId) {
        List<Shard> all = describeAllShards(streamArn);
        List<String> children = new ArrayList<>();
        for (Shard s : all) {
            if (shardId.equals(s.parentShardId())) {
                children.add(s.shardId());
            }
        }
        return children;
    }

    private boolean isClosed(Shard s) {
        return s.sequenceNumberRange() != null
                && s.sequenceNumberRange().endingSequenceNumber() != null;
    }

    private ChangeEvent mapEvent(Record r, ChangeFeedRequest request) {
        if (r == null || r.dynamodb() == null) {
            return null;
        }
        StreamRecord sr = r.dynamodb();
        ChangeType type;
        OperationType op = r.eventName();
        if (op == OperationType.INSERT) {
            type = ChangeType.CREATE;
        } else if (op == OperationType.MODIFY) {
            type = ChangeType.UPDATE;
        } else if (op == OperationType.REMOVE) {
            type = ChangeType.DELETE;
        } else {
            LOG.debug("Skipping Dynamo record with unknown eventName '{}'", op);
            return null;
        }

        // Key extraction — we use the existing convention (partitionKey, sortKey attributes).
        Map<String, AttributeValue> keyAttrs = sr.keys();
        if (keyAttrs == null || keyAttrs.isEmpty()) {
            return null;
        }
        AttributeValue pk = keyAttrs.get(DynamoConstants.ATTR_PARTITION_KEY);
        AttributeValue sk = keyAttrs.get(DynamoConstants.ATTR_SORT_KEY);
        if (pk == null) {
            return null;
        }
        String pkStr = attrToString(pk);
        String skStr = sk != null ? attrToString(sk) : null;
        MulticloudDbKey key = (skStr != null && !skStr.equals(pkStr))
                ? MulticloudDbKey.of(pkStr, skStr)
                : MulticloudDbKey.of(pkStr);

        ObjectNode data = null;
        if (request.newItemStateMode() != NewItemStateMode.OMIT
                && type != ChangeType.DELETE
                && sr.newImage() != null && !sr.newImage().isEmpty()) {
            data = imageToJson(sr.newImage());
        }
        if (request.newItemStateMode() == NewItemStateMode.REQUIRE
                && type != ChangeType.DELETE && data == null) {
            throw unsupported("newItemStateMode=REQUIRE but no NEW_IMAGE was returned for shard record");
        }

        Instant ts = sr.approximateCreationDateTime();
        return new ChangeEvent(ProviderId.DYNAMO, sr.sequenceNumber(), type,
                request.address(), key, data, ts);
    }

    private static String attrToString(AttributeValue v) {
        if (v.s() != null) return v.s();
        if (v.n() != null) return v.n();
        if (v.bool() != null) return v.bool().toString();
        return v.toString();
    }

    private static ObjectNode imageToJson(Map<String, AttributeValue> image) {
        ObjectNode obj = MAPPER.createObjectNode();
        for (Map.Entry<String, AttributeValue> e : image.entrySet()) {
            AttributeValue v = e.getValue();
            if (v.s() != null) obj.put(e.getKey(), v.s());
            else if (v.n() != null) {
                try {
                    obj.put(e.getKey(), new java.math.BigDecimal(v.n()));
                } catch (NumberFormatException nfe) {
                    obj.put(e.getKey(), v.n());
                }
            }
            else if (v.bool() != null) obj.put(e.getKey(), v.bool());
            else if (Boolean.TRUE.equals(v.nul())) obj.putNull(e.getKey());
            else obj.put(e.getKey(), v.toString());
        }
        return obj;
    }

    private MulticloudDbException unsupported(String msg) {
        return new MulticloudDbException(new MulticloudDbError(
                MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY,
                msg, ProviderId.DYNAMO, "readChanges", false, Map.of()));
    }

    private MulticloudDbException mapDynamoException(Exception e, String op) {
        if (e instanceof TrimmedDataAccessException) {
            return new MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.CHECKPOINT_EXPIRED,
                    "DynamoDB stream data has been trimmed (24h retention exceeded). "
                            + "Restart from beginning() or now(). Original: " + e.getMessage(),
                    ProviderId.DYNAMO, op, false, Map.of()), e);
        }
        return new MulticloudDbException(new MulticloudDbError(
                MulticloudDbErrorCategory.PROVIDER_ERROR,
                "DynamoDB Streams error: " + e.getMessage(),
                ProviderId.DYNAMO, op, false,
                Map.of("exceptionType", e.getClass().getName())), e);
    }

    private record ShardCursor(String lastSeq, boolean drained) { }

    private record ShardSelection(List<Shard> shardsToRead, Map<String, ShardCursor> cursors) { }
}

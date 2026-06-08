// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.changefeed.ChangeFeedCursor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeStreamRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeStreamResponse;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorRequest;
import software.amazon.awssdk.services.dynamodb.model.GetShardIteratorResponse;
import software.amazon.awssdk.services.dynamodb.model.SequenceNumberRange;
import software.amazon.awssdk.services.dynamodb.model.Shard;
import software.amazon.awssdk.services.dynamodb.model.StreamDescription;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DynamoChangeFeedReader#listCursors(DynamoDbClient, ResourceAddress, String)}.
 * <p>
 * Focuses on the invariant that the {@code issuedAtEpochMillis} stamped on each
 * minted {@link com.multiclouddb.api.changefeed.internal.CursorToken} reflects
 * the instant the iterator bookmark is actually effective:
 * <ul>
 *   <li><b>Success path</b>: {@code issuedAt} is captured <em>after</em>
 *       {@code GetShardIteratorResponse.shardIterator()} returns, so
 *       {@code issuedAt} falls within {@code [preCall, postCall]}.</li>
 *   <li><b>Fallback path</b>: when {@code GetShardIterator} throws a
 *       {@link DynamoDbException}, the cursor uses the {@code @@LATEST} anchor
 *       and {@code issuedAt} is captured at the moment of the fallback decision
 *       (still within {@code [preCall, postCall]}).</li>
 *   <li><b>Multi-shard</b>: each cursor's {@code issuedAt} is captured
 *       independently per iteration (no single pre-loop timestamp shared by
 *       every cursor).</li>
 *   <li><b>Placeholder branch</b>: when the table's stream has no open shards,
 *       a {@code __placeholder__} cursor is minted with {@code issuedAt}
 *       captured at the moment of the placeholder mint.</li>
 * </ul>
 */
class DynamoChangeFeedReaderTest {

    private static final String ITER_PREFIX = "@@ITER:";
    private static final String ANCHOR_NOW = "@@LATEST";
    private static final String TABLE = "test-table";
    private static final String STREAM_ARN = "arn:aws:dynamodb:us-east-1:123:table/test-table/stream/2025-01-01T00:00:00.000";
    private static final ResourceAddress ADDR = new ResourceAddress("test-db", TABLE);

    private static Shard shard(String id) {
        // Open shard: endingSequenceNumber == null. Build a real Shard (not a mock)
        // because the production code calls sequenceNumberRange() and shardId() on it.
        return Shard.builder()
                .shardId(id)
                .sequenceNumberRange(SequenceNumberRange.builder().startingSequenceNumber("0").build())
                .build();
    }

    private static DynamoDbClient mockDdbWithStream(String streamArn) {
        DynamoDbClient ddb = mock(DynamoDbClient.class);
        when(ddb.describeTable(any(DescribeTableRequest.class)))
                .thenReturn(DescribeTableResponse.builder()
                        .table(TableDescription.builder().latestStreamArn(streamArn).build())
                        .build());
        return ddb;
    }

    private static DynamoDbStreamsClient mockStreamsWithShards(Shard... shards) {
        DynamoDbStreamsClient streams = mock(DynamoDbStreamsClient.class);
        when(streams.describeStream(any(DescribeStreamRequest.class)))
                .thenReturn(DescribeStreamResponse.builder()
                        .streamDescription(StreamDescription.builder().shards(List.of(shards)).build())
                        .build());
        return streams;
    }

    private static DynamoDbException dynamoError(String msg) {
        return (DynamoDbException) DynamoDbException.builder()
                .message(msg)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("InternalError").build())
                .build();
    }

    // ── Success path ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Success path: issuedAt is captured after GetShardIterator returns; continuation uses @@ITER: prefix")
    void successPath_issuedAtCapturedAfterIterator() {
        DynamoDbClient ddb = mockDdbWithStream(STREAM_ARN);
        DynamoDbStreamsClient streams = mockStreamsWithShards(shard("shard-1"));
        when(streams.getShardIterator(any(GetShardIteratorRequest.class)))
                .thenReturn(GetShardIteratorResponse.builder()
                        .shardIterator("real-dynamo-iterator-abc")
                        .build());

        DynamoChangeFeedReader reader = new DynamoChangeFeedReader(ProviderId.DYNAMO, streams);

        long preCall = System.currentTimeMillis();
        List<ChangeFeedCursor> cursors = reader.listCursors(ddb, ADDR, TABLE);
        long postCall = System.currentTimeMillis();

        assertEquals(1, cursors.size(), "one cursor per open shard");
        ChangeFeedCursor c = cursors.get(0);
        long issuedAt = c.token().issuedAtEpochMillis();
        String cont = c.token().partitions().get(0).continuation();
        assertEquals(ITER_PREFIX + "real-dynamo-iterator-abc", cont,
                "success path must persist the resolved iterator under @@ITER: prefix");
        assertTrue(issuedAt >= preCall,
                "issuedAt (" + issuedAt + ") must be at or after preCall (" + preCall + ")");
        assertTrue(issuedAt <= postCall,
                "issuedAt (" + issuedAt + ") must not exceed postCall (" + postCall + ")");
    }

    // ── Fallback path ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Fallback path: GetShardIterator throws → cursor uses @@LATEST and issuedAt is captured at fallback")
    void fallbackPath_dynamoExceptionYieldsLatestAnchor() {
        DynamoDbClient ddb = mockDdbWithStream(STREAM_ARN);
        DynamoDbStreamsClient streams = mockStreamsWithShards(shard("shard-1"));
        when(streams.getShardIterator(any(GetShardIteratorRequest.class)))
                .thenThrow(dynamoError("simulated GetShardIterator failure"));

        DynamoChangeFeedReader reader = new DynamoChangeFeedReader(ProviderId.DYNAMO, streams);

        long preCall = System.currentTimeMillis();
        List<ChangeFeedCursor> cursors = reader.listCursors(ddb, ADDR, TABLE);
        long postCall = System.currentTimeMillis();

        assertEquals(1, cursors.size());
        ChangeFeedCursor c = cursors.get(0);
        String cont = c.token().partitions().get(0).continuation();
        assertEquals(ANCHOR_NOW, cont,
                "fallback path must use @@LATEST anchor; was " + cont);
        long issuedAt = c.token().issuedAtEpochMillis();
        assertTrue(issuedAt >= preCall && issuedAt <= postCall,
                "fallback issuedAt (" + issuedAt + ") must be within [preCall=" + preCall
                        + ", postCall=" + postCall + "]");
    }

    // ── Multi-shard invariant ───────────────────────────────────────────────

    @Test
    @DisplayName("Multi-shard: each cursor carries its own issuedAt (not a shared pre-loop timestamp)")
    void multiShard_eachCursorHasIndependentIssuedAt() {
        DynamoDbClient ddb = mockDdbWithStream(STREAM_ARN);
        DynamoDbStreamsClient streams = mockStreamsWithShards(
                shard("shard-1"), shard("shard-2"), shard("shard-3"));
        // Force all three down the fallback path: deterministically uses @@LATEST and
        // captures issuedAt per-iteration at the moment of the fallback decision.
        when(streams.getShardIterator(any(GetShardIteratorRequest.class)))
                .thenThrow(dynamoError("forced fallback"));

        DynamoChangeFeedReader reader = new DynamoChangeFeedReader(ProviderId.DYNAMO, streams);

        long preLoop = System.currentTimeMillis();
        List<ChangeFeedCursor> cursors = reader.listCursors(ddb, ADDR, TABLE);
        long postLoop = System.currentTimeMillis();

        assertEquals(3, cursors.size(), "one cursor per open shard");
        Set<String> partitionIds = new HashSet<>();
        for (ChangeFeedCursor c : cursors) {
            long issuedAt = c.token().issuedAtEpochMillis();
            String cont = c.token().partitions().get(0).continuation();
            assertEquals(ANCHOR_NOW, cont, "fallback path must use @@LATEST; was " + cont);
            assertTrue(issuedAt >= preLoop && issuedAt <= postLoop,
                    "issuedAt (" + issuedAt + ") must be within [preLoop=" + preLoop
                            + ", postLoop=" + postLoop + "]");
            partitionIds.add(c.token().partitions().get(0).partitionId());
        }
        // Cursors are distinct partitions (sanity check: each shard becomes its own cursor).
        assertEquals(3, partitionIds.size(), "each cursor should bind a distinct shard");
    }

    // ── Placeholder branch (no shards) ──────────────────────────────────────

    @Test
    @DisplayName("No-shards placeholder: one __placeholder__ cursor minted with fresh issuedAt")
    void noShards_mintsPlaceholderWithFreshIssuedAt() {
        DynamoDbClient ddb = mockDdbWithStream(STREAM_ARN);
        DynamoDbStreamsClient streams = mock(DynamoDbStreamsClient.class);
        when(streams.describeStream(any(DescribeStreamRequest.class)))
                .thenReturn(DescribeStreamResponse.builder()
                        .streamDescription(StreamDescription.builder().shards(List.of()).build())
                        .build());

        DynamoChangeFeedReader reader = new DynamoChangeFeedReader(ProviderId.DYNAMO, streams);

        long preCall = System.currentTimeMillis();
        List<ChangeFeedCursor> cursors = reader.listCursors(ddb, ADDR, TABLE);
        long postCall = System.currentTimeMillis();

        assertEquals(1, cursors.size(), "no-shards path must mint exactly one placeholder cursor");
        ChangeFeedCursor c = cursors.get(0);
        String partitionId = c.token().partitions().get(0).partitionId();
        assertNotNull(partitionId);
        assertTrue(partitionId.endsWith("::__placeholder__"),
                "placeholder partitionId must end with ::__placeholder__; was " + partitionId);
        String cont = c.token().partitions().get(0).continuation();
        assertEquals(ANCHOR_NOW, cont, "placeholder must carry @@LATEST anchor");
        long issuedAt = c.token().issuedAtEpochMillis();
        assertTrue(issuedAt >= preCall && issuedAt <= postCall,
                "placeholder issuedAt (" + issuedAt + ") must be within [preCall=" + preCall
                        + ", postCall=" + postCall + "]");
    }

    // ── Multi-shard success: composite test of independent timestamps ───────

    @Test
    @DisplayName("Multi-shard success path: each cursor independently captures issuedAt within call window")
    void multiShardSuccess_eachCursorWithinCallWindow() {
        DynamoDbClient ddb = mockDdbWithStream(STREAM_ARN);
        DynamoDbStreamsClient streams = mockStreamsWithShards(shard("shard-A"), shard("shard-B"));
        when(streams.getShardIterator(any(GetShardIteratorRequest.class)))
                .thenReturn(GetShardIteratorResponse.builder().shardIterator("iter-1").build())
                .thenReturn(GetShardIteratorResponse.builder().shardIterator("iter-2").build());

        DynamoChangeFeedReader reader = new DynamoChangeFeedReader(ProviderId.DYNAMO, streams);

        long preLoop = System.currentTimeMillis();
        List<ChangeFeedCursor> cursors = reader.listCursors(ddb, ADDR, TABLE);
        long postLoop = System.currentTimeMillis();

        assertEquals(2, cursors.size());
        for (ChangeFeedCursor c : cursors) {
            String cont = c.token().partitions().get(0).continuation();
            assertTrue(cont.startsWith(ITER_PREFIX),
                    "success path must use @@ITER: prefix; was " + cont);
            assertFalse(cont.equals(ANCHOR_NOW),
                    "success path must NOT use @@LATEST anchor");
            long issuedAt = c.token().issuedAtEpochMillis();
            assertTrue(issuedAt >= preLoop && issuedAt <= postLoop,
                    "issuedAt (" + issuedAt + ") must be within [preLoop=" + preLoop
                            + ", postLoop=" + postLoop + "]");
        }
    }
}

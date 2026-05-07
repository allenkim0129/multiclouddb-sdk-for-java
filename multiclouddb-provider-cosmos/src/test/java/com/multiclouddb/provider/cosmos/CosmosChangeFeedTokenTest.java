// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.fasterxml.jackson.databind.node.TextNode;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.changefeed.ChangeFeedRequest;
import com.multiclouddb.api.changefeed.FeedScope;
import com.multiclouddb.api.changefeed.StartPosition;
import com.multiclouddb.api.changefeed.internal.ContinuationTokenCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CosmosChangeFeed#decodeAndValidateResumeToken}.
 *
 * <p>Covers the round-4 fix that promotes the Cosmos token wrapper from a
 * bare cursor string to {@code {cursor, scope, partitionValue}} and rejects
 * scope-kind / partition-value mismatches on resume (parity with the
 * Spanner and Dynamo providers). Also pins the legacy bare-{@code TextNode}
 * decode path so a future codec refactor can't silently break resumes
 * issued by older SDK versions.
 */
class CosmosChangeFeedTokenTest {

    private static final ResourceAddress ADDR = new ResourceAddress("db", "col");

    private static String encodeEnvelope(String cursor, FeedScope scope) {
        return ContinuationTokenCodec.encode(ProviderId.COSMOS, ADDR,
                CosmosChangeFeed.buildCursorEnvelopeForTest(cursor, scope));
    }

    private static String encodeLegacyBareString(String cursor) {
        return ContinuationTokenCodec.encode(ProviderId.COSMOS, ADDR, new TextNode(cursor));
    }

    private static ChangeFeedRequest resumeWith(String token, FeedScope scope) {
        return ChangeFeedRequest.builder(ADDR)
                .scope(scope)
                .startPosition(StartPosition.fromContinuationToken(token))
                .build();
    }

    @Test
    @DisplayName("Resume with the same scope returns the wrapped Cosmos cursor unchanged")
    void resumeSameScopeReturnsCursor() {
        String token = encodeEnvelope("cosmos-native-cursor-X", FeedScope.entireCollection());
        ChangeFeedRequest req = resumeWith(token, FeedScope.entireCollection());

        String cursor = CosmosChangeFeed.decodeAndValidateResumeToken(token, req);

        assertEquals("cosmos-native-cursor-X", cursor);
    }

    @Test
    @DisplayName("Resume with a different scope KIND is rejected as INVALID_REQUEST")
    void resumeWithDifferentScopeKindRejected() {
        // Token issued for EntireCollection, resume requested under PhysicalPartition.
        String token = encodeEnvelope("cosmos-cursor", FeedScope.entireCollection());
        ChangeFeedRequest req = resumeWith(token, FeedScope.physicalPartition("partition-A"));

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> CosmosChangeFeed.decodeAndValidateResumeToken(token, req));

        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
        String msg = ex.error().message();
        assertTrue(msg.contains("EntireCollection"), "message should name the token's scope, was: " + msg);
        assertTrue(msg.contains("PhysicalPartition"), "message should name the request scope, was: " + msg);
    }

    @Test
    @DisplayName("Resume with the same scope KIND but a different PARTITION value is rejected")
    void resumeWithDifferentPartitionValueRejected() {
        // Token issued for PhysicalPartition('partition-A'), resume on 'partition-B'.
        String token = encodeEnvelope("cosmos-cursor", FeedScope.physicalPartition("partition-A"));
        ChangeFeedRequest req = resumeWith(token, FeedScope.physicalPartition("partition-B"));

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> CosmosChangeFeed.decodeAndValidateResumeToken(token, req));

        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
        String msg = ex.error().message();
        assertTrue(msg.contains("partition-A"), "message should name token partition, was: " + msg);
        assertTrue(msg.contains("partition-B"), "message should name request partition, was: " + msg);
    }

    @Test
    @DisplayName("Resume with the same kind+partition value (PhysicalPartition) is accepted")
    void resumeSamePhysicalPartitionAccepted() {
        String token = encodeEnvelope("cosmos-cursor", FeedScope.physicalPartition("partition-A"));
        ChangeFeedRequest req = resumeWith(token, FeedScope.physicalPartition("partition-A"));

        assertEquals("cosmos-cursor",
                CosmosChangeFeed.decodeAndValidateResumeToken(token, req));
    }

    @Test
    @DisplayName("LogicalPartition resume validates the partition-key value, not just the kind")
    void resumeLogicalPartitionValueMismatchRejected() {
        String token = encodeEnvelope("cosmos-cursor",
                FeedScope.logicalPartition(MulticloudDbKey.of("tenant-1")));
        ChangeFeedRequest req = resumeWith(token,
                FeedScope.logicalPartition(MulticloudDbKey.of("tenant-2")));

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> CosmosChangeFeed.decodeAndValidateResumeToken(token, req));

        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
        String msg = ex.error().message();
        assertTrue(msg.contains("tenant-1") && msg.contains("tenant-2"),
                "message should name both partition values, was: " + msg);
    }

    @Test
    @DisplayName("Legacy bare-TextNode token (no envelope) decodes and bypasses scope validation")
    void legacyBareStringTokenDecodes() {
        // Older SDK versions encoded the cursor directly as a TextNode. The
        // resume path must remain backward compatible: the legacy token has
        // no scope marker, so the scope check is bypassed and the cursor is
        // returned unchanged.
        String token = encodeLegacyBareString("legacy-cursor");
        // Even an explicit non-default scope must not fail for legacy tokens.
        ChangeFeedRequest req = resumeWith(token, FeedScope.physicalPartition("partition-A"));

        assertEquals("legacy-cursor",
                CosmosChangeFeed.decodeAndValidateResumeToken(token, req));
    }

    @Test
    @DisplayName("Envelope with empty cursor field is rejected as INVALID_REQUEST")
    void envelopeWithEmptyCursorRejected() {
        String token = encodeEnvelope("", FeedScope.entireCollection());
        ChangeFeedRequest req = resumeWith(token, FeedScope.entireCollection());

        MulticloudDbException ex = assertThrows(MulticloudDbException.class,
                () -> CosmosChangeFeed.decodeAndValidateResumeToken(token, req));

        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, ex.error().category());
        assertTrue(ex.error().message().contains("Cosmos cursor"),
                "message should mention the missing Cosmos cursor, was: " + ex.error().message());
    }
}

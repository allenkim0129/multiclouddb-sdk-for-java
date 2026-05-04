// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed;

import com.multiclouddb.api.MulticloudDbKey;

import java.util.Objects;

/**
 * Defines what slice of a collection's change feed a {@code readChanges} call
 * should consume.
 * <p>
 * Three variants:
 * <ul>
 *   <li>{@link EntireCollection} (3/3 providers) — the SDK fans out across all
 *       physical partitions internally; per-partition order is preserved but
 *       no global ordering is guaranteed. Default if no scope is specified.</li>
 *   <li>{@link PhysicalPartition} (3/3 providers) — consume one provider-native
 *       partition (Cosmos {@code FeedRange}, Dynamo shard, Spanner partition
 *       token). Discover IDs via
 *       {@link com.multiclouddb.api.MulticloudDbClient#listPhysicalPartitions(
 *       com.multiclouddb.api.ResourceAddress, com.multiclouddb.api.OperationOptions)}.
 *       The {@code partitionId} string is opaque and provider-scoped — it is
 *       meaningful only against the same provider+resource that produced it.</li>
 *   <li>{@link LogicalPartition} (Cosmos only — gated by
 *       {@link com.multiclouddb.api.Capability#CHANGE_FEED_LOGICAL_PARTITION_SCOPE})
 *       — filter the feed to events whose document partition key matches the
 *       given {@link MulticloudDbKey}. Throws
 *       {@link com.multiclouddb.api.MulticloudDbErrorCategory#UNSUPPORTED_CAPABILITY}
 *       on Dynamo and Spanner.</li>
 * </ul>
 *
 * <p>This is a <em>sealed</em> interface — no third-party implementations.
 * Use {@code instanceof} pattern matching:
 * <pre>{@code
 * switch (request.scope()) {
 *     case FeedScope.EntireCollection ec -> ...
 *     case FeedScope.PhysicalPartition(String id) -> ...
 *     case FeedScope.LogicalPartition(MulticloudDbKey key) -> ...
 * }
 * }</pre>
 */
public sealed interface FeedScope
        permits FeedScope.EntireCollection,
                FeedScope.PhysicalPartition,
                FeedScope.LogicalPartition {

    /** Read every change in the collection across every partition (default). */
    static FeedScope entireCollection() {
        return EntireCollection.INSTANCE;
    }

    /** Read changes from a single provider-native physical partition. */
    static FeedScope physicalPartition(String partitionId) {
        return new PhysicalPartition(partitionId);
    }

    /**
     * Filter the feed to a single logical partition key (Cosmos only).
     * Gated by {@link com.multiclouddb.api.Capability#CHANGE_FEED_LOGICAL_PARTITION_SCOPE}.
     */
    static FeedScope logicalPartition(MulticloudDbKey key) {
        return new LogicalPartition(key);
    }

    /** Entire-collection scope (singleton). */
    final class EntireCollection implements FeedScope {
        static final EntireCollection INSTANCE = new EntireCollection();

        private EntireCollection() {
        }

        @Override
        public String toString() {
            return "FeedScope.EntireCollection";
        }
    }

    /**
     * One provider-native physical partition. {@code partitionId} is opaque and
     * meaningful only against the same provider+resource that produced it.
     */
    record PhysicalPartition(String partitionId) implements FeedScope {
        public PhysicalPartition {
            Objects.requireNonNull(partitionId, "partitionId");
            if (partitionId.isBlank()) {
                throw new IllegalArgumentException("partitionId must be non-blank");
            }
        }
    }

    /** Single logical partition key (Cosmos only — capability-gated). */
    record LogicalPartition(MulticloudDbKey partitionKey) implements FeedScope {
        public LogicalPartition {
            Objects.requireNonNull(partitionKey, "partitionKey");
        }
    }
}

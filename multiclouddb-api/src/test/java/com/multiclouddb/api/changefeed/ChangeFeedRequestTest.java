// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed;

import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.ResourceAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ChangeFeedRequest}, {@link FeedScope}, and
 * {@link StartPosition} — sealed-type behavior and builder defaults.
 */
class ChangeFeedRequestTest {

    private static final ResourceAddress ADDR = new ResourceAddress("db", "col");

    @Test
    @DisplayName("builder defaults: entire-collection + beginning + INCLUDE_IF_AVAILABLE")
    void builderDefaults() {
        ChangeFeedRequest r = ChangeFeedRequest.builder(ADDR).build();
        assertEquals(ADDR, r.address());
        assertTrue(r.scope() instanceof FeedScope.EntireCollection);
        assertTrue(r.startPosition() instanceof StartPosition.Beginning);
        assertEquals(NewItemStateMode.INCLUDE_IF_AVAILABLE, r.newItemStateMode());
        assertEquals(0, r.maxPageSize());
    }

    @Test
    @DisplayName("builder accepts physical-partition scope")
    void physicalPartitionScope() {
        ChangeFeedRequest r = ChangeFeedRequest.builder(ADDR)
                .scope(FeedScope.physicalPartition("pid-1"))
                .build();
        assertInstanceOf(FeedScope.PhysicalPartition.class, r.scope());
        assertEquals("pid-1", ((FeedScope.PhysicalPartition) r.scope()).partitionId());
    }

    @Test
    @DisplayName("FeedScope.physicalPartition rejects null/blank ids")
    void physicalPartitionRejectsBlank() {
        assertThrows(NullPointerException.class, () -> FeedScope.physicalPartition(null));
        assertThrows(IllegalArgumentException.class, () -> FeedScope.physicalPartition(""));
    }

    @Test
    @DisplayName("FeedScope.logicalPartition wraps the supplied key")
    void logicalPartitionScope() {
        FeedScope s = FeedScope.logicalPartition(MulticloudDbKey.of("pk1"));
        assertInstanceOf(FeedScope.LogicalPartition.class, s);
    }

    @Test
    @DisplayName("StartPosition factories return the expected variants")
    void startPositionFactories() {
        assertInstanceOf(StartPosition.Beginning.class, StartPosition.beginning());
        assertInstanceOf(StartPosition.Now.class, StartPosition.now());
        assertInstanceOf(StartPosition.AtTime.class, StartPosition.atTime(Instant.EPOCH));
        assertInstanceOf(StartPosition.FromContinuationToken.class,
                StartPosition.fromContinuationToken("abc"));
    }

    @Test
    @DisplayName("StartPosition.AtTime requires non-null timestamp")
    void atTimeRequiresTimestamp() {
        assertThrows(NullPointerException.class, () -> StartPosition.atTime(null));
    }

    @Test
    @DisplayName("StartPosition.FromContinuationToken rejects null/blank tokens")
    void fromTokenRejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> StartPosition.fromContinuationToken(null));
        assertThrows(IllegalArgumentException.class, () -> StartPosition.fromContinuationToken(""));
        assertThrows(IllegalArgumentException.class, () -> StartPosition.fromContinuationToken("   "));
    }

    @Test
    @DisplayName("ChangeFeedRequest.builder rejects negative maxPageSize")
    void rejectsNegativePageSize() {
        assertThrows(IllegalArgumentException.class,
                () -> ChangeFeedRequest.builder(ADDR).maxPageSize(-1));
    }
}

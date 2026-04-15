// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KeyTest {

    @Test
    void ofPartitionKeyOnly() {
        MulticloudDbKey key = MulticloudDbKey.of("pk1");
        assertEquals("pk1", key.partitionKey());
        assertNull(key.sortKey());
        assertTrue(key.components().isEmpty());
    }

    @Test
    void ofPartitionKeyAndSortKey() {
        MulticloudDbKey key = MulticloudDbKey.of("pk1", "abc");
        assertEquals("pk1", key.partitionKey());
        assertEquals("abc", key.sortKey());
    }

    @Test
    void nullPartitionKeyThrows() {
        assertThrows(IllegalArgumentException.class, () -> MulticloudDbKey.of(null));
    }

    @Test
    void equalKeys() {
        MulticloudDbKey k1 = MulticloudDbKey.of("a", "b");
        MulticloudDbKey k2 = MulticloudDbKey.of("a", "b");
        assertEquals(k1, k2);
        assertEquals(k1.hashCode(), k2.hashCode());
    }

    @Test
    void differentKeysNotEqual() {
        MulticloudDbKey k1 = MulticloudDbKey.of("a", "b");
        MulticloudDbKey k2 = MulticloudDbKey.of("a", "c");
        assertNotEquals(k1, k2);
    }
}

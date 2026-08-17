// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.Type;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.OperationNames;
import com.multiclouddb.api.OperationOptions;
import com.multiclouddb.api.PatchOperation;
import com.multiclouddb.api.ResourceAddress;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class SpannerPatchValidationTest {

    private static final ResourceAddress ADDRESS = new ResourceAddress("db", "table");
    private static final MulticloudDbKey KEY = MulticloudDbKey.of("pk", "sk");

    @Test
    void directSpiPatchRejectsReservedPathBeforeCallingSpanner() {
        Spanner spanner = mock(Spanner.class);
        DatabaseClient database = mock(DatabaseClient.class);
        SpannerProviderClient client = new SpannerProviderClient(spanner, database);

        MulticloudDbException error = assertThrows(MulticloudDbException.class,
                () -> client.patch(ADDRESS, KEY, List.of(PatchOperation.set("/data", "reserved")),
                        OperationOptions.defaults()));

        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, error.error().category());
        assertEquals(OperationNames.PATCH, error.error().operation());
        verifyNoInteractions(spanner, database);
    }

    @Test
    void physicalMirrorsRejectCrossTypeValuesButAcceptMatchingKinds() {
        assertTrue(SpannerProviderClient.isCompatibleWithColumn(1L, Type.int64()));
        assertFalse(SpannerProviderClient.isCompatibleWithColumn("1", Type.int64()));
        assertTrue(SpannerProviderClient.isCompatibleWithColumn(1.5d, Type.float64()));
        assertFalse(SpannerProviderClient.isCompatibleWithColumn(true, Type.float64()));
        assertTrue(SpannerProviderClient.isCompatibleWithColumn("text", Type.string()));
        assertFalse(SpannerProviderClient.isCompatibleWithColumn(1L, Type.string()));
    }
}

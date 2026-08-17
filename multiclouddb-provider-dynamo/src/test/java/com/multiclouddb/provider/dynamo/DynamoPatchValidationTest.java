// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.OperationNames;
import com.multiclouddb.api.OperationOptions;
import com.multiclouddb.api.PatchOperation;
import com.multiclouddb.api.ResourceAddress;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class DynamoPatchValidationTest {

    private static final ResourceAddress ADDRESS = new ResourceAddress("db", "table");
    private static final MulticloudDbKey KEY = MulticloudDbKey.of("pk", "sk");

    @Test
    void directSpiPatchRejectsNullListBeforeCallingDynamo() {
        DynamoDbClient dynamo = mock(DynamoDbClient.class);
        DynamoProviderClient client = new DynamoProviderClient(dynamo);
        List<PatchOperation> operations = null;

        MulticloudDbException error = assertThrows(MulticloudDbException.class,
                () -> client.patch(ADDRESS, KEY, operations, OperationOptions.defaults()));

        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, error.error().category());
        assertEquals(OperationNames.PATCH, error.error().operation());
        verifyNoInteractions(dynamo);
    }

    @Test
    void directSpiPatchRejectsNullEntriesBeforeCallingDynamo() {
        DynamoDbClient dynamo = mock(DynamoDbClient.class);
        DynamoProviderClient client = new DynamoProviderClient(dynamo);
        List<PatchOperation> operations = new ArrayList<>();
        operations.add(PatchOperation.set("/title", "before"));
        operations.add(null);

        MulticloudDbException error = assertThrows(MulticloudDbException.class,
                () -> client.patch(ADDRESS, KEY, operations, OperationOptions.defaults()));

        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, error.error().category());
        assertEquals(OperationNames.PATCH, error.error().operation());
        verifyNoInteractions(dynamo);
    }
}

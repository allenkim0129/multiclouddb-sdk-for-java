// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.ResourceAddress;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbResponseMetadata;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Characterizes native key placement without selecting a new payload layout. */
class DynamoKeyRoutingTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "shared-id")
    void writesAndPointOperationsUseTheSameTopLevelScalarKeys(String sortKey) {
        DynamoDbClient nativeClient = mock(DynamoDbClient.class);
        DynamoDbResponseMetadata metadata = mock(DynamoDbResponseMetadata.class);
        PutItemResponse put = mock(PutItemResponse.class);
        GetItemResponse get = mock(GetItemResponse.class);
        DeleteItemResponse delete = mock(DeleteItemResponse.class);
        when(put.responseMetadata()).thenReturn(metadata);
        when(get.responseMetadata()).thenReturn(metadata);
        when(delete.responseMetadata()).thenReturn(metadata);
        when(nativeClient.putItem(any(PutItemRequest.class))).thenReturn(put);
        when(nativeClient.getItem(any(GetItemRequest.class))).thenReturn(get);
        when(nativeClient.deleteItem(any(DeleteItemRequest.class))).thenReturn(delete);
        DynamoProviderClient client = new DynamoProviderClient(nativeClient);
        ResourceAddress address = new ResourceAddress("db", "records");
        Map<String, Object> payload = Map.of("document",
                Map.of("partitionKey", "business-partition", "sortKey", "business-id", "status", "OPEN"));
        for (String partition : new String[]{"account-a", "account-b"}) {
            MulticloudDbKey key = MulticloudDbKey.of(partition, sortKey);
            client.create(address, key, payload, null);
            client.update(address, key, payload, null);
            client.upsert(address, key, payload, null);
            assertNull(client.read(address, key, null));
            client.delete(address, key, null);
        }

        ArgumentCaptor<PutItemRequest> puts = ArgumentCaptor.forClass(PutItemRequest.class);
        ArgumentCaptor<GetItemRequest> gets = ArgumentCaptor.forClass(GetItemRequest.class);
        ArgumentCaptor<DeleteItemRequest> deletes = ArgumentCaptor.forClass(DeleteItemRequest.class);
        verify(nativeClient, times(6)).putItem(puts.capture());
        verify(nativeClient, times(2)).getItem(gets.capture());
        verify(nativeClient, times(2)).deleteItem(deletes.capture());
        for (int index = 0; index < 2; index++) {
            String partition = index == 0 ? "account-a" : "account-b";
            Map<String, AttributeValue> nativeKey = Map.of(
                    "partitionKey", AttributeValue.fromS(partition),
                    "sortKey", AttributeValue.fromS(sortKey == null ? partition : sortKey));
            List<PutItemRequest> writes = puts.getAllValues().subList(index * 3, index * 3 + 3);
            assertEquals("attribute_not_exists(partitionKey)", writes.get(0).conditionExpression());
            assertEquals("attribute_exists(partitionKey)", writes.get(1).conditionExpression());
            assertNull(writes.get(2).conditionExpression());
            for (PutItemRequest write : writes) {
                assertEquals("db__records", write.tableName());
                nativeKey.forEach((name, value) -> assertEquals(value, write.item().get(name)));
                Map<String, AttributeValue> business = write.item().get("document").m();
                assertEquals(AttributeValue.fromS("business-partition"), business.get("partitionKey"));
                assertEquals(AttributeValue.fromS("business-id"), business.get("sortKey"));
                assertEquals(AttributeValue.fromS("OPEN"), business.get("status"));
            }
            assertEquals("db__records", gets.getAllValues().get(index).tableName());
            assertEquals(nativeKey, gets.getAllValues().get(index).key());
            assertEquals("db__records", deletes.getAllValues().get(index).tableName());
            assertEquals(nativeKey, deletes.getAllValues().get(index).key());
        }
        assertEquals(1, payload.size(), "native key injection must not mutate caller input");
        verifyNoMoreInteractions(nativeClient);
    }
}

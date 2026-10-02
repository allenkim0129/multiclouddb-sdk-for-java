// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import com.fasterxml.jackson.databind.node.ObjectNode;
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

import java.util.HashMap;
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
        Map<String, Object> businessSnapshot =
                Map.of("partitionKey", "business-partition", "sortKey", "business-id", "status", "OPEN");
        Map<String, Object> snapshot = Map.of("document", businessSnapshot,
                "partitionKey", "conflicting-partition", "sortKey", "conflicting-id");
        Map<String, Object> payload = new HashMap<>(snapshot);
        payload.put("document", new HashMap<>(businessSnapshot));
        for (String partition : new String[]{"account-a", "account-b"}) {
            MulticloudDbKey key = MulticloudDbKey.of(partition, sortKey);
            String id = sortKey == null ? partition : sortKey;
            client.create(address, key, payload, null);
            assertEquals(snapshot, payload);
            client.update(address, key, payload, null);
            assertEquals(snapshot, payload);
            client.upsert(address, key, payload, null);
            assertEquals(snapshot, payload);
            when(get.hasItem()).thenReturn(false);
            assertNull(client.read(address, key, null));
            Map<String, AttributeValue> nativeItem = Map.of(
                    "partitionKey", AttributeValue.fromS(partition),
                    "sortKey", AttributeValue.fromS(id),
                    "document", AttributeValue.fromM(Map.of(
                            "partitionKey", AttributeValue.fromS("business-partition"),
                            "sortKey", AttributeValue.fromS("business-id"),
                            "status", AttributeValue.fromS("OPEN"))));
            when(get.hasItem()).thenReturn(true);
            when(get.item()).thenReturn(nativeItem);
            ObjectNode returned = client.read(address, key, null).document();
            assertEquals(3, returned.size());
            assertEquals(partition, returned.get("partitionKey").textValue());
            assertEquals(id, returned.get("sortKey").textValue());
            assertEquals(3, returned.get("document").size());
            assertEquals("business-partition", returned.at("/document/partitionKey").textValue());
            assertEquals("business-id", returned.at("/document/sortKey").textValue());
            assertEquals("OPEN", returned.at("/document/status").textValue());
            client.delete(address, key, null);
        }

        ArgumentCaptor<PutItemRequest> puts = ArgumentCaptor.forClass(PutItemRequest.class);
        ArgumentCaptor<GetItemRequest> gets = ArgumentCaptor.forClass(GetItemRequest.class);
        ArgumentCaptor<DeleteItemRequest> deletes = ArgumentCaptor.forClass(DeleteItemRequest.class);
        verify(nativeClient, times(6)).putItem(puts.capture());
        verify(nativeClient, times(4)).getItem(gets.capture());
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
            for (GetItemRequest request : gets.getAllValues().subList(index * 2, index * 2 + 2)) {
                assertEquals("db__records", request.tableName());
                assertEquals(nativeKey, request.key());
            }
            assertEquals("db__records", deletes.getAllValues().get(index).tableName());
            assertEquals(nativeKey, deletes.getAllValues().get(index).key());
        }
        assertEquals(snapshot, payload, "native key injection must not mutate caller input");
        verifyNoMoreInteractions(nativeClient);
    }
}

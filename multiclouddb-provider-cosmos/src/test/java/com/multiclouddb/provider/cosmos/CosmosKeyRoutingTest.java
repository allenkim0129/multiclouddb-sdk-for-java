// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosDatabase;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.CosmosItemResponse;
import com.azure.cosmos.models.PartitionKey;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Characterizes current flat-storage routing, not an approved future envelope. */
class CosmosKeyRoutingTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "shared-id")
    @SuppressWarnings("unchecked")
    void writesAndPointOperationsUseTheSameNativeIdentity(String sortKey) {
        CosmosClient nativeClient = mock(CosmosClient.class);
        CosmosDatabase database = mock(CosmosDatabase.class);
        CosmosContainer container = mock(CosmosContainer.class);
        when(nativeClient.getDatabase("db")).thenReturn(database);
        when(database.getContainer("records")).thenReturn(container);
        CosmosItemResponse<ObjectNode> response = mock(CosmosItemResponse.class);
        when(container.createItem(any(ObjectNode.class), any(PartitionKey.class),
                any(CosmosItemRequestOptions.class))).thenReturn(response);
        when(container.replaceItem(any(ObjectNode.class), anyString(), any(PartitionKey.class),
                any(CosmosItemRequestOptions.class))).thenReturn(response);
        when(container.upsertItem(any(ObjectNode.class), any(PartitionKey.class),
                any(CosmosItemRequestOptions.class))).thenReturn(response);
        when(container.readItem(anyString(), any(PartitionKey.class),
                any(CosmosItemRequestOptions.class), eq(ObjectNode.class))).thenReturn(response);
        when(container.deleteItem(anyString(), any(PartitionKey.class),
                any(CosmosItemRequestOptions.class))).thenReturn(mock(CosmosItemResponse.class));

        try (MockedConstruction<CosmosClientBuilder> ignored = mockConstruction(
                CosmosClientBuilder.class, withSettings().defaultAnswer(RETURNS_SELF),
                (builder, context) -> when(builder.buildClient()).thenReturn(nativeClient))) {
            CosmosProviderClient client = new CosmosProviderClient(MulticloudDbClientConfig.builder()
                    .provider(ProviderId.COSMOS)
                    .connection("endpoint", "https://example.invalid:443")
                    .connection("key", "test-only")
                    .build());
            ResourceAddress address = new ResourceAddress("db", "records");
            // The nested object is ordinary input data; the provider does not add an envelope.
            Map<String, Object> businessSnapshot =
                    Map.of("id", "business-id", "partitionKey", "business-partition", "status", "OPEN");
            Map<String, Object> snapshot = Map.of("document", businessSnapshot,
                    "id", "conflicting-id", "partitionKey", "conflicting-partition");
            Map<String, Object> payload = new HashMap<>(snapshot);
            payload.put("document", new HashMap<>(businessSnapshot));
            for (String partition : new String[]{"account-a", "account-b"}) {
                MulticloudDbKey key = MulticloudDbKey.of(partition, sortKey);
                String id = sortKey == null ? partition : sortKey;
                PartitionKey nativePartition = new PartitionKey(partition);
                client.create(address, key, payload, null);
                assertEquals(snapshot, payload);
                client.update(address, key, payload, null);
                assertEquals(snapshot, payload);
                client.upsert(address, key, payload, null);
                assertEquals(snapshot, payload);
                when(response.getItem()).thenReturn(null);
                assertNull(client.read(address, key, null));
                client.delete(address, key, null);

                ArgumentCaptor<ObjectNode> created = ArgumentCaptor.forClass(ObjectNode.class);
                ArgumentCaptor<ObjectNode> replaced = ArgumentCaptor.forClass(ObjectNode.class);
                ArgumentCaptor<ObjectNode> upserted = ArgumentCaptor.forClass(ObjectNode.class);
                verify(container).createItem(created.capture(), eq(nativePartition), any());
                verify(container).replaceItem(replaced.capture(), eq(id), eq(nativePartition), any());
                verify(container).upsertItem(upserted.capture(), eq(nativePartition), any());
                for (ObjectNode stored : new ObjectNode[]{created.getValue(), replaced.getValue(), upserted.getValue()}) {
                    assertEquals(id, stored.get("id").textValue());
                    assertEquals(partition, stored.get("partitionKey").textValue());
                    assertEquals("business-id", stored.at("/document/id").textValue());
                    assertEquals("business-partition", stored.at("/document/partitionKey").textValue());
                    assertEquals("OPEN", stored.at("/document/status").textValue());
                }
                ObjectNode nativeItem = created.getValue().deepCopy();
                nativeItem.put("_etag", "native-version");
                ObjectNode nativeSnapshot = nativeItem.deepCopy();
                when(response.getItem()).thenReturn(nativeItem);
                ObjectNode returned = client.read(address, key, null).document();
                ObjectNode expected = nativeSnapshot.deepCopy();
                expected.remove(java.util.List.of("id", "partitionKey", "_etag"));
                assertEquals(expected, returned, "current Cosmos reads strip native root identity and system fields");
                assertEquals(nativeSnapshot, nativeItem, "read mapping must not mutate the native response");
                verify(container, times(2)).readItem(eq(id), eq(nativePartition), any(), eq(ObjectNode.class));
                verify(container).deleteItem(eq(id), eq(nativePartition), any());
            }
            assertEquals(snapshot, payload, "native key injection must not mutate caller input");
            verifyNoMoreInteractions(container);
        }
    }
}

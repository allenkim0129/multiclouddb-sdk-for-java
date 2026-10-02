// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.DatabaseId;
import com.google.cloud.spanner.Key;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.ReadContext;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerOptions;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.Struct;
import com.google.cloud.spanner.TransactionContext;
import com.google.cloud.spanner.TransactionRunner;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises current column-based CRUD; does not claim native JSON payload support. */
class SpannerKeyRoutingTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "shared-id")
    void writesReadsAndTransactionalUpdatesUseTheSamePrimaryKeyColumns(String sortKey) {
        SpannerOptions.Builder builder = mock(SpannerOptions.Builder.class, RETURNS_SELF);
        SpannerOptions options = mock(SpannerOptions.class);
        Spanner spanner = mock(Spanner.class);
        DatabaseClient database = mock(DatabaseClient.class);
        when(builder.build()).thenReturn(options);
        when(options.getService()).thenReturn(spanner);
        when(spanner.getDatabaseClient(DatabaseId.of("test-project", "test-instance", "configured-db")))
                .thenReturn(database);
        ReadContext read = mock(ReadContext.class);
        ResultSet rows = mock(ResultSet.class);
        when(database.singleUse()).thenReturn(read);
        when(read.executeQuery(any(Statement.class))).thenReturn(rows);
        TransactionRunner runner = mock(TransactionRunner.class);
        TransactionContext transaction = mock(TransactionContext.class);
        when(database.readWriteTransaction()).thenReturn(runner);
        when(transaction.readRow(eq("records"), any(Key.class), eq(List.of("data"))))
                .thenReturn(Struct.newBuilder().set("data").to("[\"status\"]").build());
        when(runner.run(any())).thenAnswer(invocation -> {
            TransactionRunner.TransactionCallable<Object> callback = invocation.getArgument(0);
            return callback.run(transaction);
        });
        List<Mutation> writes = new ArrayList<>();
        when(database.write(any())).thenAnswer(invocation -> {
            Iterable<Mutation> mutations = invocation.getArgument(0);
            mutations.forEach(writes::add);
            return null;
        });
        List<Mutation> updates = new ArrayList<>();
        doAnswer(invocation -> {
            updates.add(invocation.getArgument(0));
            return null;
        }).when(transaction).buffer(any(Mutation.class));

        try (MockedStatic<SpannerOptions> factory = mockStatic(SpannerOptions.class)) {
            factory.when(SpannerOptions::newBuilder).thenReturn(builder);
            SpannerProviderClient client = new SpannerProviderClient(MulticloudDbClientConfig.builder()
                    .provider(ProviderId.SPANNER)
                    .connection("projectId", "test-project")
                    .connection("instanceId", "test-instance")
                    .connection("databaseId", "configured-db")
                    .connection("emulatorHost", "localhost:1")
                    .build());
            ResourceAddress address = new ResourceAddress("logical-db", "records");
            Map<String, Object> payload = Map.of("status", "OPEN");
            for (String partition : new String[]{"account-a", "account-b"}) {
                MulticloudDbKey key = MulticloudDbKey.of(partition, sortKey);
                client.create(address, key, payload, null);
                client.update(address, key, payload, null);
                client.upsert(address, key, payload, null);
                assertNull(client.read(address, key, null));
                client.delete(address, key, null);
            }
        }
        assertEquals(6, writes.size());
        assertEquals(2, updates.size());
        ArgumentCaptor<Statement> queries = ArgumentCaptor.forClass(Statement.class);
        verify(read, times(2)).executeQuery(queries.capture());
        for (int index = 0; index < 2; index++) {
            String partition = index == 0 ? "account-a" : "account-b";
            String id = sortKey == null ? partition : sortKey;
            Mutation created = writes.get(index * 3);
            Mutation upserted = writes.get(index * 3 + 1);
            Mutation deleted = writes.get(index * 3 + 2);
            Mutation updated = updates.get(index);
            assertEquals(Mutation.Op.INSERT, created.getOperation());
            assertEquals(Mutation.Op.INSERT_OR_UPDATE, upserted.getOperation());
            assertEquals(Mutation.Op.UPDATE, updated.getOperation());
            for (Mutation write : List.of(created, updated, upserted)) {
                assertEquals("records", write.getTable());
                assertEquals(partition, write.asMap().get("partitionKey").getString());
                assertEquals(id, write.asMap().get("sortKey").getString());
                assertEquals("OPEN", write.asMap().get("status").getString());
            }
            assertEquals(Mutation.Op.DELETE, deleted.getOperation());
            assertEquals("records", deleted.getTable());
            List<Key> deletedKeys = new ArrayList<>();
            deleted.getKeySet().getKeys().forEach(deletedKeys::add);
            assertEquals(List.of(Key.of(partition, id)), deletedKeys);
            verify(transaction).readRow("records", Key.of(partition, id), List.of("data"));
            Statement query = queries.getAllValues().get(index);
            assertEquals("SELECT * FROM records WHERE partitionKey = @partitionKey AND sortKey = @sortKey",
                    query.getSql());
            assertEquals(partition, query.getParameters().get("partitionKey").getString());
            assertEquals(id, query.getParameters().get("sortKey").getString());
        }
        verify(spanner).getDatabaseClient(DatabaseId.of("test-project", "test-instance", "configured-db"));
        verify(database, times(6)).write(any());
        verify(database, times(2)).singleUse();
        verify(database, times(2)).readWriteTransaction();
        verifyNoMoreInteractions(database);
    }
}

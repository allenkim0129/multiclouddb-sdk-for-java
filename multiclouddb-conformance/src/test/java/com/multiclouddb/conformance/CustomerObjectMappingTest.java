// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance;

import com.azure.cosmos.*;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.CosmosItemResponse;
import com.azure.cosmos.models.PartitionKey;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.spanner.*;
import com.multiclouddb.api.*;
import com.multiclouddb.api.document.*;
import com.multiclouddb.spi.DocumentMaps;
import com.multiclouddb.serializer.jackson.JacksonDocumentCodec;
import com.multiclouddb.api.codec.DocumentCodecException;
import com.multiclouddb.api.codec.TypeRef;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClient;
import software.amazon.awssdk.services.dynamodb.streams.DynamoDbStreamsClientBuilder;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.multiclouddb.conformance.Documents.document;

/**
 * End-to-end unit tests: actual factory, client wrapper and provider mappings;
 * only native SDK boundaries are mocked. These are not live persistence tests.
 */
class CustomerObjectMappingTest {
    private static final ResourceAddress ADDRESS = new ResourceAddress("database", "documents");
    private static final MulticloudDbKey KEY = MulticloudDbKey.of("tenant", "record");
    private static final TypeRef<Batch<Customer>> BATCH = new TypeRef<>() {};
    private static final TypeRef<Map<String, Object>> MAP = new TypeRef<>() {};

    record Customer(String displayName) {}

    // The application, not the adapter, chooses how provider root metadata is handled.
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Batch<T>(String batchName, LocalDate createdOn, List<T> items, int quantity, double ratio) {}

    @Test
    void cosmosCustomerMappingThroughActualClientAndProvider() throws Exception {
        CosmosClient nativeClient = mock(CosmosClient.class);
        CosmosDatabase database = mock(CosmosDatabase.class);
        CosmosContainer container = mock(CosmosContainer.class);
        @SuppressWarnings("unchecked")
        CosmosItemResponse<ObjectNode> response = mock(CosmosItemResponse.class);
        AtomicReference<ObjectNode> written = new AtomicReference<>();
        AtomicReference<ObjectNode> readFixture = new AtomicReference<>();
        when(nativeClient.getDatabase(ADDRESS.database())).thenReturn(database);
        when(database.getContainer(ADDRESS.collection())).thenReturn(container);
        when(container.createItem(any(ObjectNode.class), any(PartitionKey.class), any(CosmosItemRequestOptions.class)))
                .thenAnswer(call -> {
                    written.set(call.<ObjectNode>getArgument(0).deepCopy());
                    assertEquals(new PartitionKey(KEY.partitionKey()), call.getArgument(1));
                    return response;
                });
        when(container.upsertItem(any(ObjectNode.class), any(PartitionKey.class), any(CosmosItemRequestOptions.class)))
                .thenAnswer(call -> { written.set(call.<ObjectNode>getArgument(0).deepCopy()); return response; });
        when(container.readItem(eq(KEY.sortKey()), any(PartitionKey.class),
                any(CosmosItemRequestOptions.class), eq(ObjectNode.class))).thenReturn(response);
        when(response.getItem()).thenAnswer(call -> readFixture.get());

        try (MockedConstruction<CosmosClientBuilder> ignored = mockConstruction(CosmosClientBuilder.class,
                withSettings().defaultAnswer(RETURNS_SELF),
                (builder, context) -> when(builder.buildClient()).thenReturn(nativeClient));
             MulticloudDbClient client = MulticloudDbClientFactory.create(MulticloudDbClientConfig.builder()
                     .provider(ProviderId.COSMOS)
                     .connection(Map.of("endpoint", "https://example.documents.azure.com:443/", "key", "dGVzdA=="))
                     .build())) {
            exercise(client, written::get, readFixture::set, Function.identity());
            assertEquals(KEY.sortKey(), readFixture.get().get("id").textValue());
            assertEquals(KEY.partitionKey(), readFixture.get().get("partitionKey").textValue());
            verify(container, atLeast(2)).createItem(any(ObjectNode.class), any(PartitionKey.class),
                    any(CosmosItemRequestOptions.class));
            assertLiteralMapParity(client, written::get, Function.identity());
            assertEquals("1", written.get().get("decimal_value").asText(),
                    "the existing default native tree conversion strips decimal trailing zeros");
            assertEquals("123456789012345678901234567890", written.get().get("integer_value").asText());

            readFixture.get().put("_etag", "provider-value");
            assertTrue(client.read(ADDRESS, KEY).document().get("_etag").isEmpty());
            assertTrue(readFixture.get().has("_etag"), "provider read must not mutate the fixture");
            assertValidationBeforeNativeOperation(client, container);
        }
        verify(nativeClient).close();
    }

    @Test
    void dynamoCustomerMappingThroughActualClientAndProvider() throws Exception {
        DynamoDbClient nativeClient = mock(DynamoDbClient.class);
        DynamoDbStreamsClient streams = mock(DynamoDbStreamsClient.class);
        DynamoDbClientBuilder builder = mock(DynamoDbClientBuilder.class, RETURNS_SELF);
        DynamoDbStreamsClientBuilder streamsBuilder = mock(DynamoDbStreamsClientBuilder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(nativeClient);
        when(streamsBuilder.build()).thenReturn(streams);
        List<PutItemRequest> writes = new ArrayList<>();
        AtomicReference<Map<String, AttributeValue>> readFixture = new AtomicReference<>();
        DynamoDbResponseMetadata metadata = mock(DynamoDbResponseMetadata.class);
        PutItemResponse putResponse = mock(PutItemResponse.class);
        GetItemResponse getResponse = mock(GetItemResponse.class);
        when(putResponse.responseMetadata()).thenReturn(metadata);
        when(getResponse.responseMetadata()).thenReturn(metadata);
        when(getResponse.item()).thenAnswer(call -> readFixture.get());
        when(getResponse.hasItem()).thenReturn(true);
        when(nativeClient.putItem(any(PutItemRequest.class))).thenAnswer(call -> {
            writes.add(call.getArgument(0));
            return putResponse;
        });
        when(nativeClient.getItem(any(GetItemRequest.class))).thenAnswer(call -> {
            GetItemRequest request = call.getArgument(0);
            assertEquals(KEY.partitionKey(), request.key().get("partitionKey").s());
            assertEquals(KEY.sortKey(), request.key().get("sortKey").s());
            return getResponse;
        });
        try (MockedStatic<DynamoDbClient> clients = mockStatic(DynamoDbClient.class);
             MockedStatic<DynamoDbStreamsClient> streamClients = mockStatic(DynamoDbStreamsClient.class)) {
            clients.when(DynamoDbClient::builder).thenReturn(builder);
            streamClients.when(DynamoDbStreamsClient::builder).thenReturn(streamsBuilder);
            try (MulticloudDbClient client = MulticloudDbClientFactory.create(MulticloudDbClientConfig.builder()
                    .provider(ProviderId.DYNAMO).connection(Map.of("region", "us-east-1")).build())) {
                Supplier<PutItemRequest> last = () -> writes.get(writes.size() - 1);
                exercise(client, last, request -> readFixture.set(request.item()), Function.identity());
                assertNotNull(writes.get(0).conditionExpression());
                assertNull(writes.get(2).conditionExpression());
                assertEquals(KEY.partitionKey(), readFixture.get().get("partitionKey").s());
                assertLiteralMapParity(client, last, Function.identity());
                assertEquals("1", last.get().item().get("decimal_value").n());
                assertEquals("123456789012345678901234567890", last.get().item().get("integer_value").n());
                assertValidationBeforeNativeOperation(client, nativeClient);
            }
        }
        verify(nativeClient).close();
        verify(streams).close();
    }

    @Test
    void spannerCustomerMappingUsesCapturedMutationIncludingFieldDataForRead() throws Exception {
        SpannerOptions.Builder builder = mock(SpannerOptions.Builder.class, RETURNS_SELF);
        SpannerOptions options = mock(SpannerOptions.class);
        Spanner spanner = mock(Spanner.class);
        DatabaseClient nativeClient = mock(DatabaseClient.class);
        ReadContext read = mock(ReadContext.class);
        when(builder.build()).thenReturn(options);
        when(options.getService()).thenReturn(spanner);
        when(spanner.getDatabaseClient(any(DatabaseId.class))).thenReturn(nativeClient);
        when(nativeClient.singleUse()).thenReturn(read);
        List<Mutation> writes = new ArrayList<>();
        AtomicReference<Mutation> readFixture = new AtomicReference<>();
        when(nativeClient.write(any())).thenAnswer(call -> {
            Iterable<Mutation> mutations = call.getArgument(0);
            mutations.forEach(writes::add);
            return com.google.cloud.Timestamp.ofTimeSecondsAndNanos(0, 0);
        });
        when(read.executeQuery(any(Statement.class))).thenAnswer(call -> {
            Struct.Builder row = Struct.newBuilder();
            readFixture.get().asMap().forEach((name, value) -> row.set(name).to(value));
            Struct capturedRow = row.build();
            return ResultSets.forRows(capturedRow.getType(), List.of(capturedRow));
        });
        try (MockedStatic<SpannerOptions> factories = mockStatic(SpannerOptions.class)) {
            factories.when(SpannerOptions::newBuilder).thenReturn(builder);
            try (MulticloudDbClient client = MulticloudDbClientFactory.create(MulticloudDbClientConfig.builder()
                    .provider(ProviderId.SPANNER)
                    .connection(Map.of("projectId", "project", "instanceId", "instance", "databaseId", "database"))
                    .build())) {
                Supplier<Mutation> last = () -> writes.get(writes.size() - 1);
                exercise(client, last, readFixture::set, Mutation::asMap);
                assertEquals(Mutation.Op.INSERT, writes.get(0).getOperation());
                assertEquals(Mutation.Op.INSERT_OR_UPDATE, writes.get(2).getOperation());
                assertTrue(readFixture.get().asMap().containsKey("data"));
                assertEquals(KEY.partitionKey(), readFixture.get().asMap().get("partitionKey").getString());
                assertLiteralMapParity(client, last, Mutation::asMap);
                assertEquals(Value.string("1.00"), last.get().asMap().get("decimal_value"),
                        "retain the existing Spanner top-level decimal STRING fallback");
                assertEquals(Value.string("123456789012345678901234567890"), last.get().asMap().get("integer_value"));
                assertEquals(Value.float64((double) 0.1F), last.get().asMap().get("float_value"),
                        "retain Float-to-FLOAT64 mapping, not decimal-to-STRING mapping");
                assertValidationBeforeNativeOperation(client, nativeClient);
                Document reserved = JacksonDocumentCodec.createDefault().encode(Map.of("Data", 1), MAP);
                assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, assertThrows(MulticloudDbException.class,
                        () -> client.upsert(ADDRESS, KEY, reserved)).error().category());
                verifyNoInteractions(nativeClient);
            }
        }
        verify(spanner).close();
    }

    private static <R> void exercise(MulticloudDbClient client, Supplier<R> captured, Consumer<R> readFixture,
                                    Function<R, ?> comparable) {
        AtomicInteger serialized = new AtomicInteger();
        AtomicInteger deserialized = new AtomicInteger();
        JacksonDocumentCodec codec = configuredCodec(serialized, deserialized);
        Batch<Customer> source = new Batch<>("customers", LocalDate.of(2026, 10, 6),
                List.of(new Customer("Ada")), 3, 1.5);
        Document encoded = codec.encode(source, BATCH);
        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("batch_name", "customers");
        baseline.put("created_on", "2026/10/06");
        baseline.put("items", List.of(Map.of("display_name", "Ada")));
        baseline.put("quantity", 3);
        baseline.put("ratio", 1.5);
        assertEquals(baseline, DocumentMaps.toMap(encoded));

        client.create(ADDRESS, KEY, encoded);
        R create = captured.get();
        readFixture.accept(create);
        Document createRead = client.read(ADDRESS, KEY).document();
        assertEquals(source, codec.decode(createRead, BATCH));
        client.create(ADDRESS, KEY, document(baseline));
        assertEquals(comparable.apply(create), comparable.apply(captured.get()));

        client.upsert(ADDRESS, KEY, encoded);
        R upsert = captured.get();
        readFixture.accept(upsert);
        Document upsertRead = client.read(ADDRESS, KEY).document();
        Document before = upsertRead;
        assertEquals(source, codec.decode(upsertRead, BATCH));
        assertEquals(before, upsertRead);
        client.upsert(ADDRESS, KEY, document(baseline));
        assertEquals(comparable.apply(upsert), comparable.apply(captured.get()));

        assertEquals(1, serialized.get(), "internal provider mappers must not re-run customer DTO serialization");
        assertEquals(2, deserialized.get());
        assertEquals(baseline, DocumentMaps.toMap(encoded), "client/provider must not mutate encoded input");
        assertEquals("Ada", source.items().get(0).displayName());

        PlainCustomerCodec plainCodec = new PlainCustomerCodec();
        PlainCustomerCodec.Customer plain = new PlainCustomerCodec.Customer("Grace", 7);
        Document independent = plainCodec.encode(plain, PlainCustomerCodec.Customer.class);
        client.create(ADDRESS, KEY, independent);
        readFixture.accept(captured.get());
        assertEquals(plain, plainCodec.decode(client.read(ADDRESS, KEY).document(), PlainCustomerCodec.Customer.class));
        client.upsert(ADDRESS, KEY, independent);
        readFixture.accept(captured.get());
        assertEquals(plain, plainCodec.decode(client.read(ADDRESS, KEY).document(), PlainCustomerCodec.Customer.class));
    }

    private static <R> void assertLiteralMapParity(MulticloudDbClient client, Supplier<R> captured,
                                                  Function<R, ?> comparable) {
        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("partitionKey", "business");
        baseline.put("sortKey", "business");
        baseline.put("id", "business");
        baseline.put("float_value", 0.1f);
        baseline.put("decimal_value", new BigDecimal("1.00"));
        baseline.put("integer_value", new BigInteger("123456789012345678901234567890"));
        baseline.put("nested", Map.of("decimal", new BigDecimal("1.00")));
        baseline.put("nullable", null);
        Document encoded = JacksonDocumentCodec.createDefault().encode(baseline, MAP);
        client.upsert(ADDRESS, KEY, encoded);
        Object actual = comparable.apply(captured.get());
        client.upsert(ADDRESS, KEY, document(baseline));
        assertEquals(actual, comparable.apply(captured.get()),
                "codec numeric/key behavior must match the same literal Document input");
        assertEquals(baseline, DocumentMaps.toMap(encoded));
        assertEquals("business", baseline.get("partitionKey"));
    }

    private static void assertValidationBeforeNativeOperation(MulticloudDbClient client, Object nativeBoundary) {
        clearInvocations(nativeBoundary);
        JacksonDocumentCodec codec = JacksonDocumentCodec.createDefault();
        assertThrows(DocumentCodecException.class,
                () -> client.create(ADDRESS, KEY, codec.encode("not-an-object", String.class)));
        verifyNoInteractions(nativeBoundary);
        assertThrows(DocumentCodecException.class,
                () -> client.create(ADDRESS, KEY, codec.encode(Map.of("number", Double.NaN), MAP)));
        verifyNoInteractions(nativeBoundary);
        Document oversized = codec.encode(Map.of("body", "x".repeat(400 * 1024)), MAP);
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, assertThrows(MulticloudDbException.class,
                () -> client.upsert(ADDRESS, KEY, oversized)).error().category());
        verifyNoInteractions(nativeBoundary);
        Document binary = Document.builder().put("nested",
                ArrayValue.of(List.of(BinaryValue.of(new byte[]{1})))).build();
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, assertThrows(MulticloudDbException.class,
                () -> client.upsert(ADDRESS, KEY, binary)).error().category());
        verifyNoInteractions(nativeBoundary);
    }

    private static JacksonDocumentCodec configuredCodec(AtomicInteger serialized, AtomicInteger deserialized) {
        SimpleModule module = new SimpleModule();
        module.addSerializer(LocalDate.class, new JsonSerializer<>() {
            @Override public void serialize(LocalDate date, JsonGenerator generator, SerializerProvider provider)
                    throws IOException {
                serialized.incrementAndGet();
                generator.writeString(date.toString().replace("-", "/"));
            }
        });
        module.addDeserializer(LocalDate.class, new JsonDeserializer<>() {
            @Override public LocalDate deserialize(JsonParser parser, DeserializationContext context)
                    throws IOException {
                deserialized.incrementAndGet();
                return LocalDate.parse(parser.getText().replace("/", "-"));
            }
        });
        return JacksonDocumentCodec.from(new ObjectMapper().registerModule(module)
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE));
    }
}

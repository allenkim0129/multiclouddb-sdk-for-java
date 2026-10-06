// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.serializer.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static com.multiclouddb.serializer.jackson.ObjectCodecException.Phase.*;
import static com.multiclouddb.serializer.jackson.ObjectCodecException.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

class JacksonObjectCodecTest {
    record Customer(String displayName, LocalDate birthDate) {}
    record Batch<T>(List<T> items) {}
    record Message(String value) {}
    private static final ObjectMapper TREE_MAPPER = new ObjectMapper();

    @Test
    void defaultFactoryHandlesClassAndGenericObjects() {
        JacksonObjectCodec codec = JacksonObjectCodec.createDefault();
        Message source = new Message("hello");
        Map<String, Object> map = codec.encodeMap(source, Message.class);
        assertEquals(Map.of("value", "hello"), map);
        assertEquals(source, codec.decode(TREE_MAPPER.valueToTree(map), Message.class));
        TypeRef<Batch<Message>> type = new TypeRef<>() {};
        Batch<Message> batch = new Batch<>(List.of(source));
        assertEquals(batch, codec.decode(TREE_MAPPER.valueToTree(codec.encodeMap(batch, type)), type));
        assertFalse(AutoCloseable.class.isAssignableFrom(JacksonObjectCodec.class));
    }

    @Test
    void codecKeepsCollectorsAndParsersIndependentAcrossThreads() throws Exception {
        JacksonObjectCodec codec = JacksonObjectCodec.from(new com.fasterxml.jackson.databind.json.JsonMapper());
        var pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Boolean>> operations = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                Message value = new Message("message-" + i);
                operations.add(() -> value.equals(codec.decode(
                        TREE_MAPPER.valueToTree(codec.encodeMap(value, Message.class)), Message.class)));
            }
            for (var result : pool.invokeAll(operations)) assertTrue(result.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void customerNamingDateAndGenericDeserializationSurviveMapperMutation() {
        SimpleModule module = new SimpleModule();
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        module.addSerializer(LocalDate.class, new JsonSerializer<>() {
            @Override public void serialize(LocalDate date, JsonGenerator g, SerializerProvider provider)
                    throws IOException {
                writes.incrementAndGet();
                g.writeString(date.toString().replace("-", "/"));
            }
        });
        module.addDeserializer(LocalDate.class, new JsonDeserializer<>() {
            @Override public LocalDate deserialize(JsonParser p, DeserializationContext context) throws IOException {
                reads.incrementAndGet();
                return LocalDate.parse(p.getText().replace("/", "-"));
            }
        });
        ObjectMapper mapper = new ObjectMapper().registerModule(module)
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        JacksonObjectCodec codec = JacksonObjectCodec.from(mapper);
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.UPPER_CAMEL_CASE);
        TypeRef<Batch<Customer>> type = new TypeRef<>() {};
        Batch<Customer> batch = new Batch<>(List.of(new Customer("Ada", LocalDate.of(2026, 10, 6))));
        Map<String, Object> map = codec.encodeMap(batch, type);
        assertEquals(Map.of("items", List.of(Map.of("display_name", "Ada", "birth_date", "2026/10/06"))), map);
        ObjectNode tree = TREE_MAPPER.valueToTree(map);
        assertEquals(batch, codec.decode(tree, type));
        assertEquals(1, writes.get());
        assertEquals(1, reads.get());
        assertEquals(tree, TREE_MAPPER.valueToTree(map));
    }

    @Test
    void encodingDoesNotInvokeACustomerMapDeserializer() {
        SimpleModule module = new SimpleModule();
        AtomicInteger mapReads = new AtomicInteger();
        module.addDeserializer(Map.class, new JsonDeserializer<>() {
            @Override public Map<?, ?> deserialize(JsonParser p, DeserializationContext context) {
                mapReads.incrementAndGet();
                throw new IllegalStateException("must-not-run");
            }
        });
        JacksonObjectCodec codec = JacksonObjectCodec.from(new ObjectMapper().registerModule(module));
        assertEquals(Map.of("value", "hello"), codec.encodeMap(new Message("hello"), Message.class));
        assertEquals(0, mapReads.get());
    }

    @Test
    void materializesNestedGraphsAndExplicitNullWithoutRetainingMutableCollections() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("nullable", null);
        List<Object> items = new ArrayList<>(Arrays.asList(inner, null, "x"));
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("items", items);
        Map<String, Object> output = JacksonObjectCodec.createDefault()
                .encodeMap(source, new TypeRef<Map<String, Object>>() {});
        List<?> encodedItems = (List<?>) output.get("items");
        Map<?, ?> encodedInner = (Map<?, ?>) encodedItems.get(0);
        inner.put("nullable", "changed");
        items.clear();
        source.clear();
        assertTrue(encodedInner.containsKey("nullable"));
        assertNull(encodedInner.get("nullable"));
        assertNull(encodedItems.get(1));
        assertEquals(3, encodedItems.size());
        assertThrows(UnsupportedOperationException.class, output::clear);
        assertThrows(UnsupportedOperationException.class, encodedInner::clear);
        assertThrows(UnsupportedOperationException.class, encodedItems::clear);
    }

    @Test
    void preservesNumericTokensWithoutNewDecimalOrSpecialValuePolicy() {
        JacksonObjectCodec codec = custom((g, value) -> {
            g.writeStartObject();
            g.writeNumberField("short", (short) 2);
            g.writeNumberField("int", 3);
            g.writeNumberField("long", 4L);
            g.writeNumberField("float", -0.0f);
            g.writeNumberField("double", 0.1d);
            g.writeNumberField("decimal", new BigDecimal("1.00"));
            g.writeNumberField("integer", new BigInteger("123456789012345678901234567890"));
            g.writeNumberField("nan", Double.NaN);
            g.writeNumberField("infinity", Float.POSITIVE_INFINITY);
            g.writeEndObject();
        });
        Map<String, Object> map = codec.encodeMap(new Message("unused"), Message.class);
        assertInstanceOf(Short.class, map.get("short"));
        assertInstanceOf(Integer.class, map.get("int"));
        assertInstanceOf(Long.class, map.get("long"));
        assertEquals(Float.floatToRawIntBits(-0.0f), Float.floatToRawIntBits((Float) map.get("float")));
        assertEquals(Double.doubleToRawLongBits(0.1d), Double.doubleToRawLongBits((Double) map.get("double")));
        assertEquals(new BigDecimal("1.00"), map.get("decimal"));
        assertEquals(new BigInteger("123456789012345678901234567890"), map.get("integer"));
        assertEquals(Double.NaN, map.get("nan"));
        assertEquals(Float.POSITIVE_INFINITY, map.get("infinity"));
    }

    @Test
    void rejectsObservableDuplicatesEvenWithNullValues() {
        JacksonObjectCodec codec = custom((g, value) -> {
            g.writeStartObject();
            g.writeNullField("secret");
            g.writeStringField("secret", "customer-content");
            g.writeEndObject();
        });
        assertSafeFailure(codec, DUPLICATE_FIELD);
    }

    @Test
    void duplicateNamesInSeparateObjectsAreAllowed() {
        Map<String, Object> source = Map.of("left", Map.of("same", 1), "right", Map.of("same", 2));
        assertEquals(source, JacksonObjectCodec.createDefault()
                .encodeMap(source, new TypeRef<Map<String, Object>>() {}));
    }

    @Test
    void rejectsNonObjectNullMultipleAndIncompleteRoots() {
        JacksonObjectCodec codec = JacksonObjectCodec.createDefault();
        assertEquals(INVALID_ROOT, assertThrows(ObjectCodecException.class,
                () -> codec.encodeMap("scalar", String.class)).reason());
        assertEquals(INVALID_ROOT, assertThrows(ObjectCodecException.class,
                () -> codec.encodeMap(List.of(1), new TypeRef<List<Integer>>() {})).reason());
        assertEquals(INVALID_ROOT, assertThrows(ObjectCodecException.class,
                () -> codec.encodeMap(null, Message.class)).reason());
        assertSafeFailure(custom((g, v) -> { g.writeStartObject(); }), INVALID_STRUCTURE);
        assertSafeFailure(custom((g, v) -> {
            g.writeStartObject(); g.writeEndObject(); g.writeStartObject(); g.writeEndObject();
        }), INVALID_STRUCTURE);
        assertSafeFailure(custom((g, v) -> {
            g.writeStartObject(); g.writeFieldName("secret"); g.writeEndObject();
        }), INVALID_STRUCTURE);
    }

    @Test
    void rejectsRawEmbeddedBinaryAndFormattedNumericOutputExplicitly() {
        for (Output output : List.<Output>of(
                (g, v) -> g.writeRawValue("{\"secret\":1}"),
                (g, v) -> g.writeEmbeddedObject(v),
                (g, v) -> g.writeBinary(new byte[]{1, 2}),
                (g, v) -> g.writeNumber("1.00"),
                (g, v) -> g.writeObjectId("secret"))) {
            assertSafeFailure(custom((g, value) -> {
                g.writeStartObject(); g.writeFieldName("value"); output.write(g, value); g.writeEndObject();
            }), UNSUPPORTED_OUTPUT);
        }
    }

    @Test
    void propagatesTheCallersJacksonDepthConstraintAsSafeFailure() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.getFactory().setStreamWriteConstraints(
                com.fasterxml.jackson.core.StreamWriteConstraints.builder().maxNestingDepth(1).build());
        JacksonObjectCodec codec = JacksonObjectCodec.from(mapper);
        assertThrows(ObjectCodecException.class, () -> codec.encodeMap(
                Map.of("nested", Map.of("value", 1)), new TypeRef<Map<String, Object>>() {}));
    }

    @Test
    void serializerCannotSwallowDepthFailureAndReturnAPartialMap() {
        for (boolean object : List.of(true, false)) {
            SimpleModule module = new SimpleModule();
            module.addSerializer(Message.class, new JsonSerializer<>() {
                @Override public void serialize(Message value, JsonGenerator g, SerializerProvider provider)
                        throws IOException {
                    g.writeStartObject();
                    g.writeArrayFieldStart("items");
                    try {
                        if (object) g.writeStartObject();
                        else g.writeStartArray();
                    } catch (IOException deliberatelySwallowed) {
                        // Reproduce customer code attempting to recover with a partial result.
                    }
                    g.writeEndArray();
                    g.writeEndObject();
                }
            });
            ObjectMapper mapper = new ObjectMapper().registerModule(module);
            mapper.getFactory().setStreamWriteConstraints(
                    com.fasterxml.jackson.core.StreamWriteConstraints.builder().maxNestingDepth(2).build());
            assertSafeFailure(JacksonObjectCodec.from(mapper), INVALID_STRUCTURE);
        }
    }

    @Test
    void exposesSafePhaseOnEncodeAndDecodeFailures() {
        ObjectCodecException encode = assertThrows(ObjectCodecException.class,
                () -> custom((g, v) -> { throw new IllegalArgumentException("secret"); })
                        .encodeMap(new Message("customer-content"), Message.class));
        assertEquals(ENCODE, encode.phase());
        assertSafe(encode);
        ObjectNode bad = TREE_MAPPER.createObjectNode().put("unexpected-secret", "customer-content");
        ObjectCodecException decode = assertThrows(ObjectCodecException.class,
                () -> JacksonObjectCodec.createDefault().decode(bad, Message.class));
        assertEquals(DECODE, decode.phase());
        assertSafe(decode);
    }

    @Test
    void decodeUsesAPrivateTreeCopyEvenForTreeTargets() {
        ObjectNode source = TREE_MAPPER.createObjectNode().put("value", "original");
        ObjectNode decoded = JacksonObjectCodec.createDefault().decode(source, ObjectNode.class);
        decoded.put("value", "changed");
        assertEquals("original", source.get("value").textValue());
    }

    record BinaryDto(byte[] bytes) {}
    record BinaryTreeDto(byte[] bytes, BinaryDto object, List<BinaryDto> objects, List<byte[]> arrays) {}

    @Test
    void decodedDtoBinaryArraysDoNotAliasOriginalRootOrNestedNodes() throws Exception {
        ObjectNode source = binaryTree();
        BinaryTreeDto decoded = JacksonObjectCodec.createDefault().decode(source, BinaryTreeDto.class);
        decoded.bytes()[0] = 9;
        decoded.object().bytes()[0] = 9;
        decoded.objects().get(0).bytes()[0] = 9;
        decoded.arrays().get(0)[0] = 9;
        assertBinaryTreeUnchanged(source);
    }

    @Test
    void decodedTreeBinaryArraysDoNotAliasOriginalRootOrNestedNodes() throws Exception {
        ObjectNode source = binaryTree();
        ObjectNode decoded = JacksonObjectCodec.createDefault().decode(source, ObjectNode.class);
        decoded.get("bytes").binaryValue()[0] = 9;
        decoded.get("object").get("bytes").binaryValue()[0] = 9;
        decoded.get("objects").get(0).get("bytes").binaryValue()[0] = 9;
        decoded.get("arrays").get(0).binaryValue()[0] = 9;
        assertBinaryTreeUnchanged(source);
    }

    private static ObjectNode binaryTree() {
        ObjectNode source = TREE_MAPPER.createObjectNode().put("bytes", new byte[]{1, 2});
        source.putObject("object").put("bytes", new byte[]{3, 4});
        source.putArray("objects").addObject().put("bytes", new byte[]{5, 6});
        source.putArray("arrays").add(new byte[]{7, 8});
        return source;
    }

    private static void assertBinaryTreeUnchanged(ObjectNode source) throws IOException {
        assertArrayEquals(new byte[]{1, 2}, source.get("bytes").binaryValue());
        assertArrayEquals(new byte[]{3, 4}, source.get("object").get("bytes").binaryValue());
        assertArrayEquals(new byte[]{5, 6}, source.get("objects").get(0).get("bytes").binaryValue());
        assertArrayEquals(new byte[]{7, 8}, source.get("arrays").get(0).binaryValue());
    }

    @Test
    void customDeserializerCanUseTheCapturedParserCodec() {
        SimpleModule module = new SimpleModule();
        module.addDeserializer(Message.class, new JsonDeserializer<>() {
            @Override public Message deserialize(JsonParser parser, DeserializationContext context)
                    throws IOException {
                ObjectNode tree = parser.readValueAsTree();
                return new Message(tree.remove("custom").textValue());
            }
        });
        JacksonObjectCodec codec = JacksonObjectCodec.from(new ObjectMapper().registerModule(module));
        ObjectNode source = TREE_MAPPER.createObjectNode().put("custom", "hello");
        assertEquals(new Message("hello"), codec.decode(source, Message.class));
        assertEquals("hello", source.get("custom").textValue());
    }

    @Test
    void leavesExistingReservedFieldNamesUntouched() {
        Map<String, Object> source = Map.of("id", "business", "partitionKey", "business",
                "sortKey", "business", "Data", "business", "_etag", "business");
        assertEquals(source, JacksonObjectCodec.createDefault()
                .encodeMap(source, new TypeRef<Map<String, Object>>() {}));
    }

    @Test
    void rejectsMissingTypeAndDocumentArgumentsWithTheCorrectPhase() {
        JacksonObjectCodec codec = JacksonObjectCodec.createDefault();
        assertEquals(ENCODE, assertThrows(ObjectCodecException.class,
                () -> codec.encodeMap(new Message("x"), (Class<Message>) null)).phase());
        assertEquals(DECODE, assertThrows(ObjectCodecException.class,
                () -> codec.decode(null, Message.class)).phase());
    }

    private static void assertSafeFailure(JacksonObjectCodec codec, ObjectCodecException.Reason reason) {
        ObjectCodecException failure = assertThrows(ObjectCodecException.class,
                () -> codec.encodeMap(new Message("customer-content"), Message.class));
        assertEquals(reason, failure.reason());
        assertEquals(ENCODE, failure.phase());
        assertSafe(failure);
    }

    private static void assertSafe(ObjectCodecException failure) {
        StringWriter text = new StringWriter();
        failure.printStackTrace(new PrintWriter(text));
        assertFalse(text.toString().contains("customer-content"));
        assertFalse(text.toString().contains("secret"));
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
    }

    @FunctionalInterface
    private interface Output {
        void write(JsonGenerator generator, Message value) throws IOException;
    }

    private static JacksonObjectCodec custom(Output output) {
        SimpleModule module = new SimpleModule();
        module.addSerializer(Message.class, new JsonSerializer<>() {
            @Override public void serialize(Message value, JsonGenerator g, SerializerProvider provider)
                    throws IOException { output.write(g, value); }
        });
        return JacksonObjectCodec.from(new ObjectMapper().registerModule(module));
    }
}

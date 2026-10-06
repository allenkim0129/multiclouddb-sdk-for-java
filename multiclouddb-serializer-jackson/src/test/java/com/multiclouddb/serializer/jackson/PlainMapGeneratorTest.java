// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.serializer.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.multiclouddb.serializer.jackson.ObjectCodecException.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

class PlainMapGeneratorTest {
    @Test
    void higherLevelGeneratorEntrypointsUseTheSameStructuralCollector() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (PlainMapGenerator g = new PlainMapGenerator(mapper)) {
            g.writeStartObject(new Object());
            g.writeFieldName(new SerializedString("text"));
            g.writeString(new SerializedString("a"));
            g.writeFieldName("chars");
            g.writeString("abcd".toCharArray(), 1, 2);
            g.writeFieldName("utf8");
            byte[] utf8 = "text".getBytes(StandardCharsets.UTF_8);
            g.writeUTF8String(utf8, 0, utf8.length);
            g.writeObjectField("object", Map.of("value", true));
            g.writeFieldName("tree");
            g.writeTree(mapper.createObjectNode().put("value", "nested"));
            g.writeFieldName("array");
            g.writeArray(new int[]{1, 2, 3}, 1, 2);
            g.writeFieldName("nullable");
            g.writeNumber((BigDecimal) null);
            g.writeEndObject();
            Map<String, Object> output = g.result();
            assertEquals("a", output.get("text"));
            assertEquals("bc", output.get("chars"));
            assertEquals("text", output.get("utf8"));
            assertEquals(Map.of("value", true), output.get("object"));
            assertEquals(Map.of("value", "nested"), output.get("tree"));
            assertEquals(List.of(2, 3), output.get("array"));
            assertTrue(output.containsKey("nullable"));
            assertNull(output.get("nullable"));
            assertEquals(List.of("text", "chars", "utf8", "object", "tree", "array", "nullable"),
                    List.copyOf(output.keySet()));
        }
    }

    @Test
    void copyingParserStructureCannotBypassContainerMaterialization() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var tree = mapper.createObjectNode();
        tree.putArray("items").addNull().add(2).addObject().put("value", true);
        try (PlainMapGenerator generator = new PlainMapGenerator(mapper);
             JsonParser parser = tree.traverse(mapper)) {
            parser.nextToken();
            generator.copyCurrentStructure(parser);
            assertEquals(Map.of("items", Arrays.asList(null, 2, Map.of("value", true))), generator.result());
        }
    }

    @Test
    void malformedStructuresAndCaughtErrorsCannotProduceAResult() throws Exception {
        for (Output invalid : List.<Output>of(
                JsonGenerator::writeEndObject,
                JsonGenerator::writeStartArray,
                g -> { g.writeStartObject(); g.writeString("no-name"); },
                g -> { g.writeStartObject(); g.writeFieldName("first"); g.writeFieldName("second"); },
                g -> { g.writeStartObject(); g.writeFieldName("items"); g.writeStartArray(); g.writeEndObject(); },
                g -> { g.writeStartObject(); g.writeFieldName("items"); g.writeEndArray(); },
                g -> { g.writeStartObject(); g.writeEndObject(); g.writeNull(); })) {
            try (PlainMapGenerator generator = new PlainMapGenerator(new ObjectMapper())) {
                assertThrows(ObjectCodecException.class, () -> invalid.write(generator));
                assertThrows(ObjectCodecException.class, generator::result);
                assertThrows(ObjectCodecException.class, generator::writeStartObject);
            }
        }
    }

    @Test
    void incompleteAndClosedStreamsCannotBecomeEmptySuccesses() throws Exception {
        PlainMapGenerator generator = new PlainMapGenerator(new ObjectMapper());
        generator.writeStartObject();
        generator.close();
        assertThrows(ObjectCodecException.class, generator::result);
        assertThrows(ObjectCodecException.class, generator::writeStartObject);
    }

    @Test
    void unsupportedAlternativeEntrypointsFailExplicitly() throws Exception {
        for (Output output : List.<Output>of(
                g -> g.writeRawValue(new SerializedString("{}")),
                g -> g.writeNumber(new char[]{'1'}, 0, 1),
                g -> g.writeString(new StringReader("text"), 4),
                g -> g.writeRawUTF8String(new byte[]{'x'}, 0, 1),
                g -> g.writeObjectRef("id"),
                g -> g.writeTypeId("type"))) {
            try (PlainMapGenerator generator = new PlainMapGenerator(new ObjectMapper())) {
                generator.writeStartObject();
                generator.writeFieldName("value");
                assertEquals(UNSUPPORTED_OUTPUT, assertThrows(ObjectCodecException.class,
                        () -> output.write(generator)).reason());
            }
        }
    }

    @Test
    void rejectsInvalidUtf8InsteadOfReplacingBytes() throws Exception {
        try (PlainMapGenerator generator = new PlainMapGenerator(new ObjectMapper())) {
            generator.writeStartObject();
            generator.writeFieldName("text");
            assertEquals(UNSUPPORTED_OUTPUT, assertThrows(ObjectCodecException.class,
                    () -> generator.writeUTF8String(new byte[]{(byte) 0xff}, 0, 1)).reason());
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    void numericStringFormattingIsNotSilentlyIgnored() throws Exception {
        ObjectMapper mapper = new ObjectMapper().enable(JsonGenerator.Feature.WRITE_NUMBERS_AS_STRINGS);
        try (PlainMapGenerator generator = new PlainMapGenerator(mapper)) {
            generator.writeStartObject();
            generator.writeFieldName("nullable");
            generator.writeNumber((BigDecimal) null);
            generator.writeFieldName("number");
            assertEquals(UNSUPPORTED_OUTPUT, assertThrows(ObjectCodecException.class,
                    () -> generator.writeNumber(1)).reason());
        }
    }

    @FunctionalInterface
    private interface Output {
        void write(JsonGenerator generator) throws IOException;
    }
}

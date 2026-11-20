// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
package com.multiclouddb.api.changefeed.internal;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SDK-owned JSON processing for the v1 cursor format, not application document mapping. */
final class JsonSupport {
    private JsonSupport() {}

    private record Frame(Map<String, Object> object, List<Object> array) {}

    static Object read(String json) throws IOException {
        if (!json.isEmpty() && json.charAt(0) == '\uFEFF') throw new IOException("Unexpected JSON BOM.");
        if (json.chars().allMatch(c -> c == ' ' || c == '\t' || c == '\n' || c == '\r')) return null;
        validateLexicalProfile(json);
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            // STRICT rejects isolated surrogates preserved by the v1 cursor format.
            // The lexical guard rejects LEGACY_STRICT's other non-JSON extensions.
            reader.setStrictness(Strictness.LEGACY_STRICT);
            reader.setNestingLimit(1000);
            Deque<Frame> stack = new ArrayDeque<>();
            Object root = readValue(reader, stack);
            // Iterative traversal keeps the released depth budget independent of JVM stack size.
            while (!stack.isEmpty()) {
                Frame frame = stack.peek();
                if (!reader.hasNext()) {
                    if (frame.object() != null) reader.endObject();
                    else reader.endArray();
                    stack.pop();
                } else if (frame.object() != null) {
                    String name = reader.nextName();
                    if (name.length() > 50_000) throw new IOException("JSON name limit.");
                    frame.object().put(name, readValue(reader, stack));
                } else {
                    frame.array().add(readValue(reader, stack));
                }
            }
            // The released reader consumes one root, with last-duplicate-key wins semantics.
            return root;
        }
    }

    private static void validateLexicalProfile(String json) throws IOException {
        int depth = 0;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ',' || c == ':') continue;
            if (c == '"') {
                boolean closed = false;
                while (++i < json.length()) {
                    c = json.charAt(i);
                    if (c == '"') {
                        closed = true;
                        break;
                    }
                    if (c < 0x20) throw new IOException("Unescaped JSON control character.");
                    if (c == '\\') {
                        if (++i == json.length() || "\"\\/bfnrtu".indexOf(json.charAt(i)) < 0) {
                            throw new IOException("Invalid JSON escape.");
                        }
                    }
                }
                if (!closed) throw new IOException("Unterminated JSON string.");
                if (depth == 0) return;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                if (--depth <= 0) return;
            } else {
                int start = i;
                while (i + 1 < json.length() && " \t\n\r,]}:{[\"".indexOf(json.charAt(i + 1)) < 0) i++;
                if (c != '-' && (c < '0' || c > '9')) {
                    String literal = json.substring(start, i + 1);
                    if (!literal.equals("true") && !literal.equals("false") && !literal.equals("null")) {
                        throw new IOException("Invalid JSON literal.");
                    }
                }
                if (depth == 0) return;
            }
        }
    }

    private static Object readValue(JsonReader reader, Deque<Frame> stack) throws IOException {
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                reader.beginObject();
                Map<String, Object> object = new LinkedHashMap<>();
                stack.push(new Frame(object, null));
                return object;
            case BEGIN_ARRAY:
                reader.beginArray();
                List<Object> array = new ArrayList<>();
                stack.push(new Frame(null, array));
                return array;
            case STRING:
                String text = reader.nextString();
                if (text.length() > 20_000_000) throw new IOException("JSON string limit.");
                return text;
            case NUMBER:
                String token = reader.nextString();
                validateNumberLength(token);
                if (token.indexOf('.') >= 0 || token.indexOf('e') >= 0 || token.indexOf('E') >= 0) {
                    return Double.valueOf(token);
                }
                BigInteger integer = new BigInteger(token);
                if (integer.bitLength() < 32) return integer.intValue();
                if (integer.bitLength() < 64) return integer.longValue();
                return integer;
            case BOOLEAN:
                return reader.nextBoolean();
            case NULL:
                reader.nextNull();
                return null;
            default:
                throw new IOException("Expected JSON value.");
        }
    }

    private static void validateNumberLength(String token) throws IOException {
        // Match the released parser's digit accounting, including its zero-leading slow path.
        int digits = 0;
        for (int i = 0; i < token.length(); i++) {
            if (token.charAt(i) >= '0' && token.charAt(i) <= '9') digits++;
        }
        boolean fractional = token.indexOf('.') >= 0;
        boolean exponential = token.indexOf('e') >= 0 || token.indexOf('E') >= 0;
        int firstDigit = token.startsWith("-") ? 1 : 0;
        if ((fractional || exponential) && token.charAt(firstDigit) == '0') {
            if (!fractional) digits--;
            if (!exponential) digits--;
        }
        if (digits > 1000) throw new IOException("JSON number limit.");
    }

    /** Writes only the shallow, SDK-created cursor envelope. */
    static String write(Map<String, Object> payload) throws IOException {
        StringWriter output = new StringWriter();
        try (JsonWriter writer = new JsonWriter(output)) {
            writer.setHtmlSafe(false);
            writer.setSerializeNulls(true);
            writeValue(writer, payload);
        }
        // Preserve the original wire writer's literal separators and uppercase control escapes.
        String json = output.toString();
        StringBuilder compatible = new StringBuilder(json.length());
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                if (json.startsWith("\\u2028", i) || json.startsWith("\\u2029", i)) {
                    compatible.append(json.charAt(i + 5) == '8' ? '\u2028' : '\u2029');
                    i += 5;
                } else if (json.charAt(i + 1) == 'u' && i + 5 < json.length()) {
                    compatible.append("\\u");
                    for (int digit = i + 2; digit <= i + 5; digit++) {
                        compatible.append(Character.toUpperCase(json.charAt(digit)));
                    }
                    i += 5;
                } else {
                    compatible.append(c).append(json.charAt(++i));
                }
            } else {
                compatible.append(c);
            }
        }
        return compatible.toString();
    }

    private static void writeValue(JsonWriter writer, Object value) throws IOException {
        if (value == null) writer.nullValue();
        else if (value instanceof Map<?, ?> map) {
            writer.beginObject();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalStateException("Invalid cursor envelope key.");
                }
                writer.name(key);
                writeValue(writer, entry.getValue());
            }
            writer.endObject();
        } else if (value instanceof List<?> list) {
            writer.beginArray();
            for (Object element : list) writeValue(writer, element);
            writer.endArray();
        } else if (value instanceof String text) writer.value(text);
        else if (value instanceof Integer number) writer.value(number);
        else if (value instanceof Long number) writer.value(number);
        else throw new IllegalStateException("Invalid cursor envelope value.");
    }
}

// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.serializer.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BinaryNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import static com.multiclouddb.serializer.jackson.ObjectCodecException.Phase.*;
import static com.multiclouddb.serializer.jackson.ObjectCodecException.Reason.*;

/**
 * Application-owned mapping for the SDK's existing Map writes and ObjectNode reads.
 * Call {@link #encodeMap(Object, TypeRef)} explicitly before a client write and
 * {@link #decode(ObjectNode, TypeRef)} explicitly after a read.
 *
 * <p>This class neither registers with a database client nor bypasses its
 * validation. It does not define a neutral document model or new numeric/storage
 * guarantees. Existing provider field, schema and number restrictions still apply.
 *
 * <p>Instances are thread-safe when customer mapping collaborators are thread-safe
 * and remain unmodified. Mapper configuration is copied, but custom collaborators
 * are not necessarily deep-cloned. Instances have no closeable lifecycle.
 */
public final class JacksonObjectCodec {
    private final MapperSnapshot snapshot;

    private JacksonObjectCodec(MapperSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    /**
     * Uses stock Jackson mapping without automatic module discovery or enabling
     * default typing. This is not a sandbox for untrusted DTO classes/annotations.
     */
    public static JacksonObjectCodec createDefault() {
        return from(new ObjectMapper());
    }

    /** Captures a caller-configured mapper; failed copies never fall back. */
    public static JacksonObjectCodec from(ObjectMapper mapper) {
        return new JacksonObjectCodec(MapperSnapshot.from(mapper));
    }

    /**
     * Materializes an object-root serializer token stream into plain Map/List/scalar
     * values, without JSON bytes or a Map-target deserialization pass.
     *
     * <p>The returned graph is unmodifiable and preserves explicit nulls and token
     * numeric values. No numeric normalization, rounding or reserved-name rewrite
     * is performed. Unsupported raw, binary, embedded, formatted numeric and
     * native-id output fails explicitly. Successful encoding is not DB acceptance.
     */
    public <T> Map<String, Object> encodeMap(T value, TypeRef<T> type) {
        if (type == null) throw new ObjectCodecException(ENCODE, INVALID_ARGUMENT);
        if (value == null) throw new ObjectCodecException(ENCODE, INVALID_ROOT);
        try (PlainMapGenerator generator = snapshot.generator()) {
            snapshot.writerFor(type).writeValue(generator, value);
            return generator.result();
        } catch (IOException | RuntimeException failure) {
            throw safeFailure(ENCODE, failure);
        }
    }

    /** Convenience form for a non-generic declared class. */
    public <T> Map<String, Object> encodeMap(T value, Class<T> type) {
        if (type == null) throw new ObjectCodecException(ENCODE, INVALID_ARGUMENT);
        return encodeMap(value, TypeRef.of(type));
    }

    /**
     * Uses the captured typed reader on a private copy of the existing read tree.
     * Standard ObjectNode/ArrayNode containers and BinaryNode byte arrays are
     * isolated. Opaque POJONode values and custom JsonNode subclasses retain
     * their own deepCopy semantics and are outside this isolation guarantee.
     * Extra key/metadata fields retain existing provider behavior; ignoring them
     * requires explicit customer configuration. No content is stripped here.
     */
    public <T> T decode(ObjectNode document, TypeRef<T> type) {
        if (document == null || type == null) throw new ObjectCodecException(DECODE, INVALID_ARGUMENT);
        try (JsonParser parser = snapshot.parserFor(copyReadTree(document))) {
            return snapshot.readerFor(type).readValue(parser);
        } catch (IOException | RuntimeException failure) {
            throw safeFailure(DECODE, failure);
        }
    }

    /** Convenience form for a non-generic target class. */
    public <T> T decode(ObjectNode document, Class<T> type) {
        if (type == null) throw new ObjectCodecException(DECODE, INVALID_ARGUMENT);
        return decode(document, TypeRef.of(type));
    }

    private static ObjectNode copyReadTree(ObjectNode document) {
        ObjectNode copy = document.deepCopy();
        copyBinaryLeaves(copy);
        return copy;
    }

    private static JsonNode copyBinaryLeaves(JsonNode node) {
        // Jackson deepCopy shares value nodes, including BinaryNode's mutable byte[].
        if (node.getClass() == BinaryNode.class) {
            byte[] bytes = ((BinaryNode) node).binaryValue();
            return bytes == null ? node : BinaryNode.valueOf(bytes.clone());
        }
        if (node.getClass() == ObjectNode.class) {
            node.properties().forEach(field -> field.setValue(copyBinaryLeaves(field.getValue())));
        } else if (node.getClass() == ArrayNode.class) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                array.set(i, copyBinaryLeaves(array.get(i)));
            }
        }
        return node;
    }

    private static ObjectCodecException safeFailure(ObjectCodecException.Phase phase, Exception failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof ObjectCodecException known && known.phase() == phase) return known;
        }
        return new ObjectCodecException(phase, MAPPING_FAILED);
    }
}

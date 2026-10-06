// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.fasterxml.jackson.databind.JsonNode;
import com.multiclouddb.api.document.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/** Structural conversion after the existing native mapping, without mapper coercion. */
final class NativeDocuments {
    private NativeDocuments() {
    }

    static Document document(JsonNode node) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Expected document object.");
        return Document.of((ObjectValue) value(node));
    }

    static DocumentValue value(JsonNode node) {
        return value(node, 0);
    }

    private static DocumentValue value(JsonNode node, int depth) {
        if (node == null) return null;
        if ((node.isObject() || node.isArray()) && ++depth > 128) {
            throw new IllegalArgumentException("Provider payload exceeds document nesting limit.");
        }
        switch (node.getNodeType()) {
            case NULL: return NullValue.INSTANCE;
            case STRING: return new StringValue(node.textValue());
            case BOOLEAN: return new BooleanValue(node.booleanValue());
            case NUMBER: return NumberValue.of(node.numberValue());
            case BINARY:
                try {
                    return BinaryValue.of(node.binaryValue());
                } catch (IOException invalid) {
                    throw new IllegalArgumentException("Invalid provider binary value.", invalid);
                }
            case ARRAY:
                var items = new ArrayList<DocumentValue>();
                for (JsonNode child : node) items.add(value(child, depth));
                return ArrayValue.of(items);
            case OBJECT:
                var fields = new LinkedHashMap<String, DocumentValue>();
                var iterator = node.fields();
                while (iterator.hasNext()) {
                    var field = iterator.next();
                    fields.put(field.getKey(), value(field.getValue(), depth));
                }
                return ObjectValue.of(fields);
            default: throw new IllegalArgumentException("Unsupported provider value kind.");
        }
    }
}

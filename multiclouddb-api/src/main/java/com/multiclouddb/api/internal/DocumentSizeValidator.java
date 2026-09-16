// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.OperationNames;
import com.multiclouddb.api.PortableWriteLimits;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validates portable write inputs against the serialized and structural limits
 * defined by FR-060 and enforced according to FR-061.
 *
 * <p>Both limits are 390 KiB, leaving headroom below DynamoDB's native 400 KiB
 * item limit for provider-injected key/TTL attributes. Structural validation
 * also enforces the provider-neutral nesting boundary and rejects non-portable
 * binary values before any provider receives a write.</p>
 */
public final class DocumentSizeValidator {

    /** Portable 390 KiB serialized payload and structural-footprint limit. */
    public static final int MAX_BYTES = PortableWriteLimits.MAX_SERIALIZED_INPUT_BYTES;

    private static final String RESERVED_DOCUMENT_FIELD_REASON =
            "reserved_document_field";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DocumentSizeValidator() {
    }

    /** Validates a complete JSON document for create/upsert. */
    public static void validate(JsonNode document, String operation) {
        validateDocument(document, operation);
    }

    /** Validates a complete map document for create/upsert. */
    public static void validate(Map<String, Object> document, String operation) {
        validateDocument(document, operation);
    }

    /** Validates and snapshots a complete map document for provider delegation. */
    static Map<String, Object> validateAndSnapshotDocument(
            Map<String, Object> document, String operation) {
        return validateDocument(document, operation);
    }

    /** Validates serialized size and structural rules for partial-update fields. */
    public static void validatePartialUpdate(
            Map<String, Object> fields, String operation) {
        validateAndSnapshotPartialUpdate(fields, operation);
    }

    /** Validates and snapshots partial-update fields for provider delegation. */
    static Map<String, Object> validateAndSnapshotPartialUpdate(
            Map<String, Object> fields, String operation) {
        PartialUpdateValidator.validate(fields, null, operation);
        Map<String, Object> snapshot =
                PartialUpdateStructureValidator.validateAndSnapshotDocument(fields, operation);
        PartialUpdateValidator.validate(snapshot, null, operation);
        byte[] serializedFields = validateSerializedSize(snapshot, operation);
        PartialUpdateStructureValidator.validatePartialUpdate(serializedFields, operation);
        return snapshot;
    }

    private static Map<String, Object> validateDocument(Object document, String operation) {
        if (document == null) {
            throw invalidRequest(
                    "Document is required for " + operation + "().",
                    operation,
                    Map.of("reason", "document_required"),
                    null);
        }

        Map<String, Object> snapshot =
                PartialUpdateStructureValidator.validateAndSnapshotDocument(document, operation);
        validateReservedTopLevelFields(snapshot, operation);
        byte[] serializedDocument = validateSerializedSize(snapshot, operation);
        PartialUpdateStructureValidator.validateDocument(serializedDocument, operation);
        return snapshot;
    }

    private static void validateReservedTopLevelFields(
            Map<String, Object> document, String operation) {
        for (String field : document.keySet()) {
            if (PartialUpdateValidator.isReservedProviderField(field)) {
                Map<String, String> details = new LinkedHashMap<>();
                details.put("reason", RESERVED_DOCUMENT_FIELD_REASON);
                details.put("field", field);
                throw invalidRequest(
                        "Complete write field name '" + field
                                + "' is reserved for provider identity, TTL, or metadata.",
                        operation, details, null);
            }
        }
    }

    private static byte[] validateSerializedSize(Object document, String operation) {
        try {
            byte[] bytes = MAPPER.writeValueAsBytes(document);
            if (bytes.length > MAX_BYTES) {
                boolean partialUpdate = OperationNames.UPDATE.equals(operation);
                long actualKiB = (bytes.length + 1023L) / 1024L;
                String subject = partialUpdate ? "Partial-update field map" : "Document";
                Map<String, String> details = new LinkedHashMap<>();
                details.put("reason", partialUpdate
                        ? "partial_update_serialized_size_limit"
                        : "document_serialized_size_limit");
                details.put("actualSerializedBytesAtLeast", String.valueOf(bytes.length));
                details.put("maximumSerializedBytes", String.valueOf(MAX_BYTES));
                throw invalidRequest(
                        subject + " size " + actualKiB
                                + " KiB exceeds the portable 390 KiB serialized-input limit. "
                                + "Reduce the write input to maintain portability across all providers.",
                        operation,
                        details,
                        null);
            }
            return bytes;
        } catch (JsonProcessingException e) {
            throw invalidRequest(
                    "Write input could not be serialised for size check: " + e.getMessage(),
                    operation,
                    Map.of("reason", "write_input_serialization_failed"),
                    e);
        }
    }

    private static MulticloudDbException invalidRequest(
            String message, String operation, Map<String, String> details, Throwable cause) {
        MulticloudDbError error = new MulticloudDbError(
                MulticloudDbErrorCategory.INVALID_REQUEST,
                message,
                null,
                operation,
                false,
                details);
        return cause == null
                ? new MulticloudDbException(error)
                : new MulticloudDbException(error, cause);
    }
}
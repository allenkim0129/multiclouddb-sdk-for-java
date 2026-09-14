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

import java.util.Map;

/**
 * Validates document payload sizes against the uniform maximum defined by FR-061.
 * <p>
 * The portable limit is 390 KiB, below the DynamoDB native 400 KiB item limit.
 * Cosmos DB and Cloud Spanner accept larger items,
 * but enforcing the lowest common denominator keeps writes portable. The portable
 * limit also leaves headroom for provider-injected key and TTL fields
 * and for DynamoDB internal wire-format overhead.
 */
public final class DocumentSizeValidator {

    /** Portable 390 KiB serialized payload limit. */
    public static final int MAX_BYTES = 390 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DocumentSizeValidator() {
    }

    /**
     * Validates that the serialized size of {@code document} does not exceed
     * {@link #MAX_BYTES}.
     *
     * @param document  the document to validate
     * @param operation the operation name used for error reporting
     * @throws MulticloudDbException with category {@link MulticloudDbErrorCategory#INVALID_REQUEST}
     *                               if the document exceeds the size limit
     */
    public static void validate(JsonNode document, String operation) {
        if (document == null) {
            return;
        }
        try {
            byte[] bytes = MAPPER.writeValueAsBytes(document);
            if (bytes.length > MAX_BYTES) {
                long actualKiB = (bytes.length + 1023L) / 1024L;
                throw new MulticloudDbException(new MulticloudDbError(
                        MulticloudDbErrorCategory.INVALID_REQUEST,
                        "Document size " + actualKiB
                                + " KiB exceeds the portable 390 KiB limit. Reduce the document size to "
                                + "maintain portability across all providers.",
                        null,
                        operation,
                        false,
                        null));
            }
        } catch (JsonProcessingException e) {
            throw new MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.INVALID_REQUEST,
                    "Document could not be serialised for size check: " + e.getMessage(),
                    null,
                    operation,
                    false,
                    null));
        }
    }

    /** Overload accepting {@code Map<String, Object>} documents. */
    public static void validate(Map<String, Object> document, String operation) {
        if (document == null) {
            return;
        }
        validate((JsonNode) MAPPER.valueToTree(document), operation);
    }
}

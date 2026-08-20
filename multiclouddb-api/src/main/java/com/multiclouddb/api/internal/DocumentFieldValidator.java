// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.internal;

import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.ProviderId;

import java.util.Map;

/**
 * Validates document field names owned by the portable SDK contract.
 * <p>
 * The standard Spanner schema uses {@code data} for SDK-owned document metadata
 * and resolves column names case-insensitively, while document providers would
 * otherwise accept a user field of that name. Rejecting it at the shared client
 * boundary keeps writes portable and preserves the name for future provider
 * implementations without requiring provider-specific validation.
 */
final class DocumentFieldValidator {

    /** Internal SDK metadata field, reserved across all providers. */
    static final String RESERVED_DATA_FIELD = "data";

    private DocumentFieldValidator() {
    }

    /**
     * Rejects fields that cannot be represented portably by create, update, or
     * upsert.
     *
     * @param document caller-supplied document, which may be {@code null}
     * @param provider provider recorded in a portable error envelope
     * @param operation write operation recorded in a portable error envelope
     * @throws MulticloudDbException {@code INVALID_REQUEST} when a reserved
     *         field is present
     */
    static void validateWritableDocument(Map<String, Object> document, ProviderId provider,
            String operation) {
        if (document == null) {
            return;
        }

        for (String field : document.keySet()) {
            if (field != null && RESERVED_DATA_FIELD.equalsIgnoreCase(field)) {
                throw new MulticloudDbException(new MulticloudDbError(
                        MulticloudDbErrorCategory.INVALID_REQUEST,
                        "Document field '" + field + "' is reserved by the SDK for internal document "
                                + "metadata. Reserved field names are matched without regard to case.",
                        provider,
                        operation,
                        false,
                        Map.of()));
            }
        }
    }
}

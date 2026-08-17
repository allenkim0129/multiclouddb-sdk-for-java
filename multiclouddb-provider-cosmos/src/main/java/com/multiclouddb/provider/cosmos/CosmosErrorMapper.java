// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.azure.cosmos.CosmosException;
import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.ProviderId;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps Azure Cosmos DB exceptions to portable {@link MulticloudDbException}
 * instances.
 */
public final class CosmosErrorMapper {

    private CosmosErrorMapper() {
    }

    public static MulticloudDbException map(CosmosException e, String operation) {
        int httpStatus = e.getStatusCode();

        Map<String, String> details = new LinkedHashMap<>();
        details.put("subStatusCode", String.valueOf(e.getSubStatusCode()));
        if (e.getActivityId() != null) {
            details.put("requestId", e.getActivityId());
        }
        details.put("requestCharge", String.valueOf(e.getRequestCharge()));

        MulticloudDbError error = new MulticloudDbError(
                mapCategory(httpStatus, e.getSubStatusCode()),
                e.getMessage(),
                ProviderId.COSMOS,
                operation,
                isRetryable(httpStatus),
                httpStatus,
                details);
        return new MulticloudDbException(error, e);
    }

    private static MulticloudDbErrorCategory mapCategory(int statusCode, int subStatusCode) {
        return switch (statusCode) {
            case 400 -> MulticloudDbErrorCategory.INVALID_REQUEST;
            case 401 -> MulticloudDbErrorCategory.AUTHENTICATION_FAILED;
            case 403 -> MulticloudDbErrorCategory.AUTHORIZATION_FAILED;
            case 404 -> MulticloudDbErrorCategory.NOT_FOUND;
            case 409 -> MulticloudDbErrorCategory.CONFLICT;
            // The adapter sends no If-Match anywhere, so the only precondition it
            // can attach is patch's path-scoped IS_DEFINED filter predicate. A 412
            // therefore means a required path was absent, not that a concurrent
            // writer touched the item — the portable NOT_FOUND that DynamoDB and
            // Spanner report for the same state. Mapping it to CONFLICT would
            // reintroduce a category the portable patch contract does not
            // document. Reintroducing an ETag anywhere must revisit this.
            case 412 -> MulticloudDbErrorCategory.NOT_FOUND;
            case 429 -> MulticloudDbErrorCategory.THROTTLED;
            case 449 -> MulticloudDbErrorCategory.TRANSIENT_FAILURE; // Retry with
            case 500, 502, 503 -> MulticloudDbErrorCategory.TRANSIENT_FAILURE;
            default -> MulticloudDbErrorCategory.PROVIDER_ERROR;
        };
    }

    /**
     * Whether a blind retry of the identical request could succeed.
     * <p>
     * 412 is deliberately absent: it maps to {@code NOT_FOUND} because the only
     * precondition the adapter attaches is an existence predicate, and a path
     * that was absent when the write was evaluated will not materialize on a
     * retry of the same request. This matches the {@code retryable = false} the
     * patch path sets when it classifies a missing target itself.
     */
    private static boolean isRetryable(int statusCode) {
        return switch (statusCode) {
            case 429, 449, 500, 502, 503 -> true;
            default -> false;
        };
    }
}

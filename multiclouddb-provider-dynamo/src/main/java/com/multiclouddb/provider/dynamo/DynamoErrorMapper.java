// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.ProviderId;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps DynamoDB exceptions to portable {@link MulticloudDbException} instances.
 */
public final class DynamoErrorMapper {

    private DynamoErrorMapper() {
    }

    public static MulticloudDbException map(DynamoDbException e, String operation) {
        int httpStatus = e.statusCode();
        MulticloudDbErrorCategory category = mapCategory(e);
        boolean retryable = isRetryable(e);

        Map<String, String> details = new LinkedHashMap<>();
        if (e.awsErrorDetails() != null) {
            details.put("errorCode", e.awsErrorDetails().errorCode());
            details.put("serviceName", e.awsErrorDetails().serviceName());
        }
        if (e.requestId() != null) {
            details.put("requestId", e.requestId());
        }

        MulticloudDbError error = new MulticloudDbError(
                category,
                e.getMessage(),
                ProviderId.DYNAMO,
                operation,
                retryable,
                httpStatus,
                details);
        return new MulticloudDbException(error, e);
    }

    private static MulticloudDbErrorCategory mapCategory(DynamoDbException e) {
        int statusCode = e.statusCode();
        String errorCode = e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : "";

        // Map by error code first for precision
        return switch (errorCode) {
            // The generic mapper cannot determine which condition failed. CREATE
            // uses it for duplicate-key CONFLICT; operation-specific guarded
            // paths (update and patch) intercept it to return NOT_FOUND,
            // INVALID_REQUEST, or CONFLICT from their deterministic evidence.
            case "ConditionalCheckFailedException" -> MulticloudDbErrorCategory.CONFLICT;
            case "ResourceNotFoundException" -> MulticloudDbErrorCategory.NOT_FOUND;
            case "ValidationException" -> MulticloudDbErrorCategory.INVALID_REQUEST;
            case "AccessDeniedException" -> MulticloudDbErrorCategory.AUTHORIZATION_FAILED;
            case "UnrecognizedClientException" -> MulticloudDbErrorCategory.AUTHENTICATION_FAILED;
            case "ProvisionedThroughputExceededException",
                    "ThrottlingException",
                    "RequestLimitExceeded" ->
                MulticloudDbErrorCategory.THROTTLED;
            case "ItemCollectionSizeLimitExceededException" -> MulticloudDbErrorCategory.PERMANENT_FAILURE;
            default -> switch (statusCode) {
                case 400 -> MulticloudDbErrorCategory.INVALID_REQUEST;
                case 401, 403 -> MulticloudDbErrorCategory.AUTHENTICATION_FAILED;
                case 404 -> MulticloudDbErrorCategory.NOT_FOUND;
                case 500, 502, 503 -> MulticloudDbErrorCategory.TRANSIENT_FAILURE;
                default -> MulticloudDbErrorCategory.PROVIDER_ERROR;
            };
        };
    }

    private static boolean isRetryable(DynamoDbException e) {
        if (e.isThrottlingException()) {
            return true;
        }
        int statusCode = e.statusCode();
        return statusCode >= 500 && statusCode < 600;
    }
}

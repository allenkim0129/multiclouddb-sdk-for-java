// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.OperationNames;
import com.multiclouddb.api.OperationOptions;
import com.multiclouddb.api.PatchOperation;
import com.multiclouddb.api.ResourceAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies how {@link DynamoProviderClient} classifies a rejected atomic patch
 * from its {@code ALL_OLD} before-image, and the retry contract attached to each
 * portable category.
 * <p>
 * The DynamoDB side of the portable PATCH contract is deliberately stricter than
 * DynamoDB itself needs to be: {@code UpdateItem} would happily leave an
 * absolute {@code ttlExpiry} untouched, but Cosmos DB's native patch cannot
 * preserve its relative {@code ttl}, so both providers reject a TTL-bearing item
 * instead of diverging.
 */
class DynamoPatchConditionTest {

    private static final ResourceAddress ADDRESS = new ResourceAddress("db", "table");
    private static final MulticloudDbKey KEY = MulticloudDbKey.of("pk", "sk");

    private DynamoDbClient dynamo;
    private DynamoProviderClient client;

    @BeforeEach
    void setUp() {
        dynamo = mock(DynamoDbClient.class);
        client = new DynamoProviderClient(dynamo);
    }

    // ── the portable TTL guard ───────────────────────────────────────────────

    @Test
    @DisplayName("the atomic condition asserts attribute_not_exists(ttlExpiry)")
    void conditionExpressionGuardsAgainstSdkManagedTtl() {
        rejectWith(item(Map.of("status", AttributeValue.fromS("draft"))));

        assertThrows(MulticloudDbException.class, () -> client.patch(ADDRESS, KEY,
                List.of(PatchOperation.set("/status", "live")), OperationOptions.defaults()));

        ArgumentCaptor<UpdateItemRequest> captor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamo).updateItem(captor.capture());
        assertTrue(captor.getValue().conditionExpression()
                        .contains("attribute_not_exists(" + DynamoConstants.ATTR_TTL_EXPIRY + ")"),
                "the TTL guard must ride in the same atomic condition as the write - checking it "
                        + "with a separate read would leave a window where Cosmos rejects and "
                        + "DynamoDB applies");
    }

    /**
     * Pins the behaviour the adapter Javadoc describes: a TTL-bearing item is
     * <em>rejected</em>, not patched with its expiry preserved.
     */
    @Test
    @DisplayName("patching a TTL-bearing item is UNSUPPORTED_CAPABILITY, not a preserved expiry")
    void ttlBearingItemIsUnsupportedCapability() {
        rejectWith(item(Map.of(
                "status", AttributeValue.fromS("draft"),
                DynamoConstants.ATTR_TTL_EXPIRY, AttributeValue.fromN("1893456000"))));

        MulticloudDbException error = assertThrows(MulticloudDbException.class,
                () -> client.patch(ADDRESS, KEY, List.of(PatchOperation.set("/status", "live")),
                        OperationOptions.defaults()));

        assertEquals(MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY, error.error().category(),
                "DynamoDB could have preserved the absolute ttlExpiry, but Cosmos DB cannot "
                        + "preserve its relative ttl, so the portable contract rejects both");
        assertEquals(OperationNames.PATCH, error.error().operation());
        assertFalse(error.error().retryable(),
                "the item still carries its expiry, so an identical retry reproduces this");
    }

    // ── classification from the ALL_OLD before-image ─────────────────────────

    @Test
    @DisplayName("a missing required path is NOT_FOUND and is not retryable")
    void missingRequiredPathIsNotFound() {
        rejectWith(item(Map.of("title", AttributeValue.fromS("only"))));

        MulticloudDbException error = assertThrows(MulticloudDbException.class,
                () -> client.patch(ADDRESS, KEY, List.of(PatchOperation.replace("/status", "live")),
                        OperationOptions.defaults()));

        assertEquals(MulticloudDbErrorCategory.NOT_FOUND, error.error().category());
        assertFalse(error.error().retryable(),
                "the before-image proves the target is absent - retrying cannot conjure it");
    }

    @Test
    @DisplayName("a nonnumeric INCREMENT target is INVALID_REQUEST and is not retryable")
    void nonnumericIncrementTargetIsInvalidRequest() {
        rejectWith(item(Map.of("value", AttributeValue.fromS("not-a-number"))));

        MulticloudDbException error = assertThrows(MulticloudDbException.class,
                () -> client.patch(ADDRESS, KEY, List.of(PatchOperation.increment("/value", 1)),
                        OperationOptions.defaults()));

        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, error.error().category());
        assertFalse(error.error().retryable(),
                "adding to a string has no portable meaning however often it is retried");
    }

    @Test
    @DisplayName("an INCREMENT that would overflow signed 64-bit is INVALID_REQUEST")
    void overflowingIncrementIsInvalidRequest() {
        rejectWith(item(Map.of("value", AttributeValue.fromN(Long.toString(Long.MAX_VALUE)))));

        MulticloudDbException error = assertThrows(MulticloudDbException.class,
                () -> client.patch(ADDRESS, KEY, List.of(PatchOperation.increment("/value", 1)),
                        OperationOptions.defaults()));

        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, error.error().category(),
                "the BETWEEN bound rejects atomically what Cosmos DB rejects too");
        assertFalse(error.error().retryable());
    }

    // ── the one retryable PATCH category ─────────────────────────────────────

    /**
     * {@code CONFLICT} is the only retryable portable PATCH category. The
     * condition rejected the {@code UpdateItem} atomically, so no operation in
     * the list was applied and re-issuing the identical request cannot
     * double-apply an {@code INCREMENT}. A {@code create}-duplicate
     * {@code CONFLICT} stays non-retryable for the opposite reason: the
     * conflicting key is still there.
     */
    @Test
    @DisplayName("an unprovable condition failure is a retryable CONFLICT")
    void unprovableConditionFailureIsRetryableConflict() {
        ConditionalCheckFailedException rejection =
                rejectWith(item(Map.of("status", AttributeValue.fromS("draft"))));

        MulticloudDbException error = assertThrows(MulticloudDbException.class,
                () -> client.patch(ADDRESS, KEY, List.of(PatchOperation.replace("/status", "live")),
                        OperationOptions.defaults()));

        assertEquals(MulticloudDbErrorCategory.CONFLICT, error.error().category(),
                "the before-image satisfies every term, so no deterministic cause can be named");
        assertTrue(error.error().retryable(),
                "patch is atomic, so a rejected conditional write applied no operation. "
                        + "MulticloudDbClient.patch documents CONFLICT as safe to retry, and "
                        + "retryable() is how a retry layer reads that contract");
        assertSame(rejection, error.getCause(),
                "the native rejection stays reachable for diagnostics");
        assertEquals(400, error.error().statusCode(),
                "the reclassified envelope keeps the provider's raw status");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** A before-image always carries the key attributes DynamoDB stores. */
    private static Map<String, AttributeValue> item(Map<String, AttributeValue> fields) {
        Map<String, AttributeValue> image = new LinkedHashMap<>();
        image.put(DynamoConstants.ATTR_PARTITION_KEY, AttributeValue.fromS(KEY.partitionKey()));
        image.put(DynamoConstants.ATTR_SORT_KEY, AttributeValue.fromS(KEY.sortKey()));
        image.putAll(fields);
        return image;
    }

    private ConditionalCheckFailedException rejectWith(Map<String, AttributeValue> beforeImage) {
        ConditionalCheckFailedException rejection = ConditionalCheckFailedException.builder()
                .message("The conditional request failed")
                .item(beforeImage)
                .statusCode(400)
                .awsErrorDetails(AwsErrorDetails.builder()
                        .errorCode("ConditionalCheckFailedException")
                        .build())
                .build();
        when(dynamo.updateItem(any(UpdateItemRequest.class))).thenThrow(rejection);
        return rejection;
    }
}

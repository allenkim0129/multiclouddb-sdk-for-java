// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosDatabase;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.CosmosItemResponse;
import com.azure.cosmos.models.CosmosPatchItemRequestOptions;
import com.azure.cosmos.models.CosmosPatchOperations;
import com.azure.cosmos.models.PartitionKey;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.OperationNames;
import com.multiclouddb.api.OperationOptions;
import com.multiclouddb.api.PatchOperation;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies Cosmos's atomic conditional-patch guard and failure classification.
 * Stored-state requirements are evaluated by the server-side filter predicate;
 * no potentially stale pre-write read can reject an otherwise valid patch.
 */
class CosmosPatchPreconditionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ResourceAddress ADDRESS = new ResourceAddress("db", "container");
    private static final MulticloudDbKey KEY = MulticloudDbKey.of("pk", "sk");
    private static final String COSMOS_ID = "sk";
    private static final String ETAG = "\"etag-1\"";
    private static final String TTL_GUARD = "NOT IS_DEFINED(c[\"ttl\"])";

    /**
     * The precondition a {@code +1} increment on {@code /value} must carry: the
     * target must exist and the current value must leave room for the delta
     * inside the portable signed 64-bit result range.
     */
    private static final String INCREMENT_BY_ONE_GUARD =
            "FROM c WHERE " + TTL_GUARD + " AND IS_DEFINED(c[\"value\"]) "
                    + "AND IS_NUMBER(c[\"value\"]) "
                    + "AND (c[\"value\"] BETWEEN -9223372036854775809 AND 9223372036854775806)";

    @Test
    void directSpiPatchRejectsNullListBeforeCallingCosmos() {
        AtomicReference<CosmosClient> sdkClient = new AtomicReference<>();
        try (MockedConstruction<CosmosClientBuilder> ignored = mockConstruction(
                CosmosClientBuilder.class, (builder, context) -> {
                    when(builder.endpoint(anyString())).thenReturn(builder);
                    when(builder.key(anyString())).thenReturn(builder);
                    when(builder.contentResponseOnWriteEnabled(anyBoolean())).thenReturn(builder);
                    when(builder.gatewayMode()).thenReturn(builder);
                    when(builder.userAgentSuffix(anyString())).thenReturn(builder);
                    CosmosClient client = mock(CosmosClient.class);
                    sdkClient.set(client);
                    when(builder.buildClient()).thenReturn(client);
                })) {
            CosmosProviderClient client = new CosmosProviderClient(
                    MulticloudDbClientConfig.builder()
                            .provider(ProviderId.COSMOS)
                            .connection(Map.of(
                                    CosmosConstants.CONFIG_ENDPOINT,
                                    "https://example.documents.azure.com:443/",
                                    CosmosConstants.CONFIG_KEY, "dGVzdC1rZXk="))
                            .build());
            List<PatchOperation> operations = null;

            MulticloudDbException error = assertThrows(MulticloudDbException.class,
                    () -> client.patch(ADDRESS, KEY, operations, OperationOptions.defaults()));

            assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, error.error().category());
            assertEquals(OperationNames.PATCH, error.error().operation());
            verifyNoInteractions(sdkClient.get());
            client.close();
        }
    }

    // ── write guard selection ───────────────────────────────────────────────

    @Test
    @DisplayName("a pure INCREMENT patch writes under an integral-result bound, never an If-Match")
    void pureIncrementPatchCarriesAnIntegralResultBound() throws Exception {
        CosmosContainer container = mock(CosmosContainer.class);
        stubRead(container, MAPPER.createObjectNode().put("value", 4));
        stubPatch(container);

        withCosmos(container, client ->
                client.patch(ADDRESS, KEY, List.of(PatchOperation.increment("/value", 1)),
                        OperationOptions.defaults()));

        CosmosPatchItemRequestOptions options = capturePatchOptions(container);
        assertNull(options.getIfMatchETag(),
                "Cosmos evaluates increment server-side; an If-Match would turn concurrent "
                        + "increments into avoidable CONFLICTs");
        assertEquals(INCREMENT_BY_ONE_GUARD, options.getFilterPredicate(),
                "the range and numeric type must be checked atomically with the increment");
        assertEquals(INCREMENT_BY_ONE_GUARD, CosmosProviderClient.patchFilterPredicate(
                List.of(PatchOperation.increment("/value", 1))));
        verify(container, never()).readItem(anyString(), any(PartitionKey.class),
                any(CosmosItemRequestOptions.class), eq(ObjectNode.class));
    }

    @Test
    @DisplayName("a fractional INCREMENT carries type checking but no result bound")
    void fractionalIncrementCarriesNoResultBound() {
        assertEquals("FROM c WHERE " + TTL_GUARD
                        + " AND IS_DEFINED(c[\"value\"]) AND IS_NUMBER(c[\"value\"])",
                CosmosProviderClient.patchFilterPredicate(
                        List.of(PatchOperation.increment("/value", 1.5d))),
                "the portable domain only bounds integral results, so a fractional delta must "
                        + "not inherit a range check DynamoDB does not apply either");
    }

    @Test
    @DisplayName("a nested INCREMENT patch carries the same bound at depth")
    void nestedIncrementPatchCarriesTheSameBound() throws Exception {
        ObjectNode document = MAPPER.createObjectNode();
        document.putObject("counters").put("hits", 7);
        CosmosContainer container = mock(CosmosContainer.class);
        stubRead(container, document);
        stubPatch(container);

        withCosmos(container, client ->
                client.patch(ADDRESS, KEY, List.of(PatchOperation.increment("/counters/hits", 1)),
                        OperationOptions.defaults()));

        CosmosPatchItemRequestOptions options = capturePatchOptions(container);
        assertNull(options.getIfMatchETag(),
                "depth does not change the atomicity of the native increment");
        assertEquals("FROM c WHERE " + TTL_GUARD
                        + " AND IS_DEFINED(c[\"counters\"][\"hits\"])"
                        + " AND IS_NUMBER(c[\"counters\"][\"hits\"]) AND "
                        + "(c[\"counters\"][\"hits\"] BETWEEN -9223372036854775809 "
                        + "AND 9223372036854775806)",
                options.getFilterPredicate(),
                "depth does not change the overflow window either");
    }

    @Test
    @DisplayName("REPLACE, REMOVE, and nested SET use path terms plus the TTL guard")
    void strictAndNestedNonIncrementPatchesCarryAPathScopedPredicate() throws Exception {
        assertEquals("FROM c WHERE " + TTL_GUARD + " AND IS_DEFINED(c[\"status\"])",
                CosmosProviderClient.patchFilterPredicate(
                        List.of(PatchOperation.replace("/status", "live"))),
                "REPLACE is translated to a native set that would create a missing target");
        assertEquals("FROM c WHERE " + TTL_GUARD + " AND IS_DEFINED(c[\"status\"])",
                CosmosProviderClient.patchFilterPredicate(
                        List.of(PatchOperation.remove("/status"))),
                "Cosmos rejects a missing remove path with an untyped 400, not NOT_FOUND");
        assertEquals("FROM c WHERE " + TTL_GUARD + " AND IS_DEFINED(c[\"address\"])",
                CosmosProviderClient.patchFilterPredicate(
                        List.of(PatchOperation.set("/address/city", "Redmond"))),
                "a nested set must not create intermediate objects, so the parent is asserted");
        assertEquals("FROM c WHERE " + TTL_GUARD
                        + " AND IS_DEFINED(c[\"address\"][\"city\"])",
                CosmosProviderClient.patchFilterPredicate(
                        List.of(PatchOperation.replace("/address/city", "Redmond"))),
                "a nested strict path asserts the target itself");
        assertEquals("FROM c WHERE " + TTL_GUARD
                        + " AND IS_DEFINED(c[\"status\"]) AND IS_DEFINED(c[\"owner\"])",
                CosmosProviderClient.patchFilterPredicate(List.of(
                        PatchOperation.replace("/status", "live"),
                        PatchOperation.remove("/owner"))),
                "every strict path in the request contributes its own term");
        assertEquals("FROM c WHERE " + TTL_GUARD,
                CosmosProviderClient.patchFilterPredicate(
                        List.of(PatchOperation.set("/status", "live"))),
                "a top-level SET needs only the item-TTL portability guard");

        CosmosContainer container = mock(CosmosContainer.class);
        stubRead(container, MAPPER.createObjectNode().put("status", "draft"));
        stubPatch(container);

        withCosmos(container, client ->
                client.patch(ADDRESS, KEY, List.of(PatchOperation.replace("/status", "live")),
                        OperationOptions.defaults()));

        CosmosPatchItemRequestOptions options = capturePatchOptions(container);
        assertEquals("FROM c WHERE " + TTL_GUARD
                + " AND IS_DEFINED(c[\"status\"])", options.getFilterPredicate());
        assertNull(options.getIfMatchETag(),
                "application-field preconditions must stay path-scoped; an item-scoped ETag "
                        + "would fail on a concurrent write to an unrelated field");
    }

    @Test
    @DisplayName("a path segment cannot terminate the predicate's string literal")
    void predicatePathSegmentsAreEscaped() {
        assertEquals("FROM c WHERE " + TTL_GUARD + " AND IS_DEFINED(c[\"we\\\"ird\"])",
                CosmosProviderClient.patchFilterPredicate(
                        List.of(PatchOperation.replace("/we\"ird", "live"))));
        assertEquals("FROM c WHERE " + TTL_GUARD + " AND IS_DEFINED(c[\"back\\\\slash\"])",
                CosmosProviderClient.patchFilterPredicate(
                        List.of(PatchOperation.remove("/back\\slash"))));
        assertEquals("FROM c WHERE " + TTL_GUARD + " AND IS_DEFINED(c[\"line\\nbreak\"])",
                CosmosProviderClient.patchFilterPredicate(
                        List.of(PatchOperation.remove("/line\nbreak"))));
    }

    // ── disjoint concurrent patches must not collide ────────────────────────

    @Test
    @DisplayName("two concurrent patches of disjoint fields both land — neither is a CONFLICT")
    void concurrentDisjointFieldPatchesBothLand() throws Exception {
        CosmosContainer container = mock(CosmosContainer.class);
        // Models Cosmos: an item-scoped If-Match fails once any writer has bumped
        // the ETag, whatever field that writer touched. A path-scoped filter
        // predicate is unaffected by a write to a field it does not name.
        stubServerPatch(container, new AtomicReference<>(ETAG));

        withCosmos(container, client -> {
            client.patch(ADDRESS, KEY, List.of(PatchOperation.replace("/status", "live")),
                    OperationOptions.defaults());
            assertDoesNotThrow(() -> client.patch(ADDRESS, KEY,
                    List.of(PatchOperation.replace("/owner", "grace")),
                    OperationOptions.defaults()),
                    "a concurrent write to /status must not fail a patch addressing /owner; "
                            + "Dynamo's attribute_exists condition also lets this through");
        });

        ArgumentCaptor<CosmosPatchItemRequestOptions> captor =
                ArgumentCaptor.forClass(CosmosPatchItemRequestOptions.class);
        verify(container, times(2)).patchItem(eq(COSMOS_ID), any(PartitionKey.class),
                any(CosmosPatchOperations.class), captor.capture(), eq(ObjectNode.class));
        for (CosmosPatchItemRequestOptions options : captor.getAllValues()) {
            assertNull(options.getIfMatchETag(),
                    "an item-scoped ETag is what made disjoint concurrent patches collide");
        }
        assertEquals("FROM c WHERE " + TTL_GUARD + " AND IS_DEFINED(c[\"owner\"])",
                captor.getAllValues().get(1).getFilterPredicate(),
                "the second patch's precondition names only the field it addresses");
    }

    @Test
    @DisplayName("an INCREMENT mixed with a strict operation stays concurrency-safe")
    void incrementMixedWithAStrictOperationOnlyGuardsTheStrictPath() throws Exception {
        assertEquals("FROM c WHERE " + TTL_GUARD
                        + " AND IS_DEFINED(c[\"value\"]) AND IS_NUMBER(c[\"value\"]) "
                        + "AND (c[\"value\"] BETWEEN -9223372036854775809 AND 9223372036854775806) "
                        + "AND IS_DEFINED(c[\"status\"])",
                CosmosProviderClient.patchFilterPredicate(List.of(
                        PatchOperation.increment("/value", 1),
                        PatchOperation.replace("/status", "live"))),
                "each operation contributes only its own terms; the increment adds an existence "
                        + "check and a result bound, never an item-scoped guard");

        CosmosContainer container = mock(CosmosContainer.class);
        stubServerPatch(container, new AtomicReference<>(ETAG));

        withCosmos(container, client -> {
            client.patch(ADDRESS, KEY, List.of(
                    PatchOperation.increment("/value", 1),
                    PatchOperation.replace("/status", "live")), OperationOptions.defaults());
            assertDoesNotThrow(() -> client.patch(ADDRESS, KEY, List.of(
                    PatchOperation.increment("/value", 1),
                    PatchOperation.replace("/status", "archived")), OperationOptions.defaults()),
                    "sharing a request with a strict operation must not reintroduce the "
                            + "lost-increment problem patch exists to avoid");
        });

        ArgumentCaptor<CosmosPatchItemRequestOptions> captor =
                ArgumentCaptor.forClass(CosmosPatchItemRequestOptions.class);
        verify(container, times(2)).patchItem(eq(COSMOS_ID), any(PartitionKey.class),
                any(CosmosPatchOperations.class), captor.capture(), eq(ObjectNode.class));
        for (CosmosPatchItemRequestOptions options : captor.getAllValues()) {
            assertNull(options.getIfMatchETag(),
                    "an item-scoped ETag would make concurrent increments collide");
            assertFalse(options.getFilterPredicate().contains("_etag"),
                    "the precondition stays path-scoped, so a concurrent write to an unrelated "
                            + "field cannot falsify it");
        }
    }

    @Test
    @DisplayName("a failed filter predicate is classified from current state, not assumed")
    void failedFilterPredicateIsClassifiedFromCurrentState() throws Exception {
        CosmosContainer container = mock(CosmosContainer.class);
        stubRead(container, MAPPER.createObjectNode());
        stubPatchFailure(container, cosmosException(412));

        withCosmos(container, client -> {
            MulticloudDbException error = assertThrows(MulticloudDbException.class,
                    () -> client.patch(ADDRESS, KEY,
                            List.of(PatchOperation.replace("/status", "live")),
                            OperationOptions.defaults()));
            assertEquals(MulticloudDbErrorCategory.NOT_FOUND, error.error().category(),
                    "the re-read proves the addressed path vanished, the same state Dynamo "
                            + "reports as NOT_FOUND from its before-image");
            assertEquals(412, error.error().statusCode(),
                    "the reclassified envelope keeps the provider's raw status for diagnostics");
            assertFalse(error.error().retryable(),
                    "a proven missing target is deterministic - an identical retry reproduces it");
        });
    }

    @Test
    @DisplayName("a raced INCREMENT overflow is INVALID_REQUEST, not a silently stored value")
    void racedIncrementOverflowIsInvalidRequest() throws Exception {
        CosmosContainer container = mock(CosmosContainer.class);
        stubRead(container, MAPPER.createObjectNode().put("value", Long.MAX_VALUE));
        stubPatchFailure(container, cosmosException(412));

        withCosmos(container, client -> {
            MulticloudDbException error = assertThrows(MulticloudDbException.class,
                    () -> client.patch(ADDRESS, KEY,
                            List.of(PatchOperation.increment("/value", 1)),
                            OperationOptions.defaults()));
            assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, error.error().category(),
                    "the BETWEEN bound catches the overflow atomically, so Cosmos rejects "
                            + "exactly what DynamoDB rejects");
        });
    }

    @Test
    @DisplayName("an unprovable precondition failure is CONFLICT, not an invented NOT_FOUND")
    void unprovablePreconditionFailureIsConflict() throws Exception {
        CosmosContainer container = mock(CosmosContainer.class);
        stubRead(container, MAPPER.createObjectNode().put("status", "draft"));
        stubPatchFailure(container, cosmosException(412));

        withCosmos(container, client -> {
            MulticloudDbException error = assertThrows(MulticloudDbException.class,
                    () -> client.patch(ADDRESS, KEY,
                            List.of(PatchOperation.replace("/status", "live")),
                            OperationOptions.defaults()));
            assertEquals(MulticloudDbErrorCategory.CONFLICT, error.error().category(),
                    "current state satisfies every term, so the adapter cannot name a cause. "
                            + "DynamoDB reports the same unclassifiable condition failure as "
                            + "CONFLICT rather than claiming a deterministic validation failure");
            assertTrue(error.error().retryable(),
                    "patch is atomic, so a rejected conditional write applied no operation. "
                            + "MulticloudDbClient.patch documents CONFLICT as safe to retry, and "
                            + "the retryable() hint is how a retry layer reads that contract");
        });
    }

    /**
     * Pins the one asymmetry in the PATCH retry contract: {@code CONFLICT} is
     * retryable here even though a {@code create}-duplicate {@code CONFLICT} is
     * not. A patch {@code CONFLICT} proves the atomic write was rejected with
     * nothing applied, so re-issuing it cannot double-apply an {@code INCREMENT};
     * every other PATCH category names a deterministic cause an identical retry
     * would reproduce.
     */
    @Test
    @DisplayName("CONFLICT is the only retryable portable PATCH category")
    void conflictIsTheOnlyRetryablePatchCategory() throws Exception {
        CosmosContainer container = mock(CosmosContainer.class);
        stubRead(container, MAPPER.createObjectNode()
                .put("value", "not-a-number")
                .put("status", "draft"));
        stubPatchFailure(container, cosmosException(412));

        withCosmos(container, client -> {
            MulticloudDbException invalid = assertThrows(MulticloudDbException.class,
                    () -> client.patch(ADDRESS, KEY,
                            List.of(PatchOperation.increment("/value", 1)),
                            OperationOptions.defaults()));
            assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, invalid.error().category());
            assertFalse(invalid.error().retryable(),
                    "a nonnumeric target is deterministic - retrying reproduces it");

            MulticloudDbException conflict = assertThrows(MulticloudDbException.class,
                    () -> client.patch(ADDRESS, KEY,
                            List.of(PatchOperation.replace("/status", "live")),
                            OperationOptions.defaults()));
            assertEquals(MulticloudDbErrorCategory.CONFLICT, conflict.error().category());
            assertTrue(conflict.error().retryable(),
                    "only the unprovable race is worth retrying");
        });
    }

    // ── error categories classified after atomic rejection ─────────────────

    @Test
    @DisplayName("a missing document is NOT_FOUND for a pure INCREMENT patch")
    void missingDocumentIsNotFoundForAPureIncrementPatch() throws Exception {
        CosmosContainer container = mock(CosmosContainer.class);
        stubPatchFailure(container, cosmosException(404));

        withCosmos(container, client -> {
            MulticloudDbException error = assertThrows(MulticloudDbException.class,
                    () -> client.patch(ADDRESS, KEY,
                            List.of(PatchOperation.increment("/value", 1)),
                            OperationOptions.defaults()));
            assertEquals(MulticloudDbErrorCategory.NOT_FOUND, error.error().category());
        });

        verify(container).patchItem(anyString(), any(PartitionKey.class),
                any(CosmosPatchOperations.class), any(CosmosPatchItemRequestOptions.class),
                eq(ObjectNode.class));
    }

    @Test
    @DisplayName("a missing INCREMENT target is NOT_FOUND")
    void missingIncrementTargetIsNotFound() throws Exception {
        CosmosContainer container = mock(CosmosContainer.class);
        stubRead(container, MAPPER.createObjectNode().put("title", "present"));
        stubPatchFailure(container, cosmosException(412));

        withCosmos(container, client -> {
            MulticloudDbException error = assertThrows(MulticloudDbException.class,
                    () -> client.patch(ADDRESS, KEY,
                            List.of(PatchOperation.increment("/value", 1)),
                            OperationOptions.defaults()));
            assertEquals(MulticloudDbErrorCategory.NOT_FOUND, error.error().category(),
                    "the failed atomic predicate is classified from current state");
        });
    }

    @Test
    @DisplayName("a nonnumeric target and an integral overflow stay INVALID_REQUEST")
    void nonNumericTargetAndOverflowAreClassifiedAfterAtomicRejection() throws Exception {
        CosmosContainer container = mock(CosmosContainer.class);
        stubRead(container, MAPPER.createObjectNode().put("value", "not-a-number"));
        stubPatchFailure(container, cosmosException(412));
        withCosmos(container, client -> {
            MulticloudDbException error = assertThrows(MulticloudDbException.class,
                    () -> client.patch(ADDRESS, KEY,
                            List.of(PatchOperation.increment("/value", 1)),
                            OperationOptions.defaults()));
            assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, error.error().category());
        });

        CosmosContainer overflowContainer = mock(CosmosContainer.class);
        stubRead(overflowContainer, MAPPER.createObjectNode().put("value", Long.MAX_VALUE));
        stubPatchFailure(overflowContainer, cosmosException(412));
        withCosmos(overflowContainer, client -> {
            MulticloudDbException error = assertThrows(MulticloudDbException.class,
                    () -> client.patch(ADDRESS, KEY,
                            List.of(PatchOperation.increment("/value", 1)),
                            OperationOptions.defaults()));
            assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, error.error().category(),
                    "the atomic range predicate keeps the result inside the portable domain");
        });
    }

    @Test
    @DisplayName("a mid-flight document delete remains NOT_FOUND")
    void deletedDocumentDoesNotSynthesizeAConflict() throws Exception {
        CosmosContainer container = mock(CosmosContainer.class);
        stubRead(container, MAPPER.createObjectNode().put("value", 4));
        CosmosException deletedMidFlight = cosmosException(404);
        when(container.patchItem(eq(COSMOS_ID), any(PartitionKey.class),
                any(CosmosPatchOperations.class), any(CosmosPatchItemRequestOptions.class),
                eq(ObjectNode.class)))
                .thenThrow(deletedMidFlight);

        withCosmos(container, client -> {
            MulticloudDbException error = assertThrows(MulticloudDbException.class,
                    () -> client.patch(ADDRESS, KEY,
                            List.of(PatchOperation.increment("/value", 1)),
                            OperationOptions.defaults()));
            assertEquals(MulticloudDbErrorCategory.NOT_FOUND, error.error().category(),
                    "a missing document is still NOT_FOUND, not an invented CONFLICT");
        });
    }

    // ── raced INCREMENT classification ──────────────────────────────────────

    @Test
    @DisplayName("a raced INCREMENT whose target vanished is NOT_FOUND, not INVALID_REQUEST")
    void racedIncrementOnAVanishedTargetIsNotFound() throws Exception {
        CosmosContainer container = mock(CosmosContainer.class);
        // Cosmos rejects a target that vanished at evaluation time.
        stubRead(container, MAPPER.createObjectNode().put("title", "still here"));
        stubPatchFailure(container, cosmosException(400));

        withCosmos(container, client -> {
            MulticloudDbException error = assertThrows(MulticloudDbException.class,
                    () -> client.patch(ADDRESS, KEY,
                            List.of(PatchOperation.increment("/value", 1)),
                            OperationOptions.defaults()));
            assertEquals(MulticloudDbErrorCategory.NOT_FOUND, error.error().category(),
                    "Cosmos must not report INVALID_REQUEST for a vanished target");
            assertEquals(400, error.error().statusCode(),
                    "the reclassified envelope keeps the provider's raw status for diagnostics");
        });

        verify(container).readItem(eq(COSMOS_ID), any(PartitionKey.class),
                any(CosmosItemRequestOptions.class), eq(ObjectNode.class));
    }

    @Test
    @DisplayName("a raced INCREMENT on a deleted document is NOT_FOUND")
    void racedIncrementOnADeletedDocumentIsNotFound() throws Exception {
        CosmosContainer container = mock(CosmosContainer.class);
        CosmosException notFound = cosmosException(404);
        when(container.readItem(eq(COSMOS_ID), any(PartitionKey.class),
                any(CosmosItemRequestOptions.class), eq(ObjectNode.class)))
                .thenThrow(notFound);
        stubPatchFailure(container, cosmosException(400));

        withCosmos(container, client -> {
            MulticloudDbException error = assertThrows(MulticloudDbException.class,
                    () -> client.patch(ADDRESS, KEY,
                            List.of(PatchOperation.increment("/value", 1)),
                            OperationOptions.defaults()));
            assertEquals(MulticloudDbErrorCategory.NOT_FOUND, error.error().category());
        });
    }

    @Test
    @DisplayName("a raced INCREMENT on a retyped target stays INVALID_REQUEST — now proven")
    void racedIncrementOnARetypedTargetIsInvalidRequest() throws Exception {
        CosmosContainer container = mock(CosmosContainer.class);
        stubRead(container, MAPPER.createObjectNode().put("value", "not-a-number"));
        stubPatchFailure(container, cosmosException(400));

        withCosmos(container, client -> {
            MulticloudDbException error = assertThrows(MulticloudDbException.class,
                    () -> client.patch(ADDRESS, KEY,
                            List.of(PatchOperation.increment("/value", 1)),
                            OperationOptions.defaults()));
            assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, error.error().category(),
                    "a nonnumeric target is INVALID_REQUEST on every provider");
        });
    }

    @Test
    @DisplayName("a 400 the re-read cannot explain keeps the raw INVALID_REQUEST mapping")
    void unexplainedBadRequestKeepsTheInvalidRequestMapping() throws Exception {
        CosmosContainer container = mock(CosmosContainer.class);
        // Current state satisfies every portable precondition, so the 400 had
        // some other cause and no NOT_FOUND may be invented.
        stubRead(container, MAPPER.createObjectNode().put("value", 4));
        stubPatchFailure(container, cosmosException(400));

        withCosmos(container, client -> {
            MulticloudDbException error = assertThrows(MulticloudDbException.class,
                    () -> client.patch(ADDRESS, KEY,
                            List.of(PatchOperation.increment("/value", 1)),
                            OperationOptions.defaults()));
            assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, error.error().category());
        });
    }

    @Test
    @DisplayName("a failed follow-up read leaves the original classification authoritative")
    void failedFollowUpReadDoesNotInventAClassification() throws Exception {
        CosmosContainer container = mock(CosmosContainer.class);
        CosmosException unavailable = cosmosException(503);
        when(container.readItem(eq(COSMOS_ID), any(PartitionKey.class),
                any(CosmosItemRequestOptions.class), eq(ObjectNode.class)))
                .thenThrow(unavailable);
        stubPatchFailure(container, cosmosException(400));

        withCosmos(container, client -> {
            MulticloudDbException error = assertThrows(MulticloudDbException.class,
                    () -> client.patch(ADDRESS, KEY,
                            List.of(PatchOperation.increment("/value", 1)),
                            OperationOptions.defaults()));
            assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, error.error().category(),
                    "an unavailable follow-up read cannot prove a missing target");
        });
    }

    // ── FR-185: a missing addressed field is NOT_FOUND on every provider ────

    @Test
    @DisplayName("FR-185: REPLACE, REMOVE, and INCREMENT on a missing field are all NOT_FOUND")
    void missingAddressedFieldIsNotFoundForEveryStrictOperation() throws Exception {
        CosmosContainer container = mock(CosmosContainer.class);
        stubRead(container, MAPPER.createObjectNode().put("title", "present"));
        stubPatchFailure(container, cosmosException(412));

        withCosmos(container, client -> {
            for (PatchOperation operation : List.of(
                    PatchOperation.replace("/status", "live"),
                    PatchOperation.remove("/status"),
                    PatchOperation.increment("/value", 1))) {
                MulticloudDbException error = assertThrows(MulticloudDbException.class,
                        () -> client.patch(ADDRESS, KEY, List.of(operation),
                                OperationOptions.defaults()),
                        () -> operation.type() + " on a missing field must fail");
                assertEquals(MulticloudDbErrorCategory.NOT_FOUND, error.error().category(),
                        () -> "FR-185 requires NOT_FOUND for " + operation.type()
                                + " on a missing addressed field, on every provider");
            }
        });

        verify(container, times(3)).patchItem(anyString(), any(PartitionKey.class),
                any(CosmosPatchOperations.class), any(CosmosPatchItemRequestOptions.class),
                eq(ObjectNode.class));
    }

    @Test
    @DisplayName("an SDK-managed TTL item is rejected without mutation")
    void itemTtlIsRejectedAsUnsupportedCapability() throws Exception {
        CosmosContainer container = mock(CosmosContainer.class);
        stubRead(container, MAPPER.createObjectNode().put("title", "before").put("ttl", 3600));
        stubPatchFailure(container, cosmosException(412));

        withCosmos(container, client -> {
            MulticloudDbException error = assertThrows(MulticloudDbException.class,
                    () -> client.patch(ADDRESS, KEY,
                            List.of(PatchOperation.set("/status", "after")),
                            OperationOptions.defaults()));
            assertEquals(MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY,
                    error.error().category());
            assertEquals("patch_on_ttl_item_unsupported",
                    error.error().providerDetails().get("reason"));
        });
    }

    @Test
    @DisplayName("failure classification reuses the rejecting request's session token")
    void rejectionSessionTokenIsUsedForClassificationRead() throws Exception {
        CosmosContainer container = mock(CosmosContainer.class);
        CosmosException rejected = cosmosException(412);
        when(rejected.getResponseHeaders()).thenReturn(Map.of(
                CosmosConstants.HEADER_SESSION_TOKEN, "session-token"));
        stubRead(container, MAPPER.createObjectNode());
        stubPatchFailure(container, rejected);

        withCosmos(container, client -> assertThrows(MulticloudDbException.class,
                () -> client.patch(ADDRESS, KEY,
                        List.of(PatchOperation.replace("/status", "after")),
                        OperationOptions.defaults())));

        ArgumentCaptor<CosmosItemRequestOptions> options =
                ArgumentCaptor.forClass(CosmosItemRequestOptions.class);
        verify(container).readItem(eq(COSMOS_ID), any(PartitionKey.class),
                options.capture(), eq(ObjectNode.class));
        assertEquals("session-token", options.getValue().getSessionToken());
    }

    // ── state classification (no Cosmos SDK involved) ───────────────────────

    @Test
    void strictMissingPathIsClassifiedFromRejectedState() {
        ObjectNode document = MAPPER.createObjectNode().put("title", "present");

        MulticloudDbException error = assertThrows(MulticloudDbException.class,
                () -> CosmosProviderClient.validatePatchPreconditionState(document,
                        List.of(PatchOperation.replace("/status", "new"))));

        assertEquals(MulticloudDbErrorCategory.NOT_FOUND, error.error().category());
    }

    @Test
    void invalidIncrementTargetAndOverflowAreClassifiedFromRejectedState() {
        ObjectNode stringDocument = MAPPER.createObjectNode().put("value", "not-a-number");
        MulticloudDbException typeError = assertThrows(MulticloudDbException.class,
                () -> CosmosProviderClient.validatePatchPreconditionState(stringDocument,
                        List.of(PatchOperation.increment("/value", 1))));
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, typeError.error().category());

        ObjectNode overflowDocument = MAPPER.createObjectNode().put("value", Long.MAX_VALUE);
        MulticloudDbException overflowError = assertThrows(MulticloudDbException.class,
                () -> CosmosProviderClient.validatePatchPreconditionState(overflowDocument,
                        List.of(PatchOperation.increment("/value", 1))));
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, overflowError.error().category());
    }

    @Test
    void validStrictStateDoesNotExplainARejectedWrite() {
        ObjectNode document = MAPPER.createObjectNode().put("value", 4);

        assertDoesNotThrow(() -> CosmosProviderClient.validatePatchPreconditionState(document,
                List.of(PatchOperation.increment("/value", 1))));
    }

    // ── harness ─────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface Scenario {
        void run(CosmosProviderClient client) throws Exception;
    }

    /**
     * Builds a {@link CosmosProviderClient} whose SDK client resolves
     * {@code db/container} to {@code container}, following the
     * {@code mockConstruction(CosmosClientBuilder.class, ...)} style already used
     * above so no emulator or network is needed.
     */
    private static void withCosmos(CosmosContainer container, Scenario scenario) throws Exception {
        CosmosDatabase database = mock(CosmosDatabase.class);
        when(database.getContainer(ADDRESS.collection())).thenReturn(container);
        try (MockedConstruction<CosmosClientBuilder> ignored = mockConstruction(
                CosmosClientBuilder.class, (builder, context) -> {
                    when(builder.endpoint(anyString())).thenReturn(builder);
                    when(builder.key(anyString())).thenReturn(builder);
                    when(builder.contentResponseOnWriteEnabled(anyBoolean())).thenReturn(builder);
                    when(builder.gatewayMode()).thenReturn(builder);
                    when(builder.userAgentSuffix(anyString())).thenReturn(builder);
                    CosmosClient sdkClient = mock(CosmosClient.class);
                    when(sdkClient.getDatabase(ADDRESS.database())).thenReturn(database);
                    when(builder.buildClient()).thenReturn(sdkClient);
                })) {
            CosmosProviderClient client = newClient();
            try {
                scenario.run(client);
            } finally {
                client.close();
            }
        }
    }

    private static CosmosProviderClient newClient() {
        return new CosmosProviderClient(MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection(Map.of(
                        CosmosConstants.CONFIG_ENDPOINT, "https://example.documents.azure.com:443/",
                        CosmosConstants.CONFIG_KEY, "dGVzdC1rZXk="))
                .build());
    }

    @SuppressWarnings("unchecked")
    private static CosmosItemResponse<ObjectNode> itemResponse(ObjectNode document) {
        CosmosItemResponse<ObjectNode> response = mock(CosmosItemResponse.class);
        when(response.getItem()).thenReturn(document);
        when(response.getETag()).thenReturn(ETAG);
        return response;
    }

    private static void stubRead(CosmosContainer container, ObjectNode document) {
        CosmosItemResponse<ObjectNode> response = itemResponse(document);
        when(container.readItem(eq(COSMOS_ID), any(PartitionKey.class),
                any(CosmosItemRequestOptions.class), eq(ObjectNode.class)))
                .thenReturn(response);
    }

    @SuppressWarnings("unchecked")
    private static void stubPatch(CosmosContainer container) {
        CosmosItemResponse<ObjectNode> response = mock(CosmosItemResponse.class);
        when(container.patchItem(eq(COSMOS_ID), any(PartitionKey.class),
                any(CosmosPatchOperations.class), any(CosmosPatchItemRequestOptions.class),
                eq(ObjectNode.class))).thenReturn(response);
    }

    private static void stubPatchFailure(CosmosContainer container, CosmosException failure) {
        when(container.patchItem(eq(COSMOS_ID), any(PartitionKey.class),
                any(CosmosPatchOperations.class), any(CosmosPatchItemRequestOptions.class),
                eq(ObjectNode.class))).thenThrow(failure);
    }

    /**
     * Models Cosmos's own precondition semantics: a request carrying an
     * item-scoped {@code If-Match} fails with 412 once <em>any</em> writer has
     * bumped the item ETag, while a path-scoped filter predicate is unaffected by
     * a write to a field it does not name. Each successful write bumps the ETag,
     * so a second patch of a disjoint field exercises the concurrency behavior
     * this test protects.
     */
    @SuppressWarnings("unchecked")
    private static void stubServerPatch(CosmosContainer container, AtomicReference<String> serverEtag) {
        CosmosItemResponse<ObjectNode> response = mock(CosmosItemResponse.class);
        CosmosException staleEtag = cosmosException(412);
        when(container.patchItem(eq(COSMOS_ID), any(PartitionKey.class),
                any(CosmosPatchOperations.class), any(CosmosPatchItemRequestOptions.class),
                eq(ObjectNode.class))).thenAnswer(invocation -> {
                    CosmosPatchItemRequestOptions options = invocation.getArgument(3);
                    String ifMatch = options.getIfMatchETag();
                    if (ifMatch != null && !ifMatch.equals(serverEtag.get())) {
                        throw staleEtag;
                    }
                    serverEtag.set("\"etag-" + UUID.randomUUID() + "\"");
                    return response;
                });
    }

    private static CosmosPatchItemRequestOptions capturePatchOptions(CosmosContainer container) {
        ArgumentCaptor<CosmosPatchItemRequestOptions> captor =
                ArgumentCaptor.forClass(CosmosPatchItemRequestOptions.class);
        verify(container).patchItem(eq(COSMOS_ID), any(PartitionKey.class),
                any(CosmosPatchOperations.class), captor.capture(), eq(ObjectNode.class));
        CosmosPatchItemRequestOptions options = captor.getValue();
        assertNotNull(options, "the adapter must always pass patch request options");
        return options;
    }

    private static CosmosException cosmosException(int statusCode) {
        CosmosException exception = mock(CosmosException.class);
        when(exception.getStatusCode()).thenReturn(statusCode);
        when(exception.getSubStatusCode()).thenReturn(0);
        when(exception.getMessage()).thenReturn("Mock Cosmos error " + statusCode);
        when(exception.getActivityId()).thenReturn(null);
        when(exception.getRequestCharge()).thenReturn(0.0);
        return exception;
    }
}
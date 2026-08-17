// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.OperationNames;
import com.multiclouddb.api.PatchOperation;
import com.multiclouddb.api.ProviderId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the portable patch contract enforced by {@link PatchValidator}.
 * <p>
 * Every rule here exists to prevent a request that behaves differently on
 * Cosmos DB, DynamoDB and Spanner from reaching a provider adapter at all. If
 * one of these assertions is relaxed, at least one provider silently diverges —
 * so each test names the divergence it is preventing.
 */
class PatchValidatorTest {

    private static final ProviderId PROVIDER = ProviderId.fromId("fake-patch-validator-test");
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private static MulticloudDbException validateExpectingFailure(List<PatchOperation> ops) {
        return assertThrows(MulticloudDbException.class, () -> PatchValidator.validate(ops, PROVIDER));
    }

    private static void assertInvalidRequest(List<PatchOperation> ops) {
        MulticloudDbException e = validateExpectingFailure(ops);
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, e.error().category(),
                "portable contract violations must surface as INVALID_REQUEST");
        assertEquals(OperationNames.PATCH, e.error().operation(),
                "error envelope must attribute the failure to the patch operation");
        assertEquals(PROVIDER, e.error().provider());
    }

    @Test
    @DisplayName("accepts a well-formed mixed-operation patch")
    void acceptsValidOperations() {
        PatchValidator.validate(List.of(
                PatchOperation.set("/status", "active"),
                PatchOperation.replace("/title", "new"),
                PatchOperation.remove("/obsolete"),
                PatchOperation.increment("/counter", 1)), PROVIDER);
    }

    @Test
    @DisplayName("rejects an empty or null operation list")
    void rejectsEmptyOperations() {
        assertInvalidRequest(List.of());
        assertInvalidRequest(null);
    }

    @Test
    @DisplayName("rejects a null entry inside the operation list")
    void rejectsNullEntry() {
        List<PatchOperation> ops = new ArrayList<>();
        ops.add(PatchOperation.set("/a", 1));
        ops.add(null);
        assertInvalidRequest(ops);
    }

    /**
     * Cosmos DB caps a patch at 10 operations. Allowing 11 would mean a request
     * that succeeds on DynamoDB fails on Cosmos — the exact divergence the SDK
     * exists to prevent.
     */
    @Test
    @DisplayName("rejects more operations than Cosmos DB's native limit")
    void rejectsTooManyOperations() {
        List<PatchOperation> ops = new ArrayList<>();
        for (int i = 0; i <= MulticloudDbClient.MAX_PATCH_OPERATIONS; i++) {
            ops.add(PatchOperation.set("/f" + i, i));
        }
        assertEquals(11, ops.size());
        assertInvalidRequest(ops);
    }

    @Test
    @DisplayName("accepts exactly the maximum number of operations")
    void acceptsExactlyMaxOperations() {
        List<PatchOperation> ops = new ArrayList<>();
        for (int i = 0; i < MulticloudDbClient.MAX_PATCH_OPERATIONS; i++) {
            ops.add(PatchOperation.set("/f" + i, i));
        }
        PatchValidator.validate(ops, PROVIDER);
    }

    @Test
    @DisplayName("rejects paths that are not absolute JSON Pointers")
    void rejectsRelativePaths() {
        assertInvalidRequest(List.of(PatchOperation.set("status", "active")));
        assertInvalidRequest(List.of(PatchOperation.set("", "active")));
    }

    @Test
    @DisplayName("rejects empty path segments")
    void rejectsEmptySegments() {
        assertInvalidRequest(List.of(PatchOperation.set("/", "x")));
        assertInvalidRequest(List.of(PatchOperation.set("/a//b", "x")));
        assertInvalidRequest(List.of(PatchOperation.set("/a/", "x")));
    }

    /**
     * JSON Pointer's {@code ~0} / {@code ~1} escapes decode differently across
     * the three native path dialects, so the tilde is rejected outright rather
     * than translated three ways.
     */
    @Test
    @DisplayName("rejects JSON Pointer escape sequences")
    void rejectsTildeEscapes() {
        assertInvalidRequest(List.of(PatchOperation.set("/a~1b", "x")));
        assertInvalidRequest(List.of(PatchOperation.set("/a~0b", "x")));
    }

    /**
     * Array indexes are not portable: Cosmos inserts and shifts, DynamoDB
     * replaces or appends, and Spanner cannot address an element at all.
     */
    @Test
    @DisplayName("rejects numeric path segments (array indexes)")
    void rejectsArrayIndexes() {
        assertInvalidRequest(List.of(PatchOperation.set("/items/0", "x")));
        assertInvalidRequest(List.of(PatchOperation.remove("/0")));
    }

    @Test
    @DisplayName("allows a field name that merely starts with a digit")
    void allowsAlphanumericSegmentStartingWithDigit() {
        PatchValidator.validate(List.of(PatchOperation.set("/1st", "x")), PROVIDER);
    }

    @Test
    @DisplayName("rejects key, TTL and provider-reserved field names")
    void rejectsReservedFields() {
        assertInvalidRequest(List.of(PatchOperation.set("/id", "x")));
        assertInvalidRequest(List.of(PatchOperation.set("/partitionKey", "x")));
        assertInvalidRequest(List.of(PatchOperation.set("/sortKey", "x")));
        assertInvalidRequest(List.of(PatchOperation.set("/ttl", 60)));
        assertInvalidRequest(List.of(PatchOperation.set("/ttlExpiry", 60)));
        assertInvalidRequest(List.of(PatchOperation.set("/data", "internal")));
        assertInvalidRequest(List.of(PatchOperation.set("/_etag", "x")));
        assertInvalidRequest(List.of(PatchOperation.set("/id/nested", "x")));
    }

    /**
     * Spanner resolves column names without regard to case, so "/PartitionKey"
     * would collide with the real key column there while Cosmos and DynamoDB
     * created a stray second field. Matching case-sensitively would let that
     * divergence through the one guard whose whole job is portability.
     */
    @Test
    @DisplayName("reserved field names are rejected in every casing")
    void rejectsReservedFieldsCaseInsensitively() {
        assertInvalidRequest(List.of(PatchOperation.set("/ID", "x")));
        assertInvalidRequest(List.of(PatchOperation.set("/PartitionKey", "x")));
        assertInvalidRequest(List.of(PatchOperation.set("/SORTKEY", "x")));
        assertInvalidRequest(List.of(PatchOperation.set("/TtL", 60)));
        assertInvalidRequest(List.of(PatchOperation.set("/TTLExpiry", 60)));
        assertInvalidRequest(List.of(PatchOperation.set("/DATA", "internal")));
    }

    /**
     * Cosmos and Spanner apply operations sequentially; DynamoDB resolves every
     * operand against the pre-update item. Overlapping paths would therefore
     * produce different documents per provider.
     */
    @Test
    @DisplayName("rejects duplicate and prefix-overlapping paths")
    void rejectsOverlappingPaths() {
        assertInvalidRequest(List.of(
                PatchOperation.set("/a", 1),
                PatchOperation.set("/a", 2)));
        assertInvalidRequest(List.of(
                PatchOperation.set("/a", 1),
                PatchOperation.set("/a/b", 2)));
        assertInvalidRequest(List.of(
                PatchOperation.set("/a/b", 1),
                PatchOperation.remove("/a")));
        assertInvalidRequest(List.of(
                PatchOperation.set("/title", "lowercase"),
                PatchOperation.set("/Title", "uppercase")));
    }

    @Test
    @DisplayName("allows sibling nested paths, which do not overlap")
    void allowsSiblingPaths() {
        PatchValidator.validate(List.of(
                PatchOperation.set("/a/b", 1),
                PatchOperation.set("/a/c", 2)), PROVIDER);
    }

    @Test
    @DisplayName("rejects non-finite and out-of-domain INCREMENT deltas")
    void rejectsBadIncrementDeltas() {
        assertInvalidRequest(List.of(PatchOperation.increment("/n", Double.NaN)));
        assertInvalidRequest(List.of(PatchOperation.increment("/n", Double.POSITIVE_INFINITY)));
        assertInvalidRequest(List.of(PatchOperation.increment("/n", Float.NEGATIVE_INFINITY)));
        assertInvalidRequest(List.of(PatchOperation.increment("/n",
                BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE))));
        assertInvalidRequest(List.of(PatchOperation.increment("/n",
                new BigDecimal("0.1234567890123456789"))));
    }

    @Test
    @DisplayName("accepts the signed 64-bit integral delta boundaries")
    void acceptsSignedLongBoundaries() {
        assertDoesNotThrow(() -> PatchValidator.validate(List.of(
                PatchOperation.increment("/n", Long.MIN_VALUE)), PROVIDER));
        assertDoesNotThrow(() -> PatchValidator.validate(List.of(
                PatchOperation.increment("/n", Long.MAX_VALUE)), PROVIDER));
    }

    @Test
    @DisplayName("accepts a whole-valued floating delta (1.0 must behave like 1)")
    void acceptsWholeValuedFloatingDeltas() {
        assertDoesNotThrow(() -> PatchValidator.validate(
                List.of(PatchOperation.increment("/n", 1.0d)), PROVIDER));
        assertDoesNotThrow(() -> PatchValidator.validate(
                List.of(PatchOperation.increment("/n", 2.0f)), PROVIDER));
    }

    @Test
    @DisplayName("accepts negative and fractional INCREMENT deltas")
    void acceptsNegativeAndFractionalDeltas() {
        PatchValidator.validate(List.of(PatchOperation.increment("/n", -5)), PROVIDER);
        PatchValidator.validate(List.of(PatchOperation.increment("/n", 0.5)), PROVIDER);
    }

    /**
     * The patch payload travels as one request, so it is bound by the same
     * portable ceiling as a full document write. {@code DocumentSizeValidator}
     * builds its envelope without a provider, so only category and operation are
     * asserted here.
     */
    @Test
    @DisplayName("enforces the portable document size ceiling on the patch payload")
    void rejectsOversizedPayload() {
        String huge = "x".repeat(DocumentSizeValidator.MAX_BYTES + 1);
        MulticloudDbException e = validateExpectingFailure(List.of(PatchOperation.set("/blob", huge)));
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, e.error().category());
        assertEquals(OperationNames.PATCH, e.error().operation());
    }

    @Test
    @DisplayName("accepts a patch payload at the portable byte limit")
    void acceptsPayloadAtPortableByteLimit() throws Exception {
        int envelopeBytes = JSON_MAPPER.writeValueAsBytes(PatchValidator.requestPayload(
                List.of(PatchOperation.set("/blob", "")))).length;
        String exactLimitValue = "x".repeat(DocumentSizeValidator.MAX_BYTES - envelopeBytes);

        assertDoesNotThrow(() -> PatchValidator.validate(
                List.of(PatchOperation.set("/blob", exactLimitValue)), PROVIDER));
    }

    @Test
    @DisplayName("REMOVE verbs and paths contribute to the complete request size")
    void oversizedRemovePathIsRejected() {
        String oversizedPath = "/" + "x".repeat(DocumentSizeValidator.MAX_BYTES);
        MulticloudDbException e = validateExpectingFailure(List.of(PatchOperation.remove(oversizedPath)));
        assertEquals(MulticloudDbErrorCategory.INVALID_REQUEST, e.error().category());
        assertEquals(OperationNames.PATCH, e.error().operation());
    }

    @Test
    @DisplayName("hasNestedPath detects any path below the top level")
    void detectsNestedPaths() {
        assertFalse(PatchValidator.hasNestedPath(List.of(
                PatchOperation.set("/a", 1),
                PatchOperation.remove("/b"))));
        assertTrue(PatchValidator.hasNestedPath(List.of(
                PatchOperation.set("/a", 1),
                PatchOperation.set("/b/c", 2))));
    }

    @Test
    @DisplayName("PatchOperation exposes path structure and existence requirements")
    void patchOperationAccessors() {
        PatchOperation nested = PatchOperation.set("/address/city", "Redmond");
        assertEquals(List.of("address", "city"), nested.pathSegments());
        assertEquals("address", nested.rootField());
        assertTrue(nested.isNested());
        assertFalse(nested.requiresExistingPath());

        assertTrue(PatchOperation.replace("/a", 1).requiresExistingPath());
        assertTrue(PatchOperation.remove("/a").requiresExistingPath());
        assertTrue(PatchOperation.increment("/a", 1).requiresExistingPath());

        assertEquals(PatchOperation.set("/a", 1), PatchOperation.set("/a", 1));
        assertEquals(PatchOperation.set("/a", 1).hashCode(), PatchOperation.set("/a", 1).hashCode());
    }
}
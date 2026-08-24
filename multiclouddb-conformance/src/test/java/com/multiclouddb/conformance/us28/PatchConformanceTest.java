// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us28;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.multiclouddb.api.Capability;
import com.multiclouddb.api.DocumentResult;
import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.OperationNames;
import com.multiclouddb.api.OperationOptions;
import com.multiclouddb.api.PatchOperation;
import com.multiclouddb.api.QueryPage;
import com.multiclouddb.api.QueryRequest;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.SortDirection;
import com.multiclouddb.conformance.ConformanceHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;

import java.util.ArrayList;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Conformance tests for User Story 28 — Portable Field-Level Patch
 * (FR-181 … FR-192).
 * <p>
 * Like {@link com.multiclouddb.conformance.CrudConformanceTests}, every
 * behaviour asserted here MUST hold identically on every provider that declares
 * {@link Capability#PATCH} supported. Providers may defer the implementation
 * only by declaring PATCH unsupported and failing fast with
 * {@code UNSUPPORTED_CAPABILITY}; the capability conformance suite covers that
 * branch. Provider implementations can use different native primitives, so this
 * suite is what prevents their observable behaviour from drifting apart.
 * Nested-path support is independently gated on {@link Capability#NESTED_PATCH}
 * and asserted in both directions.
 * <p>
 * Subclass and implement {@link #createClient()} and {@link #getAddress()} to
 * run the suite against a provider.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class PatchConformanceTest {

    protected abstract MulticloudDbClient createClient();

    protected abstract ResourceAddress getAddress();

    protected abstract boolean expectedNestedPatchSupport();

    private MulticloudDbClient client;
    private final List<MulticloudDbKey> seededKeys = new ArrayList<>();
    private Throwable testFailure;

    @RegisterExtension
    final TestExecutionExceptionHandler rememberTestFailure = (context, throwable) -> {
        testFailure = throwable;
        throw throwable;
    };

    @BeforeEach
    void setUp() {
        testFailure = null;
        seededKeys.clear();
        client = createClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        Exception cleanupFailure = null;
        for (MulticloudDbKey key : seededKeys) {
            try {
                client.delete(getAddress(), key, OperationOptions.defaults());
            } catch (MulticloudDbException e) {
                if (e.error().category() != MulticloudDbErrorCategory.NOT_FOUND) {
                    cleanupFailure = appendCleanupFailure(cleanupFailure, e);
                }
            } catch (Exception e) {
                cleanupFailure = appendCleanupFailure(cleanupFailure, e);
            }
        }
        try {
            client.close();
        } catch (Exception e) {
            cleanupFailure = appendCleanupFailure(cleanupFailure, e);
        }

        if (cleanupFailure != null) {
            if (testFailure != null) {
                testFailure.addSuppressed(cleanupFailure);
                return;
            }
            throw cleanupFailure;
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Seed a document and return its key. */
    private MulticloudDbKey seed(String prefix, Map<String, Object> document) {
        return seed(prefix, document, OperationOptions.defaults());
    }

    /** Seed a document with explicit write options and return its key. */
    private MulticloudDbKey seed(String prefix, Map<String, Object> document,
            OperationOptions options) {
        MulticloudDbKey key = ConformanceHarness.uniqueKey(prefix);
        client.create(getAddress(), key, document, options);
        seededKeys.add(key);
        return key;
    }

    /**
     * Seed a document into an explicit partition. {@link #seed(String, Map)}
     * derives the sort key from the partition key, which puts every document in
     * its own partition; ordering assertions need several documents the query can
     * see at once under a single partition-key scope.
     */
    private MulticloudDbKey seedInPartition(String partitionKey, String sortKey,
                                            Map<String, Object> document) {
        MulticloudDbKey key = MulticloudDbKey.of(partitionKey, sortKey);
        client.create(getAddress(), key, document, OperationOptions.defaults());
        seededKeys.add(key);
        return key;
    }

    private ObjectNode read(MulticloudDbKey key) {
        DocumentResult result = client.read(getAddress(), key, OperationOptions.defaults());
        assertNotNull(result, "document must exist");
        return result.document();
    }

    private MulticloudDbException patchExpectingFailure(MulticloudDbKey key, List<PatchOperation> ops) {
        return assertThrows(MulticloudDbException.class,
                () -> client.patch(getAddress(), key, ops));
    }

    private void assertCategory(MulticloudDbException e, MulticloudDbErrorCategory expected) {
        assertEquals(expected, e.error().category(),
                "patch must normalise this failure to " + expected + " on every provider");
        assertEquals(OperationNames.PATCH, e.error().operation(),
                "the error envelope must attribute the failure to the patch operation");
    }

    private void assertInvalidAndUnchanged(MulticloudDbKey key, List<PatchOperation> operations) {
        assertCategory(patchExpectingFailure(key, operations), MulticloudDbErrorCategory.INVALID_REQUEST);
        assertEquals("before", read(key).get("title").asText(),
                "a rejected request must leave the document unchanged");
    }

    private static Exception appendCleanupFailure(Exception prior, Exception next) {
        if (prior == null) {
            return next;
        }
        prior.addSuppressed(next);
        return prior;
    }

    private boolean supportsNestedPatch() {
        return client.capabilities().isSupported(Capability.NESTED_PATCH);
    }

    // ── capability declaration ───────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("PATCH is declared supported by each provider running this suite")
    void patchCapabilityIsDeclared() {
        assertTrue(client.capabilities().isSupported(Capability.PATCH),
                "providers running patch conformance must declare PATCH supported");
        assertNotNull(client.capabilities().get(Capability.NESTED_PATCH),
                "NESTED_PATCH must be declared either way so callers can branch on it");
    }

    @Test
    @Order(28)
    @DisplayName("NESTED_PATCH capability matches the provider contract")
    void nestedPatchCapabilityMatchesProviderContract() {
        assertEquals(expectedNestedPatchSupport(), supportsNestedPatch(),
                "the provider's NESTED_PATCH declaration must match its conformance contract");
    }

    // ── SET ──────────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("SET adds dynamic fields without disturbing the rest of the document")
    void setAddsFieldAndPreservesOthers() {
        MulticloudDbKey key = seed("patch-set-add", Map.of("title", "original", "extra", "keep"));

        client.patch(getAddress(), key, List.of(
                PatchOperation.set("/status", "active"),
                PatchOperation.set("/onSale", true)));

        ObjectNode doc = read(key);
        assertEquals("active", doc.get("status").asText());
        assertTrue(doc.get("onSale").asBoolean(),
                "a dynamic top-level SET must not require predeclaring the field");
        assertEquals("original", doc.get("title").asText(), "patch must not rewrite untouched fields");
        assertEquals("keep", doc.get("extra").asText(), "patch must not rewrite untouched fields");
    }

    @Test
    @Order(3)
    @DisplayName("SET overwrites an existing field")
    void setOverwritesExistingField() {
        MulticloudDbKey key = seed("patch-set-over", Map.of("title", "before"));

        client.patch(getAddress(), key, List.of(PatchOperation.set("/title", "after")));

        assertEquals("after", read(key).get("title").asText());
    }

    // ── REPLACE ──────────────────────────────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("REPLACE updates an existing field")
    void replaceUpdatesExistingField() {
        MulticloudDbKey key = seed("patch-replace", Map.of("title", "before"));

        client.patch(getAddress(), key, List.of(PatchOperation.replace("/title", "after")));

        assertEquals("after", read(key).get("title").asText());
    }

    /**
     * The strictest of the three natives wins: Cosmos cannot make {@code replace}
     * lenient without a pre-read, so the portable contract is strict everywhere.
     */
    @Test
    @Order(5)
    @DisplayName("REPLACE on a missing field is NOT_FOUND on every provider")
    void replaceMissingFieldIsNotFound() {
        MulticloudDbKey key = seed("patch-replace-missing", Map.of("title", "only"));

        assertCategory(patchExpectingFailure(key, List.of(PatchOperation.replace("/status", "x"))),
                MulticloudDbErrorCategory.NOT_FOUND);
    }

    // ── REMOVE ───────────────────────────────────────────────────────────────

    @Test
    @Order(6)
    @DisplayName("REMOVE deletes a field so reads no longer return it")
    void removeDeletesField() {
        MulticloudDbKey key = seed("patch-remove", Map.of("title", "keep", "extra", "drop"));

        client.patch(getAddress(), key, List.of(PatchOperation.remove("/extra")));

        ObjectNode doc = read(key);
        assertFalse(doc.has("extra"), "removed field must be absent from subsequent reads");
        assertEquals("keep", doc.get("title").asText());
    }

    /**
     * DynamoDB's native REMOVE is a silent no-op on a missing attribute; the
     * condition expression is what makes it fail like Cosmos does.
     */
    @Test
    @Order(7)
    @DisplayName("REMOVE on a missing field is NOT_FOUND on every provider")
    void removeMissingFieldIsNotFound() {
        MulticloudDbKey key = seed("patch-remove-missing", Map.of("title", "only"));

        assertCategory(patchExpectingFailure(key, List.of(PatchOperation.remove("/status"))),
                MulticloudDbErrorCategory.NOT_FOUND);
    }

    // ── INCREMENT ────────────────────────────────────────────────────────────

    @Test
    @Order(8)
    @DisplayName("INCREMENT adds a delta to an existing numeric field")
    void incrementAddsDelta() {
        MulticloudDbKey key = seed("patch-incr", Map.of("title", "counter", "value", 1));

        client.patch(getAddress(), key, List.of(PatchOperation.increment("/value", 5)));

        assertEquals(6L, read(key).get("value").asLong());
    }

    @Test
    @Order(9)
    @DisplayName("INCREMENT accepts a negative delta")
    void incrementAcceptsNegativeDelta() {
        MulticloudDbKey key = seed("patch-incr-neg", Map.of("title", "counter", "value", 10));

        client.patch(getAddress(), key, List.of(PatchOperation.increment("/value", -4)));

        assertEquals(6L, read(key).get("value").asLong());
    }

    @Test
    @Order(10)
    @DisplayName("INCREMENT on a missing field is NOT_FOUND on every provider")
    void incrementMissingFieldIsNotFound() {
        MulticloudDbKey key = seed("patch-incr-missing", Map.of("title", "only"));

        assertCategory(patchExpectingFailure(key, List.of(PatchOperation.increment("/value", 1))),
                MulticloudDbErrorCategory.NOT_FOUND);
    }

    // ── document-level preconditions ─────────────────────────────────────────

    @Test
    @Order(11)
    @DisplayName("patch on a missing document is NOT_FOUND, never an implicit create")
    void patchMissingDocumentIsNotFound() {
        MulticloudDbKey key = ConformanceHarness.uniqueKey("patch-no-doc");

        assertCategory(patchExpectingFailure(key, List.of(PatchOperation.set("/status", "x"))),
                MulticloudDbErrorCategory.NOT_FOUND);

        assertNull(client.read(getAddress(), key, OperationOptions.defaults()),
                "a failed patch must not have created the document");
    }

    // ── atomicity ────────────────────────────────────────────────────────────

    /**
     * The headline guarantee: a patch is all-or-nothing. If the last operation
     * fails its precondition, the earlier ones must leave no trace.
     */
    @Test
    @Order(12)
    @DisplayName("a failing operation rolls back the whole patch")
    void patchIsAtomic() {
        MulticloudDbKey key = seed("patch-atomic", Map.of("title", "before"));

        MulticloudDbException e = patchExpectingFailure(key, List.of(
                PatchOperation.set("/marker", "should-not-persist"),
                PatchOperation.replace("/status", "missing-field")));
        assertCategory(e, MulticloudDbErrorCategory.NOT_FOUND);

        ObjectNode doc = read(key);
        assertFalse(doc.has("marker"),
                "no operation may persist when a later operation fails");
        assertEquals("before", doc.get("title").asText());
    }

    @Test
    @Order(13)
    @DisplayName("multiple operations apply together in a single request")
    void multipleOperationsApplyTogether() {
        MulticloudDbKey key = seed("patch-multi",
                Map.of("title", "before", "extra", "drop", "value", 1));

        client.patch(getAddress(), key, List.of(
                PatchOperation.replace("/title", "after"),
                PatchOperation.set("/status", "done"),
                PatchOperation.remove("/extra"),
                PatchOperation.increment("/value", 2)));

        ObjectNode doc = read(key);
        assertEquals("after", doc.get("title").asText());
        assertEquals("done", doc.get("status").asText());
        assertFalse(doc.has("extra"));
        assertEquals(3L, doc.get("value").asLong());
    }

    // ── portable request validation ──────────────────────────────────────────

    @Test
    @Order(14)
    @DisplayName("an empty operation list is INVALID_REQUEST")
    void emptyOperationListIsInvalid() {
        MulticloudDbKey key = seed("patch-empty", Map.of("title", "before"));

        assertInvalidAndUnchanged(key, List.of());
    }

    @Test
    @Order(44)
    @DisplayName("a null operation list is INVALID_REQUEST and leaves state unchanged")
    void nullOperationListIsInvalid() {
        MulticloudDbKey key = seed("patch-null-list", Map.of("title", "before"));
        List<PatchOperation> operations = null;

        assertInvalidAndUnchanged(key, operations);
    }

    @Test
    @Order(45)
    @DisplayName("a null operation entry is INVALID_REQUEST and leaves state unchanged")
    void nullOperationEntryIsInvalid() {
        MulticloudDbKey key = seed("patch-null-entry", Map.of("title", "before"));
        List<PatchOperation> operations = new ArrayList<>();
        operations.add(PatchOperation.set("/status", "new"));
        operations.add(null);

        assertInvalidAndUnchanged(key, operations);
    }

    /**
     * Cosmos caps a patch at 10 operations. The SDK enforces that everywhere so
     * an 11-operation patch cannot succeed on one provider and fail on another.
     */
    @Test
    @Order(15)
    @DisplayName("exceeding the portable operation limit is INVALID_REQUEST")
    void tooManyOperationsIsInvalid() {
        MulticloudDbKey key = seed("patch-limit", Map.of("title", "before"));

        List<PatchOperation> ops = new ArrayList<>();
        for (int i = 0; i <= MulticloudDbClient.MAX_PATCH_OPERATIONS; i++) {
            ops.add(PatchOperation.set("/f" + i, i));
        }
        assertInvalidAndUnchanged(key, ops);
    }

    /** Native engines disagree on overlapping-path evaluation, so it is rejected portably. */
    @Test
    @Order(16)
    @DisplayName("overlapping paths in one patch are INVALID_REQUEST")
    void overlappingPathsAreInvalid() {
        MulticloudDbKey key = seed("patch-overlap", Map.of("title", "before"));

        assertInvalidAndUnchanged(key, List.of(
                        PatchOperation.set("/status", "a"),
                        PatchOperation.set("/status", "b")));
        assertInvalidAndUnchanged(key, List.of(
                PatchOperation.set("/status", "a"),
                PatchOperation.set("/status/detail", "b")));
    }

    @Test
    @Order(17)
    @DisplayName("patching a key or TTL field is INVALID_REQUEST")
    void reservedFieldsAreInvalid() {
        MulticloudDbKey key = seed("patch-reserved", Map.of("title", "before"));
        List<String> roots = List.of("id", "partitionKey", "sortKey", "ttl", "ttlExpiry",
                "data", "_etag", "_providerOwned");

        for (String root : roots) {
            assertInvalidAndUnchanged(key, List.of(PatchOperation.set("/" + root, "x")));
            assertInvalidAndUnchanged(key, List.of(PatchOperation.set(
                    "/" + root.toUpperCase(Locale.ROOT), "x")));
        }
    }

    @Test
    @Order(18)
    @DisplayName("array-index paths are INVALID_REQUEST")
    void arrayIndexPathsAreInvalid() {
        MulticloudDbKey key = seed("patch-array", Map.of("title", "before"));

        assertInvalidAndUnchanged(key, List.of(PatchOperation.set("/items/0", "x")));
        assertInvalidAndUnchanged(key, List.of(PatchOperation.remove("/0")));
    }

    @Test
    @Order(29)
    @DisplayName("relative paths are rejected before any provider mutation")
    void relativePathsAreInvalidAndLeaveStateUnchanged() {
        MulticloudDbKey key = seed("patch-relative", Map.of("title", "before"));

        assertInvalidAndUnchanged(key, List.of(PatchOperation.set("status", "active")));
    }

    @Test
    @Order(30)
    @DisplayName("empty path segments are rejected before any provider mutation")
    void emptyPathSegmentsAreInvalidAndLeaveStateUnchanged() {
        MulticloudDbKey key = seed("patch-empty-segment", Map.of("title", "before"));

        assertInvalidAndUnchanged(key, List.of(PatchOperation.set("/status/", "active")));
        assertInvalidAndUnchanged(key, List.of(PatchOperation.set("/", "active")));
    }

    @Test
    @Order(31)
    @DisplayName("tilde escapes are rejected before any provider mutation")
    void tildeEscapesAreInvalidAndLeaveStateUnchanged() {
        MulticloudDbKey key = seed("patch-tilde", Map.of("title", "before"));

        assertInvalidAndUnchanged(key, List.of(PatchOperation.set("/sta~1tus", "active")));
    }

    @Test
    @Order(32)
    @DisplayName("non-finite increments are rejected before any provider mutation")
    void nonFiniteIncrementIsInvalidAndLeavesStateUnchanged() {
        MulticloudDbKey key = seed("patch-non-finite", Map.of("title", "before", "value", 1));

        assertInvalidAndUnchanged(key, List.of(PatchOperation.increment("/value", Double.NaN)));
    }

    @Test
    @Order(33)
    @DisplayName("oversized patch payloads are rejected before any provider mutation")
    void oversizedPatchPayloadIsInvalidAndLeavesStateUnchanged() {
        MulticloudDbKey key = seed("patch-too-large", Map.of("title", "before"));
        String oversized = "x".repeat(408_576);

        assertInvalidAndUnchanged(key, List.of(PatchOperation.set("/blob", oversized)));
    }

    @Test
    @Order(34)
    @DisplayName("the exact portable operation limit succeeds")
    void exactOperationLimitSucceeds() {
        MulticloudDbKey key = seed("patch-exact-limit", Map.of("title", "before"));
        List<PatchOperation> operations = new ArrayList<>();
        for (int i = 0; i < MulticloudDbClient.MAX_PATCH_OPERATIONS; i++) {
            operations.add(PatchOperation.set("/field" + i, i));
        }

        assertDoesNotThrow(() -> client.patch(getAddress(), key, operations));
        assertEquals(MulticloudDbClient.MAX_PATCH_OPERATIONS - 1,
                read(key).get("field" + (MulticloudDbClient.MAX_PATCH_OPERATIONS - 1)).asInt(),
                "every operation at the portable limit must be applied");
    }

    @Test
    @Order(40)
    @DisplayName("patched fractional and dynamic values are visible to portable queries")
    void patchedValuesAreVisibleToPortableQueries() {
        // The predicate must provably select only this document. Left unscoped it is
        // a cross-partition Cosmos query and a full DynamoDB Scan over storage that
        // is shared with other suites and reused across runs, so a single leftover
        // document from an interrupted run would break the exact-count assertion for
        // every later run. Scope by partition key plus a per-run marker.
        String marker = "patch-query-" + UUID.randomUUID();
        MulticloudDbKey key = seed("patch-query-dynamic", Map.of("value", 1, "marker", marker));
        client.patch(getAddress(), key, List.of(
                PatchOperation.increment("/value", 1),
                PatchOperation.set("/onSale", true)));

        QueryPage page = client.query(getAddress(), QueryRequest.builder()
                .partitionKey(key.partitionKey())
                .expression("value > @minimum AND onSale = @onSale AND marker = @marker")
                .parameter("minimum", 1)
                .parameter("onSale", true)
                .parameter("marker", marker)
                .maxPageSize(100)
                .build());

        assertEquals(1, page.items().size(),
                "the patched document must match both numeric and dynamic portable predicates");
        Number queriedValue = org.junit.jupiter.api.Assertions.assertInstanceOf(
                Number.class, page.items().get(0).get("value"));
        assertEquals(2d, queriedValue.doubleValue(), 1e-9);
        assertEquals(true, page.items().get(0).get("onSale"));
    }

    /**
     * ORDER BY on a field that only a patch created. Dynamic-field ordering is a
     * seam where provider implementations can drift: a textual implementation ranks
     * {@code 1, 10, 2} instead of {@code 1, 2, 10} and would silently return a
     * different result set per provider for the same portable query.
     * <p>
     * The sort keys are deliberately assigned so that key order ({@code a, b, c}),
     * lexicographic rank order ({@code 1, 10, 2}) and numeric rank order
     * ({@code 1, 2, 10}) are three different permutations — a provider that falls
     * back to key order or to string collation fails here rather than passing by
     * coincidence. DynamoDB legitimately does not support ORDER BY, so the
     * assertion is gated on the declared capability rather than on the provider.
     */
    @Test
    @Order(45)
    @DisplayName("ORDER BY on a patch-created dynamic field ranks numerically, and DESC is its exact reverse")
    void orderByOnPatchedDynamicFieldIsNumericAndSymmetric() {
        assumeTrue(client.capabilities().isSupported(Capability.ORDER_BY),
                "Skip: provider does not declare ORDER_BY");

        String partition = "patch-orderby-" + UUID.randomUUID();
        String marker = "patch-orderby-" + UUID.randomUUID();
        // sortKey -> rank. Key order is a, b, c; numeric rank order is b, c, a;
        // lexicographic rank order would be b, a, c.
        Map<String, Integer> ranks = new LinkedHashMap<>();
        ranks.put("a", 10);
        ranks.put("b", 1);
        ranks.put("c", 2);

        for (Map.Entry<String, Integer> entry : ranks.entrySet()) {
            MulticloudDbKey key = seedInPartition(partition, entry.getKey(), Map.of("marker", marker));
            // rankValue exists only because of this patch.
            client.patch(getAddress(), key, List.of(PatchOperation.set("/rankValue", entry.getValue())));
        }

        List<Long> ascending = rankValuesOrderedBy(partition, marker, SortDirection.ASC);
        assertEquals(List.of(1L, 2L, 10L), ascending,
                "ORDER BY on a patch-created dynamic field must rank numerically on every provider; "
                        + "[1, 10, 2] means the field is being compared as text");

        List<Long> descending = rankValuesOrderedBy(partition, marker, SortDirection.DESC);
        assertEquals(List.of(10L, 2L, 1L), descending,
                "DESC must be the exact reverse of ASC, not merely a different ordering");
    }

    /** Runs a partition-scoped, marker-filtered ORDER BY query and projects {@code rankValue}. */
    private List<Long> rankValuesOrderedBy(String partition, String marker, SortDirection direction) {
        QueryPage page = client.query(getAddress(), QueryRequest.builder()
                .partitionKey(partition)
                .expression("marker = @marker")
                .parameter("marker", marker)
                .orderBy("rankValue", direction)
                .maxPageSize(100)
                .build());
        List<Long> values = new ArrayList<>();
        for (Map<String, Object> item : page.items()) {
            Object value = item.get("rankValue");
            assertTrue(value instanceof Number,
                    "a patched numeric field must come back as a number, got: " + value);
            values.add(((Number) value).longValue());
        }
        return values;
    }

    @Test
    @Order(41)
    @DisplayName("oversized REMOVE paths are rejected before mutation")
    void oversizedRemovePathIsInvalidAndLeavesStateUnchanged() {
        MulticloudDbKey key = seed("patch-remove-too-large", Map.of("title", "before"));
        String oversizedPath = "/" + "x".repeat(408_576);

        assertInvalidAndUnchanged(key, List.of(PatchOperation.remove(oversizedPath)));
    }

    @Test
    @Order(42)
    @DisplayName("portable numeric delta boundaries reject before mutation")
    void numericDeltaBoundariesArePortable() {
        MulticloudDbKey key = seed("patch-numeric-boundary", Map.of("title", "before", "value", 0));

        assertDoesNotThrow(() -> client.patch(getAddress(), key,
                List.of(PatchOperation.increment("/value", Long.MAX_VALUE))));
        assertEquals(Long.MAX_VALUE, read(key).get("value").asLong());

        assertInvalidAndUnchanged(key, List.of(PatchOperation.increment("/value",
                BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE))));
        assertInvalidAndUnchanged(key, List.of(PatchOperation.increment("/value",
                new BigDecimal("0.1234567890123456789"))));
    }

    @Test
    @Order(48)
    @DisplayName("fractional floor is accepted and smaller written values are INVALID_REQUEST")
    void fractionalFloorIsPortableForWrittenValues() {
        MulticloudDbKey key = seed("patch-fractional-floor",
                Map.of("title", "before", "value", 0));

        // 1E-130 is DynamoDB's smallest non-zero magnitude, so it is the floor of
        // the portable domain for any number a patch writes.
        client.patch(getAddress(), key,
                List.of(PatchOperation.set("/value", new BigDecimal("1E-130"))));
        assertEquals(1e-130d, read(key).get("value").asDouble());

        assertInvalidAndUnchanged(key,
                List.of(PatchOperation.set("/value", new BigDecimal("1E-131"))));
        assertEquals(1e-130d, read(key).get("value").asDouble(),
                "a rejected sub-floor value must not alter the boundary value");
    }

    @Test
    @Order(49)
    @DisplayName("SET honours the portable integral boundary and rejects anything wider")
    void setWideIntegerIsBoundedByThePortableNumericDomain() {
        BigInteger boundary = BigInteger.valueOf(Long.MAX_VALUE);
        MulticloudDbKey key = seed("patch-wide-integer", Map.of("title", "before"));

        // Long.MAX_VALUE is the widest integer every provider stores and
        // returns unchanged, so it must round-trip exactly.
        client.patch(getAddress(), key, List.of(PatchOperation.set("/wide", boundary)));
        assertEquals(boundary, read(key).get("wide").bigIntegerValue(),
                "the portable integral boundary must round-trip exactly on every provider");

        // One past the boundary is outside the portable domain: DynamoDB keeps
        // it exactly while Cosmos DB rounds it to the nearest double, so the
        // SDK rejects it uniformly rather than store provider-dependent data.
        assertInvalidAndUnchanged(key,
                List.of(PatchOperation.set("/wide", boundary.add(BigInteger.ONE))));
        assertEquals(boundary, read(key).get("wide").bigIntegerValue(),
                "a rejected out-of-domain SET must not alter the stored value");
    }

    @Test
    @Order(43)
    @DisplayName("integral INCREMENT result overflow is INVALID_REQUEST and leaves the document unchanged")
    void integralIncrementResultOverflowIsInvalidRequest() {
        MulticloudDbKey upperKey = seed("patch-result-overflow-upper",
                Map.of("value", Long.MAX_VALUE));
        assertCategory(patchExpectingFailure(upperKey,
                List.of(PatchOperation.increment("/value", 1))),
                MulticloudDbErrorCategory.INVALID_REQUEST);
        assertEquals(Long.MAX_VALUE, read(upperKey).get("value").asLong(),
                "a rejected positive overflow must not change the stored value");

        MulticloudDbKey lowerKey = seed("patch-result-overflow-lower",
                Map.of("value", Long.MIN_VALUE));
        assertCategory(patchExpectingFailure(lowerKey,
                List.of(PatchOperation.increment("/value", -1))),
                MulticloudDbErrorCategory.INVALID_REQUEST);
        assertEquals(Long.MIN_VALUE, read(lowerKey).get("value").asLong(),
                "a rejected negative overflow must not change the stored value");
    }

    @Test
    @Order(35)
    @DisplayName("case-only path aliases are rejected before any provider mutation")
    void caseOnlyPathsAreInvalidAndLeaveStateUnchanged() {
        MulticloudDbKey key = seed("patch-case-alias", Map.of("title", "before"));

        assertInvalidAndUnchanged(key, List.of(
                PatchOperation.set("/title", "lowercase"),
                PatchOperation.set("/Title", "uppercase")));
        assertFalse(read(key).has("Title"));
    }

    @Test
    @Order(36)
    @DisplayName("the SDK metadata field is reserved on every provider")
    void dataFieldIsInvalidAndLeavesStateUnchanged() {
        MulticloudDbKey key = seed("patch-data-reserved", Map.of("title", "before"));

        assertInvalidAndUnchanged(key, List.of(PatchOperation.set("/data", "internal")));
    }

    @Test
    @Order(37)
    @DisplayName("nested SET does not create a missing parent where nested patch is supported")
    void nestedSetMissingParentIsNotFoundAndLeavesStateUnchanged() {
        assumeTrue(supportsNestedPatch(), "provider does not declare NESTED_PATCH");
        MulticloudDbKey key = seed("patch-nested-parent", Map.of("title", "before"));

        assertCategory(patchExpectingFailure(key,
                List.of(PatchOperation.set("/address/city", "Redmond"))),
                MulticloudDbErrorCategory.NOT_FOUND);
        assertFalse(read(key).has("address"), "a failed nested SET must not create its parent");
    }

    @Test
    @Order(38)
    @DisplayName("fractional INCREMENT on an integral field is INVALID_REQUEST")
    void incrementFractionalDeltaOnIntegralFieldIsRejected() {
        MulticloudDbKey key = seed("patch-incr-integral-frac",
                Map.of("title", "before", "value", 1));

        assertInvalidAndUnchanged(key, List.of(PatchOperation.increment("/value", 0.5d)));
        assertEquals(1, read(key).get("value").asInt(),
                "a rejected fractional delta must not alter the stored value");
    }

    @Test
    @Order(39)
    @DisplayName("SET null and REMOVE preserve typed and dynamic field semantics")
    void setNullAndRemoveWorkForTypedAndDynamicFields() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("intField", 1);
        document.put("doubleField", 1.5d);
        document.put("boolTrue", true);
        document.put("dynamicField", "dynamic");
        MulticloudDbKey key = seed("patch-typed-nulls", document);
        List<PatchOperation> nulls = List.of(
                PatchOperation.set("/intField", null),
                PatchOperation.set("/doubleField", null),
                PatchOperation.set("/boolTrue", null),
                PatchOperation.set("/dynamicField", null));

        client.patch(getAddress(), key, nulls);
        ObjectNode afterSet = read(key);
        assertTrue(afterSet.get("intField").isNull());
        assertTrue(afterSet.get("doubleField").isNull());
        assertTrue(afterSet.get("boolTrue").isNull());
        assertTrue(afterSet.get("dynamicField").isNull());

        client.patch(getAddress(), key, List.of(
                PatchOperation.remove("/intField"),
                PatchOperation.remove("/doubleField"),
                PatchOperation.remove("/boolTrue"),
                PatchOperation.remove("/dynamicField")));
        ObjectNode afterRemove = read(key);
        assertFalse(afterRemove.has("intField"));
        assertFalse(afterRemove.has("doubleField"));
        assertFalse(afterRemove.has("boolTrue"));
        assertFalse(afterRemove.has("dynamicField"));
    }

    // ── nested paths: capability-gated in both directions ────────────────────

    @Test
    @Order(19)
    @DisplayName("nested path patches a sub-field where NESTED_PATCH is declared")
    void nestedPatchWorksWhereSupported() {
        assumeTrue(supportsNestedPatch(),
                "provider does not declare NESTED_PATCH");

        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("city", "before");
        nested.put("zip", "12345");
        MulticloudDbKey key = seed("patch-nested", Map.of("title", "t", "nestedObj", nested));

        client.patch(getAddress(), key, List.of(PatchOperation.set("/nestedObj/city", "after")));

        com.fasterxml.jackson.databind.JsonNode stored = read(key).get("nestedObj");
        assertEquals("after", stored.get("city").asText());
        assertEquals("12345", stored.get("zip").asText(), "sibling sub-fields must be preserved");
    }

    /** Unsupported nested paths must fail loudly rather than being rewritten or ignored. */
    @Test
    @Order(20)
    @DisplayName("nested path is UNSUPPORTED_CAPABILITY where NESTED_PATCH is not declared")
    void nestedPatchRejectedWhereUnsupported() {
        assumeTrue(!supportsNestedPatch(),
                "provider declares NESTED_PATCH");

        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("city", "before");
        MulticloudDbKey key = seed("patch-nested-unsup", Map.of("title", "t", "nestedObj", nested));

        assertCategory(patchExpectingFailure(key, List.of(
                        PatchOperation.set("/nestedObj/city", "after"))),
                MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY);

        assertEquals("before", read(key).get("nestedObj").get("city").asText(),
                "a rejected nested patch must leave the document untouched");
    }

    // ── documented uniform behaviour ─────────────────────────────────────────

    /** {@code ttlSeconds} on the patch request itself never creates a new expiry. */
    @Test
    @Order(21)
    @DisplayName("ttlSeconds is accepted and ignored on every provider")
    void ttlOptionIsAcceptedAndIgnored() {
        MulticloudDbKey key = seed("patch-ttl", Map.of("title", "t"));

        OperationOptions opts = OperationOptions.builder().ttlSeconds(3600).build();
        assertDoesNotThrow(() -> client.patch(getAddress(), key,
                List.of(PatchOperation.set("/status", "x")), opts));

        assertEquals("x", read(key).get("status").asText());

        // The patch must not have stamped an expiry. Asserting only that the write
        // succeeded would pass even if the provider had honoured the TTL, which is
        // exactly the divergence FR-190 forbids.
        DocumentResult withMeta = client.read(getAddress(), key,
                OperationOptions.builder().includeMetadata(true).build());
        assertNotNull(withMeta, "document must still exist");
        if (withMeta.metadata() != null) {
            assertNull(withMeta.metadata().ttlExpiry(),
                    "patch must not stamp an expiry from ttlSeconds on any provider");
        }
    }

    @Test
    @Order(50)
    @DisplayName("patching an item with an SDK-managed TTL is UNSUPPORTED_CAPABILITY everywhere")
    void existingTtlRejectsPatchOnEveryProvider() {
        OperationOptions ttl = OperationOptions.builder().ttlSeconds(3600).build();
        MulticloudDbKey key = seed("patch-existing-ttl", Map.of("title", "before"), ttl);

        assertCategory(patchExpectingFailure(key,
                List.of(PatchOperation.set("/status", "after"))),
                MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY);
        assertFalse(read(key).has("status"),
                "the rejection must happen before any mutation");

        OperationOptions metadata = OperationOptions.builder().includeMetadata(true).build();
        DocumentResult after = client.read(getAddress(), key, metadata);
        assertNotNull(after);
        assertNotNull(after.metadata());
        assertNotNull(after.metadata().ttlExpiry(),
                "the rejected patch must leave the seeded expiry intact");
    }

    // ── numeric representation parity ────────────────────────────────────────

    /**
     * {@code increment("/value", 1.0)} and {@code increment("/value", 1)} are the
     * same portable request and must land the same stored number. Keying the
     * integer-vs-floating decision off the boxed Java type rather than the value
     * can make the former write a floating-point number on a typed backend.
     */
    @Test
    @Order(22)
    @DisplayName("INCREMENT with a whole-valued floating delta matches an integer delta")
    void incrementWithWholeValuedDoubleDelta() {
        MulticloudDbKey key = seed("patch-incr-whole", Map.of("value", 5));

        client.patch(getAddress(), key, List.of(PatchOperation.increment("/value", 1.0d)));

        ObjectNode doc = read(key);
        assertEquals(6, doc.get("value").asInt(),
                "a whole-valued delta must behave identically to an integer delta");
        assertTrue(doc.get("value").isIntegralNumber(),
                "the stored number must stay integral on every provider");
    }

    /**
     * A fractional delta is outside the portable contract even on a field that
     * already holds a floating-point value, because the divergence comes from
     * the provider's server-side accumulation, not from the stored type.
     */
    @Test
    @Order(23)
    @DisplayName("INCREMENT with a fractional delta is INVALID_REQUEST on a floating-point field")
    void incrementFractionalDeltaOnFloatingFieldIsRejected() {
        MulticloudDbKey key = seed("patch-incr-frac",
                Map.of("title", "before", "doubleField", 1.5d));

        assertInvalidAndUnchanged(key,
                List.of(PatchOperation.increment("/doubleField", 0.25d)));
        assertEquals(1.5d, read(key).get("doubleField").asDouble(), 0.0d,
                "a rejected fractional delta must not alter the stored value");
    }

    /**
     * The one place a portable PATCH could store different bytes on different
     * providers: server-side accumulation of a fractional delta. DynamoDB adds
     * in the exact-decimal {@code N} type and would store {@code 0.3}; Cosmos DB
     * evaluates in IEEE-754 binary64 and would store {@code 0.30000000000000004}.
     * The portable contract removes the divergence at the source by rejecting
     * fractional deltas outright, so this asserts the rejection with no tolerance
     * and no capability gate — it must hold identically on every provider.
     */
    @Test
    @Order(46)
    @DisplayName("the fractional accumulation divergence is unreachable: 0.1 + 0.2 is rejected")
    void fractionalAccumulationDivergenceIsUnreachable() {
        MulticloudDbKey key = seed("patch-incr-exact-decimal",
                Map.of("title", "before", "doubleField", 0.1d));

        assertInvalidAndUnchanged(key,
                List.of(PatchOperation.increment("/doubleField", 0.2d)));

        assertEquals(0.1d, read(key).get("doubleField").asDouble(), 0.0d,
                "a rejected fractional delta must leave the stored value byte-identical");
    }

    /**
     * Fractional <em>values</em> stay portable. Only accumulation diverges, and
     * SET performs none — the operand is stored verbatim, so the same call lands
     * the same number on every provider.
     */
    @Test
    @Order(47)
    @DisplayName("SET stores a fractional value identically on every provider")
    void fractionalSetValueIsPortable() {
        MulticloudDbKey key = seed("patch-set-fractional", Map.of("title", "before"));

        client.patch(getAddress(), key, List.of(PatchOperation.set("/price", 19.99d)));

        assertEquals(19.99d, read(key).get("price").asDouble(), 0.0d,
                "SET writes the operand verbatim, so no provider arithmetic is involved");
    }

    @Test
    @Order(24)
    @DisplayName("INCREMENT on a non-numeric field is INVALID_REQUEST on every provider")    void incrementNonNumericIsInvalid() {
        MulticloudDbKey key = seed("patch-incr-type",
                Map.of("title", "before", "value", "not-a-number"));

        assertInvalidAndUnchanged(key, List.of(PatchOperation.increment("/value", 1)));
        assertEquals("not-a-number", read(key).get("value").asText(),
                "a rejected increment must not alter the nonnumeric target");
    }

    // ── value fidelity ───────────────────────────────────────────────────────

    /** Complex-value encoding differs by provider, so object round-tripping is pinned here. */
    @Test
    @Order(25)
    @DisplayName("SET with an object value round-trips identically on every provider")
    void setComplexValueRoundTrips() {
        MulticloudDbKey key = seed("patch-set-obj", Map.of("title", "t"));

        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("city", "Redmond");
        nested.put("zip", "98052");
        client.patch(getAddress(), key, List.of(PatchOperation.set("/nestedObj", nested)));

        ObjectNode doc = read(key);
        assertNotNull(doc.get("nestedObj"), "the object value must be stored");
        assertEquals("Redmond", doc.get("nestedObj").get("city").asText());
        assertEquals("98052", doc.get("nestedObj").get("zip").asText());
    }

    /** {@code SET} null must remain observably different from {@code REMOVE}. */
    @Test
    @Order(26)
    @DisplayName("SET null stores an explicit null, distinct from REMOVE")
    void setNullIsDistinctFromRemove() {
        MulticloudDbKey key = seed("patch-set-null", Map.of("title", "t", "status", "live"));

        client.patch(getAddress(), key, List.of(PatchOperation.set("/status", null)));
        ObjectNode afterSet = read(key);
        assertTrue(afterSet.has("status"), "SET null must leave the field present");
        assertTrue(afterSet.get("status").isNull(), "SET null must store an explicit null");

        client.patch(getAddress(), key, List.of(PatchOperation.remove("/status")));
        assertFalse(read(key).has("status"), "REMOVE must drop the field entirely");
    }

    // ── concurrency ──────────────────────────────────────────────────────────

    /**
     * The headline reason patch exists. Every provider evaluates the delta in
     * its atomic native write or retryable transaction, so N concurrent
     * increments must land N - a client-side read-modify-write would lose some.
     */
    @Test
    @Order(27)
    @DisplayName("concurrent INCREMENTs do not lose updates on any provider")
    void concurrentIncrementsDoNotLoseUpdates() throws Exception {
        MulticloudDbKey key = seed("patch-incr-race", Map.of("value", 0));

        int writers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    client.patch(getAddress(), key, List.of(PatchOperation.increment("/value", 1)));
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(writers, read(key).get("value").asInt(),
                "every concurrent increment must be reflected - none may be lost");
    }
}
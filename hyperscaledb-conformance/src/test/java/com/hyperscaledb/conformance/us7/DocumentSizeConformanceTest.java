package com.hyperscaledb.conformance.us7;

import java.util.Map;
import com.hyperscaledb.api.*;
import com.hyperscaledb.api.internal.DocumentSizeValidator;
import com.hyperscaledb.conformance.ConformanceHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conformance tests for User Story 7 — Uniform Document Size Enforcement (FR-061–FR-063).
 * <p>
 * Verifies that:
 * <ul>
 *   <li>FR-061: Documents within the 400 KB limit are accepted by all providers.</li>
 *   <li>FR-062: Documents exceeding 400 KB are rejected at the SDK layer with
 *       {@link HyperscaleDbErrorCategory#INVALID_REQUEST} before reaching the provider.</li>
 *   <li>FR-063: The rejection is consistent across {@code create()} and {@code upsert()}.</li>
 * </ul>
 * <p>
 * These tests run against a mock/in-process provider via the conformance harness
 * so that oversized document rejection is verifiable without a live provider.
 */
public class DocumentSizeConformanceTest {

    /** 399 KB limit — same as {@code DocumentSizeValidator.MAX_BYTES}. */
    private static final int MAX_BYTES = DocumentSizeValidator.MAX_BYTES;

    // -------------------------------------------- FR-061: within limit

    @Test
    @DisplayName("FR-061: document within 399 KB limit is accepted on upsert")
    void documentWithinLimitIsAccepted() throws Exception {
        try (HyperscaleDbClient client = ConformanceHarness.createClient(ProviderId.DYNAMO)) {
            ResourceAddress address = ConformanceHarness.defaultAddress(ProviderId.DYNAMO);

            // Build a document just under 400 KB
            Map<String, Object> doc = Map.of("payload", "A".repeat(MAX_BYTES - 200));
            HyperscaleDbKey key = HyperscaleDbKey.of("size-test-within", "size-test-within");

            // The SDK size-validation check happens before any provider I/O.
            // A document within the limit must never be rejected with INVALID_REQUEST.
            // Provider connection errors (e.g., no live DynamoDB in unit-test environments)
            // are acceptable — they confirm the document passed the size gate.
            try {
                client.upsert(address, key, doc);
            } catch (HyperscaleDbException e) {
                assertNotEquals(HyperscaleDbErrorCategory.INVALID_REQUEST, e.error().category(),
                        "Document within 400 KB limit must not be rejected with INVALID_REQUEST");
            }
        }
    }

    // -------------------------------------------- FR-062: exceeds limit on upsert

    @Test
    @DisplayName("FR-062: document exceeding 400 KB is rejected on upsert")
    void documentExceedingLimitIsRejectedOnUpsert() throws Exception {
        try (HyperscaleDbClient client = ConformanceHarness.createClient(ProviderId.DYNAMO)) {
            ResourceAddress address = ConformanceHarness.defaultAddress(ProviderId.DYNAMO);

            // Build a document well over 400 KB
            Map<String, Object> doc = Map.of("payload", "B".repeat(MAX_BYTES + 1000));
            HyperscaleDbKey key = HyperscaleDbKey.of("size-test-over", "size-test-over");

            HyperscaleDbException ex = assertThrows(HyperscaleDbException.class,
                    () -> client.upsert(address, key, doc),
                    "Document exceeding 400 KB must throw HyperscaleDbException");
            assertNotNull(ex.error(), "Exception must carry structured error");
            assertEquals(HyperscaleDbErrorCategory.INVALID_REQUEST, ex.error().category(),
                    "Category must be INVALID_REQUEST for oversized documents");
        }
    }

    // -------------------------------------------- FR-063: consistent on create

    @Test
    @DisplayName("FR-063: document exceeding 400 KB is rejected on create")
    void documentExceedingLimitIsRejectedOnCreate() throws Exception {
        try (HyperscaleDbClient client = ConformanceHarness.createClient(ProviderId.DYNAMO)) {
            ResourceAddress address = ConformanceHarness.defaultAddress(ProviderId.DYNAMO);

            Map<String, Object> doc = Map.of("payload", "C".repeat(MAX_BYTES + 1000));
            HyperscaleDbKey key = HyperscaleDbKey.of("size-test-create-over", "size-test-create-over");

            HyperscaleDbException ex = assertThrows(HyperscaleDbException.class,
                    () -> client.create(address, key, doc),
                    "Oversized document must throw on create too");
            assertEquals(HyperscaleDbErrorCategory.INVALID_REQUEST, ex.error().category());
        }
    }
}

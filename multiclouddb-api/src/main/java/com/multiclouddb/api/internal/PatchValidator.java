// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.internal;

import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.OperationNames;
import com.multiclouddb.api.PatchOperation;
import com.multiclouddb.api.PatchNumericDomain;
import com.multiclouddb.api.ProviderId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Validates a portable patch request before it reaches a provider adapter.
 * <p>
 * Every rule enforced here exists because provider-native patch dialects would
 * otherwise diverge. The validator turns those differences into a single,
 * predictable {@link MulticloudDbErrorCategory#INVALID_REQUEST} for every
 * provider that advertises PATCH:
 * <ul>
 *   <li><b>Operation count</b> — Cosmos DB caps a single patch at 10 operations.
 *       The SDK applies that cap uniformly so a request that succeeds on
 *       DynamoDB cannot fail on Cosmos.</li>
 *   <li><b>Overlapping paths</b> — native engines can resolve operands against
 *       different document versions. Two operations touching the same path (or
 *       a path and its ancestor) could therefore produce different results, so
 *       they are rejected.</li>
 *   <li><b>Array indexes</b> — Cosmos DB and DynamoDB expose different
 *       insert/replace/append semantics, and future providers may not expose
 *       addressable array elements. Purely numeric segments are rejected.</li>
 *   <li><b>JSON Pointer escapes</b> — {@code ~0} / {@code ~1} are decoded
 *       inconsistently across native path dialects, so {@code ~} is rejected
 *       outright.</li>
 *   <li><b>Reserved fields</b> — the SDK injects {@code id} / {@code partitionKey}
 *       / {@code sortKey}, uses {@code ttl} / {@code ttlExpiry} for
 *       row-level TTL, and reserves {@code data} for Spanner metadata, so
 *       patching them would corrupt SDK state or silently mean different
 *       things per provider.</li>
 *   <li><b>Numeric floor</b> — DynamoDB rejects non-zero numbers below
 *       {@code 1E-130}, so smaller fractional increments are rejected before
 *       either supported provider is called.</li>
 * </ul>
 */
public final class PatchValidator {

    /** Maximum operations in a single patch — the lowest native cap (Cosmos DB). */
    public static final int MAX_OPERATIONS = MulticloudDbClient.MAX_PATCH_OPERATIONS;

    /**
     * Top-level field names the SDK or a provider owns. Patching them is
     * rejected because the effect would not be portable: {@code id} /
     * {@code partitionKey} / {@code sortKey} are injected from
     * {@link com.multiclouddb.api.MulticloudDbKey}, {@code ttl} is the Cosmos
     * document-TTL field, {@code ttlExpiry} is the DynamoDB TTL attribute, and
     * {@code data} is Spanner's internal document-metadata column. Names
     * starting with {@code _} are rejected separately — Cosmos reserves that
     * prefix for system properties ({@code _ts}, {@code _etag}, ...).
     */
    private static final Set<String> RESERVED_FIELDS = Set.of(
            "id", "partitionkey", "sortkey", "ttl", "ttlexpiry", "data");

    private PatchValidator() {
    }

    /**
     * Validate a patch request.
     *
     * @param operations the caller-supplied operations
     * @param provider   the active provider, stamped into the error envelope
     * @throws MulticloudDbException with category
     *         {@link MulticloudDbErrorCategory#INVALID_REQUEST} if any rule is violated
     */
    public static void validate(List<PatchOperation> operations, ProviderId provider) {
        if (operations == null || operations.isEmpty()) {
            throw invalid(provider, "patch requires at least one operation");
        }
        if (operations.size() > MAX_OPERATIONS) {
            throw invalid(provider, "patch accepts at most " + MAX_OPERATIONS
                    + " operations (Cosmos DB's native limit, applied uniformly for portability); got "
                    + operations.size());
        }

        List<List<String>> seenPaths = new ArrayList<>();
        for (PatchOperation op : operations) {
            if (op == null) {
                throw invalid(provider, "patch operations must not contain null entries");
            }
            List<String> segments = validatePath(op, provider);

            if (op.type() == PatchOperation.Type.INCREMENT) {
                validateIncrementDelta(op, provider);
            } else {
                validateWriteOperand(op, provider);
            }

            for (List<String> seen : seenPaths) {
                if (overlaps(seen, segments)) {
                    throw invalid(provider, "patch operations must address disjoint paths; '"
                            + op.path() + "' overlaps an earlier operation on '/"
                            + String.join("/", seen) + "'. Providers disagree on how "
                            + "overlapping operations within one patch are ordered, so the "
                            + "result would not be portable.");
                }
            }
            seenPaths.add(segments);
        }

        // Every verb and path contributes to the request that reaches the
        // provider. In particular, a REMOVE has no operand but its path can be
        // arbitrarily long, so excluding it would leave a provider-specific
        // oversized request path.
        DocumentSizeValidator.validate(requestPayload(operations), OperationNames.PATCH);
    }

    /**
     * Whether any operation addresses a field below the top level, which
     * requires {@link com.multiclouddb.api.Capability#NESTED_PATCH}.
     * <p>
     * Call only after {@link #validate(List, ProviderId)} has passed.
     *
     * @param operations the validated operations
     * @return {@code true} if at least one path has more than one segment
     */
    public static boolean hasNestedPath(List<PatchOperation> operations) {
        for (PatchOperation op : operations) {
            if (op.isNested()) {
                return true;
            }
        }
        return false;
    }

    private static List<String> validatePath(PatchOperation op, ProviderId provider) {
        String path = op.path();
        if (path == null || path.isEmpty() || path.charAt(0) != '/') {
            throw invalid(provider, "patch path must be an absolute JSON Pointer starting with '/'; got: "
                    + path);
        }
        if (path.indexOf('~') >= 0) {
            throw invalid(provider, "patch path must not contain '~'; JSON Pointer escape sequences "
                    + "are not portable across providers. Offending path: " + path);
        }

        List<String> segments = op.pathSegments();
        for (String segment : segments) {
            if (segment.isEmpty()) {
                throw invalid(provider, "patch path must not contain empty segments: " + path);
            }
            if (isAllDigits(segment)) {
                throw invalid(provider, "patch path segment '" + segment + "' in '" + path
                        + "' looks like an array index. Array element addressing is not portable "
                        + "(native providers differ on insert, replace, and append behavior), "
                        + "so it is rejected. Replace the "
                        + "whole array with a SET operation instead.");
            }
        }

        String root = segments.get(0);
        // Match case-insensitively so a path accepted by a case-sensitive
        // document provider cannot collide with an SDK-owned field on a
        // case-insensitive current or future provider.
        if (RESERVED_FIELDS.contains(root.toLowerCase(Locale.ROOT)) || root.charAt(0) == '_') {
            throw invalid(provider, "patch cannot modify the reserved field '" + root
                    + "'. Key fields (id, partitionKey, sortKey), TTL fields (ttl, ttlExpiry), "
                    + "data, and names starting with '_' are owned by the SDK or the provider. "
                    + "Reserved names are matched without regard to case.");
        }
        return segments;
    }

    /**
     * Confines a {@code SET} / {@code REPLACE} operand to the same portable
     * numeric domain that {@link #validateIncrementDelta} applies to an
     * {@code INCREMENT} delta.
     * <p>
     * Without this, a written number could be stored with a provider-dependent
     * value: DynamoDB's {@code N} type keeps up to 38 digits of exact decimal
     * precision, while Cosmos DB stores JSON numbers and only guarantees an
     * exact round-trip inside the signed 64-bit integral range. Writing
     * {@code Long.MAX_VALUE + 1} therefore returns the value unchanged on
     * DynamoDB but the nearest {@code double} on Cosmos DB — silent divergence
     * on a portable write, which the SDK never allows. Rejecting the operand
     * before dispatch makes the outcome identical everywhere.
     * <p>
     * Nested values are walked because {@code PatchOperation} normalises an
     * operand to {@code null}, {@code String}, {@code Boolean}, {@code Number},
     * {@code List} or {@code Map}, so an out-of-domain number can sit inside an
     * object or array operand just as easily as at the top level.
     */
    private static void validateWriteOperand(PatchOperation op, ProviderId provider) {
        validateOperandValue(op, op.value(), provider);
    }

    private static void validateOperandValue(PatchOperation op, Object value, ProviderId provider) {
        if (value instanceof Number operand) {
            try {
                PatchNumericDomain.normalize(operand);
            } catch (IllegalArgumentException e) {
                throw invalid(provider, op.type() + " on '" + op.path()
                        + "' has a value outside the portable numeric domain: " + e.getMessage());
            }
        } else if (value instanceof Map<?, ?> nested) {
            for (Object element : nested.values()) {
                validateOperandValue(op, element, provider);
            }
        } else if (value instanceof List<?> elements) {
            for (Object element : elements) {
                validateOperandValue(op, element, provider);
            }
        }
    }

    private static void validateIncrementDelta(PatchOperation op, ProviderId provider) {
        if (!(op.value() instanceof Number delta)) {
            throw invalid(provider, "INCREMENT on '" + op.path() + "' requires a numeric delta; got: "
                    + (op.value() == null ? "null" : op.value().getClass().getName()));
        }
        Number normalized;
        try {
            normalized = PatchNumericDomain.normalize(delta);
        } catch (IllegalArgumentException e) {
            throw invalid(provider, "INCREMENT on '" + op.path()
                    + "' has a delta outside the portable numeric domain: " + e.getMessage());
        }
        if (!(normalized instanceof Long)) {
            throw invalid(provider, "INCREMENT on '" + op.path()
                    + "' requires a whole-number delta. Providers accumulate fractional "
                    + "increments differently: DynamoDB adds in exact decimal arithmetic while "
                    + "Cosmos DB adds in IEEE-754 binary64, so incrementing 0.1 by 0.2 stores "
                    + "0.3 on one provider and 0.30000000000000004 on the other. Scale to whole "
                    + "units (for example cents rather than dollars) and increment by an integer.");
        }
    }

    /**
     * Builds the deterministic JSON request shape used for portable size
     * validation. Package-visible for the boundary tests that pin the exact
     * serialization contract.
     */
    static List<Map<String, Object>> requestPayload(List<PatchOperation> operations) {
        List<Map<String, Object>> payload = new ArrayList<>(operations.size());
        for (PatchOperation op : operations) {
            Map<String, Object> encodedOperation = new LinkedHashMap<>();
            encodedOperation.put("type", op.type().name());
            encodedOperation.put("path", op.path());
            if (op.type() != PatchOperation.Type.REMOVE) {
                encodedOperation.put("value", op.value());
            }
            payload.add(encodedOperation);
        }
        return payload;
    }

    /**
     * Whether one path is equal to, or an ancestor of, the other. Sibling paths
     * ({@code /a/b} and {@code /a/c}) do not overlap and are allowed.
     */
    private static boolean overlaps(List<String> a, List<String> b) {
        int shared = Math.min(a.size(), b.size());
        for (int i = 0; i < shared; i++) {
            // Treat case-only path variants as aliases so one portable request
            // cannot have provider-dependent meaning.
            if (!a.get(i).equalsIgnoreCase(b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllDigits(String segment) {
        for (int i = 0; i < segment.length(); i++) {
            if (!Character.isDigit(segment.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static MulticloudDbException invalid(ProviderId provider, String message) {
        return new MulticloudDbException(new MulticloudDbError(
                MulticloudDbErrorCategory.INVALID_REQUEST,
                message,
                provider,
                OperationNames.PATCH,
                false,
                Map.of()));
    }
}
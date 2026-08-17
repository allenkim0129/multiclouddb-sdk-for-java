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
 * Every rule enforced here exists because the three providers would otherwise
 * diverge — the validator is the mechanism that turns "Cosmos and DynamoDB
 * disagree" into a single, predictable {@link MulticloudDbErrorCategory#INVALID_REQUEST}
 * on all of them:
 * <ul>
 *   <li><b>Operation count</b> — Cosmos DB caps a single patch at 10 operations.
 *       The SDK applies that cap uniformly so a request that succeeds on
 *       DynamoDB cannot fail on Cosmos.</li>
 *   <li><b>Overlapping paths</b> — Cosmos and Spanner evaluate operations
 *       sequentially, while DynamoDB resolves every operand against the
 *       <em>pre-update</em> item. Two operations touching the same path (or a
 *       path and its ancestor) would therefore produce different results per
 *       provider, so they are rejected.</li>
 *   <li><b>Array indexes</b> — insert-at-index shifts elements on Cosmos but
 *       replaces-or-appends on DynamoDB, and Spanner has no addressable array
 *       at all. Purely numeric segments are rejected.</li>
 *   <li><b>JSON Pointer escapes</b> — {@code ~0} / {@code ~1} are decoded
 *       inconsistently across the three path dialects, so {@code ~} is rejected
 *       outright.</li>
 *   <li><b>Reserved fields</b> — the SDK injects {@code id} / {@code partitionKey}
 *       / {@code sortKey} and uses {@code ttl} / {@code ttlExpiry} for
 *       row-level TTL, so patching them would corrupt the key or silently mean
 *       different things per provider.</li>
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
     * document-TTL field, and {@code ttlExpiry} is the DynamoDB TTL attribute.
     * Names starting with {@code _} are rejected separately — Cosmos reserves
     * that prefix for system properties ({@code _ts}, {@code _etag}, ...).
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
                        + "(Cosmos DB inserts and shifts, DynamoDB replaces or appends, Spanner "
                        + "cannot address array elements at all), so it is rejected. Replace the "
                        + "whole array with a SET operation instead.");
            }
        }

        String root = segments.get(0);
        // Matched case-insensitively: Spanner resolves column names without regard to
        // case, so "/PartitionKey" would collide with the real key column there while
        // Cosmos and DynamoDB would happily create a second, stray field. The `data`
        // column is also SDK-owned metadata on Spanner. Rejecting every casing keeps
        // the outcome identical on all three.
        if (RESERVED_FIELDS.contains(root.toLowerCase(Locale.ROOT)) || root.charAt(0) == '_') {
            throw invalid(provider, "patch cannot modify the reserved field '" + root
                    + "'. Key fields (id, partitionKey, sortKey), TTL fields (ttl, ttlExpiry), "
                    + "and names starting with '_' are owned by the SDK or the provider. "
                    + "Reserved names are matched without regard to case.");
        }
        return segments;
    }

    private static void validateIncrementDelta(PatchOperation op, ProviderId provider) {
        if (!(op.value() instanceof Number delta)) {
            throw invalid(provider, "INCREMENT on '" + op.path() + "' requires a numeric delta; got: "
                    + (op.value() == null ? "null" : op.value().getClass().getName()));
        }
        try {
            PatchNumericDomain.normalize(delta);
        } catch (IllegalArgumentException e) {
            throw invalid(provider, "INCREMENT on '" + op.path()
                    + "' has a delta outside the portable numeric domain: " + e.getMessage());
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
            // Spanner column names are case-insensitive, while Cosmos and DynamoDB
            // accept case-distinct document fields. Treating segments as aliases here
            // prevents a single portable patch from ambiguously touching both.
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
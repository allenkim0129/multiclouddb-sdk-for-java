// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A single field-level modification applied by
 * {@link MulticloudDbClient#patch(ResourceAddress, MulticloudDbKey, List, OperationOptions)}.
 * <p>
 * Unlike {@code upsert()} / {@code update()} — which replace the whole stored
 * document — a patch sends only the fields being changed and lets the provider
 * apply them atomically. Providers may use native partial writes or another
 * equivalent atomic primitive. A provider without a safe implementation must
 * declare {@link Capability#PATCH} unsupported rather than emulate patch through
 * a non-transactional client-side read-modify-write.
 *
 * <h3>Paths</h3>
 * {@link #path()} is a <b>JSON Pointer</b> rooted at the document: {@code /status},
 * {@code /address/city}. Restrictions that keep the contract portable across
 * current and future providers:
 * <ul>
 *   <li>The path must be absolute (start with {@code /}) and must not be the
 *       document root.</li>
 *   <li>Segments must not be empty, must not contain {@code ~} (JSON Pointer
 *       escapes are not supported), and must not be purely numeric — array
 *       element addressing is not portable and is rejected.</li>
 *   <li>A path with more than one segment is a <em>nested</em> path and
 *       requires {@link Capability#NESTED_PATCH}.</li>
 *   <li>Key and provider-reserved field names ({@code id}, {@code partitionKey},
 *       {@code sortKey}, {@code ttl}, {@code ttlExpiry}, {@code data}, and any
 *       name starting with {@code _}) are rejected.</li>
 * </ul>
 *
 * <h3>Operation semantics</h3>
 * <table border="1">
 * <caption>Portable patch operation semantics</caption>
 * <tr><th>Type</th><th>Target must exist</th><th>Effect</th></tr>
 * <tr><td>{@link Type#SET}</td><td>no</td><td>Creates the field, or overwrites it if already present.</td></tr>
 * <tr><td>{@link Type#REPLACE}</td><td>yes</td><td>Overwrites an existing field; {@code NOT_FOUND} if the field is absent.</td></tr>
 * <tr><td>{@link Type#REMOVE}</td><td>yes</td><td>Deletes an existing field; {@code NOT_FOUND} if the field is absent.</td></tr>
 * <tr><td>{@link Type#INCREMENT}</td><td>yes</td><td>Adds a whole-number delta to an existing numeric field; {@code NOT_FOUND} if absent, {@code INVALID_REQUEST} if not numeric or if the delta is fractional.</td></tr>
 * </table>
 * <p>
 * For a nested path, every <em>parent</em> object must already exist on every
 * operation type including {@code SET}; patch never creates intermediate
 * objects, because provider-native recursive path creation is not portable.
 * <p>
 * Instances are immutable and safe to share across threads. Values are
 * normalised at construction into detached JSON-compatible scalars, maps, and
 * lists. Arrays and collections become immutable lists; mutable
 * {@link CharSequence}s become strings; {@link Date}s become epoch-millisecond
 * {@link Long}s; and {@link AtomicInteger}/{@link AtomicLong} become immutable
 * numeric values. Jackson JSON trees and ordinary POJOs are normalised through
 * the SDK Jackson mapper into the same map/list/scalar shape. Map keys must be
 * strings, non-finite floating-point values are rejected for replacement
 * operands, and a value the mapper cannot represent causes construction to
 * fail with {@link IllegalArgumentException}. An {@code INCREMENT} delta is
 * retained for shared request validation so an out-of-domain numeric value
 * returns the portable {@code INVALID_REQUEST} error on submission. No provider
 * performs its own operand serialisation.
 *
 * @see MulticloudDbClient#patch(ResourceAddress, MulticloudDbKey, List, OperationOptions)
 * @see Capability#PATCH
 * @see Capability#NESTED_PATCH
 */
public final class PatchOperation {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    /** The kind of modification a {@link PatchOperation} performs. */
    public enum Type {
        /** Create the field, or overwrite it when already present. */
        SET,
        /** Overwrite a field that must already exist. */
        REPLACE,
        /** Delete a field that must already exist. */
        REMOVE,
        /** Add a whole-number delta to a field that must already exist and hold a number. */
        INCREMENT
    }

    private final Type type;
    private final String path;
    private final Object value;

    private PatchOperation(Type type, String path, Object value) {
        this.type = Objects.requireNonNull(type, "type");
        this.path = Objects.requireNonNull(path, "path");
        // INCREMENT retains non-finite deltas long enough for PatchValidator to
        // return the portable INVALID_REQUEST envelope instead of leaking a
        // construction-time IllegalArgumentException. All other operands must
        // already be JSON-compatible at construction.
        this.value = snapshotValue(value, type == Type.INCREMENT);
    }

    /**
     * Create the field at {@code path}, or overwrite it if it already exists.
     *
     * @param path  JSON Pointer to the target field, e.g. {@code /status}
     * @param value the new value; may be {@code null} to store an explicit null
     * @return the operation
     */
    public static PatchOperation set(String path, Object value) {
        return new PatchOperation(Type.SET, path, value);
    }

    /**
     * Overwrite the field at {@code path}, which must already exist.
     *
     * @param path  JSON Pointer to the target field
     * @param value the new value; may be {@code null} to store an explicit null
     * @return the operation
     */
    public static PatchOperation replace(String path, Object value) {
        return new PatchOperation(Type.REPLACE, path, value);
    }

    /**
     * Delete the field at {@code path}, which must already exist.
     *
     * @param path JSON Pointer to the target field
     * @return the operation
     */
    public static PatchOperation remove(String path) {
        return new PatchOperation(Type.REMOVE, path, null);
    }

    /**
     * Add {@code delta} to the existing numeric field at {@code path}.
     * <p>
     * The addition is evaluated by the provider inside the same atomic write, so
     * concurrent increments do not lose updates.
     * <p>
     * <b>The delta must be a whole number</b>, and it and the resulting value
     * must fit signed 64-bit range. Integral results are exact on every
     * provider that advertises PATCH, but accumulated <em>fractional</em>
     * results are not bit-identical across the current implementations -
     * DynamoDB adds in exact decimal arithmetic while Cosmos DB uses IEEE-754
     * binary64 - so <em>every</em> fractional delta is rejected with
     * {@code INVALID_REQUEST}, whatever its magnitude. Scale to whole units
     * (cents rather than dollars) and increment by an integer. Normalization
     * folds whole-valued floating types down to {@code Long}, so {@code 1.0d}
     * and {@code 1} are the same portable delta and both succeed; check a
     * caller-supplied delta up front with
     * {@link PatchNumericDomain#isIntegralDelta(Number)}.
     * <p>
     * The wider fractional domain described by {@link PatchNumericDomain}
     * governs values written by {@link #set} and {@link #replace}, which store
     * identically everywhere because no server-side accumulation occurs.
     *
     * @param path  JSON Pointer to the target field
     * @param delta the amount to add; may be negative; must be whole-valued.
     *              A fractional delta, or an integral delta or result outside
     *              signed 64-bit range, fails with {@code INVALID_REQUEST} when
     *              the patch is submitted
     * @return the operation
     */
    public static PatchOperation increment(String path, Number delta) {
        return new PatchOperation(Type.INCREMENT, path, Objects.requireNonNull(delta, "delta"));
    }

    /** The kind of modification this operation performs. */
    public Type type() {
        return type;
    }

    /** The raw JSON Pointer supplied by the caller. */
    public String path() {
        return path;
    }

    /**
     * The operand: the new value for {@link Type#SET} / {@link Type#REPLACE},
     * the {@link Number} delta for {@link Type#INCREMENT}, and {@code null} for
     * {@link Type#REMOVE}.
     * <p>
     * The returned object graph is the detached snapshot taken at construction:
     * every container is unmodifiable and every leaf is an immutable scalar, so
     * it is safe to hand out directly and safe to share across threads. Callers
     * must not attempt to mutate it — nested maps and lists throw
     * {@link UnsupportedOperationException}.
     */
    public Object value() {
        return value;
    }

    /**
     * Whether the target path must already exist for this operation to succeed.
     * True for every type except {@link Type#SET}.
     */
    public boolean requiresExistingPath() {
        return type != Type.SET;
    }

    /**
     * Split {@link #path()} into its individual segments.
     * <p>
     * Callers must validate the operation first (the SDK does this in
     * {@code DefaultMulticloudDbClient.patch}); this method assumes a
     * well-formed absolute pointer and throws {@link IllegalArgumentException}
     * otherwise.
     *
     * @return the decoded, non-empty segment list
     * @throws IllegalArgumentException if the path is not an absolute JSON Pointer
     */
    public List<String> pathSegments() {
        if (path.isEmpty() || path.charAt(0) != '/') {
            throw new IllegalArgumentException(
                    "Patch path must be an absolute JSON Pointer starting with '/': " + path);
        }
        List<String> segments = new ArrayList<>();
        int start = 1;
        for (int i = 1; i <= path.length(); i++) {
            if (i == path.length() || path.charAt(i) == '/') {
                segments.add(path.substring(start, i));
                start = i + 1;
            }
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("Patch path must address a field: " + path);
        }
        return Collections.unmodifiableList(segments);
    }

    /** The first path segment — the top-level document field this operation touches. */
    public String rootField() {
        return pathSegments().get(0);
    }

    /**
     * Whether this operation addresses a field below the top level, which
     * requires {@link Capability#NESTED_PATCH}.
     */
    public boolean isNested() {
        return pathSegments().size() > 1;
    }

    /**
     * Creates a detached, recursive JSON-compatible snapshot. Taken once at
     * construction so no queued work — and no caller holding the result of
     * {@link #value()} — can observe or cause mutation: every container in the
     * returned graph is unmodifiable and every leaf is an immutable scalar.
     */
    private static Object snapshotValue(Object candidate, boolean allowNonFinite) {
        return snapshotValue(candidate, new IdentityHashMap<>(), allowNonFinite);
    }

    private static Object snapshotValue(Object candidate, IdentityHashMap<Object, Boolean> ancestors,
            boolean allowNonFinite) {
        if (candidate == null) {
            return null;
        }
        if (candidate instanceof JsonNode node) {
            return snapshotJsonNode(node, allowNonFinite);
        }
        if (candidate instanceof AtomicInteger value) {
            return (long) value.get();
        }
        if (candidate instanceof AtomicLong value) {
            return value.get();
        }
        if (candidate instanceof Date value) {
            return value.getTime();
        }
        if (candidate instanceof CharSequence value) {
            return value.toString();
        }
        if (candidate instanceof Number value) {
            return snapshotNumber(value, allowNonFinite);
        }
        if (candidate instanceof Boolean || candidate instanceof String) {
            return candidate;
        }
        if (candidate != null && candidate.getClass().isArray()) {
            enter(candidate, ancestors);
            try {
                int length = Array.getLength(candidate);
                List<Object> copy = new ArrayList<>(length);
                for (int i = 0; i < length; i++) {
                    copy.add(snapshotValue(Array.get(candidate, i), ancestors, allowNonFinite));
                }
                return Collections.unmodifiableList(copy);
            } finally {
                ancestors.remove(candidate);
            }
        }
        if (candidate instanceof Map<?, ?> source) {
            enter(candidate, ancestors);
            try {
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : source.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw new IllegalArgumentException("Patch map keys must be strings; got "
                                + (entry.getKey() == null ? "null"
                                        : entry.getKey().getClass().getName()));
                    }
                    copy.put(key, snapshotValue(entry.getValue(), ancestors, allowNonFinite));
                }
                return Collections.unmodifiableMap(copy);
            } finally {
                ancestors.remove(candidate);
            }
        }
        if (candidate instanceof Collection<?> source) {
            enter(candidate, ancestors);
            try {
                List<Object> copy = new ArrayList<>(source.size());
                for (Object element : source) {
                    copy.add(snapshotValue(element, ancestors, allowNonFinite));
                }
                return Collections.unmodifiableList(copy);
            } finally {
                ancestors.remove(candidate);
            }
        }

        try {
            return snapshotJsonNode(JSON_MAPPER.valueToTree(candidate), allowNonFinite);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Patch value of type "
                    + candidate.getClass().getName()
                    + " cannot be normalised to a detached JSON-compatible value", e);
        }
    }

    private static Number snapshotNumber(Number value, boolean allowNonFinite) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Float floating) {
            if (!allowNonFinite && !Float.isFinite(floating)) {
                throw new IllegalArgumentException("Patch floating-point values must be finite");
            }
            return floating;
        }
        if (value instanceof Double floating) {
            if (!allowNonFinite && !Double.isFinite(floating)) {
                throw new IllegalArgumentException("Patch floating-point values must be finite");
            }
            return floating;
        }
        throw new IllegalArgumentException("Patch values use unsupported Number type "
                + value.getClass().getName());
    }

    private static Object snapshotJsonNode(JsonNode node, boolean allowNonFinite) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual() || node.isBinary()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return snapshotNumber(node.numberValue(), allowNonFinite);
        }
        if (node.isArray()) {
            List<Object> copy = new ArrayList<>(node.size());
            for (JsonNode element : node) {
                copy.add(snapshotJsonNode(element, allowNonFinite));
            }
            return Collections.unmodifiableList(copy);
        }
        if (node.isObject()) {
            Map<String, Object> copy = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry ->
                    copy.put(entry.getKey(), snapshotJsonNode(entry.getValue(), allowNonFinite)));
            return Collections.unmodifiableMap(copy);
        }
        throw new IllegalArgumentException("Patch JSON value must be a scalar, object, or array; got "
                + node.getNodeType());
    }

    private static void enter(Object candidate, IdentityHashMap<Object, Boolean> ancestors) {
        if (ancestors.put(candidate, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("Patch values must not contain cyclic arrays, maps, or collections");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PatchOperation other)) return false;
        return type == other.type
                && path.equals(other.path)
                && Objects.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, path, value);
    }

    @Override
    public String toString() {
        return "PatchOperation{" + type + " " + path
                + (type == Type.REMOVE ? "" : " = " + value) + "}";
    }
}
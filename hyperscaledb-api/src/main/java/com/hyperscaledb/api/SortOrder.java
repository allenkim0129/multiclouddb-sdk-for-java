package com.hyperscaledb.api;

import java.util.Objects;

/**
 * Specifies how query results should be sorted on a single field.
 * <p>
 * ORDER BY is a capability-gated feature — not all providers support it.
 * Check {@link Capability#ORDER_BY} before submitting a query with sort orders.
 *
 * @see SortDirection
 * @see QueryRequest.Builder#orderBy(String, SortDirection)
 */
public final class SortOrder {

    private final String field;
    private final SortDirection direction;

    private SortOrder(String field, SortDirection direction) {
        Objects.requireNonNull(field, "SortOrder field must not be null");
        Objects.requireNonNull(direction, "SortOrder direction must not be null");
        if (field.isBlank()) {
            throw new IllegalArgumentException("SortOrder field must not be blank");
        }
        this.field = field;
        this.direction = direction;
    }

    /**
     * Creates a new sort specification.
     *
     * @param field     the field name to sort on (must be non-null, non-blank)
     * @param direction the sort direction (must be non-null)
     * @return a new {@code SortOrder}
     */
    public static SortOrder of(String field, SortDirection direction) {
        return new SortOrder(field, direction);
    }

    /** Returns the field name to sort on. */
    public String field() {
        return field;
    }

    /** Returns the sort direction. */
    public SortDirection direction() {
        return direction;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SortOrder that)) return false;
        return field.equals(that.field) && direction == that.direction;
    }

    @Override
    public int hashCode() {
        return Objects.hash(field, direction);
    }

    @Override
    public String toString() {
        return "SortOrder{field='" + field + "', direction=" + direction + "}";
    }
}

package com.hyperscaledb.api;

/**
 * Sort direction for ORDER BY clauses in query requests.
 *
 * @see SortOrder
 * @see QueryRequest.Builder#orderBy(String, SortDirection)
 */
public enum SortDirection {

    /** Ascending order (smallest value first). */
    ASC,

    /** Descending order (largest value first). */
    DESC
}

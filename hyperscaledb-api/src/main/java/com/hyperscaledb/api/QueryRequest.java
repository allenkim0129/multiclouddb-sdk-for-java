package com.hyperscaledb.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Portable query request.
 */
public final class QueryRequest {

    private final String expression;
    private final String nativeExpression;
    private final Map<String, Object> parameters;
    private final Integer pageSize;
    private final String continuationToken;
    private final String partitionKey;
    private final Integer limit;
    private final List<SortOrder> orderBy;

    private QueryRequest(Builder builder) {
        if (builder.expression != null && builder.nativeExpression != null) {
            throw new IllegalArgumentException(
                    "expression and nativeExpression are mutually exclusive; set only one");
        }
        if (builder.limit != null && builder.limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1 when set");
        }
        this.expression = builder.expression;
        this.nativeExpression = builder.nativeExpression;
        this.parameters = builder.parameters != null ? Map.copyOf(builder.parameters) : Collections.emptyMap();
        this.pageSize = builder.pageSize;
        this.continuationToken = builder.continuationToken;
        this.partitionKey = builder.partitionKey;
        this.limit = builder.limit;
        this.orderBy = builder.orderBy != null ? List.copyOf(builder.orderBy) : Collections.emptyList();
    }

    public String expression() {
        return expression;
    }

    /**
     * Provider-native query expression (mutually exclusive with
     * {@link #expression()}).
     * When set, the expression is passed directly to the provider without
     * translation.
     */
    public String nativeExpression() {
        return nativeExpression;
    }

    public Map<String, Object> parameters() {
        return parameters;
    }

    /**
     * Preferred maximum items per page. Providers may return fewer or more.
     */
    public Integer pageSize() {
        return pageSize;
    }

    /**
     * Opaque continuation token from a previous QueryPage. Null for the first page.
     */
    public String continuationToken() {
        return continuationToken;
    }

    /**
     * Optional partition key value to scope the query.
     * <p>When set, each provider uses its native mechanism to restrict the query
     * to items sharing this partition key value:
     * <ul>
     *   <li>Cosmos&nbsp;DB &ndash; {@code CosmosQueryRequestOptions.setPartitionKey()}</li>
     *   <li>DynamoDB &ndash; adds a {@code sortKey} equality condition</li>
     *   <li>Spanner &ndash; adds a {@code sortKey} equality condition</li>
     * </ul>
     * <p>When {@code null} (the default), the query is sent cross-partition as before.
     * This field may be combined with {@link #expression()} or
     * {@link #nativeExpression()} for further filtering.
     *
     * @return partition key value, or {@code null}
     */
    public String partitionKey() {
        return partitionKey;
    }

    /**
     * Optional maximum number of items to return (Top N).
     * Applied after filtering and partition scoping.
     * {@code null} means no limit.
     *
     * @return result limit, or {@code null}
     */
    public Integer limit() {
        return limit;
    }

    /**
     * Optional list of sort specifications for ORDER BY.
     * Empty list means no ordering.
     * <p>ORDER BY is capability-gated — check {@link Capability#ORDER_BY} before use.
     *
     * @return unmodifiable list of sort orders, never null
     */
    public List<SortOrder> orderBy() {
        return orderBy;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String expression;
        private String nativeExpression;
        private Map<String, Object> parameters;
        private Integer pageSize;
        private String continuationToken;
        private String partitionKey;
        private Integer limit;
        private List<SortOrder> orderBy;

        public Builder expression(String expression) {
            this.expression = expression;
            return this;
        }

        /**
         * Set a provider-native expression (mutually exclusive with
         * {@link #expression(String)}).
         */
        public Builder nativeExpression(String nativeExpression) {
            this.nativeExpression = nativeExpression;
            return this;
        }

        public Builder parameters(Map<String, Object> parameters) {
            this.parameters = parameters;
            return this;
        }

        public Builder pageSize(int pageSize) {
            this.pageSize = pageSize;
            return this;
        }

        public Builder continuationToken(String continuationToken) {
            this.continuationToken = continuationToken;
            return this;
        }

        /**
         * Scope the query to items sharing this partition key value.
         * Each provider maps this to its native partition-scoping mechanism.
         *
         * @param partitionKey the partition key value (pass {@code null} for cross-partition)
         * @return this builder
         */
        public Builder partitionKey(String partitionKey) {
            this.partitionKey = partitionKey;
            return this;
        }

        /**
         * Limit the number of results returned (Top N).
         *
         * @param limit maximum number of items (must be >= 1)
         * @return this builder
         */
        public Builder limit(int limit) {
            this.limit = limit;
            return this;
        }

        /**
         * Append a sort specification to the ORDER BY clause.
         * May be called multiple times for multi-field sorting.
         * ORDER BY is capability-gated — check {@link Capability#ORDER_BY} before use.
         *
         * @param field     the field name to sort on
         * @param direction the sort direction
         * @return this builder
         */
        public Builder orderBy(String field, SortDirection direction) {
            if (this.orderBy == null) {
                this.orderBy = new ArrayList<>();
            }
            this.orderBy.add(SortOrder.of(field, direction));
            return this;
        }

        public QueryRequest build() {
            return new QueryRequest(this);
        }
    }
}

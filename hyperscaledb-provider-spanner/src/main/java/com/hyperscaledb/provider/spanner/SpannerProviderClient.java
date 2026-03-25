package com.hyperscaledb.provider.spanner;

import com.hyperscaledb.api.CapabilitySet;
import com.hyperscaledb.api.DocumentMetadata;
import com.hyperscaledb.api.DocumentResult;
import com.hyperscaledb.api.HyperscaleDbClientConfig;
import com.hyperscaledb.api.HyperscaleDbError;
import com.hyperscaledb.api.HyperscaleDbErrorCategory;
import com.hyperscaledb.api.HyperscaleDbException;
import com.hyperscaledb.api.OperationNames;
import com.hyperscaledb.api.OperationOptions;
import com.hyperscaledb.api.ProviderId;
import com.hyperscaledb.api.QueryPage;
import com.hyperscaledb.api.QueryRequest;
import com.hyperscaledb.api.ResourceAddress;
import com.hyperscaledb.api.SortOrder;
import com.hyperscaledb.api.query.TranslatedQuery;
import com.hyperscaledb.spi.HyperscaleDbProviderClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.spanner.DatabaseAdminClient;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.DatabaseId;
import com.google.cloud.spanner.ErrorCode;
import com.google.cloud.spanner.KeySet;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerException;
import com.google.cloud.spanner.SpannerOptions;
import com.google.cloud.spanner.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Google Cloud Spanner provider client implementing CRUD + query operations.
 * <p>
 * Connection config keys:
 * <ul>
 * <li>{@code projectId} - GCP project ID</li>
 * <li>{@code instanceId} - Spanner instance ID</li>
 * <li>{@code databaseId} - Spanner database ID</li>
 * <li>{@code emulatorHost} - Optional emulator host (e.g.,
 * "localhost:9010")</li>
 * </ul>
 * <p>
 * Table conventions:
 * <ul>
 * <li>Primary key columns:
 * {@code partitionKey STRING(MAX), sortKey STRING(MAX)}</li>
 * <li>Document fields are stored as individual columns (STRING, INT64, BOOL,
 * FLOAT64)</li>
 * </ul>
 */
public class SpannerProviderClient implements HyperscaleDbProviderClient {

    private static final Logger LOG = LoggerFactory.getLogger(SpannerProviderClient.class);

    private final Spanner spanner;
    private final DatabaseClient databaseClient;
    private final HyperscaleDbClientConfig config;
    private final String projectId;
    private final String instanceId;
    private final String databaseId;

    public SpannerProviderClient(HyperscaleDbClientConfig config) {
        this.config = config;
        this.projectId = config.connection().getOrDefault(SpannerConstants.CONFIG_PROJECT_ID, SpannerConstants.CONFIG_PROJECT_ID_DEFAULT);
        this.instanceId = config.connection().get(SpannerConstants.CONFIG_INSTANCE_ID);
        this.databaseId = config.connection().get(SpannerConstants.CONFIG_DATABASE_ID);
        String emulatorHost = config.connection().get(SpannerConstants.CONFIG_EMULATOR_HOST);

        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException(SpannerConstants.ERR_INSTANCE_ID_REQUIRED);
        }
        if (databaseId == null || databaseId.isBlank()) {
            throw new IllegalArgumentException(SpannerConstants.ERR_DATABASE_ID_REQUIRED);
        }

        SpannerOptions.Builder builder = SpannerOptions.newBuilder()
                .setProjectId(projectId);

        if (emulatorHost != null && !emulatorHost.isBlank()) {
            builder.setEmulatorHost(emulatorHost);
        }

        this.spanner = builder.build().getService();
        this.databaseClient = spanner.getDatabaseClient(
                DatabaseId.of(projectId, instanceId, databaseId));
        LOG.info("Spanner client created for project={}, instance={}, database={}, emulator={}",
                projectId, instanceId, databaseId, emulatorHost != null ? emulatorHost : "none");
    }

    @Override
    public void create(ResourceAddress address, com.hyperscaledb.api.Key key, JsonNode document, OperationOptions options) {
        try {
            String table = address.collection();
            Mutation.WriteBuilder mutation = Mutation.newInsertBuilder(table)
                    .set(SpannerConstants.FIELD_PARTITION_KEY).to(key.partitionKey())
                    .set(SpannerConstants.FIELD_SORT_KEY).to(key.sortKey() != null ? key.sortKey() : key.partitionKey());

            writeMutationFields(mutation, document);
            databaseClient.write(List.of(mutation.build()));
            logItemDiagnostics(OperationNames.CREATE, address);
        } catch (SpannerException e) {
            throw SpannerErrorMapper.map(e, OperationNames.CREATE);
        }
    }

    @Override
    public void update(ResourceAddress address, com.hyperscaledb.api.Key key, JsonNode document, OperationOptions options) {
        try {
            String table = address.collection();
            Mutation.WriteBuilder mutation = Mutation.newUpdateBuilder(table)
                    .set(SpannerConstants.FIELD_PARTITION_KEY).to(key.partitionKey())
                    .set(SpannerConstants.FIELD_SORT_KEY).to(key.sortKey() != null ? key.sortKey() : key.partitionKey());

            writeMutationFields(mutation, document);
            databaseClient.write(List.of(mutation.build()));
            logItemDiagnostics(OperationNames.UPDATE, address);
        } catch (SpannerException e) {
            throw SpannerErrorMapper.map(e, OperationNames.UPDATE);
        }
    }

    @Override
    public void upsert(ResourceAddress address, com.hyperscaledb.api.Key key, JsonNode document, OperationOptions options) {
        try {
            String table = address.collection();
            Mutation.WriteBuilder mutation = Mutation.newInsertOrUpdateBuilder(table)
                    .set(SpannerConstants.FIELD_PARTITION_KEY).to(key.partitionKey())
                    .set(SpannerConstants.FIELD_SORT_KEY).to(key.sortKey() != null ? key.sortKey() : key.partitionKey());

            writeMutationFields(mutation, document);
            databaseClient.write(List.of(mutation.build()));
            logItemDiagnostics(OperationNames.UPSERT, address);
        } catch (SpannerException e) {
            throw SpannerErrorMapper.map(e, OperationNames.UPSERT);
        }
    }

    /** Writes document fields into a mutation, skipping PK columns. */
    private void writeMutationFields(Mutation.WriteBuilder mutation, JsonNode document) {
        if (document != null && document.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = document.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String name = field.getKey();
                JsonNode value = field.getValue();

                // Skip primary key fields — already set by caller
                if (SpannerConstants.FIELD_SORT_KEY.equals(name) || SpannerConstants.FIELD_PARTITION_KEY.equals(name))
                    continue;

                setMutationValue(mutation, name, value);
            }
        }
    }

    @Override
    public DocumentResult read(ResourceAddress address, com.hyperscaledb.api.Key key, OperationOptions options) {
        try {
            String table = address.collection();
            String partitionKeyVal = key.partitionKey();
            String sortKeyVal = key.sortKey() != null ? key.sortKey() : key.partitionKey();

            Statement statement = Statement.newBuilder(
                    String.format(SpannerConstants.QUERY_READ_BY_KEY, table))
                    .bind(SpannerConstants.FIELD_PARTITION_KEY).to(partitionKeyVal)
                    .bind(SpannerConstants.FIELD_SORT_KEY).to(sortKeyVal)
                    .build();

            try (ResultSet rs = databaseClient.singleUse().executeQuery(statement)) {
                if (rs.next()) {
                    JsonNode rawItem = SpannerRowMapper.toJsonNode(rs);
                    if (!(rawItem instanceof ObjectNode item)) {
                        throw new HyperscaleDbException(new HyperscaleDbError(
                                HyperscaleDbErrorCategory.PROVIDER_ERROR,
                                "SpannerRowMapper.toJsonNode returned a non-ObjectNode: "
                                        + rawItem.getClass().getSimpleName(),
                                ProviderId.SPANNER, OperationNames.READ, false, null));
                    }

                    DocumentMetadata metadata = null;
                    if (options != null && options.includeMetadata()) {
                        // Spanner does not expose per-row commit timestamps via query unless
                        // the table has allow_commit_timestamp=true. Return empty shell.
                        metadata = DocumentMetadata.builder().build();
                    }
                    return new DocumentResult(item, metadata);
                }
                return null;
            }
        } catch (SpannerException e) {
            throw SpannerErrorMapper.map(e, OperationNames.READ);
        }
    }

    @Override
    public void delete(ResourceAddress address, com.hyperscaledb.api.Key key, OperationOptions options) {
        try {
            String table = address.collection();
            String partitionKeyVal = key.partitionKey();
            String sortKeyVal = key.sortKey() != null ? key.sortKey() : key.partitionKey();

            com.google.cloud.spanner.Key spannerKey = com.google.cloud.spanner.Key.of(partitionKeyVal, sortKeyVal);
            Mutation deleteMutation = Mutation.delete(table, KeySet.singleKey(spannerKey));

            databaseClient.write(List.of(deleteMutation));
            logItemDiagnostics(OperationNames.DELETE, address);
        } catch (SpannerException e) {
            // Delete is idempotent — NOT_FOUND is not an error
            if (e.getErrorCode() == ErrorCode.NOT_FOUND) {
                return;
            }
            throw SpannerErrorMapper.map(e, OperationNames.DELETE);
        }
    }

    @Override
    public QueryPage query(ResourceAddress address, QueryRequest query, OperationOptions options) {
        try {
            String table = address.collection();
            long offset = SpannerContinuationToken.decode(query.continuationToken());

            // Native expression passthrough
            if (query.nativeExpression() != null && !query.nativeExpression().isBlank()) {
                String stmt = query.nativeExpression();
                Map<String, Object> params = query.parameters();
                if (query.partitionKey() != null) {
                    stmt = appendPartitionKeyConditionSQL(stmt);
                    Map<String, Object> combined = new LinkedHashMap<>();
                    if (params != null) {
                        combined.putAll(params);
                    }
                    combined.put(SpannerConstants.PARAM_PK_VAL, query.partitionKey());
                    params = combined;
                }
                return executeStatement(stmt, params, query.pageSize(), offset);
            }

            // Expression-based query or full scan
            String expression = query.expression();
            if (expression == null || expression.isBlank()
                    || expression.trim().equalsIgnoreCase(SpannerConstants.QUERY_SELECT_ALL_COSMOS)) {
                if (query.partitionKey() != null) {
                    // Scope scan to items with matching partitionKey
                    return executeStatement(
                            String.format(SpannerConstants.QUERY_SCOPED_FULL_SCAN, table),
                            Map.of(SpannerConstants.PARAM_PK_VAL, query.partitionKey()),
                            query.pageSize(), offset, query);
                }
                // Full scan
                return executeStatement(SpannerConstants.QUERY_SELECT_ALL_PREFIX + table, null, query.pageSize(), offset, query);
            }

            // Legacy: pass through as-is
            if (query.partitionKey() != null) {
                String stmt = appendPartitionKeyConditionSQL(expression);
                Map<String, Object> combined = new LinkedHashMap<>();
                if (query.parameters() != null) {
                    combined.putAll(query.parameters());
                }
                combined.put(SpannerConstants.PARAM_PK_VAL, query.partitionKey());
                return executeStatement(stmt, combined, query.pageSize(), offset, query);
            }
            return executeStatement(expression, query.parameters(), query.pageSize(), offset, query);
        } catch (SpannerException e) {
            throw SpannerErrorMapper.map(e, OperationNames.QUERY);
        }
    }

    @Override
    public QueryPage queryWithTranslation(ResourceAddress address, TranslatedQuery translated,
            QueryRequest query, OperationOptions options) {
        try {
            long offset = SpannerContinuationToken.decode(query.continuationToken());
            int pageSize = query.pageSize() != null ? query.pageSize() : SpannerConstants.PAGE_SIZE_DEFAULT;
            // Respect Top N limit
            if (query.limit() != null) {
                pageSize = Math.min(pageSize, query.limit());
            }

            // Inject partition key condition before ORDER BY / pagination
            String sql = translated.queryString();
            if (query.partitionKey() != null) {
                sql = appendPartitionKeyConditionSQL(sql);
            }

            // Apply ORDER BY before LIMIT/OFFSET
            sql = appendResultSetControl(sql, query);

            // Append LIMIT/OFFSET to the translated SQL for pagination
            String pagedSql = sql + " LIMIT " + (pageSize + 1) + " OFFSET " + offset;

            Statement.Builder stmtBuilder = Statement.newBuilder(pagedSql);

            // Bind named parameters from the translated query
            for (Map.Entry<String, Object> entry : translated.namedParameters().entrySet()) {
                String paramName = entry.getKey();
                // Strip leading @ if present — Spanner Statement expects param name without @
                if (paramName.startsWith("@")) {
                    paramName = paramName.substring(1);
                }
                bindParameter(stmtBuilder, paramName, entry.getValue());
            }

            // Bind partition key parameter if present
            if (query.partitionKey() != null) {
                stmtBuilder.bind(SpannerConstants.PARAM_PK_VAL).to(query.partitionKey());
            }

            Statement stmt = stmtBuilder.build();

            List<JsonNode> items = new ArrayList<>();
            try (ResultSet rs = databaseClient.singleUse().executeQuery(stmt)) {
                while (rs.next() && items.size() < pageSize + 1) {
                    items.add(SpannerRowMapper.toJsonNode(rs));
                }
            }

            // If we got more than pageSize items, there are more pages
            boolean hasMore = items.size() > pageSize;
            if (hasMore) {
                items = items.subList(0, pageSize);
            }
            String continuationToken = hasMore
                    ? SpannerContinuationToken.encode(offset + pageSize)
                    : null;
            logQueryDiagnostics(OperationNames.QUERY_WITH_TRANSLATION, address, items.size(), hasMore);
            return new QueryPage(items, continuationToken);
        } catch (SpannerException e) {
            throw SpannerErrorMapper.map(e, OperationNames.QUERY_WITH_TRANSLATION);
        }
    }

    @Override
    public CapabilitySet capabilities() {
        return SpannerCapabilities.CAPABILITIES;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T nativeClient(Class<T> clientType) {
        if (clientType.isInstance(spanner)) {
            return (T) spanner;
        }
        return null;
    }

    @Override
    public ProviderId providerId() {
        return ProviderId.SPANNER;
    }

    @Override
    public void close() {
        if (spanner != null) {
            spanner.close();
        }
    }

    // ── Provisioning ────────────────────────────────────────────────────────

    /**
     * No-op — the Spanner database is set at client construction time.
     */
    @Override
    public void ensureDatabase(String database) {
        LOG.debug("ensureDatabase is a no-op for Spanner (database={})", database);
    }

    @Override
    public void ensureContainer(ResourceAddress address) {
        String tableName = address.collection();
        try {
            // Check if table already exists by attempting a trivial query
            Statement checkStmt = Statement.of(
                    String.format(SpannerConstants.QUERY_TABLE_EXISTS_PROBE, tableName));
            try (ResultSet rs = databaseClient.singleUse().executeQuery(checkStmt)) {
                // If we get here, table exists
                LOG.info("Spanner table already exists: {}", tableName);
                return;
            }
        } catch (SpannerException e) {
            if (e.getErrorCode() != ErrorCode.NOT_FOUND
                    && e.getErrorCode() != ErrorCode.INVALID_ARGUMENT) {
                throw SpannerErrorMapper.map(e, "ensureContainer");
            }
            // Table doesn't exist — create it
        }

        try {
            DatabaseAdminClient adminClient = spanner.getDatabaseAdminClient();
            String ddl = String.format(SpannerConstants.DDL_CREATE_TABLE, tableName);
            adminClient.updateDatabaseDdl(
                    instanceId, databaseId, List.of(ddl), null).get();
            LOG.info("Created Spanner table: {}", tableName);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains(SpannerConstants.DDL_ERR_DUPLICATE_NAME)) {
                LOG.debug("Spanner table already exists (race): {}", tableName);
            } else if (e instanceof SpannerException se) {
                throw SpannerErrorMapper.map(se, "ensureContainer");
            } else {
                throw new RuntimeException("Failed to create Spanner table: " + tableName, e);
            }
        }
    }

    // ---- Internal helpers ----

    /**
     * Appends {@code AND partitionKey = @_pkval} (or
     * {@code WHERE partitionKey = @_pkval})
     * to a SQL statement so the query is scoped to a single partition key value.
     */
    private String appendPartitionKeyConditionSQL(String sql) {
        if (sql.toUpperCase().contains(SpannerConstants.SQL_WHERE)) {
            return sql + SpannerConstants.QUERY_PARTITION_KEY_AND;
        }
        return sql + SpannerConstants.QUERY_PARTITION_KEY_WHERE;
    }

    /**
     * Appends ORDER BY and LIMIT N clauses for result-set control.
     * ORDER BY is appended before LIMIT/OFFSET is applied in {@link #executeStatement}.
     */
    private String appendResultSetControl(String sql, QueryRequest query) {
        StringBuilder result = new StringBuilder(sql);
        if (query != null && query.orderBy() != null && !query.orderBy().isEmpty()) {
            result.append(" ORDER BY ");
            for (int i = 0; i < query.orderBy().size(); i++) {
                SortOrder so = query.orderBy().get(i);
                if (i > 0) result.append(", ");
                result.append(so.field()).append(" ").append(so.direction().name());
            }
        }
        return result.toString();
    }

    private QueryPage executeStatement(String sql, Map<String, Object> parameters,
            Integer pageSize, long offset) {
        return executeStatement(sql, parameters, pageSize, offset, null);
    }

    private QueryPage executeStatement(String sql, Map<String, Object> parameters,
            Integer pageSize, long offset, QueryRequest query) {
        int limit = pageSize != null ? pageSize : SpannerConstants.PAGE_SIZE_DEFAULT;
        // Respect Top N limit: cap the page size
        if (query != null && query.limit() != null) {
            limit = Math.min(limit, query.limit());
        }

        // Append ORDER BY before LIMIT/OFFSET
        String baseSQL = appendResultSetControl(sql, query);

        // Append LIMIT/OFFSET for pagination
        String pagedSql = baseSQL + " LIMIT " + (limit + 1) + " OFFSET " + offset;

        Statement.Builder stmtBuilder = Statement.newBuilder(pagedSql);

        if (parameters != null) {
            for (Map.Entry<String, Object> entry : parameters.entrySet()) {
                String paramName = entry.getKey().startsWith(SpannerConstants.PARAM_PREFIX)
                        ? entry.getKey().substring(1)
                        : entry.getKey();
                bindParameter(stmtBuilder, paramName, entry.getValue());
            }
        }

        List<JsonNode> items = new ArrayList<>();

        try (ResultSet rs = databaseClient.singleUse().executeQuery(stmtBuilder.build())) {
            while (rs.next() && items.size() < limit + 1) {
                items.add(SpannerRowMapper.toJsonNode(rs));
            }
        }

        boolean hasMore = items.size() > limit;
        if (hasMore) {
            items = items.subList(0, limit);
        }
        String continuationToken = hasMore
                ? SpannerContinuationToken.encode(offset + limit)
                : null;
        logQueryDiagnostics(OperationNames.QUERY, null, items.size(), hasMore);
        return new QueryPage(items, continuationToken);
    }

    private void bindParameter(Statement.Builder builder, String name, Object value) {
        if (value instanceof String s) {
            builder.bind(name).to(s);
        } else if (value instanceof Long l) {
            builder.bind(name).to(l);
        } else if (value instanceof Integer i) {
            builder.bind(name).to((long) i);
        } else if (value instanceof Boolean b) {
            builder.bind(name).to(b);
        } else if (value instanceof Double d) {
            builder.bind(name).to(d);
        } else if (value instanceof Float f) {
            builder.bind(name).to((double) f);
        } else if (value == null) {
            builder.bind(name).to((String) null);
        } else {
            // Fallback: convert to string
            builder.bind(name).to(value.toString());
        }
    }

    /**
     * Logs per-item-operation diagnostics at DEBUG level.
     */
    private void logItemDiagnostics(String op, ResourceAddress address) {
        if (LOG.isDebugEnabled()) {
            String db = address != null ? address.database() : "unknown";
            String col = address != null ? address.collection() : "unknown";
            LOG.debug("{} op={} db={} col={}", SpannerConstants.DIAG_PREFIX, op, db, col);
        }
    }

    /**
     * Logs per-query diagnostics at DEBUG level.
     */
    private void logQueryDiagnostics(String op, ResourceAddress address, int itemCount, boolean hasMore) {
        if (LOG.isDebugEnabled()) {
            String db = address != null ? address.database() : "unknown";
            String col = address != null ? address.collection() : "unknown";
            LOG.debug("{} op={} db={} col={} itemCount={} hasMore={}",
                    SpannerConstants.DIAG_PREFIX, op, db, col, itemCount, hasMore);
        }
    }

    private void setMutationValue(Mutation.WriteBuilder mutation, String column, JsonNode value) {
        if (value == null || value.isNull()) {
            mutation.set(column).to((String) null);
        } else if (value.isTextual()) {
            mutation.set(column).to(value.asText());
        } else if (value.isInt() || value.isLong()) {
            mutation.set(column).to(value.asLong());
        } else if (value.isBoolean()) {
            mutation.set(column).to(value.asBoolean());
        } else if (value.isDouble() || value.isFloat()) {
            mutation.set(column).to(value.asDouble());
        } else {
            // Complex types: serialize as JSON string
            mutation.set(column).to(value.toString());
        }
    }

}

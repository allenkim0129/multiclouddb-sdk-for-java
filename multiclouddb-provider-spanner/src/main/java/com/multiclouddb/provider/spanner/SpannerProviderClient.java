// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.multiclouddb.api.CapabilitySet;
import com.multiclouddb.api.DocumentMetadata;
import com.multiclouddb.api.DocumentResult;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbError;
import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.OperationDiagnostics;
import com.multiclouddb.api.OperationNames;
import com.multiclouddb.api.OperationOptions;
import com.multiclouddb.api.PatchOperation;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.QueryPage;
import com.multiclouddb.api.QueryRequest;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.PatchNumericDomain;
import com.multiclouddb.spi.DocumentFieldValidator;
import com.multiclouddb.spi.SdkUserAgent;
import com.multiclouddb.api.SortOrder;
import com.multiclouddb.api.query.TranslatedQuery;
import com.multiclouddb.spi.MulticloudDbProviderClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.spanner.DatabaseAdminClient;
import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.DatabaseId;
import com.google.cloud.spanner.ErrorCode;
import com.google.cloud.spanner.Key;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerException;
import com.google.cloud.spanner.Type;

import java.time.Duration;
import java.util.Map;
import com.google.cloud.spanner.SpannerOptions;
import com.google.cloud.spanner.Statement;
import com.google.api.gax.rpc.FixedHeaderProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

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
 * <li>The internal {@code data} column stores the complete document envelope;
 * compatible physical columns are populated when present for existing query
 * schemas and legacy-row compatibility.</li>
 * </ul>
 */
public class SpannerProviderClient implements MulticloudDbProviderClient {

    private static final Logger LOG = LoggerFactory.getLogger(SpannerProviderClient.class);

    /**
     * Detects an {@code ORDER BY} clause already present in caller-supplied SQL
     * (e.g., a raw GoogleSQL expression passed via {@link QueryRequest#expression()}
     * or a {@link TranslatedQuery} that already includes ordering).
     * Used by {@link #appendResultSetControl} to avoid emitting a duplicate
     * {@code ORDER BY} clause.
     * <p>
     * Detection is performed after {@link #stripStringLiterals(String)} so that
     * an {@code ORDER BY} substring inside a quoted literal (e.g.
     * {@code WHERE note = 'please ORDER BY date'}) does not false-positive.
     */
    private static final Pattern ORDER_BY_CLAUSE =
            Pattern.compile("\\bORDER\\s+BY\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Matches aggregate functions / GROUP BY clauses that GoogleSQL forbids
     * combining with a default {@code ORDER BY partitionKey, sortKey} (the
     * non-aggregated column references would be illegal). Covers COUNT, SUM,
     * MIN, MAX, AVG (the GoogleSQL set that mirrors the Cosmos peer).
     */
    private static final Pattern AGGREGATE_PATTERN =
            Pattern.compile("\\b(COUNT|SUM|MIN|MAX|AVG)\\s*\\(|\\bGROUP\\s+BY\\b",
                    Pattern.CASE_INSENSITIVE);

    /**
     * Matches GoogleSQL string-literal forms so {@link #stripStringLiterals}
     * can mask their contents before keyword detection. Covers, in priority
     * order:
     * <ol>
     *   <li>triple-double-quoted {@code """..."""} (allows lone {@code "} and
     *       {@code ""} inside)</li>
     *   <li>triple-single-quoted {@code '''...'''} (allows lone {@code '} and
     *       {@code ''} inside)</li>
     *   <li>double-quoted {@code "..."} with backslash escapes and {@code ""}
     *       escape</li>
     *   <li>single-quoted {@code '...'} with backslash escapes and {@code ''}
     *       escape</li>
     * </ol>
     * All four forms accept an optional {@code r}/{@code R} raw-string prefix;
     * for masking purposes the raw vs non-raw distinction does not matter
     * (we are only removing the literal text, not interpreting escapes).
     * <p>
     * Triple-quoted alternatives must come before the single-character forms;
     * otherwise the engine would match the first three quotes of
     * {@code '''abc'''} as an empty single literal followed by garbage.
     * The {@code (?s)} DOTALL flag lets literals span newlines.
     */
    private static final Pattern STRING_LITERAL_PATTERN =
            Pattern.compile(
                    "(?s)"
                            + "[rR]?\"\"\"(?:\\\\.|\"(?!\"\")|[^\"\\\\])*\"\"\""
                            + "|[rR]?'''(?:\\\\.|'(?!'')|[^'\\\\])*'''"
                            + "|[rR]?\"(?:\\\\.|\"\"|[^\"\\\\])*\""
                            + "|[rR]?'(?:\\\\.|''|[^'\\\\])*'");

    /**
     * Replaces all GoogleSQL string literals in a SQL fragment with empty
     * placeholders so that keyword detection (ORDER BY / aggregate) cannot be
     * confused by literal content. Covers single-, double-, and triple-quoted
     * forms, with the optional {@code r}/{@code R} raw prefix.
     * <p>
     * Package-private for unit testing.
     */
    static String stripStringLiterals(String sql) {
        return sql == null ? null : STRING_LITERAL_PATTERN.matcher(sql).replaceAll("''");
    }

    /**
     * Returns {@code true} if {@code sql} already contains an {@code ORDER BY}
     * clause (case-insensitive), after stripping string literals so that an
     * {@code ORDER BY} substring inside a quoted literal does not false-positive.
     * Package-private for unit testing.
     */
    static boolean hasOrderByClause(String sql) {
        return sql != null && ORDER_BY_CLAUSE.matcher(stripStringLiterals(sql)).find();
    }

    /**
     * Returns {@code true} if {@code sql} contains an aggregate function call
     * ({@code COUNT|SUM|MIN|MAX|AVG}) or a {@code GROUP BY} clause, after
     * stripping string literals. Used to suppress the default
     * {@code ORDER BY partitionKey, sortKey} tiebreaker for aggregate queries
     * (GoogleSQL rejects ORDER BY columns that aren't aggregated or in
     * GROUP BY). Package-private for unit testing.
     */
    static boolean containsAggregate(String sql) {
        return sql != null && AGGREGATE_PATTERN.matcher(stripStringLiterals(sql)).find();
    }

    private final Spanner spanner;
    private final DatabaseClient databaseClient;
    private final MulticloudDbClientConfig config;
    private final String projectId;
    private final String instanceId;
    private final String databaseId;
    private final SpannerChangeFeedReader changeFeedReader;
    private final boolean emulatorMode;
    /**
     * Physical column layout per table, keyed by case-folded table name (Spanner
     * identifiers are case-insensitive). Populated lazily by
     * {@link #tableColumns(String, String)}, which never caches an empty
     * (table-does-not-exist) result, and cleared for a table by
     * {@link #invalidateTableColumns(String)} after schema-changing DDL.
     */
    private final Map<String, Map<String, TableColumn>> tableColumns = new ConcurrentHashMap<>();
    private volatile boolean closed = false;

    private record TableColumn(String name, Type type) {
    }

    /**
     * Constructs a Cloud Spanner provider client from the supplied configuration.
     * <p>
     * If {@code connection.emulatorHost} is set (e.g. {@code localhost:9010}), the
     * Spanner emulator is targeted instead of the live Cloud Spanner service.
     * Application Default Credentials are used when connecting to the live service;
     * no explicit credential config is needed when running on GCP with a service account.
     *
     * @param config client configuration carrying connection, auth, and options
     * @throws IllegalArgumentException if {@code connection.instanceId} or
     *         {@code connection.databaseId} is missing or blank
     */
    public SpannerProviderClient(MulticloudDbClientConfig config) {
        this.config = config;
        this.projectId = config.connection().getOrDefault(SpannerConstants.CONFIG_PROJECT_ID, SpannerConstants.CONFIG_PROJECT_ID_DEFAULT);
        this.instanceId = config.connection().get(SpannerConstants.CONFIG_INSTANCE_ID);
        this.databaseId = config.connection().get(SpannerConstants.CONFIG_DATABASE_ID);
        String emulatorHost = config.connection().get(SpannerConstants.CONFIG_EMULATOR_HOST);
        this.emulatorMode = emulatorHost != null && !emulatorHost.isBlank();

        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException(SpannerConstants.ERR_INSTANCE_ID_REQUIRED);
        }
        if (databaseId == null || databaseId.isBlank()) {
            throw new IllegalArgumentException(SpannerConstants.ERR_DATABASE_ID_REQUIRED);
        }

        SpannerOptions.Builder builder = SpannerOptions.newBuilder()
                .setProjectId(projectId)
                .setHeaderProvider(FixedHeaderProvider.create(
                        "user-agent", SdkUserAgent.userAgent(config)));

        if (emulatorMode) {
            builder.setEmulatorHost(emulatorHost);
        }

        this.spanner = builder.build().getService();
        this.databaseClient = spanner.getDatabaseClient(
                DatabaseId.of(projectId, instanceId, databaseId));
        this.changeFeedReader = SpannerChangeFeedReader.create(
                ProviderId.SPANNER, this.databaseClient, config);
        LOG.info("Spanner client created for project={}, instance={}, database={}, emulator={}",
                projectId, instanceId, databaseId, emulatorHost != null ? emulatorHost : "none");
    }

    /**
     * Package-private constructor for unit tests that need a provider client
     * without opening a Spanner session pool.
     */
    SpannerProviderClient(Spanner spanner, DatabaseClient databaseClient) {
        this.spanner = spanner;
        this.databaseClient = databaseClient;
        this.config = MulticloudDbClientConfig.builder().provider(ProviderId.SPANNER).build();
        this.projectId = "test-project";
        this.instanceId = "test-instance";
        this.databaseId = "test-database";
        this.changeFeedReader = null;
        this.emulatorMode = true;
    }

    /**
     * Inserts a new row into the Spanner table that corresponds to
     * {@code address.collection()}.
     * <p>
     * Uses a Spanner {@code INSERT} mutation. Two primary key columns are always
     * written first:
     * <ul>
     *   <li>{@code partitionKey} — set to {@code key.partitionKey()}.</li>
     *   <li>{@code sortKey} — set to {@code key.sortKey()} if present, otherwise
     *       {@code key.partitionKey()}.</li>
     * </ul>
     * All document fields are written to the {@code data} document envelope;
     * compatible physical columns are populated when they exist. If the row
     * already exists, the mutation fails with
     * {@link com.multiclouddb.api.MulticloudDbErrorCategory#CONFLICT}.
     *
     * @param address  the logical database + collection; the collection maps directly to
     *                 a Spanner table name
     * @param key      the document key
     * @param document the document payload; map entries become column values
     * @param options  operation options (currently unused by this provider)
     * @throws com.multiclouddb.api.MulticloudDbException on any Spanner error
     */
    @Override
    public void create(ResourceAddress address, MulticloudDbKey key, Map<String, Object> document, OperationOptions options) {
        checkOpen(OperationNames.CREATE);
        DocumentFieldValidator.validateWritableDocument(document, ProviderId.SPANNER, OperationNames.CREATE);
        try {
            String table = address.collection();
            Mutation.WriteBuilder mutation = Mutation.newInsertBuilder(table)
                    .set(SpannerConstants.FIELD_PARTITION_KEY).to(key.partitionKey())
                    .set(SpannerConstants.FIELD_SORT_KEY).to(key.sortKey() != null ? key.sortKey() : key.partitionKey());

            writeFullDocument(mutation, document, table, OperationNames.CREATE);
            databaseClient.write(List.of(mutation.build()));
            logItemDiagnostics(OperationNames.CREATE, address);
        } catch (SpannerException e) {
            throw SpannerErrorMapper.map(e, OperationNames.CREATE);
        }
    }

    /**
     * Replaces an existing row with the supplied document.
     * <p>
     * A Spanner {@code UPDATE} mutation leaves omitted physical columns intact,
     * but the newly written {@link SpannerConstants#FIELD_DATA} envelope is the
     * authoritative portable document. The row mapper and portable-expression
     * translator therefore hide stale physical columns from current SDK rows,
     * matching Cosmos and DynamoDB replacement semantics.
     *
     * @param address  the logical database + collection
     * @param key      the document key identifying the row to update
     * @param document the complete replacement document
     * @param options  operation options (currently unused by this provider)
     * @throws com.multiclouddb.api.MulticloudDbException category {@code NOT_FOUND} if
     *         the row does not exist, or any other Spanner error
     */
    @Override
    public void update(ResourceAddress address, MulticloudDbKey key, Map<String, Object> document, OperationOptions options) {
        checkOpen(OperationNames.UPDATE);
        DocumentFieldValidator.validateWritableDocument(document, ProviderId.SPANNER, OperationNames.UPDATE);
        try {
            String table = address.collection();
            Mutation.WriteBuilder mutation = Mutation.newUpdateBuilder(table)
                    .set(SpannerConstants.FIELD_PARTITION_KEY).to(key.partitionKey())
                    .set(SpannerConstants.FIELD_SORT_KEY).to(
                            key.sortKey() != null ? key.sortKey() : key.partitionKey());

            writeFullDocument(mutation, document, table, OperationNames.UPDATE);
            databaseClient.write(List.of(mutation.build()));

            logItemDiagnostics(OperationNames.UPDATE, address);
        } catch (SpannerException e) {
            throw SpannerErrorMapper.map(e, OperationNames.UPDATE);
        }
    }

    /**
     * Creates or replaces a row in Spanner (INSERT_OR_UPDATE mutation / upsert semantics).
     * <p>
     * Uses a Spanner {@code INSERT_OR_UPDATE} mutation paired with a fresh
     * {@link SpannerConstants#FIELD_DATA} document envelope. On read,
     * {@link SpannerRowMapper} treats that envelope as authoritative, so any
     * field from a prior write that is not in the new document is invisible to
     * the SDK — preserving the "full document replacement" semantics that
     * {@code upsert} provides on schemaless stores (Cosmos, DynamoDB).
     * <p>
     * <b>Why INSERT_OR_UPDATE rather than REPLACE.</b> A Spanner {@code REPLACE}
     * mutation on an existing row is internally a delete-then-insert and surfaces in
     * change streams as {@code mod_type=INSERT} (i.e.,
     * {@link com.multiclouddb.api.changefeed.ChangeType#CREATE}). That breaks
     * cross-provider change-feed parity: Cosmos AVAD and DynamoDB Streams both
     * report a second upsert of an existing key as {@code UPDATE} / {@code MODIFY},
     * which the SDK maps to {@link com.multiclouddb.api.changefeed.ChangeType#UPDATE}.
     * {@code INSERT_OR_UPDATE} preserves the same observable upsert semantics for
     * reads (via {@code FIELD_DATA} filtering) while emitting {@code mod_type=INSERT}
     * on first write and {@code mod_type=UPDATE} on subsequent writes of the same
     * key, matching the conformance contract asserted by
     * {@code ChangeFeedConformanceTest.updateEventSurfacesAfterUpsert}.
     *
     * @param address  the logical database + collection
     * @param key      the document key
     * @param document the document payload
     * @param options  operation options (currently unused by this provider)
     * @throws com.multiclouddb.api.MulticloudDbException on any Spanner error
     */
    @Override
    public void upsert(ResourceAddress address, MulticloudDbKey key, Map<String, Object> document, OperationOptions options) {
        checkOpen(OperationNames.UPSERT);
        DocumentFieldValidator.validateWritableDocument(document, ProviderId.SPANNER, OperationNames.UPSERT);
        try {
            String table = address.collection();
            Mutation.WriteBuilder mutation = Mutation.newInsertOrUpdateBuilder(table)
                    .set(SpannerConstants.FIELD_PARTITION_KEY).to(key.partitionKey())
                    .set(SpannerConstants.FIELD_SORT_KEY).to(key.sortKey() != null ? key.sortKey() : key.partitionKey());

            writeFullDocument(mutation, document, table, OperationNames.UPSERT);
            databaseClient.write(List.of(mutation.build()));
            logItemDiagnostics(OperationNames.UPSERT, address);
        } catch (SpannerException e) {
            throw SpannerErrorMapper.map(e, OperationNames.UPSERT);
        }
    }

    /** Applies field-level modifications atomically in a retryable Spanner transaction. */
    @Override
    public void patch(ResourceAddress address, MulticloudDbKey key, List<PatchOperation> operations,
            OperationOptions options) {
        checkOpen(OperationNames.PATCH);
        validatePatchRequest(operations);
        try {
            String table = address.collection();
            String pk = key.partitionKey();
            String sk = key.sortKey() != null ? key.sortKey() : key.partitionKey();
            Map<String, TableColumn> physicalColumns = tableColumns(table, OperationNames.PATCH);

            databaseClient.readWriteTransaction().run(txn -> {
                com.google.cloud.spanner.Struct envelopeRow = txn.readRow(
                        table, Key.of(pk, sk), List.of(SpannerConstants.FIELD_DATA));
                if (envelopeRow == null) {
                    // Typed SpannerException required: a plain exception would be
                    // wrapped opaquely by the transaction runner and degraded to
                    // PROVIDER_ERROR by SpannerErrorMapper. Same rationale as update().
                    throw com.google.cloud.spanner.SpannerExceptionFactory.newSpannerException(
                            ErrorCode.NOT_FOUND,
                            "Spanner row not found for patch: partitionKey=" + pk + ", sortKey=" + sk);
                }

                Map<String, Object> fields = envelopeRow.isNull(0)
                        ? null
                        : SpannerRowMapper.parseDocumentEnvelope(envelopeRow.getString(0));
                if (fields == null) {
                    // Legacy rows carry only a field-name array (or no metadata), so
                    // reconstructing their visible document still requires physical
                    // columns. New envelope rows avoid this full-row fallback.
                    try (ResultSet rs = txn.executeQuery(
                            Statement.newBuilder(String.format(SpannerConstants.QUERY_READ_BY_KEY, table))
                                    .bind(SpannerConstants.FIELD_PARTITION_KEY).to(pk)
                                    .bind(SpannerConstants.FIELD_SORT_KEY).to(sk)
                                    .build())) {
                        if (!rs.next()) {
                            throw com.google.cloud.spanner.SpannerExceptionFactory.newSpannerException(
                                    ErrorCode.NOT_FOUND,
                                    "Spanner row not found for patch: partitionKey=" + pk
                                            + ", sortKey=" + sk);
                        }
                        fields = SpannerRowMapper.toMap(rs);
                    }
                }
                fields = documentFields(fields);

                Mutation.WriteBuilder mutation = Mutation.newUpdateBuilder(table)
                        .set(SpannerConstants.FIELD_PARTITION_KEY).to(pk)
                        .set(SpannerConstants.FIELD_SORT_KEY).to(sk);

                for (PatchOperation op : operations) {
                    String field = op.rootField();
                    TableColumn physicalColumn = physicalColumns.get(field.toLowerCase(Locale.ROOT));
                    if (op.requiresExistingPath() && !fields.containsKey(field)) {
                        throw com.google.cloud.spanner.SpannerExceptionFactory.newSpannerException(
                                ErrorCode.NOT_FOUND,
                                "Patch target field does not exist: '" + field + "' (partitionKey="
                                        + pk + ", sortKey=" + sk + ")");
                    }
                    switch (op.type()) {
                        case SET, REPLACE -> {
                            Object value = op.value();
                            fields.put(field, value);
                            writePhysicalPatchValue(mutation, physicalColumn, value);
                        }
                        case REMOVE -> {
                            fields.remove(field);
                            writePhysicalPatchValue(mutation, physicalColumn, null);
                        }
                        case INCREMENT -> {
                            Object existing = fields.get(field);
                            if (!(existing instanceof Number base)) {
                                throw com.google.cloud.spanner.SpannerExceptionFactory.newSpannerException(
                                        ErrorCode.INVALID_ARGUMENT,
                                        "INCREMENT target '" + field + "' is not numeric: "
                                                + (existing == null ? "null" : existing.getClass().getName()));
                            }
                            Object incremented = addDelta(base, (Number) op.value());
                            fields.put(field, incremented);
                            writePhysicalPatchValue(mutation, physicalColumn, incremented);
                        }
                    }
                }

                mutation.set(SpannerConstants.FIELD_DATA).to(
                        serialiseDocument(documentFields(fields), OperationNames.PATCH));

                txn.buffer(mutation.build());
                return null;
            });

            logItemDiagnostics(OperationNames.PATCH, address);
        } catch (SpannerException e) {
            throw SpannerErrorMapper.map(e, OperationNames.PATCH);
        }
    }

    /** Adds an INCREMENT delta using the shared portable result-domain rules. */
    private static Object addDelta(Number base, Number delta) {
        try {
            return PatchNumericDomain.add(base, delta);
        } catch (IllegalArgumentException e) {
            throw com.google.cloud.spanner.SpannerExceptionFactory.newSpannerException(
                    ErrorCode.INVALID_ARGUMENT,
                    e.getMessage());
        }
    }

    /**
     * Mirrors an envelope value only when its runtime kind can be represented
     * by the physical column. An incompatible replacement explicitly clears
     * the mirror so a stale typed value cannot outlive the authoritative
     * envelope or affect non-SDK consumers of that column.
     */
    private void writePhysicalPatchValue(Mutation.WriteBuilder mutation, TableColumn column,
            Object value) {
        if (column == null || !supportsPhysicalMirror(column.type())) {
            return;
        }
        if (value == null || !isCompatibleWithColumn(value, column.type())) {
            setTypedNull(mutation, column.name(), column.type());
            return;
        }
        setCompatiblePhysicalValue(mutation, column.name(), value, column.type());
    }

    static boolean isCompatibleWithColumn(Object value, Type columnType) {
        return switch (columnType.getCode()) {
            case INT64 -> value instanceof Long || value instanceof Integer
                    || value instanceof Short || value instanceof Byte;
            case FLOAT64 -> value instanceof Number number
                    && Double.isFinite(number.doubleValue());
            case BOOL -> value instanceof Boolean;
            case STRING -> value instanceof String;
            default -> false;
        };
    }

    private static boolean supportsPhysicalMirror(Type columnType) {
        return switch (columnType.getCode()) {
            case INT64, FLOAT64, BOOL, STRING -> true;
            default -> false;
        };
    }

    /**
     * Writes a compatible runtime value to its matching physical Spanner type.
     * <p>
     * Callers must have already established compatibility with
     * {@link #isCompatibleWithColumn(Object, Type)}.
     */
    private static void setCompatiblePhysicalValue(Mutation.WriteBuilder mutation, String column,
            Object value, Type columnType) {
        switch (columnType.getCode()) {
            case INT64 -> mutation.set(column).to(((Number) value).longValue());
            case FLOAT64 -> mutation.set(column).to(((Number) value).doubleValue());
            case BOOL -> mutation.set(column).to((Boolean) value);
            case STRING -> {
                String text = (String) value;
                mutation.set(column).to(!text.isEmpty() && text.charAt(0) == '\u0001'
                        ? '\u0001' + text
                        : text);
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported physical mirror type: " + columnType.getCode());
        }
    }

    private Map<String, TableColumn> tableColumns(String table, String op) {
        String cacheKey = tableCacheKey(table);
        Map<String, TableColumn> cached = tableColumns.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        try {
            Map<String, TableColumn> loaded = loadTableColumns(table);
            if (!loaded.isEmpty()) {
                // Never cache a negative result. loadTableColumns() returns an
                // EMPTY map (not an error) for a table that does not exist yet,
                // and create() resolves columns through writeFullDocument BEFORE
                // the write itself fails with NOT_FOUND. Caching that empty map
                // would poison the client for its whole lifetime: a later
                // ensureContainer() creates the table, but every physical mirror
                // column would still be skipped by writePhysicalPatchValue()
                // (column == null), silently breaking the documented
                // compatibility mirrors for non-SDK consumers while SDK reads
                // stay correct through the envelope — a failure with no symptom.
                tableColumns.put(cacheKey, loaded);
            }
            return loaded;
        } catch (SpannerException | MulticloudDbException e) {
            // Already portable (or mapped by the caller's SpannerException handler).
            throw e;
        } catch (RuntimeException e) {
            // Nothing raw may cross the portable surface: an IllegalStateException
            // or NPE from the underlying client would otherwise bypass every
            // `catch (SpannerException)` handler in this class.
            throw new MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.PROVIDER_ERROR,
                    "Failed to load Spanner column metadata for table '" + table + "': "
                            + e.getMessage(),
                    ProviderId.SPANNER, op, false, null));
        }
    }

    /**
     * Case-folds a table name for the metadata cache. The lookup query matches
     * on {@code LOWER(TABLE_NAME) = LOWER(@_tablename)} because Spanner
     * identifiers are case-insensitive, so {@code Todos} and {@code todos} must
     * resolve to one cache entry rather than two independently loaded copies.
     */
    private static String tableCacheKey(String table) {
        return table == null ? "" : table.toLowerCase(Locale.ROOT);
    }

    /**
     * Drops any cached physical-column layout for {@code table}, forcing the
     * next write to re-read {@code INFORMATION_SCHEMA}. Called after DDL that
     * can change the layout ({@link #ensureContainer(ResourceAddress)}), since
     * an entry loaded before a {@code CREATE TABLE} / {@code ALTER TABLE ... ADD
     * COLUMN} would otherwise keep mirroring an out-of-date column set.
     */
    private void invalidateTableColumns(String table) {
        tableColumns.remove(tableCacheKey(table));
    }

    /**
     * Loads the physical column layout of {@code table} from
     * {@code INFORMATION_SCHEMA.COLUMNS}.
     * <p>
     * The result set is consumed with an ordinary {@code while (rs.next())}
     * loop. The previous implementation probed {@code SELECT * FROM <table>
     * LIMIT 0} and read {@link ResultSet#getType()}, but both {@code getType()}
     * and {@code getMetadata()} are guarded by
     * {@code Preconditions.checkState(..., "next() call required")} in
     * {@code GrpcResultSet} — with {@code LIMIT 0} no row is ever consumed, so
     * every write path threw {@link IllegalStateException}.
     * <p>
     * Columns whose declared type has no {@link Type} counterpart (arrays,
     * protos, enums, tokenlists, structs, …) are skipped: the map exists solely
     * to decide physical mirroring, and an unmappable type is never mirrored,
     * so omitting it is behaviourally identical to declaring it unmirrorable.
     * <p>
     * The map is <em>keyed</em> by the case-folded column name because Spanner
     * column identifiers are case-insensitive (the same reason
     * {@code PatchValidator.overlaps} compares segments with
     * {@code equalsIgnoreCase}); {@link TableColumn#name()} keeps the
     * {@code INFORMATION_SCHEMA} casing so mutations address the column exactly
     * as declared. Keying case-sensitively made a patch on {@code /status} miss
     * a column declared {@code Status} and silently leave a stale mirror behind.
     *
     * @return an unmodifiable map from case-folded column name to column, empty
     *         when the table does not exist (callers must not cache that)
     */
    private Map<String, TableColumn> loadTableColumns(String table) {
        Map<String, TableColumn> columns = new LinkedHashMap<>();
        Statement statement = Statement.newBuilder(SpannerConstants.QUERY_TABLE_COLUMNS)
                .bind(SpannerConstants.PARAM_TABLE_NAME).to(table)
                .build();
        try (ResultSet rs = databaseClient.singleUse().executeQuery(statement)) {
            while (rs.next()) {
                String name = rs.getString(SpannerConstants.COLUMN_METADATA_NAME);
                Type type = parseSpannerType(rs.getString(SpannerConstants.COLUMN_METADATA_SPANNER_TYPE));
                if (name != null && type != null) {
                    columns.put(name.toLowerCase(Locale.ROOT), new TableColumn(name, type));
                }
            }
        }
        return Collections.unmodifiableMap(columns);
    }

    /**
     * Maps an {@code INFORMATION_SCHEMA.SPANNER_TYPE} string (e.g.
     * {@code STRING(MAX)}, {@code INT64}, {@code ARRAY<INT64>}) onto a
     * {@link Type}. {@code Type.fromProto} is package-private in
     * {@code com.google.cloud.spanner}, so the mapping is explicit here.
     *
     * @return the matching {@link Type}, or {@code null} for a type this
     *         provider cannot represent (the caller then skips the column)
     */
    static Type parseSpannerType(String spannerType) {
        if (spannerType == null) {
            return null;
        }
        String declared = spannerType.trim();
        if (declared.regionMatches(true, 0, "ARRAY<", 0, 6) && declared.endsWith(">")) {
            Type element = parseSpannerType(declared.substring(6, declared.length() - 1));
            return element == null ? null : Type.array(element);
        }
        int paren = declared.indexOf('(');
        if (paren >= 0) {
            declared = declared.substring(0, paren);
        }
        return switch (declared.trim().toUpperCase(Locale.ROOT)) {
            case "BOOL" -> Type.bool();
            case "INT64" -> Type.int64();
            case "FLOAT32" -> Type.float32();
            case "FLOAT64" -> Type.float64();
            case "NUMERIC" -> Type.numeric();
            case "STRING" -> Type.string();
            case "JSON" -> Type.json();
            case "BYTES" -> Type.bytes();
            case "TIMESTAMP" -> Type.timestamp();
            case "DATE" -> Type.date();
            default -> null;
        };
    }

    private static Map<String, Object> documentFields(Map<String, Object> document) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (document == null) {
            return fields;
        }
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            String name = entry.getKey();
            if (SpannerConstants.FIELD_PARTITION_KEY.equals(name)
                    || SpannerConstants.FIELD_SORT_KEY.equals(name)
                    || (name != null && SpannerConstants.FIELD_DATA.equalsIgnoreCase(name))) {
                continue;
            }
            fields.put(name, documentValue(entry.getValue()));
        }
        return fields;
    }

    /**
     * Normalises a runtime value for the {@code data} envelope.
     * <p>
     * {@code Map} / {@code Collection} values are handed to Jackson as-is: the
     * envelope is serialised once, by {@link #serialiseDocument}, and any
     * failure surfaces there. This method used to serialise them here purely as
     * a serialisability probe and throw the result away — paying a second full
     * serialisation on every write — and, worse, silently substituted
     * {@code value.toString()} when the probe failed, storing a Java debug
     * string such as {@code {a=1}} in place of the caller's document. An
     * unserialisable nested value now fails the operation with
     * {@code INVALID_REQUEST} instead of corrupting the stored data.
     * <p>
     * Every other non-scalar type keeps the historical {@code toString()}
     * fallback so common JDK values (e.g. {@code java.time.Instant}) continue to
     * round-trip through the STRING mirrors.
     */
    private static Object documentValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> || value instanceof Collection<?>) {
            return value;
        }
        return value.toString();
    }

    private static String serialiseDocument(Map<String, Object> document, String op) {
        try {
            return JSON_MAPPER.writeValueAsString(
                    Map.of(SpannerConstants.FIELD_DATA_DOCUMENT, document));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // The only serialisable input here is the caller's own document, so
            // a Jackson failure means the request carried a value the SDK cannot
            // store (e.g. a Map/Collection holding a type with no serialiser, or
            // a self-referential structure). That is a caller-fixable INVALID_REQUEST,
            // not an opaque PROVIDER_ERROR — and it must be reported rather than
            // silently degraded to a toString() of the offending value.
            throw new MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.INVALID_REQUEST,
                    "Document contains a value that cannot be serialised to the Spanner '"
                            + SpannerConstants.FIELD_DATA + "' envelope: " + e.getMessage(),
                    ProviderId.SPANNER, op, false, null), e);
        }
    }

    /**
     * Writes document fields into a mutation and stamps the {@link
     * SpannerConstants#FIELD_DATA} column with the complete document envelope.
     * Suitable for full-document write paths ({@code create} / {@code update}
     * / {@code upsert}). The envelope, rather than untouched physical columns,
     * defines the document visible to SDK reads and portable queries. Every
     * supported physical mirror is also written or cleared so a replacement
     * never leaves a stale value after a field is omitted or changes runtime
     * type.
     */
    private void writeFullDocument(Mutation.WriteBuilder mutation, Map<String, Object> document,
            String table, String op) {
        Map<String, Object> documentFields = documentFields(document);
        for (TableColumn column : tableColumns(table, op).values()) {
            if (isDocumentMirrorColumn(column)) {
                writePhysicalPatchValue(mutation, column, mirrorValue(documentFields, column.name()));
            }
        }
        mutation.set(SpannerConstants.FIELD_DATA).to(serialiseDocument(documentFields, op));
    }

    /**
     * Resolves the document value that mirrors {@code columnName}.
     * <p>
     * {@code column.name()} carries the {@code INFORMATION_SCHEMA} casing while
     * {@code documentFields} is keyed by the caller's casing, and Spanner column
     * identifiers are case-insensitive. A case-sensitive lookup therefore
     * resolved a column declared {@code Status} against a document field
     * {@code status} to {@code null} and cleared the mirror to a typed NULL —
     * defeating the very purpose of the compatibility mirror. An exact match
     * still wins, so a document that (legally) carries both {@code Status} and
     * {@code status} resolves deterministically rather than by map iteration
     * order.
     *
     * @return the mirrored value, or {@code null} when the document has no
     *         field for that column (which correctly clears the mirror)
     */
    private static Object mirrorValue(Map<String, Object> documentFields, String columnName) {
        if (documentFields.containsKey(columnName)) {
            return documentFields.get(columnName);
        }
        for (Map.Entry<String, Object> entry : documentFields.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(columnName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Reports whether a physical column participates in document mirroring.
     * <p>
     * All three exclusions compare case-insensitively because Spanner column
     * identifiers are. A table declaring {@code PartitionKey} was previously
     * treated as a mirror column by the {@code .equals()} comparisons, so the
     * same column was set twice in one mutation — once as the key and once as a
     * mirror — and Spanner rejected the write with {@code Duplicate column name}.
     */
    private static boolean isDocumentMirrorColumn(TableColumn column) {
        return !SpannerConstants.FIELD_PARTITION_KEY.equalsIgnoreCase(column.name())
                && !SpannerConstants.FIELD_SORT_KEY.equalsIgnoreCase(column.name())
                && !SpannerConstants.FIELD_DATA.equalsIgnoreCase(column.name());
    }

    /**
     * Reads a single row from Spanner by its composite primary key.
     * <p>
     * Executes a GoogleSQL query of the form
     * {@code SELECT * FROM <table> WHERE partitionKey = @partitionKey AND sortKey = @sortKey}.
     * Uses a {@code singleUse} read-only transaction (no session overhead).
     *
     * @param address the logical database + collection
     * @param key     the document key
     * @param options operation options (currently unused by this provider)
     * @return the row as a {@code Map<String, Object>}, or {@code null} if not found
     * @throws com.multiclouddb.api.MulticloudDbException on any Spanner error
     */
    @Override
    public DocumentResult read(ResourceAddress address, MulticloudDbKey key, OperationOptions options) {
        checkOpen(OperationNames.READ);
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
                        throw new MulticloudDbException(new MulticloudDbError(
                                MulticloudDbErrorCategory.PROVIDER_ERROR,
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

    /**
     * Deletes a row from Spanner by its composite primary key.
     * <p>
     * Idempotent: uses a Spanner {@code Mutation.delete} which silently no-ops
     * when no row matches the given key. This matches the LCD cross-provider
     * contract on {@link com.multiclouddb.api.MulticloudDbClient#delete}, where
     * DynamoDB {@code DeleteItem} naturally no-ops and Cosmos swallows 404.
     *
     * @param address the logical database + collection
     * @param key     the document key identifying the row to delete
     * @param options operation options (currently unused by this provider)
     * @throws com.multiclouddb.api.MulticloudDbException on any Spanner error
     */
    @Override
    public void delete(ResourceAddress address, MulticloudDbKey key, OperationOptions options) {
        checkOpen(OperationNames.DELETE);
        try {
            String table = address.collection();
            String partitionKeyVal = key.partitionKey();
            String sortKeyVal = key.sortKey() != null ? key.sortKey() : key.partitionKey();

            databaseClient.write(List.of(
                    Mutation.delete(table, Key.of(partitionKeyVal, sortKeyVal))));
            logItemDiagnostics(OperationNames.DELETE, address);
        } catch (SpannerException e) {
            throw SpannerErrorMapper.map(e, OperationNames.DELETE);
        }
    }

    /**
     * Executes a query and returns a single page of results using LIMIT/OFFSET pagination.
     * <p>
     * Query routing logic (evaluated in order):
     * <ol>
     *   <li><b>Native GoogleSQL passthrough</b> — if {@link QueryRequest#nativeExpression()}
     *       is set, it is executed as-is.</li>
     *   <li><b>Full scan</b> — if expression is null/blank or equals the Cosmos-style
     *       {@code "SELECT * FROM c"} sentinel, a {@code SELECT * FROM <table>} is
     *       executed.</li>
     *   <li><b>Legacy expression</b> — the expression is passed through to
     *       {@link #executeStatement} as-is (backward-compatible path).</li>
     * </ol>
     * If {@link QueryRequest#partitionKey()} is set, a {@code WHERE partitionKey = @_pkval}
     * (or {@code AND partitionKey = @_pkval}) condition is appended automatically.
     * <p>
     * Pagination uses integer OFFSET encoding via {@link SpannerContinuationToken}.
     * Note: OFFSET-based pagination is not ideal for large datasets — it rescans all
     * preceding rows on each call.
     *
     * @param address the logical database + collection
     * @param query   query request containing expression, parameters, page size, and
     *                optional continuation token
     * @param options operation options (currently unused by this provider)
     * @return a page of results; {@link QueryPage#continuationToken()} is non-null when
     *         more pages are available
     * @throws com.multiclouddb.api.MulticloudDbException on any Spanner error
     */
    @Override
    public QueryPage query(ResourceAddress address, QueryRequest query, OperationOptions options) {
        checkOpen(OperationNames.QUERY);
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
                return executeStatement(stmt, params, query.maxPageSize(), offset);
            }

            // Expression-based query or full scan
            String expression = query.expression();
            if (expression == null || expression.isBlank()
                    || expression.trim().equalsIgnoreCase(SpannerConstants.QUERY_SELECT_ALL_COSMOS)) {
                // SDK-generated scans carry the portable row alias so ORDER BY can
                // read the authoritative document envelope (dynamic top-level
                // fields have no physical column to sort by).
                if (query.partitionKey() != null) {
                    // Scope scan to items with matching partitionKey
                    return executeStatement(
                            aliasedScan(table) + SpannerConstants.QUERY_PARTITION_KEY_WHERE,
                            Map.of(SpannerConstants.PARAM_PK_VAL, query.partitionKey()),
                            query.maxPageSize(), offset, query, true);
                }
                // Full scan
                return executeStatement(aliasedScan(table), null, query.maxPageSize(), offset,
                        query, true);
            }

            // Legacy: pass through as-is. The caller owns provider-native SQL and it
            // exposes no portable row alias, so ORDER BY stays on bare columns.
            if (query.partitionKey() != null) {
                String stmt = appendPartitionKeyConditionSQL(expression);
                Map<String, Object> combined = new LinkedHashMap<>();
                if (query.parameters() != null) {
                    combined.putAll(query.parameters());
                }
                combined.put(SpannerConstants.PARAM_PK_VAL, query.partitionKey());
                return executeStatement(stmt, combined, query.maxPageSize(), offset, query);
            }
            return executeStatement(expression, query.parameters(), query.maxPageSize(), offset, query);
        } catch (SpannerException e) {
            throw SpannerErrorMapper.map(e, OperationNames.QUERY);
        }
    }

    /**
     * Executes a pre-translated portable query using GoogleSQL and returns a single
     * page of results.
     * <p>
     * Called by {@link com.multiclouddb.api.internal.DefaultMulticloudDbClient} after
     * the portable expression has been parsed, validated, and translated into GoogleSQL
     * by {@link SpannerExpressionTranslator}. Named parameters from
     * {@link TranslatedQuery#namedParameters()} are bound; leading {@code @} prefixes
     * are stripped because Spanner's {@link Statement.Builder} expects bare names.
     * <p>
     * {@code LIMIT (pageSize + 1) OFFSET offset} is appended for pagination; if the
     * result set contains more than {@code pageSize} rows, a continuation token is
     * encoded and returned.
     *
     * @param address    the logical database + collection
     * @param translated the GoogleSQL statement and named parameters produced by the
     *                   expression translator
     * @param query      the original query request (used for page size, continuation
     *                   token, and partition key)
     * @param options    operation options (currently unused by this provider)
     * @return a page of results with an optional continuation token
     * @throws com.multiclouddb.api.MulticloudDbException on any Spanner error
     */
    @Override
    public QueryPage queryWithTranslation(ResourceAddress address, TranslatedQuery translated,
            QueryRequest query, OperationOptions options) {
        checkOpen(OperationNames.QUERY_WITH_TRANSLATION);
        try {
            long offset = SpannerContinuationToken.decode(query.continuationToken());
            int pageSize = query.maxPageSize() != null ? query.maxPageSize() : SpannerConstants.PAGE_SIZE_DEFAULT;
            // Respect Top N limit
            if (query.limit() != null) {
                pageSize = Math.min(pageSize, query.limit());
            }

            // Inject partition key condition before ORDER BY / pagination
            String sql = translated.queryString();
            if (query.partitionKey() != null) {
                sql = appendPartitionKeyConditionSQL(sql);
            }

            // Apply ORDER BY before LIMIT/OFFSET. The translated statement exposes
            // the portable row alias, so sort keys read the same authoritative
            // envelope the WHERE clause does.
            sql = appendResultSetControl(sql, query, true);

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

            List<Map<String, Object>> items = new ArrayList<>();
            try (ResultSet rs = databaseClient.singleUse().executeQuery(stmt)) {
                while (rs.next() && items.size() < pageSize + 1) {
                    items.add(SpannerRowMapper.toMap(rs));
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
    public ProviderId providerId() {
        return ProviderId.SPANNER;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        // Spanner.close() is idempotent in the SDK; we keep the reference final
        // so concurrent callers cannot observe a NULL or half-replaced field.
        spanner.close();
    }

    /**
     * Guards public entry points against use after {@link #close()}.
     * <p>
     * Verifies the client is open and throws a typed CLIENT_CLOSED exception otherwise.
     * <p>
     * Throws a typed {@link MulticloudDbException} with category
     * {@link MulticloudDbErrorCategory#CLIENT_CLOSED} so callers can branch
     * on {@code e.error().category()} without string-matching the message.
     * <p>
     * The {@code operation} argument is the caller's operation name (see
     * {@link OperationNames}). Stamping the actual attempted operation onto
     * {@link MulticloudDbError#operation()} keeps post-close error telemetry
     * attributable to the failing call ({@code create}, {@code read}, etc.)
     * rather than the generic literal {@code "checkOpen"}.
     *
     * @param operation the caller's operation name (e.g.
     *                  {@link OperationNames#CREATE}); must not be {@code null}.
     */
    private void checkOpen(String operation) {
        if (closed) {
            throw new MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.CLIENT_CLOSED,
                    "SpannerProviderClient has been closed",
                    ProviderId.SPANNER, operation, false, null));
        }
    }

    // ── Change Feed ─────────────────────────────────────────────────────────

    @Override
    public java.util.List<com.multiclouddb.api.changefeed.ChangeFeedCursor> listCursors(
            ResourceAddress address) {
        checkOpen(OperationNames.LIST_CURSORS);
        return changeFeedReader.listCursors(address);
    }

    @Override
    public com.multiclouddb.api.changefeed.ChangeFeedPage readChanges(
            ResourceAddress address,
            com.multiclouddb.api.changefeed.ChangeFeedCursor cursor,
            OperationOptions options) {
        checkOpen(OperationNames.READ_CHANGES);
        return changeFeedReader.readChanges(address, cursor, options);
    }

    // ── Provisioning ────────────────────────────────────────────────────────

    /**
     * Ensures the Spanner database exists, creating it if absent.
     * <p>
     * The {@code database} argument <em>must equal</em> the {@code databaseId} this
     * client was constructed with — operations route to the bound database regardless
     * of {@link com.multiclouddb.api.ResourceAddress#database()}, so accepting a
     * different name here would silently provision the wrong database. A
     * {@link MulticloudDbException} with category
     * {@link MulticloudDbErrorCategory#INVALID_REQUEST} is thrown if the names disagree
     * — typed so callers can branch on {@code e.error().category()} rather than
     * string-matching the message.
     * <p>
     * <strong>Emulator mode</strong> ({@code emulatorHost} configured): also ensures the
     * Spanner instance exists, creating it with the emulator's built-in
     * {@code emulator-config} instance config if necessary. This is required because
     * the local emulator starts with no instances.
     * <p>
     * <strong>Production mode</strong> (live Cloud Spanner): the instance is expected to
     * already exist; only the database is created. Creating a Spanner instance is a
     * billable, region-specific operation that should be done deliberately (via Terraform,
     * gcloud, or the Cloud Console), not implicitly from an SDK call.
     * <p>
     * Idempotent: {@code ALREADY_EXISTS} from either creation is swallowed.
     */
    @Override
    public void ensureDatabase(String database) {
        checkOpen(OperationNames.ENSURE_DATABASE);
        if (!databaseId.equals(database)) {
            throw new MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.INVALID_REQUEST,
                    "ensureDatabase('" + database + "') does not match the configured databaseId ('"
                            + databaseId + "'); this client routes operations to the configured "
                            + "database only. Construct a separate client for a different database.",
                    ProviderId.SPANNER, OperationNames.ENSURE_DATABASE, false, null));
        }
        try {
            if (emulatorMode) {
                // Emulator only: create the instance with the built-in emulator config.
                var instanceAdmin = spanner.getInstanceAdminClient();
                try {
                    instanceAdmin.createInstance(
                            com.google.cloud.spanner.InstanceInfo.newBuilder(
                                    com.google.cloud.spanner.InstanceId.of(projectId, instanceId))
                                    .setInstanceConfigId(
                                            com.google.cloud.spanner.InstanceConfigId.of(
                                                    projectId, SpannerConstants.EMULATOR_INSTANCE_CONFIG_ID))
                                    .setDisplayName(instanceId)
                                    .setNodeCount(1)
                                    .build()).get();
                    LOG.info("Created Spanner instance (emulator): {}", instanceId);
                } catch (java.util.concurrent.ExecutionException e) {
                    if (e.getCause() instanceof SpannerException se
                            && se.getErrorCode() == ErrorCode.ALREADY_EXISTS) {
                        LOG.debug("Spanner instance already exists: {}", instanceId);
                    } else {
                        throw e;
                    }
                }
            }

            // Ensure database exists (both modes).
            DatabaseAdminClient dbAdmin = spanner.getDatabaseAdminClient();
            try {
                dbAdmin.createDatabase(instanceId, database, List.of()).get();
                LOG.info("Created Spanner database: {}", database);
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof SpannerException se
                        && se.getErrorCode() == ErrorCode.ALREADY_EXISTS) {
                    LOG.debug("Spanner database already exists: {}", database);
                } else {
                    throw e;
                }
            }
        } catch (InterruptedException e) {
            // Preserve the interrupt flag (per the standard contract) and surface
            // the failure through the SDK's typed exception envelope so callers
            // never have to catch a raw RuntimeException to detect interruption.
            // TRANSIENT_FAILURE because the operation can usually be retried on a
            // non-interrupted thread.
            Thread.currentThread().interrupt();
            throw new MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.TRANSIENT_FAILURE,
                    "Interrupted while creating Spanner database: " + database,
                    ProviderId.SPANNER, OperationNames.ENSURE_DATABASE, true, null), e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SpannerException se) {
                throw SpannerErrorMapper.map(se, OperationNames.ENSURE_DATABASE);
            }
            // Non-Spanner cause (e.g. a runtime failure from the admin RPC layer):
            // wrap as PROVIDER_ERROR so callers see the SDK's portable error type
            // instead of a raw RuntimeException. The original cause is attached.
            throw new MulticloudDbException(new MulticloudDbError(
                    MulticloudDbErrorCategory.PROVIDER_ERROR,
                    "Failed to create Spanner database: " + database,
                    ProviderId.SPANNER, OperationNames.ENSURE_DATABASE, false, null), cause);
        } catch (SpannerException se) {
            throw SpannerErrorMapper.map(se, OperationNames.ENSURE_DATABASE);
        }
    }

    /**
     * Ensures the Spanner table for the given address exists, creating it if absent.
     * <p>
     * Existence is detected by issuing a lightweight {@code SELECT 1 FROM <table> LIMIT 1}
     * query. If that throws {@code NOT_FOUND} or {@code INVALID_ARGUMENT} (table does not
     * exist), a DDL {@code CREATE TABLE} statement is issued via the
     * {@link DatabaseAdminClient}.
     * <p>
     * The table is always created with the standard schema:
     * <pre>
     * CREATE TABLE &lt;tableName&gt; (
     *   partitionKey STRING(MAX) NOT NULL,
     *   sortKey      STRING(MAX) NOT NULL,
     *   data         STRING(MAX)
     * ) PRIMARY KEY (partitionKey, sortKey)
     * </pre>
     * Race conditions ("Duplicate name in schema") are silently ignored.
     *
     * @param address the logical database + collection; {@code address.collection()} is
     *                used as the Spanner table name
     * @throws com.multiclouddb.api.MulticloudDbException on DDL errors, or on a non-Spanner
     *         cause from the DDL future (wrapped as {@code PROVIDER_ERROR} so callers
     *         see the typed envelope rather than a raw {@code RuntimeException})
     */
    @Override
    public void ensureContainer(ResourceAddress address) {
        checkOpen(OperationNames.ENSURE_CONTAINER);
        String tableName = address.collection();
        boolean tableExists = false;
        try {
            // Check if table already exists by attempting a trivial query
            Statement checkStmt = Statement.of(
                    String.format(SpannerConstants.QUERY_TABLE_EXISTS_PROBE, tableName));
            try (ResultSet rs = databaseClient.singleUse().executeQuery(checkStmt)) {
                // If we get here, table exists. Do NOT early-return — fall
                // through so the change-stream provisioning block below also
                // runs for callers that flipped on
                // ChangeFeedConfig.extendedRetention(...) AFTER the table was
                // first created (the most common upgrade path). The DDL block
                // is idempotent (swallows "Duplicate name in schema") so the
                // re-run is safe and cheap when no opt-in is set.
                LOG.info("Spanner table already exists: {}", tableName);
                tableExists = true;
            }
        } catch (SpannerException e) {
            if (e.getErrorCode() != ErrorCode.NOT_FOUND
                    && e.getErrorCode() != ErrorCode.INVALID_ARGUMENT) {
                throw SpannerErrorMapper.map(e, OperationNames.ENSURE_CONTAINER);
            }
            // Table doesn't exist — create it
        }

        if (!tableExists) {
            try {
                DatabaseAdminClient adminClient = spanner.getDatabaseAdminClient();
                String ddl = String.format(SpannerConstants.DDL_CREATE_TABLE, tableName);
                adminClient.updateDatabaseDdl(
                        instanceId, databaseId, List.of(ddl), null).get();
                LOG.info("Created Spanner table: {}", tableName);
            } catch (InterruptedException e) {
                // Preserve the interrupt flag and surface as TRANSIENT_FAILURE so
                // callers don't have to catch a raw RuntimeException for interruption.
                Thread.currentThread().interrupt();
                throw new MulticloudDbException(new MulticloudDbError(
                        MulticloudDbErrorCategory.TRANSIENT_FAILURE,
                        "Interrupted while creating Spanner table: " + tableName,
                        ProviderId.SPANNER, OperationNames.ENSURE_CONTAINER, true, null), e);
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains(SpannerConstants.DDL_ERR_DUPLICATE_NAME)) {
                    LOG.debug("Spanner table already exists (race): {}", tableName);
                } else if (e instanceof SpannerException se) {
                    throw SpannerErrorMapper.map(se, OperationNames.ENSURE_CONTAINER);
                } else if (e instanceof java.util.concurrent.ExecutionException ee
                        && ee.getCause() instanceof SpannerException se) {
                    throw SpannerErrorMapper.map(se, OperationNames.ENSURE_CONTAINER);
                } else {
                    // Non-Spanner cause from the DDL future — surface through the
                    // SDK's typed envelope (PROVIDER_ERROR) rather than leaking a
                    // raw RuntimeException to callers.
                    Throwable cause = e instanceof java.util.concurrent.ExecutionException ee ? ee.getCause() : e;
                    throw new MulticloudDbException(new MulticloudDbError(
                            MulticloudDbErrorCategory.PROVIDER_ERROR,
                            "Failed to create Spanner table: " + tableName,
                            ProviderId.SPANNER, OperationNames.ENSURE_CONTAINER, false, null),
                            cause);
                }
            }
        }

        // The physical column layout may have changed: this call may have just
        // created the table, or an operator may have run
        // `ALTER TABLE ... ADD COLUMN` out of band before calling us. Drop the
        // cached layout so the next write re-reads INFORMATION_SCHEMA instead of
        // mirroring an out-of-date (possibly non-existent) column set.
        invalidateTableColumns(tableName);

        // If the user opted in to extended change-feed retention, ensure the
        // companion Spanner CHANGE STREAM exists with the requested
        // retention_period option. This is idempotent: if the stream already
        // exists we swallow "Duplicate name in schema". The change-stream
        // name follows the convention used by SpannerChangeFeedReader
        // (default: <table>_changes) so the reader transparently picks it up.
        if (config.changeFeed().hasExtendedRetention()) {
            Duration retention = config.changeFeed().extendedRetention().orElseThrow();
            // Resolve the stream name the same way SpannerChangeFeedReader does
            // so the connection-override key `changeStream.<collection>` does
            // not cause a producer/consumer mismatch: a user with the override
            // set and the opt-in set would otherwise provision an orphan
            // <table>_changes stream (still accruing change-data-volume cost)
            // and read from the un-extended override stream.
            String streamName = config.connection().getOrDefault(
                    "changeStream." + tableName, tableName + "_changes");
            // value_capture_type='NEW_ROW' is REQUIRED — the reader's extractValues
            // pipeline assumes the entire current row arrives in mods.new_values
            // (so per-field FIELD_DATA filtering produces the documented full-
            // document payload on UPDATE). Spanner's default value_capture_type is
            // OLD_AND_NEW_VALUES, under which mods.new_values carries ONLY the
            // columns mutated by each write — silently dropping unchanged columns
            // on every UPDATE event. Omitting this option would make
            // SDK-provisioned streams emit a different payload shape than operator-
            // provisioned streams (the documented out-of-band DDL also specifies
            // NEW_ROW), introducing silent cross-provider divergence within Spanner
            // itself depending on whose CREATE ran.
            String streamDdl = "CREATE CHANGE STREAM " + streamName + " FOR " + tableName
                    + " OPTIONS ("
                    + "value_capture_type = 'NEW_ROW', "
                    + "retention_period = '" + formatRetentionPeriod(retention) + "'"
                    + ")";
            try {
                DatabaseAdminClient adminClient = spanner.getDatabaseAdminClient();
                adminClient.updateDatabaseDdl(instanceId, databaseId, List.of(streamDdl), null).get();
                LOG.info("Created Spanner change stream '{}' on table '{}' with retention {}",
                        streamName, tableName, retention);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MulticloudDbException(new MulticloudDbError(
                        MulticloudDbErrorCategory.TRANSIENT_FAILURE,
                        "Interrupted while creating Spanner change stream: " + streamName,
                        ProviderId.SPANNER, OperationNames.ENSURE_CONTAINER, true, null), e);
            } catch (Exception e) {
                Throwable cause = e instanceof java.util.concurrent.ExecutionException ee ? ee.getCause() : e;
                String causeMsg = cause != null && cause.getMessage() != null ? cause.getMessage() : "";
                if (causeMsg.contains(SpannerConstants.DDL_ERR_DUPLICATE_NAME)) {
                    // The change stream already exists. Read back its active
                    // retention_period and refuse to silently honour a mismatch:
                    // a caller flipping from extendedRetention(3d) to (7d) would
                    // otherwise see the call succeed while the on-disk stream
                    // stays at 3d, and only discover the gap days later when
                    // PROVIDER_TRIMMED arrives far from the offending call.
                    // Cosmos throws UNSUPPORTED_CAPABILITY(extended_retention_not_enacted)
                    // on the same scenario (CosmosProviderClient#ensureContainer);
                    // Spanner must mirror that to keep providerDetails portable.
                    Duration activeRetention = readChangeStreamRetention(streamName);
                    if (activeRetention != null && !activeRetention.equals(retention)) {
                        throw new MulticloudDbException(new MulticloudDbError(
                                MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY,
                                "Spanner change stream '" + streamName
                                        + "' already exists with retention_period=" + activeRetention
                                        + ", but the caller requested extendedRetention(" + retention + "). "
                                        + "Spanner cannot self-heal an in-place CREATE — run ALTER CHANGE STREAM "
                                        + streamName + " SET OPTIONS (retention_period = '"
                                        + formatRetentionPeriod(retention) + "') out of band, "
                                        + "or revert the opt-in to extendedRetention(" + activeRetention + ").",
                                ProviderId.SPANNER, OperationNames.ENSURE_CONTAINER, false,
                                Map.of("reason", "extended_retention_not_enacted",
                                        "capability", com.multiclouddb.api.Capability.EXTENDED_CHANGE_FEED_HISTORY,
                                        "requestedRetention", retention.toString(),
                                        "activeRetention", activeRetention.toString())), cause);
                    }
                    if (activeRetention == null) {
                        // INFORMATION_SCHEMA read-back returned no row, or its
                        // value did not parse to a Duration. We cannot prove
                        // the live stream matches the requested retention, so
                        // we do not claim it does — log loudly enough for an
                        // operator chasing retention drift to find this path.
                        LOG.warn("Spanner change stream '{}' already exists, but active retention_period "
                                + "could not be read back from INFORMATION_SCHEMA.CHANGE_STREAM_OPTIONS — "
                                + "skipping drift check. Requested extendedRetention({}) may or may not "
                                + "be enacted; verify with: SELECT option_value FROM "
                                + "INFORMATION_SCHEMA.CHANGE_STREAM_OPTIONS WHERE change_stream_name = '{}' "
                                + "AND option_name = 'retention_period'", streamName, retention, streamName);
                    } else {
                        LOG.debug("Spanner change stream already exists with matching retention: {}", streamName);
                    }
                } else if (cause instanceof SpannerException se
                        && se.getErrorCode() == ErrorCode.INVALID_ARGUMENT
                        && causeMsg.toLowerCase(java.util.Locale.ROOT).contains("retention_period")) {
                    // Spanner rejects retention_period values outside its native bounds
                    // (default max 7d, up to 1y with extended retention enabled).
                    // Surface this as a portable UNSUPPORTED_CAPABILITY tagged
                    // retention_exceeds_native_max so callers don't have to substring-
                    // match the message to disambiguate from generic INVALID_ARGUMENT.
                    throw new MulticloudDbException(new MulticloudDbError(
                            MulticloudDbErrorCategory.UNSUPPORTED_CAPABILITY,
                            "Spanner change stream retention_period " + retention
                                    + " exceeds the database's native maximum. "
                                    + "Default max is 7 days; up to 1 year is available only "
                                    + "when the database is configured for extended retention. "
                                    + "Reduce ChangeFeedConfig.extendedRetention(...) or "
                                    + "enable extended-retention support on the Spanner database. "
                                    + "Underlying message: " + causeMsg,
                            ProviderId.SPANNER, OperationNames.ENSURE_CONTAINER, false,
                            Map.of("reason", "retention_exceeds_native_max",
                                    "requestedRetention", retention.toString())), cause);
                } else if (cause instanceof SpannerException se) {
                    throw SpannerErrorMapper.map(se, OperationNames.ENSURE_CONTAINER);
                } else {
                    throw new MulticloudDbException(new MulticloudDbError(
                            MulticloudDbErrorCategory.PROVIDER_ERROR,
                            "Failed to create Spanner change stream: " + streamName,
                            ProviderId.SPANNER, OperationNames.ENSURE_CONTAINER, false, null),
                            cause);
                }
            }
        }
    }

    /**
     * Reads back the active {@code retention_period} option of an existing
     * Spanner change stream via
     * {@code INFORMATION_SCHEMA.CHANGE_STREAM_OPTIONS}. Returns {@code null}
     * if the option is absent, the stream row is missing, or the value fails
     * to parse — callers (currently the duplicate-name catch in
     * {@link #ensureContainer(ResourceAddress)}) treat {@code null} as
     * "cannot verify, fall through to the prior log-and-continue behaviour"
     * so a transient INFORMATION_SCHEMA hiccup does not crash an otherwise
     * successful provisioning re-run.
     * <p>
     * Package-private and instance-scoped so the duplicate-name catch can
     * reuse the live {@code databaseClient} (no separate admin RPC required).
     */
    Duration readChangeStreamRetention(String streamName) {
        try {
            Statement stmt = Statement.newBuilder(
                    "SELECT OPTION_VALUE FROM INFORMATION_SCHEMA.CHANGE_STREAM_OPTIONS "
                            + "WHERE CHANGE_STREAM_NAME = @name AND OPTION_NAME = 'retention_period'")
                    .bind("name").to(streamName)
                    .build();
            try (ResultSet rs = databaseClient.singleUse().executeQuery(stmt)) {
                if (!rs.next()) return null;
                String raw = rs.getString(0);
                if (raw == null || raw.isEmpty()) return null;
                return parseRetentionPeriod(raw);
            }
        } catch (Exception e) {
            LOG.debug("Could not read back Spanner change stream '{}' retention_period: {}",
                    streamName, e.getMessage());
            return null;
        }
    }

    /**
     * Inverse of {@link #formatRetentionPeriod(Duration)}: parses Spanner's
     * {@code retention_period} option-value back into a {@link Duration}.
     * Spanner stores the option value as the same {@code Ns}/{@code Nm}/
     * {@code Nh}/{@code Nd} string the SDK emits, optionally surrounded by
     * single quotes (the value is wire-formatted as a SQL string literal).
     * Returns {@code null} on any parse failure so the caller can fall
     * through to the existing log-and-continue path rather than mis-typing
     * a different retention.
     */
    static Duration parseRetentionPeriod(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.startsWith("'") && trimmed.endsWith("'") && trimmed.length() >= 2) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) return null;
        char suffix = trimmed.charAt(trimmed.length() - 1);
        String numPart = trimmed.substring(0, trimmed.length() - 1);
        long n;
        try {
            n = Long.parseLong(numPart);
        } catch (NumberFormatException nfe) {
            return null;
        }
        switch (suffix) {
            case 's': return Duration.ofSeconds(n);
            case 'm': return Duration.ofMinutes(n);
            case 'h': return Duration.ofHours(n);
            case 'd': return Duration.ofDays(n);
            default:  return null;
        }
    }

    /**
     * Formats a JDK {@link Duration} as a Spanner {@code retention_period}
     * options string. Spanner accepts the suffixes {@code s} (seconds),
     * {@code m} (minutes), {@code h} (hours), {@code d} (days). We pick the
     * coarsest suffix that exactly represents the value to keep DDL diffs
     * stable (so the same {@code Duration} always emits the same string).
     */
    static String formatRetentionPeriod(Duration retention) {
        // Round up when there is sub-second residue so a Duration like
        // 24h + 1ms (the smallest builder-accepted opt-in above the portable
        // baseline) never silently truncates to 24h — that would revert the
        // opt-in to the portable baseline and break the
        // ChangeFeedConfig.extendedRetention("strictly greater than 24h")
        // contract.
        long seconds = retention.getSeconds() + (retention.getNano() > 0 ? 1 : 0);
        if (seconds % 86_400L == 0) return (seconds / 86_400L) + "d";
        if (seconds % 3_600L == 0)  return (seconds / 3_600L)  + "h";
        if (seconds % 60L == 0)     return (seconds / 60L)     + "m";
        return seconds + "s";
    }

    // ---- Internal helpers ----

    /**
     * Appends a partition key scoping condition to a GoogleSQL statement.
     * <p>
     * If the statement already has a {@code WHERE} clause, appends
     * {@code AND partitionKey = @_pkval}; otherwise appends
     * {@code WHERE partitionKey = @_pkval}.
     * The caller must bind the {@code @_pkval} parameter separately.
     *
     * @param sql the base SQL statement
     * @return the statement with the partition key condition appended
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
     * <p>
     * When no explicit ordering is requested, a default {@code ORDER BY partitionKey, sortKey}
     * is appended to guarantee deterministic OFFSET-based pagination.
     * <p>
     * The default tiebreaker is <b>skipped</b> when:
     * <ul>
     *   <li>The caller-supplied SQL already contains an {@code ORDER BY} clause
     *       (idempotency guard — uses a word-boundary regex applied to a
     *       string-literal-stripped copy of the SQL, so quoted text containing
     *       "ORDER BY" does not false-positive). The caller owns ordering.</li>
     *   <li>The SQL contains an aggregate function ({@code COUNT, SUM, MIN,
     *       MAX, AVG}) or a {@code GROUP BY} clause — GoogleSQL rejects
     *       {@code ORDER BY <non-grouped column>} on aggregate queries.</li>
     * </ul>
     */
    static String appendResultSetControl(String sql, QueryRequest query) {
        return appendResultSetControl(sql, query, false);
    }

    /**
     * @param envelopeOrdering when {@code true}, non-key {@link QueryRequest#orderBy()}
     *        fields are sorted through the authoritative {@code data} envelope
     *        via {@link SpannerExpressionTranslator#orderByExpression} instead of
     *        a bare physical column reference. Only SDK-generated SQL that
     *        exposes the {@link SpannerExpressionTranslator#ROW_ALIAS} row alias
     *        may pass {@code true}; caller-supplied native/legacy GoogleSQL has
     *        no such alias (and is provider-native by definition), so it keeps
     *        the bare-column form.
     */
    static String appendResultSetControl(String sql, QueryRequest query, boolean envelopeOrdering) {
        // Caller-supplied SQL already orders its results — do not emit a
        // second ORDER BY clause, even when the caller also populates
        // QueryRequest.orderBy().
        if (hasOrderByClause(sql)) {
            return sql;
        }
        // Aggregate queries reject ORDER BY on non-aggregated columns.
        boolean aggregate = containsAggregate(sql);
        StringBuilder result = new StringBuilder(sql);
        if (query != null && query.orderBy() != null && !query.orderBy().isEmpty()) {
            if (aggregate) {
                // Caller-supplied ordering on an aggregate query — honor the
                // caller's explicit ordering, but skip the primary-key
                // tiebreakers (GoogleSQL would reject them).
                result.append(" ORDER BY ");
                for (int i = 0; i < query.orderBy().size(); i++) {
                    SortOrder so = query.orderBy().get(i);
                    if (i > 0) result.append(", ");
                    result.append(so.field()).append(" ").append(so.direction().name());
                }
                return result.toString();
            }
            boolean sortsByPartitionKey = false;
            boolean sortsBySortKey = false;
            result.append(" ORDER BY ");
            for (int i = 0; i < query.orderBy().size(); i++) {
                SortOrder so = query.orderBy().get(i);
                if (i > 0) result.append(", ");
                appendSortKey(result, so, envelopeOrdering);
                if (SpannerConstants.FIELD_PARTITION_KEY.equals(so.field())) sortsByPartitionKey = true;
                if (SpannerConstants.FIELD_SORT_KEY.equals(so.field())) sortsBySortKey = true;
            }
            // Add only the missing primary key columns as tiebreakers for deterministic
            // pagination. Avoid duplicating columns the caller already sorted by.
            if (!sortsByPartitionKey) {
                result.append(", ").append(SpannerConstants.FIELD_PARTITION_KEY);
            }
            if (!sortsBySortKey) {
                result.append(", ").append(SpannerConstants.FIELD_SORT_KEY);
            }
        } else if (query != null && !aggregate) {
            // No explicit ordering — use primary key for deterministic OFFSET pagination.
            // Skipped for aggregate queries: GoogleSQL rejects ORDER BY on non-aggregated
            // columns (e.g. SELECT COUNT(*) FROM t ORDER BY partitionKey is invalid).
            result.append(" ORDER BY ").append(SpannerConstants.FIELD_PARTITION_KEY)
                  .append(", ").append(SpannerConstants.FIELD_SORT_KEY);
        }
        return result.toString();
    }

    /**
     * Appends one {@code ORDER BY} sort key.
     * <p>
     * {@code partitionKey} / {@code sortKey} are physical key columns that never
     * appear in the document envelope, so they always sort as bare columns —
     * which also keeps the deterministic-pagination tie-breakers intact. Every
     * other field is a portable document field, and on the envelope-aware paths
     * it is routed through {@link SpannerExpressionTranslator#orderByExpression}
     * so ORDER BY reads the same authoritative source as the WHERE clause and
     * {@link SpannerRowMapper}.
     */
    private static void appendSortKey(StringBuilder result, SortOrder so, boolean envelopeOrdering) {
        boolean keyColumn = SpannerConstants.FIELD_PARTITION_KEY.equals(so.field())
                || SpannerConstants.FIELD_SORT_KEY.equals(so.field());
        if (envelopeOrdering && !keyColumn) {
            result.append(SpannerExpressionTranslator.orderByExpression(
                    so.field(), so.direction().name()));
        } else {
            result.append(so.field()).append(" ").append(so.direction().name());
        }
    }

    /**
     * Builds the SDK's scan projection with the portable row alias
     * ({@code SELECT r.* FROM <table> AS r}). The alias is what lets
     * {@link #appendResultSetControl(String, QueryRequest, boolean)} emit
     * envelope-authoritative ORDER BY keys on the scan paths.
     */
    private static String aliasedScan(String table) {
        return "SELECT " + SpannerExpressionTranslator.ROW_ALIAS + ".* FROM " + table
                + " AS " + SpannerExpressionTranslator.ROW_ALIAS;
    }

    /**
     * Executes a GoogleSQL statement with LIMIT/OFFSET pagination and returns one page.
     * <p>
     * Appends {@code LIMIT (pageSize + 1) OFFSET offset} to detect whether more pages
     * exist. Parameter names starting with {@code @} are stripped before binding.
     *
     * @param sql        the GoogleSQL statement (without LIMIT/OFFSET)
     * @param parameters named query parameters, or {@code null}
     * @param pageSize   maximum items per page; defaults to 100 if {@code null}
     * @param offset     the number of rows to skip (0 for the first page)
     * @return a page of results with an encoded continuation token if more rows exist
     */
    private QueryPage executeStatement(String sql, Map<String, Object> parameters,
            Integer pageSize, long offset) {
        return executeStatement(sql, parameters, pageSize, offset, null, false);
    }

    private QueryPage executeStatement(String sql, Map<String, Object> parameters,
            Integer pageSize, long offset, QueryRequest query) {
        return executeStatement(sql, parameters, pageSize, offset, query, false);
    }

    private QueryPage executeStatement(String sql, Map<String, Object> parameters,
            Integer pageSize, long offset, QueryRequest query, boolean envelopeOrdering) {
        int limit = pageSize != null ? pageSize : SpannerConstants.PAGE_SIZE_DEFAULT;
        // Respect Top N limit: cap the page size
        if (query != null && query.limit() != null) {
            limit = Math.min(limit, query.limit());
        }

        // Append ORDER BY before LIMIT/OFFSET
        String baseSQL = appendResultSetControl(sql, query, envelopeOrdering);

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

        List<Map<String, Object>> items = new ArrayList<>();

        try (ResultSet rs = databaseClient.singleUse().executeQuery(stmtBuilder.build())) {
            while (rs.next() && items.size() < limit + 1) {
                items.add(SpannerRowMapper.toMap(rs));
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

    /**
     * Binds a single named parameter to a Spanner {@link Statement.Builder}.
     * <p>
     * Supported Java types: {@link String}, {@link Long}, {@link Integer},
     * {@link Boolean}, {@link Double}, {@link Float}, and {@code null} (bound as
     * {@code STRING NULL}). All other types are converted via {@link Object#toString()}.
     *
     * @param builder the statement builder to bind the parameter to
     * @param name    the bare parameter name (without the leading {@code @})
     * @param value   the parameter value; {@code null} is bound as a null STRING
     */
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

    /**
     * Shared serialiser for the SDK's internal {@code FIELD_DATA} JSON envelope
     * and complex value marshalling. Intentionally {@code private} so unrelated
     * code (including tests in this package) cannot mutate its configuration
     * and silently alter the on-the-wire format of every Spanner row.
     */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private static void setTypedNull(Mutation.WriteBuilder mutation, String column, Type columnType) {
        switch (columnType.getCode()) {
            case INT64 -> mutation.set(column).to((Long) null);
            case FLOAT64 -> mutation.set(column).to((Double) null);
            case BOOL -> mutation.set(column).to((Boolean) null);
            case STRING -> mutation.set(column).to((String) null);
            default -> throw new IllegalArgumentException(
                    "Unsupported physical mirror type: " + columnType.getCode());
        }
    }

}

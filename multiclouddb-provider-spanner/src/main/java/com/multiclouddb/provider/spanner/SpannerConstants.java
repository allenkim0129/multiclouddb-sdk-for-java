// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

/**
 * All hard-coded string keys, values, and numeric constants used by the
 * Spanner provider in one place.
 * <p>
 * Using this class avoids magic strings scattered across the implementation
 * and makes every tunable value easy to find and change.
 */
public final class SpannerConstants {

    private SpannerConstants() {}

    // ── Config property keys ──────────────────────────────────────────────────

    /** Connection config key for the GCP project ID. */
    public static final String CONFIG_PROJECT_ID = "projectId";

    /** Connection config key for the Spanner instance ID (required). */
    public static final String CONFIG_INSTANCE_ID = "instanceId";

    /** Connection config key for the Spanner database ID (required). */
    public static final String CONFIG_DATABASE_ID = "databaseId";

    /** Connection config key for the Spanner emulator host (e.g. {@code localhost:9010}). */
    public static final String CONFIG_EMULATOR_HOST = "emulatorHost";

    /** Default project ID used when {@code projectId} is not configured. */
    public static final String CONFIG_PROJECT_ID_DEFAULT = "test-project";

    // ── Document / column names ───────────────────────────────────────────────

    /** The partition key column — stores {@code Key.partitionKey()} for data placement. */
    public static final String FIELD_PARTITION_KEY = "partitionKey";

    /** The sort key column — stores {@code Key.sortKey()} for item identification. */
    public static final String FIELD_SORT_KEY = "sortKey";

    /** The data column used for document fields not stored as separate columns. */
    public static final String FIELD_DATA = "data";

    // ── Query parameter names ─────────────────────────────────────────────────

    /** Named parameter bound to the partition key value in scoped queries. */
    public static final String PARAM_PK_VAL = "_pkval";

    /** Named parameter prefix character for Spanner named parameters. */
    public static final String PARAM_PREFIX = "@";

    // ── Query fragments ───────────────────────────────────────────────────────

    /** SQL fragment used to detect whether a WHERE clause is already present. */
    public static final String SQL_WHERE = "WHERE";

    /** Cosmos DB SELECT * expression checked for backward compatibility. */
    public static final String QUERY_SELECT_ALL_COSMOS = "SELECT * FROM c";

    /** SELECT * template — append table name. */
    public static final String QUERY_SELECT_ALL_PREFIX = "SELECT * FROM ";

    /** Read-by-key SQL template — binds partitionKey and sortKey. */
    public static final String QUERY_READ_BY_KEY =
            "SELECT * FROM %s WHERE " + FIELD_PARTITION_KEY + " = @" + FIELD_PARTITION_KEY
            + " AND " + FIELD_SORT_KEY + " = @" + FIELD_SORT_KEY;

    /** WHERE clause fragment to scope a query to a single partition key. */
    public static final String QUERY_PARTITION_KEY_WHERE =
            " WHERE " + FIELD_PARTITION_KEY + " = @" + PARAM_PK_VAL;

    /** AND clause fragment to append partition scoping when WHERE already exists. */
    public static final String QUERY_PARTITION_KEY_AND =
            " AND " + FIELD_PARTITION_KEY + " = @" + PARAM_PK_VAL;

    /** Scoped full-scan SQL — append table name and partition key WHERE. */
    public static final String QUERY_SCOPED_FULL_SCAN =
            QUERY_SELECT_ALL_PREFIX + "%s" + QUERY_PARTITION_KEY_WHERE;

    // ── Existence probe ───────────────────────────────────────────────────────

    /** SQL used to probe whether a table exists (lightweight read). */
    public static final String QUERY_TABLE_EXISTS_PROBE = "SELECT 1 FROM %s LIMIT 1";

    // ── DDL ───────────────────────────────────────────────────────────────────

    /** DDL template to create the standard SDK table layout. */
    public static final String DDL_CREATE_TABLE =
            "CREATE TABLE %s ("
            + FIELD_PARTITION_KEY + " STRING(MAX) NOT NULL, "
            + FIELD_SORT_KEY      + " STRING(MAX) NOT NULL, "
            + FIELD_DATA          + " STRING(MAX)"
            + ") PRIMARY KEY (" + FIELD_PARTITION_KEY + ", " + FIELD_SORT_KEY + ")";

    /** Error fragment that indicates a table already exists in Spanner DDL responses. */
    public static final String DDL_ERR_DUPLICATE_NAME = "Duplicate name in schema";

    // ── Paging ────────────────────────────────────────────────────────────────

    /** Default page size used when the caller does not specify one. */
    public static final int PAGE_SIZE_DEFAULT = 100;

    // ── Diagnostics log prefix ────────────────────────────────────────────────

    /** Prefix for all Spanner success-path diagnostic log lines. */
    public static final String DIAG_PREFIX = "spanner.diagnostics";

    // ── Error / validation messages ───────────────────────────────────────────

    /** Error thrown when instanceId config key is absent or blank. */
    public static final String ERR_INSTANCE_ID_REQUIRED = "Spanner connection.instanceId is required";

    /** Error thrown when databaseId config key is absent or blank. */
    public static final String ERR_DATABASE_ID_REQUIRED = "Spanner connection.databaseId is required";
}

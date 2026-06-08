# Changelog — multiclouddb-provider-spanner

All notable changes to the `multiclouddb-provider-spanner` module will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this module adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added — Change-Feed support

- Change-feed reader backed by Spanner change streams, queried through the
  TVF `READ_<stream>(start_timestamp, end_timestamp, partition_token,
  heartbeat_milliseconds)` against a single-use read-only transaction.
  `listCursors` bootstraps the partition tree by calling the TVF with a
  `NULL` partition token; `readChanges` drains a bounded 5-second window per
  call and absorbs `child_partitions_record` rows (splits/merges) by
  rotating the active partition set.
- Per-collection stream-name resolution: defaults to `<collection>_changes`;
  override via the `changeStream.<collection>` connection key.
- Each `data_change_record.mod` is surfaced as one `ChangeEvent` with a
  stable `providerEventId` of
  `<server_transaction_id>:<commit_ts>:<record_sequence>:<mod_index>`.
- `INVALID_ARGUMENT`, `NOT_FOUND` and `OUT_OF_RANGE` on the TVF call (most
  commonly a partition token outside the stream's retention window) are
  mapped to `CursorExpiredException` with `reason=PROVIDER_TRIMMED`.
- Provisioning requirement: a change stream must exist for the target
  table — `CREATE CHANGE STREAM <name> FOR <table> OPTIONS (value_capture_type = 'NEW_ROW')`.
- Decodes the documented Spanner change-stream TVF schema: each row carries a
  single column `ChangeRecord` of type
  `ARRAY<STRUCT<data_change_record ARRAY<STRUCT<...>>, heartbeat_record ARRAY<STRUCT<...>>, child_partitions_record ARRAY<STRUCT<...>>>>`.
  The reader decomposes each row into its constituent inner records before
  dispatching to `readDataChange` / `extractHeartbeatTimestamp` /
  `extractChildPartitionTokens`. Earlier `Unreleased` builds attempted to read
  `ChangeRecord` as a single `STRUCT`, which failed at runtime against the
  Spanner Emulator with
  `Column ChangeRecord is not of correct type: expected STRUCT<…> but was ARRAY<STRUCT<…>>`.

- Reads `mods.keys`, `mods.new_values` and `mods.old_values` via Spanner's
  `getJson()` accessor when the column type reports `JSON` and falls back to
  `getString()` otherwise. Earlier `Unreleased` builds called `getString()`
  unconditionally, which failed against the Spanner Emulator with
  `Column keys is not of correct type: expected one of [STRING, NUMERIC] but was JSON`.
- Honours each `child_partitions_record.start_timestamp` when minting the
  continuation for the child partition tokens it announces. Earlier
  `Unreleased` builds reused the original bootstrap `start_timestamp` (or the
  parent partition's `lastCommitTs`), which the emulator rejected with
  `OUT_OF_RANGE: Specified start_timestamp is invalid for the partition`
  when the child window opened slightly later than the bootstrap call.

### Breaking changes

- **`update()` now uses a read-modify-write transaction to preserve previously
  written fields** (Spanner provider only). Earlier `Unreleased` builds overwrote
  the internal `FIELD_DATA` metadata column with only the fields named in the
  current call, silently hiding every other previously-written column on the next
  `read()` (the columns were still in Spanner — they were simply omitted from
  the SDK-visible projection). The fix merges the existing field set with
  the new one inside a single `databaseClient.readWriteTransaction()`,
  adding one read per `update` (acceptable for correctness).
  **Known cross-provider asymmetry:** this makes Spanner `update()` a partial
  update that preserves unrelated fields. The sibling providers do **not**
  preserve unrelated fields — Cosmos `update()` calls `replaceItem` (full-document
  replace) and DynamoDB `update()` calls `PutItem` with an `attribute_exists`
  guard (full-item replace). The portable SPI contract for `update()`
  partial-vs-full semantics is currently undefined; aligning the three providers
  is tracked as follow-up. Callers that relied on the bug to "forget" fields
  should issue a full document `upsert()` instead.
- **Document field named `data` is now rejected** with
  `MulticloudDbException(category = INVALID_REQUEST)`. The Spanner provider
  reserves the `data` column for internal `FIELD_DATA` metadata; previously a
  user document containing a field named `data` was silently dropped on
  `create()` / `update()` / `upsert()`, producing silent cross-provider data
  loss (Cosmos and DynamoDB both persist user fields named `data`). Surfacing
  this as a typed error gives the caller a deterministic signal. Aligning the
  three providers by encoding metadata under a non-collidable name is tracked
  as a follow-up schema-migration release; for now, rename the offending field
  in your document.
- **`upsert()` semantics changed from `INSERT_OR_UPDATE` to `REPLACE`.**
  Previous releases (incorrectly documented as `INSERT_OR_UPDATE` in the
  `[0.1.0-beta.1]` notes below — corrected) merged the new document with
  whatever columns already existed on the row. `REPLACE` deletes the
  existing row and inserts the new one, so columns absent from the upserted
  document become NULL on read. This matches the Cosmos / DynamoDB upsert
  contract (upsert is a *full document replace*). Callers that want partial
  modification must call `update()`.
- **Customer-managed tables now require a `data STRING(MAX)` column.** The
  provider uses this column to store the internal `FIELD_DATA` metadata that
  distinguishes "explicitly written null" from "absent schema column" — the
  same distinction that schemaless stores like Cosmos and DynamoDB preserve
  for free. Tables created by `ensureContainer()` already include this
  column; tables provisioned outside the SDK must be migrated:
  ```sql
  ALTER TABLE <my-table> ADD COLUMN data STRING(MAX);
  ```
- **`ensureDatabase(name)` now throws `MulticloudDbException` with category
  `INVALID_REQUEST` if `name` does not match the configured `databaseId`.**
  Operations always route to the database the client was constructed with,
  so accepting a different name silently provisioned the wrong database in
  earlier releases. The exception message cites both names so the operator
  can diagnose the mismatch. To target a different database, construct a
  new client.
- **Lifecycle errors are now typed.** `checkOpen()` (called by every public
  entry point) and the `ensureDatabase` name-mismatch validation throw
  `MulticloudDbException` with categories `CLIENT_CLOSED` and
  `INVALID_REQUEST` respectively, replacing the prior raw
  `IllegalStateException` / `IllegalArgumentException`. Consumers that
  caught the raw JDK exceptions must catch `MulticloudDbException` and
  branch on `error().category()`. The new `CLIENT_CLOSED` category is
  available on `MulticloudDbErrorCategory` and is added without breaking the
  expandable-enum contract (consumers must use `.equals()`, not `switch`).
- **`SpannerRowMapper.toMap()` now preserves explicitly written `null`
  values.** Earlier `Unreleased` builds silently filtered nulls out, which
  diverged from `toJsonNode()` (preserved nulls) and from the Cosmos /
  DynamoDB schemaless round-trip. `QueryPage` now uses a null-tolerant
  defensive copy. Callers iterating `page.items().get(i)` must tolerate
  `null` values, e.g. `Objects.toString(e.getValue(), "")` instead of
  `e.getValue().toString()`.
- **`ensureDatabase()` and `ensureContainer()` no longer leak raw
  `RuntimeException` on non-Spanner failures.** `InterruptedException` is now
  surfaced as `MulticloudDbException(TRANSIENT_FAILURE, retryable=true)`
  (with the interrupt flag still restored), and a non-Spanner cause inside
  the admin `ExecutionException` is surfaced as
  `MulticloudDbException(PROVIDER_ERROR)`, preserving the original cause.
  Callers that previously caught `RuntimeException` to detect interruption
  or admin RPC failures must catch `MulticloudDbException` and branch on
  `error().category()`.
- **Post-close errors now attribute the failing operation.** The
  `MulticloudDbError.operation()` value on a post-close exception used to
  be the literal `"checkOpen"`; it is now the caller's operation name
  (`create`, `read`, `update`, `upsert`, `delete`, `query`,
  `queryWithTranslation`, `ensureDatabase`, `ensureContainer`). Telemetry
  / dashboards aggregating by `operation` will now group post-close
  failures by the real failing call instead of the lifecycle helper name.

### Changed

- **Spanner instance creation in `ensureDatabase` is gated to emulator mode.**
  In production (no `emulatorHost` configured), the instance is expected to
  pre-exist; only the database is created. Creating a Spanner instance is a
  billable, region-specific operation that should be done deliberately.
- **Complex container values (`Map`, `Collection`) round-trip through STRING
  columns using an unambiguous prefix marker** (`U+0001` + `mcdb:json:`).
  `SpannerRowMapper` only parses values that carry the marker, so user strings
  that happen to start with `{` or `[` are returned verbatim. A user string
  that *itself* begins with `U+0001` is escaped at write time (doubled
  leading `U+0001`) so it cannot collide with the marker and still
  round-trips verbatim.
- **`BETWEEN` translation now wraps in parentheses** (`(field BETWEEN @lo AND @hi)`).
  Mirrors the parenthesised form emitted by sibling translators so cross-provider
  query stitching is uniform. GoogleSQL parses both forms correctly, so this is
  not a correctness fix on Spanner — purely a consistency improvement. The
  output of `TranslatedQuery.whereClause()` is now parenthesised.

### Fixed

- **Silent data loss on `update()` after partial writes.** See *Breaking
  changes* above for the read-modify-write transactional fix.
- **Default `ORDER BY` no longer fires for aggregate queries.** The provider
  previously appended `ORDER BY partitionKey, sortKey` to *every* SELECT,
  which GoogleSQL rejects for `SELECT COUNT(*)` / `SUM(...)` / `GROUP BY`
  queries with `column not aggregated`. The default is now suppressed when
  the SQL contains an aggregate function or `GROUP BY`; caller-supplied
  `ORDER BY` on aggregate queries is still honored verbatim.
- **`ORDER BY` detection ignores string literals.** `WHERE comment = 'please
  ORDER BY date'` no longer false-positives as "caller already provides
  ordering"; the literal is stripped before the regex match. SQL-escaped
  quotes (`''`) inside literals are handled correctly.
- **Race / NPE hazard in `close()`.** The `Spanner` field is now `final` again
  and `close()` is idempotent via a `volatile boolean closed` flag. All public
  entry points call `checkOpen()` so post-close callers receive a deterministic
  typed exception instead of a racy `NullPointerException`.
- **Default ORDER BY no longer duplicates primary-key columns** when the caller
  already sorts by `partitionKey` and/or `sortKey` — only the missing key is
  appended as a tiebreaker. In addition, if the caller-supplied SQL already
  contains its own `ORDER BY` clause (e.g., a raw GoogleSQL expression passed
  via `QueryRequest.expression()`), no default or tiebreaker `ORDER BY` is
  appended at all — the caller's ordering is honored verbatim.
- **`setMutationValue` no longer fails on common Java types** (e.g.
  `java.time.Instant`). JSON serialisation is restricted to `Map`/`Collection`;
  every other type falls back to `value.toString()`.
- **Legacy / pre-`FIELD_DATA` rows preserve null columns on read.** When
  `FIELD_DATA` is absent or malformed, `SpannerRowMapper` now applies the
  historical "no metadata => no filtering" rule including null columns
  (earlier `Unreleased` builds silently dropped every null on this path).
  Tables that pre-date the `FIELD_DATA` metadata column now round-trip
  null fields consistently with Cosmos / DynamoDB.
- **Legacy / pre-`FIELD_DATA` rows preserve every non-null column on
  `update()`.** Earlier `Unreleased` builds stamped `FIELD_DATA` with only
  the keys named in the current `update()` call, so a row that pre-dated this
  SDK (or was written by a sibling system without the `FIELD_DATA` metadata)
  had its untouched columns filtered out by the reader on the very next
  `read()` — silent data loss with no exception or log. The fix tracks
  whether pre-existing `FIELD_DATA` was successfully parsed; if the row has
  no trustworthy metadata (NULL or malformed), `update()` deliberately
  leaves `FIELD_DATA` alone so the reader's "no metadata => project every
  column" fallback continues to project all legacy columns. A subsequent
  `upsert()` (REPLACE) or `create()` promotes the row into the metadata
  regime by writing a complete `FIELD_DATA` stamp. Companion emulator test:
  `SpannerLegacyRowUpdateEmulatorTest`.
- **Reserved-field validation is now case-insensitive.** Spanner resolves
  column names case-insensitively, so a user document containing `Data` /
  `DATA` / `dAtA` previously slipped past the lowercase-only `data` reserved
  field check, then surfaced as a deep `INVALID_ARGUMENT: Duplicate column
  name` from the Spanner client (mapped to `MulticloudDbErrorCategory
  .INVALID_REQUEST`, but with a less actionable Spanner-internal message
  instead of the friendly SDK envelope). `validateNoReservedFields` now
  rejects any case-variant of `data` with `INVALID_REQUEST`, echoing the
  actual offending field name in the error message so callers can pinpoint
  which key in their document to rename. The defensive skip in
  `writeDocumentFields` was also extended to `equalsIgnoreCase` so any
  future internal caller bypassing the public-entry-point validation is
  still safe.

### Known limitations

- **`setMutationValue` / `bindParameter` write `(String) null` for null
  values regardless of the target column type.** Writing `null` into a
  Spanner `INT64`, `BOOL`, or `FLOAT64` column will be rejected by Spanner
  with `INVALID_ARGUMENT` until the provider performs schema introspection
  to bind the correctly typed null. Workaround for now: pass a *typed* zero
  / sentinel value (e.g., `0L`, `false`) instead of `null` for non-STRING
  columns, or wrap the column in a STRING. A schema-introspection fix is
  tracked for a follow-up release.

### Documentation

- **`delete()` of a missing key remains a silent no-op (idempotent).** The
  Spanner provider continues to use `Mutation.delete(table, Key.of(pk, sk))`
  via `databaseClient.write(...)`, which is idempotent natively — deleting
  a row that does not exist returns success without modifying state. This
  matches the LCD behaviour of Cosmos (404 swallowed) and DynamoDB
  (`DeleteItem` is idempotent natively). Documented in the API Javadoc on
  `MulticloudDbClient.delete(...)` and in `docs/guide.md`. Callers needing to detect a
  missing key should use `read()`, which returns `null` on every provider
  when the key does not exist.

### Changed

- **`BETWEEN` translation now wraps in parentheses** (`(field BETWEEN @lo AND @hi)`).
  Mirrors the parenthesised form emitted by sibling translators so cross-provider
  query stitching is uniform. GoogleSQL parses both forms correctly, so this is
  not a correctness fix on Spanner — purely a consistency improvement. The
  output of `TranslatedQuery.whereClause()` is now parenthesised.

### Fixed

- `SpannerChangeFeedReader.readDataChange` (and its companion `extractKey`,
  `extractValues`, `extractChildPartitionTokens`, `extractHeartbeatTimestamp`,
  `parseSequence`) now read every `Struct` field through new
  case-canonical `getStringOrNull` / `getTimestampOrNull` /
  `getStructListOrEmpty` helpers backed by `canonicalField(...)`, matching
  the case-canonical pattern already applied to `unwrap` / `unwrapOrNull` /
  `hasNonNullField`. Spanner's TVF column casing varies across client
  versions; the previous direct accessors (e.g. `getTimestamp("commit_timestamp")`)
  would throw `IllegalArgumentException` whenever Spanner returned a
  differently-cased identifier. The unused `table` local in `readDataChange`
  is also dropped.
- `SpannerChangeFeedReader.unwrapOrNull` now resolves the canonical
  (case-sensitive) struct-field name before calling `row.getStruct(...)`,
  matching the case-canonical fix already applied to `hasNonNullField`.
  Some Spanner change-stream TVF columns are returned as upper-case
  identifiers, and the previous code threw `IllegalArgumentException`
  whenever the caller's lookup key did not exactly match Spanner's casing.
- `SpannerChangeFeedReader.parseContinuation` no longer silently falls back
  to `Timestamp.now()` when a saved continuation fails to parse. A non-empty
  continuation that cannot be parsed now throws an internal
  `MalformedContinuation`, which `readChanges` surfaces as
  `CursorExpiredException` with `reason=MALFORMED`. The legitimate
  "no continuation yet" (`null` / blank) case still anchors at "now".
- `hasNonNullField()` in `SpannerChangeFeedReader` no longer mismatches when
  Spanner returns a field name in different case than the lookup key. The
  helper now resolves the canonical field name from
  `getType().getStructFields()` (case-insensitive) and passes that exact name
  to `Struct.isNull()`, which is case-sensitive.

## [0.1.0-beta.1] — 2026-04-23

### Added

- The Spanner client now contributes the canonical
  `multiclouddb-sdk-java/<version>` token to the outgoing gRPC `user-agent`
  metadata via gax `FixedHeaderProvider`. The gax channel preserves the
  underlying gRPC default user-agent and merges this token alongside it. When
  `MulticloudDbClientConfig.Builder.userAgentSuffix(String)` is configured,
  the suffix is appended to the token.

#### Provider adapter and client

- `SpannerProviderAdapter` — SPI entry point auto-discovered via
  `ServiceLoader`; registers as `ProviderId.SPANNER` and supplies
  `SpannerProviderClient` and `SpannerExpressionTranslator`
- `SpannerProviderClient` — full `MulticloudDbProviderClient` implementation
  backed by the Google Cloud Spanner Java client library

#### Authentication

- **Application Default Credentials** — uses the Google Cloud ADC chain
  (service account JSON, `gcloud auth`, Compute Engine metadata, etc.) for
  production environments
- **Emulator support** — when `connection.emulatorHost` is set, routes traffic
  to the Spanner emulator and bypasses normal cloud authentication

#### Connection configuration

- Required: `connection.instanceId`, `connection.databaseId`
- Optional: `connection.projectId` (defaults to `"test-project"`),
  `connection.emulatorHost`

#### CRUD operations

- `create` — Spanner `INSERT` mutation with `partitionKey` and `sortKey`
  columns; sort key defaults to partition key when absent; additional document
  fields written as individual columns via `writeMutationFields`
- `read` — GoogleSQL `SELECT * FROM <table> WHERE partitionKey = @partitionKey
  AND sortKey = @sortKey` via `singleUse().executeQuery()`; returns `null` when
  no row matches
- `update` — Spanner `UPDATE` mutation (fails if row does not exist)
- `upsert` — Spanner `INSERT_OR_UPDATE` mutation (merging upsert; superseded
  by `REPLACE` semantics in the Unreleased section — see *Breaking changes*
  above)
- `delete` — Spanner `DELETE` mutation using `KeySet.singleKey()`; `NOT_FOUND`
  is silently ignored for idempotent delete semantics

#### Query support

- **Native GoogleSQL passthrough** — execute raw GoogleSQL via
  `QueryRequest.nativeExpression()`
- **Full table scan** — when no expression is provided or expression equals
  the Cosmos-style sentinel `SELECT * FROM c`
- **Portable expression translation** — automatic translation via
  `SpannerExpressionTranslator` in the `queryWithTranslation` path
- **Partition-key scoping** — automatically appends
  `partitionKey = @_pkval` when `QueryRequest.partitionKey()` is set
- Named parameter binding supporting String, Long, Double, Boolean, and
  `byte[]` types; parameter names have leading `@` stripped for Spanner
  binding compatibility

#### Expression translation (`SpannerExpressionTranslator`)

- Translates the portable AST to GoogleSQL
  `SELECT * FROM <container> WHERE ...` syntax with named `@parameter`
  placeholders
- Comparison, logical, NOT, IN, BETWEEN expressions fully supported
- Portable function mapping:
  - `starts_with` → `STARTS_WITH(...)`
  - `contains` → `STRPOS(...) > 0`
  - `field_exists` → `field IS NOT NULL`
  - `string_length` → `CHAR_LENGTH(...)`
  - `collection_size` → `ARRAY_LENGTH(...)`

#### Row mapping (`SpannerRowMapper`)

- Read-side conversion from Spanner `ResultSet` rows to portable
  `Map<String, Object>` and Jackson `JsonNode`
- Type mapping: `STRING` → string, `INT64` → long, `FLOAT64` → double,
  `BOOL` → boolean, `BYTES` → Base64 string, `TIMESTAMP`/`DATE` → ISO string,
  `JSON` → parsed JSON node (with raw-string fallback)
- Null values mapped to JSON null / Java null

#### Error mapping (`SpannerErrorMapper`)

- Maps Spanner gRPC `ErrorCode` to portable error categories:
  - `INVALID_ARGUMENT` → `INVALID_REQUEST`
  - `NOT_FOUND` → `NOT_FOUND`
  - `ALREADY_EXISTS`, `ABORTED` → `CONFLICT`
  - `PERMISSION_DENIED` → `AUTHORIZATION_FAILED`
  - `UNAUTHENTICATED` → `AUTHENTICATION_FAILED`
  - `RESOURCE_EXHAUSTED` → `THROTTLED`
  - `FAILED_PRECONDITION` → `INVALID_REQUEST`
  - `UNIMPLEMENTED` → `UNSUPPORTED_CAPABILITY`
  - `UNAVAILABLE` → `TRANSIENT_FAILURE`
- Retryable flag sourced from `SpannerException.isRetryable()`
- Captures gRPC status name and error message in provider details
- Non-Spanner exceptions mapped to `PROVIDER_ERROR` with retryable `false`

#### Pagination (`SpannerContinuationToken`)

- Offset-based pagination encoded as opaque Base64-URL tokens (no padding)
- Provider client applies `LIMIT pageSize + 1 OFFSET offset` to detect whether
  more pages exist
- Decode gracefully returns offset `0` for null, blank, or malformed tokens

#### Provisioning

- `ensureDatabase` — no-op (database is selected at construction time)
- `ensureContainer` — probes table existence with `SELECT 1 FROM <table>
  LIMIT 1`; if missing, creates DDL with fixed schema: `partitionKey
  STRING(MAX) NOT NULL`, `sortKey STRING(MAX) NOT NULL`, `data STRING(MAX)`,
  `PRIMARY KEY (partitionKey, sortKey)` via `DatabaseAdminClient.
  updateDatabaseDdl()`; ignores `"Duplicate name in schema"` race conditions

#### Capabilities

- Reports all 13 well-known capabilities as supported with Spanner-specific
  notes: continuation-token paging (offset-based), cross-partition query,
  transactions, batch operations, strong consistency (external consistency),
  native SQL query (GoogleSQL), change feed, portable expression translation,
  LIKE operator, ORDER BY, ENDS_WITH, REGEX_MATCH, and case functions

#### Dependencies

- Google Cloud Spanner (`google-cloud-spanner 6.62.0`)

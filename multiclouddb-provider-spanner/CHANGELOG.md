# Changelog — multiclouddb-provider-spanner

All notable changes to the `multiclouddb-provider-spanner` module will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this module adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

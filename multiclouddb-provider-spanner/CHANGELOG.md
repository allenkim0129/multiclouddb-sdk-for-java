# Changelog — multiclouddb-provider-spanner

All notable changes to the `multiclouddb-provider-spanner` module will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this module adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Portable `patch(...)` updates the standard `data` document envelope in a retryable read-write transaction. Dynamic top-level `SET` paths, such as `/onSale`, work without DDL; only runtime values compatible with physical column types are mirrored for legacy schemas, while incompatible, null, or omitted values clear a mirror to its typed null. Portable expressions choose the authoritative envelope once per row and use a physical-column JSON projection only when no valid envelope exists, so reads and queries agree after fractional or dynamic patches without stale-field fallback. `SpannerCapabilities` declares `PATCH_CAP`.
- `SpannerCapabilities` declares **`NESTED_PATCH_UNSUPPORTED`**: nested JSON traversal is deferred from the v1 compatibility scope. A nested patch path fails fast with `UNSUPPORTED_CAPABILITY` (never a silent parent rewrite or no-op); replace the whole top-level field with a `SET` instead. This does not assert that a future transactional implementation would have a lost-update window.
- The portable contract requires `REPLACE` / `REMOVE` / `INCREMENT` to fail with `NOT_FOUND` on a missing field. Spanner performs the envelope read, validation, arithmetic, and write inside one retryable read-write transaction, so concurrent increments cannot be lost. Legacy rows remain readable through the existing physical-column projection and are promoted on a subsequent full write.
- Patch restrictions are enforced centrally on every provider: `data` (in any case) is reserved for the document envelope, case-only aliases such as `/title` and `/Title` are rejected, and the complete serialized request payload limit is 399 KB (408,576 bytes). Integral deltas and their resulting values must fit signed 64-bit range; accepted fractional deltas are canonical finite IEEE-754 `double`s with magnitude at most 9,007,199,254,740,991. Fractional increments of integral values are stored in the envelope as floating-point values; out-of-domain values are rejected as `INVALID_REQUEST`.
- Patch writes preserve typed `INT64`, `FLOAT64`, and `BOOL` nulls for compatible physical columns. Dynamic explicit nulls and `REMOVE` are preserved distinctly by the document envelope.
- `SpannerCapabilities` declares **`EXACT_FRACTIONAL_INCREMENT` as unsupported**: Spanner adds a fractional `INCREMENT` in IEEE-754 binary64, so accumulated fractional results may differ in the last ulp from DynamoDB's exact-decimal `N` arithmetic (`0.1` incremented by `0.2` stores `0.30000000000000004` here and `0.3` on DynamoDB). Integral increments remain exact. The declaration is informational — Spanner accepts every in-domain fractional delta and never raises `UNSUPPORTED_CAPABILITY` for it.

- Change-feed reader backed by Spanner change streams via the `READ_<stream>` TVF (single-use read-only transaction; 5-second bounded window per call). `listCursors` bootstraps the partition tree by calling the TVF with a `NULL` partition token and anchors each cursor''s bookmark at `max(now, childStart)` so `now()` cursors honour their live-tip contract on the emulator. `readChanges` drains a bounded window, absorbs `child_partitions_record` rows (splits/merges), rotates the partition list across partitions, and surfaces `isTerminal()=true` when a cursor''s sole partition closes without children. Each `data_change_record.mod` becomes one `ChangeEvent` with a stable `providerEventId` (`<server_transaction_id>:<commit_ts>:<record_sequence>:<mod_index>`). `INVALID_ARGUMENT` / `NOT_FOUND` / `OUT_OF_RANGE` on the TVF (most commonly a partition token outside the stream''s retention window) is mapped to `CursorExpiredException(reason=PROVIDER_TRIMMED)`.
- Per-collection change-stream name resolution: defaults to `<collection>_changes`; override via the `changeStream.<collection>` connection key (so producer and reader resolve the same stream when running against an operator-provisioned change stream).
- Extended-retention provisioning: `SpannerProviderClient.ensureContainer(address)` emits an idempotent `CREATE CHANGE STREAM <name> FOR <table> OPTIONS (value_capture_type = ''NEW_ROW'', retention_period = ''<value>'')` when the user opted in via `ChangeFeedConfig.extendedRetention(...)`, on both the fresh-table and the pre-existing-table paths. `value_capture_type = ''NEW_ROW''` ensures `mods.new_values` carries the full post-image (the GoogleSQL default of `OLD_AND_NEW_VALUES` only carries the mutated columns). The duplicate-name path reads back the active `retention_period` from `INFORMATION_SCHEMA.CHANGE_STREAM_OPTIONS` and throws `UNSUPPORTED_CAPABILITY(reason="extended_retention_not_enacted")` (with `requestedRetention` and `activeRetention` in `providerDetails`) when the on-disk retention does not match the request — mirroring Cosmos''s read-back-and-reject so application code that branches on `providerDetails.reason` stays portable. `INVALID_ARGUMENT` from `updateDatabaseDdl(...)` whose message contains the `retention_period` token is re-mapped to `UNSUPPORTED_CAPABILITY(reason="retention_exceeds_native_max")`. `SpannerCapabilities` declares `EXTENDED_CHANGE_FEED_HISTORY_CAP` (default 24h; configurable up to 7d natively).
- Typed `CLIENT_CLOSED` envelope replacing prior raw `IllegalStateException` from `checkOpen()`. `close()` is idempotent and the `Spanner` field is `final` again; post-close errors attribute the failing operation (`create` / `read` / `update` / ...) instead of the literal `"checkOpen"`.

### Changed

- `upsert(address, key, document)` now uses Spanner `INSERT_OR_UPDATE` (was `REPLACE`). `REPLACE` is internally delete-then-insert, which change streams surface as `mod_type=INSERT` — making a second upsert of the same key appear as `ChangeType.CREATE` instead of `ChangeType.UPDATE`. `INSERT_OR_UPDATE` matches the `UPDATE` / `MODIFY` behaviour of Cosmos AVAD and DynamoDB Streams. The observable upsert semantics are unchanged: `FIELD_DATA` continues to project only the new document''s fields on read, so `CrudConformanceTests.upsertOverwrites` still passes.
- Spanner instance creation in `ensureDatabase` is gated to emulator mode. In production (no `emulatorHost` configured), the instance is expected to pre-exist; only the database is created. Creating a Spanner instance is a billable, region-specific operation that should be done deliberately.
- Complex container values (`Map`, `Collection`) round-trip through STRING columns using an unambiguous prefix marker (`U+0001` + `mcdb:json:`). User strings that happen to start with `{` or `[` are returned verbatim; user strings that themselves begin with `U+0001` are escaped at write time.
- `BETWEEN` translation wraps in parentheses (`(field BETWEEN @lo AND @hi)`) for cross-provider consistency.
- Scalar portable comparisons now use JSON type guards before `LAX_*` coercion, so numeric and boolean operands never match string values. `field_exists` uses the same envelope state and requires a non-null field.

### Breaking changes

- **`update()` is a full document replacement.** Spanner writes a fresh `FIELD_DATA` envelope from only the supplied document, matching Cosmos and DynamoDB. Omitted, null, or incompatible supported physical mirrors are explicitly written as typed NULL; fields omitted from the envelope are absent from SDK reads and portable queries. Use `patch()` for field-level modification.
- **Document field named `data` is rejected** with `MulticloudDbException(category = INVALID_REQUEST)` for `create`, `update`, and `upsert` on every provider. The name is reserved for the internal `FIELD_DATA` metadata. The reserved-field check is case-insensitive; `Data` / `DATA` / `dAtA` are all rejected with the offending field name echoed back so callers can pinpoint which key to rename.
- **`upsert()` is a full document replace.** Fields absent from the upserted document are absent from SDK reads and portable queries through the authoritative envelope; their supported physical mirrors are cleared to typed NULL. An explicit JSON `null` remains distinguishable from an absent field in the envelope. Callers that want partial modification must call `patch()`.
- **Customer-managed tables require a `data STRING(MAX)` column.** Tables created by `ensureContainer()` already include it; tables provisioned outside the SDK must run `ALTER TABLE <table> ADD COLUMN data STRING(MAX);`.
- **`ensureDatabase(name)` throws `MulticloudDbException(INVALID_REQUEST)` when `name` does not match the configured `databaseId`.** Operations always route to the client''s configured database; accepting a different name silently provisioned the wrong database previously. To target a different database, construct a new client.
- **Lifecycle errors are typed.** `checkOpen()` and the `ensureDatabase` name-mismatch validation throw `MulticloudDbException` with categories `CLIENT_CLOSED` and `INVALID_REQUEST` respectively, replacing the prior raw `IllegalStateException` / `IllegalArgumentException`. Consumers that caught the raw JDK exceptions must catch `MulticloudDbException` and branch on `error().category()`.
- **`SpannerRowMapper.toMap()` preserves explicitly written `null` values.** Callers iterating `page.items().get(i)` must tolerate `null` (e.g., `Objects.toString(e.getValue(), "")` instead of `e.getValue().toString()`).
- **The `data` column's stored format changed, and downgrades are not supported.** `create` / `update` / `upsert` / `patch` now write the authoritative full-document envelope `{"_mcdbDocument": { ... }}`; previously `data` held a JSON array of the field names that had been explicitly written. *Reads* are backward compatible in this direction — `SpannerRowMapper` detects a legacy `[`-prefixed value and falls back to the physical-column projection — but **rolling back to an older SDK is not**. An older SDK reading an envelope row does not recognise `{`-prefixed `data`, falls back to the physical-column projection, and therefore cannot see any top-level field that exists only in the envelope (every field without a matching DDL column, including dynamic fields created by `patch`). A write from that older SDK then leaves a stale envelope behind in `data`, which the newer SDK will treat as authoritative on the next read.
  **Operational guidance: roll forward, not back.** Deploy the new SDK to all writers before relying on dynamic fields, and if a rollback is unavoidable, first rewrite affected rows with the older SDK's full-document `upsert` so every field it needs has a physical column.

### Fixed

- **`NOT field_exists(x)` now matches Cosmos and DynamoDB.** `field_exists` translated to a bare `JSON_TYPE(<envelope accessor>) != 'null'`, but Spanner's `JSON_QUERY` yields SQL `NULL` for an absent path, so the predicate was SQL `NULL` — not `FALSE` — for a missing field. Un-negated that still excluded the row (which is why the divergence was invisible), but `NOT NULL` is also `NULL`, so `NOT field_exists(x)` **excluded** rows whose field is absent or explicitly null, while Cosmos (`NOT (IS_DEFINED(c.x) AND NOT IS_NULL(c.x))`) and DynamoDB (`NOT (x IS NOT MISSING AND x IS NOT NULL)`) **include** them. The emitted SQL is now `COALESCE(JSON_TYPE(<accessor>) != 'null', FALSE)`, which is two-valued and therefore negatable identically on all three providers. Un-negated `field_exists` behaviour is unchanged.
- **Portable `ORDER BY` cross-type ranking now matches Cosmos.** Values of different JSON types were ranked `number < string < boolean < null/absent`; they are now ranked `null/absent < boolean < number < string`, reproducing Cosmos NoSQL's documented total order (`undefined < null < boolean < number < string`). DynamoDB declares `ORDER_BY` unsupported, so Cosmos and Spanner are the only two providers on this surface and their disagreement was an ungated divergence. A query that sorts a field holding mixed JSON types therefore returns a different row order than before; ordering within a single type (numeric for numbers, lexicographic for strings, `FALSE` before `TRUE` for booleans) and the `partitionKey` / `sortKey` tiebreakers are unchanged, and `DESC` remains the exact reverse of `ASC`.
- **Physical mirror columns are resolved case-insensitively.** Spanner column identifiers are case-insensitive, but mirroring compared them exactly: a column declared `Status` never matched a document field `status` and was cleared to a typed NULL instead of mirroring the value, and a patch on `/status` silently left the `Status` mirror stale. A table declaring `PartitionKey` / `SortKey` was additionally treated as a mirror column and set twice in one mutation, which Spanner rejects with `Duplicate column name`. Reads were always correct through the `data` envelope; only the compatibility mirrors for non-SDK consumers were affected.
- **The physical-column metadata cache no longer poisons itself.** `INFORMATION_SCHEMA` returns an empty column list (not an error) for a table that does not exist yet, and `create()` resolves columns before its write fails — so the empty result was cached for the life of the client and every mirror column stayed unwritten even after `ensureContainer()` created the table. Empty results are no longer cached, the entry is invalidated after `ensureContainer(...)` runs its DDL (so an `ALTER TABLE ... ADD COLUMN` is picked up without a client restart), and the cache is keyed case-insensitively to match the `LOWER(TABLE_NAME)` lookup it caches.
- **An unserialisable nested value fails instead of being silently rewritten.** A `Map` / `Collection` field whose contents Jackson cannot serialise was stored as its Java `toString()` (e.g. `{k=java.lang.Object@1b6d}`), corrupting the document; it now raises `MulticloudDbException(INVALID_REQUEST)` naming the offending envelope. The redundant per-write serialisability probe that produced that fallback is gone, so each write serialises the document once instead of twice.

- **Default `ORDER BY` no longer fires for aggregate / `GROUP BY` queries.** The provider previously appended `ORDER BY partitionKey, sortKey` to every SELECT, which GoogleSQL rejects on aggregates with `column not aggregated`. The default is now suppressed when the SQL contains an aggregate function or `GROUP BY`; caller-supplied `ORDER BY` is honoured verbatim. The default also no longer duplicates primary-key columns when the caller already sorts by them — only the missing key is appended as a tiebreaker — and `ORDER BY` detection ignores string literals so `WHERE comment = ''please ORDER BY date''` is no longer a false positive.
- **Legacy / pre-`FIELD_DATA` rows preserve every column on read.** When `FIELD_DATA` is absent or malformed, `SpannerRowMapper` applies the historical "no metadata => no filtering" rule including nulls. A subsequent `create`, `update`, or `upsert` writes a complete authoritative `FIELD_DATA` envelope; `update` intentionally hides fields omitted from its replacement payload.
- `ensureDatabase()` / `ensureContainer()` no longer leak raw `RuntimeException` on non-Spanner failures. `InterruptedException` surfaces as `MulticloudDbException(TRANSIENT_FAILURE, retryable=true)` (with the interrupt flag restored); non-Spanner causes inside the admin `ExecutionException` surface as `MulticloudDbException(PROVIDER_ERROR)` preserving the original cause.
- `setMutationValue` no longer fails on common Java types (e.g. `java.time.Instant`) — JSON serialisation is restricted to `Map`/`Collection`; every other type falls back to `value.toString()`.
- **Typed nulls are bound with the physical column's own type.** A null or type-incompatible mirror value is now written as `(Long) null`, `(Double) null`, `(Boolean) null`, or `(String) null` according to `INFORMATION_SCHEMA`, instead of binding every null as `(String) null` and failing the mutation on an `INT64` / `FLOAT64` / `BOOL` column. The fix applies to `create`, `update`, `upsert`, **and** `patch` — they all route mirror writes through `writeFullDocument` / `writePhysicalPatchValue`. The previously documented workaround (writing a typed zero or sentinel, or widening the column to `STRING`) is no longer needed.
- **Physical column types are loaded from `INFORMATION_SCHEMA.COLUMNS`.** The previous implementation probed `SELECT * FROM <table> LIMIT 0` and read `ResultSet.getType()`, but `getType()` / `getMetadata()` are guarded by `Preconditions.checkState(..., "next() call required")` in `GrpcResultSet` — with `LIMIT 0` no row is ever consumed, so **every** Spanner write path threw `IllegalStateException`. Columns whose declared type has no `Type` counterpart (arrays, protos, enums, structs, …) are skipped, which is behaviourally identical to declaring them unmirrorable. Metadata-load failures are normalised to `PROVIDER_ERROR` rather than leaking a raw `RuntimeException`.
- **Portable `ORDER BY` sorts through the authoritative `data` envelope.** SDK-generated query SQL now orders non-key fields with a `JSON_TYPE`-ranked sort key (null/absent, then booleans, then numbers, then strings, each followed by `LAX_FLOAT64` / `LAX_STRING` / `LAX_BOOL`) over the same envelope the `WHERE` clause and `SpannerRowMapper` read, instead of a bare physical column. Dynamic fields with no DDL column can therefore be sorted at all, and a physical mirror cleared to a typed null can no longer cause silent mis-ordering. `partitionKey` / `sortKey` remain bare-column tie-breakers (they never appear in the envelope, which also keeps deterministic pagination intact), and caller-supplied native GoogleSQL keeps the bare-column form because it exposes no SDK row alias.

### Known limitations

- **`NESTED_PATCH` is not supported.** A patch path with more than one segment (`/address/city`) fails fast with `UNSUPPORTED_CAPABILITY` before the transaction opens; nested JSON traversal is deferred from the v1 compatibility scope. Replace the whole top-level field with a `SET` instead, or branch on `capabilities().isSupported(Capability.NESTED_PATCH)`.
- **`patch()` serialises concurrent writers to the same row.** The existence check, the increment arithmetic, and the envelope write all happen inside one retryable read-write transaction over the row, so two concurrent patches to *disjoint* fields of the same document contend and one transparently retries. Cosmos DB and DynamoDB do not — their native partial writes apply per-attribute. No update is ever lost on any provider, but a hot single document will serialise on Spanner.
- **Fractional `INCREMENT` accumulates in IEEE-754 binary64** (`EXACT_FRACTIONAL_INCREMENT` is declared unsupported), so a fractional running total can drift in the last ulp from DynamoDB's exact-decimal result. Integral increments are exact.

### Documentation

- `delete()` of a missing key is documented as a silent no-op (idempotent); `Mutation.delete(table, Key.of(pk, sk))` is idempotent natively.

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

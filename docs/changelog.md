# Changelog

All notable changes to the Multicloud DB SDK modules.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and all modules adhere to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## multiclouddb-api

### [Unreleased]

**Changed:**

- `CursorExpiredException`'s `providerDetails.reason` set is now documented as
  a single canonical list across all providers — `TOKEN_AGED_OUT`,
  `PROVIDER_TRIMMED`, `ITERATOR_EXPIRED`, `MALFORMED`, `VERSION_UNSUPPORTED`,
  `PROVIDER_MISMATCH`, `RESOURCE_MISMATCH`. All seven are public constants
  on `CursorTokenCodec` (`REASON_*`). The `ITERATOR_EXPIRED` reason is new
  in this release; it is surfaced by DynamoDB Streams when a persisted shard
  iterator ages out (~5 minutes) before its next read. Recovery: re-bootstrap
  with `listCursors()` from the live tip. See `docs/guide.md` recovery table.
- All three change-feed readers (Cosmos / Dynamo / Spanner) now rotate the
  cursor's partition list inside `readChanges()` so multi-partition cursors
  visit each partition in true round-robin order. Previously such cursors
  silently starved every partition after index 0. The cursor wire format is
  unchanged.

**Added — Change-Feed API (3-primitive cursor model):**

- `com.multiclouddb.api.changefeed` package — new portable change-feed surface
  comprising `ChangeFeedCursor` (opaque, immutable position;
  `now()` sentinel + `fromToken`/`toToken` for persistence),
  `ChangeFeedPage` (events + `nextCursor` + `hasMore`/`terminal`),
  `ChangeEvent` (key + `ChangeType` + `commitTimestamp` + data +
  `providerEventId`), `ChangeType` enum (`CREATE`/`UPDATE`/`DELETE`), and
  `CursorExpiredException`.
- `MulticloudDbClient.listCursors(ResourceAddress)` — discovers one cursor per
  provider partition at the live tip.
- `MulticloudDbClient.readChanges(ResourceAddress, ChangeFeedCursor)` /
  `readChanges(ResourceAddress, ChangeFeedCursor, OperationOptions)` — drains
  one page of change events from a cursor and returns a fresh `nextCursor`.
- `MulticloudDbErrorCategory.CURSOR_EXPIRED` — new well-known category for
  trimmed / aged-out / mismatched cursors. Provider details key `reason`
  carries one of `TOKEN_AGED_OUT`, `PROVIDER_TRIMMED`, `ITERATOR_EXPIRED`,
  `MALFORMED`, `VERSION_UNSUPPORTED`, `PROVIDER_MISMATCH`,
  `RESOURCE_MISMATCH` (matching the canonical set documented in the
  preceding "Changed" entry).
- SPI: `MulticloudDbProviderClient.listCursors` / `readChanges` default to
  throwing `UNSUPPORTED_CAPABILITY` so existing adapters compile unchanged.
- `DefaultMulticloudDbClient` enforces capability-gating, validates the
  cursor's provider id and resource binding against the call site, and
  enforces a client-side 24-hour cap on the cursor's last-issued timestamp.
- Cursor token format documented as opaque, version-tagged
  (`{"v":1,...}` Base64URL JSON) and stable across SDK versions; the
  `internal` subpackage holds the codec for provider implementations.
**Added:**

- `MulticloudDbErrorCategory.CLIENT_CLOSED` — portable post-close error
  category. Every provider now surfaces this typed envelope when a CRUD,
  query, or provisioning call is made after `MulticloudDbClient.close()`.
  Previously the post-close behaviour was provider-specific: callers
  received a raw `IllegalStateException` from azure-cosmos / aws-sdk, an
  `IllegalStateException` from Spanner, or `null` / undefined behaviour
  depending on the provider. Telemetry, retry-policy, and circuit-breaker
  layers can now branch on the typed envelope; `CLIENT_CLOSED` is declared
  non-retryable because closing is a terminal lifecycle state.
- `OperationNames.PROVISION_SCHEMA` — operation-name constant. The
  `provisionSchema()` entry point now reports its operation name through
  the typed `MulticloudDbError.operation()` field for diagnostics
  attribution, matching every other entry point.
- `DefaultMulticloudDbClient` facade post-close guard. The facade
  short-circuits every public entry point with `CLIENT_CLOSED` *before*
  any per-request validation (document size, query parsing, etc.) runs.
  This guarantees that a closed client never reports `REQUEST_TOO_LARGE`
  or other category errors that would mask the underlying lifecycle bug.
  **`MulticloudDbClient.close()` itself is now idempotent**: a second
  `close()` is a synchronized no-op, and the underlying
  `providerClient.close()` is invoked exactly once even under concurrent
  callers.

**Documentation:**

- `MulticloudDbClient.delete(...)` is documented as idempotent — silent on
  missing key. The Javadoc now declares that deleting a key that does not
  exist is a silent no-op on every provider, which is the true LCD across
  Cosmos (404 swallowed), DynamoDB (`DeleteItem` is idempotent natively) and
  Spanner (`Mutation.delete` is idempotent natively). Callers that need to detect a missing key should use
  `MulticloudDbClient.read(...)`, which returns `null` on every provider
  when the key does not exist (non-mutating). `update()` also throws
  `NOT_FOUND` on a missing key, but it requires a document body and
  **overwrites on hit**, so it is not a safe pure existence probe.

### [0.1.0-beta.1] - 2026-04-23

**Added:**

- `MulticloudDbClientConfig.Builder.userAgentSuffix(String)` - optional
  caller-supplied token appended to the SDK user-agent header sent by all
  provider clients.
- `MulticloudDbClientConfig.userAgentSuffix()` - accessor returning the
  configured suffix, or `null` if unset.
- `com.multiclouddb.spi.SdkUserAgent` - SPI helper that builds the canonical
  `multiclouddb-sdk-java/<version>` user-agent token.
- `MulticloudDbClient` - synchronous, provider-agnostic interface for CRUD,
  query, and schema provisioning
- `MulticloudDbClientFactory` - discovers provider adapters via `ServiceLoader`
- `MulticloudDbClientConfig` - immutable builder-pattern configuration
- `QueryRequest` - portable expression or native expression passthrough with
  named parameters, pagination, partition key scoping, limit, and orderBy
- `MulticloudDbKey` - portable `(partitionKey, sortKey)` identity
- `ResourceAddress` - `(database, collection)` targeting
- Portable expression parser, validator, and translator SPI
- `CapabilitySet` - runtime capability introspection
- `MulticloudDbException` - structured error model with portable categories
- `OperationDiagnostics` - latency, request charge, request ID
- `DocumentMetadata` - last modified, TTL expiry, version/ETag
- Document size enforcement (399 KB limit)

**Validation:**

- `userAgentSuffix(String)` rejects values longer than 256 characters and
  non-printable US-ASCII, protecting against header injection.

---

## multiclouddb-provider-cosmos

### [Unreleased]

**Added — Change-Feed support:**

- Pull-mode change-feed reader backed by
  `CosmosContainer.queryChangeFeed(CosmosChangeFeedRequestOptions, JsonNode.class)`
  and `CosmosContainer.getFeedRanges()`. `listCursors` mints one cursor per
  feed range at the live tip; `readChanges` drains one page at a time and
  refreshes the per-range continuation token.
- AVAD opt-in via the `changeFeed.mode=allVersionsAndDeletes` connection key.
  In AVAD mode the reader maps `metadata.operationType` to
  `CREATE`/`UPDATE`/`DELETE`; in the default LatestVersion mode every event
  is surfaced as `UPDATE` and deletes are silently absent (Cosmos limitation —
  documented in [guide.md - Change Feeds](guide.md#change-feeds)).
- HTTP 410 GONE on `queryChangeFeed` is mapped to
  `CursorExpiredException` with `reason=PROVIDER_TRIMMED`.

**Added:**

- Typed `CLIENT_CLOSED` envelope on post-close entry points. Every CRUD,
  query, and provisioning method on `CosmosProviderClient` now consults a
  lifecycle guard before delegating to `azure-cosmos`. Calling any entry
  point after `close()` raises `MulticloudDbException` with category
  `CLIENT_CLOSED` (non-retryable) attributed to the caller's operation,
  instead of leaking the raw `IllegalStateException` from azure-cosmos's
  internal client. `close()` itself is now idempotent under concurrent
  callers (double-checked-locking `volatile` flag); the underlying
  `cosmosClient.close()` is invoked exactly once.
- `consistencyLevel` connection config key for opt-in client-level read
  consistency override (applied uniformly to every read from a given client
  instance). Valid values
  (case-insensitive): `STRONG`, `BOUNDED_STALENESS`, `SESSION`,
  `CONSISTENT_PREFIX`, `EVENTUAL`. When absent, read requests inherit the
  Cosmos DB account's configured default. See `docs/configuration.md` —
  *Consistency Level*.

**Changed:**

- Removed the hardcoded `ConsistencyLevel.SESSION` override from
  `CosmosClientBuilder`. Previously all reads were forced to `SESSION`
  regardless of the account's configured default. **Migration note:**
  accounts with a default of `STRONG` or `BOUNDED_STALENESS` will now serve
  reads at their configured level (higher latency / higher RU cost than
  before). Accounts configured to `SESSION` are unaffected. To restore the
  previous behaviour explicitly, set
  `multiclouddb.connection.consistencyLevel=SESSION`.

**Removed:**

- `CosmosConstants.CONSISTENCY_LEVEL_DEFAULT`
  (`public static final ConsistencyLevel`, previously
  `ConsistencyLevel.SESSION`) — removed without a deprecation cycle; the
  project is pre-release. Callers referencing this constant should use
  `ConsistencyLevel.SESSION` directly.

**Changed:**

- `BETWEEN` translation now wraps in parentheses
  (`(c.field BETWEEN @lo AND @hi)`). Without the wrapping parens, Cosmos
  NoSQL's parser greedily binds the `BETWEEN`'s inner `AND` together with any
  trailing logical `AND`, producing a *"Syntax error, incorrect syntax near
  'AND'"* `BadRequest` for predicates like
  `age BETWEEN @lo AND @hi AND marker = @m`. The output of
  `TranslatedQuery.whereClause()` is now parenthesised — backward-compatible
  at the query-execution level, but consumers that string-match the where
  clause should update their expectations.

**Documentation:**

- `delete()` of a missing key remains a silent no-op (idempotent). The
  Cosmos provider continues to swallow the native 404 from `deleteItem(...)`,
  matching the LCD behaviour of DynamoDB (`DeleteItem` is idempotent
  natively) and Spanner (`Mutation.delete` is idempotent natively).
  Documented in the API Javadoc on `MulticloudDbClient.delete(...)` and in
  `docs/guide.md`. No caller-visible behaviour change. Callers needing to
  detect a missing key should use `read()`, which returns `null` on every
  provider when the key does not exist.

### [0.1.0-beta.1] - 2026-04-23

**Added:**

- User-Agent header stamping with `multiclouddb-sdk-java/<version>` token
  and optional user-configured suffix.
- `CosmosProviderAdapter` - SPI entry point for Cosmos DB
- `CosmosProviderClient` - full implementation backed by Azure Cosmos DB Java SDK v4
- Master-key and Azure Identity (Entra ID) authentication
- Gateway and Direct connection modes
- Full CRUD with automatic field injection (`id`, `partitionKey`)
- Portable expression translation to Cosmos SQL
- Native SQL passthrough
- Cross-partition query support (capability-gated)
- Schema provisioning (database + container creation)

---

## multiclouddb-provider-dynamo

### [Unreleased]

**Added — Change-Feed support:**

- Change-feed reader backed by DynamoDB Streams (`DescribeStream`,
  `GetShardIterator`, `GetRecords`). `listCursors` returns one cursor per
  open shard at the live tip; `readChanges` drains one shard's
  next page per call and absorbs shard splits/closes by re-describing the
  stream and emitting child shards in the next cursor.
- Continuation sentinels (`@@TRIM_HORIZON`, `@@LATEST`) preserve the correct
  `ShardIteratorType` on resume after an empty page.
- `TrimmedDataAccessException` (records older than the fixed 24-hour
  Streams retention) is mapped to `CursorExpiredException` with
  `reason=PROVIDER_TRIMMED`.
- Provisioning requirement: table must have
  `StreamSpecification(NEW_AND_OLD_IMAGES)` enabled — `listCursors` returns
  `UNSUPPORTED_CAPABILITY` with `reason=stream_not_enabled` otherwise. The
  24-hour Streams retention naturally matches the portable client-side
  baseline.
- Note: the AWS SDK v2 ships the Streams client classes inside the main
  `dynamodb` artifact (`software.amazon.awssdk.services.dynamodb.streams.*`)
  as of 2.34.x; no separate `dynamodbstreams` artifact is required.
**Added:**

- Typed `CLIENT_CLOSED` envelope on post-close entry points. Every CRUD,
  query, and provisioning method on `DynamoProviderClient` now consults a
  lifecycle guard before delegating to the AWS SDK. Calling any entry
  point after `close()` raises `MulticloudDbException` with category
  `CLIENT_CLOSED` (non-retryable) attributed to the caller's operation,
  instead of leaking the raw `IllegalStateException` from the AWS SDK
  client. `close()` itself is now idempotent under concurrent callers
  (double-checked-locking `volatile` flag); the underlying
  `dynamoClient.close()` is invoked exactly once.

**Changed:**

- `BETWEEN` translation now wraps in parentheses (`(field BETWEEN ? AND ?)`).
  Mirrors the parenthesised form emitted by sibling translators so
  cross-provider query stitching is uniform. PartiQL parses both forms
  correctly, so this is not a correctness fix on Dynamo — purely a
  consistency improvement. The output of `TranslatedQuery.whereClause()` is
  now parenthesised.

**Documentation:**

- `delete()` of a missing key remains a silent no-op (idempotent). The
  Dynamo provider issues an unconditional `DeleteItem`, so a delete of a key
  that does not exist is silently ignored — matching the LCD behaviour of
  Cosmos (404 swallowed) and Spanner (`Mutation.delete` is idempotent
  natively). No `attribute_exists` guard is added, so deletes do not pay the
  conditional-write WCU surcharge. Documented in the API Javadoc on
  `MulticloudDbClient.delete(...)` and in `docs/guide.md`. Callers needing to
  detect a missing key should use `read()`, which returns `null` on every
  provider when the key does not exist.

### [0.1.0-beta.1] - 2026-04-23

**Added:**

- User-Agent suffix support via `SdkAdvancedClientOption.USER_AGENT_SUFFIX`.
- `DynamoProviderAdapter` - SPI entry point for DynamoDB
- `DynamoProviderClient` - full implementation backed by AWS SDK for Java 2.25.16
- AWS credential authentication (access key + secret key)
- Full CRUD with `attribute_not_exists` / `attribute_exists` guards
- Portable expression translation to PartiQL
- Native PartiQL passthrough
- Schema provisioning (table creation with ACTIVE-wait)

---

## multiclouddb-provider-spanner

### [Unreleased]

**Added — Change-Feed support:**

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
- Provisioning requirement: a change stream must exist for the target table —
  `CREATE CHANGE STREAM <name> FOR <table> OPTIONS (value_capture_type = 'NEW_ROW')`.
**Breaking changes:**

- `update()` now uses a read-modify-write transaction to preserve previously
  written fields (Spanner provider only). Earlier `Unreleased` builds
  overwrote the internal `FIELD_DATA` metadata column with only the fields
  named in the current call, silently hiding every other previously-written
  column on the next `read()`. The fix merges the existing field set with
  the new one inside a single `databaseClient.readWriteTransaction()`,
  adding one read per `update` (acceptable for correctness). **Known
  cross-provider asymmetry:** Spanner `update()` is now a partial update
  that preserves unrelated fields; Cosmos and DynamoDB `update()` are
  full-document replace. Callers that relied on the bug to "forget" fields
  should issue a full document `upsert()` instead.
- Document field named `data` is now rejected with
  `MulticloudDbException(category = INVALID_REQUEST)`. The Spanner provider
  reserves the `data` column for internal `FIELD_DATA` metadata; previously
  a user document containing a field named `data` was silently dropped on
  `create()` / `update()` / `upsert()`, producing silent cross-provider data
  loss. Rename the offending field in your document.
- `upsert()` semantics changed from `INSERT_OR_UPDATE` to `REPLACE`.
  Columns absent from the upserted document become NULL on read, matching
  the Cosmos / DynamoDB upsert contract (full document replace). Callers
  that want partial modification must call `update()`.
- Customer-managed tables now require a `data STRING(MAX)` column. Tables
  created by `ensureContainer()` already include this column; tables
  provisioned outside the SDK must be migrated:
  `ALTER TABLE <my-table> ADD COLUMN data STRING(MAX);`
- `ensureDatabase(name)` now throws `MulticloudDbException` with category
  `INVALID_REQUEST` if `name` does not match the configured `databaseId`.
- Lifecycle errors are now typed. `checkOpen()` and the `ensureDatabase`
  name-mismatch validation throw `MulticloudDbException` with categories
  `CLIENT_CLOSED` and `INVALID_REQUEST` respectively, replacing the prior
  raw `IllegalStateException` / `IllegalArgumentException`.
- `SpannerRowMapper.toMap()` now preserves explicitly written `null`
  values. `QueryPage` now uses a null-tolerant defensive copy. Callers
  iterating `page.items().get(i)` must tolerate `null` values.
- `ensureDatabase()` and `ensureContainer()` no longer leak raw
  `RuntimeException`. `InterruptedException` is surfaced as
  `MulticloudDbException(TRANSIENT_FAILURE, retryable=true)`; a non-Spanner
  cause inside the admin `ExecutionException` is surfaced as
  `MulticloudDbException(PROVIDER_ERROR)` preserving the original cause.
- Post-close errors now attribute the failing operation. The
  `MulticloudDbError.operation()` value on a post-close exception used to
  be the literal `"checkOpen"`; it is now the caller's operation name.

**Changed:**

- Spanner instance creation in `ensureDatabase` is gated to emulator mode.
  In production (no `emulatorHost` configured), the instance is expected
  to pre-exist; only the database is created.
- Complex container values (`Map`, `Collection`) round-trip through STRING
  columns using an unambiguous prefix marker (`U+0001` + `mcdb:json:`).
  `SpannerRowMapper` only parses values that carry the marker.
- `BETWEEN` translation now wraps in parentheses
  (`(field BETWEEN @lo AND @hi)`). Mirrors the parenthesised form emitted
  by sibling translators so cross-provider query stitching is uniform.
  GoogleSQL parses both forms correctly, so this is not a correctness fix
  on Spanner — purely a consistency improvement. The output of
  `TranslatedQuery.whereClause()` is now parenthesised.

**Fixed:**

- Silent data loss on `update()` after partial writes (see *Breaking
  changes* above for the read-modify-write transactional fix).
- Default `ORDER BY` no longer fires for aggregate queries. The default
  is now suppressed when the SQL contains an aggregate function or
  `GROUP BY`; caller-supplied `ORDER BY` on aggregate queries is still
  honored verbatim.
- `ORDER BY` detection ignores string literals. `WHERE comment = 'please
  ORDER BY date'` no longer false-positives as "caller already provides
  ordering"; the literal is stripped before the regex match. SQL-escaped
  quotes (`''`) inside literals are handled correctly.
- Race / NPE hazard in `close()`. The `Spanner` field is now `final` and
  `close()` is idempotent via a `volatile boolean closed` flag.
- Default ORDER BY no longer duplicates primary-key columns when the
  caller already sorts by `partitionKey` and/or `sortKey`. If the
  caller-supplied SQL already contains its own `ORDER BY` clause, no
  default or tiebreaker `ORDER BY` is appended at all.
- `setMutationValue` no longer fails on common Java types (e.g.
  `java.time.Instant`). JSON serialisation is restricted to
  `Map`/`Collection`; every other type falls back to `value.toString()`.
- Legacy / pre-`FIELD_DATA` rows preserve null columns on read. When
  `FIELD_DATA` is absent or malformed, `SpannerRowMapper` now applies the
  historical "no metadata => no filtering" rule including null columns.
- Legacy / pre-`FIELD_DATA` rows preserve every non-null column on
  `update()`. The fix tracks whether pre-existing `FIELD_DATA` was
  successfully parsed; if the row has no trustworthy metadata, `update()`
  deliberately leaves `FIELD_DATA` alone so the reader's "no metadata =>
  project every column" fallback continues to project all legacy columns.
- Reserved-field validation is now case-insensitive. A user document
  containing `Data` / `DATA` / `dAtA` previously slipped past the
  lowercase-only `data` reserved field check; `validateNoReservedFields`
  now rejects any case-variant of `data` with `INVALID_REQUEST`, echoing
  the actual offending field name in the error message.

**Known limitations:**

- `setMutationValue` / `bindParameter` write `(String) null` for null
  values regardless of the target column type. Writing `null` into a
  Spanner `INT64`, `BOOL`, or `FLOAT64` column is rejected by Spanner
  with `INVALID_ARGUMENT` until schema introspection lands. Workaround:
  pass a typed zero (e.g., `0L`, `false`) instead of `null` for
  non-STRING columns, or wrap the column in a STRING. A
  schema-introspection fix is tracked for a follow-up release.

**Documentation:**

- `delete()` of a missing key remains a silent no-op (idempotent). The
  Spanner provider continues to use `Mutation.delete(table, Key.of(pk, sk))`
  via `databaseClient.write(...)`, which is idempotent natively — deleting a
  row that does not exist returns success without modifying state. This
  matches the LCD behaviour of Cosmos (404 swallowed) and DynamoDB
  (`DeleteItem` is idempotent natively). Documented in the API Javadoc on
  `MulticloudDbClient.delete(...)` and in `docs/guide.md`. Callers needing to
  detect a missing key should use `read()`, which returns `null` on every
  provider when the key does not exist.

### [0.1.0-beta.1] - 2026-04-23

**Added:**

- User-Agent support via gax `FixedHeaderProvider`.
- `SpannerProviderAdapter` - SPI entry point for Spanner
- `SpannerProviderClient` - full implementation backed by Google Cloud Spanner 6.62.0
- GCP credential and emulator authentication
- Full CRUD with mutation-based writes
- Portable expression translation to GoogleSQL
- Native GoogleSQL passthrough
- Schema provisioning (DDL-based table creation)

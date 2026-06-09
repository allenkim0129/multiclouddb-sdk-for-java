# Changelog — multiclouddb-provider-cosmos

All notable changes to the `multiclouddb-provider-cosmos` module will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this module adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added — Extended Change-Feed Retention

- **`CosmosCapabilities`** now declares `EXTENDED_CHANGE_FEED_HISTORY_CAP`
  (notes: "Up to 30 days via Continuous Backup 30d tier; 7d minimum (AVAD
  requires Continuous Backup)"). The registry size for the Cosmos adapter
  grows from 16 to 17.
- **`CosmosProviderClient.ensureContainer(address)`** now provisions an AVAD
  `ChangeFeedPolicy` carrying the duration from
  `ChangeFeedConfig.extendedRetention(...)` when the user opted in. When the
  caller did not opt in (the default), `ensureContainer` behaves bit-for-bit
  identical to v1.
- New error normalisation: a 400 BadRequest whose message fingerprint
  indicates the Cosmos account does not have Continuous Backup enabled is
  re-mapped to `UNSUPPORTED_CAPABILITY` with
  `providerDetails.reason="continuous_backup_required"`. Without this
  re-mapping callers would see a generic `INVALID_REQUEST` and have to
  substring-match the message to disambiguate provisioning failures from
  genuine input validation. The continuous-backup fingerprint set is
  centralised in `CosmosConstants.CONTINUOUS_BACKUP_FINGERPRINTS`.
- **`CosmosProviderClient.ensureContainer(address)`** under the opt-in path
  now reads back the container's active `ChangeFeedPolicy` after
  `createContainerIfNotExists(...)` and throws
  `UNSUPPORTED_CAPABILITY(reason="extended_retention_not_enacted")` (with
  `requestedRetention` and `activeRetention` in `providerDetails`) when the
  pre-existing container's retention does not match the request. Cosmos has
  no public SDK API to update an existing container's `ChangeFeedPolicy`
  in place — silent acceptance would leave the caller paying for an opt-in
  the SDK never enacted. The operator must drop-and-recreate the container
  or revert to the active retention to clear the error.


### Changed

- `CosmosChangeFeedReader.readChanges()` now rotates the cursor's partition
  list so multi-range cursors (e.g., the `now()` sentinel hydrate that merges
  every feed range) visit each range in true round-robin order. Previously a
  multi-range cursor would only ever advance the first range and silently
  starve every range after index 0. `hasMore` is signalled eagerly when a
  multi-range cursor returned any events (not only on a full page), so the
  caller drains the remaining ranges before sleeping. The cursor wire format
  is unchanged; the partition list order now encodes the active-partition
  state. Same fix is applied to `DynamoChangeFeedReader` and
  `SpannerChangeFeedReader` for cross-provider parity.
- `CosmosChangeFeedReader.listCursors()` now stamps each minted `CursorToken`'s
  `issuedAtEpochMillis` with the wall-clock instant captured immediately after
  the warmup continuation is obtained (previously a single pre-loop timestamp
  was reused for every cursor). This aligns token age with the actual bookmark
  effective time and matches the semantics already used by `readChanges()`. On
  the PIT fallback path the stamped `issuedAt` equals the numeric suffix of the
  `@@PIT:<nowMs>` continuation by construction. The on-the-wire continuation
  format is unchanged, and callers that do not inspect `issuedAtEpochMillis`
  see no behavioural change. The
  `com.multiclouddb.api.changefeed.internal.CursorToken` Javadoc for
  `issuedAtEpochMillis` is tightened in the same change to spell out the new
  invariant. The Dynamo and Spanner providers receive the same alignment in
  this release (see their respective changelogs); the cross-provider invariant
  is now uniform.

### Added — Change-Feed support

- Pull-mode change-feed reader backed by
  `CosmosContainer.queryChangeFeed(CosmosChangeFeedRequestOptions, JsonNode.class)`
  and `CosmosContainer.getFeedRanges()`. `listCursors` mints one cursor per
  feed range at the live tip; `readChanges` drains one page at a time and
  refreshes the per-range continuation token. The reader always uses
  All-Versions-and-Deletes mode and unwraps the AVAD envelope so
  `ChangeEvent.type()` faithfully distinguishes
  `CREATE`/`UPDATE`/`DELETE`, and `ChangeEvent.data()` carries the
  document body (not the AVAD transport envelope). The Cosmos container
  the caller targets must therefore be provisioned with an AVAD
  change-feed policy (`ChangeFeedPolicy.createAllVersionsAndDeletesPolicy`)
  on an account that supports it; a non-AVAD container surfaces a Cosmos
  400 BadRequest through the SDK's normalised error envelope on the
  first read.
- HTTP 410 GONE on `queryChangeFeed` is mapped to
  `CursorExpiredException` with `reason=PROVIDER_TRIMMED`.
- The new change-feed entry points (`listCursors`, `readChanges`) honour the
  lifecycle guard described in **Added** below — calls after `close()` raise
  `MulticloudDbException(CLIENT_CLOSED)` attributed to
  `listCursors`/`readChanges`.

### Added

- **Typed `CLIENT_CLOSED` envelope on post-close entry points.** Every CRUD,
  query, and provisioning method on `CosmosProviderClient` now consults a
  lifecycle guard before delegating to `azure-cosmos`. Calling any entry
  point after `close()` raises `MulticloudDbException` with category
  `CLIENT_CLOSED` (non-retryable) attributed to the caller's operation,
  instead of leaking the raw `IllegalStateException` from azure-cosmos's
  internal client. `close()` itself is now idempotent under concurrent
  callers (double-checked-locking `volatile` flag); the underlying
  `cosmosClient.close()` is invoked exactly once.

### Documentation

- **`delete()` of a missing key remains a silent no-op (idempotent).** The
  Cosmos provider continues to swallow the native 404 from
  `deleteItem(...)`, matching the LCD behaviour of DynamoDB
  (`DeleteItem` is idempotent natively) and Spanner (`Mutation.delete` is
  idempotent natively). Documented in the API Javadoc on
  `MulticloudDbClient.delete(...)` and in `docs/guide.md`. No caller-visible
  behaviour change. Callers needing to detect a missing key should use `read()`, which
  returns `null` on every provider when the key does not exist.

### Added

- `consistencyLevel` connection config key for opt-in client-level read consistency
  override (applied uniformly to every read from a given client instance). Valid values (case-insensitive): `STRONG`, `BOUNDED_STALENESS`, `SESSION`,
  `CONSISTENT_PREFIX`, `EVENTUAL`. When absent, read requests inherit the Cosmos DB
  account's configured default. See `docs/configuration.md` — *Consistency Level*.

### Changed

- Removed the hardcoded `ConsistencyLevel.SESSION` override from `CosmosClientBuilder`.
  Previously all reads were forced to `SESSION` regardless of the account's configured
  default. **Migration note:** accounts with a default of `STRONG` or `BOUNDED_STALENESS`
  will now serve reads at their configured level (higher latency / higher RU cost than
  before). Accounts configured to `SESSION` are unaffected. To restore the previous
  behaviour explicitly, set `multiclouddb.connection.consistencyLevel=SESSION`.

### Removed

- `CosmosConstants.CONSISTENCY_LEVEL_DEFAULT` (`public static final ConsistencyLevel`,
  previously `ConsistencyLevel.SESSION`) — removed without a deprecation cycle; the project
  is pre-release. Callers referencing this constant should use `ConsistencyLevel.SESSION`
  directly.

### Fixed

- Cosmos `@@PIT:<epoch-millis>` continuation strings with a non-numeric
  suffix (tampered / corrupted tokens) now surface as
  `CursorExpiredException` with `reason=MALFORMED` and provider context,
  instead of bubbling an unchecked `NumberFormatException` out of
  `readChanges`.
- **`BETWEEN` translation now wraps in parentheses** (`(c.field BETWEEN @lo AND @hi)`).
  Without the wrapping parens, Cosmos NoSQL's parser greedily binds the
  `BETWEEN`'s inner `AND` together with any trailing logical `AND`, producing
  a *"Syntax error, incorrect syntax near 'AND'"* `BadRequest` for predicates
  like `age BETWEEN @lo AND @hi AND marker = @m`. The output of
  `TranslatedQuery.whereClause()` is now parenthesised — backward-compatible
  at the query-execution level, but consumers that string-match the where
  clause should update their expectations.

### Fixed

- `now()` cursors no longer silently lose events written between cursor mint
  and first read. Previously, `listCursors()` and `now()`-hydrate carried a
  `CONT_FROM_NOW` sentinel; the first `readChanges()` call resolved
  `createForProcessingFromNow(range)` at *that* moment, so any events written
  between cursor mint and first read were skipped. `listCursors()` now eagerly
  executes a one-shot `createForProcessingFromNow(range)` "warmup" query per
  feed range to obtain a real Cosmos continuation token at mint time, and
  persists that token in the cursor. Subsequent reads use
  `createForProcessingFromContinuation(token)` against the captured bookmark.
  If the warmup query cannot produce a continuation, the reader falls back to
  a `@@PIT:<epoch-millis>` timestamp anchor (resolved via
  `createForProcessingFromPointInTime`); if that path is also unavailable
  (e.g., older SDK), the legacy `@@FROM_NOW` sentinel still works as before.
- `CursorExpiredException` thrown from `decodeRange()` (malformed cursor
  partitionId) now carries the active `providerId` instead of `null`, matching
  the surrounding `CursorExpiredException` paths.

## [0.1.0-beta.1] — 2026-04-23

### Added

- Default sort-key ordering: all Cosmos DB queries now have `ORDER BY c.id ASC`
  appended automatically when no explicit `ORDER BY` is set, ensuring consistent
  sort behavior with DynamoDB. Aggregate queries (`COUNT`, `SUM`, `MIN`, `MAX`,
  `AVG`) and `GROUP BY` queries are exempt — Cosmos DB rejects `ORDER BY` on them.
  Queries with an existing `ORDER BY` clause are not modified (idempotent).
  See `docs/compatibility.md` for custom indexing policy requirements and RU cost
  implications.

### Changed

- `applyResultSetControl()` now uses a word-boundary regex (`\bORDER\s+BY\b`)
  instead of `String.contains()` to detect existing `ORDER BY` clauses, preventing
  false positives from string literals (e.g. `WHERE c.note = 'place order by friday'`).

- The Cosmos client now stamps the outgoing `User-Agent` header with the
  canonical `multiclouddb-sdk-java/<version>` token. When
  `MulticloudDbClientConfig.Builder.userAgentSuffix(String)` is configured,
  the suffix is appended to the header.

#### Provider adapter and client

- `CosmosProviderAdapter` — SPI entry point auto-discovered via `ServiceLoader`;
  registers as `ProviderId.COSMOS` and supplies `CosmosProviderClient` and
  `CosmosExpressionTranslator`
- `CosmosProviderClient` — full `MulticloudDbProviderClient` implementation
  backed by the Azure Cosmos DB Java SDK v4

#### Authentication

- **Master-key auth** — when `connection.key` is provided, uses
  `CosmosClientBuilder.key()` for shared-key authentication
- **Azure Identity / Entra ID auth** — when no key is provided, uses
  `DefaultAzureCredential` (supporting Managed Identity, Azure CLI, environment
  variables, and the full Azure credential chain); optional `connection.tenantId`
  for multi-tenant scenarios

#### Connection modes

- **Gateway mode** (default) — HTTP-based routing through the Cosmos DB gateway
- **Direct mode** — TCP-based direct connectivity when
  `connection.connectionMode` is set to `"direct"`
- Default consistency level: `SESSION`

#### CRUD operations

- `create` — `CosmosContainer.createItem()` with automatic injection of Cosmos
  `id` field (from sort key or partition key) and `partitionKey` field
- `read` — `CosmosContainer.readItem()` with 404 mapped to `null` return
- `update` — `CosmosContainer.replaceItem()` with key-field injection
- `upsert` — `CosmosContainer.upsertItem()` with key-field injection
- `delete` — `CosmosContainer.deleteItem()` with idempotent 404 handling

#### Query support

- **Native Cosmos SQL passthrough** — execute raw Cosmos SQL via
  `QueryRequest.nativeExpression()`
- **Portable expression translation** — automatic translation of the portable
  query AST to Cosmos SQL via `CosmosExpressionTranslator`
- **Partition-key scoping** — when `QueryRequest.partitionKey()` is set,
  queries are scoped to a single logical partition via
  `CosmosQueryRequestOptions.setPartitionKey()`
- **Continuation-token pagination** — uses Cosmos DB's native continuation
  tokens for efficient server-side paging with configurable page size (default:
  100)
- Named parameter binding with automatic `@` prefix normalization

#### Expression translation (`CosmosExpressionTranslator`)

- Translates the portable AST to Cosmos SQL `SELECT * FROM c WHERE ...` syntax
- All fields prefixed with the Cosmos alias `c.` (e.g., `c.age >= @minAge`)
- Comparison, logical, NOT, IN, BETWEEN expressions fully supported
- Portable function mapping:
  - `starts_with` → `STARTSWITH(...)`
  - `contains` → `CONTAINS(...)`
  - `field_exists` → `IS_DEFINED(...)`
  - `string_length` → `LENGTH(...)`
  - `collection_size` → `ARRAY_LENGTH(...)`

#### Error mapping (`CosmosErrorMapper`)

- Maps Cosmos DB HTTP status codes to portable error categories:
  - `400` → `INVALID_REQUEST`
  - `401` → `AUTHENTICATION_FAILED`
  - `403` → `AUTHORIZATION_FAILED`
  - `404` → `NOT_FOUND`
  - `409`, `412` → `CONFLICT`
  - `429` → `THROTTLED`
  - `449`, `500`, `502`, `503` → `TRANSIENT_FAILURE`
- Retryable flag set for `429`, `449`, `500`, `502`, `503`
- Captures Cosmos substatus code, activity ID, and request charge in provider
  details

#### Diagnostics (`CosmosDiagnosticsLogger`)

- **Point operations** — DEBUG-level logging of operation, database, container,
  activity ID, status code, RU charge, and latency; auto-escalates to WARN when
  latency exceeds 10 ms or RU charge exceeds 10
- **Query pages** — DEBUG-level logging of RU charge, item count, continuation
  token presence, and latency; auto-escalates to WARN above 100 ms / 100 RU,
  or ERROR above 1000 ms
- **Exceptions** — ERROR-level logging with HTTP status, substatus, activity ID,
  and full native diagnostics string
- Optional **native SDK diagnostics** pass-through when
  `MulticloudDbClientConfig.nativeDiagnosticsEnabled()` is `true`

#### Provisioning

- `ensureDatabase` — idempotent database creation via
  `CosmosClient.createDatabaseIfNotExists()`
- `ensureContainer` — idempotent container creation with partition key path
  `/partitionKey`

#### Capabilities

- Reports all 13 well-known capabilities as supported with Cosmos-specific
  notes: continuation-token paging, cross-partition query, transactional batch,
  bulk operations, configurable consistency (including strong), native SQL
  query, change feed, portable expression translation, LIKE operator, ORDER BY,
  ENDS_WITH, REGEX_MATCH, and UPPER/LOWER case functions

#### Dependencies

- Azure Cosmos DB Java SDK v4 (`azure-cosmos 4.78.0`)
- Azure Identity (`azure-identity 1.18.2`)

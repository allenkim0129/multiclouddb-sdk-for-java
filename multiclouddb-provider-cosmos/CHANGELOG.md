# Changelog — multiclouddb-provider-cosmos

All notable changes to the `multiclouddb-provider-cosmos` module will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this module adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Portable `patch(...)` implemented with the Cosmos DB Patch API (`CosmosContainer.patchItem`), applying all operations atomically in one request. `CosmosCapabilities` declares `PATCH_CAP` and `NESTED_PATCH_CAP` — Cosmos patch paths address the JSON document tree directly, so nested fields are patchable without rewriting the parent object.
- Stored-state requirements are enforced by Cosmos's **server-side conditional patch** (`CosmosPatchItemRequestOptions.setFilterPredicate`) rather than a potentially stale pre-write read. `REPLACE`, `REMOVE`, and nested operations contribute `IS_DEFINED` terms; `INCREMENT` also contributes `IS_NUMBER` and, for an integral delta, a `BETWEEN` bound. The predicate additionally rejects the SDK-managed `ttl` field because native Cosmos patch advances `_ts` and would restart that relative expiry. Successful PATCH now uses one provider request.
- Patch-specific error normalisation classifies an HTTP **412** or untyped **400** from a follow-up point read carrying the rejecting request's session token. A vanished document/path reports `NOT_FOUND`, a nonnumeric or out-of-range increment reports `INVALID_REQUEST`, an SDK-managed TTL reports `UNSUPPORTED_CAPABILITY(reason="patch_on_ttl_item_unsupported")`, and a rejection current state cannot explain reports `CONFLICT`. That `CONFLICT` is the only patch category marked `retryable() == true` — the conditional patch applied no operation, so an identical retry cannot double-apply an `INCREMENT` — while every other patch category names a deterministic cause and stays non-retryable. The exact Cosmos emulator behavior remains unverified pending T192.
- Items carrying the SDK-managed `ttl` field are rejected atomically with `UNSUPPORTED_CAPABILITY` instead of having their relative expiry silently restarted by the `_ts` advance that native Cosmos patch performs.

- `INCREMENT` selects Cosmos's integer or floating-point `increment` overload from the delta's **value** rather than its boxed Java type, so `increment(path, 1.0)` stores the same number as `increment(path, 1)` and matches the providers whose column type forces an integral result.
- Accepted `INCREMENT` deltas are normalized by the shared portable numeric domain before the Cosmos Patch API overload is selected. Integral deltas and results are signed 64-bit values, while fractional deltas are one canonical finite IEEE-754 `double`, so no accepted input can be silently narrowed by `longValue()`.

### Breaking changes

- **A document field named `data` is now rejected.** `create`, `update`, and `upsert` throw `MulticloudDbException(INVALID_REQUEST)` before `CosmosProviderClient` dispatch when the document carries a top-level `data` key in **any** casing (`data`, `Data`, `DATA`, `dAtA`); `PatchOperation` rejects the `/data` path on the same rule. Cosmos DB itself reserves no such attribute — the restriction is portability-driven: `data` is an SDK-managed Spanner metadata column, and Spanner resolves column names case-insensitively, so a document Cosmos would happily store could not be moved to Spanner.
  **Migration — do this before upgrading:** rename any application-owned `data` field (for example to `payload` or `applicationData`) **and rewrite the affected documents with the renamed key**, then upgrade. There is no alias, no compatibility flag, and no opt-out; a document that still carries `data` fails at the client boundary before any Cosmos request is issued. See [`docs/guide.md` → *Document Field Injection*](../docs/guide.md#document-field-injection).

### Fixed

- **Portable query filters on fields whose names are SQL keywords no longer produce a syntax error.** `CosmosExpressionTranslator` emitted a bare `c.value` identifier, so a filter or projection on a field named `value` (or any other Cosmos SQL keyword) failed at parse time even though the SDK accepts the name everywhere else. Field references are now emitted in quoted bracket form (`c["value"]`), one quoted segment per path component, with `\` and `"` escaped. This closes a divergence in which the same portable query succeeded on one provider and failed on another purely because of a field name.
- **Concurrent patches of disjoint fields no longer conflict because of an item-wide ETag.** Strict operations now use path-scoped server-side filter predicates, so writes to unrelated fields remain independent while addressed-path existence and integral increment bounds are still checked atomically.
- **Stale pre-write classification was removed.** Required paths, numeric type, and integral bounds are evaluated atomically by the conditional patch. A rejected condition or untyped increment error triggers a session-token follow-up read so vanished documents/paths report `NOT_FOUND`, retyped numeric targets or proven overflow report `INVALID_REQUEST`, TTL-bearing items report `UNSUPPORTED_CAPABILITY`, and an unexplained failed condition reports `CONFLICT`.
- **Narrowed residual for a raced `INCREMENT`.** The reclassification above is evidence-based, so it is deliberately *not* claimed to eliminate the race entirely. Two cases remain, both of which fall back to the previous `INVALID_REQUEST` mapping rather than inventing a `NOT_FOUND` the adapter cannot substantiate: (1) the re-read shows the target present and numeric — the `400` had some cause other than a missing or retyped field, or a third writer recreated the field between the rejected write and the re-read (an ABA the adapter cannot distinguish from a genuine bad request); and (2) the follow-up read itself fails, so no current state is available and the rejected write stays authoritative. The residual is bounded to "the cause could not be determined" and no longer covers the ordinary raced-delete case, which was the divergence callers actually hit. DynamoDB has the equivalent unprovable-race fallback (it returns `CONFLICT` when its before-image does not explain the rejected condition); Cosmos matches it for an unprovable **412**, where a real precondition was falsified server-side, and falls back to `INVALID_REQUEST` only for an unprovable **400**, because a Cosmos `400` whose preconditions all currently hold is more likely a genuinely invalid request than a race. Emulator verification of both fallbacks is pending T192.

### Documentation

- `patch()` is documented as a payload-size, round-trip, and lost-update-safety optimization, not a guaranteed RU saving. RU cost is workload- and indexing-dependent; no replace-equivalence claim is made.

## [0.1.0-beta.2] — 2026-06-17

> **Requires `multiclouddb-api` 0.1.0-beta.2 or later** — this release consumes API surface (change-feed cursors, `CLIENT_CLOSED` envelope, `EXTENDED_CHANGE_FEED_HISTORY` capability) introduced in API beta.2. The dependency is pinned in the published POM.

### Added

- Change-feed reader backed by `CosmosContainer.queryChangeFeed(...)` and `getFeedRanges()`. `listCursors` mints one cursor per feed range at the live tip via a one-item warmup query that captures a real continuation token (with a `@@PIT:<epoch-millis>` fallback for older SDKs). `readChanges` drains one page per call, rotates the partition list across ranges so multi-range cursors are not starved, and uses All-Versions-and-Deletes (AVAD) mode so `ChangeEvent.type()` distinguishes `CREATE` / `UPDATE` / `DELETE`. The target container must be provisioned with an AVAD `ChangeFeedPolicy`; non-AVAD containers surface the Cosmos 400 BadRequest through the normalised envelope on the first read. HTTP 410 GONE on `queryChangeFeed` is mapped to `CursorExpiredException(reason=PROVIDER_TRIMMED)`.
- Extended-retention provisioning: `CosmosProviderClient.ensureContainer(address)` provisions an AVAD `ChangeFeedPolicy` carrying the duration from `ChangeFeedConfig.extendedRetention(...)` when the user opted in, and reads back the active policy after `createContainerIfNotExists(...)` — throwing `UNSUPPORTED_CAPABILITY(reason="extended_retention_not_enacted")` (with `requestedRetention` and `activeRetention` in `providerDetails`) when a pre-existing container's retention does not match the request. A 400 BadRequest whose message fingerprint indicates the Cosmos account lacks Continuous Backup is re-mapped to `UNSUPPORTED_CAPABILITY(reason="continuous_backup_required")` so callers do not have to substring-match raw messages. `CosmosCapabilities` declares `EXTENDED_CHANGE_FEED_HISTORY_CAP` (up to 30 days via Continuous Backup; 7d minimum).
- `consistencyLevel` connection config key for opt-in client-level read consistency override. Valid case-insensitive values: `STRONG`, `BOUNDED_STALENESS`, `SESSION`, `CONSISTENT_PREFIX`, `EVENTUAL`. When absent, reads inherit the Cosmos DB account's configured default. See `docs/configuration.md` — *Consistency Level*.
- Typed `CLIENT_CLOSED` envelope on every post-close CRUD / query / provisioning / change-feed entry point, replacing leaked `IllegalStateException`s from azure-cosmos. `close()` is idempotent under concurrent callers; the underlying `cosmosClient.close()` is invoked exactly once.

### Changed

- Removed the hardcoded `ConsistencyLevel.SESSION` override from `CosmosClientBuilder`. Accounts with a default of `STRONG` or `BOUNDED_STALENESS` will now serve reads at their configured level (higher latency / RU cost than before). Accounts configured to `SESSION` are unaffected. To restore the previous behaviour explicitly, set `multiclouddb.connection.consistencyLevel=SESSION`.
- `BETWEEN` translation now wraps in parentheses (`(c.field BETWEEN @lo AND @hi)`). Without this, Cosmos NoSQL's parser binds the inner `AND` together with any trailing logical `AND`, producing a `BadRequest` for predicates like `age BETWEEN @lo AND @hi AND marker = @m`. The output of `TranslatedQuery.whereClause()` is now parenthesised.

### Removed

- `CosmosConstants.CONSISTENCY_LEVEL_DEFAULT` (`public static final ConsistencyLevel`, previously `ConsistencyLevel.SESSION`) — removed without a deprecation cycle; the project is pre-release. Callers referencing this constant should use `ConsistencyLevel.SESSION` directly.

### Documentation

- `delete()` of a missing key is documented as a silent no-op (idempotent); the Cosmos provider continues to swallow the native 404.

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
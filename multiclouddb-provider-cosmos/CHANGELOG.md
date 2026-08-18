# Changelog — multiclouddb-provider-cosmos

All notable changes to the `multiclouddb-provider-cosmos` module will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this module adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Portable `patch(...)` implemented with the Cosmos DB Patch API (`CosmosContainer.patchItem`), applying all operations atomically in one request. `CosmosCapabilities` declares `PATCH_CAP` and `NESTED_PATCH_CAP` — Cosmos patch paths address the JSON document tree directly, so nested fields are patchable without rewriting the parent object.
- Strict-path, nested-path, and numeric-result state is validated by a point read before the native patch. The write itself is then guarded by Cosmos's **server-side conditional patch** (`CosmosPatchItemRequestOptions.setFilterPredicate`) — a *path-scoped* precondition of the form `FROM c WHERE IS_DEFINED(c["field"]) AND ...` — attached for every operation whose native Cosmos translation cannot enforce the portable contract by itself. `REPLACE`, `REMOVE`, and nested operations contribute an `IS_DEFINED` term over the path they address. `INCREMENT` contributes that existence term plus, for an integral delta, a `BETWEEN` bound on the current value — the Cosmos spelling of the condition DynamoDB attaches to its own increment — so the portable signed-64 result range is enforced atomically with the write instead of only at the validating read. Every term stays *path*-scoped, so `CosmosPatchOperations.increment` remains atomic server-side and concurrent increments of an in-range counter all land, matching DynamoDB's `SET x = x + :v` and Spanner's retryable read-write transaction.
- Patch-specific error normalisation returns `NOT_FOUND` for a missing required path and `INVALID_REQUEST` for a nonnumeric target or proven signed-64 result overflow during that pre-read. A HTTP **412** from the conditional patch is classified from current state rather than assumed: the adapter re-reads the document and reuses the same precondition classifier, so a vanished path reports `NOT_FOUND`, a retyped target or an out-of-range increment result reports `INVALID_REQUEST`, and a rejection current state cannot explain reports `CONFLICT`, exactly how DynamoDB classifies its own `ConditionalCheckFailedException` from the before-image. The exact Cosmos emulator 412 behavior remains unverified pending T192.
- `CosmosCapabilities` declares **`EXACT_FRACTIONAL_INCREMENT` as unsupported**: Cosmos evaluates a fractional `INCREMENT` as a JSON IEEE-754 binary64 number, so accumulated fractional results may differ in the last ulp from DynamoDB's exact-decimal `N` arithmetic (`0.1` incremented by `0.2` stores `0.30000000000000004` here and `0.3` on DynamoDB). Integral increments remain exact. The declaration is informational — Cosmos accepts every in-domain fractional delta and never raises `UNSUPPORTED_CAPABILITY` for it.

- `INCREMENT` selects Cosmos's integer or floating-point `increment` overload from the delta's **value** rather than its boxed Java type, so `increment(path, 1.0)` stores the same number as `increment(path, 1)` and matches the providers whose column type forces an integral result.
- Accepted `INCREMENT` deltas are normalized by the shared portable numeric domain before the Cosmos Patch API overload is selected. Integral deltas and results are signed 64-bit values, while fractional deltas are one canonical finite IEEE-754 `double`, so no accepted input can be silently narrowed by `longValue()`.

### Changed

- **`IN` and `BETWEEN` with a `null` operand now translate to `FALSE`.** A `null` literal — or a named parameter bound to `null` — appearing in an `IN` list or as either `BETWEEN` bound previously reached Cosmos NoSQL as a live comparand, where the outcome was engine-defined rather than portable. `CosmosExpressionTranslator` now short-circuits the whole predicate to `FALSE`, so "a null operand matches nothing" holds identically on Cosmos DB, DynamoDB, and Spanner. Lists whose members are all non-null are unaffected.
- A degenerate empty `IN` list also translates to `FALSE` instead of the syntactically invalid `c.field IN ()`. `InExpression`'s canonical constructor already rejects an empty list, so this is defence-in-depth for an AST built around that record.

### Breaking changes

- **A document field named `data` is now rejected.** `create`, `update`, and `upsert` throw `MulticloudDbException(INVALID_REQUEST)` before `CosmosProviderClient` dispatch when the document carries a top-level `data` key in **any** casing (`data`, `Data`, `DATA`, `dAtA`); `PatchOperation` rejects the `/data` path on the same rule. Cosmos DB itself reserves no such attribute — the restriction is portability-driven: `data` is the SDK-managed Spanner document envelope, and Spanner resolves column names case-insensitively, so a document Cosmos would happily store could not be moved to Spanner.
  **Migration — do this before upgrading:** rename any application-owned `data` field (for example to `payload` or `applicationData`) **and rewrite the affected documents with the renamed key**, then upgrade. There is no alias, no compatibility flag, and no opt-out; a document that still carries `data` fails at the client boundary before any Cosmos request is issued. See [`docs/guide.md` → *Document Field Injection*](../docs/guide.md#document-field-injection).
- **`field_exists` changed meaning; existing queries can return different rows.** `field_exists(f)` now translates to `IS_DEFINED(c.f) AND NOT IS_NULL(c.f)` — *present **and** non-null* — where it previously translated to a bare `IS_DEFINED(c.f)` and therefore also matched a field explicitly stored as JSON `null`. A filter that relied on the old behaviour now returns fewer rows, and `NOT field_exists(f)` returns more. Re-check any filter using `field_exists` on a field legitimately stored as `null`; use an explicit `f = null` comparison to keep matching it.

### Fixed

- **Query fields named after Cosmos NoSQL reserved words no longer fail with a syntax error.** `CosmosExpressionTranslator` emitted a dotted accessor (`c.value`), so a document field called `value` — or any other reserved word — failed the request with ``Syntax error, incorrect syntax near 'value'`` while the identical portable expression succeeded on Spanner, which routes every field through a quoted JSON path. Every field reference is now emitted as a quoted property accessor (`c["value"]`, and `c["address"]["city"]` for a nested path), the same accessor `patch` already used for its filter predicate. Each dotted segment is quoted separately so nesting is preserved, and embedded `"` and `\` are escaped.
- **Concurrent patches of disjoint fields no longer fail on Cosmos alone.** `patch()` guarded strict operations with an `If-Match` ETag taken from its validating point read. `If-Match` is *item*-scoped, so it failed on **any** concurrent mutation of the item — including one touching a completely different field that left every precondition the adapter validated still true. Two threads each calling `patch(key, [replace("/status", "live")])` both succeed on DynamoDB (whose condition is only `attribute_exists(...)`) and on Spanner (whose `readWriteTransaction` auto-retries `ABORTED`), while one failed on Cosmos with a non-retryable `CONFLICT` — a category the portable `MulticloudDbClient.patch` contract does not document, and a silent divergence not declared through `CapabilitySet`. The ETag is replaced by Cosmos's server-side conditional patch, whose filter predicate is *path*-scoped (`FROM c WHERE IS_DEFINED(c["status"])`) and evaluated atomically with the mutation, so a concurrent write to an unaddressed field is harmless while "the addressed field must exist" is still enforced. Mixed patches benefit likewise: `[increment("/value", 1), replace("/status", "live")]` makes each operation contribute only its own path-scoped terms, so the lost-increment problem patch exists to avoid does not return when an increment shares a call with a strict operation. A failed predicate (HTTP 412) is classified from a re-read of current state rather than assumed to mean one thing, so it yields the same `NOT_FOUND` / `INVALID_REQUEST` / `CONFLICT` categories DynamoDB derives from its before-image. Request cost is unchanged: the same single point read still runs, and the precondition rides along on the write. `CosmosErrorMapper`'s shared status table was aligned to match — 412 now yields `NOT_FOUND` instead of `CONFLICT` for every operation, which is unambiguous because the adapter sends no `If-Match` anywhere, so a path-scoped predicate is the only precondition any Cosmos request can carry; `patch` overrides that default with its own evidence-based classification. The category-aware `CosmosErrorMapper.map(CosmosException, String, MulticloudDbErrorCategory)` overload added earlier in this unreleased line existed solely to force that `CONFLICT`, and is removed rather than shipped unused; every remaining call site already uses the two-argument form.
- **A raced `INCREMENT` now reports `NOT_FOUND`, not `INVALID_REQUEST`.** An `INCREMENT` previously carried no precondition at all, so a target deleted or retyped *between* the validating read and the write was classified by Cosmos's own untyped `400` and surfaced as `INVALID_REQUEST`, where DynamoDB re-reads the before-image and Spanner sees true state inside its transaction — both reporting `NOT_FOUND`. A caller branching on `NOT_FOUND` to recreate the field therefore behaved differently on Cosmos, contrary to FR-185. The adapter now re-reads on an untyped `400` and reuses the same precondition classifier as the pre-read, so a vanished document or a vanished target reports `NOT_FOUND` and a retyped target reports `INVALID_REQUEST` — the categories the peers produce for the same state. FR-185 therefore holds on all three providers for every raced state the adapter can observe. `INCREMENT` now also carries a path-scoped result bound (see *Added*), so a raced overflow is rejected atomically rather than stored, and reclassified envelopes keep the rejecting request's Cosmos status code, activity id, and request charge.
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
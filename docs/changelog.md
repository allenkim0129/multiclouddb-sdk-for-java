# Changelog

All notable changes to the Multicloud DB SDK modules.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and all modules adhere to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## multiclouddb-api

### [Unreleased]

**Added:**

- Portable **patch** operation for field-level partial updates: `MulticloudDbClient.patch(ResourceAddress, MulticloudDbKey, List<PatchOperation>[, OperationOptions])`, the new public type `com.multiclouddb.api.PatchOperation` (`SET` / `REPLACE` / `REMOVE` / `INCREMENT`, addressed by JSON Pointer), and `OperationNames.PATCH`. Cosmos DB and DynamoDB execute native partial writes; Spanner updates its document envelope inside a retryable transaction. No provider exposes a non-transactional client-side read-modify-write window, and all operations in one call apply atomically. Patch payloads are limited to 399 KB (408,576 bytes) using every operation's deterministic serialized type, path, and optional value (including `REMOVE` paths). Integral deltas and their resulting values must fit signed 64-bit range; fractional deltas must be finite, have magnitude at most 9,007,199,254,740,991, and exactly round-trip through a `double`. The provider SPI method `MulticloudDbProviderClient.patch(...)` defaults to `UNSUPPORTED_CAPABILITY` so existing adapters compile unchanged.
- New well-known capabilities `Capability.PATCH` (supported on Cosmos, DynamoDB and Spanner) and `Capability.NESTED_PATCH` (supported on Cosmos and DynamoDB; **unsupported on Spanner**, where nested JSON traversal is deferred from the v1 compatibility scope — a nested path fails fast with `UNSUPPORTED_CAPABILITY` rather than silently rewriting the parent). Providers now declare 20 capabilities.
- New well-known capability `Capability.EXACT_FRACTIONAL_INCREMENT` — **supported on DynamoDB, unsupported on Cosmos DB and Spanner**. It reports how a *fractional* `INCREMENT` accumulates: DynamoDB evaluates `field = field + delta` server-side in its `N` type (exact decimal, 38 significant digits), while Cosmos DB and Spanner evaluate in IEEE-754 binary64. `{"v": 0.1}` incremented by `0.2` stores exactly `0.3` on DynamoDB and `0.30000000000000004` on Cosmos DB and Spanner. Integral increments stay exact everywhere. The capability is **informational only** — nothing is rejected and no `UNSUPPORTED_CAPABILITY` is raised; callers that need bit-identical fractional totals branch on it.
- `MulticloudDbClient.MAX_PATCH_OPERATIONS` (10) - the Cosmos DB native per-request cap, enforced uniformly so a patch that succeeds on one provider cannot fail on another. The rest of the v1 contract is equally narrow by design: the target document must already exist (`NOT_FOUND` otherwise); paths must be disjoint (including case-only aliases); `REPLACE` / `REMOVE` / `INCREMENT` require the target field to exist; `SET` never creates missing intermediate objects; array-index segments, JSON Pointer `~` escapes, and key/TTL/`data` field names are rejected as `INVALID_REQUEST`; and `OperationOptions.ttlSeconds()` is ignored on every provider.
- `data` is now a case-insensitive SDK-reserved *patch path*: `/data`, `/Data`, and any other casing are rejected as `INVALID_REQUEST`. See *Breaking changes* for the matching `create` / `update` / `upsert` rejection.

- Portable change-feed API in `com.multiclouddb.api.changefeed`: `ChangeFeedCursor` (opaque, persistable via `toToken()` / `fromToken(...)` with a `now()` live-tip sentinel), `ChangeFeedPage` (events + `nextCursor` + `hasMore`/`terminal`), `ChangeEvent` (with stable `providerEventId` for dedup), `ChangeType`, and `CursorExpiredException`. Two new entry points on `MulticloudDbClient`: `listCursors(ResourceAddress)` and `readChanges(ResourceAddress, ChangeFeedCursor[, OperationOptions])`. Provider SPI methods default to `UNSUPPORTED_CAPABILITY` so existing adapters compile unchanged. The cursor wire format is opaque, version-tagged Base64URL JSON; the 24-hour portable baseline is enforced client-side on the token''s last-issued timestamp. `OperationOptions.timeout()` is not enforced on the change-feed path in this release.
- New error category `MulticloudDbErrorCategory.CURSOR_EXPIRED` carrying a canonical `providerDetails.reason` set (`TOKEN_AGED_OUT`, `PROVIDER_TRIMMED`, `ITERATOR_EXPIRED`, `MALFORMED`, `VERSION_UNSUPPORTED`, `PROVIDER_MISMATCH`, `RESOURCE_MISMATCH`), exported as public `CursorTokenCodec.REASON_*` constants.
- New error category `MulticloudDbErrorCategory.CLIENT_CLOSED` surfaced by a `DefaultMulticloudDbClient` post-close guard on every public entry point (replaces provider-specific `IllegalStateException` leaks). `MulticloudDbClient.close()` is now idempotent.
- Extended change-feed retention opt-in: `ChangeFeedConfig.extendedRetention(Duration)` (validates `> 24h`), wired into `MulticloudDbClientConfig.changeFeed(...)`, plus the new `Capability.EXTENDED_CHANGE_FEED_HISTORY`. The factory''s build-time gate refuses to instantiate a client whose provider does not declare the capability, surfacing `UNSUPPORTED_CAPABILITY(reason="extended_retention_unavailable")` before any I/O. The cursor token wire format carries an optional `"e"` field stamping the opted-in retention so a persisted cursor under a 7-day opt-in can be resumed beyond 24h up to the configured window without `TOKEN_AGED_OUT`; older tokens (no `"e"`) keep the 24h floor.
- `OperationNames.LIST_CURSORS`, `READ_CHANGES`, `PROVISION_SCHEMA` propagated through `MulticloudDbError.operation()` and `OperationDiagnostics`.

**Breaking changes:**

- **A document field named `data` is now rejected.** `create`, `update`, and `upsert` throw `MulticloudDbException(INVALID_REQUEST)` before provider dispatch when the document contains a top-level `data` key in **any** casing (`data`, `Data`, `DATA`, `dAtA`). `data` is the SDK-managed Spanner document envelope, and Spanner resolves column names case-insensitively, so allowing the name on Cosmos DB or DynamoDB would make the same document unportable.
  **Migration — do this before upgrading:** rename any application-owned `data` field (for example to `payload` or `applicationData`) **and rewrite the affected documents with the renamed key**, then upgrade. There is no alias, no compatibility flag, and no opt-out. See [guide.md → *Document Field Injection*](guide.md#document-field-injection).
- **`update` is a full document replacement, not a merge.** Fields omitted from the supplied document disappear from both `read()` and portable queries on every provider — Spanner writes a fresh authoritative envelope from only the replacement payload, so omitted fields cannot leak through stale physical columns. Callers that relied on `update` preserving untouched fields must send the complete document or switch those calls to `patch()`.
- **`field_exists` and scalar comparisons changed meaning; existing queries can return different rows.** `field_exists(f)` now means *present **and** non-null* on all three providers (it previously matched a field that was present but explicitly `null`), and scalar comparisons are JSON-type-sensitive: a numeric or boolean operand no longer coerces to match a string value, including inside `IN` and `BETWEEN`. `ExpressionValidator` rejects an `IN` / `BETWEEN` list whose non-null members mix value kinds, surfacing `INVALID_REQUEST` from `query(...)` instead of a provider-specific coercion; a `null` operand is exempt and keeps its portable "no match" semantics.
  **Before upgrading, re-check:** filters using `field_exists` on a field legitimately stored as `null` (use an explicit `f = null` comparison to keep matching it); any comparison, `IN`, or `BETWEEN` that relied on `"5"` matching `5` or `"true"` matching `true`; and any `IN` / `BETWEEN` list mixing numbers, strings, or booleans, which now fails validation rather than silently matching a subset.

**Documentation:**

- `MulticloudDbClient.delete(...)` is documented as idempotent on every provider — a missing key is a silent no-op. Callers needing to detect a missing key should use `read(...)`.

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

**Fixed:**

- A `null` entry inside a patch operation list is now `INVALID_REQUEST` instead of `PROVIDER_ERROR`. `MulticloudDbClient.patch` snapshotted the caller's list with `List.copyOf`, which rejects a null *element* with a raw `NullPointerException`, so `PatchValidator`'s ``patch operations must not contain null entries`` rule was unreachable. The snapshot now tolerates a null entry and lets the adapter's validator report the portable category.

---

## multiclouddb-provider-cosmos

### [Unreleased]

**Added:**

- Portable `patch(...)` implemented with the Cosmos DB Patch API (`CosmosContainer.patchItem`), applying all operations atomically in one request. `CosmosCapabilities` declares `PATCH` and `NESTED_PATCH`; Cosmos patch paths address the JSON document tree directly, so nested fields are patchable without rewriting the parent. Strict (`REPLACE` / `REMOVE` / `INCREMENT`) or nested patches first point-read the document and validate required-path and numeric state.
- Strict (`REPLACE` / `REMOVE` / nested non-increment) operations carry a server-side **path-scoped filter predicate** (an `IS_DEFINED` existence check over each addressed path) rather than an `If-Match` ETag guard, so a concurrent write to an unaddressed field cannot fail the patch and concurrency alone never produces `CONFLICT`. `INCREMENT` carries an existence term plus, for an integral delta, a `BETWEEN` bound on the current value — the Cosmos spelling of the condition DynamoDB attaches to its own increment — so the portable signed-64 result range is enforced atomically with the write rather than only at the validating read. Both terms are path-scoped, so `CosmosPatchOperations.increment` stays atomic server-side and concurrent increments of an in-range counter all land. Residual, documented trade-off: a rejection whose cause current state cannot prove falls back to `INVALID_REQUEST` for an untyped `400` and to `CONFLICT` for a `412`, rather than inventing a `NOT_FOUND`. Non-raced classification is identical on all three providers.
- Patch-specific error normalisation: the classifying pre-read returns `NOT_FOUND` for a missing document/path and `INVALID_REQUEST` for a nonnumeric target or proven overflow. A failed path-scoped predicate (HTTP 412) is classified from a re-read of current state, so it yields the same `NOT_FOUND` / `INVALID_REQUEST` / `CONFLICT` categories DynamoDB derives from its before-image. The exact Cosmos emulator status behavior remains unverified pending T192. Patch billing is workload- and indexing-dependent; no replace-equivalent or reduced-RU claim is made.
- `CosmosCapabilities` declares `EXACT_FRACTIONAL_INCREMENT` as **unsupported** — Cosmos evaluates a fractional `INCREMENT` in IEEE-754 binary64, so `0.1` incremented by `0.2` stores `0.30000000000000004` (DynamoDB's exact-decimal `N` arithmetic stores `0.3`). Integral increments remain exact, and the declaration is informational: no fractional increment is ever rejected.

- Change-feed reader backed by `CosmosContainer.queryChangeFeed(...)` and `getFeedRanges()`. `listCursors` mints one cursor per feed range at the live tip via a one-item warmup query that captures a real continuation token (with a `@@PIT:<epoch-millis>` fallback for older SDKs). `readChanges` drains one page per call, rotates the partition list across ranges so multi-range cursors are not starved, and uses All-Versions-and-Deletes (AVAD) mode so `ChangeEvent.type()` distinguishes `CREATE` / `UPDATE` / `DELETE`. The target container must be provisioned with an AVAD `ChangeFeedPolicy`. HTTP 410 GONE on `queryChangeFeed` is mapped to `CursorExpiredException(reason=PROVIDER_TRIMMED)`.
- Extended-retention provisioning: `CosmosProviderClient.ensureContainer(address)` provisions an AVAD `ChangeFeedPolicy` carrying the duration from `ChangeFeedConfig.extendedRetention(...)` when the user opted in, and reads back the active policy — throwing `UNSUPPORTED_CAPABILITY(reason="extended_retention_not_enacted")` when a pre-existing container''s retention does not match. A 400 BadRequest whose message fingerprint indicates the Cosmos account lacks Continuous Backup is re-mapped to `UNSUPPORTED_CAPABILITY(reason="continuous_backup_required")`. `CosmosCapabilities` declares `EXTENDED_CHANGE_FEED_HISTORY_CAP` (up to 30 days via Continuous Backup; 7d minimum).
- `consistencyLevel` connection config key for opt-in client-level read consistency override (`STRONG`, `BOUNDED_STALENESS`, `SESSION`, `CONSISTENT_PREFIX`, `EVENTUAL`). When absent, reads inherit the account''s configured default.
- Typed `CLIENT_CLOSED` envelope on every post-close entry point, replacing leaked `IllegalStateException`s from azure-cosmos. `close()` is idempotent under concurrent callers.

**Changed:**

- Removed the hardcoded `ConsistencyLevel.SESSION` override from `CosmosClientBuilder`. Accounts with a default of `STRONG` or `BOUNDED_STALENESS` will now serve reads at their configured level. To restore the previous behaviour, set `multiclouddb.connection.consistencyLevel=SESSION`.
- `BETWEEN` translation now wraps in parentheses (`(c["field"] BETWEEN @lo AND @hi)`) to avoid a Cosmos NoSQL parser ambiguity with trailing `AND`.

**Breaking changes:**

- **A document field named `data` is now rejected.** `create`, `update`, and `upsert` throw `MulticloudDbException(INVALID_REQUEST)` before `CosmosProviderClient` dispatch when the document carries a top-level `data` key in **any** casing (`data`, `Data`, `DATA`, `dAtA`); `PatchOperation` rejects the `/data` path on the same rule. Cosmos DB itself reserves no such attribute — `data` is the SDK-managed Spanner document envelope, and Spanner resolves column names case-insensitively, so a document Cosmos would happily store could not be moved to Spanner.
  **Migration — do this before upgrading:** rename any application-owned `data` field (for example to `payload` or `applicationData`) **and rewrite the affected documents with the renamed key**, then upgrade. There is no alias, no compatibility flag, and no opt-out. See [guide.md → *Document Field Injection*](guide.md#document-field-injection).
- **`field_exists` changed meaning; existing queries can return different rows.** `field_exists(f)` now translates to `IS_DEFINED(c.f) AND NOT IS_NULL(c.f)` — *present **and** non-null* — where it previously translated to a bare `IS_DEFINED(c.f)` and therefore also matched a field explicitly stored as JSON `null`. A filter that relied on the old behaviour now returns fewer rows, and `NOT field_exists(f)` returns more. Use an explicit `f = null` comparison to keep matching a field legitimately stored as `null`.

**Removed:**

- `CosmosConstants.CONSISTENCY_LEVEL_DEFAULT` — removed without a deprecation cycle (pre-release). Callers should use `ConsistencyLevel.SESSION` directly.

**Documentation:**

- `delete()` of a missing key is documented as a silent no-op (idempotent); the Cosmos provider continues to swallow the native 404.

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

**Fixed:**

- Query fields named after Cosmos NoSQL reserved words no longer fail with a syntax error. Every field reference is emitted as a quoted property accessor (`c["value"]`, `c["address"]["city"]`) instead of a dotted one, so a document field called `value` behaves the same here as on Spanner.

---

## multiclouddb-provider-dynamo

### [Unreleased]

**Added:**

- Portable `patch(...)` implemented with `UpdateItem`: the operation list is compiled into a single `UpdateExpression` (`SET ... REMOVE ...`) so DynamoDB applies every change atomically in one request. `DynamoCapabilities` declares `PATCH` and `NESTED_PATCH`; document paths (`a.b.c`) address nested map attributes directly. Capacity use is item- and configuration-dependent, so no `PutItem` billing-equivalence claim is made.
- The portable contract is enforced with a `ConditionExpression`, because `UpdateItem` would otherwise *create* a missing item and a native `REMOVE` on a missing attribute is a silent no-op - both diverge from Cosmos. The adapter always asserts `attribute_exists(partitionKey)` and adds an `attribute_exists(<path>)` term for every `REPLACE` / `REMOVE` / `INCREMENT`. A condition failure is classified from its old image as `NOT_FOUND` (missing document/path), `INVALID_REQUEST` (nonnumeric target or overflow), or `CONFLICT` (concurrent state change). Path segments are always compiled to `ExpressionAttributeNames` placeholders so field names colliding with DynamoDB reserved words work unchanged, and a `REMOVE`-only patch omits `ExpressionAttributeValues` entirely (DynamoDB rejects an empty map).
- `DynamoItemMapper.objectToAttributeValue(Object)` - a Jackson-backed converter so a patch operand that is a `Map` or `List` is stored as a real DynamoDB `M` / `L`. The pre-existing shallow `toAttributeValue(Object)` used for query parameters is unchanged.
- `DynamoCapabilities` declares `EXACT_FRACTIONAL_INCREMENT` as **supported** — the only v1 provider that does. DynamoDB evaluates `field = field + :delta` server-side in its `N` type (exact decimal, 38 significant digits), so `0.1` incremented by `0.2` stores exactly `0.3`, where Cosmos DB and Spanner store `0.30000000000000004`. The declaration is informational; it lets callers that need bit-identical fractional totals branch.

- Change-feed reader backed by DynamoDB Streams (`DescribeStream`, `GetShardIterator`, `GetRecords`). `listCursors` returns one cursor per open shard at the live tip with a pre-resolved `LATEST` iterator (`@@ITER:<iterator>` continuation), avoiding silent event loss between mint and first read. `readChanges` drains one shard''s page per call, rotates the partition list across shards, transitions to an `AFTER_SEQUENCE_NUMBER` continuation on the first observed record, and absorbs shard splits/closes. `TrimmedDataAccessException` → `CursorExpiredException(reason=PROVIDER_TRIMMED)`; `ExpiredIteratorException` → `reason=ITERATOR_EXPIRED`. Change-event payloads preserve the full DynamoDB type system via the shared `DynamoItemMapper`. The target table must have `StreamSpecification(NEW_AND_OLD_IMAGES)` enabled; otherwise `UNSUPPORTED_CAPABILITY(reason="stream_not_enabled")`.
- `DynamoCapabilities` declares `EXTENDED_CHANGE_FEED_HISTORY_UNSUPPORTED` (DynamoDB Streams is fixed at 24h server-side; SDK-managed archive-on-read via customer-provisioned Kafka is on the v1.x roadmap). Callers that opt in to `ChangeFeedConfig.extendedRetention(...)` fail fast at client-build time via the API-module factory gate; the `DynamoProviderClient` constructor mirrors the gate for SPI-direct integrators.
- Default sort-key ordering: scan paths sort items per-page by sort key ascending, matching DynamoDB''s native `Query` API and the Cosmos provider''s default. Per-page only.
- Typed `CLIENT_CLOSED` envelope on every post-close entry point. `close()` is idempotent and also disposes the embedded `DynamoDbStreamsClient`.

**Changed:**

- `SORT_KEY_ASC` comparator handles numeric sort keys with type-aware comparison (Long/Integer use native compare; mixed numerics fall back to `BigDecimal`) so integers beyond `2^53` are no longer truncated.
- `BETWEEN` translation wraps in parentheses (`(field BETWEEN ? AND ?)`) for cross-provider consistency.

**Breaking changes:**

- **A document field named `data` is now rejected.** `create`, `update`, and `upsert` throw `MulticloudDbException(INVALID_REQUEST)` before `DynamoProviderClient` dispatch when the document carries a top-level `data` key in **any** casing (`data`, `Data`, `DATA`, `dAtA`); `PatchOperation` rejects the `/data` path on the same rule. DynamoDB itself reserves no such attribute — `data` is the SDK-managed Spanner document envelope, and Spanner resolves column names case-insensitively, so an item DynamoDB would happily store could not be moved to Spanner.
  **Migration — do this before upgrading:** rename any application-owned `data` attribute (for example to `payload` or `applicationData`) **and rewrite the affected items with the renamed key**, then upgrade. There is no alias, no compatibility flag, and no opt-out. See [guide.md → *Document Field Injection*](guide.md#document-field-injection).
- **`field_exists` changed meaning; existing queries can return different rows.** `field_exists(f)` now translates to `(f IS NOT MISSING AND f IS NOT NULL)` — *present **and** non-null* — where it previously translated to a bare `f IS NOT MISSING` and therefore also matched an attribute explicitly stored as the DynamoDB `NULL` type. A filter that relied on the old behaviour now returns fewer items, and `NOT field_exists(f)` returns more. Use an explicit `f = null` comparison to keep matching an attribute legitimately stored as null.

**Documentation:**

- `delete()` of a missing key is documented as a silent no-op (idempotent); the Dynamo provider issues an unconditional `DeleteItem`.
- AWS SDK v2 (2.34.x) bundles the DynamoDB Streams client classes inside the main `software.amazon.awssdk:dynamodb` artifact at `software.amazon.awssdk.services.dynamodb.streams.*` (verified against the published `dynamodb-2.34.0.jar`); no separate `dynamodbstreams` dependency is required. If `aws-sdk.version` is bumped, re-verify that the Streams classes remain bundled.

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

**Fixed:**

- Query fields named after PartiQL reserved words no longer fail the statement. Every field reference is double-quoted (`"value"`, `"address"."city"`) instead of bare, so a document field called `value` or `size` behaves the same here as on Spanner.

---

## multiclouddb-provider-spanner

### [Unreleased]

**Added:**

- Portable `patch(...)` stores the resulting document in the standard `data` envelope inside a retryable read-write transaction. It therefore supports new top-level fields such as `/onSale` without a matching DDL column, while preserving readable legacy rows and mirroring only runtime values compatible with physical columns for existing query schemas. Incompatible, null, or omitted values clear the physical mirror to a typed null; the envelope remains authoritative. `SpannerCapabilities` declares `PATCH`.
- `SpannerCapabilities` declares **`NESTED_PATCH` as unsupported**: nested JSON traversal is deferred from the v1 compatibility scope. A nested patch path fails fast with `UNSUPPORTED_CAPABILITY` (never a silent parent rewrite or no-op); replace the whole top-level field with a `SET` instead. This is not a claim that a future transactional implementation would have a lost-update window.
- The portable contract requires `REPLACE` / `REMOVE` / `INCREMENT` to fail with `NOT_FOUND` on a missing field. Spanner reads and updates the envelope in one retryable transaction, preserving atomicity and preventing lost increments. The transaction only reads the full row for legacy compatibility; new envelope rows use the document representation. Typed nulls for INT64, FLOAT64, and BOOL physical columns retain their column type; fractional finite increments are represented in the envelope, and a signed-64 integral-result overflow is normalised to `INVALID_REQUEST`.
- `SpannerCapabilities` declares `EXACT_FRACTIONAL_INCREMENT` as **unsupported** — Spanner adds a fractional `INCREMENT` in IEEE-754 binary64, so `0.1` incremented by `0.2` stores `0.30000000000000004` (DynamoDB's exact-decimal `N` arithmetic stores `0.3`). Integral increments remain exact, and the declaration is informational: no fractional increment is ever rejected.

- Change-feed reader backed by Spanner change streams via the `READ_<stream>` TVF (single-use read-only transaction; 5-second bounded window per call). `listCursors` bootstraps the partition tree with a `NULL` partition token and anchors each cursor''s bookmark at `max(now, childStart)` so `now()` cursors honour their live-tip contract on the emulator. `readChanges` drains a bounded window, absorbs `child_partitions_record` rows (splits/merges), rotates the partition list, and surfaces `isTerminal()=true` when a cursor''s sole partition closes without children. Each `data_change_record.mod` becomes one `ChangeEvent` with a stable `providerEventId` (`<server_transaction_id>:<commit_ts>:<record_sequence>:<mod_index>`). `INVALID_ARGUMENT` / `NOT_FOUND` / `OUT_OF_RANGE` on the TVF → `CursorExpiredException(reason=PROVIDER_TRIMMED)`.
- Per-collection change-stream name resolution: defaults to `<collection>_changes`; override via the `changeStream.<collection>` connection key.
- Extended-retention provisioning: `SpannerProviderClient.ensureContainer(address)` emits an idempotent `CREATE CHANGE STREAM <name> FOR <table> OPTIONS (value_capture_type = ''NEW_ROW'', retention_period = ''<value>'')` when the user opted in. `value_capture_type = ''NEW_ROW''` ensures `mods.new_values` carries the full post-image (the GoogleSQL default of `OLD_AND_NEW_VALUES` only carries the mutated columns). The duplicate-name path reads back the active `retention_period` from `INFORMATION_SCHEMA.CHANGE_STREAM_OPTIONS` and throws `UNSUPPORTED_CAPABILITY(reason="extended_retention_not_enacted")` on mismatch. `INVALID_ARGUMENT` from `updateDatabaseDdl(...)` mentioning `retention_period` → `UNSUPPORTED_CAPABILITY(reason="retention_exceeds_native_max")`. `SpannerCapabilities` declares `EXTENDED_CHANGE_FEED_HISTORY_CAP` (default 24h; up to 7d natively).
- Typed `CLIENT_CLOSED` envelope replacing prior raw `IllegalStateException` from `checkOpen()`. `close()` is idempotent; post-close errors attribute the failing operation instead of `"checkOpen"`.

**Changed:**

- `upsert(address, key, document)` uses Spanner `INSERT_OR_UPDATE` (was `REPLACE`). `REPLACE` is internally delete-then-insert, which change streams surface as `mod_type=INSERT` — making a second upsert of the same key appear as `ChangeType.CREATE` instead of `ChangeType.UPDATE`. `INSERT_OR_UPDATE` matches Cosmos AVAD and DynamoDB Streams.
- Spanner instance creation in `ensureDatabase` is gated to emulator mode. In production the instance is expected to pre-exist; only the database is created.
- Complex container values (`Map`, `Collection`) round-trip through STRING columns using an unambiguous prefix marker (`U+0001` + `mcdb:json:`).
- `BETWEEN` translation wraps in parentheses (`(field BETWEEN @lo AND @hi)`) for cross-provider consistency.

**Breaking changes:**

- `update()` now has one portable replacement contract: each call replaces the full document and omitted fields disappear from reads and portable queries, including on Spanner rows with stale physical columns.
- Document field named `data` is rejected with `MulticloudDbException(INVALID_REQUEST)` for `create`, `update`, and `upsert` on every provider (case-insensitive). The name is reserved for the internal `FIELD_DATA` metadata envelope.
- `upsert()` is a full document replace; fields absent from the supplied document are absent from SDK reads and portable queries. Supported physical mirrors for omitted or incompatible fields are explicitly written as typed NULL, while the authoritative envelope preserves the distinction between an absent field and an explicit JSON `null`.
- Customer-managed tables require a `data STRING(MAX)` column. Tables created by `ensureContainer()` already include it; tables provisioned outside the SDK must run `ALTER TABLE <table> ADD COLUMN data STRING(MAX);`.
- `ensureDatabase(name)` throws `MulticloudDbException(INVALID_REQUEST)` when `name` does not match the configured `databaseId`.
- Lifecycle errors are typed: `checkOpen()` throws `MulticloudDbException(CLIENT_CLOSED)`, `ensureDatabase` name-mismatch throws `MulticloudDbException(INVALID_REQUEST)`, replacing the prior raw `IllegalStateException` / `IllegalArgumentException`.
- `SpannerRowMapper.toMap()` preserves explicitly written `null` values; callers iterating `page.items().get(i)` must tolerate `null`.
- **The `data` column's stored format changed, and downgrades are not supported.** Writes now store the authoritative full-document envelope `{"_mcdbDocument": { ... }}`; previously `data` held a JSON array of explicitly-written field names. Reads are backward compatible (a legacy `[`-prefixed value falls back to the physical-column projection), but an **older** SDK reading an envelope row does not recognise it, falls back to the physical-column projection, and therefore cannot see any top-level field that exists only in the envelope — every field without a matching DDL column, including dynamic fields created by `patch`. A write from that older SDK also leaves a stale envelope behind in `data`. **Roll forward, not back:** deploy the new SDK to all writers before relying on dynamic fields, and if a rollback is unavoidable, first rewrite affected rows so every field the old SDK needs has a physical column.

**Fixed:**

- **`NOT field_exists(absentField)` now matches rows on Spanner.** The translator emitted a bare `JSON_TYPE(...) != 'null'` guard, which evaluates to SQL `NULL` — not `FALSE` — when the field is absent from the document envelope, so `NOT (...)` was `NULL` too and the row was silently filtered out where Cosmos DB and DynamoDB returned it. The guard is now wrapped as `COALESCE(JSON_TYPE(...) != 'null', FALSE)`, so `field_exists` is `FALSE` and `NOT field_exists` is `TRUE` for both an absent field and an explicit `null`, and `TRUE` / `FALSE` respectively for a present non-null field — matching the other two providers. Verified against the Spanner emulator.
- Default `ORDER BY` no longer fires for aggregate / `GROUP BY` queries (GoogleSQL rejects with `column not aggregated`). It also no longer duplicates primary-key columns when the caller already sorts by them, and `ORDER BY` detection ignores string literals (so `WHERE comment = ''please ORDER BY date''` is no longer a false positive).
- **Physical column types are loaded from `INFORMATION_SCHEMA.COLUMNS`.** The previous `SELECT * FROM <table> LIMIT 0` + `ResultSet.getType()` probe hit `GrpcResultSet`'s `Preconditions.checkState(..., "next() call required")` — with `LIMIT 0` no row is consumed — so **every** Spanner write path threw `IllegalStateException`. Types with no `Type` counterpart (arrays, protos, enums, structs, …) are skipped, which is equivalent to declaring them unmirrorable; metadata-load failures are normalised to `PROVIDER_ERROR`.
- **Portable `ORDER BY` sorts through the authoritative `data` envelope.** SDK-generated query SQL orders non-key fields with a `JSON_TYPE`-ranked sort key (numbers, then strings, then booleans, then null/absent, each followed by `LAX_FLOAT64` / `LAX_STRING` / `LAX_BOOL`) over the same envelope the `WHERE` clause and `SpannerRowMapper` read, instead of a bare physical column. Dynamic fields with no DDL column can therefore be sorted, and a physical mirror cleared to a typed null no longer causes silent mis-ordering. `partitionKey` / `sortKey` remain bare-column tie-breakers, and caller-supplied native GoogleSQL keeps the bare-column form.
- **Typed nulls are bound with the physical column's own type** (`(Long) null` / `(Double) null` / `(Boolean) null` / `(String) null` per `INFORMATION_SCHEMA`) instead of binding every null as `(String) null` and failing the mutation on an `INT64` / `FLOAT64` / `BOOL` column. Applies to `create`, `update`, `upsert`, **and** `patch` — all mirror writes route through `writeFullDocument` / `writePhysicalPatchValue`. The previous workaround (typed zero/sentinel, or widening the column to `STRING`) is no longer needed.
- Legacy / pre-`FIELD_DATA` rows preserve every column on read. When `FIELD_DATA` is absent or malformed, the reader applies the historical "no metadata => no filtering" rule. A subsequent `create`, `update`, or `upsert` writes a complete authoritative envelope and promotes the row into the metadata regime.
- `ensureDatabase()` / `ensureContainer()` no longer leak raw `RuntimeException` on non-Spanner failures. `InterruptedException` → `TRANSIENT_FAILURE`; non-Spanner causes inside the admin `ExecutionException` → `PROVIDER_ERROR`.
- `setMutationValue` no longer fails on common Java types (e.g. `java.time.Instant`) — JSON serialisation is restricted to `Map`/`Collection`; every other type falls back to `value.toString()`.

**Known limitations:**

- **`NESTED_PATCH` is not supported.** A patch path with more than one segment (`/address/city`) fails fast with `UNSUPPORTED_CAPABILITY` before the transaction opens; nested JSON traversal is deferred from the v1 compatibility scope. Replace the whole top-level field with a `SET`, or branch on `capabilities().isSupported(Capability.NESTED_PATCH)`.
- **`patch()` serialises concurrent writers to the same row.** The existence check, increment arithmetic, and envelope write all run inside one retryable read-write transaction over the row, so two concurrent patches to *disjoint* fields of the same document contend and one transparently retries. Cosmos DB and DynamoDB do not. No update is ever lost, but a hot single document will serialise on Spanner.
- **Fractional `INCREMENT` accumulates in IEEE-754 binary64** (`EXACT_FRACTIONAL_INCREMENT` is declared unsupported), so a fractional running total can drift in the last ulp from DynamoDB's exact-decimal result. Integral increments are exact.

**Documentation:**

- `delete()` of a missing key is documented as a silent no-op (idempotent); `Mutation.delete(table, Key.of(pk, sk))` is idempotent natively.

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
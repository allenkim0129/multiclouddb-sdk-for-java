# Changelog — multiclouddb-api

All notable changes to the `multiclouddb-api` module will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this module adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Portable **patch** operation for field-level partial updates: `MulticloudDbClient.patch(ResourceAddress, MulticloudDbKey, List<PatchOperation>[, OperationOptions])`, the new public type `com.multiclouddb.api.PatchOperation` (`SET` / `REPLACE` / `REMOVE` / `INCREMENT`, addressed by JSON Pointer), and `OperationNames.PATCH`. Cosmos DB and DynamoDB use native atomic partial writes. Spanner explicitly declares PATCH unsupported and inherits the SPI default `UNSUPPORTED_CAPABILITY` failure, leaving room for a future implementation without changing the public contract.
- New well-known capabilities `Capability.PATCH` and `Capability.NESTED_PATCH`. Cosmos DB and DynamoDB declare both supported; Spanner declares both unsupported pending a follow-up implementation.
- New well-known capability `Capability.EXACT_FRACTIONAL_INCREMENT`. DynamoDB declares it supported because its `N` type uses exact decimal arithmetic; Cosmos DB declares it unsupported because fractional increments use IEEE-754 binary64. Spanner also declares it unsupported while PATCH itself is unavailable. The capability is informational for providers that support PATCH, allowing callers that require bit-identical fractional totals to branch explicitly.
- New well-known capability `Capability.PATCH_PRESERVES_TTL`. DynamoDB declares it supported because `UpdateItem` leaves the absolute `ttlExpiry` attribute unchanged. Cosmos DB declares it unsupported and rejects an item carrying the SDK-managed `ttl` field rather than silently restarting its relative expiry; Spanner declares it unsupported while PATCH is unavailable.
- `MulticloudDbClient.MAX_PATCH_OPERATIONS` (10) — Cosmos DB's native per-request cap, enforced uniformly so a patch that succeeds on one provider cannot fail on another.
- Patch reserved-field names (`id`, `partitionKey`, `sortKey`, `ttl`, `ttlExpiry`, `data`, and any name starting with `_`) are matched **without regard to case**, so `/data` and `/Data` are rejected identically on every provider. See *Breaking changes* for the matching `create` / `update` / `upsert` rejection.
- Patch request payloads are limited to **399 KB (408,576 bytes)**. Validation measures a deterministic serialized list containing every operation's type, path, and optional value, so even an oversized `REMOVE` path fails before provider dispatch. This is a request-envelope limit, not a guarantee that the resulting stored item fits every provider's native item limit. Case-only duplicate or ancestor paths are rejected as `INVALID_REQUEST`, preventing provider-specific path aliasing.
- `INCREMENT` now has one central portable numeric domain: integral deltas and their resulting value must fit signed 64-bit range; non-zero fractional deltas must be at least `1E-130` in magnitude (DynamoDB's numeric floor), no larger than 9,007,199,254,740,991, finite, and round-trip through an IEEE-754 `double` without decimal precision loss. Accepted values are normalized before dispatch, and integral-result overflow is rejected atomically as `INVALID_REQUEST`, preventing provider-specific narrowing or precision drift.
- `PatchOperation` now takes recursive immutable JSON-compatible snapshots of arrays, collections, maps, mutable `CharSequence`s, dates, atomic numbers, Jackson JSON trees, and mapper-supported POJOs, so instances remain thread-safe after construction and through `value()` access. Non-string map keys, cyclic containers, unsupported number types, and non-finite replacement values fail deterministically.
- `MulticloudDbProviderClient.validatePatchRequest(...)` is the SPI's shared direct-dispatch guard. Supported provider implementations call it before provider SDK work, yielding portable validation and PATCH/NESTED_PATCH capability errors; the default `patch()` fails closed for unsupported adapters. `DocumentFieldValidator` is an internal facade-boundary helper, while `PatchValidator` remains internal and `PatchNumericDomain` is public for callers that need to validate an increment domain before submission.

### Changed

- **`patch(...)` now snapshots the caller-supplied operation list before dispatch.** The `List<PatchOperation>` you pass is copied at the client boundary, so the operations a provider executes are exactly the operations that were validated. Previously the caller-owned list was handed straight to the provider and re-read *after* validation, so a caller mutating that list from another thread could apply operations `PatchValidator` never approved (a time-of-check/time-of-use hole). `PatchOperation` was already a deeply immutable snapshot; the list carrying the operations now is too. A `null` operation list still fails with `INVALID_REQUEST`, not a `NullPointerException`.
- **The portable patch guard now runs exactly once per supported `patch(...)` call.** Cosmos DB and DynamoDB validate in their provider adapters as required by the SPI contract; the client facade does not repeat the potentially 399 KB serialization. Spanner and third-party adapters that inherit the SPI default fail closed with `UNSUPPORTED_CAPABILITY` without provider I/O. **SPI note for third-party adapters:** an adapter that overrides `patch(...)` must call `validatePatchRequest(...)` immediately after its lifecycle guard.

### Breaking changes

- **A document field named `data` is now rejected.** `create`, `update`, and `upsert` throw `MulticloudDbException(INVALID_REQUEST)` before provider dispatch when the document contains a top-level `data` key in **any** casing (`data`, `Data`, `DATA`, `dAtA`); `PatchOperation` rejects `/data` on the same rule. `data` is an SDK-managed Spanner metadata column, and Spanner resolves column names case-insensitively, so allowing it on Cosmos DB or DynamoDB would make the same document unportable.
  **Migration — do this before upgrading:** rename any application-owned `data` field (for example to `payload` or `applicationData`) **and rewrite the affected documents with the renamed key**, then upgrade. There is no alias, no compatibility flag, and no opt-out; a document that still carries `data` fails at the client boundary on every provider. See [`docs/guide.md` → *Document Field Injection*](../docs/guide.md#document-field-injection).

### Fixed

- **A `null` entry inside a patch operation list is now `INVALID_REQUEST`, not `PROVIDER_ERROR`.** `MulticloudDbClient.patch` snapshotted the caller's list with `List.copyOf`, which rejects a null *element* — not just a null list — with a raw `NullPointerException` before any adapter could see it; the catch-all normalised that to `PROVIDER_ERROR`, leaving `PatchValidator`'s own ``patch operations must not contain null entries`` rule unreachable for supported providers. The snapshot now uses `Collections.unmodifiableList(new ArrayList<>(...))`, which keeps the TOCTOU guarantee while permitting the adapter validator to report the portable `INVALID_REQUEST`.
- `PatchNumericDomain.isIntegralResultOutsideRange(current, delta)` now rejects a `null` current value with the same `IllegalArgumentException` that `PatchNumericDomain.add(current, delta)` raises. The two entry points previously disagreed about their own contract: `add` guarded explicitly while `isIntegralResultOutsideRange` reached its internal decimal conversion and would have failed with a `NullPointerException`.

### Documentation

- `PatchNumericDomain` now documents **which result bound an `INCREMENT` gets, and why it is chosen by the normalized delta rather than by the Java type you passed**. An integral delta bounds the *result* to signed 64-bit (an integral result must round-trip as a `Long` on every provider); a fractional delta only requires the result to stay finite (a fractional result is never promised as a `Long`). Because normalization folds any whole-valued `Float` / `Double` / `BigDecimal` to a `Long`, `increment("/v", 1.0)` takes the integral rule. On a field holding `1e300` this means `increment("/v", 1)` and `increment("/v", 1.0)` are both `INVALID_REQUEST` while the larger `increment("/v", 1.5)` succeeds. The behaviour is unchanged — it is now stated, with that example, on the class and on `add(...)` / `isIntegralResultOutsideRange(...)`. Use `PatchNumericDomain.isIntegralDelta(delta)` to determine which rule applies before dispatch.
- `patch()` is documented as a **latency and concurrency** optimisation, not a guaranteed write-cost reduction. Its bill depends on provider pricing, account configuration, indexes, item shape, and workload; the SDK makes no precise cross-operation billing-equivalence claim. It is also the only portable way to express a safe atomic counter.
- The v1 portable patch contract is deliberately narrow, and the restrictions are documented on `PatchOperation` and `MulticloudDbClient.patch(...)`: the target document must already exist (`NOT_FOUND` otherwise); at most 10 operations; paths must be disjoint (including case-only aliases); `REPLACE` / `REMOVE` / `INCREMENT` require the target field to exist and fail with `NOT_FOUND` otherwise; `SET` never creates missing intermediate objects; array-index segments and JSON Pointer `~` escapes are rejected as `INVALID_REQUEST`; key, TTL, and `data` field names are not patchable; `OperationOptions.ttlSeconds()` is ignored; and existing SDK-managed expiry is preserved only where `PATCH_PRESERVES_TTL` is declared.

## [0.1.0-beta.2] — 2026-06-17

### Added

- Portable change-feed API in `com.multiclouddb.api.changefeed`: `ChangeFeedCursor` (opaque, persistable via `toToken()` / `fromToken(...)` with a `now()` live-tip sentinel), `ChangeFeedPage` (events + `nextCursor` + `hasMore`/`terminal`), `ChangeEvent` (with stable `providerEventId` for dedup), `ChangeType`, and `CursorExpiredException`. Two new entry points on `MulticloudDbClient`: `listCursors(ResourceAddress)` and `readChanges(ResourceAddress, ChangeFeedCursor[, OperationOptions])`. Provider SPI methods default to `UNSUPPORTED_CAPABILITY` so existing adapters compile unchanged. The cursor wire format is opaque, version-tagged Base64URL JSON; the 24-hour portable baseline is enforced client-side on the token's last-issued timestamp. `OperationOptions.timeout()` is not enforced on the change-feed path in this release (wall-clock follows each provider's page-fetch budget).
- New error category `MulticloudDbErrorCategory.CURSOR_EXPIRED` carrying a canonical `providerDetails.reason` set (`TOKEN_AGED_OUT`, `PROVIDER_TRIMMED`, `ITERATOR_EXPIRED`, `MALFORMED`, `VERSION_UNSUPPORTED`, `PROVIDER_MISMATCH`, `RESOURCE_MISMATCH`), exported as public `CursorTokenCodec.REASON_*` constants so providers share a single source of truth.
- New error category `MulticloudDbErrorCategory.CLIENT_CLOSED` surfaced by a `DefaultMulticloudDbClient` post-close guard on every public entry point (replaces provider-specific `IllegalStateException` leaks). `MulticloudDbClient.close()` is now idempotent.
- Extended change-feed retention opt-in: `ChangeFeedConfig.extendedRetention(Duration)` (validates `> 24h`), wired into `MulticloudDbClientConfig.changeFeed(...)`, plus the new well-known `Capability.EXTENDED_CHANGE_FEED_HISTORY`. The `MulticloudDbClientFactory.create(...)` build-time gate refuses to instantiate a client whose provider does not declare the capability, surfacing `UNSUPPORTED_CAPABILITY(reason="extended_retention_unavailable")` before any I/O. The cursor token wire format carries an optional `"e"` field stamping the opted-in retention so a persisted cursor under a 7-day opt-in can be resumed beyond 24h up to the configured window without `TOKEN_AGED_OUT`; older tokens (no `"e"`) keep the 24h floor.
- `OperationNames.LIST_CURSORS`, `READ_CHANGES`, `PROVISION_SCHEMA` propagated through `MulticloudDbError.operation()` and `OperationDiagnostics`.

### Documentation

- `MulticloudDbClient.delete(...)` is documented as idempotent on every provider — a missing key is a silent no-op (LCD of Cosmos 404 swallow, DynamoDB native idempotence, and Spanner `Mutation.delete`). Callers needing to detect a missing key should use `read(...)`, which returns `null` when the key is missing.

## [0.1.0-beta.1] — 2026-04-23

### Added

- `MulticloudDbClientConfig.Builder.userAgentSuffix(String)` — optional
  caller-supplied token appended to the SDK user-agent header sent by all
  provider clients (Cosmos, DynamoDB, Spanner). Useful for downstream
  identification of applications, frameworks, or tenants. Pass `null` to clear
  a previously-set suffix.
- `MulticloudDbClientConfig.userAgentSuffix()` — accessor returning an
  `Optional<String>` of the configured suffix.
- `com.multiclouddb.spi.SdkUserAgent` — SPI helper that builds the canonical
  `multiclouddb-sdk-java/<version> (<jvm>; <os>)` token consumed by provider
  adapters.

### Validation

- `userAgentSuffix(String)` rejects values longer than 256 characters and any
  string containing characters outside printable US-ASCII (0x20–0x7E) plus
  horizontal tab (0x09), throwing `IllegalArgumentException`. This protects
  the user-agent header from injection of CR/LF or other control characters.

#### Portable client API

- `MulticloudDbClient` — synchronous, provider-agnostic interface for CRUD
  (`create`, `read`, `update`, `upsert`, `delete`), query, and schema
  provisioning (`ensureDatabase`, `ensureContainer`, `provisionSchema`)
- `MulticloudDbClientFactory` — discovers provider adapters via `ServiceLoader`
  and returns a configured `MulticloudDbClient` instance
- `MulticloudDbClientConfig` — immutable builder-pattern configuration holding
  provider identity, connection properties, auth properties, default operation
  options, and native diagnostics opt-in flag. All map accessors return
  unmodifiable copies; builder setters perform defensive copying
- `OperationOptions` — per-call controls (currently: timeout with hard-deadline
  contract)
- `QueryRequest` — immutable query input with portable expression or native
  expression passthrough, named parameters, page-size hint, continuation token,
  and optional partition-key scoping
- `QueryPage` — immutable query result page carrying items
  (`List<Map<String, Object>>`), opaque continuation token, and optional
  diagnostics

#### Identity and addressing

- `ProviderId` — extensible value-object identifying providers with well-known
  constants (`COSMOS`, `DYNAMO`, `SPANNER`) and runtime registration support
- `ResourceAddress` — database + container/table logical address
- `MulticloudDbKey` — portable record key supporting partition key, optional
  sort key, and arbitrary extra components. `toString()` result is cached at
  construction time

#### Query expression model

- Sealed `Expression` AST with six node types:
  - `ComparisonExpression` — field comparisons (`=`, `<>`, `!=`, `<`, `>`,
    `<=`, `>=`)
  - `LogicalExpression` — boolean connectives (`AND`, `OR`)
  - `NotExpression` — unary negation
  - `InExpression` — set membership (`field IN (...)`)
  - `BetweenExpression` — range predicate (`field BETWEEN low AND high`)
  - `FunctionCallExpression` — portable functions with five built-ins:
    `starts_with`, `contains`, `field_exists`, `string_length`,
    `collection_size`
- `FieldRef` — field reference supporting dot-notation paths (e.g.,
  `address.city`)
- `Literal` — typed constant values (string, number, boolean, null)
- `Parameter` — named parameter placeholder (`@paramName`)
- `ComparisonOp` and `LogicalOp` — operator enumerations
- `PortableFunction` — enumeration of cross-provider function names
- `TranslatedQuery` — translation output carrying the provider-native query
  string plus named or positional parameter bindings
- `ExpressionTranslator` — SPI interface that providers implement to translate
  the portable AST into their native query language
- `ExpressionParser` — hand-written recursive-descent parser for a SQL-like
  WHERE-clause subset supporting parentheses, boolean logic (`AND`, `OR`,
  `NOT`), comparisons, `IN`, `BETWEEN`, function calls, string/number/boolean/
  null literals, `@`-prefixed named parameters, and dot-notation field paths
- `ExpressionValidator` — validates that all `@parameter` references in a parsed
  expression tree have corresponding entries in the supplied parameter map;
  throws `ExpressionValidationException` with all unresolved parameter names

#### Capability model

- `Capability` — extensible value-object with supported/unsupported state and
  optional provider-specific notes. Thirteen well-known capabilities defined:
  `continuation_token_paging`, `cross_partition_query`, `transactions`,
  `batch_operations`, `strong_consistency`, `native_sql_query`, `change_feed`,
  `portable_query_expression`, `like_operator`, `order_by`, `ends_with`,
  `regex_match`, `case_functions`
- `CapabilitySet` — immutable capability map for capability introspection at
  runtime

#### Error model

- `MulticloudDbErrorCategory` — extensible string-based error category with ten
  well-known values: `INVALID_REQUEST`, `AUTHENTICATION_FAILED`,
  `AUTHORIZATION_FAILED`, `NOT_FOUND`, `CONFLICT`, `THROTTLED`,
  `TRANSIENT_FAILURE`, `PERMANENT_FAILURE`, `PROVIDER_ERROR`,
  `UNSUPPORTED_CAPABILITY`
- `MulticloudDbError` — structured portable error payload carrying category,
  message, provider identity, operation name, HTTP status code, retryable flag,
  and unmodifiable provider-detail map
- `MulticloudDbException` — runtime exception wrapping `MulticloudDbError` with
  optional `OperationDiagnostics` attachment

#### Diagnostics

- `OperationDiagnostics` — builder-pattern value object capturing provider
  identity, operation name, duration, and optional fields: request ID, HTTP
  status code, request charge (RU), ETag, session token, and item count
- `OperationNames` — canonical operation name constants shared across
  diagnostics, errors, and provider implementations

#### SPI (Service Provider Interface)

- `MulticloudDbProviderAdapter` — SPI entry point that providers register via
  `META-INF/services`; supplies provider identity, client factory, and optional
  expression translator
- `MulticloudDbProviderClient` — SPI contract for provider implementations
  covering CRUD, query, query-with-translation, provisioning
  (`ensureDatabase`, `ensureContainer`, `provisionSchema` with default parallel
  two-phase implementation using bounded thread pool), capabilities, provider
  identity, and `AutoCloseable` lifecycle

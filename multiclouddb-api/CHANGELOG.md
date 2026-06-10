# Changelog — multiclouddb-api

All notable changes to the `multiclouddb-api` module will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this module adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed — Cursor token age cap honours `extendedRetention(...)`

- **`CursorTokenCodec` / `CursorToken`** — the client-side age cap is no
  longer the static 24h baseline for tokens minted under
  `ChangeFeedConfig.extendedRetention(...)`. Provider readers (Cosmos,
  Spanner) now stamp the opted-in window onto every minted token via a new
  optional `"e"` field in the JSON wire format; the decoder applies
  `max(24h baseline, encoded)` as the age cap, so a persisted cursor under
  a 7-day opt-in can be resumed beyond 24h without `TOKEN_AGED_OUT`.
  Backwards-compatible: tokens without `"e"` (and tokens minted at the
  baseline) keep the 24h floor unchanged, so the wire form for the common
  case is bit-for-bit identical to v1. The codec rejects non-numeric,
  zero, or negative `"e"` as `MALFORMED`, and the `CursorToken`
  constructor clamps any too-small value up to the 24h floor so a buggy
  producer cannot silently shorten the portable guarantee. Dynamo is
  unaffected because the opt-in is gated at build time.

### Added — Extended Change-Feed Retention (opt-in)

- **`com.multiclouddb.api.changefeed.ChangeFeedConfig`** — new immutable value
  class with a fluent builder that carries the opt-in request for change-feed
  history beyond the portable 24-hour baseline. `extendedRetention(Duration)`
  validates eagerly (must be strictly greater than 24 h; null clears the
  request). `defaults()` returns the cached no-op singleton so callers that
  never touch this class are bit-for-bit identical to v1.
- **`MulticloudDbClientConfig.changeFeed(ChangeFeedConfig)`** — new builder
  setter + accessor wiring `ChangeFeedConfig` into the client. Default is
  `ChangeFeedConfig.defaults()`; passing `null` is normalised to defaults.
- **`Capability.EXTENDED_CHANGE_FEED_HISTORY`** — new well-known capability
  name + `EXTENDED_CHANGE_FEED_HISTORY_CAP` / `EXTENDED_CHANGE_FEED_HISTORY_UNSUPPORTED`
  singletons. The registry size grows from 16 to 17.
- **Build-time capability gate** — `MulticloudDbClientFactory.create(...)`
  now refuses to build a client when `ChangeFeedConfig.hasExtendedRetention()`
  is true but the provider does not declare
  `EXTENDED_CHANGE_FEED_HISTORY`. Surfaces as
  `MulticloudDbException` with category `UNSUPPORTED_CAPABILITY` and
  `providerDetails.reason="extended_retention_unavailable"` before any change-feed-substrate I/O is issued.
- See `docs/guide.md` → *"Extending change-feed history beyond 24 hours"* for
  the cost-model callout (per-provider price drivers; the windows are **not**
  interchangeable).
- **Test coverage note**: the build-time gate is verified by an abstract
  test in this module (`MulticloudDbClientFactoryExtendedRetentionGateTest`,
  driven through a fake adapter registered via `META-INF/services`), and the
  per-provider declaration is verified by
  `CapabilitiesConformanceTest` + the per-provider `isSupported(EXTENDED_CHANGE_FEED_HISTORY)`
  assertions in `Cosmos/Dynamo/SpannerCapabilitiesTest`. The end-to-end
  *behavioural* conformance — "set 7d, observe events older than 24h are
  still readable" — is deferred to a live-cloud nightly fixture because the
  Cosmos emulator caps AVAD retention at 10 minutes and the Spanner emulator
  silently ignores `OPTIONS (retention_period = …)` on
  `CREATE CHANGE STREAM`. See `specs/001-clouddb-sdk/plan.md`
  Planning Addendum (2026-11) for the deferral rationale.



### Fixed — Round-6 diagnostic accuracy

- **`DefaultMulticloudDbClient.checkCapability`** now accepts an explicit
  `operation` parameter and plumbs it into
  `MulticloudDbError.operation()`. The earlier signature hard-coded
  `"query"` for every call site, so a change-feed entry point
  (`listCursors` / `readChanges`) that hit the capability gate surfaced
  `error().operation() == "query"` — mis-attributing the failure for any
  diagnostics consumer that branched on the operation name. Call sites now
  pass `OperationNames.LIST_CURSORS` / `READ_CHANGES` / `QUERY`
  respectively. Wire format of `providerDetails` is unchanged (still
  carries `"capability"`).

### Added — Change-Feed API (3-primitive cursor model)

- **`com.multiclouddb.api.changefeed` package** — new portable change-feed surface
  comprising `ChangeFeedCursor` (opaque, immutable position;
  `now()` sentinel + `fromToken`/`toToken` for persistence),
  `ChangeFeedPage` (events + `nextCursor` + `hasMore`/`terminal`),
  `ChangeEvent` (key + `ChangeType` + `commitTimestamp` + data +
  `providerEventId`), `ChangeType` enum (`CREATE`/`UPDATE`/`DELETE`), and
  `CursorExpiredException`.
- **`MulticloudDbClient.listCursors(ResourceAddress)`** — discovers one cursor
  per provider partition at the live tip.
- **`MulticloudDbClient.readChanges(ResourceAddress, ChangeFeedCursor)`** and
  the `OperationOptions` overload — drains one page of change events from a
  cursor and returns a fresh `nextCursor`. In v1, `OperationOptions.timeout()`
  is **not** enforced on the change-feed path — wall-clock of each call is
  bounded by the provider's own page-fetch behaviour (Cosmos: per-request;
  Dynamo: ~5s `GetRecords`; Spanner: 5s TVF window). The facade emits a
  one-shot `WARN` the first time a non-default timeout is observed.
- **`OperationNames.LIST_CURSORS` / `READ_CHANGES`** — operation-name
  constants surfaced through `MulticloudDbError.operation()` and
  `OperationDiagnostics`, matching the every-other-entry-point pattern.
- **`MulticloudDbErrorCategory.CURSOR_EXPIRED`** — new well-known category for
  trimmed / aged-out / mismatched cursors. Provider details key `reason`
  carries one of `TOKEN_AGED_OUT`, `PROVIDER_TRIMMED`, `ITERATOR_EXPIRED`,
  `MALFORMED`, `VERSION_UNSUPPORTED`, `PROVIDER_MISMATCH`,
  `RESOURCE_MISMATCH`. All seven values are exported as public string
  constants on `com.multiclouddb.api.changefeed.internal.CursorTokenCodec`
  (`REASON_TOKEN_AGED_OUT` … `REASON_RESOURCE_MISMATCH`); provider
  adapters reference those constants instead of bare string literals, so
  the wire-format-stable values have a single source of truth. The
  `.internal` subpackage is still considered an SPI surface — callers
  who only consume the values via `e.error().providerDetails().get("reason")`
  do not import the codec class.
- **SPI**: `MulticloudDbProviderClient.listCursors` / `readChanges` default to
  throwing `UNSUPPORTED_CAPABILITY` so existing adapters compile unchanged.
- **`DefaultMulticloudDbClient`** enforces capability-gating, validates the
  cursor's provider id and resource binding against the call site, and
  enforces a client-side 24-hour cap on the cursor's last-issued timestamp.
- Cursor token format documented as opaque, version-tagged (`{"v":1,...}`
  Base64URL JSON) and stable across SDK versions; the `internal` subpackage
  holds the codec for provider implementations.
- 30 new unit tests in `com.multiclouddb.api.changefeed` covering the cursor
  primitives, page semantics, exception details, and the round-trip /
  expiry / mismatch / tampering paths of the token codec.

### Added

- **`MulticloudDbErrorCategory.CLIENT_CLOSED` — portable post-close error
  category.** A new typed category that every provider now surfaces when a
  CRUD, query, or provisioning call is made after `MulticloudDbClient.close()`.
  Previously the post-close behaviour was provider-specific: callers
  received a raw `IllegalStateException` from azure-cosmos / aws-sdk, an
  `IllegalStateException` from Spanner, or `null` / undefined behaviour
  depending on the provider. Telemetry, retry-policy, and circuit-breaker
  layers can now branch on the typed envelope; `CLIENT_CLOSED` is
  declared non-retryable because closing is a terminal lifecycle state.
- **`OperationNames.PROVISION_SCHEMA` — operation-name constant.** The
  `provisionSchema()` entry point now reports its operation name through
  the typed `MulticloudDbError.operation()` field for diagnostics
  attribution, matching every other entry point.
- **`DefaultMulticloudDbClient` facade post-close guard.** The facade now
  short-circuits every public entry point with `CLIENT_CLOSED` *before*
  any per-request validation (document size, query parsing, etc.) runs.
  This guarantees that a closed client never reports `REQUEST_TOO_LARGE`
  or other category errors that would mask the underlying lifecycle bug.
  **`MulticloudDbClient.close()` itself is now idempotent**: a second
  `close()` is a synchronized no-op, and the underlying
  `providerClient.close()` is invoked exactly once even under concurrent
  callers.

### Documentation

- **`MulticloudDbClient.delete(...)` is documented as idempotent — silent on
  missing key.** The Javadoc now declares that deleting a key that does not
  exist is a silent no-op on every provider, which is the true LCD across
  Cosmos (404 swallowed), DynamoDB (`DeleteItem` is idempotent natively) and
  Spanner (`Mutation.delete` is idempotent natively). Callers that need to detect a missing key should use
  `MulticloudDbClient.read(...)`, which returns `null` on every provider
  when the key does not exist (non-mutating). `update()` also throws
  `NOT_FOUND` on a missing key, but it requires a document body and
  **overwrites on hit**, so it is not a safe pure existence probe.

### Fixed

- `CursorTokenCodec.decode` no longer applies the 24-hour client-side age
  cap to unhydrated `ChangeFeedCursor.now()` sentinels (anchor=NOW with no
  resource binding and no partition positions). The age check still applies
  to every hydrated cursor — i.e., every token that represents a real
  resumable position — but a persisted `now()` sentinel that has not yet
  been read against any resource has no provider-side position to expire
  against and is now correctly accepted as a "start from the live tip
  *now*" instruction.
- `CursorTokenCodec.validateProviderMatch` and
  `CursorTokenCodec.validateResourceMatch` now build their
  `CursorExpiredException` with the runtime `ProviderId` and
  `operation="readChanges"` attached, instead of `provider=null`. These
  validators run at `DefaultMulticloudDbClient.readChanges(...)` entry
  (before the enrichment `try/catch`), so the runtime provider is known
  and should be on the error for diagnostics.
- The decode-error helper in `CursorTokenCodec` is now scoped to the
  decode path only (called from `ChangeFeedCursor.fromToken(...)`) and
  reports `operation="fromToken"` so client-side decode failures
  (`MALFORMED`, `VERSION_UNSUPPORTED`, `TOKEN_AGED_OUT`) are no longer
  mis-attributed to a runtime read.
- **API change (internal):** `CursorTokenCodec.validateResourceMatch`
  now takes an additional `ProviderId runtimeProvider` parameter.

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

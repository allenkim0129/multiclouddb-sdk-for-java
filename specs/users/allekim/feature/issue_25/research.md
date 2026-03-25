# Research: Result Set Control, TTL/Write Metadata, Uniform Document Size, and Provider Diagnostics

**Branch**: `users/allekim/feature/issue_25`  
**Parent research**: [`specs/001-clouddb-sdk/research.md`](../../../../001-clouddb-sdk/research.md)

This document records design decisions for the four new capability areas introduced in the updated spec. Prior decisions (D1–D15) are captured in the parent research document and are not repeated here.

---

## Decision 16: SpannerConstants class

- **Decision**: Add `SpannerConstants` to `hyperscaledb-provider-spanner` mirroring the structure of `CosmosConstants` and `DynamoConstants`.
- **Rationale**: FR-049 requires all hard-coded string literals in each provider to be centralized in a provider-specific constants class. The Spanner provider was missing this class.
- **Alternatives considered**: Inline constants (status quo) — violates FR-049.

---

## Decision 17: OperationNamesTest for duplicate prevention

- **Decision**: Add `OperationNamesTest` in `hyperscaledb-api` (or `hyperscaledb-conformance`) that reflectively reads all `public static final String` fields in `OperationNames` and asserts no two fields share the same value.
- **Rationale**: SC-017 requires that no provider adapter re-declares a shared operation name string. A test catches duplicates at CI time. Reflective field scan avoids manually maintaining the test list.
- **Alternatives considered**: Code review only — not machine-enforceable.

---

## Decision 18: Spanner structured diagnostics

- **Decision**: Add `logItemDiagnostics` and `logQueryDiagnostics` helpers to `SpannerProviderClient`, emitting `spanner.diagnostics op=... db=... col=... itemCount=... hasMore=...` at `DEBUG` level. Spanner does not expose a per-RPC request ID at the Java client level in the same form as Cosmos activityId or DynamoDB requestId, so the log line will include `op`, `db`, `col`, and query result metrics only.
- **Rationale**: FR-051 requires DEBUG-level structured diagnostics on every successful data-plane operation. Spanner had only no-op provisioning debug logs.
- **Alternatives considered**: Skip Spanner diagnostics — violates FR-051.

---

## Decision 19: Result limit (Top N) implementation per provider

- **Decision**: Add `Integer limit` and `List<SortOrder> orderBy` to `QueryRequest.Builder`.
  - **Cosmos DB**: Render `SELECT TOP N VALUE c ...` when limit is set; append `ORDER BY c.{field} ASC/DESC` to the generated SQL.
  - **DynamoDB**: Append `LIMIT N` to the generated PartiQL statement. ORDER BY capability is absent (DynamoDB PartiQL has no ORDER BY).
  - **Spanner**: Append `LIMIT N` and `ORDER BY {field} ASC/DESC` to generated GoogleSQL.
- **Rationale**: All three providers support LIMIT natively. ORDER BY is a documented capability-gated feature on Cosmos and Spanner only (absent on DynamoDB). Keeping limit and orderBy in `QueryRequest` is consistent with the existing builder pattern.
- **Alternatives considered**:
  - Client-side limit: wasteful at scale, violates spec intent.
  - Separate `LimitedQueryRequest` subtype: unnecessary complexity, breaks the existing builder pattern.

---

## Decision 20: SortOrder and SortDirection types

- **Decision**: Add `SortDirection` enum (`ASC`, `DESC`) and `SortOrder` record/class (`field: String`, `direction: SortDirection`) to `hyperscaledb-api` in the `com.hyperscaledb.api` package.
- **Rationale**: A typed representation prevents string-based errors and supports future multi-field ORDER BY.
- **Alternatives considered**: String direction only — less type-safe, no IDE completions.

---

## Decision 21: Document TTL representation

- **Decision**: Add `Integer ttlSeconds` field to `OperationOptions` (or as a separate field on write method signatures via overloads). Prefer `OperationOptions` to keep the write API surface stable.
  - **Cosmos DB**: Set `_ttl` field on the document JSON node before writing (Cosmos evaluates `_ttl` as seconds from document creation).
  - **DynamoDB**: Add a `ttlExpiry` attribute set to `Instant.now().plus(ttlSeconds).getEpochSecond()` (epoch seconds). The DynamoDB TTL attribute name is configurable per container but defaults to `ttlExpiry`.
  - **Spanner**: No native row-level TTL. Capability check at request time → `UNSUPPORTED_CAPABILITY` error.
- **Rationale**: `OperationOptions` is the established per-request options carrier. TTL is a per-request option for writes. Cosmos and DynamoDB support it natively. Spanner does not.
- **Alternatives considered**: TTL as a separate method parameter — would break all write method signatures.

---

## Decision 22: DocumentMetadata return type

- **Decision**: Add `DocumentMetadata` class to `hyperscaledb-api` with fields:
  - `OptionalLong remainingTtlSeconds` (absent if TTL not set or provider doesn't expose it)
  - `Optional<Instant> writeTimestamp` (absent if provider doesn't expose it)
  Provider mappings:
  - **Cosmos DB**: `_ts` (epoch seconds, last write) → `writeTimestamp`; `_ttl` + `_ts` → `remainingTtlSeconds`.
  - **DynamoDB**: `ttlExpiry` (epoch seconds) → `remainingTtlSeconds`; no native write timestamp at item level (absent).
  - **Spanner**: Commit timestamp column (`lastModified TIMESTAMP OPTIONS (allow_commit_timestamp=true)`) → `writeTimestamp`; no native TTL → absent.
- **Decision on opt-in**: Metadata retrieval is opt-in via `OperationOptions.includeMetadata(boolean)`. Default false.
  Read return type changes: `HyperscaleDbClient.read()` returns `DocumentResult` wrapping both the `ObjectNode` payload and optional `DocumentMetadata`.
- **Rationale**: Opt-in avoids breaking existing callers. `DocumentResult` keeps backward compatibility by providing a `.document()` accessor.
- **Alternatives considered**: Always return metadata — extra provider overhead for Cosmos (requires projecting system fields), breaks existing API contracts.

---

## Decision 23: Uniform document size enforcement (400 KB)

- **Decision**: Add a `DocumentSizeValidator` utility in `hyperscaledb-api` or `hyperscaledb-spi` that serializes `ObjectNode` to UTF-8 bytes and checks against `MAX_DOCUMENT_SIZE_BYTES = 400 * 1024`. Validation occurs in `DefaultHyperscaleDbClient` before delegating to the provider adapter, so the check is provider-agnostic and happens once.
- **Decision on programmatic access**: Expose `HyperscaleDbClient.getDocumentSizeLimit()` → returns `400 * 1024`.
- **Rationale**: DynamoDB's 400 KB limit is the lowest common denominator. Enforcing at the `DefaultHyperscaleDbClient` layer means no provider adapter needs to duplicate the check. Serializing to check size is deterministic and does not require provider I/O.
- **Alternatives considered**:
  - Enforce per-provider adapter: duplicates logic, inconsistent enforcement.
  - Enforce at SPI layer: coupling SPI to a specific limit.

---

## Decision 24: Capability additions for new features

- **Decision**: Add capability constants to `Capability`:
  - `ROW_LEVEL_TTL` — row-level document TTL support
  - `WRITE_TIMESTAMP` — write timestamp metadata
  - `RESULT_LIMIT` — Top N result limiting (all three providers support this)
  - `TOP_N` — alias for `RESULT_LIMIT` (same capability constant)
- **Rationale**: Follows the constitution's capability-based API principle. FR-054 and FR-057 explicitly require capability gating.
- Provider support matrix (new caps):
  | Capability | Cosmos | DynamoDB | Spanner |
  |---|---|---|---|
  | `ROW_LEVEL_TTL` | ✅ | ✅ | ❌ |
  | `WRITE_TIMESTAMP` | ✅ | ❌ | ✅ |
  | `ORDER_BY` | ✅ (existing) | ❌ (existing) | ✅ (existing) |
  | `RESULT_LIMIT` | ✅ | ✅ | ✅ |

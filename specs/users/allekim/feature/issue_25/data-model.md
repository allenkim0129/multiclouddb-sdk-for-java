# Data Model: Issue 25 — Result Set Control, TTL/Write Metadata, Uniform Document Size, Provider Diagnostics

**Branch**: `users/allekim/feature/issue_25`  
**Parent data model**: [`specs/001-clouddb-sdk/data-model.md`](../../../../001-clouddb-sdk/data-model.md)

This document extends the parent data model with new entities and modifications introduced by the spec update.

---

## New/Modified Entities

### SortDirection (new enum)

Location: `hyperscaledb-api/src/main/java/com/hyperscaledb/api/SortDirection.java`

Values:
- `ASC` — ascending order
- `DESC` — descending order

### SortOrder (new class)

Location: `hyperscaledb-api/src/main/java/com/hyperscaledb/api/SortOrder.java`

Fields:
- `field: String` (required, non-empty — field name to sort on)
- `direction: SortDirection` (required)

Validation:
- field must be non-empty

### QueryRequest (modified)

New optional fields:
- `limit: Integer` — maximum number of items to return (Top N). `null` means no limit.
- `orderBy: List<SortOrder>` — zero or more sort specifications. Empty list means no ordering.

Builder methods added:
- `limit(int n)` — sets Top N
- `orderBy(String field, SortDirection direction)` — appends a sort specification

Constraints:
- `limit` ≥ 1 when set
- `orderBy` is capability-gated; fails at translation time on providers that do not support it
- `limit` and `orderBy` are combinable with `expression`, `nativeExpression`, `partitionKey`, and `pageSize`

### DocumentMetadata (new class)

Location: `hyperscaledb-api/src/main/java/com/hyperscaledb/api/DocumentMetadata.java`

Fields:
- `remainingTtlSeconds: OptionalLong` — approximate remaining TTL in seconds. Empty if TTL not set or provider does not expose it.
- `writeTimestamp: Optional<Instant>` — last write timestamp. Empty if provider does not expose it.

Provider availability:
| Field | Cosmos DB | DynamoDB | Spanner |
|---|---|---|---|
| `remainingTtlSeconds` | ✅ via `_ttl` + `_ts` | ✅ via `ttlExpiry` attribute | ❌ absent |
| `writeTimestamp` | ✅ via `_ts` | ❌ absent | ✅ via `lastModified` column |

### DocumentResult (new class)

Location: `hyperscaledb-api/src/main/java/com/hyperscaledb/api/DocumentResult.java`

Fields:
- `document: ObjectNode` (required — the document payload)
- `metadata: Optional<DocumentMetadata>` — present only when `OperationOptions.includeMetadata()` is true

Methods:
- `document()` → `ObjectNode`
- `metadata()` → `Optional<DocumentMetadata>`

**API impact**: `HyperscaleDbClient.read()` currently returns `ObjectNode`. The return type will change to `DocumentResult`. Existing callers use `.document()` to get the `ObjectNode`.

### OperationOptions (modified)

New optional field:
- `ttlSeconds: Integer` — per-request TTL for write operations (`create`, `upsert`). `null` means no TTL.
- `includeMetadata: boolean` — whether to return `DocumentMetadata` on read. Default `false`.

Builder methods added:
- `ttlSeconds(int seconds)` — sets TTL for write operations
- `includeMetadata(boolean include)` — enables metadata retrieval on reads

Constraints:
- `ttlSeconds` ≥ 1 when set
- `ttlSeconds` is capability-gated (`ROW_LEVEL_TTL`); fails fast on Spanner

### Capability (modified — new constants)

New constants added to `Capability`:
- `ROW_LEVEL_TTL = "row_level_ttl"` — provider supports per-document expiry
- `WRITE_TIMESTAMP = "write_timestamp"` — provider exposes last-write timestamp in document metadata
- `RESULT_LIMIT = "result_limit"` — provider supports Top N result capping

### HyperscaleDbClient (modified)

Method signature change:
- `read(ResourceAddress, Key, OperationOptions) → DocumentResult` (was `ObjectNode`)

New method:
- `getDocumentSizeLimit() → int` — returns the uniform maximum document size in bytes (400 × 1024)

### DocumentSizeValidator (new utility)

Location: `hyperscaledb-api/src/main/java/com/hyperscaledb/api/internal/DocumentSizeValidator.java`

Static utility:
- `MAX_DOCUMENT_SIZE_BYTES = 400 * 1024`
- `validate(ObjectNode document)` — throws `HyperscaleDbException(INVALID_REQUEST)` when serialized UTF-8 size exceeds limit

---

## Provider Schema Changes

### Cosmos DB

- No schema change. `_ttl` and `_ts` are system-managed properties read from the response and written to the document JSON.

### DynamoDB

- `ttlExpiry` attribute (Number, epoch seconds) is written when TTL is set. This is the standard DynamoDB TTL attribute convention; the attribute name is defined in `DynamoConstants`.

### Spanner

- `lastModified TIMESTAMP OPTIONS (allow_commit_timestamp=true)` column added to table DDL in `ensureContainer`. This enables write timestamp capture on each upsert/create.
- `SpannerConstants` class added (mirrors `CosmosConstants`/`DynamoConstants`).

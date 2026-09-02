# Phase 0 Research: Portable Partial Update

**Branch**: `002-partial-update`
**Reconciled**: 2026-09-02 for the focused Cosmos/Dynamo scope

## Decision 1 — Keep the existing Java API

Retain both `update()` overloads and `Map<String,Object>`. Rename only the
parameter from `document` to `fields`.

**Why**: Java parameter names are not binary API, and a new patch type or method
would expand scope unnecessarily.

## Decision 2 — Use Spanner only as the behavioral baseline

Do not change `SpannerProviderClient`.

The existing implementation already preserves omitted columns through a
partial mutation and merges valid `FIELD_DATA` metadata in a read-write
transaction. It is fixed-schema: caller fields must map to existing columns.
It binds null as STRING and stores maps/lists through its existing encoded
STRING mapping.

**Rejected**:

- schema-aware typed nulls;
- reading requested columns for type discovery;
- schema/row precedence changes;
- DDL or automatic column creation;
- new Spanner provider tests; and
- a Spanner E2E schema helper.

Those changes are not needed to migrate Cosmos and Dynamo and would alter the
baseline this feature is meant to match.

## Decision 3 — Define a shallow absolute operation

Present fields are set/replaced; omitted fields survive; map/list values replace
as units; null is a stored null for a supported mapping.

**Why**: Cosmos `set`, Dynamo `SET`, and the existing Spanner mutation share
these semantics. Absolute assignments are replay-idempotent.

Recursive merge, nested paths, remove, increment, and conditional field updates
are out of scope.

## Decision 4 — Centralize preflight

The default client validates:

1. non-null/non-empty map;
2. non-null/non-empty/non-blank names;
3. reserved names and underscore prefix;
4. case-insensitive collisions;
5. update TTL;
6. exact 408,576-byte serialized size; and
7. core capability support.

**Why**: one preflight gives all providers the same category and zero-I/O
behavior.

Accepted names are literal and are not trimmed. Cosmos and Dynamo can directly
support punctuation through escaping/aliases. Spanner can use a literal name
only when a matching column already exists; no new shared Spanner fixture is
added.

## Decision 5 — Reject TTL on update

`OperationOptions.ttlSeconds()` remains create/upsert-only. A non-null value on
`update()` is `INVALID_REQUEST` before provider I/O.

**Why**: provider-specific TTL mutation would break portable behavior and make
replay time-relative.

## Decision 6 — Keep two capability declarations

- `partial_update`: core shallow set/replace behavior, internally gated.
- `partial_update_extended_payload`: no lower provider request or
  resulting-item envelope for field mappings already supported by that
  provider.

Cosmos and Dynamo declare the extension unsupported because their native
request/result constraints can bind before the shared 408,576-byte limit.
Spanner declares it supported for its existing fixed-schema mappings; this is
not a claim that arbitrary columns or value types are supported.

Every provider declares all 19 known capability names.

## Decision 7 — Cosmos uses direct patch plus one atomic wide batch

- up to 10 fields: one `patchItem`;
- wider maps: one same-item, same-partition `CosmosBatch` of patch chunks;
- no adapter read, replace, or retry loop.

Field names are encoded as one RFC 6901 segment.

For wide requests, mirror the public SDK JSON shape and reject more than 100
batch operations or more than 2,097,152 UTF-8 bytes before I/O.

**Rejected**:

- read/merge/replace, because it adds RU cost and races;
- independent patch requests, because they are not atomic; and
- private SDK serialization APIs, because they are not stable public contract.

## Decision 8 — Cosmos batch errors skip 424

Select the first usable failed operation status other than 424, then a usable
aggregate status, then return a sanitized no-root `PROVIDER_ERROR`.

HTTP 424 represents rollback dependency, not root cause. HTTP 408 and 410 are
transient/retryable for CRUD/update; 410 substatus is retained.

## Decision 9 — Cosmos write bodies can be disabled

Use `contentResponseOnWriteEnabled(false)`.

**Why**: all portable writes return `void`; existing paths use only response
metadata. Focused tests cover constructor configuration and create/update/
upsert consistency invariants.

## Decision 10 — Dynamo uses one aliased UpdateItem

Generate stable `#fN`/`:vN` aliases, an aliased
`attribute_exists(#pk)` guard, and one `SET` expression.

Map values through the structured item mapper. Measure the complete update
expression in UTF-8; 4,096 bytes passes and 4,097 fails.

Conditional failure maps to `NOT_FOUND`. No read, `PutItem`, or adapter retry
loop is used.

## Decision 11 — Normalize DynamoDB's state-dependent result-item limit

An update can have a small fields map and short expression but still push an
existing item above DynamoDB's 409,600-byte limit. Do not read and merge before
the write. Attempt the one conditional `UpdateItem`, then recognize only the
size-specific `ValidationException` message for `update()`.

That variant maps to non-retryable `UNSUPPORTED_CAPABILITY` with
`reason=dynamodb_result_item_size_limit` and
`maximumResultBytes=409600`. Other `ValidationException` messages remain
`INVALID_REQUEST`; the native cause and sanitized code/status/request ID/service
details are retained without payload data.

**Why**: a read preflight adds cost and a race. DynamoDB already rejects the
oversized result atomically, so normalizing that one native response preserves
state and portability with one attempted write.

## Decision 12 — Normalize Cosmos DB's state-dependent result-item limit

An update can have a small fields map and valid native request envelope but
still push an existing Cosmos document above 2,097,152 bytes. Do not read and
merge before the write. Attempt the one direct patch or atomic batch, then map
HTTP 413 from `update()` to non-retryable `UNSUPPORTED_CAPABILITY` with
`reason=cosmos_result_item_size_limit` and
`maximumResultBytes=2097152`.

Direct exceptions retain their cause and sanitized native metadata; failed
batches retain sanitized aggregate/result diagnostics. HTTP 413 from other
operations keeps the general provider-error mapping.

**Why**: a read preflight adds RU cost and a race. Cosmos rejects the
oversized result atomically, so update-scoped status normalization preserves
state and portability with one attempted native write.

## Decision 13 — Keep shared runtime assertions portable

Shared conformance uses fields and shapes already present in the existing
three-provider fixtures. It proves unchanged state for invalid names, update
TTL, and a 408,577-byte fields map, plus `NOT_FOUND` without create for a wide
missing-item update. The exact 408,576-byte positive boundary stays at the API
validator layer because provider-native envelopes and fixed schema can prevent
a portable runtime acceptance assertion.

The Cosmos and Dynamo result-item regressions belong in their concrete
conformance classes because they deliberately exercise provider-native limits.
No provider branch is added to the shared base.

## Decision 14 — Preserve migration intent

Callers that require complete replacement move to `upsert()` and must be told
that it creates a missing document. TTL-bearing updates also move to a complete
create/upsert write.

No compatibility flag or new `replace()` method is added.

## Baseline repository gaps

Issues #102 (native client access), #103 (cancellation), and #104 (configurable
safe retries) predate this feature and remain out of scope.

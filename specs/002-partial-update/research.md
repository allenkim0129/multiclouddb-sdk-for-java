# Phase 0 Research: Portable Partial Update

**Branch**: `002-partial-update`
**Reconciled**: 2026-09-03 for the focused implementation and portability-review remediation

## Decision 1 — Keep the existing Java API

Retain both `update()` overloads and `Map<String,Object>`. Rename only the
parameter from `document` to `fields`.

**Why**: Java parameter names are not binary API, and a new patch type or method
would expand scope unnecessarily.

## Decision 2 — Keep Spanner fixed-schema and make casing explicit

Retain Spanner's read-write transaction, `FIELD_DATA` merge, STRING-null
binding, and encoded STRING map/list mapping. Add only an exact-case guard and
logical-name projection because GoogleSQL identifiers are case-insensitive:

1. compare requested names with established `FIELD_DATA` names;
2. query `INFORMATION_SCHEMA.COLUMNS` for previously unseen names;
3. reject a case-only alias as non-retryable `UNSUPPORTED_CAPABILITY`; and
4. project the accepted logical spelling recorded in `FIELD_DATA`.

**Rejected**:

- schema-aware typed nulls or type discovery;
- DDL or automatic column creation;
- new Spanner schema fixtures; and
- a Spanner E2E schema helper.

The narrow guard is required to prevent silent cross-provider field aliasing;
the broader fixed-schema/value baseline remains unchanged.

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

## Decision 6 — Keep three capability declarations

- `partial_update`: core shallow set/replace behavior, internally gated.
- `partial_update_extended_payload`: no lower provider request or
  resulting-item envelope for field mappings already supported by that
  provider.
- `partial_update_case_sensitive_fields`: case-distinct names retain separate
  literal identities.

Cosmos and Dynamo declare the payload extension unsupported and case-sensitive
identity supported. Spanner declares extended payload supported for fixed-schema
mappings and case-sensitive identity unsupported.

Every provider declares all 20 known capability names.

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
three-provider fixtures. It proves unchanged state for every invalid-map/name
class, update TTL, and a 408,577-byte fields map, plus `NOT_FOUND` without create
for a wide missing-item update. Case-distinct identity is asserted when the new
capability is supported and explicitly rejected otherwise. The exact
408,576-byte positive boundary executes only when
`partial_update_extended_payload` is advertised, so Spanner performs the
provider call while Cosmos and Dynamo skip it under their declared envelopes.

The Cosmos and Dynamo result-item regressions remain in their concrete
conformance classes because they deliberately exercise provider-native limits.
Capability gates, not provider-type branches, control shared assertions.

## Decision 14 — Preserve migration intent

Callers that require complete replacement move to `upsert()` and must be told
that it creates a missing document. TTL-bearing updates also move to a complete
create/upsert write.

No compatibility flag or new `replace()` method is added.

## Baseline repository gaps

Issues #102 (native client access), #103 (cancellation), and #104 (configurable
safe retries) predate this feature and remain out of scope.

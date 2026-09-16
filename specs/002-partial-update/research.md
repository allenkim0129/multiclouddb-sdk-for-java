# Phase 0 Research: Portable Partial Update

**Branch**: `002-partial-update`
**Reconciled**: 2026-09-15 for the implemented Cosmos/Dynamo contract and API-default Spanner unsupported boundary

## Decision 1 — Keep the existing Java API

Retain both `update()` overloads and `Map<String,Object>`. Rename only the
parameter from `document` to `fields`.

**Why**: Java parameter names are not binary API, and a new patch type or method
would expand scope unnecessarily.

## Decision 2 — Exclude Spanner from this feature release

Keep the Spanner production source unchanged and deliberately leave portable
partial update unsupported in this feature. Changelog and provider-direct test
alignment do not alter released behavior. `CapabilitySet` supplies unsupported
defaults for all three Feature 002 capabilities when the provider omits them.
The default client gates the operation, so a valid Spanner update returns
non-retryable `UNSUPPORTED_CAPABILITY` before provider delegation.

**Why**: This release intentionally limits the supported native update paths to
Cosmos DB and DynamoDB rather than silently invoking Spanner's legacy behavior
or requiring a coordinated Spanner provider release. The Spanner emulator ran
shared-validation/capability-gate coverage and the provider-direct legacy
regression. No live production Spanner validation was performed or is claimed;
future portable Spanner update support requires a separate release decision.

**Rejected**:

- retaining the casing guard, because it changes an unreleased provider module;
- bypassing the core gate for Spanner, because that restores silent field-case
  divergence; and
- adding a provider-ID special case in shared code.

## Decision 3 — Define a shallow absolute operation

Present fields are set/replaced; omitted fields survive; map/list values replace
as units; null is a stored null for a supported mapping.

**Why**: Cosmos `set` and Dynamo `SET` share these semantics. Absolute
assignments are replay-idempotent for logical document fields; provider-maintained
metadata and TTL timing are not part of that guarantee.

Recursive merge, nested paths, remove, increment, and conditional field updates
are out of scope.

## Decision 4 — Centralize preflight

The default client validates:

1. non-null/non-empty map;
2. non-null/non-empty/non-blank names no longer than 50,000 UTF-8 bytes;
3. reserved names and underscore prefix;
4. case-distinct non-reserved names remain separate literal fields, including
   both variants in one map;
5. update TTL;
6. reject binary values, including values hidden in POJOs;
7. bounded SDK-owned Jackson serialization, rejecting non-collection iterables
   before iteration;
8. portable 390 KiB serialized-size limit;
9. replacement map/list depth up to 31 containers;
10. every nested field name no longer than 50,000 UTF-8 bytes;
11. portable 390 KiB structural footprint; and
12. core capability support.

**Why**: one preflight gives all providers the same category and zero-I/O
behavior. Top-level update paths can still carry deeply nested replacement values,
and compact JSON such as a dense list of empty containers understates DynamoDB's
native item accounting. The structure-aware pass applies the lowest common
denominator before the capability gate while inspecting only incoming fields.
Complete documents use the same binary-value, depth, nested-name, and structural
limits; a null document, every case-insensitive top-level
provider-owned name (`id`, `partitionKey`, `sortKey`, `ttl`, `ttlExpiry`,
`data`), and every underscore-prefixed top-level name are rejected before I/O.
Case-distinct non-reserved top-level names remain valid.
Create, upsert, and update snapshot top-level maps and use bounded SDK-owned
Jackson serialization while inspecting nested values before delegation.
`PortableWriteLimits` exposes exactly five input/structure constants. The 50,000-byte name
bound is at or below the AWS SDK's 50,000-character DynamoDB response-parser
limit, including for multibyte UTF-8 names.
Existing-state result size remains native and does not justify a read/merge
preflight.

Accepted names are literal and are not trimmed. Cosmos and Dynamo support
punctuation through escaping and aliases.

## Decision 5 — Reject TTL on update

`OperationOptions.ttlSeconds()` remains create/upsert-only. A non-null value on
`update()` is `INVALID_REQUEST` before provider I/O.

**Why**: provider-specific TTL mutation would break portable behavior and make
replay time-relative.

## Decision 6 — Separate the portable and extended result envelopes

- `partial_update`: core shallow set/replace behavior when serialized result JSON
  and portable structural footprint are each at most 390 KiB.
- `partial_update_extended_result_size`: support above either 390 KiB base bound
  through the provider documented native ceiling.

Cosmos advertises the core and extended-result capabilities and supports
extended results up to 2 MiB.
Dynamo advertises the core capability and explicitly marks extended results
unsupported. Spanner omits them; its capability set is normalized independently
from the TTL-expiry capability described below.
Normalization is capability-specific and does not populate unrelated omissions
in arbitrary legacy or third-party sets.

Result size depends on stored state. A read/merge preflight would add cost and a
race, so callers needing portability keep results within both 390 KiB bounds and inspect the
extended capability before relying on larger results. Native failures retain stable,
structured reasons and limits.

## Decision 7 — Separate TTL-expiry preservation from partial update

Add `partial_update_preserves_ttl_expiry` without changing the meaning of
`partial_update` or `partial_update_extended_result_size`.

- DynamoDB advertises support because the `UpdateItem` expression does not
  assign `ttlExpiry`, so the existing absolute expiry is unchanged.
- Cosmos DB advertises unsupported because `patchItem` advances `_ts`, which
  restarts the countdown for a TTL-bearing item.
- Spanner and legacy omissions receive the API-default unsupported value with
  no Spanner production change.

**Why**: base shallow update is portable even though provider TTL clocks differ.
Callers requiring fixed absolute expiry need a separate discoverable guarantee;
silently treating `partial_update` as that guarantee would be incorrect.

## Decision 8 — Keep every accepted update to one native write

The portable contract accepts at most 10 fields per call. Shared preflight
rejects wider maps before provider delegation. Cosmos uses one `patchItem`;
DynamoDB uses one `UpdateItem`. The Cosmos planner sorts literal field names
before constructing patch operations so caller map iteration order cannot change
the native plan.

**Rejected**:

- Cosmos transactional batches, because patch-chunk count creates unbounded RU
  asymmetry and intermediate item states can depend on caller map order;
- read/merge/replace, because it adds RU cost and races; and
- independent patch requests, because they are not atomic.

## Decision 9 — Cosmos write bodies can be disabled

Use `contentResponseOnWriteEnabled(false)`.

**Why**: all portable writes return `void`; existing paths use only response
metadata. Focused tests cover constructor configuration and create/update/
upsert consistency invariants.

## Decision 10 — Dynamo uses one aliased UpdateItem

Generate stable `#fN`/`:vN` aliases, an aliased
`attribute_exists(#pk)` guard, and one `SET` expression.

Map values through the structured item mapper. The shared 10-field limit keeps
the generated expression safely below the DynamoDB native expression ceiling.
The planner retains a defensive size check for direct SPI misuse, but that path
is not part of the portable public contract.

Conditional failure maps to `NOT_FOUND`. No read, `PutItem`, or adapter retry
loop is used.

## Decision 11 — Normalize DynamoDB's state-dependent result-item limit

An update can have a small fields map and short expression but still push an
existing item above the DynamoDB native limit. Do not read and merge before
the write. Attempt the one conditional `UpdateItem`, then recognize only the
size-specific `ValidationException` message for `update()`.

That variant maps to non-retryable `UNSUPPORTED_CAPABILITY` with
`reason=dynamodb_result_item_size_limit` and
`maximumResultBytes`. Other `ValidationException` messages remain
`INVALID_REQUEST`; the native cause and sanitized code/status/request ID/service
details are retained without payload data.

**Why**: a read preflight adds cost and a race. DynamoDB already rejects the
oversized result atomically, so normalizing that one native response preserves
state and portability with one attempted write.

## Decision 12 — Normalize Cosmos DB's state-dependent result-item limit

An update can have a small fields map and valid shared preflight but
still push an existing Cosmos document above 2,097,152 bytes. Do not read and
merge before the write. Attempt the one direct patch, then map
HTTP 413 from `update()` to non-retryable `UNSUPPORTED_CAPABILITY` with
`reason=cosmos_result_item_size_limit` and
`maximumResultBytes`.

Direct exceptions retain their cause and sanitized native metadata. HTTP 413 from other
operations keeps the general provider-error mapping.

**Why**: a read preflight adds RU cost and a race. Cosmos rejects the
oversized result atomically, so update-scoped status normalization preserves
state and portability with one attempted native write.

## Decision 13 — Keep shared runtime assertions capability-driven

Shared invalid-map/name, update-TTL, and over-limit assertions run on all
providers because validation precedes the core gate. Supported behavior runs
only where `partial_update` is advertised. A dedicated assertion verifies that
the API defaults Spanner's omitted capability to unsupported and fails locally
with `UNSUPPORTED_CAPABILITY` and `capability=partial_update`.

Case-distinct identity, including `foo` and `Foo` in one atomic request, runs
directly on Cosmos and DynamoDB as part of the base contract. API
recording-provider tests prove the exact 390 KiB boundary reaches
delegation while one byte over does not. When each emulator is available, shared conformance verifies exact-limit
create/upsert on every provider and partial update on Cosmos/DynamoDB.
Concrete Cosmos and DynamoDB regressions continue to exercise their native
result-item limits.
Capability conformance asserts 20 effective rows for each built-in provider,
the Cosmos-supported/Dynamo-and-Spanner-unsupported extended-result matrix, and
the Dynamo-supported/Cosmos-and-Spanner-unsupported TTL-preservation matrix.
An older Spanner provider's 17 declarations receive exactly the three Feature
002 unsupported defaults, while unrelated omissions remain absent.

## Decision 14 — Preserve migration intent

Callers that require complete replacement move to `upsert()` and must be told
that it creates a missing document. TTL-bearing updates also move to a complete
create/upsert write.

That migration changes TTL through a complete write. A caller performing a
partial update on an already TTL-bearing item and requiring the absolute expiry
to stay fixed instead checks `partial_update_preserves_ttl_expiry`.

No compatibility flag or new `replace()` method is added. Read-then-upsert is
not atomic, and this release has no exact portable atomic full-document
replace-if-present equivalent.

## Current validation record

- DynamoDB Local validation ran for the supported path.
- The Spanner emulator ran the shared unsupported-gate coverage and
  provider-direct legacy regression; this is not live production Spanner
  validation and does not change the unsupported capability state.
- The Cosmos emulator is unavailable in the current environment, so T061
  remains pending.

## Baseline repository gaps

Issues #102 (native client access), #103 (cancellation), and #104 (configurable
safe retries) predate this feature and remain out of scope.

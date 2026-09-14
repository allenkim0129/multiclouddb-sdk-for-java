# Feature Specification: Portable Partial Update

**Branch**: `002-partial-update`
**Status**: Complete; Cosmos DB and DynamoDB validated, Spanner deferred pending live-account validation

## Scope decision

`MulticloudDbClient.update()` becomes a capability-gated shallow set/replace
operation. Cosmos DB and DynamoDB move from full replacement to native partial
update and advertise `PARTIAL_UPDATE`.

The Spanner provider data path is not part of this feature release because a
live account is not currently available for release-grade validation. Files
under `multiclouddb-provider-spanner/` remain unchanged except for the capability
declaration and changelog. Spanner explicitly declares the sole feature-002
partial-update capability unsupported. After shared validation, a valid Spanner
`update()` call fails at the default client core capability gate with
non-retryable `UNSUPPORTED_CAPABILITY`, `capability=partial_update`, and zero
Spanner I/O. Support can be enabled after live-account validation is available.

## User scenarios

### US1 — Update selected fields without losing omitted data

Given an existing document with `title`, `status`, and `owner`, updating only
`status` changes `status` and preserves `title` and `owner`.

**Acceptance**:

1. Present fields are set/replaced.
2. Omitted fields remain unchanged.
3. A missing document returns `NOT_FOUND` and is not created.
4. The assignments commit atomically.

### US2 — Use predictable shallow value semantics

Scalar values replace scalars. A map or list replaces the complete top-level
value. Java null stores provider-native null for a supported mapping and never
means remove.

Shared conformance runs partial-update behavior on providers advertising the
core capability. Provider-neutral validation still runs before the gate for all
providers.

### US3 — Receive deterministic native-envelope failures

Cosmos DB and DynamoDB normalize lower native partial-update envelopes to
non-retryable `UNSUPPORTED_CAPABILITY` with stable reasons and structured
size/count details.
Maps above 10 fields fail in shared preflight. Oversized Dynamo update expressions fail before
provider I/O. Cosmos DB's state-dependent 2,097,152-byte resulting-document
limit is reported after one attempted patch request, and DynamoDB's
state-dependent native result-item limit is reported after one attempted
`UpdateItem`; no read/merge preflight is added.

### US4 — Migrate callers that relied on replacement

Cosmos DB and DynamoDB callers that used `update()` as complete replacement
move to `upsert(address, key, completeDocument)`. Documentation must warn that
`upsert()` creates a missing document and is not an atomic guarded replacement.

### US5 — Respect the provider release boundary

Cosmos DB and DynamoDB preserve distinct `title` and `TITLE` fields across an
update. Spanner explicitly declares the core operation unsupported, so
a valid update is rejected before provider delegation.
## Functional requirements

- **FR-001**: `update()` MUST treat its map as literal top-level fields to set
  or replace.
- **FR-002**: Omitted top-level fields MUST be preserved.
- **FR-003**: Map and list values MUST replace the complete named top-level
  value; recursive merge is out of scope.
- **FR-004**: Java null MUST store null for participating provider mappings that
  support the value.
- **FR-005**: A missing document MUST return `NOT_FOUND` and MUST NOT be
  created.
- **FR-006**: All assignments in one call MUST commit atomically and replaying
  the same absolute assignments MUST be idempotent.
- **FR-007**: Shared preflight MUST reject a null/empty map, a map above 10 fields, and null, empty, or
  blank field names as non-retryable `INVALID_REQUEST`.
- **FR-008**: Shared preflight MUST reject, case-insensitively, `id`,
  `partitionKey`, `sortKey`, `ttl`, `ttlExpiry`, and `data`; names beginning
  with `_`; and case-insensitive duplicates.
- **FR-009**: Accepted field names MUST NOT be trimmed or rewritten.
- **FR-010**: A non-null `OperationOptions.ttlSeconds()` on `update()` MUST be
  rejected before provider I/O. TTL remains create/upsert-only.
- **FR-011**: The serialized field map limit MUST be the portable 390 KiB boundary.
  A payload at the boundary passes shared preflight and a larger payload fails.
- **FR-012**: After validation and before delegation, the default client MUST
  gate `Capability.PARTIAL_UPDATE`; an unsupported provider receives
  non-retryable `UNSUPPORTED_CAPABILITY` with
  `providerDetails.capability=partial_update`.
- **FR-013**: Lower native request or resulting-item envelope failures MUST be
  non-retryable `UNSUPPORTED_CAPABILITY` errors with stable
  `providerDetails.reason` and limit values. They MUST NOT define or require a
  second partial-update capability.
- **FR-014**: Cosmos DB, DynamoDB, and Spanner MUST declare all 18 known capabilities.
  Spanner MUST explicitly declare `PARTIAL_UPDATE` unsupported.
- **FR-015**: A valid `update()` against a provider that does not support
  `PARTIAL_UPDATE` MUST fail at the shared gate with non-retryable
  `UNSUPPORTED_CAPABILITY`, `capability=partial_update`, and zero provider I/O.
- **FR-016**: Cosmos DB MUST encode each raw field name as one RFC 6901 segment
  (`~` → `~0`, `/` → `~1`) and use `set`.
- **FR-017**: Cosmos DB MUST issue one direct `patchItem` for every accepted update.
- **FR-018**: Shared preflight MUST reject more than 10 fields with non-retryable
  `INVALID_REQUEST` before provider delegation.
- **FR-019**: The Cosmos planner MUST defensively reject direct SPI calls above 10
  fields before I/O.
- **FR-020**: Cosmos DB MUST NOT add an adapter read, replace, or retry loop.
- **FR-021**: Cosmos planner output MUST be deterministic regardless of the caller map's
  iteration order. Field names MUST be ordered before native patch construction;
  no transactional batch may be constructed for `update()`.
- **FR-022**: Cosmos CRUD/update HTTP 408 and 410 failures MUST be transient and
  retryable; 410 substatus MUST be preserved.
- **FR-023**: Cosmos write response bodies MAY be disabled only while status,
  activity ID, request charge, duration, and diagnostics used by existing
  write paths remain available.
- **FR-024**: DynamoDB MUST issue one conditional `UpdateItem` with stable name
  and value aliases, one `SET` assignment per field, and an aliased
  `attribute_exists(partitionKey)` guard.
- **FR-025**: DynamoDB values MUST preserve null, scalar, map, and list shapes.
- **FR-026**: The shared 10-field limit MUST keep every portable DynamoDB
 update safely below the native expression ceiling. Any defensive planner
 limit for direct SPI misuse is not part of the public error contract.
- **FR-027**: DynamoDB conditional failure on the existence guard MUST map to
  `NOT_FOUND`; no read, `PutItem`, or adapter retry loop may be added.
- **FR-028**: Provider diagnostics MUST be concise and MUST NOT log field
  values, serialized request bodies, credentials, or authorization data.
- **FR-029**: Shared conformance MUST run supported partial-update behavior only
  when `PARTIAL_UPDATE` is advertised, while retaining provider-neutral
  preflight and unsupported-gate assertions for Spanner.
- **FR-030**: Migration documentation MUST direct replacement callers to
  `upsert()` and explain its create-on-missing behavior.
- **FR-031**: On `update()` only, the DynamoDB `ValidationException` message
  variant indicating that the resulting item exceeds the maximum item size MUST
  map to non-retryable `UNSUPPORTED_CAPABILITY` with
  `reason=dynamodb_result_item_size_limit` and
  `maximumResultBytes`. Other `ValidationException` failures MUST remain
  `INVALID_REQUEST`. The original cause and sanitized native error code, status,
  request ID, and service details MUST be preserved where available, without
  payload data.
- **FR-032**: On `update()` only, Cosmos DB HTTP 413 MUST map to
  non-retryable `UNSUPPORTED_CAPABILITY` with
  `reason=cosmos_result_item_size_limit` and
  `maximumResultBytes`. The direct exception cause and sanitized
  native status, substatus, activity ID, and request charge MUST be preserved
  where available. The failed native patch MUST leave the item
  unchanged. HTTP 413 from other operations MUST retain the normal Cosmos
  provider-error mapping.
- **FR-033**: Providers advertising `PARTIAL_UPDATE` MUST preserve case-distinct
  field identities. Cosmos DB and DynamoDB MUST satisfy this base behavior.

## Provider behavior matrix

| Concern | Cosmos DB | DynamoDB | Spanner |
|---|---|---|---|
| Core partial update | Native patch | Native `UpdateItem` | Explicitly unsupported; shared gate rejects |
| Missing item | 404 → `NOT_FOUND` | failed existence condition → `NOT_FOUND` | Not reached |
| More than 10 fields | shared `INVALID_REQUEST`; no provider call | shared `INVALID_REQUEST`; no provider call | shared `INVALID_REQUEST`; no provider call |
| Lower native envelope | provider-native resulting-item limit | provider-native resulting-item limit | Not reached |
| Case-distinct names | preserved | preserved | Not part of this release |
| Adapter read/retry | no read/retry; result-size rejection follows one attempted patch | no read/retry; result-size rejection follows one attempted `UpdateItem` | zero provider I/O |

## Edge cases

- Empty maps, blank names, reserved names, underscore-prefixed names,
  case-insensitive collisions, update TTL, and over-limit maps fail before
  provider delegation.
- Names containing `.`, `/`, `~`, or surrounding spaces remain literal. Cosmos
  escapes them and Dynamo aliases them.
- Across calls, Cosmos and Dynamo preserve case-distinct names.
- A valid Spanner update stops at the shared core capability gate and performs
  no provider I/O.
- More than 10 fields fail shared validation before any provider
  calls.
- Cosmos planning sorts field names so caller map iteration order cannot alter
  native patch order.
- A small Cosmos update can pass shared preflight but fail with HTTP
  413 when the existing document plus assignments would exceed 2,097,152
  bytes. The failure becomes a reason-coded native-limit error and leaves
  the document unchanged.
- Dynamo reserved words and punctuation never appear directly in the update
  expression.
- A small Dynamo update can pass shared and expression preflight but fail when
  the existing item plus assignments would exceed 390 KiB. Only the
  item-size `ValidationException` variant becomes a reason-coded native-limit
  error; the failed native update leaves the item unchanged.

## Non-functional requirements

- **NFR-001**: Keep planners package-private, deterministic, and small.
- **NFR-002**: Use one adapter SDK invocation for each accepted Cosmos or
  Dynamo update. Vendor-managed retries are outside this count.
- **NFR-003**: Local validation failures perform zero provider I/O.
- **NFR-004**: No unsafe casts, swallowed failures, private vendor SDK imports,
  or read/replace emulation may be introduced.
- **NFR-005**: Spanner data-path code and fixtures MUST remain unchanged; only its explicit unsupported capability declaration and aligned changelog may change.

## Success criteria

- **SC-001**: Focused API tests pass for validation order, capability gating,
  TTL rejection, and the exact common-size boundary.
- **SC-002**: Focused Cosmos tests prove one direct patch, the 10-field limit, RFC 6901
  escaping, deterministic planning, exact 408/410 mapping,
  update-only 413 result-size normalization, diagnostics, and the updated
  consistency test.
- **SC-003**: Focused Dynamo tests prove one aliased conditional `UpdateItem`,
  structured values, exact expression measurement, `NOT_FOUND`, zero-I/O
  expression rejection, narrow result-item-size error normalization, cause
  preservation, and unchanged state after the failed native update.
- **SC-004**: Shared conformance passes supported behavior on Cosmos DB and
  DynamoDB, and verifies Spanner's shared-validation ordering plus core
  capability rejection without provider I/O.
- **SC-005**: `git diff --check` passes; the Spanner data path remains unchanged,
  and `multiclouddb-perf/` is untouched.

## Out of scope

- any Spanner data-path, schema, or fixture change beyond the explicit unsupported capability declaration and changelog alignment
- remove/increment/nested-path patch operations
- a new `replace()` API or compatibility mode
- native-client escape hatch, cancellation, or retry-policy work tracked by
  issues #102, #103, and #104
- changes under `multiclouddb-perf/`

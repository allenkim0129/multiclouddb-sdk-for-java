# Feature Specification: Portable Partial Update

**Branch**: `002-partial-update`
**Status**: Implementation complete; T061 remains pending because the Cosmos
emulator is unavailable in the current validation environment. DynamoDB Local
and Spanner emulator validation ran; portable Spanner update remains
deliberately unsupported, and no live production Spanner validation is claimed.

## Scope decision

`MulticloudDbClient.update()` becomes a capability-gated shallow set/replace
operation. Cosmos DB and DynamoDB move from full replacement to native partial
update and advertise `PARTIAL_UPDATE`.
The core capability guarantees results whose serialized JSON and portable structural footprint are each within 390 KiB.
`PARTIAL_UPDATE_EXTENDED_RESULT_SIZE` declares provider support above that
portable envelope without requiring a read-before-write size preflight.
`PARTIAL_UPDATE_PRESERVES_TTL_EXPIRY` separately declares whether updating an
existing TTL-bearing item leaves its absolute expiry unchanged. DynamoDB
advertises that capability; Cosmos DB does not because `patchItem` advances
`_ts` and restarts the TTL countdown. The base partial-update capability alone
does not guarantee TTL-expiry preservation.

Spanner partial update is deliberately outside this feature's supported-provider
set. Production source under `multiclouddb-provider-spanner/src/main` remains
identical to `upstream/main`; the Spanner emulator run covers shared-validation
ordering, the portable capability rejection, and the provider-direct legacy
regression. Because the provider omits all three Feature 002 capabilities,
`CapabilitySet` supplies their unsupported defaults. After shared validation, a valid Spanner
`update()` call fails at the default client capability gate with non-retryable
`UNSUPPORTED_CAPABILITY`, `capability=partial_update`, and zero Spanner I/O.
No live production Spanner run was performed or is implied; adding portable
Spanner update support requires a separate provider release and validation
decision.

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

Before delegation, shared preflight round-trips caller values through Jackson.
Jackson `JsonNode`, serializable POJO, and Java array values therefore reach
every participating provider as the same provider-neutral map/list/scalar
representation.

Shared conformance runs partial-update behavior on providers advertising the
core capability. Provider-neutral validation still runs before the gate for all
providers.

### US3 — Discover and handle native-envelope differences

The base `PARTIAL_UPDATE` capability guarantees the normalized operation when both the
resulting document's serialized JSON and portable structural footprint stay within 390 KiB. Cosmos DB advertises
`PARTIAL_UPDATE_EXTENDED_RESULT_SIZE` for larger results up to its 2,097,152-byte
native item limit. DynamoDB reports it unsupported, and API normalization supplies
the same unsupported value when an older provider omits the capability.

Native result-size failures remain non-retryable `UNSUPPORTED_CAPABILITY` errors
with stable reasons and structured limit details after one attempted atomic update.
No read/merge preflight is added because the resulting size depends on stored state.

### US4 — Migrate callers that relied on replacement

Cosmos DB and DynamoDB callers that used `update()` as complete replacement
move to `upsert(address, key, completeDocument)`. Documentation must warn that
`upsert()` creates a missing document and is not an atomic guarded replacement.
This release has no exact portable atomic full-document replace-if-present
equivalent.

### US5 — Respect the provider release boundary

Cosmos DB and DynamoDB preserve distinct `title` and `TITLE` fields across an
update, including when both names occur in one atomic request. Spanner omits
the capability, so the API supplies the unsupported default and
a valid update is rejected before provider delegation.

### US6 — Discover TTL-expiry preservation separately

Callers that require a fixed absolute expiry inspect
`PARTIAL_UPDATE_PRESERVES_TTL_EXPIRY` in addition to `PARTIAL_UPDATE`.
DynamoDB supports that guarantee because `UpdateItem` leaves `ttlExpiry`
unchanged. Cosmos DB does not because a patch advances `_ts` and restarts the
TTL countdown. Spanner and legacy omissions receive the API-default unsupported
value without a Spanner production change.

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
  the same absolute assignments MUST be idempotent for logical document fields.
  Provider-maintained metadata and TTL timing are outside this guarantee.
- **FR-007**: Shared preflight MUST reject a null/empty map, a map above 10 fields, and null, empty, or
  blank field names, or field names above 50,000 UTF-8 bytes as non-retryable
  `INVALID_REQUEST`.
- **FR-008**: Shared preflight MUST reject, case-insensitively, `id`,
  `partitionKey`, `sortKey`, `ttl`, `ttlExpiry`, and `data`; names beginning
  with `_`. Case-distinct non-reserved names MUST remain separate valid literal
  fields, including when supplied in the same request.
- **FR-009**: Accepted field names MUST NOT be trimmed or rewritten.
- **FR-010**: A non-null `OperationOptions.ttlSeconds()` on `update()` MUST be
  rejected before provider I/O. TTL remains create/upsert-only.
- **FR-011**: The serialized field map limit MUST be the portable 390 KiB boundary.
  A payload at the boundary passes shared preflight and a larger payload fails.
  Inputs that cannot be serialized MUST fail with non-retryable `INVALID_REQUEST`,
  preserve the serialization cause, and perform zero provider I/O.
- **FR-012**: After validation and before delegation, the default client MUST
  gate `Capability.PARTIAL_UPDATE`; an unsupported provider receives
  non-retryable `UNSUPPORTED_CAPABILITY` with
  `providerDetails.capability=partial_update`.
- **FR-013**: `PARTIAL_UPDATE` MUST guarantee the normalized operation when the
  resulting logical document's serialized JSON and portable structural footprint
  are each at most 390 KiB. Results above either portable bound MUST be represented
  by `PARTIAL_UPDATE_EXTENDED_RESULT_SIZE`. Cosmos DB
  MUST advertise the extended capability; DynamoDB MUST advertise it unsupported.
  Omitted declarations MUST default to unsupported. Native result-size failures MUST
  remain non-retryable `UNSUPPORTED_CAPABILITY` errors with stable reasons and limit
  values, and MUST NOT require a read/merge preflight.
- **FR-014**: Every built-in provider's effective capability set MUST expose 20
  rows. A provider that omits `PARTIAL_UPDATE`,
  `PARTIAL_UPDATE_EXTENDED_RESULT_SIZE`, or
  `PARTIAL_UPDATE_PRESERVES_TTL_EXPIRY` MUST receive the corresponding API
  unsupported default. Feature 002 MUST NOT require `CapabilitySet` to
  synthesize unrelated well-known capabilities omitted by a legacy or
  third-party provider.
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
- **FR-022**: Supported partial-update providers MUST normalize native timeout
 equivalents as retryable `TRANSIENT_FAILURE`: Cosmos update HTTP 408/410 and
 DynamoDB update `RequestTimeout`/`RequestTimeoutException` or SDK API-call/
 API-call-attempt timeout. Cosmos 410 substatus MUST be preserved. Retryability
 MUST NOT be documented as preserving provider metadata or TTL timing.
- **FR-023**: Cosmos write response bodies MAY be disabled only while status,
  activity ID, request charge, duration, and diagnostics used by existing
  write paths remain available.
- **FR-024**: DynamoDB MUST issue one conditional `UpdateItem` with stable name
  and value aliases, one `SET` assignment per field, and an aliased
  `attribute_exists(partitionKey)` guard.
- **FR-025**: DynamoDB values MUST preserve portable JSON null, scalar, map,
  and list shapes.
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
  non-reserved field identities. Cosmos DB and DynamoDB MUST accept and preserve
  names such as `foo` and `Foo` as separate literal fields, including in the
  same atomic request.
- **FR-034**: Shared preflight MUST limit complete create/upsert documents and each
  incoming replacement value to 31 nested map/list containers below the document
  root, counting a top-level value container as level 1. Complete documents and
  incoming update field maps MUST each have a structural footprint at most 390 KiB.
  The footprint MUST include UTF-8 attribute names, three bytes per map/list
  container, and one byte per nested element.
- **FR-035**: Structural-limit failures MUST be non-retryable `INVALID_REQUEST`
  with `provider=null`, zero provider I/O, stable depth/footprint reasons, and
  actual/maximum limit details. Structural preflight MUST NOT read or merge the
  existing document; state-dependent resulting-item overflow remains a native
  reason-coded `UNSUPPORTED_CAPABILITY` path.
- **FR-036**: Every field name in a complete document or replacement value MUST be
  at most 50,000 UTF-8 bytes. Shared preflight MUST reject larger names with
  non-retryable `INVALID_REQUEST` and stable actual/maximum details before I/O.
- **FR-037**: Binary values (`byte[]`, `ByteBuffer`, or Jackson binary
  nodes) are outside the portable JSON value model and MUST fail all write
  operations through shared non-retryable `INVALID_REQUEST` before provider I/O.
  This includes values exposed while serializing a POJO.
- **FR-038**: Shared preflight for `create()`, `upsert()`, and `update()`
  MUST snapshot the caller's top-level `Map` entries before validation so a
  class-level map serializer cannot rewrite validated fields. Values MUST be
  serializable with the SDK-owned Jackson configuration. Shared preflight MUST
  reject cyclic graphs and non-collection `Iterable` values through bounded,
  non-recursive inspection; `char[]` follows Jackson string semantics.
  Serialized JSON output MUST be capped at 390 KiB while it is produced,
  including output generated from POJOs and custom serializers.
- **FR-039**: A null complete document for `create()` or `upsert()` MUST fail
  before provider I/O with non-retryable `INVALID_REQUEST`. Complete-document
  top-level names matching `id`, `partitionKey`, `sortKey`, `ttl`, `ttlExpiry`,
  or `data` case-insensitively, and names beginning with `_`, MUST fail through
  the same zero-I/O category before any provider I/O. Case-distinct
  non-reserved top-level names MUST remain valid.
- **FR-040**: Public `com.multiclouddb.api.PortableWriteLimits` constants MUST
  expose exactly five input/structure limits: both 399,360-byte limits, the
  50,000-byte field-name limit, the 31-container depth limit, and the 10-field
  partial-update limit.
- **FR-041**: `PARTIAL_UPDATE_PRESERVES_TTL_EXPIRY` MUST remain separate from
  both `PARTIAL_UPDATE` and `PARTIAL_UPDATE_EXTENDED_RESULT_SIZE`. DynamoDB MUST
  advertise it supported because `UpdateItem` leaves the absolute `ttlExpiry`
  unchanged. Cosmos DB MUST advertise it unsupported because `patchItem`
  advances `_ts` and restarts the TTL countdown. Spanner and legacy omissions
  MUST receive the API-default unsupported value. Callers requiring fixed
  absolute expiry MUST inspect this capability; base partial update alone MUST
  NOT imply the guarantee.
- **FR-042**: `read()` and `query()` results MUST remove adapter-injected
  identity, TTL, and system-metadata fields before returning documents. Cosmos
  MUST remove `id`, `partitionKey`, `ttl`, and its underscore-prefixed system
  metadata; DynamoDB MUST remove `partitionKey`, `sortKey`, and `ttlExpiry`;
  Spanner MUST remove `partitionKey`, `sortKey`, and its internal `data` metadata
  column. Metadata requested through `OperationOptions` remains in `DocumentMetadata`.
  A result within the portable write envelope MUST NOT fail replacement
  `upsert()` solely because an adapter-owned field leaked into the payload.

## Provider behavior matrix

| Concern | Cosmos DB | DynamoDB | Spanner |
|---|---|---|---|
| Core partial update | Native patch | Native `UpdateItem` | Unsupported by API default; shared gate rejects |
| Missing item | 404 → `NOT_FOUND` | failed existence condition → `NOT_FOUND` | Not reached |
| More than 10 fields | shared `INVALID_REQUEST`; no provider call | shared `INVALID_REQUEST`; no provider call | shared `INVALID_REQUEST`; no provider call |
| Replacement/document depth > 31 | shared `INVALID_REQUEST`; no provider call | shared `INVALID_REQUEST`; no provider call | shared `INVALID_REQUEST`; no provider call |
| Complete-document or incoming-update structural footprint > 390 KiB | shared `INVALID_REQUEST`; no provider call | shared `INVALID_REQUEST`; no provider call | shared `INVALID_REQUEST`; no provider call |
| Canonical provider input | shared Jackson map/list/scalar form | shared Jackson map/list/scalar form | same form for supported base writes; update stops at gate |
| Null complete document or provider-owned/underscore-prefixed top-level name | shared `INVALID_REQUEST`; no provider call | shared `INVALID_REQUEST`; no provider call | shared `INVALID_REQUEST`; no provider call |
| Portable result envelope | serialized and structural <= 390 KiB | serialized and structural <= 390 KiB | Not reached |
| Lower native envelope | provider-native resulting-item limit | provider-native resulting-item limit | Not reached |
| Extended result size | supported up to 2 MiB | unsupported | unsupported by API default |
| TTL-expiry preservation | unsupported: patch advances `_ts` and restarts countdown | supported: `ttlExpiry` remains unchanged | unsupported by API default |
| Case-distinct non-reserved names | preserved, including in one request | preserved, including in one request | Not part of this release |
| Adapter read/retry | no read/retry; result-size rejection follows one attempted patch | no read/retry; result-size rejection follows one attempted `UpdateItem` | zero provider I/O |

## Edge cases

- Empty maps, blank names, reserved names, underscore-prefixed names,
  update TTL, over-limit maps, replacement values
  deeper than 31 map/list containers, names above 50,000 UTF-8 bytes, binary
  values, and complete-document or incoming-update structural footprints above
  390 KiB fail
  before provider delegation.
- Null complete create/upsert documents, all case-insensitive provider-owned
  top-level names, and underscore-prefixed top-level names fail before provider
  I/O. Binary values hidden in serializable POJOs are rejected during bounded
  SDK-owned Jackson serialization.
- A top-level field may hold a nested map/list; shallow update semantics do not
  exempt that replacement value from the structural limits.
- Structural checks inspect incoming replacements only. Omitted stored fields
  remain state-dependent and do not trigger a read/merge preflight.
- Names containing `.`, `/`, `~`, or surrounding spaces remain literal. Cosmos
  escapes them and Dynamo aliases them.
- Cosmos and Dynamo preserve case-distinct non-reserved names across calls and
  when both variants occur in one atomic update.
- Existing TTL-bearing items have a fixed absolute expiry only when
  `PARTIAL_UPDATE_PRESERVES_TTL_EXPIRY` is supported. DynamoDB supports that
  guarantee; Cosmos DB does not.
- A resulting logical document remains inside the base portable contract only when
  both serialized JSON and portable structural footprint are at or below 390 KiB.
  Above either boundary, callers inspect the extended-result capability.
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
  the existing item plus assignments would exceed the provider-native
  resulting-item ceiling. Only the
  item-size `ValidationException` variant becomes a reason-coded native-limit
  error; the failed native update leaves the item unchanged.

## Non-functional requirements

- **NFR-001**: Keep planners package-private, deterministic, and small.
- **NFR-002**: Use one adapter SDK invocation for each accepted Cosmos or
  Dynamo update. Vendor-managed retries are outside this count.
- **NFR-003**: Local validation failures perform zero provider I/O.
- **NFR-004**: No unsafe casts, swallowed failures, private vendor SDK imports,
  or read/replace emulation may be introduced.
- **NFR-005**: Spanner production source under `multiclouddb-provider-spanner/src/main` MUST remain identical to `upstream/main`; unsupported behavior is supplied by the API capability default. Changelog and test-only alignment MAY describe and validate this boundary.

## Success criteria

- **SC-001**: Focused API tests pass for validation order, all three Feature 002
  capability defaults, TTL rejection, complete-write reserved-name rejection,
  case-distinct same-request acceptance, and the exact common-size boundary.
- **SC-002**: Focused Cosmos tests prove one direct patch, the 10-field limit, RFC 6901
  escaping, deterministic planning, exact 408/410 mapping,
  update-only 413 result-size normalization, diagnostics, and the updated
  consistency test.
- **SC-003**: Focused Dynamo tests prove one aliased conditional `UpdateItem`,
  structured values, exact expression measurement, `NOT_FOUND`, zero-I/O
  expression rejection, narrow result-item-size error normalization, cause
  preservation, and unchanged state after the failed native update.
- **SC-004**: Shared conformance passes supported behavior on Cosmos DB and
  DynamoDB, verifies their TTL-expiry-preservation capability values and all 20
  effective capability rows, and verifies Spanner's shared-validation ordering
  plus core capability rejection without provider I/O.
- **SC-005**: `git diff --check` passes; Spanner production source remains identical to `upstream/main`, the provider-direct legacy regression remains runnable,
  and `multiclouddb-perf/` is untouched.

## Current validation evidence

- DynamoDB Local validation ran for the supported partial-update path.
- The Spanner emulator ran shared preflight/capability-gate coverage and the
  provider-direct legacy regression. This does not advertise portable Spanner
  update support and is not live production Spanner validation.
- The Cosmos emulator is unavailable in the current environment, so the final
  Cosmos post-remediation rerun and T061 remain pending.

## Out of scope

- any Spanner production behavior change under `multiclouddb-provider-spanner/src/main`
- remove/increment/nested-path patch operations
- a new `replace()` API or compatibility mode
- native-client escape hatch, cancellation, or retry-policy work tracked by
  issues #102, #103, and #104
- changes under `multiclouddb-perf/`

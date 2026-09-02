# Specification Quality Checklist: Portable Partial Update

**Reviewed**: 2026-09-02
**Scope**: Cosmos DB and DynamoDB implementation; unchanged Spanner baseline

## Scope and consistency

- [x] The artifacts state that `SpannerProviderClient` is unchanged.
- [x] No artifact requires schema-aware typed nulls, requested-column reads, DDL,
  automatic column creation, or schema/row precedence changes.
- [x] No artifact requires a new Spanner provider test or E2E schema helper.
- [x] Spanner is described as fixed-schema with existing STRING-null and
  encoded STRING map/list mappings.
- [x] Shared conformance is limited to fields/shapes in the existing
  three-provider fixtures.
- [x] Cosmos/Dynamo replacement-to-partial-update migration remains explicit.

## Shared API

- [x] Both existing `update()` overloads and `Map<String,Object>` are retained.
- [x] Shallow set/replace, omitted-field preservation, atomicity, idempotency,
  and missing-item `NOT_FOUND` are unambiguous.
- [x] Null/empty maps and invalid names are specified as zero-I/O
  `INVALID_REQUEST`.
- [x] Reserved names, underscore prefixes, case-insensitive collisions, and
  no-trimming behavior are explicit.
- [x] Update TTL rejection is explicit and create/upsert migration is clear.
- [x] The common limit is exactly 408,576 bytes with pass/fail boundaries.
- [x] The core `partial_update` gate and future unsupported-provider error are
  explicit.
- [x] `partial_update_extended_payload` covers native request and
  resulting-item envelopes for supported provider mappings.
- [x] All providers must declare all 19 known capabilities.

## Cosmos DB

- [x] Literal RFC 6901 `set` paths are specified.
- [x] Direct patch through 10 fields is specified.
- [x] One same-item transactional batch for wider requests is specified.
- [x] No read, replace, independent patch loop, or adapter retry is allowed.
- [x] The 100-operation and 2,097,152-byte local limits and details are complete.
- [x] Update HTTP 413 is a state-dependent 2,097,152-byte result-item
  capability error after one attempted patch/batch; non-update 413 behavior is
  unchanged.
- [x] Batch root selection skips 424 and has aggregate/no-root fallbacks.
- [x] Exact 408/410 transient mapping is specified.
- [x] Diagnostics exclude payloads and secrets.
- [x] Disabling write response bodies is conditioned on preserving metadata used
  by existing write paths.

## DynamoDB

- [x] One conditional aliased `UpdateItem` is specified.
- [x] Structured null/map/list values are required.
- [x] The complete update expression is measured in UTF-8.
- [x] The 4,096-byte boundary and structured error details are complete.
- [x] The state-dependent 409,600-byte result-item rejection is normalized only
  for the matching update `ValidationException`, preserves the cause/native
  metadata, and does not add a read preflight.
- [x] Conditional failure maps to `NOT_FOUND`.
- [x] No read, `PutItem`, TTL assignment, or adapter retry is allowed.

## Testing and delivery

- [x] T001–T015 remain retained and completed.
- [x] Focused API, Cosmos, and Dynamo unit tests are named.
- [x] Existing replace-to-patch consistency coverage is updated.
- [x] Runnable shared `CrudConformanceTests` cover update TTL,
  invalid/reserved-field atomic failure, 408,577-byte atomic failure, and a
  wide missing-item update without provider branches.
- [x] Concrete Cosmos and Dynamo emulator regressions cover result-item
  overflow and unchanged stored state.
- [x] No Spanner test is added or run in the focused unit phase.
- [x] Remaining Cosmos/Spanner runtime and all-provider E2E work is explicitly
  left unchecked in `tasks.md`.
- [x] `multiclouddb-perf/` is excluded.
- [x] Issues #102–#104 remain out of scope.

## Notes

The feature artifacts describe both completed focused work and remaining
delivery phases. A checked specification-quality item does not imply that its
corresponding implementation task is complete; `tasks.md` is authoritative for
execution status.

# Specification Quality Checklist: Portable Partial Update

**Reviewed**: 2026-09-15
**Scope**: Cosmos DB and DynamoDB implementation; Spanner unsupported through the API capability default

## Scope and consistency

- [x] Spanner production source under `multiclouddb-provider-spanner/src/main` matches `upstream/main`; changelog and provider-direct emulator-test alignment are allowed.
- [x] No artifact requires Spanner production capability, data-path, schema,
  or E2E helper changes; changelog and test coverage describe the boundary.
- [x] Spanner partial update is described as deliberately unsupported in this
  feature release and rejected by the shared `partial_update` capability gate;
  emulator evidence is not described as live production validation.
- [x] Shared conformance gates supported behavior by capability while retaining
  provider-neutral preflight and unsupported-gate coverage.
- [x] Cosmos/Dynamo replacement-to-partial-update migration remains explicit.

## Shared API

- [x] Both existing `update()` overloads and `Map<String,Object>` are retained.
- [x] Shallow set/replace, omitted-field preservation, atomicity, idempotency,
  and missing-item `NOT_FOUND` are unambiguous.
- [x] Null/empty maps, binary values, unsafe graphs, and invalid or overlong names are specified
  as zero-I/O `INVALID_REQUEST`.
- [x] Create/upsert/update top-level maps are snapshotted and nested values use
  bounded SDK-owned Jackson serialization, including binary detection in a POJO.
- [x] Null create/upsert documents, all case-insensitive provider-owned
  top-level names (`id`, `partitionKey`, `sortKey`, `ttl`, `ttlExpiry`, `data`),
  and underscore-prefixed top-level names are specified as zero-I/O
  `INVALID_REQUEST`; case-distinct non-reserved top-level names remain valid.
- [x] Reserved-name matching, underscore prefixes, no-trimming behavior, and
  same-request acceptance of case-distinct non-reserved names are explicit.
- [x] Update TTL rejection is explicit and create/upsert migration is clear.
- [x] The common limits are 10 update fields plus 390 KiB serialized and structural
  write-input bounds, with pass/fail boundaries.
- [x] Replacement values are limited to 31 nested map/list containers, with the top-level replacement container counted as level 1.
- [x] Complete documents and incoming update fields have a 390 KiB structural-footprint
  limit covering UTF-8 names and native map/list overhead, including compact-JSON
  boundary coverage and the 50,000-byte per-name bound.
- [x] Structural failures are zero-I/O, reason-coded `INVALID_REQUEST` with actual/maximum details; existing-state result overflow remains native without a read/merge preflight.
- [x] The core `partial_update` gate and future unsupported-provider error are
  explicit.
- [x] The base result envelope requires both serialized JSON and structural footprint
  at or below 390 KiB, and `PARTIAL_UPDATE_EXTENDED_RESULT_SIZE`
  explicitly distinguishes Cosmos extended support from DynamoDB and
  legacy-provider unsupported behavior.
- [x] `PARTIAL_UPDATE_PRESERVES_TTL_EXPIRY` is distinct and does not change
  `PARTIAL_UPDATE_EXTENDED_RESULT_SIZE`: DynamoDB supports fixed absolute expiry,
  Cosmos does not because patch advances `_ts`, and Spanner/legacy omissions
  default to unsupported. Callers requiring fixed expiry must inspect it.
- [x] Every built-in provider exposes 20 effective capability rows; omitted
  `PARTIAL_UPDATE`, `PARTIAL_UPDATE_EXTENDED_RESULT_SIZE`, and
  `PARTIAL_UPDATE_PRESERVES_TTL_EXPIRY` declarations default to unsupported,
  while unrelated omissions in arbitrary partial sets remain absent.
- [x] `PortableWriteLimits` exposes exactly five input/structure constants:
  both 399,360-byte limits, 50,000-byte names, 31 nested containers, and 10
  update fields.

## Cosmos DB

- [x] Literal RFC 6901 `set` paths are specified.
- [x] Every accepted update uses one direct patch.
- [x] Maps above 10 fields fail shared validation before Cosmos I/O.
- [x] No read, replace, independent patch loop, or adapter retry is allowed.
- [x] The planner defensively enforces the 10-field limit for direct SPI calls.
- [x] Update HTTP 413 is a state-dependent 2,097,152-byte result-item
  capability error after one attempted patch; non-update 413 behavior is
  unchanged.
- [x] Field names are sorted so map iteration order cannot change the native plan.
- [x] Exact 408/410 transient mapping is specified.
- [x] Diagnostics exclude payloads and secrets.
- [x] Disabling write response bodies is conditioned on preserving metadata used
  by existing write paths.
- [x] Cosmos explicitly reports TTL-expiry preservation unsupported because
  `patchItem` advances `_ts` and restarts the TTL countdown.

## DynamoDB

- [x] One conditional aliased `UpdateItem` is specified.
- [x] Structured null/map/list values are required.
- [x] The shared 31-level and structural-footprint limits prevent compact replacement values from exceeding DynamoDB's native structure envelope before I/O.
- [x] The shared 10-field limit keeps generated expressions safely below the
  native ceiling; the defensive planner guard is not a public envelope.
- [x] The state-dependent native result-item rejection is normalized only
  for the matching update `ValidationException`, preserves the cause/native
  metadata, and does not add a read preflight.
- [x] Conditional failure maps to `NOT_FOUND`.
- [x] No read, `PutItem`, TTL assignment, or adapter retry is allowed.
- [x] DynamoDB reports TTL-expiry preservation supported because `UpdateItem`
  leaves the absolute `ttlExpiry` attribute unchanged.

## Testing and delivery

- [x] T001–T015 remain retained and completed.
- [x] Focused API, Cosmos, and Dynamo unit tests are named.
- [x] Existing replace-to-patch consistency coverage is updated.
- [x] Runnable shared `CrudConformanceTests` cover exact serialized/structural create/upsert boundaries and
  supported update read-back, update TTL, invalid/reserved-field atomic failure,
  over-limit atomic failure, and a wide missing-item update without provider branches.
- [x] Concrete Cosmos and Dynamo emulator regressions cover result-item
  overflow and unchanged stored state.
- [x] Shared conformance verifies Spanner core capability rejection without
  entering provider code.
- [x] Shared conformance covers Cosmos/Dynamo case identity and keeps the exact
  limit assertion capability-gated, including `foo` and `Foo` in one request.
- [x] API/shared coverage rejects every provider-owned and underscore-prefixed
  complete-write top-level name.
- [x] Provider and shared conformance coverage strips adapter-owned fields from
  read/query results and proves an in-envelope read result can be reused by `upsert()`.
- [x] Capability conformance covers all 20 effective rows and both independent
  Feature 002 optional-capability matrices.
- [x] Current DynamoDB Local and Spanner emulator validation ran; the Spanner
  evidence covers the deliberate unsupported boundary and provider-direct
  legacy regression, not live production validation.
- [ ] T061 final post-remediation unit/emulator/E2E rerun and scope audit remains
  pending because the Cosmos emulator is unavailable.
- [x] `multiclouddb-perf/` is excluded.
- [x] Issues #102–#104 remain out of scope.

## Notes

The feature artifacts describe the completed Cosmos/Dynamo implementation and
the intended zero-Spanner-production-diff release boundary. Changelog and
provider-direct test alignment are allowed; `tasks.md` keeps T061 pending until
the Cosmos emulator can run the post-remediation profile.

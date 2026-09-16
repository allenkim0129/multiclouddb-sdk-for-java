# Implementation Plan: Portable Partial Update

**Branch**: `002-partial-update`
**Binding design**: [design.md](design.md)

## Summary

Change Cosmos DB and DynamoDB `update()` from full replacement to native shallow
partial update. Deliberately capability-gate portable Spanner update in this
release; the API defaults all three omitted Feature 002 capabilities to
unsupported. The shared default client owns portable read/query identity cleanup,
the Spanner write path remains unchanged, and the only Spanner production adjustment
preserves caller field spelling across physical-column casing differences.
The base result envelope requires serialized JSON and portable structural footprint
to each remain within 390 KiB. A second optional capability declares support above
either boundary: Cosmos supports it, Dynamo and API-normalized
older providers do not.
A third, independent capability declares whether partial update preserves an
existing absolute TTL expiry: DynamoDB supports it, Cosmos DB does not because
patch advances `_ts` and restarts the countdown, and omitted declarations
default to unsupported.

The work is intentionally split:

1. shared API contract and preflight;
2. focused Cosmos/Dynamo production code and unit tests;
3. shared baseline conformance;
4. migration/docs/E2E; and
5. final validation.

## Current status

| Area | Status |
|---|---|
| Shared API/preflight | Complete; capability gate rejects non-participating providers |
| Cosmos production/unit work | Complete; current Cosmos emulator rerun unavailable |
| Dynamo production/unit work | Complete; DynamoDB Local validation ran |
| Spanner provider implementation | Partial update remains deliberately unsupported; the write path matches `upstream/main`, and a focused read mapper casing fix is covered alongside shared result cleanup, capability gating, and the provider-direct legacy path |
| Feature artifacts/docs/contracts | Reconciled to the implemented Cosmos/Dynamo contract and three-capability matrix |
| Shared conformance | Supported behavior on Cosmos/Dynamo; preflight and unsupported gate on Spanner |
| Provider-native result-size regressions | DynamoDB Local ran; current Cosmos emulator rerun unavailable |
| Final validation | T061 pending because Cosmos emulator validation is unavailable; no live production Spanner validation is claimed |

## Technical context

- Java 17 modular Maven build
- Azure Cosmos DB Java SDK from existing dependency management
- AWS SDK v2 DynamoDB client from existing dependency management
- JUnit 5 and Mockito already present
- no dependency version change

The Dynamo module descriptor must read the AWS `utils` and `identity-spi`
automatic modules required by the pinned SDK so clean compilation does not emit
unresolved-error bytecode.

## Project paths

### Shared API

- `multiclouddb-api/src/main/java/com/multiclouddb/api/Capability.java`
- `multiclouddb-api/src/main/java/com/multiclouddb/api/MulticloudDbClient.java`
- `multiclouddb-api/src/main/java/com/multiclouddb/api/OperationOptions.java`
- `multiclouddb-api/src/main/java/com/multiclouddb/api/PortableWriteLimits.java`
- `multiclouddb-api/src/main/java/com/multiclouddb/api/internal/DefaultMulticloudDbClient.java`
- `multiclouddb-api/src/main/java/com/multiclouddb/api/internal/DocumentSizeValidator.java`
- `multiclouddb-api/src/main/java/com/multiclouddb/api/internal/PartialUpdateValidator.java`
- `multiclouddb-api/src/main/java/com/multiclouddb/api/internal/PartialUpdateStructureValidator.java`
- `multiclouddb-api/src/main/java/com/multiclouddb/spi/MulticloudDbProviderClient.java`

### Cosmos DB

- `multiclouddb-provider-cosmos/src/main/java/com/multiclouddb/provider/cosmos/CosmosPartialUpdatePlanner.java`
- `multiclouddb-provider-cosmos/src/main/java/com/multiclouddb/provider/cosmos/CosmosProviderClient.java`
- `multiclouddb-provider-cosmos/src/main/java/com/multiclouddb/provider/cosmos/CosmosErrorMapper.java`
- `multiclouddb-provider-cosmos/src/main/java/com/multiclouddb/provider/cosmos/CosmosDiagnosticsLogger.java`
- focused tests under the corresponding `src/test/java` package

### DynamoDB

- `multiclouddb-provider-dynamo/src/main/java/com/multiclouddb/provider/dynamo/DynamoPartialUpdatePlanner.java`
- `multiclouddb-provider-dynamo/src/main/java/com/multiclouddb/provider/dynamo/DynamoProviderClient.java`
- `multiclouddb-provider-dynamo/src/main/java/com/multiclouddb/provider/dynamo/DynamoItemMapper.java`
- `multiclouddb-provider-dynamo/src/main/java/module-info.java`
- focused tests under the corresponding `src/test/java` package

### Spanner

Spanner continues to omit all three Feature 002 capabilities, so `CapabilitySet`
adds their unsupported defaults and the shared client rejects valid updates before
provider delegation. The Spanner write path matches `upstream/main`; portable
identity cleanup occurs in `DefaultMulticloudDbClient` after provider mapping. The
only Spanner production adjustment restores caller field spelling when `FIELD_DATA`
metadata and physical columns differ only by case. It does not advertise or enable
the provider-direct legacy update method through `MulticloudDbClient.update()`.
## Implementation stages

### Stage 1 — Shared contract and validation

1. Keep both existing `update()` overloads and `Map<String,Object>`.
2. Validate field map/names, reject binary values and unsafe graphs, and reject
   update TTL.
3. Snapshot top-level maps, serialize once through bounded SDK-owned Jackson,
   and delegate the resulting detached normalized representation.
4. Reject null complete documents, every case-insensitive top-level
   provider-owned name (`id`, `partitionKey`, `sortKey`, `ttl`, `ttlExpiry`,
   `data`), underscore-prefixed names, case-insensitive top-level collisions,
   and top-level names above 128 Unicode characters before provider I/O.
5. Enforce the portable 390 KiB serialized-input limit while producing output.
6. Enforce 31-level complete-document/replacement nesting, 50,000-byte nested
   and partial-update field names, and a separate 390 KiB structural footprint.
7. Expose exactly six public `PortableWriteLimits` input/structure constants:
   serialized bytes, structural footprint, nested/partial-update field-name bytes,
   complete-write top-level characters, nested-container depth, and partial-update
   field count.
8. Gate `Capability.PARTIAL_UPDATE` before delegation.
9. Define serialized and structural 390 KiB bounds as the base result envelope and
   expose `PARTIAL_UPDATE_EXTENDED_RESULT_SIZE` for provider support above either.
10. Preserve stable native result-size reasons and limit details without adding a
   read/merge preflight.
11. Require case-distinct non-reserved field identity as part of
   `PARTIAL_UPDATE`, including both variants in one atomic request.
12. Add `PARTIAL_UPDATE_PRESERVES_TTL_EXPIRY` independently of the unchanged
   extended-result capability; default all three Feature 002 omissions to
   unsupported and document the 20-row effective built-in capability sets.

### Stage 2 — Cosmos DB

1. Build literal RFC 6901 `set` operations.
2. Use one direct patch for every accepted update.
3. Reject maps above 10 fields in shared preflight before provider delegation.
4. Sort literal field names before constructing deterministic patch operations.
5. Normalize direct patch failures through the portable error mapper.
6. Normalize Cosmos update 408/410 as retryable transient failures; align DynamoDB update service/SDK timeout equivalents while leaving Spanner partial update capability-gated.
7. Normalize update HTTP 413 as the state-dependent 2-MiB result-item
   capability limit without adding a read.
8. Keep diagnostics metadata-only and verify write response bodies can be
   disabled without affecting existing paths.
9. Advertise TTL-expiry preservation unsupported because each patch advances
   `_ts` and restarts the TTL countdown.

### Stage 3 — DynamoDB

1. Build one aliased `SET` expression.
2. Preserve null/map/list values with `AttributeValue`.
3. Rely on shared depth and native-style structural-footprint preflight for incoming replacements.
4. Guard with aliased `attribute_exists(partitionKey)`.
5. Preflight exact UTF-8 update-expression length.
6. Map condition failure to `NOT_FOUND`.
7. Map only the result-item-size `ValidationException` from `update()` to
   `UNSUPPORTED_CAPABILITY`; keep other validation failures
   `INVALID_REQUEST`.
8. Issue one `UpdateItem`, never read/`PutItem`/retry. The state-dependent
   result-size rejection follows that one attempted update.
9. Advertise TTL-expiry preservation because the update expression leaves the
   absolute `ttlExpiry` attribute unchanged.

### Stage 4 — Shared conformance

Keep provider-neutral invalid-map/name, update-TTL, and oversize preflight tests
on all providers because validation runs before the core gate. Gate supported
behavior on `PARTIAL_UPDATE`: Cosmos and Dynamo run preservation, missing-item,
replay, concurrency, and same-request case-identity assertions. All three providers run the
shared field-count, depth, and structural-footprint rejections; Spanner additionally
runs a dedicated `UNSUPPORTED_CAPABILITY` assertion with zero provider mutation.
Supported Cosmos/Dynamo paths also prove that the 31-level depth boundary succeeds.
Shared create/upsert coverage rejects all provider-owned and underscore-prefixed
top-level names and verifies the shared write envelope.

API tests prove exact-limit delegation and one-byte-over zero delegation. Shared
emulator conformance must prove the 390 KiB create/upsert boundary on every
provider and the update boundary on capability-supporting providers. The current
DynamoDB Local and Spanner emulator runs provide their applicable evidence; the
Cosmos rerun remains pending because the emulator is unavailable.
Capability conformance verifies 20 effective rows for every built-in provider,
the extended-result matrix (Cosmos supported, Dynamo and Spanner unsupported),
and the TTL-expiry-preservation matrix (Dynamo supported, Cosmos and Spanner
unsupported). API
unit coverage separately proves that arbitrary partial declarations receive only
the three Feature 002 defaults rather than a general 20-row backfill.

### Stage 5 — Docs and E2E

Document:

- shallow set/replace semantics for Cosmos DB and DynamoDB;
- the API-default unsupported behavior for Spanner;
- the 31-level replacement-depth and 390 KiB complete-document/update structural-footprint limits;
- the dual 390 KiB serialized/structural base result envelope and extended-result
  capability matrix;
- the independent TTL-expiry-preservation capability, its Cosmos/Dynamo
  divergence, and the requirement to inspect it when fixed absolute expiry
  matters;
- Cosmos/Dynamo native request and resulting-item errors;
- replacement migration to `upsert()` and its create-on-missing warning;
- the absence of an exact portable atomic full-document replace-if-present
  equivalent;
- create/upsert-only TTL;
- bounded shared serialization, null-document rejection, and all provider-owned/
  underscore-prefixed top-level complete-write rejections; and
- the exact six public `PortableWriteLimits` constants.

The E2E runner checks `PARTIAL_UPDATE` before executing update scenarios, so the
Spanner run skips them without changing its schema or provider code.
## Test order

### Canonical focused validation

```powershell
mvn -pl multiclouddb-api -am -Punit `
  '-Dtest=PartialUpdateValidatorTest,DefaultMulticloudDbClientPartialUpdateTest,DocumentSizeValidatorTest,MulticloudDbClientPartialUpdateContractTest,CapabilityTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test

mvn -pl multiclouddb-provider-cosmos -am -Punit clean `
  '-Dtest=CosmosPartialUpdatePlannerTest,CosmosPartialUpdateTest,CosmosErrorMappingTest,CosmosDiagnosticsLogTest,CosmosConsistencyTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test

mvn -pl multiclouddb-provider-dynamo -am -Punit clean `
  '-Dtest=DynamoPartialUpdatePlannerTest,DynamoPartialUpdateTest,DynamoItemMapperTest,DynamoErrorMappingTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test

mvn -pl multiclouddb-conformance -am -DskipTests clean test-compile
```

Each canonical command must report positive test discovery with zero failures and
errors. After all remediation edits, rerun the focused commands, the clean unit
reactor, and conformance test compilation. Record exact counts only from the
resulting Surefire reports rather than retaining historical totals in this plan.

### Final scope-correction validation

Run focused API tests plus complete Cosmos, DynamoDB, and Spanner emulator
profiles. Cosmos and Dynamo execute supported partial-update behavior. Spanner
executes shared validation and the core capability rejection, while the
provider-direct legacy regression runs separately. Current local validation ran
DynamoDB Local and the Spanner emulator; the Cosmos emulator was unavailable,
so this final step and T061 remain pending. Confirm Spanner still omits all three
Feature 002 capabilities and performs zero provider I/O for portable update, then
validate Javadocs, changed Markdown links, capability counts, requirement
traceability, and the protected-path audit. Do not describe the emulator run as
live production Spanner validation.
## Parity matrix

| Behavior | Cosmos DB | DynamoDB | Spanner |
|---|---|---|---|
| Core partial update | supported: one direct patch | supported: `UpdateItem SET` | unsupported by API default; shared gate rejects |
| Omitted fields preserved | yes | yes | not reached |
| Missing item | 404 | condition failure | not reached |
| Null/map/list | native JSON | Dynamo native values | not reached |
| More than 10 fields | shared `INVALID_REQUEST` | shared `INVALID_REQUEST` | shared `INVALID_REQUEST` |
| Portable result envelope | serialized + structural <= 390 KiB | serialized + structural <= 390 KiB | not reached |
| Lower native envelope | attempted result-size rejection | local expression or attempted result-size rejection | not reached |
| Extended result size | supported up to 2 MiB | unsupported | unsupported by API default |
| TTL-expiry preservation | unsupported; patch advances `_ts` | supported; `ttlExpiry` unchanged | unsupported by API default |
| Case-distinct non-reserved fields | preserved together or across requests | preserved together or across requests | not part of release |
| Complete-write provider-owned names | shared rejection before I/O | shared rejection before I/O | shared rejection before I/O |
| New partial-update data path | yes | yes | none |

## Cost matrix

| Provider | Cost driver |
|---|---|
| Cosmos DB | one attempted point patch per accepted call |
| DynamoDB | one attempted `UpdateItem`; accepted WCU is based on resulting item size |
| Spanner | zero provider I/O; rejected by the shared capability gate |
No implementation may add an adapter read/replace cycle for Cosmos or Dynamo.

## Scope guard

Spanner write behavior must remain identical to `upstream/main`. Shared read/query
identity cleanup belongs in `DefaultMulticloudDbClient`; the only Spanner production
change may restore caller field spelling while matching `FIELD_DATA` metadata to
physical columns case-insensitively. The provider must continue to omit all three
Feature 002 capabilities, and the shared gate must prevent portable delegation to
its legacy update method.

Do not:

- advertise or implement Spanner partial-update support without a separate
  provider design, implementation, and validation decision;
- add a public patch model or `replace()` method;
- implement issues #102–#104; or
- touch/stage `multiclouddb-perf/`.

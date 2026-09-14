# Implementation Plan: Portable Partial Update

**Branch**: `002-partial-update`
**Binding design**: [design.md](design.md)

## Summary

Change Cosmos DB and DynamoDB `update()` from full replacement to native shallow
partial update. Keep the Spanner data path unchanged, declare the capability
unsupported, and exclude it through the shared `partial_update` capability gate
until release-grade live-account validation is available.

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
| Cosmos production/unit work | Complete and passing |
| Dynamo production/unit work | Complete and passing |
| Spanner provider implementation | Data path unchanged; unsupported capability declaration and changelog aligned pending live-account validation |
| Feature artifacts/docs/contracts | Reconciled to Cosmos/Dynamo release scope |
| Shared conformance | Supported behavior on Cosmos/Dynamo; preflight and unsupported gate on Spanner |
| Provider-native result-size regressions | Cosmos and Dynamo emulator regressions pass |
| Final validation | Complete; clean unit reactor, three-provider emulator conformance, and E2E pass |
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
- `multiclouddb-api/src/main/java/com/multiclouddb/api/internal/DefaultMulticloudDbClient.java`
- `multiclouddb-api/src/main/java/com/multiclouddb/api/internal/DocumentSizeValidator.java`
- `multiclouddb-api/src/main/java/com/multiclouddb/api/internal/PartialUpdateValidator.java`
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

Spanner data paths remain unchanged. Its 18-name
capability set explicitly marks feature 002 unsupported, so the shared client rejects valid updates
before provider delegation.
## Implementation stages

### Stage 1 — Shared contract and validation

1. Keep both existing `update()` overloads and `Map<String,Object>`.
2. Validate field map/names and reject update TTL.
3. Enforce the portable 390 KiB common limit.
4. Gate `Capability.PARTIAL_UPDATE` before delegation.
5. Surface lower native request/result envelopes through stable reason and limit
   details without defining another capability.
6. Require case-distinct field identity as part of `PARTIAL_UPDATE` and document
   Spanner as explicitly unsupported at the core capability gate.

### Stage 2 — Cosmos DB

1. Build literal RFC 6901 `set` operations.
2. Use one direct patch for every accepted update.
3. Reject maps above 10 fields in shared preflight before provider delegation.
4. Sort literal field names before constructing deterministic patch operations.
5. Normalize direct patch failures through the portable error mapper.
6. Normalize 408 and 410 as retryable transient failures.
7. Normalize update HTTP 413 as the state-dependent 2-MiB result-item
   capability limit without adding a read.
8. Keep diagnostics metadata-only and verify write response bodies can be
   disabled without affecting existing paths.

### Stage 3 — DynamoDB

1. Build one aliased `SET` expression.
2. Preserve null/map/list values with `AttributeValue`.
3. Guard with aliased `attribute_exists(partitionKey)`.
4. Preflight exact UTF-8 update-expression length.
5. Map condition failure to `NOT_FOUND`.
6. Map only the result-item-size `ValidationException` from `update()` to
   `UNSUPPORTED_CAPABILITY`; keep other validation failures
   `INVALID_REQUEST`.
7. Issue one `UpdateItem`, never read/`PutItem`/retry. The state-dependent
   result-size rejection follows that one attempted update.

### Stage 4 — Shared conformance

Keep provider-neutral invalid-map/name, update-TTL, and oversize preflight tests
on all providers because validation runs before the core gate. Gate supported
behavior on `PARTIAL_UPDATE`: Cosmos and Dynamo run preservation, missing-item,
replay, concurrency, and case-identity assertions. All three providers run the
shared field-count rejection; Spanner additionally runs a dedicated
`UNSUPPORTED_CAPABILITY` assertion with zero provider mutation.

API tests retain the portable 390 KiB positive boundary. A shared
provider-runtime success assertion is omitted because native envelopes may bind
first. Concrete Cosmos and Dynamo emulator tests retain native result-item
regressions.
### Stage 5 — Docs and E2E

Document:

- shallow set/replace semantics for Cosmos DB and DynamoDB;
- Spanner and its explicit unsupported core capability;
- Cosmos/Dynamo native request and resulting-item envelopes;
- replacement migration to `upsert()` and its create-on-missing warning; and
- create/upsert-only TTL.

The E2E runner checks `PARTIAL_UPDATE` before executing update scenarios, so the
Spanner run skips them without changing its schema or provider code.
## Test order

### Completed in this turn

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

Focused results before final full-suite validation: API 36 tests, Cosmos 95
tests, and Dynamo 46 tests, all with zero failures/errors/skips. The Cosmos 413
mapper additions also pass focused unit tests. Final clean unit totals are API
182, Cosmos 180, Dynamo 115, Spanner 109, and conformance-unit 105, all with
zero failures/errors/skips. The conformance module and its 41 test sources
compile successfully. The earlier provider-neutral E2E completed against all
three emulators.

### Final scope-correction validation

Run focused API tests plus complete Cosmos, DynamoDB, and Spanner emulator
profiles. Cosmos and Dynamo execute supported partial-update behavior. Spanner
executes shared validation and the core capability rejection only. Confirm the
Spanner data path is unchanged apart from its capability declaration and changelog, then validate Javadocs,
changed Markdown links, capability counts, requirement traceability, and the
protected-path audit.
## Parity matrix

| Behavior | Cosmos DB | DynamoDB | Spanner |
|---|---|---|---|
| Core partial update | supported: one direct patch | supported: `UpdateItem SET` | explicitly unsupported; shared gate rejects |
| Omitted fields preserved | yes | yes | not reached |
| Missing item | 404 | condition failure | not reached |
| Null/map/list | native JSON | Dynamo native values | not reached |
| More than 10 fields | shared `INVALID_REQUEST` | shared `INVALID_REQUEST` | shared `INVALID_REQUEST` |
| Lower native envelope | attempted result-size rejection | local expression or attempted result-size rejection | not reached |
| Case-distinct fields | preserved | preserved | not part of release |
| New provider data path | yes | yes | none |

## Cost matrix

| Provider | Cost driver |
|---|---|
| Cosmos DB | one attempted point patch per accepted call |
| DynamoDB | one attempted `UpdateItem`; accepted WCU is based on resulting item size |
| Spanner | zero provider I/O; rejected by the shared capability gate |
No implementation may add an adapter read/replace cycle for Cosmos or Dynamo.

## Scope guard

Do not:

- change any path under `multiclouddb-provider-spanner/`;
- add Spanner capability, data-path, schema, fixture, or changelog work;
- add a public patch model or `replace()` method;
- implement issues #102–#104; or
- touch/stage `multiclouddb-perf/`.

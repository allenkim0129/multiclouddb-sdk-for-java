# Implementation Plan: Portable Partial Update

**Branch**: `002-partial-update`
**Binding design**: [design.md](design.md)

## Summary

Change Cosmos DB and DynamoDB `update()` from full replacement to native shallow
partial update while leaving Spanner's existing implementation unchanged.

The work is intentionally split:

1. shared API contract and preflight;
2. focused Cosmos/Dynamo production code and unit tests;
3. shared baseline conformance;
4. migration/docs/E2E; and
5. final validation.

## Current status

| Area | Status |
|---|---|
| T001–T015 shared API/preflight | Complete and passing |
| Restore `.gitignore` and `SpannerProviderClient.java` to HEAD | Complete |
| Cosmos production/unit work | Complete and passing |
| Dynamo production/unit work | Complete and passing |
| Spanner provider implementation | Unchanged |
| Dynamo result-item-size normalization | Implemented with focused unit coverage |
| Cosmos result-item-size normalization | Implemented with focused unit coverage |
| Feature artifacts/docs/contracts | Reconciled for request and result envelopes |
| Shared conformance | Provider-neutral coverage implemented; Cosmos and Dynamo runtime reruns complete |
| Provider-native result-size regressions | Cosmos and Dynamo emulator regressions pass |
| Spanner runtime validation | Existing shared conformance passed in PR CI |
| Emulator/full-suite validation | Clean unit plus Cosmos, Dynamo, and Spanner CI suites passed; complete E2E remains |

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

- `SpannerProviderClient.java`: no diff permitted
- `SpannerCapabilities.java`: only the two capability declarations/notes
- no new Spanner source or test file

## Implementation stages

### Stage 1 — Shared contract and validation

1. Keep both existing `update()` overloads and `Map<String,Object>`.
2. Validate field map/names and reject update TTL.
3. Enforce the exact 408,576-byte common limit.
4. Gate `Capability.PARTIAL_UPDATE` before delegation.
5. Define `PARTIAL_UPDATE_EXTENDED_PAYLOAD` as a lower native request/result
   envelope declaration for supported provider mappings.
6. Update Javadocs without claiming new Spanner behavior.

### Stage 2 — Cosmos DB

1. Build literal RFC 6901 `set` operations.
2. Use one direct patch for at most 10 fields.
3. For wider maps, build one same-item transactional batch.
4. Preflight the 100-operation and 2-MiB batch limits.
5. Select the first non-424 root failure, then aggregate fallback.
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

Update only provider-neutral shared tests and use existing baseline
fields/shapes. Do not add Spanner-specific tests or schema columns. Put update
TTL rejection in `CrudConformanceTests`, where all three concrete providers
inherit it. Also assert unchanged state for invalid/reserved fields and the
408,577-byte boundary, and `NOT_FOUND` without create for a wide missing-item
update.

The exact 408,576-byte positive boundary remains in the API validator unit
suite. Native provider envelopes and fixed schema make a provider-runtime
positive assertion non-portable.

Run named Cosmos and Dynamo emulator suites first. A later Spanner emulator run
uses the existing concrete conformance class and existing schema only.

Keep state-dependent result-item regressions in the concrete
`CosmosConformanceTest` and `DynamoConformanceTest`; each seeds below its native
limit and verifies that a small update fails atomically without adding a
provider branch to the shared base.

### Stage 5 — Docs and E2E

Document:

- shallow set/replace semantics;
- fixed-schema Spanner mapping;
- Cosmos/Dynamo native request and resulting-item envelopes;
- replacement migration to `upsert()` and its create-on-missing warning; and
- create/upsert-only TTL.

E2E remains provider-neutral and uses fields already provisioned by the current
Spanner setup. Do not add a Spanner schema helper.

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
compile successfully. No Spanner runtime validation is claimed.

### Remaining

1. complete provider-neutral E2E across configured Cosmos, Dynamo, and Spanner
   environments.

The exact CI Dynamo emulator profile passed all 88 discovered tests, including
the concrete result-item-size regression. The exact Cosmos profile passed all
78 discovered tests, including its concrete result-item-size regression, with
zero failures/errors and one expected emulator skip. Javadocs and feature
artifact/link/schema/traceability checks pass. PR CI also passes the unchanged
Spanner shared conformance suite.

## Planned parity matrix

| Behavior | Cosmos DB | DynamoDB | Spanner |
|---|---|---|---|
| Shallow set/replace | direct/batch patch | `UpdateItem SET` | existing partial mutation |
| Omitted fields preserved | yes | yes | yes |
| Missing item | 404 | condition failure | existing row-read behavior |
| Null shared baseline | JSON null | Dynamo NULL | existing STRING-null column |
| Map/list shared baseline | native JSON | M/L | existing encoded STRING columns |
| Wide request | one atomic batch | one expression | existing mutation |
| Lower native envelope | local request or attempted result-size rejection | local expression or attempted result-size rejection | none declared for supported mappings |
| New provider data path | yes | yes | no |

## Cost matrix

| Provider | Cost driver |
|---|---|
| Cosmos DB | one attempted point patch, or `ceil(fieldCount/10)` patch operations inside one atomic batch |
| DynamoDB | one attempted `UpdateItem`; accepted WCU is based on resulting item size |
| Spanner | unchanged existing read-write transaction |

No implementation may add an adapter read/replace cycle for Cosmos or Dynamo.

## Scope guard

Do not:

- modify `SpannerProviderClient.java`;
- add Spanner tests, typed-null logic, DDL, or E2E schema helpers;
- add a public patch model or `replace()` method;
- implement issues #102–#104; or
- touch/stage `multiclouddb-perf/`.

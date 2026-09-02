---
description: "Focused implementation tasks for portable partial update"
branch: "002-partial-update"
status: "ci-validation-complete-all-provider-e2e-pending"
---

# Tasks: Portable Partial Update

**Binding design**: `specs/002-partial-update/design.md`
**Scope**: shared API plus Cosmos DB and DynamoDB implementation. Spanner's
existing update data path is unchanged.

Checked tasks are complete. Unchecked tasks require final validation or a
configured Cosmos/Spanner/E2E environment.

## Phase 1: Setup and baseline

- [X] T001 Verify Java/dependency versions and the `unit`, `emulator-cosmos`, `emulator-dynamo`, and `emulator-spanner` profile group filters in `pom.xml`, `multiclouddb-api/pom.xml`, `multiclouddb-provider-cosmos/pom.xml`, `multiclouddb-provider-dynamo/pom.xml`, `multiclouddb-provider-spanner/pom.xml`, `multiclouddb-conformance/pom.xml`, and `multiclouddb-e2e/pom.xml`; retain pinned versions unless a binding-design API is unavailable
- [X] T002 Verify the pre-change `update()` behavior and test assumptions in the API and all three providers before editing the migration paths
- [X] T003 Run and retain the pre-change targeted unit baseline for API/Cosmos/Dynamo/Spanner modules

## Phase 2: Shared API and preflight

- [X] T004 Add `PartialUpdateValidatorTest` coverage for null/empty maps; null, empty, and blank names; non-trimmed names; reserved names; underscore prefixes; case collisions; punctuation acceptance; and update TTL rejection
- [X] T005 Add `DefaultMulticloudDbClientPartialUpdateTest` coverage for closed-client precedence, zero delegation, validation order, core capability gating, and no extended-capability lookup
- [X] T006 Add `DocumentSizeValidatorTest` coverage proving 408,576 bytes passes and 408,577 bytes fails
- [X] T007 Add `MulticloudDbClientPartialUpdateContractTest` coverage for both existing overloads, `Map<String,Object>`, default options, and TTL rejection
- [X] T008 Rewrite `MulticloudDbClient.update()` Javadocs for shallow set/replace, omitted-field preservation, mapping-aware null semantics, missing-item `NOT_FOUND`, exact validation, fixed-schema unchanged Spanner behavior, capabilities, and replacement migration
- [X] T009 Rewrite `MulticloudDbProviderClient.update()` Javadocs for the validated SPI contract and unchanged fixed-schema Spanner mapping
- [X] T010 Update `OperationOptions` Javadocs so TTL is create/upsert-only and `update()` rejects it
- [X] T011 Implement `PartialUpdateValidator` with `Locale.ROOT`, literal names, reserved/collision checks, underscore rejection, and update TTL rejection
- [X] T012 Add `PARTIAL_UPDATE` and `PARTIAL_UPDATE_EXTENDED_PAYLOAD`, declare them in all providers, and gate the core capability in `DefaultMulticloudDbClient`
- [X] T013 State and test the exact 408,576-byte common limit without changing the existing serializer
- [X] T014 Remove stale full-replacement wording from API update/delete documentation
- [X] T015 Run the focused API suite (`PartialUpdateValidatorTest`, `DefaultMulticloudDbClientPartialUpdateTest`, `DocumentSizeValidatorTest`, `MulticloudDbClientPartialUpdateContractTest`, `CapabilityTest`) successfully

## Phase 3: Focused Cosmos and Dynamo production work

### Cosmos DB

- [X] T016 Add `CosmosPartialUpdatePlannerTest` for RFC 6901 escaping, direct/batch selection, same-item batch operations, and exact native-envelope boundaries
- [X] T017 Implement the package-private `CosmosPartialUpdatePlanner` with literal `set` operations, at-most-10-operation chunks, prospective batch measurement, and structured local limit errors
- [X] T018 Add `CosmosPartialUpdateTest` proving one direct patch or one wide batch, no read/replace path, direct 404 normalization, and zero-I/O limit rejection
- [X] T019 Update `CosmosConsistencyTest` from `replaceItem` to `patchItem` and verify write response bodies are disabled explicitly
- [X] T020 Extend `CosmosErrorMappingTest` and `CosmosDiagnosticsLogTest` for exact 408/410 behavior, 424-skipping batch fallback, sanitized no-root failure, and batch diagnostics
- [X] T021 Replace the Cosmos update data path with direct patch/one transactional batch, add batch error normalization and diagnostics, and retain metadata-only write responses without an adapter retry loop

### DynamoDB

- [X] T022 Add `DynamoPartialUpdatePlannerTest` and mapper coverage for stable aliases, null/map/list values, UTF-8 measurement, and the generated expression boundary
- [X] T023 Implement the package-private `DynamoPartialUpdatePlanner` and structured single-value mapper
- [X] T024 Add `DynamoPartialUpdateTest` proving one conditional `UpdateItem`, no read/`PutItem`, consumed-capacity diagnostics, `NOT_FOUND`, zero-I/O expression rejection, and result-item-size normalization after one attempted update
- [X] T025 Replace the Dynamo update data path with one conditional aliased `UpdateItem` and add the AWS module reads required for a clean Java 17 module-path build

### Cross-provider declarations and focused validation

- [X] T026 Keep only the small partial-update declarations in `SpannerCapabilities.java`; all three providers declare all 19 known capabilities, and Spanner's notes describe existing fixed-schema behavior
- [X] T027 Restore `.gitignore` and `SpannerProviderClient.java` exactly to HEAD without checkout/reset; verify neither file has a diff
- [X] T028 Run focused tests successfully: API 36 tests, Cosmos 95 tests, and Dynamo 15 tests, all with zero failures/errors/skips
- [X] T029 Reconcile `spec.md`, binding `design.md`, `plan.md`, `research.md`, `data-model.md`, contracts, `quickstart.md`, requirements checklist, and `tasks.md` to the focused scope

**Checkpoint**: Shared/API and Cosmos/Dynamo unit implementation is complete.
No Spanner provider data-path or provider-test change is present.

## Phase 4: Shared baseline conformance

- [X] T030 Put update-TTL rejection in `CrudConformanceTests` so all three concrete providers inherit the zero-I/O `INVALID_REQUEST` assertion, and remove the unreachable duplicate from `TtlAndMetadataConformanceTest`
- [X] T031 Add provider-neutral partial-update assertions to `CrudConformanceTests` using only existing three-provider fixture fields/shapes: overwrite, omitted-field preservation, mapped absent field, missing-item `NOT_FOUND`, STRING-backed null, existing map/list mapping, replay, disjoint concurrency, a wider-than-10-field successful update, update-TTL and invalid/reserved-field atomic failure, exact 408,577-byte atomic failure, and a wider-than-10-field missing-item failure without create
- [X] T032 Verify the existing Cosmos, Dynamo, and Spanner concrete conformance classes inherit the same methods; update capability assertions for all 19 names without adding provider branches, new Spanner classes, or Spanner schema columns
- [X] T033 Run the named Cosmos emulator/conformance tests and verify positive Surefire discovery
- [X] T034 Re-run the exact Dynamo emulator/conformance profile, including the concrete result-item-size regression; all 88 discovered tests pass with zero failures/errors/skips
- [X] T035 Run the existing Spanner shared conformance suite against the existing schema only; PR CI passed without a Spanner-specific test, schema helper, or data-path change

## Phase 5: Documentation and migration

- [X] T036 Update `docs/guide.md`, `docs/api-reference.md`, `docs/configuration.md`, `docs/compatibility.md`, and `docs/changelog.md` for shallow update, capability declarations, fixed-schema Spanner mapping, native envelopes, TTL rejection, and `upsert()` migration
- [X] T037 Update `[Unreleased]` entries in API, Cosmos, Dynamo, and Spanner changelogs; the Spanner entry states capability declaration/documentation alignment only and no data-path change
- [X] T038 Update E2E to exercise selected-field preservation on every provider and a wider-than-10-field path on Cosmos/Dynamo only; the Spanner run remains fixed-schema and adds no schema helper
- [X] T039 Update `multiclouddb-e2e/README.md` and directly related root README text without claiming automatic Spanner column creation

## Phase 6: Final validation

- [X] T040 Re-run targeted API/Cosmos/Dynamo unit suites after conformance/docs edits (API 36, Cosmos 95, Dynamo 46; zero failures/errors/skips)
- [X] T041 Run the applicable complete unit and emulator/conformance suites with positive discovery: clean unit reactor plus complete Cosmos (78 discovered, one expected emulator skip) and Dynamo (88 discovered) profiles
- [X] T042 Run provider-neutral E2E against Cosmos Emulator, DynamoDB Local, and Spanner Emulator; all three provider runs completed successfully
- [X] T043 Build API/provider Javadocs and validate 23 changed Markdown files/anchors, four JSON examples, the provider-details schema, 19 capabilities per provider, and all 42 requirement IDs in task traceability
- [X] T044 Run `git diff --check` and final scope/status audit; confirm no `SpannerProviderClient.java` diff, no new Spanner test/source helper, no credentials, and no touched/staged `multiclouddb-perf/`

## Phase 7: Portability-review blocker remediation

- [X] T045 Normalize only DynamoDB update result-item-size `ValidationException` failures to non-retryable `UNSUPPORTED_CAPABILITY` with `dynamodb_result_item_size_limit`, `maximumResultBytes=409600`, native metadata, and cause preservation
- [X] T046 Add focused matching/non-matching Dynamo error-mapper tests and provider update-path coverage
- [X] T047 Add the runnable DynamoDB Local result-item overflow regression and keep it out of the shared abstract suite
- [X] T048 Move update-TTL coverage into `CrudConformanceTests`, remove the unreachable duplicate, and add shared unchanged-state/wide-missing coverage without provider branches
- [X] T049 Reconcile capability notes, feature artifacts, contracts/schema, docs, and changelogs for both Dynamo envelopes and the pre-I/O versus attempted-I/O distinction
- [X] T050 Run focused API/Dynamo/Cosmos tests, compile all 41 conformance test sources, parse the contract JSON, run `git diff --check`, and complete the final protected-path/scope audit

## Phase 8: Final Cosmos result-envelope remediation

- [X] T051 Normalize update-only Cosmos HTTP 413 from direct patch or batch to non-retryable `UNSUPPORTED_CAPABILITY` with `cosmos_result_item_size_limit`, `maximumResultBytes=2097152`, sanitized native metadata, and direct-exception cause preservation
- [X] T052 Add focused direct/batch mapper coverage and a concrete Cosmos emulator regression that seeds below 2 MiB, attempts a small overflowing update, and verifies unchanged stored state
- [X] T053 Reconcile the binding spec/design, plan, research, data model, contracts/schema, checklist, user docs, capability notes, and changelogs for the Cosmos state-dependent result-item envelope
- [X] T054 Run the exact Cosmos emulator profile and verify the concrete result-item-size regression; all 78 tests are discovered with zero failures/errors and one expected emulator skip

## Dependencies

```text
T001-T015
  -> T016-T029
  -> T030-T035
  -> T036-T039
  -> T040-T044
  -> T045-T050
  -> T051-T054
```

Cosmos and Dynamo emulator work can proceed independently after shared
conformance compiles. The existing Spanner shared conformance run follows the
same provider-neutral tests and requires no Spanner-specific implementation.

## Requirement traceability

| Requirements | Tasks |
|---|---|
| FR-001–FR-014 shared contract, validation, capabilities | T004–T015, T026, T030–T032 |
| FR-015 unchanged Spanner baseline | T008–T009, T026–T027, T029, T032, T035 |
| FR-016–FR-023 Cosmos mechanics/errors/diagnostics | T016–T021, T033 |
| FR-024–FR-027 Dynamo mechanics/errors | T022–T025, T034 |
| FR-031 Dynamo result-item envelope | T024, T034, T045–T047 |
| FR-032 Cosmos result-item envelope | T020–T021, T033, T051–T054 |
| FR-028 diagnostics safety | T020–T025, T043–T044 |
| FR-029 shared baseline-only conformance | T030–T035 |
| FR-030 migration | T036–T039 |
| NFR-001–NFR-005 | T016–T029, T040–T044 |
| SC-001–SC-003 focused unit success | T015, T028 |
| SC-004 shared conformance | T030–T035, T041 |
| SC-005 final scope/diff | T027, T044 |

## Counts

- Total tasks: **54**
- Completed: **53**
- Remaining: **1**

The exact Dynamo emulator profile passed all 88 discovered tests, and the
Cosmos profile passed all 78 discovered tests with one expected emulator skip.
The focused Dynamo E2E partial/wide update run remains recorded. PR CI also
passed the unchanged Spanner shared conformance suite. Complete three-provider
E2E remains intentionally unclaimed until all environments are configured
together.

## Scope rules

- Do not modify `SpannerProviderClient.java`.
- Do not add Spanner typed-null/schema work, provider-specific tests, or E2E
  schema helpers.
- Do not claim arbitrary missing Spanner columns can be created.
- Do not add a public patch model, `replace()` method, cancellation, retry
  configuration, or native-client escape hatch.
- Do not touch or stage `multiclouddb-perf/`.

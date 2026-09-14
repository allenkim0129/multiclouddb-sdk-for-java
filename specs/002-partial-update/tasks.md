---
description: "Focused implementation tasks for portable partial update"
branch: "002-partial-update"
status: "complete"
---

# Tasks: Portable Partial Update

**Binding design**: `specs/002-partial-update/design.md`
**Scope**: shared API plus Cosmos DB and DynamoDB implementation. Spanner is explicitly
unsupported and excluded through the shared core capability gate.

All tasks are complete.

## Phase 1: Setup and baseline

- [X] T001 Verify Java/dependency versions and the `unit`, `emulator-cosmos`, `emulator-dynamo`, and `emulator-spanner` profile group filters in `pom.xml`, `multiclouddb-api/pom.xml`, `multiclouddb-provider-cosmos/pom.xml`, `multiclouddb-provider-dynamo/pom.xml`, `multiclouddb-provider-spanner/pom.xml`, `multiclouddb-conformance/pom.xml`, and `multiclouddb-e2e/pom.xml`; retain pinned versions unless a binding-design API is unavailable
- [X] T002 Verify the pre-change `update()` behavior and test assumptions in the API and all three providers before editing the migration paths
- [X] T003 Run and retain the pre-change targeted unit baseline for API/Cosmos/Dynamo/Spanner modules

## Phase 2: Shared API and preflight

- [X] T004 Add `PartialUpdateValidatorTest` coverage for null/empty/over-10 maps; null, empty, and blank names; non-trimmed names; reserved names; underscore prefixes; case collisions; punctuation acceptance; and update TTL rejection
- [X] T005 Add `DefaultMulticloudDbClientPartialUpdateTest` coverage for closed-client precedence, zero delegation, validation order, core capability gating, and the 10-field limit
- [X] T006 Add `DocumentSizeValidatorTest` coverage proving the 390 KiB boundary passes and a larger payload fails
- [X] T007 Add `MulticloudDbClientPartialUpdateContractTest` coverage for both existing overloads, `Map<String,Object>`, default options, and TTL rejection
- [X] T008 Rewrite `MulticloudDbClient.update()` Javadocs for shallow set/replace, omitted-field preservation, mapping-aware null semantics, missing-item `NOT_FOUND`, exact validation, capability gating, and replacement migration
- [X] T009 Rewrite `MulticloudDbProviderClient.update()` Javadocs for the validated SPI contract and participating-provider boundary
- [X] T010 Update `OperationOptions` Javadocs so TTL is create/upsert-only and `update()` rejects it
- [X] T011 Implement `PartialUpdateValidator` with `Locale.ROOT`, literal names, reserved/collision checks, underscore rejection, and update TTL rejection
- [X] T012 Add `PARTIAL_UPDATE`, declare it in Cosmos/Dynamo, gate it in `DefaultMulticloudDbClient`, and represent native limits with structured reasons
- [X] T013 State and test the portable 390 KiB common limit without changing the existing serializer
- [X] T014 Remove stale full-replacement wording from API update/delete documentation
- [X] T015 Run the focused API suite (`PartialUpdateValidatorTest`, `DefaultMulticloudDbClientPartialUpdateTest`, `DocumentSizeValidatorTest`, `MulticloudDbClientPartialUpdateContractTest`, `CapabilityTest`) successfully

## Phase 3: Focused Cosmos and Dynamo production work

### Cosmos DB

- [X] T016 Add `CosmosPartialUpdatePlannerTest` for RFC 6901 escaping, deterministic ordering, the direct plan, and the 10-field boundary
- [X] T017 Implement the package-private `CosmosPartialUpdatePlanner` with sorted literal `set` operations, one direct patch plan, and defensive field-count validation
- [X] T018 Add `CosmosPartialUpdateTest` proving one direct patch, no read/replace/batch path, direct 404 normalization, and zero-I/O field-count rejection
- [X] T019 Update `CosmosConsistencyTest` from `replaceItem` to `patchItem` and verify write response bodies are disabled explicitly
- [X] T020 Extend `CosmosErrorMappingTest` and `CosmosDiagnosticsLogTest` for exact 408/410 behavior, 424-skipping batch fallback, sanitized no-root failure, and batch diagnostics
- [X] T021 Replace the Cosmos update data path with one direct patch and retain metadata-only write responses without an adapter retry loop

### DynamoDB

- [X] T022 Add `DynamoPartialUpdatePlannerTest` and mapper coverage for stable aliases, null/map/list values, UTF-8 measurement, and the generated expression boundary
- [X] T023 Implement the package-private `DynamoPartialUpdatePlanner` and structured single-value mapper
- [X] T024 Add `DynamoPartialUpdateTest` proving one conditional `UpdateItem`, no read/`PutItem`, consumed-capacity diagnostics, `NOT_FOUND`, zero-I/O expression rejection, and result-item-size normalization after one attempted update
- [X] T025 Replace the Dynamo update data path with one conditional aliased `UpdateItem` and add the AWS module reads required for a clean Java 17 module-path build

### Cross-provider declarations and focused validation

- [X] T026 Declare `PARTIAL_UPDATE` explicitly unsupported in Spanner and verify all providers expose 18 capability rows
- [X] T027 Confirm Spanner data-path and fixture files remain unchanged; limit Spanner edits to capabilities and changelog
- [X] T028 Run focused tests successfully: API 36 tests, Cosmos 95 tests, and Dynamo 15 tests, all with zero failures/errors/skips
- [X] T029 Reconcile `spec.md`, binding `design.md`, `plan.md`, `research.md`, `data-model.md`, contracts, `quickstart.md`, requirements checklist, and `tasks.md` to the focused scope

**Checkpoint**: Shared/API and Cosmos/Dynamo implementation is complete. Spanner
is excluded through capability gating; only its capability declaration and changelog change.

## Phase 4: Shared baseline conformance

- [X] T030 Put update-TTL rejection in `CrudConformanceTests` so all three concrete providers inherit the zero-I/O `INVALID_REQUEST` assertion, and remove the unreachable duplicate from `TtlAndMetadataConformanceTest`
- [X] T031 Add capability-gated partial-update behavior assertions for Cosmos/Dynamo plus provider-neutral update-TTL, invalid/reserved-field, and over-limit preflight assertions that also run before the Spanner gate
- [X] T032 Verify Cosmos/Dynamo run supported behavior, while Spanner advertises 18 capabilities with `PARTIAL_UPDATE` unsupported and runs the core unsupported assertion
- [X] T033 Run the named Cosmos emulator/conformance tests and verify positive Surefire discovery
- [X] T034 Re-run the exact Dynamo emulator/conformance profile, including the concrete result-item-size regression; all 88 discovered tests pass with zero failures/errors/skips
- [X] T035 Run Spanner conformance against the unchanged provider and verify shared validation plus core capability rejection

## Phase 5: Documentation and migration

- [X] T036 Update user docs for Cosmos/Dynamo shallow update, explicit Spanner capability rejection, native envelopes, TTL rejection, and `upsert()` migration
- [X] T037 Update API, Cosmos, Dynamo, and Spanner `[Unreleased]` entries for the final capability boundary
- [X] T038 Update E2E to exercise partial update on Cosmos/Dynamo and skip the scenario when `PARTIAL_UPDATE` is unsupported
- [X] T039 Update E2E and root README text to document Spanner as explicitly unsupported for the feature

## Phase 6: Final validation

- [X] T040 Re-run targeted API/Cosmos/Dynamo unit suites after conformance/docs edits (API 36, Cosmos 95, Dynamo 46; zero failures/errors/skips)
- [X] T041 Run the applicable complete unit and emulator/conformance suites with positive discovery: clean unit reactor plus complete Cosmos (78 discovered, one expected emulator skip) and Dynamo (88 discovered) profiles
- [X] T042 Run E2E against all three emulators; Cosmos/Dynamo execute partial update and Spanner skips it by capability
- [X] T043 Build Javadocs and validate Markdown, JSON, provider details, 18 capabilities on every provider with Spanner partial update unsupported, and requirement traceability
- [X] T044 Run the pre-review `git diff --check` and scope/status audit; confirm no credentials and no touched/staged `multiclouddb-perf/`

## Phase 7: Portability-review blocker remediation

- [X] T045 Normalize only DynamoDB update result-item-size `ValidationException` failures to non-retryable `UNSUPPORTED_CAPABILITY` with `dynamodb_result_item_size_limit`, `maximumResultBytes`, native metadata, and cause preservation
- [X] T046 Add focused matching/non-matching Dynamo error-mapper tests and provider update-path coverage
- [X] T047 Add the runnable DynamoDB Local result-item overflow regression and keep it out of the shared abstract suite
- [X] T048 Move update-TTL coverage into `CrudConformanceTests`, remove the unreachable duplicate, and add shared unchanged-state/field-count and literal-name coverage without provider branches
- [X] T049 Reconcile capability notes, feature artifacts, contracts/schema, docs, and changelogs for both Dynamo envelopes and the pre-I/O versus attempted-I/O distinction
- [X] T050 Run focused API/Dynamo/Cosmos tests, compile all 41 conformance test sources, parse the contract JSON, run `git diff --check`, and complete the final protected-path/scope audit

## Phase 8: Final Cosmos result-envelope remediation

- [X] T051 Normalize update-only Cosmos HTTP 413 from direct patch to non-retryable `UNSUPPORTED_CAPABILITY` with `cosmos_result_item_size_limit`, `maximumResultBytes`, sanitized native metadata, and direct-exception cause preservation
- [X] T052 Add focused direct-patch mapper coverage and a concrete Cosmos emulator regression that seeds below the Cosmos native ceiling, attempts a small overflowing update, and verifies unchanged stored state
- [X] T053 Reconcile the binding spec/design, plan, research, data model, contracts/schema, checklist, user docs, capability notes, and changelogs for the Cosmos state-dependent result-item envelope
- [X] T054 Run the exact Cosmos emulator profile and verify the concrete result-item-size regression; all 78 tests are discovered with zero failures/errors and one expected emulator skip

## Phase 9: Final portability-review blocker remediation

- [X] T055 Make literal and case-distinct field identity part of the base `PARTIAL_UPDATE` contract for Cosmos/Dynamo; declare Spanner explicitly unsupported
- [X] T056 Keep every Spanner data-path file at the PR base while adding only the explicit unsupported capability declaration and changelog
- [X] T057 Expand shared invalid-map/name conformance, gate supported behavior by `PARTIAL_UPDATE`, and assert Spanner core rejection
- [X] T058 Retain the 10-field and portable 390 KiB API validator boundaries and omit a shared provider-runtime size success assertion because native result envelopes may bind first
- [X] T059 Document Cosmos CRUD/update 408/410 normalization and direct-patch result limits in compatibility docs and changelogs
- [X] T060 Reconcile all feature artifacts and user docs with the Cosmos/Dynamo release scope and explicit Spanner unsupported declaration
- [X] T061 Run targeted/full validation, verify all three emulator profiles with Spanner capability gating, confirm unchanged Spanner data paths, and complete documentation/traceability audits

## Dependencies

```text
T001-T015
  -> T016-T029
  -> T030-T035
  -> T036-T039
  -> T040-T044
  -> T045-T050
  -> T051-T054
  -> T055-T061
```

Cosmos and Dynamo emulator work can proceed independently after shared
conformance compiles. Spanner runs shared preflight and unsupported-gate
coverage without any provider-module change.

## Requirement traceability

| Requirements | Tasks |
|---|---|
| FR-001–FR-014 shared contract, validation, capabilities | T004–T015, T026, T030–T032 |
| FR-015 unchanged-provider core capability rejection | T005, T008–T009, T026–T027, T032, T035, T056–T057 |
| FR-016–FR-023 Cosmos mechanics/errors/diagnostics | T016–T021, T033 |
| FR-024–FR-027 Dynamo mechanics/errors | T022–T025, T034 |
| FR-031 Dynamo result-item envelope | T024, T034, T045–T047 |
| FR-032 Cosmos result-item envelope | T020–T021, T033, T051–T054 |
| FR-033 explicit field-case identity | T055–T057, T060–T061 |
| FR-028 diagnostics safety | T020–T025, T043–T044 |
| FR-029 shared baseline-only conformance | T030–T035 |
| FR-030 migration | T036–T039 |
| NFR-001–NFR-005 | T016–T029, T040–T044, T055–T061 |
| SC-001–SC-003 focused unit success | T015, T028 |
| SC-004 shared conformance | T030–T035, T041, T057–T058, T061 |
| SC-005 final scope/diff | T027, T044, T061 |

## Counts

- Total tasks: **61**
- Completed: **61**
- Remaining: **0**

The exact Dynamo emulator profile passed all 88 discovered tests, and the
Cosmos profile passed all 78 discovered tests with one expected emulator skip.
Prior PR CI passed all provider jobs. The scope-correction rerun verifies
Cosmos/Dynamo behavior and Spanner's shared preflight/core-gate path before
publication.

## Scope rules

- Keep Spanner data-path and fixture files identical to the PR base; capabilities and changelog may document explicit unsupported status.
- Do not add Spanner data-path logic, schema
  fixtures, or E2E schema helpers beyond the approved capability/changelog alignment.
- Do not add a public patch model, `replace()` method, cancellation, retry
  configuration, or native-client escape hatch.
- Do not touch or stage `multiclouddb-perf/`.

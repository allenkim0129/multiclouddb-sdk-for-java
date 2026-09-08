# Implementation Plan: Cosmos Gateway Transport Defaults

**Branch**: `feat/cosmos-gateway-defaults` | **Date**: 2026-08-31 |
**Spec**: [spec.md](spec.md)
**Input**: Feature specification from
`/specs/feat/cosmos-gateway-defaults/spec.md`

## Summary

Standardize the Cosmos provider on Gateway mode with HTTP/2 explicitly enabled,
upgrade Azure Cosmos Java SDK from 4.78.0 to 4.82.0, and use the SDK's
probe-gated Gateway V2 routing as the zero-configuration default.
Retain `gatewayV2Enable` as a strict process-wide hard opt-in/opt-out, honor
operator-supplied Azure SDK settings, and fail fast when removed
`connectionMode`, `gatewayHttp2Enabled`, or draft `thinClientEnabled` keys are
present. Document Dedicated Gateway with Integrated Cache as a provider-native
profile that recommends the existing hard opt-out and warns when Gateway V2 is
enabled or eligible; no portable cache API or capability is introduced.

The change is isolated to Cosmos client construction, provider configuration,
tests, examples, changelogs, and design artifacts. It does not alter the
provider-neutral API or cross-provider data semantics.

## Technical Context

**Language/Version**: Java 17 LTS
**Primary Dependencies**: Azure Cosmos Java SDK 4.82.0, Azure Identity 1.18.2,
Jackson 2.22.1, SLF4J 2.0.12
**Storage**: N/A; construction-time provider configuration only
**Testing**: JUnit 5.10.2, Mockito 5.11.0, Maven Surefire, Cosmos emulator
conformance
**Target Platform**: JVM 17+ on Windows and Linux; Azure Cosmos DB and Cosmos
emulator endpoints
**Project Type**: Multi-module Maven library
**Performance Goals**: Add no wrapper-owned request path or network probe;
delegate Gateway V2 probing/fallback to the Azure SDK and perform wrapper
selection only during client construction
**Constraints**: Gateway and HTTP/2 are fixed; Direct/RNTBD is unavailable
through wrapper configuration; Gateway V2 with Integrated Cache is not
recommended; native Gateway V2 selection is JVM-global and read lazily;
portable cache policy and configurable staleness are out of scope;
provider-neutral API and behavior must remain unchanged
**Scale/Scope**: One dependency update, two Cosmos production classes, focused
provider tests, four conformance/example fixtures, user documentation,
changelogs, and feature design artifacts

There are no unresolved technical clarifications.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

| Principle | Status | Evidence |
|---|---|---|
| **0 - Portability-First Default** | PASS | Portable application operations are unchanged. Cosmos-specific transport selection remains connection configuration owned by the adapter. |
| **1 - Thin Wrapper** | PASS | The official Azure SDK performs all I/O, HTTP/2 transport, Gateway V2 probing, authentication, and fallback. No wrapper probe is introduced. |
| **2 - Capability-Based API** | PASS | No provider-neutral cache capability is promised or changed. Dedicated Gateway is explicitly provider-native deployment guidance. |
| **3 - Consistent Surface** | PASS | CRUD/query inputs, outputs, errors, and diagnostics are unchanged. The SDK-global Gateway V2 limitation is documented explicitly rather than presented as truly per-client. |
| **3.1 - Configuration-Only Portability** | PASS | Default behavior requires no code or transport setting. The operational opt-out is configuration-driven. |
| **4 - Explicit Reliability Controls** | PASS | Unset Gateway V2 state uses the provider SDK's connectivity probe and Gateway V1 fallback. Explicit hard opt-in failures remain visible. |
| **5 - Diagnostics Without Secrets** | PASS | Configuration conflicts fail with actionable, non-secret messages. Endpoint keys and credentials are never logged by this change. |
| **5.1 - Layered Diagnostics** | PASS | Wrapper validation identifies the invalid setting; native SDK connectivity failures remain available for explicit hard opt-in troubleshooting. |
| **Provider Adapter Requirements** | PASS | The adapter continues to delegate to the official SDK and exposes no new provider type through the portable API. |
| **Testing Minimum** | PASS | Provider construction behavior has focused unit coverage and existing Cosmos emulator conformance remains the integration gate. |
| **Versioning & Compatibility** | PASS | The provider changelog states SDK 4.82.0 and the pre-release breaking configuration/constant removal. |

**Pre-research gate result**: PASS. No constitutional violations require an
exception.

## Research Decisions

Phase 0 research is captured in [research.md](research.md):

1. Gateway mode is the only supported wrapper path.
2. HTTP/2 must be explicitly enabled because SDK 4.82.0 does not enable it by
   default.
3. SDK 4.82.0 supplies probe-gated Gateway V2 with Gateway V1 fallback.
4. Unset, `true`, and `false` must preserve the native SDK tri-state.
5. Gateway V2 selection is process-wide and follows operator-first precedence.
6. The narrower query-plan kill switch does not require wrapper exposure.
7. Removed switches fail fast instead of becoming silent no-ops.
8. Gateway V2 with Dedicated Gateway Integrated Cache is not recommended; the
   recommended cache profile uses the existing hard opt-out.

## Design

The detailed architecture and migration rationale are in
[design.md](design.md). The external behavior is fixed by
[contracts/configuration-contract.md](contracts/configuration-contract.md), and
the construction-time entities and transitions are in
[data-model.md](data-model.md).

### Construction Sequence

1. Read and validate the Cosmos endpoint.
2. Reject removed `connectionMode`, `gatewayHttp2Enabled`, and draft `thinClientEnabled` keys.
3. Parse `gatewayV2Enable` and `consistencyLevel` before global publication.
4. Configure endpoint and key or Azure identity on `CosmosClientBuilder`.
5. Attach `GatewayConnectionConfig` containing
   `Http2ConnectionConfig(enabled=true)`.
6. Apply consistency and user-agent configuration.
7. Preserve an existing native setting or publish the explicit connection
   preference; leave an absent value untouched.
8. Warn when a Dedicated Gateway endpoint has Gateway V2 enabled or eligible.
9. Build the native Cosmos client.

### Configuration Precedence

```text
COSMOS.THINCLIENT_ENABLED system property
    > COSMOS_THINCLIENT_ENABLED environment variable
    > multiclouddb.connection.gatewayV2Enable
    > unset SDK auto-probe/fallback
```

The connection-to-system-property check-and-set is synchronized. The native SDK
reads the setting lazily, so a later explicit value can affect an existing AUTO
client. Clients requiring different Gateway V2 values use separate JVM
processes.

## Project Structure

### Documentation (this feature)

```text
specs/feat/cosmos-gateway-defaults/
|-- spec.md
|-- plan.md
|-- research.md
|-- design.md
|-- data-model.md
|-- quickstart.md
`-- contracts/
    `-- configuration-contract.md
```

No `tasks.md` is created by this planning phase.

### Source Code (repository root)

```text
pom.xml
    # Azure Cosmos SDK version

multiclouddb-provider-cosmos/
|-- CHANGELOG.md
`-- src/
    |-- main/java/com/multiclouddb/provider/cosmos/
    |   |-- CosmosConstants.java
    |   `-- CosmosProviderClient.java
    `-- test/java/com/multiclouddb/provider/cosmos/
        |-- CosmosConstantsTest.java
        `-- CosmosGatewayDefaultsTest.java

multiclouddb-conformance/src/test/java/com/multiclouddb/conformance/
    # Cosmos fixtures no longer set connectionMode

multiclouddb-e2e/src/main/resources/cosmos.properties.template
    # Fixed transport and optional Gateway V2 opt-out example

docs/
|-- configuration.md
`-- changelog.md

README.md
specs/001-clouddb-sdk/
|-- plan.md
`-- spec.md
```

**Structure Decision**: Keep implementation in the existing Cosmos provider
module and place feature-specific design artifacts under the path selected by
the repository planning script. No module, service, endpoint, or portable API
package is added.

## Implementation Phases

### Phase 0 - Research

- Verify HTTP/2 defaults and public builder configuration in SDK 4.82.0.
- Verify Gateway V2 behavior for unset, `false`, and `true`.
- Verify system-property and environment-variable names and precedence.
- Verify query-plan routing follows the main Gateway V2 eligibility gate.
- Record Cosmos team guidance that Gateway V2 with Dedicated Gateway Integrated
  Cache is not recommended.
- Record decisions and rejected alternatives in `research.md`.

### Phase 1 - Design and Contract

- Define prioritized user scenarios and acceptance criteria in `spec.md`.
- Define fixed and tri-state configuration entities plus the standard and
  Dedicated Gateway deployment profiles in `data-model.md`.
- Define the external configuration contract and migration errors.
- Document architecture, global-state boundary, rollout, rollback, and PR
  dependency in `design.md`.
- Provide default, opt-out, force, migration, and Dedicated Gateway cache
  examples in `quickstart.md`.
- Update the original SDK plan with the Cosmos transport amendment.

### Phase 2 - Implementation

- Upgrade Azure Cosmos Java SDK to 4.82.0.
- Remove public connection-mode constants.
- Add `gatewayV2Enable` and internal Azure SDK setting names.
- Reject removed and renamed transport keys.
- Parse wrapper-owned settings before publishing JVM-wide state.
- Always construct Gateway mode with HTTP/2 enabled.
- Warn when a Dedicated Gateway endpoint has Gateway V2 enabled or eligible.
- Preserve SDK tri-state and native-setting precedence.
- Update active examples, conformance fixtures, and changelogs.
- Add focused unit tests.

## Test Strategy

| Level | Coverage |
|---|---|
| Unit | Gateway overload selected, HTTP/2 enabled, Direct never selected |
| Unit | Standard endpoint x Gateway V2 AUTO/false/true matrix |
| Unit | Dedicated endpoint x Gateway V2 AUTO/false/true matrix and warning |
| Unit | Operator SDK property is not overwritten |
| Unit | Malformed Boolean and removed/renamed keys fail before builder creation |
| Unit | Invalid consistency does not publish a Gateway V2 preference |
| Build | Cosmos provider and upstream API reactor |
| Integration | Cosmos emulator conformance under fixed Gateway HTTP/2 |
| Regression | DynamoDB and Spanner emulator jobs remain unchanged and green |

## Migration and Documentation

- Remove active `connectionMode` examples from README, configuration guide,
  conformance fixtures, and E2E template.
- Explain fixed Gateway/HTTP2 separately from Gateway V2 routing.
- Document JVM-wide semantics and operator precedence.
- Add SDK 4.82.0 and breaking pre-release cleanup to both Cosmos and aggregate
  changelogs.
- Document Gateway V2 as the default and Dedicated Gateway with Integrated
  Cache as a provider-native profile where Gateway V2 is not recommended.
- Document the Dedicated Gateway endpoint, `EVENTUAL` consistency, native
  staleness default, cache-hit cost, and JVM process-isolation constraints.
- Preserve historical changelog statements as historical records.

## Post-Design Constitution Re-check

The final design introduces no portable API, provider capability, retry,
diagnostic, data-model, or error-semantic divergence. The Dedicated Gateway
profile is explicitly Cosmos-native deployment guidance and does not claim the
planned portable cache capability.

The only ambient state is imposed by the official Azure SDK's Gateway V2
switch. The design uses synchronized no-overwrite publication, warns on the
non-recommended Gateway V2 and Integrated Cache combination, and validates
wrapper-owned values before publication. Because the SDK reads the value
lazily, clients requiring different Gateway V2 values use separate processes.

**Post-design gate result**: PASS. Implementation may proceed without a
constitution exception.

## Complexity Tracking

No constitution violations or additional architectural layers require
justification.

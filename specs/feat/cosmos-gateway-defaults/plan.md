# Implementation Plan: Cosmos Gateway Transport Defaults

**Branch**: `feat/cosmos-gateway-defaults` | **Date**: 2026-08-31 |
**Spec**: [spec.md](spec.md)
**Input**: Feature specification from
`/specs/feat/cosmos-gateway-defaults/spec.md`

## Summary

Standardize the Cosmos provider on Gateway mode with HTTP/2 explicitly enabled,
upgrade Azure Cosmos Java SDK from 4.78.0 to 4.82.0, and leave Gateway V1/V2
selection to account-advertised endpoints and the SDK connectivity probe.
Expose no HTTP-version or Gateway-version option, fail fast when pre-release
transport keys are present, and log fixed transport plus automatic selection
after successful client construction.

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
delegate Gateway V2 probing and per-request routing to the Azure SDK
**Constraints**: Gateway and required HTTP/2 are fixed; Direct/RNTBD is
unavailable through wrapper configuration; Gateway version selection is
account/SDK owned and decided after construction; provider-neutral API and
behavior must remain unchanged
**Scale/Scope**: One dependency update, two Cosmos production classes, focused
provider tests, four conformance/example fixtures, user documentation,
changelogs, and feature design artifacts

There are no unresolved technical clarifications.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

| Principle | Status | Evidence |
|---|---|---|
| **0 - Portability-First Default** | PASS | Portable application operations are unchanged. Cosmos transport remains an internal provider policy rather than a portable setting. |
| **1 - Thin Wrapper** | PASS | The official Azure SDK performs all I/O, HTTP/2 transport, Gateway V2 probing, authentication, and fallback. No wrapper probe is introduced. |
| **2 - Capability-Based API** | PASS | No provider-neutral capability is promised or changed. |
| **3 - Consistent Surface** | PASS | CRUD/query inputs, outputs, errors, and diagnostics are unchanged; no provider-specific selector is added to the portable surface. |
| **3.1 - Configuration-Only Portability** | PASS | Default behavior requires no code or transport setting. Account configuration and the native SDK select Gateway V1/V2 automatically. |
| **4 - Explicit Reliability Controls** | PASS | The provider SDK probes advertised Gateway V2 endpoints and retains Gateway V1 for unsuccessful probes or ineligible requests. |
| **5 - Diagnostics Without Secrets** | PASS | Configuration conflicts remain actionable, while successful construction logs fixed transport and automatic selection ownership. Endpoint keys and credentials are never logged by this change. |
| **5.1 - Layered Diagnostics** | PASS | Wrapper logs distinguish construction policy from per-request service routing; native SDK diagnostics remain available for connectivity troubleshooting. |
| **Provider Adapter Requirements** | PASS | The adapter continues to delegate to the official SDK and exposes no new provider type through the portable API. |
| **Testing Minimum** | PASS | Provider construction behavior has focused unit coverage and existing Cosmos emulator conformance remains the integration gate. |
| **Versioning & Compatibility** | PASS | The provider changelog states SDK 4.82.0 and the pre-release breaking configuration/constant removal. |

**Pre-research gate result**: PASS. No constitutional violations require an
exception.

## Research Decisions

Phase 0 research is captured in [research.md](research.md):

1. Gateway mode is the only supported wrapper path.
2. HTTP/2 must be explicitly enabled because Gateway V2 and newer supported
   Cosmos features require it and SDK 4.82.0 defaults it off.
3. SDK 4.82.0 supplies probe-gated Gateway V2 with Gateway V1 fallback.
4. Gateway V2 availability comes from account-advertised endpoints, and the SDK
   probes connectivity before routing eligible requests to them.
5. No supported public builder selector exists, so the wrapper does not expose
   or manipulate internal JVM-wide thin-client flags.
6. The narrower query-plan kill switch does not require wrapper exposure.
7. Removed pre-release switches fail fast instead of becoming silent no-ops.
8. Construction logs fixed transport and automatic-selection ownership, not a
   negotiated request route.

## Design

The detailed architecture and migration rationale are in
[design.md](design.md). The external behavior is fixed by
[contracts/configuration-contract.md](contracts/configuration-contract.md), and
the construction-time entities and transitions are in
[data-model.md](data-model.md).

### Construction Sequence

1. Read and validate the Cosmos endpoint.
2. Reject removed `connectionMode`, `gatewayHttp2Enabled`, `gatewayV2Enable`,
   and `thinClientEnabled` keys.
3. Configure endpoint and key or Azure identity on `CosmosClientBuilder`.
4. Attach `GatewayConnectionConfig` containing
   `Http2ConnectionConfig(enabled=true)`.
5. Apply consistency and user-agent configuration.
6. Build the native Cosmos client.
7. Log fixed Gateway/HTTP2 transport and automatic account/SDK selection as a
   construction snapshot, without claiming an actual per-request route.

### Gateway Version Ownership

The Cosmos account advertises Gateway V2 endpoint availability. Azure Cosmos
SDK 4.82.0 probes advertised endpoints and chooses Gateway V1 or V2 for each
eligible request, retaining Gateway V1 for metadata, unsupported requests, and
unsuccessful probes. The wrapper neither reads nor writes the SDK's internal
JVM-wide thin-client flags.

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
    # Fixed HTTP/2 transport and automatic Gateway selection

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
- Verify account-advertised Gateway V2 endpoints, SDK connectivity probing,
  request eligibility, and Gateway V1 fallback.
- Confirm no supported public Gateway-version selector exists and classify the
  native thin-client flags as internal implementation details.
- Verify query-plan routing follows the main Gateway V2 eligibility gate.
- Record decisions and rejected alternatives in `research.md`.

### Phase 1 - Design and Contract

- Define prioritized user scenarios and acceptance criteria in `spec.md`.
- Define fixed transport and automatic-selection entities in `data-model.md`.
- Define the external configuration contract and migration errors.
- Document architecture, account/SDK selection ownership, rollout, rollback,
  and PR dependency in `design.md`.
- Provide default, fallback, and migration examples in `quickstart.md`.
- Update the original SDK plan with the Cosmos transport amendment.

### Phase 2 - Implementation

- Upgrade Azure Cosmos Java SDK to 4.82.0.
- Remove public transport-selector constants.
- Reject all four removed pre-release transport keys.
- Always construct Gateway mode with HTTP/2 enabled.
- Do not read or write native thin-client JVM flags.
- Log fixed transport and automatic account/SDK selection after successful
  client construction without claiming an actual route.
- Update active examples, conformance fixtures, and changelogs.
- Add focused unit tests.

## Test Strategy

| Level | Coverage |
|---|---|
| Unit | Gateway overload selected, HTTP/2 enabled, Direct never selected |
| Unit | Construction log reports fixed transport and automatic selection ownership |
| Unit | All four removed transport keys fail before builder creation |
| Unit | Public constants omit removed transport selectors |
| Build | Cosmos provider and upstream API reactor |
| Integration | Cosmos emulator conformance under fixed Gateway HTTP/2 |
| Regression | DynamoDB and Spanner emulator jobs remain unchanged and green |

## Migration and Documentation

- Remove active transport-selector examples from README, configuration guide,
  conformance fixtures, and E2E template.
- Explain fixed Gateway/HTTP2 separately from Gateway V1/V2 routing.
- Document account-advertised availability, SDK connectivity probing, request
  eligibility, and automatic Gateway V1 fallback.
- Add SDK 4.82.0 and breaking pre-release cleanup to both Cosmos and aggregate
  changelogs.
- Distinguish the construction-time transport snapshot from actual per-request
  routing.
- Preserve historical changelog statements as historical records.

## Post-Design Constitution Re-check

The final design introduces no portable API, provider capability, retry,
diagnostic, data-model, or error-semantic divergence.

Gateway V2 availability is account-level configuration, and the official SDK
owns connectivity probing and per-request routing. The wrapper adds no global
state and logs its fixed transport policy without claiming a negotiated route.

**Post-design gate result**: PASS. Implementation may proceed without a
constitution exception.

## Complexity Tracking

No constitution violations or additional architectural layers require
justification.

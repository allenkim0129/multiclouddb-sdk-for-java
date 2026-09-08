# Feature Specification: Cosmos Gateway Transport Defaults

**Feature Branch**: `feat/cosmos-gateway-defaults`
**Created**: 2026-08-31
**Status**: In review
**Input**: Cosmos must use Gateway mode only, HTTP/2 must be fixed on, and
Gateway V2 routing must be eligible by default with an explicit user opt-out.
Dedicated Gateway with Integrated Cache is an alternate profile that recommends
the non-Gateway-V2 path; enabling or leaving Gateway V2 eligible with that
endpoint emits a warning. Direct mode remains unavailable.

## User Scenarios & Testing

### User Story 1 - Safe Gateway Defaults (Priority: P1)

As an SDK user, I want every Cosmos client to use the supported Gateway
transport automatically so that I do not need provider-specific transport
knowledge to establish a safe, performant connection.

**Why this priority**: A single safe default removes configuration drift and
prevents applications from unknowingly selecting a different network protocol.

**Independent Test**: Construct a Cosmos provider client with only endpoint and
credentials, then verify that the native builder receives Gateway mode with
HTTP/2 enabled and never receives Direct mode.

**Acceptance Scenarios**:

1. **Given** valid Cosmos endpoint and authentication settings, **When** a
   client is created, **Then** Gateway mode is selected.
2. **Given** no transport settings, **When** a client is created, **Then**
   HTTP/2 is explicitly enabled.
3. **Given** no Gateway V2 override, **When** Gateway V2 is reachable, **Then**
   the provider SDK may route through Gateway V2 after its connectivity probe.
4. **Given** no Gateway V2 override, **When** Gateway V2 is not reachable,
   **Then** the provider SDK remains on Gateway V1 without failing client
   construction.

---

### User Story 2 - Explicit Gateway V2 Opt-Out (Priority: P2)

As an operator, I want to disable Gateway V2 routing through configuration so
that I can mitigate a regional, account, or intermediary compatibility issue
or use Dedicated Gateway with Integrated Cache without
changing application code.

**Why this priority**: Gateway V2 is the preferred default, but operators need
a deterministic kill switch for incident response and the recommended
Dedicated Gateway cache topology.

**Independent Test**: Create a client with `gatewayV2Enable=false` and verify
that the provider supplies the Azure SDK hard opt-out before native client
construction.

**Acceptance Scenarios**:

1. **Given** `gatewayV2Enable=false`, **When** a Cosmos client is created,
   **Then** Gateway V2 is disabled process-wide through the Azure SDK setting.
2. **Given** `gatewayV2Enable=true`, **When** a Cosmos client is created,
   **Then** the Azure SDK receives an explicit hard opt-in.
3. **Given** an operator-supplied Azure SDK system property or environment
   variable, **When** the connection property disagrees, **Then** the
   operator-supplied value wins.
4. **Given** a provisioned Dedicated Gateway `sqlx` endpoint, `EVENTUAL`
   consistency, and `gatewayV2Enable=false`, **When** a Cosmos client is
   created, **Then** the provider remains in Gateway mode with Gateway V2
   disabled so eligible reads can use the provider-native Integrated Cache.

---
5. **Given** a Dedicated Gateway `sqlx` endpoint with `gatewayV2Enable` absent
   or `true`, **When** a Cosmos client is created, **Then** the provider warns
   that Gateway V2 with Integrated Cache is not recommended and identifies
   `gatewayV2Enable=false` as the corrective action.

### User Story 3 - Actionable Migration Failure (Priority: P3)

As an existing pre-release user, I want removed or renamed transport settings
to fail clearly so that stale configuration cannot appear
to work while being silently ignored.

**Why this priority**: Silent configuration changes make deployment behavior
unpredictable and are harder to diagnose than construction-time failures.

**Independent Test**: Attempt client construction with each removed key and
verify that it fails before network I/O with migration guidance.

**Acceptance Scenarios**:

1. **Given** any `connectionMode` value, **When** a client is created, **Then**
   construction fails and states that Gateway mode is fixed.
2. **Given** any `gatewayHttp2Enabled` value, **When** a client is created,
   **Then** construction fails and states that Gateway HTTP/2 is fixed.
3. **Given** an invalid `gatewayV2Enable` value, **When** a client is
   created, **Then** construction fails and lists the valid Boolean values.

### Edge Cases

4. **Given** the draft `thinClientEnabled` key, **When** a client is created,
   **Then** construction fails and identifies `gatewayV2Enable` as the
   replacement.
- A JVM system property and environment variable are both present: the Azure
  SDK system-property precedence is preserved.
- Multiple Cosmos clients request different Gateway V2 values: the existing
  JVM-wide native setting remains authoritative. The Azure SDK reads it lazily,
  so a later explicit value can also affect an already-created AUTO client;
  conflicting values require process isolation.
- The Gateway V2 setting is absent: the provider must not write the native
  property, because doing so would bypass SDK 4.82's safe connectivity probe.
- Gateway V2 is unsupported by the account or network path: the SDK probe must
  leave traffic on Gateway V1.
- A stale transport key uses the value that is now fixed (`gateway` or `true`):
  it still fails so the removed configuration surface cannot persist.
- A Dedicated Gateway `sqlx` endpoint is configured while Gateway V2 is enabled
  or eligible: construction warns that the combination is not recommended and
  directs the operator to `gatewayV2Enable=false`.
- Dedicated Gateway is configured with a consistency level other than
  `SESSION` or `EVENTUAL`: eligible reads bypass Integrated Cache, and an
  override stronger than the account default can also fail at the service; the
  documented Multicloud DB cache profile recommends `EVENTUAL`.

## Requirements

### Functional Requirements

- **FR-001**: The Cosmos provider MUST always select Gateway connection mode.
- **FR-002**: The Cosmos provider MUST NOT expose a supported Direct-mode
  configuration option.
- **FR-003**: The Cosmos provider MUST explicitly enable HTTP/2 on every native
  Cosmos client.
- **FR-004**: The Cosmos provider MUST NOT expose a supported HTTP/2 enablement
  toggle.
- **FR-005**: The provider MUST use an Azure Cosmos SDK version whose unset
  native Gateway V2 state performs connectivity probing with Gateway V1 fallback.
- **FR-006**: When `gatewayV2Enable` is absent, the provider MUST leave the
  Azure SDK native thin-client property unset.
- **FR-007**: `gatewayV2Enable=false` MUST provide a hard Gateway V2 opt-out.
- **FR-008**: `gatewayV2Enable=true` MUST provide an explicit Gateway V2
  opt-in.
- **FR-009**: Only case-insensitive `true` and `false` values are valid for
  `gatewayV2Enable`; all other values MUST fail before native client
  construction.
- **FR-010**: Existing non-empty Azure SDK system-property or environment
  settings MUST take precedence over the connection property.
- **FR-011**: Stale `connectionMode`, `gatewayHttp2Enabled`, and draft
  `thinClientEnabled` keys MUST fail before network I/O with actionable
  migration messages.
- **FR-012**: Documentation MUST state that Gateway V2 selection maps to a
  JVM-wide native SDK setting, not a per-client setting.
- **FR-013**: The change MUST NOT add or alter provider-neutral API methods,
  capability declarations, data semantics, or error normalization.
- **FR-014**: Cosmos emulator and provider unit coverage MUST continue to pass
  under the fixed Gateway transport.
- **FR-015**: Documentation MUST present Gateway V2 as the default standard
  endpoint profile and Dedicated Gateway with Integrated Cache as an explicit
  provider-native profile where Gateway V2 is not recommended.
- **FR-016**: Documentation MUST define the Dedicated Gateway profile with its
  `sqlx` endpoint and `gatewayV2Enable=false`, and SHOULD recommend
  `EVENTUAL` consistency for forward compatibility with the planned portable
  cache contract.
- **FR-017**: A recognized Dedicated Gateway endpoint where Gateway V2 is
  enabled or eligible MUST warn that the combination with Integrated Cache is
  not recommended and identify `gatewayV2Enable=false` as corrective action.
- **FR-018**: All wrapper-owned configuration values MUST be parsed before the
  provider publishes an explicit JVM-wide Gateway V2 preference.
- **FR-019**: Documentation MUST state that clients requiring different Gateway
  V2 preferences need separate JVM processes because the native thin-client
  setting is global and read lazily.
- **FR-020**: The Dedicated Gateway profile MUST be described as native Cosmos
  deployment guidance, not as an unimplemented portable cache capability.

### Key Entities

- **FixedTransportPolicy**: The non-configurable Cosmos transport decision:
  Gateway mode with HTTP/2 enabled.
- **GatewayV2Preference**: Tri-state Gateway V2 routing preference:
  `AUTO` (unset), `FORCE_ENABLED` (`true`), or `DISABLED` (`false`).
- **GatewayV2ConfigurationSource**: The effective source ordered by
  precedence: Azure SDK system property, Azure SDK environment variable, then
  Multicloud DB connection property.
- **GatewayDeploymentProfile**: Either a standard account endpoint with the
  selected tri-state Gateway routing behavior or a Dedicated Gateway endpoint
  with Gateway V2 disabled for Integrated Cache.
- **RemovedTransportSetting**: A stale `connectionMode` or
  `gatewayHttp2Enabled` key, or the renamed draft `thinClientEnabled` key, that
  causes construction-time rejection.

## Success Criteria

### Measurable Outcomes

- **SC-001**: 100% of Cosmos client-construction paths select Gateway mode with
  HTTP/2 enabled.
- **SC-002**: 0 supported configuration paths can select Direct mode or disable
  HTTP/2.
- **SC-003**: With no Gateway V2 override, 100% of clients leave routing to
  the provider SDK's probe-and-fallback behavior.
- **SC-004**: Standard and Dedicated Gateway endpoints each have automated
  coverage for `gatewayV2Enable` absent, `false`, and `true`, including native
  property publication and Integrated Cache warning presence or absence.
- **SC-005**: Active configuration examples contain no `connectionMode` or
  `gatewayHttp2Enabled` setting.
- **SC-006**: Provider unit tests and all three emulator conformance jobs pass.
- **SC-007**: User documentation contains one complete Dedicated Gateway cache
  example and states the endpoint, consistency, process-isolation, cost, and
  staleness constraints.
- **SC-008**: Invalid consistency plus an explicit Gateway V2 preference does
  not publish the JVM-wide preference.
- **SC-009**: Malformed, removed, renamed, and operator-precedence settings each
  have automated construction-time coverage.

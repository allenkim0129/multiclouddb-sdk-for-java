# Feature Specification: Cosmos Gateway Transport Defaults

**Feature Branch**: `feat/cosmos-gateway-defaults`
**Created**: 2026-08-31
**Status**: In review
**Input**: Cosmos must use Gateway mode only, HTTP/2 must be fixed on for newer
features, and Gateway V2 routing must be eligible by default with an explicit
user opt-out. Integrated Cache is an account-level option that requires HTTP/2
and automatically uses Gateway V1 even when Gateway V2 is enabled. Direct mode
remains unavailable.

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
   HTTP/2 is explicitly enabled because Gateway V2, Integrated Cache, and newer
   Multicloud DB Cosmos features require it.
3. **Given** no Gateway V2 override, **When** Gateway V2 is reachable, **Then**
   the provider SDK may route through Gateway V2 after its connectivity probe.
4. **Given** no Gateway V2 override, **When** Gateway V2 is not reachable,
   **Then** the provider SDK remains on Gateway V1 without failing client
   construction.

---

### User Story 2 - Gateway V2 Control and Transport Visibility (Priority: P2)

As an operator, I want to control Gateway V2 routing through configuration and
see the effective transport preference when each client is created so that I
can diagnose routing without confusing configuration with the actual
per-request route selected by Cosmos DB.

**Why this priority**: Gateway V2 is the preferred default, but operators need
a deterministic kill switch for incident response and accurate visibility into
the fixed transport and effective process-wide preference.

**Independent Test**: Create clients for standard and Dedicated Gateway
endpoints with `gatewayV2Enable` absent, `false`, and `true`; verify native
property publication and the INFO transport snapshot after successful native
client construction.

**Acceptance Scenarios**:

1. **Given** `gatewayV2Enable=false`, **When** a Cosmos client is created,
   **Then** Gateway V2 is disabled process-wide through the Azure SDK setting.
2. **Given** `gatewayV2Enable=true`, **When** a Cosmos client is created,
   **Then** the Azure SDK receives an explicit opt-in that bypasses its
   connectivity probe, while service-side routing remains authoritative.
3. **Given** an existing Azure SDK system property or environment variable,
   **When** the connection property disagrees, **Then** the existing native
   value wins.
4. **Given** Integrated Cache is enabled for the account, **When** eligible
   requests execute with any Gateway V2 preference, **Then** HTTP/2 remains
   enabled and Cosmos DB automatically routes the cache path through Gateway
   V1 without requiring a Gateway V2 opt-out.
5. **Given** any valid Gateway V2 preference, **When** native client construction
   succeeds, **Then** the provider logs Gateway mode, HTTP/2 enablement, and the
   effective preference, and identifies that the actual route is selected per
   request by Cosmos DB.

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
- A native Gateway V2 value is non-empty but not Boolean: the Azure SDK owns
  validation, warns, and treats it as unset/AUTO; the provider snapshot must not
  contradict that behavior by claiming AUTO is inactive.
- A stale transport key uses the value that is now fixed (`gateway` or `true`):
  it still fails so the removed configuration surface cannot persist.
- Integrated Cache is enabled while Gateway V2 is enabled or eligible: no
  wrapper warning or opt-out is needed because Cosmos DB automatically routes
  eligible cache requests through Gateway V1.
- The logged Gateway V2 preference can change after construction because the
  native value is read lazily; the log is explicitly a construction-time
  configuration snapshot and never claims to be the negotiated request route.
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
  Cosmos client because Gateway V2, Integrated Cache, and newer supported
  Cosmos features require it.
- **FR-004**: The Cosmos provider MUST NOT expose a supported HTTP/2 enablement
  toggle.
- **FR-005**: The provider MUST use an Azure Cosmos SDK version whose unset
  native Gateway V2 state performs connectivity probing with Gateway V1 fallback.
- **FR-006**: When `gatewayV2Enable` is absent, the provider MUST leave the
  Azure SDK native thin-client property unset.
- **FR-007**: `gatewayV2Enable=false` MUST provide a hard Gateway V2 opt-out.
- **FR-008**: `gatewayV2Enable=true` MUST provide an explicit Gateway V2
  opt-in that bypasses the connectivity probe without overriding service-side
  routing decisions.
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
  endpoint profile and Integrated Cache as an account-level provider-native
  option that automatically uses Gateway V1 even when Gateway V2 is enabled.
- **FR-016**: Documentation MUST define the Dedicated Gateway profile with its
  `sqlx` endpoint and eligible consistency level, MUST state that HTTP/2 is
  required, and MUST NOT require `gatewayV2Enable=false` for cache routing.
- **FR-017**: After successful native client construction, the provider MUST
  log Gateway mode, HTTP/2 enablement, and the effective Gateway V2 preference
  as a configuration snapshot, while stating that Cosmos DB selects the actual
  route per request and Integrated Cache uses Gateway V1.
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
  `AUTO` (unset), `ENABLED` (`true`), or `DISABLED` (`false`).
- **GatewayV2ConfigurationSource**: The effective source ordered by
  precedence: Azure SDK system property, Azure SDK environment variable, then
  Multicloud DB connection property.
- **GatewayDeploymentProfile**: Either a standard account endpoint with the
  selected tri-state Gateway routing behavior or a Dedicated Gateway endpoint
  whose account-level Integrated Cache automatically uses Gateway V1.
- **TransportConfigurationSnapshot**: The successful-construction INFO record
  of fixed transport and the effective Gateway V2 preference, explicitly not a
  negotiated per-request route.
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
  property publication, transport snapshot content, and absence of an
  endpoint-based Integrated Cache warning.
- **SC-005**: Active configuration examples contain no `connectionMode` or
  `gatewayHttp2Enabled` setting.
- **SC-006**: Provider unit tests and all three emulator conformance jobs pass.
- **SC-007**: User documentation contains one complete Dedicated Gateway cache
  example and states the account-level routing, HTTP/2, endpoint, consistency,
  cost, and staleness constraints.
- **SC-008**: Invalid consistency plus an explicit Gateway V2 preference does
  not publish the JVM-wide preference.
- **SC-009**: Malformed, removed, renamed, native-precedence, and invalid-native
  settings each have automated construction-time coverage without contradictory
  diagnostics.
- **SC-010**: 100% of successful client-construction matrix cases emit one INFO
  snapshot that distinguishes effective Gateway V2 preference from actual
  request routing.

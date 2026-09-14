# Feature Specification: Cosmos Gateway Transport Defaults

**Feature Branch**: `feat/cosmos-gateway-defaults`
**Created**: 2026-08-31
**Status**: In review
**Input**: Cosmos must use Gateway mode only, HTTP/2 must be fixed on for newer
features, and Gateway V1/V2 routing must be selected automatically from account
configuration by Azure Cosmos DB and its SDK. Direct mode and transport-version
selectors remain unavailable.

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
   HTTP/2 is explicitly enabled because Gateway V2 and newer Multicloud DB
   Cosmos features require it.
3. **Given** the account advertises Gateway V2 and the SDK probe succeeds,
   **When** an eligible request is sent, **Then** the provider SDK may route it
   through Gateway V2.
4. **Given** the account does not advertise Gateway V2 or the SDK probe does
   not succeed, **When** requests are sent, **Then** the provider SDK remains on
   Gateway V1 without failing client construction.

---

### User Story 2 - Automatic Gateway Selection and Visibility (Priority: P2)

As an operator, I want account and SDK settings to select the Gateway version
automatically and the fixed client transport to be logged so that unsupported
wrapper options cannot override service-owned routing.

**Why this priority**: Gateway V2 availability is account-level and probe-gated,
while the wrapper can accurately report only its fixed Gateway/HTTP2 policy.

**Independent Test**: Construct a client with no transport keys; verify that the
wrapper does not publish a Gateway-version setting and emits one INFO record
describing fixed Gateway/HTTP2 plus automatic account/SDK selection.

**Acceptance Scenarios**:

1. **Given** valid Cosmos configuration, **When** a client is created, **Then**
   the wrapper does not read or write internal Azure thin-client flags.
2. **Given** successful native client construction, **When** transport
   configuration is logged, **Then** the message states Gateway mode, HTTP/2
   enablement, and automatic account/SDK selection.
3. **Given** the construction log, **Then** it does not claim that Gateway V1
   or Gateway V2 was negotiated, because routing is evaluated later and per
   request.

### User Story 3 - Actionable Migration Failure (Priority: P3)

As an existing pre-release user, I want removed transport settings to fail
clearly so that stale configuration cannot appear to work while being silently
ignored.

**Why this priority**: Silent configuration changes make deployment behavior
unpredictable and are harder to diagnose than construction-time failures.

**Independent Test**: Attempt client construction with each removed key and
verify that it fails before network I/O with migration guidance.

**Acceptance Scenarios**:

1. **Given** any `connectionMode` value, **When** a client is created, **Then**
   construction fails and states that Gateway mode is fixed.
2. **Given** any `gatewayHttp2Enabled` value, **When** a client is created,
   **Then** construction fails and states that Gateway HTTP/2 is fixed.
3. **Given** any `gatewayV2Enable` value, **When** a client is created, **Then**
   construction fails and states that Gateway version selection is automatic.
4. **Given** any `thinClientEnabled` value, **When** a client is created,
   **Then** construction fails with the same automatic-selection guidance.

### Edge Cases

- Gateway V2 is not advertised by the account: the SDK remains on Gateway V1.
- A V2 endpoint is advertised but the connectivity probe does not succeed: the
  SDK remains on Gateway V1.
- The V2 probe succeeds but an operation is not eligible: that operation may
  still use Gateway V1.
- A stale transport key uses the value that is now fixed (`gateway` or `true`):
  it still fails so the removed configuration surface cannot persist.
- The construction log never claims a negotiated route because the service and
  SDK evaluate routing after construction and per request.

## Requirements

### Functional Requirements

- **FR-001**: The Cosmos provider MUST always select Gateway connection mode.
- **FR-002**: The Cosmos provider MUST NOT expose a supported Direct-mode
  configuration option.
- **FR-003**: The Cosmos provider MUST explicitly enable HTTP/2 on every native
  Cosmos client because Gateway V2 and newer supported Cosmos features require
  it.
- **FR-004**: The Cosmos provider MUST NOT expose a supported HTTP/2 enablement
  toggle.
- **FR-005**: The provider MUST use Azure Cosmos SDK 4.82.0 or later so
  account-advertised Gateway V2 endpoints use the SDK connectivity probe.
- **FR-006**: The provider MUST NOT expose a Gateway V1/V2 selection option.
- **FR-007**: The provider MUST NOT read or write Azure SDK internal
  thin-client settings.
- **FR-008**: Gateway version selection MUST remain owned by Cosmos account
  configuration and the native SDK. Missing endpoints or an unsuccessful probe
  MUST leave routing on Gateway V1; successful probing MAY enable Gateway V2
  for eligible requests.
- **FR-009**: Stale `connectionMode`, `gatewayHttp2Enabled`,
  `gatewayV2Enable`, and `thinClientEnabled` keys MUST fail before native client
  construction with actionable migration messages.
- **FR-010**: After successful native client construction, the provider MUST
  log Gateway mode, HTTP/2 enablement, and automatic account/SDK selection
  without claiming a negotiated Gateway version.
- **FR-011**: The change MUST NOT add or alter provider-neutral API methods,
  capability declarations, data semantics, or error normalization.
- **FR-012**: Cosmos emulator and provider unit coverage MUST continue to pass
  under the fixed Gateway transport.
- **FR-013**: User documentation MUST state that Gateway and HTTP/2 are fixed,
  Gateway version selection is automatic, and no wrapper selector exists.

### Key Entities

- **FixedTransportPolicy**: The non-configurable Cosmos transport decision:
  Gateway mode with HTTP/2 enabled.
- **AutomaticGatewayRouting**: Account endpoint advertisement, native SDK
  connectivity result, and per-request eligibility used by Azure Cosmos DB to
  select Gateway V1 or V2.
- **TransportConfigurationSnapshot**: The successful-construction INFO record
  of fixed transport and automatic selection, explicitly not a negotiated
  per-request route.
- **RemovedTransportSetting**: A stale transport selector that causes
  construction-time rejection.

## Success Criteria

### Measurable Outcomes

- **SC-001**: 100% of Cosmos client-construction paths select Gateway mode with
  HTTP/2 enabled.
- **SC-002**: 0 supported configuration paths can select Direct mode or disable
  HTTP/2 or select a Gateway version.
- **SC-003**: 0 wrapper code paths read or write an Azure SDK internal
  thin-client setting.
- **SC-004**: Active configuration examples contain none of the removed
  transport keys.
- **SC-005**: All four removed transport keys have automated
  construction-time rejection coverage.
- **SC-006**: 100% of successful construction cases emit one INFO snapshot
  that distinguishes fixed transport from automatic request routing.
- **SC-007**: Provider unit tests and all three emulator conformance jobs pass.

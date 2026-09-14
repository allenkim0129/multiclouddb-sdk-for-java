# Research: Cosmos Gateway Transport Defaults

This document records the decisions that support the design in
[design.md](design.md). All unknowns from the technical context are resolved.

## Decision 1: Use Gateway mode exclusively

- **Decision**: Always call `CosmosClientBuilder.gatewayMode(...)`; remove the
  public connection-mode constants and reject stale `connectionMode` entries.
- **Rationale**: The requested product policy has one supported Cosmos
  transport. Retaining a switch would preserve configuration drift and make
  performance, proxy, and firewall behavior dependent on an unnecessary user
  choice.
- **Alternatives considered**:
  - Keep Direct mode but change the default: rejected because users could still
    select an unsupported path.
  - Ignore `connectionMode`: rejected because a stale `direct` value would
    silently produce Gateway behavior.

## Decision 2: Enable HTTP/2 explicitly

- **Decision**: Attach `new Http2ConnectionConfig().setEnabled(true)` to the
  fixed `GatewayConnectionConfig`.
- **Rationale**: Azure Cosmos SDK 4.82.0 still defaults Gateway HTTP/2 to
  disabled internally. Gateway V2 and newer Cosmos features require HTTP/2, so
  relying on the native default would not satisfy the Multicloud DB transport
  contract.
- **Alternatives considered**:
  - Rely on the SDK default: rejected because it remains HTTP/2-off.
  - Set only `COSMOS.HTTP2_ENABLED`: rejected because it introduces ambient
    process configuration when a supported per-client builder setting exists.

## Decision 3: Adopt Azure Cosmos SDK 4.82.0

- **Decision**: Upgrade `com.azure:azure-cosmos` from 4.78.0 to 4.82.0.
- **Rationale**: Version 4.82.0 makes Gateway V2 eligible by default for
  Gateway HTTP/2 clients. With no explicit Gateway V2 value, it performs a
  connectivity probe and routes to Gateway V2 only after an affirmative
  result; otherwise it stays on Gateway V1.
- **Alternatives considered**:
  - Stay on 4.78.0 or 4.81.0 and force Gateway V2 on: rejected because those
    versions do not provide the requested safe default with automatic fallback.
  - Implement a wrapper-owned probe: rejected by the thin-wrapper principle and
    would duplicate provider SDK networking logic.

## Decision 4: Expose no Gateway version selector

- **Decision**: Do not expose a Multicloud DB Gateway V1/V2 option and do not
  read or write the Azure SDK's internal thin-client flags.
- **Rationale**: Gateway V2 availability is advertised by the Cosmos account,
  and SDK 4.82.0 performs its own connectivity probe. The SDK has no supported
  public per-client Gateway V2 builder API; its JVM flags are implementation
  details that were introduced for internal testing and emergency control.
  Promoting them into the wrapper would create an unsupported, process-wide
  public contract and could bypass the safe probe.
- **Alternatives considered**:
  - Map a connection property to the internal JVM flag: rejected because it is
    unsupported, global, and not account provisioning.
  - Expose only an opt-out: rejected for the same reason; account/service and
    SDK controls remain authoritative.
  - Use reflection to mutate SDK internals: rejected as brittle and unsupported.

## Decision 5: Delegate Gateway V1/V2 selection

- **Decision**: Enable Gateway mode and HTTP/2, then leave version selection to
  the Cosmos account response and Azure SDK connectivity probe.
- **Rationale**: The account response supplies Gateway V2 readable/writable
  locations. The SDK starts conservatively on V1, probes an advertised V2
  endpoint, and enables V2 only after success. Eligible data-plane requests may
  then use V2; metadata, unsupported operations, missing endpoints, and failed
  probes remain on V1.
- **Alternatives considered**:
  - Implement wrapper routing or probing: rejected by the thin-wrapper
    principle and because it would duplicate SDK behavior.
  - Claim V2 for every request after a successful probe: rejected because route
    eligibility remains request-specific.

## Decision 6: Fail fast on removed switches

- **Decision**: Reject `connectionMode`, `gatewayHttp2Enabled`, and the
  pre-release `gatewayV2Enable`/`thinClientEnabled` keys whenever present.
- **Rationale**: This makes migration explicit and prevents configuration files
  from carrying ineffective settings indefinitely.
- **Alternatives considered**:
  - Ignore the removed keys: rejected because they would look effective while
    having no effect.

## Decision 7: Log configuration, not a negotiated route

- **Decision**: After successful native client construction, log Gateway mode,
  fixed HTTP/2 enablement, and automatic account/SDK route selection.
- **Rationale**: Azure Cosmos SDK 4.82.0 exposes no public client-construction
  API for a negotiated Gateway version. V2 eligibility depends on account
  topology, a connectivity probe, and request type after construction.
- **Alternatives considered**:
  - Log that the client "uses Gateway V1/V2": rejected because that would turn
    a fixed configuration into a false negotiated-route claim.
  - Inspect SDK internals via reflection: rejected as unsupported and brittle.

## Official Sources

- Azure Cosmos SDK 4.82.0 release:
  <https://github.com/Azure/azure-sdk-for-java/releases/tag/com.azure%2Bazure-cosmos_4.82.0>
- `GatewayConnectionConfig` source:
  <https://github.com/Azure/azure-sdk-for-java/blob/com.azure%2Bazure-cosmos_4.82.0/sdk/cosmos/azure-cosmos/src/main/java/com/azure/cosmos/GatewayConnectionConfig.java>
- `Http2ConnectionConfig` source:
  <https://github.com/Azure/azure-sdk-for-java/blob/com.azure%2Bazure-cosmos_4.82.0/sdk/cosmos/azure-cosmos/src/main/java/com/azure/cosmos/Http2ConnectionConfig.java>
- Account-response model carrying Gateway V2 locations:
  <https://github.com/Azure/azure-sdk-for-java/blob/com.azure%2Bazure-cosmos_4.82.0/sdk/cosmos/azure-cosmos/src/main/java/com/azure/cosmos/implementation/DatabaseAccount.java>
- SDK Gateway V2 connectivity configuration and probe:
  <https://github.com/Azure/azure-sdk-for-java/blob/com.azure%2Bazure-cosmos_4.82.0/sdk/cosmos/azure-cosmos/src/main/java/com/azure/cosmos/implementation/ThinClientConnectivityConfig.java>
  and
  <https://github.com/Azure/azure-sdk-for-java/blob/com.azure%2Bazure-cosmos_4.82.0/sdk/cosmos/azure-cosmos/src/main/java/com/azure/cosmos/implementation/EndpointProbeClient.java>
- Internal SDK flags (not a public builder contract):
  <https://github.com/Azure/azure-sdk-for-java/blob/com.azure%2Bazure-cosmos_4.82.0/sdk/cosmos/azure-cosmos/src/main/java/com/azure/cosmos/implementation/Configs.java>

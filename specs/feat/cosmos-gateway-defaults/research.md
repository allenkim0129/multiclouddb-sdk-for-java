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
  disabled internally. Gateway V2 requires Gateway mode plus HTTP/2, so relying
  on the native default would not satisfy the feature.
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

## Decision 4: Preserve the SDK tri-state

- **Decision**:
  - unset -> leave `COSMOS.THINCLIENT_ENABLED` unset (`AUTO`);
  - `false` -> set the SDK hard opt-out;
  - `true` -> set the SDK hard opt-in.
- **Rationale**: Writing `true` as the wrapper default would skip the 4.82.0
  connectivity probe. Leaving the property absent is the only way to obtain
  probe-gated Gateway V2 with Gateway V1 fallback.
- **Alternatives considered**:
  - Default the wrapper property to `true`: rejected because it bypasses the
    probe.
  - Support only `false`: rejected because a strict Boolean override is easier
    to operate and preserves the Azure SDK's explicit opt-in path.

## Decision 5: Treat Gateway V2 selection as process-wide

- **Decision**: Map `gatewayV2Enable` to the Azure SDK's JVM-wide native
  thin-client setting before native client construction. Preserve this precedence:
  non-empty system property, non-empty environment variable, connection
  property, then unset SDK default.
- **Rationale**: Azure SDK 4.82.0 does not expose a per-client Gateway V2
  builder API. It reads the internally named `COSMOS.THINCLIENT_ENABLED` or
  `COSMOS_THINCLIENT_ENABLED` from static configuration. Synchronizing the
  check-and-set prevents two wrapper client constructors from overwriting each
  other, but applications must still use one value per JVM.
- **Alternatives considered**:
  - Pretend the setting is per-client: rejected because that contract would be
    false.
  - Remove the Multicloud DB setting and require only a JVM flag: rejected
    because configuration-only operation is a project principle.
  - Use reflection to mutate SDK internals: rejected as unsupported and brittle.

## Decision 6: The query-plan switch needs no wrapper setting

- **Decision**: Do not expose
  `COSMOS.THINCLIENT_QUERY_PLAN_ENABLED`.
- **Rationale**: Query-plan routing first checks the main thin-client
  eligibility decision. The main `COSMOS.THINCLIENT_ENABLED=false` opt-out
  therefore prevents Gateway V2 use for query-plan requests as well. The
  separate Azure setting is a narrower SDK kill switch, not required to meet
  this feature's all-thin-client opt-out contract.
- **Alternatives considered**:
  - Set both flags: rejected because the second flag is redundant for the
    requested opt-out and would expand wrapper configuration without need.

## Decision 7: Fail fast on removed switches

- **Decision**: Reject `connectionMode` and `gatewayHttp2Enabled` whenever
  present, even when their values equal the fixed behavior. Reject the earlier
  draft key `thinClientEnabled` with guidance to use `gatewayV2Enable`.
- **Rationale**: This makes migration explicit and prevents configuration files
  from carrying ineffective settings indefinitely.
- **Alternatives considered**:
  - Accept `gateway` and `true` for compatibility: rejected because they would
    remain misleading no-op controls.

## Decision 8: Treat Integrated Cache as an alternate deployment profile

- **Decision**: Keep Gateway V2 as the zero-configuration standard-endpoint
  profile. Dedicated Gateway with Integrated Cache uses its `sqlx` endpoint,
  `gatewayV2Enable=false`, and `EVENTUAL` consistency in Multicloud DB
  examples.
- **Rationale**: Cosmos team guidance does not recommend Gateway V2 with
  Integrated Cache. Integrated Cache is server-side memory on dedicated compute
  and forwards cache misses to backend nodes; Gateway V2 adds another routing
  path without improving cache hits.
- **Scope**: This PR documents a Cosmos-native deployment profile only. It does
  not add the planned portable cache capability, configurable staleness, or
  provisioning. The Dedicated Gateway service-side staleness default applies.
- **Alternatives considered**:
  - Keep Gateway V2 enabled with Integrated Cache: rejected as the recommended
    profile because the combination adds no cache benefit.
  - Make Dedicated Gateway the default: rejected because its paid compute and
    bounded-staleness cache suit read-heavy workloads, not every workload.
  - Add a portable cache API in this PR: rejected because that requires a
    separate cross-provider design covering DynamoDB DAX and Spanner.

## Official Sources

- Azure Cosmos SDK 4.82.0 release:
  <https://github.com/Azure/azure-sdk-for-java/releases/tag/com.azure%2Bazure-cosmos_4.82.0>
- `GatewayConnectionConfig` source:
  <https://github.com/Azure/azure-sdk-for-java/blob/com.azure%2Bazure-cosmos_4.82.0/sdk/cosmos/azure-cosmos/src/main/java/com/azure/cosmos/GatewayConnectionConfig.java>
- `Http2ConnectionConfig` source:
  <https://github.com/Azure/azure-sdk-for-java/blob/com.azure%2Bazure-cosmos_4.82.0/sdk/cosmos/azure-cosmos/src/main/java/com/azure/cosmos/Http2ConnectionConfig.java>
- SDK global configuration and tri-state parsing:
  <https://github.com/Azure/azure-sdk-for-java/blob/com.azure%2Bazure-cosmos_4.82.0/sdk/cosmos/azure-cosmos/src/main/java/com/azure/cosmos/implementation/Configs.java>
- Query-plan Gateway V2 gate:
  <https://github.com/Azure/azure-sdk-for-java/blob/com.azure%2Bazure-cosmos_4.82.0/sdk/cosmos/azure-cosmos/src/main/java/com/azure/cosmos/implementation/query/QueryPlanRetriever.java>
- Dedicated Gateway overview:
  <https://learn.microsoft.com/azure/cosmos-db/dedicated-gateway>
- Configure Integrated Cache:
  <https://learn.microsoft.com/azure/cosmos-db/how-to-configure-integrated-cache>

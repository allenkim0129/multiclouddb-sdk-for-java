# Design: Cosmos Gateway Transport Defaults

**Status**: In review — implementation proposed in PR #101
**Created**: 2026-08-31
**Last updated**: 2026-09-09
**Feature spec**: [spec.md](spec.md)
**Implementation plan**: [plan.md](plan.md)

## Context

The Cosmos adapter previously exposed `connectionMode`, allowing Gateway or
Direct/RNTBD operation. Gateway HTTP/2 and Gateway V2 routing were also easy to
treat as one setting even though they are separate layers:

1. **Gateway mode** selects the HTTP-based Cosmos connectivity path instead of
   Direct/RNTBD.
2. **Gateway HTTP/2** selects the wire protocol used by the Gateway client.
3. **Gateway V2** selects a lower-overhead data-plane proxy when the account and
   network path support it.

The product decision is to standardize the first two and make Gateway V2 safe
by default. Integrated Cache is an account-level option that requires HTTP/2
and automatically uses Gateway V1 even when Gateway V2 is enabled, so it does
not require a wrapper opt-out or warning.

## Change at a Glance

```mermaid
flowchart LR
    subgraph Before["Before"]
        direction TB
        BConfig["Connection configuration"]
        BConfig --> BMode{"Choose connection mode"}
        BMode -->|Gateway| BGateway["Gateway path<br/>SDK transport defaults"]
        BMode -->|Direct| BDirect["Direct / RNTBD path"]
    end

    subgraph After["After PR 101"]
        direction TB
        AConfig["Connection configuration"]
        AConfig --> AProvider["Cosmos provider"]
        AProvider --> AGateway["Gateway mode<br/>fixed"]
        AGateway --> AHttp2["HTTP/2<br/>fixed on"]
        AHttp2 --> AProfile{"Service/account routing"}
        AProfile -->|standard account| AThin["Gateway V2 preference<br/>AUTO by default"]
        AProfile -->|Integrated Cache enabled| ACache["Dedicated Gateway<br/>Gateway V1 cache path"]
    end
```

`gatewayV2Enable` controls the process-wide Gateway V2 preference, not the final
per-request route. It cannot select Direct mode or disable HTTP/2. Cosmos DB
service routing remains authoritative and automatically selects Gateway V1 for
Integrated Cache.

## Goals

- Construct every Cosmos client in Gateway mode.
- Explicitly enable HTTP/2 for every Gateway client because Gateway V2,
  Integrated Cache, and newer supported Cosmos features require it.
- Make SDK 4.82.0's probe-gated Gateway V2 behavior the zero-configuration
  default.
- Preserve deterministic hard opt-out and probe-bypass controls.
- Document Dedicated Gateway with Integrated Cache as an account-level,
  Cosmos-native profile that automatically uses Gateway V1.
- Reject removed settings rather than silently changing their meaning.
- Keep the provider-neutral API and cross-provider data semantics unchanged.

## Non-goals

- Exposing Direct/RNTBD through the portable wrapper.
- Implementing a wrapper-owned Gateway V2 connectivity probe.
- Making the Azure SDK's global Gateway V2 flag truly per-client.
- Exposing the narrower Azure query-plan kill switch.
- Defining a portable read-through-cache capability or configurable cache
  staleness; those require a separate cross-provider design covering DynamoDB
  DAX and unsupported providers.
- Defining Gateway connection-pool sizes; those are performance-tuning work in
  dependent PR #98.

## Decision Summary

| Area | Decision |
|---|---|
| Native SDK | Upgrade Azure Cosmos Java SDK to 4.82.0 |
| Connection mode | Always Gateway; no supported switch |
| HTTP protocol | Always HTTP/2 via explicit builder configuration |
| Gateway V2 default | Leave the native SDK value unset for probe/fallback |
| User override | `gatewayV2Enable=false` disables; `true` enables without probing |
| Dedicated Gateway cache | Account-level option; `sqlx` endpoint + eligible consistency; automatic Gateway V1 |
| Profile isolation | Clients needing different Gateway V2 values use separate JVM processes |
| Native-setting precedence | SDK system property, then SDK environment variable |
| Removed keys | Reject `connectionMode`, `gatewayHttp2Enabled`, and draft `thinClientEnabled` |
| Portable API | No change |

## Gateway V2 Terminology

Gateway V2 is the public feature name. Azure Cosmos SDK internals and native
settings also call this path the "thin client". It is **not** another client
library, application-side process, sidecar, or proxy that users install.
Application code, credentials, endpoints, and Multicloud DB operations remain
the same.

```mermaid
flowchart LR
    App["Application<br/>same Multicloud DB calls"]
    Provider["Cosmos provider"]
    SDK["Azure Cosmos SDK 4.82.0<br/>Gateway + HTTP/2"]
    V1["Cosmos Gateway V1<br/>metadata and fallback"]
    V2["Cosmos Gateway V2<br/>eligible data-plane requests"]
    Data["Cosmos DB data"]

    App --> Provider
    Provider --> SDK
    SDK -->|"opt-out, AUTO fallback,<br/>or Integrated Cache"| V1
    SDK -->|"AUTO success or<br/>enabled without probing"| V2
    V1 --> Data
    V2 --> Data
```

Both branches remain Gateway-mode HTTP/2 traffic. The setting expresses a
Gateway V2 preference; it does not change the portable API or document/query
behavior, and it does not override service-side routing. Gateway V2 handles
eligible data-plane requests; metadata and Integrated Cache requests remain on
Gateway V1.

## Dedicated Gateway with Integrated Cache

Integrated Cache is account-level server-side memory enabled by provisioning
paid Dedicated Gateway compute, not a client-local cache. It requires HTTP/2
and automatically routes eligible cache requests through Gateway V1 even when
Gateway V2 is enabled.

The recommended provider-native cache profile uses:

1. A provisioned Dedicated Gateway and its `sqlx.cosmos.azure.com` endpoint.
2. `EVENTUAL` consistency in Multicloud DB examples, matching the planned
   portable cache contract.
3. The provider's fixed Gateway mode and HTTP/2 transport. No Gateway V2
   opt-out is required.

Cache-hit point reads and queries may return with 0 RU; writes, misses, and
Dedicated Gateway compute retain their normal cost. This feature does not add
a portable cache capability or expose `MaxIntegratedCacheStaleness`, so the
Dedicated Gateway service-side staleness default applies.

Because the Azure SDK reads its JVM-wide native setting lazily, clients that
need different Gateway V2 preferences must run in separate JVM processes.
Integrated Cache and Gateway V2 do not themselves require process isolation.

## Construction Flow

```mermaid
flowchart TD
    Config["MulticloudDbClientConfig"]
    Validate["Validate endpoint and reject removed keys"]
    Parse["Parse gatewayV2Enable<br/>and consistencyLevel"]
    Builder["Configure CosmosClientBuilder<br/>endpoint, credentials, Gateway, HTTP/2"]
    Resolve["Preserve existing global value<br/>or publish explicit preference"]
    Build["Build Cosmos client"]
    Log["Log fixed transport + effective preference<br/>not negotiated route"]

    Config --> Validate
    Validate --> Parse
    Parse --> Builder
    Builder --> Resolve
    Resolve --> Build
    Build --> Log
```

Validation precedes native client construction so malformed or stale
configuration cannot trigger credential work or network I/O.

## Transport Observability

The provider emits one INFO record only after `CosmosClientBuilder.buildClient()`
succeeds:

```text
Cosmos transport configured: Gateway mode, HTTP/2 enabled,
Gateway V2 preference at client creation: <preference>.
Actual routing is selected per request by Azure Cosmos DB;
accounts with Integrated Cache use Gateway V1.
```

`<preference>` is derived from the effective JVM-wide native setting at that
moment:

| Effective native value | Logged preference |
|---|---|
| absent | `AUTO (probe/fallback)` |
| `true` | `ENABLED (probe bypassed)` |
| `false` | `DISABLED (Gateway V1)` |
| other non-empty value | `AUTO (invalid native value is treated as unset by Azure SDK)` |

This is deliberately a **configuration snapshot**, not a negotiated Gateway
version. Azure Cosmos SDK 4.82.0 exposes no public client-construction getter
for that result, and routing can vary by connectivity-probe result, service
topology, request type, and the lazily read process-wide preference. Logging
"using Gateway V1" or "using Gateway V2" at construction would therefore be
incorrect except when V2 is explicitly disabled.

The provider does not infer Integrated Cache enablement from an endpoint and
does not warn for a Dedicated Gateway plus Gateway V2 preference. Integrated
Cache is account-level state that Cosmos DB resolves by routing eligible cache
requests through Gateway V1. The INFO record states this rule without claiming
that Integrated Cache is enabled for the current account.

## Gateway V2 Selection

```mermaid
flowchart TD
    Start["Gateway mode and HTTP/2 are already fixed"]
    System{"Non-empty SDK<br/>system property?"}
    Environment{"Non-empty SDK<br/>environment variable?"}
    Connection{"Connection property<br/>gatewayV2Enable?"}
    Publish["Validate and publish<br/>one JVM-wide SDK setting"]
    Value{"Resolved Boolean value"}
    Auto["AUTO<br/>leave SDK setting unset"]
    Probe["Azure SDK probes Gateway V2"]
    Eligible["Gateway V2 eligible<br/>service routing applies"]
    V2["Route eligible data-plane requests<br/>through Gateway V2"]
    V1["Route through Gateway V1"]
    Enable["Enable Gateway V2 eligibility<br/>skip probe"]
    Off["Disable Gateway V2<br/>skip probe"]
    Error["Fail client construction"]

    Start --> System
    System -->|Yes| Value
    System -->|No| Environment
    Environment -->|Yes| Value
    Environment -->|No| Connection
    Connection -->|true or false| Publish
    Connection -->|unset| Auto
    Connection -->|invalid| Error
    Publish --> Value
    Value -->|true| Enable
    Value -->|false| Off
    Enable --> Eligible
    Eligible -->|normal eligible request| V2
    Eligible -->|Integrated Cache or service V1| V1
    Off --> V1
    Auto --> Probe
    Probe -->|available| Eligible
    Probe -->|unavailable| V1
```

| Effective preference | V2 probe | Eligible data-plane routing |
|---|---|---|
| unset / `AUTO` | yes | Gateway V2 when available; otherwise Gateway V1 |
| `false` | no | Gateway V1; Gateway V2 is disabled |
| `true` | no | Gateway V2 is eligible without probing; service-side routing still applies |

> **Default does not mean `true`.** Leaving the value unset activates SDK
> 4.82.0's safe AUTO behavior. Explicit `true` bypasses the probe and fallback.

### Default

When `gatewayV2Enable` is absent, the wrapper writes no SDK property. Azure
Cosmos SDK 4.82.0 uses a tri-state `null` value to represent this condition.
For Gateway HTTP/2 clients, the SDK probes Gateway V2 connectivity and routes
through Gateway V2 only on an affirmative verdict. A failed or unavailable
probe leaves routing on Gateway V1.

This is intentionally different from setting `true`: explicit `true` bypasses
the connectivity probe but does not override service-side routing, including
automatic Gateway V1 routing for Integrated Cache.

### Explicit override

The wrapper accepts only `true` or `false`, case-insensitive:

- `true` writes `COSMOS.THINCLIENT_ENABLED=true` and bypasses the probe;
- `false` writes `COSMOS.THINCLIENT_ENABLED=false`;
- any other value fails construction.

### Global-state boundary

The Azure SDK exposes Gateway V2 selection through native properties named for
the thin client, not through `CosmosClientBuilder`. Therefore:

- a pre-existing non-empty SDK setting is never overwritten;
- the wrapper's check-and-set is synchronized;
- the SDK reads the value lazily for requests rather than snapshotting it per
  client;
- an unset client logs when a global value has already replaced AUTO behavior;
- all Cosmos clients in one JVM must use a consistent preference.

This global behavior is documented as a provider constraint. Process isolation
is required when clients need different Gateway V2 preferences.
All wrapper-owned values are validated before publication. If native
`buildClient()` later fails, the published property remains process-wide and
governs subsequent Cosmos clients.

The system property and environment variable are native Azure SDK controls and
are expected to contain `true` or `false`. The wrapper strictly validates only
its `gatewayV2Enable` connection property; native-setting parsing remains
owned by the SDK. Invalid native values are reported in the INFO snapshot as
SDK-treated AUTO and do not trigger the conflicting "AUTO inactive" warning.

## Query-plan Routing

SDK 4.82.0 also has
`COSMOS.THINCLIENT_QUERY_PLAN_ENABLED`, a narrower kill switch. The query-plan
path first evaluates the main thin-client eligibility decision. Consequently,
the main `COSMOS.THINCLIENT_ENABLED=false` opt-out prevents both data-plane and
query-plan Gateway V2 routing. The wrapper does not duplicate the narrower
switch.

## Compatibility and Migration

This is a deliberate pre-release configuration and public-constant cleanup.

| Previous input | New result | Migration |
|---|---|---|
| no `connectionMode` | Fixed Gateway | none |
| `connectionMode=gateway` | construction failure | remove key |
| `connectionMode=direct` | construction failure | construct and use an Azure SDK client directly if Direct is essential |
| no HTTP/2 key | Fixed HTTP/2 | none |
| `gatewayHttp2Enabled=true/false` | construction failure | remove key |
| no Gateway V2 key | auto-probe/fallback | recommended default |
| `thinClientEnabled=<value>` | construction failure | rename key to `gatewayV2Enable` |
| `gatewayV2Enable=false` | hard opt-out | unchanged operational intent |
| Integrated Cache enabled + any Gateway V2 preference | Automatic Gateway V1 cache path | use a Dedicated Gateway endpoint and eligible consistency; no V2 opt-out required |

Failing even fixed-equivalent stale values ensures deployments do not retain
configuration that appears to control behavior but no longer does.

## Portability Analysis

The change is isolated to provider connection configuration. It does not alter
portable CRUD, query, paging, diagnostics, capability, or error contracts.
DynamoDB and Spanner have no corresponding provider setting to add.

The Dedicated Gateway profile is explicit Cosmos-native deployment guidance,
not a portable cache capability. A future portable cache feature must add
cross-provider capability gating and configurable staleness.

The default favors portability operationally: applications still switch
providers through configuration only, while the Cosmos adapter owns its safe
native transport policy. Users needing Direct/RNTBD must construct and use the
Azure SDK directly and accept provider-specific code.

## Failure Handling

| Failure | Surface | Network I/O |
|---|---|---:|
| missing/blank endpoint | `IllegalArgumentException` | no |
| removed or renamed transport key | `IllegalArgumentException` with migration guidance | no |
| malformed Gateway V2 value | `IllegalArgumentException` with valid values | no |
| Gateway V2 probe failure | automatic Gateway V1 fallback | probe only |
| explicit probe bypass unsupported | native SDK connectivity failure | yes |
| Integrated Cache enabled with V2 enabled or eligible | automatic service-side Gateway V1 cache routing | yes |

The wrapper does not catch or success-shape native failures from an explicit
probe bypass.

## Testing Strategy

- Construction test captures `GatewayConnectionConfig`, asserts HTTP/2 is
  enabled, and verifies Direct mode is never called.
- A 2x3 matrix covers standard and Dedicated endpoints with Gateway V2 unset,
  `true`, and `false`, including property publication, the effective-preference
  INFO snapshot, and absence of an endpoint-based Integrated Cache warning.
- Logging assertions distinguish the construction snapshot from actual
  per-request routing.
- Precedence test verifies an existing native SDK property is not overwritten.
- Validation tests cover malformed Boolean input, removed keys, and the renamed
  draft key.
- Invalid native value coverage verifies SDK-treated AUTO diagnostics are not
  contradictory.
- Environment-sensitive property-publication tests skip when the native
  environment override is present and lock JVM system properties.
- Pre-publication consistency validation has focused unit coverage.
- Existing builder mocks use the `gatewayMode(GatewayConnectionConfig)`
  overload.
- Cosmos emulator conformance confirms the fixed transport remains compatible
  with the emulator.

## Rollout and Rollback

Rollout is a normal provider-module release with Azure Cosmos SDK 4.82.0.
Release notes and configuration docs call out the removed/renamed keys,
transport snapshot, Integrated Cache service routing, and JVM-wide semantics.

Rollback requires reverting both the provider implementation and the Cosmos
SDK version. Operators can mitigate Gateway V2 independently by setting
`gatewayV2Enable=false`; Gateway mode and HTTP/2 are intentional fixed
policy and have no runtime rollback switch.

## Dependency with Performance PRs

PR #101 is the base:

```text
#101 Cosmos Gateway defaults
  -> #98 performance harness and pool tuning
      -> #99 performance tests
```

PR #98 may retain pool-size and write-response controls, but it must not
reintroduce connection-mode or HTTP/2 enablement switches.

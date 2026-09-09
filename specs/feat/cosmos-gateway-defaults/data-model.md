# Data Model: Cosmos Gateway Transport Defaults

This feature does not add persisted application data. Its model is the
construction-time configuration and the effective native transport policy.

## FixedTransportPolicy

Represents the invariant transport applied to every Cosmos client.

| Field | Type | Value | Validation |
|---|---|---|---|
| `connectionMode` | enum | `GATEWAY` | Fixed; no user input accepted |
| `http2Enabled` | Boolean | `true` | Fixed; no user input accepted |

Relationships:

- One `FixedTransportPolicy` is applied to every Cosmos provider client.
- It owns one Azure `GatewayConnectionConfig`.
- The gateway configuration owns one Azure `Http2ConnectionConfig`.
- HTTP/2 remains enabled for Gateway V2, Integrated Cache, and newer supported
  Cosmos features.

## GatewayDeploymentProfile

Represents the supported Cosmos-native deployment topology. Integrated Cache is
enabled at the account level rather than through a new SDK configuration key.

| Profile | Endpoint | Gateway V2 state | Intended path |
|---|---|---|---|
| `STANDARD_GATEWAY` | Standard account endpoint | Any valid state | Gateway V2 when eligible, or Gateway V1 when disabled |
| `DEDICATED_CACHE` | Dedicated Gateway `sqlx` endpoint | Any valid state | Account-level Integrated Cache automatically uses Gateway V1 |

The `DEDICATED_CACHE` profile requires HTTP/2 but does not require a Gateway V2
opt-out. Examples use `EVENTUAL` consistency. Cache staleness remains the
Dedicated Gateway service default and is not represented in this feature's
model. This is provider-native deployment guidance, not a portable cache
capability.

## GatewayV2Preference

Represents the requested Gateway V2 routing behavior.

| State | Connection value | SDK property written | Routing behavior |
|---|---|---|---|
| `AUTO` | absent | none | SDK connectivity probe; V2 on success, V1 otherwise |
| `ENABLED` | `true` | `true` | Probe bypassed; service-side routing still applies |
| `DISABLED` | `false` | `false` | Hard opt-out; no probe |

Validation:

- Matching is case-insensitive.
- Only `true` and `false` are accepted when the key is present.
- Any other value fails client construction before network I/O.

## GatewayV2ConfigurationSource

Represents the source of the effective process-wide preference.

Precedence:

1. Non-empty JVM system property `COSMOS.THINCLIENT_ENABLED`
2. Non-empty environment variable `COSMOS_THINCLIENT_ENABLED`
3. `gatewayV2Enable` Multicloud DB connection property
4. Unset SDK default (`AUTO`)

Relationships and constraints:

- A connection property is translated into the JVM system property because the
  Azure SDK has no per-client API.
- The translation is synchronized within the Cosmos provider class.
- Once a non-empty process value exists, later client construction does not
  overwrite it.
- All Cosmos clients in one JVM must use a compatible preference.
- The Azure SDK reads the setting lazily, so publishing a later value can alter
  routing for an existing AUTO client.
- Clients that require different Gateway V2 preferences use separate JVM
  processes.
- Invalid non-empty native values remain authoritative as the configuration
  source but are warned on and treated as `AUTO` by the Azure SDK.

## TransportConfigurationSnapshot

Represents the INFO record emitted after successful native client construction.

| Field | Value |
|---|---|
| `connectionMode` | `GATEWAY` |
| `http2Enabled` | `true` |
| `gatewayV2Preference` | `AUTO`, `ENABLED`, or `DISABLED` from the effective native value |
| `routingQualification` | Actual routing is selected per request; Integrated Cache uses Gateway V1 |

This snapshot is not a negotiated route and can become stale if the lazily read
native Gateway V2 value changes after construction.

## RemovedTransportSetting

Represents a stale configuration key that is no longer supported.

| Key | Former purpose | Construction result |
|---|---|---|
| `connectionMode` | Select Gateway or Direct | Reject: Gateway is fixed |
| `gatewayHttp2Enabled` | Enable or disable Gateway HTTP/2 | Reject: HTTP/2 is fixed |
| `thinClientEnabled` | Draft name for Gateway V2 override | Reject: renamed to `gatewayV2Enable` |

## State Transitions

```text
raw connection config
        |
        +-- removed key present --------> REJECTED
        |
        +-- Gateway V2 value malformed -> REJECTED
        |
        +-- operator override present --> OPERATOR_CONTROLLED
        |
        +-- gatewayV2=true -------------> ENABLED
        |
        +-- gatewayV2=false ------------> DISABLED
        |
        `-- gatewayV2 absent -----------> AUTO

AUTO -- probe success ------------------> GATEWAY_V2
AUTO -- probe failure/no verdict -------> GATEWAY_V1
ENABLED + eligible service topology ----> GATEWAY_V2
INTEGRATED_CACHE + any preference ------> GATEWAY_V1_CACHE
```

The fixed Gateway/HTTP2 policy applies in every non-rejected state. The actual
per-request route is not a construction-time state.

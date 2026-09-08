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

## GatewayDeploymentProfile

Represents the supported Cosmos-native deployment topology. It is derived from
the endpoint and Gateway V2 preference rather than a new configuration key.

| Profile | Endpoint | Gateway V2 state | Intended path |
|---|---|---|---|
| `STANDARD_GATEWAY` | Standard account endpoint | Any valid state | Gateway V2 when eligible, or Gateway V1 when disabled |
| `DEDICATED_CACHE` | Dedicated Gateway `sqlx` endpoint | `DISABLED` | Gateway V1 and Integrated Cache |

Using Gateway V2 with Integrated Cache is not recommended. A Dedicated Gateway
endpoint in `AUTO` or `FORCE_ENABLED` state is accepted but emits a warning;
the recommended `DEDICATED_CACHE` profile uses `DISABLED` and `EVENTUAL`
consistency. Cache staleness remains the Dedicated Gateway service default and
is not represented in this feature's model. This is provider-native deployment
guidance, not a portable cache capability.

## GatewayV2Preference

Represents the requested Gateway V2 routing behavior.

| State | Connection value | SDK property written | Routing behavior |
|---|---|---|---|
| `AUTO` | absent | none | SDK connectivity probe; V2 on success, V1 otherwise |
| `FORCE_ENABLED` | `true` | `true` | Hard opt-in; probe bypassed |
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
        +-- gatewayV2=true -------------> FORCE_ENABLED
        |
        +-- gatewayV2=false ------------> DISABLED
        |
        `-- gatewayV2 absent -----------> AUTO

AUTO -- probe success ------------------> GATEWAY_V2
AUTO -- probe failure/no verdict -------> GATEWAY_V1
DISABLED + dedicated sqlx endpoint ------> DEDICATED_CACHE
```

The fixed Gateway/HTTP2 policy applies in every non-rejected state.

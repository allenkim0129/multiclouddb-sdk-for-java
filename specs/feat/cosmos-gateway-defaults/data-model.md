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
- HTTP/2 remains enabled for Gateway V2 and newer supported Cosmos features.

## AutomaticGatewayRouting

Represents service- and SDK-owned Gateway version selection. It is not a
Multicloud DB configuration entity.

| Input/state | Owner | Effect |
|---|---|---|
| Gateway V2 endpoint advertisement | Cosmos account response | Determines whether V2 can be probed |
| Connectivity probe result | Azure Cosmos DB SDK | Enables V2 eligibility only after success |
| Request eligibility | Azure Cosmos DB SDK/service | Eligible data-plane operations may use V2; other operations use V1 |
| Missing endpoint or unsuccessful probe | Azure Cosmos DB SDK | Routing remains on V1 |

Constraints:

- Multicloud DB neither reads nor writes internal thin-client settings.
- No connection property can force Gateway V1 or Gateway V2.
- Gateway mode and HTTP/2 remain fixed regardless of the selected Gateway
  version.
- The actual route is evaluated after construction and can differ by request.

## TransportConfigurationSnapshot

Represents the INFO record emitted after successful native client construction.

| Field | Value |
|---|---|
| `connectionMode` | `GATEWAY` |
| `http2Enabled` | `true` |
| `gatewayVersionSelection` | `AUTOMATIC` (account and SDK owned) |
| `routingQualification` | Actual Gateway version is selected after construction and per request |

This snapshot is not a negotiated route.

## RemovedTransportSetting

Represents a stale configuration key that is no longer supported.

| Key | Former purpose | Construction result |
|---|---|---|
| `connectionMode` | Select Gateway or Direct | Reject: Gateway is fixed |
| `gatewayHttp2Enabled` | Enable or disable Gateway HTTP/2 | Reject: HTTP/2 is fixed |
| `gatewayV2Enable` | Draft Gateway version override | Reject: selection is automatic |
| `thinClientEnabled` | Earlier draft Gateway version override | Reject: selection is automatic |

## State Transitions

```text
raw connection config
        |
        +-- removed key present --------> REJECTED
        |
        `-- valid config ---------------> FIXED_GATEWAY_HTTP2

FIXED_GATEWAY_HTTP2 -- account has no V2 endpoint --> GATEWAY_V1
FIXED_GATEWAY_HTTP2 -- account advertises V2 ------> SDK_PROBE
SDK_PROBE -- unsuccessful -------------------------> GATEWAY_V1
SDK_PROBE -- successful + eligible request --------> GATEWAY_V2
SDK_PROBE -- successful + ineligible request ------> GATEWAY_V1
```

The fixed Gateway/HTTP2 policy applies in every non-rejected state. Gateway
version is service/SDK state, not a user configuration or construction-time
state.

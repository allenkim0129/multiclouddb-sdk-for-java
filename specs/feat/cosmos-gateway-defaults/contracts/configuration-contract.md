# Cosmos Transport Configuration Contract

This feature changes a Java library construction contract, not a REST or
GraphQL endpoint. This document is the external configuration contract.

## Supported input

| Property | Required | Values | Default |
|---|---:|---|---|
| `multiclouddb.connection.endpoint` | yes | Non-blank Cosmos account URI | none |
| `multiclouddb.connection.key` | no | Cosmos account key | Azure identity |
| `multiclouddb.connection.tenantId` | no | Azure tenant ID | credential-chain default |
| `multiclouddb.connection.gatewayV2Enable` | no | `true` or `false`, case-insensitive | unset / SDK auto-probe |

## Fixed output contract

For every valid Cosmos client construction:

```text
CosmosClientBuilder
  .gatewayMode(
      GatewayConnectionConfig
        .http2ConnectionConfig(
            Http2ConnectionConfig.enabled = true))
```

`directMode(...)` is never selected.

## Deployment profiles

| Profile | Endpoint | Gateway V2 preference | Cache behavior |
|---|---|---|---|
| Standard Gateway (V2 default) | Standard `documents.azure.com` endpoint | Unset for probe/fallback; `true` to force V2; `false` to use V1 | No Integrated Cache |
| Dedicated Gateway | Provisioned `sqlx.cosmos.azure.com` endpoint | `false` | Provider-native Integrated Cache for eligible reads |

Using Gateway V2 with Integrated Cache is not recommended. The recommended
Dedicated Gateway profile therefore sets `gatewayV2Enable=false` and uses
`EVENTUAL` consistency in Multicloud DB examples for forward compatibility
with the planned portable cache contract. The service-side default cache
staleness applies because this release does not expose
`MaxIntegratedCacheStaleness`.

The Dedicated Gateway profile is provider-native deployment guidance, not a
portable cache capability. Clients that require different Gateway V2
preferences use separate JVM processes because the native thin-client setting
is global and read lazily.

## Gateway V2 precedence contract

| SDK system property | SDK environment variable | Connection property | Effective behavior |
|---|---|---|---|
| non-empty | any | any | System property wins |
| empty/absent | non-empty | any | Environment variable wins |
| empty/absent | empty/absent | `true` | Hard opt-in |
| empty/absent | empty/absent | `false` | Hard opt-out |
| empty/absent | empty/absent | absent | SDK probe and fallback |

The connection property is mapped to the SDK system property only when no
native system-property or environment value already exists. Conflicting values
are not overwritten. An unset connection value also logs when a global value
has already disabled the documented AUTO behavior.

A recognized Dedicated Gateway `sqlx` endpoint logs an actionable warning when
Gateway V2 is enabled or eligible. The warning recommends `gatewayV2Enable=false`
to keep requests on the Integrated Cache path.

## Rejected input

| Property | Result |
|---|---|
| `multiclouddb.connection.connectionMode` | `IllegalArgumentException`: Gateway mode is always used |
| `multiclouddb.connection.gatewayHttp2Enabled` | `IllegalArgumentException`: Gateway HTTP/2 is always enabled |
| `multiclouddb.connection.gatewayV2Enable=<other>` | `IllegalArgumentException`: value must be `true` or `false` |
| `multiclouddb.connection.thinClientEnabled` | `IllegalArgumentException`: renamed to `gatewayV2Enable` |

All validation occurs before native client construction and before network
I/O.

## Compatibility

- Provider-neutral interfaces and operation semantics are unchanged.
- The public Cosmos constants for connection-mode selection are removed.
- Existing pre-release configuration containing a removed or renamed key must be updated.
- `gatewayV2Enable` is process-wide due to the native SDK contract, even
  though it is accepted through the standard connection-property map.
- Integrated Cache support here is limited to a Cosmos-native endpoint and
  routing profile; no portable cache capability or configurable staleness is
  introduced.
- The native SDK reads the JVM-wide value controlling Gateway V2 lazily per request.

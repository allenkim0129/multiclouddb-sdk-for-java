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

`directMode(...)` is never selected. HTTP/2 cannot be disabled because Gateway
V2, Integrated Cache, and newer Cosmos features supported by Multicloud DB
require it.

## Deployment profiles

| Profile | Endpoint | Gateway V2 preference | Cache behavior |
|---|---|---|---|
| Standard Gateway (V2 default) | Standard `documents.azure.com` endpoint | Unset for probe/fallback; `true` to enable without probing; `false` to disable V2 | No Integrated Cache |
| Dedicated Gateway | Provisioned `sqlx.cosmos.azure.com` endpoint | Any valid preference | Account-level Integrated Cache automatically uses Gateway V1 for eligible reads |

Integrated Cache is enabled at the account level by provisioning paid
Dedicated Gateway compute. It requires HTTP/2 and automatically routes eligible
cache requests through Gateway V1, even when Gateway V2 is enabled. The
Dedicated Gateway example uses `EVENTUAL` consistency but does not require
`gatewayV2Enable=false`. The service-side default cache staleness applies
because this release does not expose `MaxIntegratedCacheStaleness`.

The Dedicated Gateway profile is provider-native deployment guidance, not a
portable cache capability. Clients that require different Gateway V2
preferences use separate JVM processes because the native thin-client setting
is global and read lazily; Integrated Cache itself does not require process
isolation from Gateway V2 clients.

## Gateway V2 precedence contract

| SDK system property | SDK environment variable | Connection property | Effective behavior |
|---|---|---|---|
| valid `true`/`false` | any | any | System property wins |
| other non-empty | any | any | System property wins as source; SDK warns and treats it as unset/AUTO |
| empty/absent | valid `true`/`false` | any | Environment variable wins |
| empty/absent | other non-empty | any | Environment variable wins as source; SDK warns and treats it as unset/AUTO |
| empty/absent | empty/absent | `true` | Enable without connectivity probe; service routing still applies |
| empty/absent | empty/absent | `false` | Hard opt-out |
| empty/absent | empty/absent | absent | SDK probe and fallback |

The connection property is mapped to the SDK system property only when no
native system-property or environment value already exists. Invalid native
values remain SDK-owned: the SDK warns and treats them as unset/AUTO, and the
wrapper does not also claim that AUTO is inactive. Conflicting valid values
are not overwritten. An unset connection value also logs when a global value
has already disabled the documented AUTO behavior.

After successful native client construction, the provider emits one INFO
snapshot containing Gateway mode, HTTP/2 enablement, and the effective Gateway
V2 preference. The snapshot states that actual routing is selected per request
and that Integrated Cache uses Gateway V1. It does not claim a negotiated
Gateway version because Gateway V2 eligibility and routing are evaluated after
construction and per request.

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
- Integrated Cache support here is limited to Cosmos-native account
  configuration and routing; no portable cache capability or configurable
  staleness is introduced, and no Gateway V2 opt-out is required for cache
  routing.
- The native SDK reads the JVM-wide value controlling Gateway V2 lazily per request.

# Cosmos Transport Configuration Contract

This feature changes a Java library construction contract, not a REST or
GraphQL endpoint. This document is the external configuration contract.

## Supported input

| Property | Required | Values | Default |
|---|---:|---|---|
| `multiclouddb.connection.endpoint` | yes | Non-blank Cosmos account URI | none |
| `multiclouddb.connection.key` | no | Cosmos account key | Azure identity |
| `multiclouddb.connection.tenantId` | no | Azure tenant ID | credential-chain default |

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
V2 and newer Cosmos features supported by Multicloud DB require it.

## Automatic Gateway version contract

Multicloud DB exposes no Gateway V1/V2 input. Azure Cosmos DB advertises
Gateway V2 endpoints through the account response, and SDK 4.82 performs its
own connectivity probe when Gateway mode and HTTP/2 are enabled. A successful
probe makes eligible data-plane requests candidates for Gateway V2. Missing
endpoints, an unsuccessful probe, metadata requests, and other ineligible
operations remain on Gateway V1.

The wrapper does not read or write internal Azure SDK thin-client properties.
Account configuration and the native SDK own Gateway version selection.

After successful native client construction, the provider emits one INFO
snapshot containing fixed Gateway mode, HTTP/2 enablement, and automatic
Gateway version selection. It does not claim a negotiated version because
eligibility and routing are evaluated after construction and per request.

## Rejected input

| Property | Result |
|---|---|
| `multiclouddb.connection.connectionMode` | `IllegalArgumentException`: Gateway mode is always used |
| `multiclouddb.connection.gatewayHttp2Enabled` | `IllegalArgumentException`: Gateway HTTP/2 is always enabled |
| `multiclouddb.connection.gatewayV2Enable` | `IllegalArgumentException`: Gateway version is selected automatically |
| `multiclouddb.connection.thinClientEnabled` | `IllegalArgumentException`: Gateway version is selected automatically |

All validation occurs before native client construction and before network
I/O.

## Compatibility

- Provider-neutral interfaces and operation semantics are unchanged.
- The public Cosmos constants for connection-mode selection are removed.
- Existing pre-release configuration containing a removed transport key must
  be updated.
- No HTTP-version or Gateway-version control is part of the public contract.

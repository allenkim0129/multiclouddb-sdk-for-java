# Quickstart: Cosmos Gateway Transport Defaults

## Default configuration

No transport keys are required:

```properties
multiclouddb.provider=cosmos
multiclouddb.connection.endpoint=https://account.documents.azure.com:443/
multiclouddb.connection.key=<account-key>
```

The provider always constructs a Gateway client with HTTP/2 enabled. There is
no connection-mode, HTTP-version, or Gateway-version selector. The Cosmos
account advertises Gateway V2 availability, and Azure Cosmos SDK 4.82.0 probes
an advertised endpoint before routing eligible requests through V2. Without
an advertised endpoint or successful probe, requests remain on Gateway V1.

## Programmatic configuration

```java
MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
    .provider(ProviderId.COSMOS)
    .connection("endpoint", "https://account.documents.azure.com:443/")
    .connection("key", accountKey)
    .build();

MulticloudDbClient client = MulticloudDbClientFactory.create(config);
```

## Migration

Remove all pre-release transport-selection keys from existing configuration:

```properties
multiclouddb.connection.connectionMode=gateway
multiclouddb.connection.gatewayHttp2Enabled=true
multiclouddb.connection.gatewayV2Enable=true
multiclouddb.connection.thinClientEnabled=true
```

These keys are no longer switches. Their presence fails client construction so
stale configuration is not silently ignored. Gateway mode and HTTP/2 are fixed;
Gateway V1/V2 routing is selected automatically from account configuration by
Azure Cosmos DB and its SDK.

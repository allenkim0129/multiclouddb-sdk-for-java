# Quickstart: Cosmos Gateway Transport Defaults

## Default configuration

No transport keys are required:

```properties
multiclouddb.provider=cosmos
multiclouddb.connection.endpoint=https://account.documents.azure.com:443/
multiclouddb.connection.key=<account-key>
```

The provider always constructs a Gateway client with HTTP/2 enabled. HTTP/2 is
required for Gateway V2, Integrated Cache, and newer Cosmos features. Azure
Cosmos SDK 4.82.0 probes Gateway V2 connectivity and uses Gateway V2 after a
successful probe; otherwise it remains on Gateway V1.

> **Gateway V2 requires nothing extra to install or deploy.** Azure SDK
> internals also call it the thin-client routing path. See the
> [request-path diagram](design.md#gateway-v2-terminology) and
> [selection flow](design.md#gateway-v2-selection).

## Dedicated Gateway with Integrated Cache

Integrated Cache is an account-level option enabled by provisioning paid
Dedicated Gateway compute. It requires HTTP/2 and automatically routes eligible
cache requests through Gateway V1, even when Gateway V2 is enabled:

```properties
multiclouddb.provider=cosmos
multiclouddb.connection.endpoint=https://account.sqlx.cosmos.azure.com:443/
multiclouddb.connection.key=<account-key>
multiclouddb.connection.consistencyLevel=EVENTUAL
```

Only eligible cache-hit point reads and queries can return with 0 RU. This
Cosmos-native profile uses the Dedicated Gateway service-side staleness default;
custom cache staleness is not configurable through this SDK release. No
`gatewayV2Enable=false` setting or separate Gateway V2 process is required.

See [Configure the Integrated Cache](https://learn.microsoft.com/azure/cosmos-db/how-to-configure-integrated-cache)
for provisioning, networking, consistency, and cache-hit verification.

## Opt out of Gateway V2

```properties
multiclouddb.connection.gatewayV2Enable=false
```

This is a hard process-wide opt-out. Gateway mode and HTTP/2 remain enabled.

## Enable Gateway V2 without probing

```properties
multiclouddb.connection.gatewayV2Enable=true
```

This is a process-wide preference that bypasses the connectivity probe. Use it
only after verifying Gateway V2 availability for the account, region, and
network path. Cosmos DB still controls service-side routing, including automatic
Gateway V1 routing for Integrated Cache.

## Operator-level configuration

The native Azure SDK settings take precedence over the Multicloud DB
connection property:

```powershell
java -DCOSMOS.THINCLIENT_ENABLED=false -jar app.jar
```

or:

```text
COSMOS_THINCLIENT_ENABLED=false
```

Because the Azure SDK exposes and reads this selection globally, all Cosmos
clients in one JVM must use the same effective value. Use separate processes
only when clients require different Gateway V2 preferences.

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

Remove these fixed transport keys from existing configuration:

```properties
multiclouddb.connection.connectionMode=gateway
multiclouddb.connection.gatewayHttp2Enabled=true
```

They are no longer switches. Their presence fails client construction with an
actionable message so stale configuration is not silently ignored.

The earlier draft name also fails with migration guidance; rename it:

```properties
multiclouddb.connection.thinClientEnabled=false
# becomes: multiclouddb.connection.gatewayV2Enable=false
```

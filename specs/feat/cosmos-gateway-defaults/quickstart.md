# Quickstart: Cosmos Gateway Transport Defaults

## Default configuration

No transport keys are required:

```properties
multiclouddb.provider=cosmos
multiclouddb.connection.endpoint=https://account.documents.azure.com:443/
multiclouddb.connection.key=<account-key>
```

The provider always constructs a Gateway client with HTTP/2 enabled. Azure
Cosmos SDK 4.82.0 then probes Gateway V2 connectivity. It uses Gateway V2 after
a successful probe and otherwise remains on Gateway V1.

> **Gateway V2 requires nothing extra to install or deploy.** Azure SDK
> internals also call it the thin-client routing path. See the
> [request-path diagram](design.md#gateway-v2-terminology) and
> [selection flow](design.md#gateway-v2-selection).

## Dedicated Gateway with Integrated Cache

Cosmos team guidance does not recommend Gateway V2 with Integrated Cache.
Provision Dedicated Gateway compute, use its `sqlx` endpoint, and disable
Gateway V2 process-wide:

```properties
multiclouddb.provider=cosmos
multiclouddb.connection.endpoint=https://account.sqlx.cosmos.azure.com:443/
multiclouddb.connection.key=<account-key>
multiclouddb.connection.gatewayV2Enable=false
multiclouddb.connection.consistencyLevel=EVENTUAL
```

Only eligible cache-hit point reads and queries can return with 0 RU. This
Cosmos-native profile uses the Dedicated Gateway service-side staleness default;
custom cache staleness is not configurable through this SDK release. Use a
separate JVM process if another Cosmos client needs Gateway V2.

See [Configure the Integrated Cache](https://learn.microsoft.com/azure/cosmos-db/how-to-configure-integrated-cache)
for provisioning, networking, consistency, and cache-hit verification.

## Opt out of Gateway V2

```properties
multiclouddb.connection.gatewayV2Enable=false
```

This is a hard process-wide opt-out. Gateway mode and HTTP/2 remain enabled.

## Force Gateway V2

```properties
multiclouddb.connection.gatewayV2Enable=true
```

This is a hard process-wide opt-in and bypasses the connectivity probe. Use it
only after verifying Gateway V2 availability for the account, region, and
network path.

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
clients in one JVM must use the same effective value. Run Gateway V2 and
Dedicated Gateway cache profiles in separate processes.

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

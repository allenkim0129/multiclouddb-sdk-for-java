# Changelog

All notable changes to the Multicloud DB SDK modules.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and all modules adhere to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## multiclouddb-api

### [Unreleased]

**Added (Change feed — User Story 8):**

- New `com.multiclouddb.api.changefeed` package with
  `MulticloudDbClient.readChanges` / `listPhysicalPartitions`,
  `ChangeFeedRequest` / `ChangeFeedPage` / `ChangeEvent`, and sealed
  `FeedScope` (Entire / Physical / Logical) and `StartPosition`
  (Beginning / Now / AtTime / FromContinuationToken) types.
- New capability tokens: `change_feed`, `change_feed_point_in_time`,
  `change_feed_logical_partition_scope` — introspectable via
  `client.capabilities()`. Calls fail fast with `UNSUPPORTED_CAPABILITY`
  when the active provider does not advertise the required capability.
- New SPI hooks `MulticloudDbProviderClient.readChanges` and
  `listPhysicalPartitions` with `UNSUPPORTED_CAPABILITY` defaults.

### [0.1.0-beta.1] - 2026-04-23

**Added:**

- `MulticloudDbClientConfig.Builder.userAgentSuffix(String)` - optional
  caller-supplied token appended to the SDK user-agent header sent by all
  provider clients.
- `MulticloudDbClientConfig.userAgentSuffix()` - accessor returning the
  configured suffix, or `null` if unset.
- `com.multiclouddb.spi.SdkUserAgent` - SPI helper that builds the canonical
  `multiclouddb-sdk-java/<version>` user-agent token.
- `MulticloudDbClient` - synchronous, provider-agnostic interface for CRUD,
  query, and schema provisioning
- `MulticloudDbClientFactory` - discovers provider adapters via `ServiceLoader`
- `MulticloudDbClientConfig` - immutable builder-pattern configuration
- `QueryRequest` - portable expression or native expression passthrough with
  named parameters, pagination, partition key scoping, limit, and orderBy
- `MulticloudDbKey` - portable `(partitionKey, sortKey)` identity
- `ResourceAddress` - `(database, collection)` targeting
- Portable expression parser, validator, and translator SPI
- `CapabilitySet` - runtime capability introspection
- `MulticloudDbException` - structured error model with portable categories
- `OperationDiagnostics` - latency, request charge, request ID
- `DocumentMetadata` - last modified, TTL expiry, version/ETag
- Document size enforcement (399 KB limit)

**Validation:**

- `userAgentSuffix(String)` rejects values longer than 256 characters and
  non-printable US-ASCII, protecting against header injection.

---

## multiclouddb-provider-cosmos

### [Unreleased]

**Added (Change feed — User Story 8):**

- `CosmosProviderClient.readChanges` / `listPhysicalPartitions` via
  `CosmosAsyncContainer.queryChangeFeed` with `FeedRange`. Capabilities:
  `change_feed`, `change_feed_point_in_time`,
  `change_feed_logical_partition_scope` — all supported.
- **Provisioning:** containers must be created with the
  `AllVersionsAndDeletes` mode for distinct CREATE / UPDATE / DELETE
  events; `LatestVersion` containers emit only the latest snapshot and
  never surface DELETE events.

### [0.1.0-beta.1] - 2026-04-23

**Added:**

- User-Agent header stamping with `multiclouddb-sdk-java/<version>` token
  and optional user-configured suffix.
- `CosmosProviderAdapter` - SPI entry point for Cosmos DB
- `CosmosProviderClient` - full implementation backed by Azure Cosmos DB Java SDK v4
- Master-key and Azure Identity (Entra ID) authentication
- Gateway and Direct connection modes
- Full CRUD with automatic field injection (`id`, `partitionKey`)
- Portable expression translation to Cosmos SQL
- Native SQL passthrough
- Cross-partition query support (capability-gated)
- Schema provisioning (database + container creation)

---

## multiclouddb-provider-dynamo

### [Unreleased]

**Added (Change feed — User Story 8):**

- `DynamoProviderClient.readChanges` / `listPhysicalPartitions` via
  DynamoDB Streams + the DynamoDB Streams Adapter
  (shard-iterator-per-physical-partition). Capabilities: `change_feed`
  supported; `change_feed_point_in_time` and
  `change_feed_logical_partition_scope` **UNSUPPORTED** (Streams have no
  timestamp start; shards are physical).
- **Provisioning:** the table must have
  `StreamSpecification.StreamEnabled=true` with `StreamViewType` of
  `NEW_AND_OLD_IMAGES` (or `NEW_IMAGE`).

### [0.1.0-beta.1] - 2026-04-23

**Added:**

- User-Agent suffix support via `SdkAdvancedClientOption.USER_AGENT_SUFFIX`.
- `DynamoProviderAdapter` - SPI entry point for DynamoDB
- `DynamoProviderClient` - full implementation backed by AWS SDK for Java 2.25.16
- AWS credential authentication (access key + secret key)
- Full CRUD with `attribute_not_exists` / `attribute_exists` guards
- Portable expression translation to PartiQL
- Native PartiQL passthrough
- Schema provisioning (table creation with ACTIVE-wait)

---

## multiclouddb-provider-spanner

### [Unreleased]

**Added (Change feed — User Story 8):**

- `SpannerProviderClient.readChanges` / `listPhysicalPartitions` via
  Spanner Change Streams using the
  `READ_<stream>(start, end, partition_token, hb)` SQL TVF; parses
  `data_change_record` and `child_partitions_record`. Capabilities:
  `change_feed` and `change_feed_point_in_time` supported;
  `change_feed_logical_partition_scope` **UNSUPPORTED** (partition
  tokens are physical row ranges).
- New connection key `connection.changeStream.<collection>` (defaults to
  `<collection>_changes`).
- **Provisioning:** `CREATE CHANGE STREAM <name> FOR <table> OPTIONS
  (value_capture_type='NEW_ROW')` must be run out-of-band.
  `value_capture_type` must be `NEW_ROW` or `NEW_ROW_AND_OLD_VALUES`
  when callers pass `newItemStateMode = REQUIRE`. The Spanner emulator
  does **not** support change streams.

### [0.1.0-beta.1] - 2026-04-23

**Added:**

- User-Agent support via gax `FixedHeaderProvider`.
- `SpannerProviderAdapter` - SPI entry point for Spanner
- `SpannerProviderClient` - full implementation backed by Google Cloud Spanner 6.62.0
- GCP credential and emulator authentication
- Full CRUD with mutation-based writes
- Portable expression translation to GoogleSQL
- Native GoogleSQL passthrough
- Schema provisioning (DDL-based table creation)

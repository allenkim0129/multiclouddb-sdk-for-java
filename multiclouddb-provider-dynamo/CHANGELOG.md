# Changelog — multiclouddb-provider-dynamo

All notable changes to the `multiclouddb-provider-dynamo` module will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this module adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added — Change-Feed support

- Change-feed reader backed by DynamoDB Streams (`DescribeStream`,
  `GetShardIterator`, `GetRecords`). `listCursors` returns one cursor per
  open shard at the live tip; `readChanges` drains one shard's next page per
  call and absorbs shard splits/closes by re-describing the stream and
  emitting child shards in the next cursor.
- Continuation sentinels (`@@TRIM_HORIZON`, `@@LATEST`) preserve the correct
  `ShardIteratorType` on resume after an empty page.
- `TrimmedDataAccessException` (records older than the fixed 24-hour Streams
  retention) is mapped to `CursorExpiredException` with
  `reason=PROVIDER_TRIMMED`.
- Provisioning requirement: table must have
  `StreamSpecification(NEW_AND_OLD_IMAGES)` enabled — `listCursors` returns
  `UNSUPPORTED_CAPABILITY` with `reason=stream_not_enabled` otherwise. The
  24-hour Streams retention naturally matches the portable client-side
  baseline.
- AWS SDK v2 (2.34.x) ships the Streams client classes inside the main
  `dynamodb` artifact at `software.amazon.awssdk.services.dynamodb.streams.*`;
  no separate `dynamodbstreams` dependency is required.

### Documentation

- **`delete()` of a missing key remains a silent no-op (idempotent).** The
  Dynamo provider issues an unconditional `DeleteItem`, so a delete of a
  key that does not exist is silently ignored — matching the LCD behaviour
  of Cosmos (404 swallowed) and Spanner (`Mutation.delete` is idempotent
  natively). No `attribute_exists` guard is added, so deletes do not pay
  the conditional-write WCU surcharge. Documented in the API Javadoc on
  `MulticloudDbClient.delete(...)` and in `docs/guide.md`. Callers needing to detect a
  missing key should use `read()`, which returns `null` on every provider
  when the key does not exist.

### Changed

- **`BETWEEN` translation now wraps in parentheses** (`(field BETWEEN ? AND ?)`).
  Mirrors the parenthesised form emitted by sibling translators so cross-provider
  query stitching is uniform. PartiQL parses both forms correctly, so this is
  not a correctness fix on Dynamo — purely a consistency improvement. The
  output of `TranslatedQuery.whereClause()` is now parenthesised.

### Fixed

- `now()` cursors no longer silently lose events between mint and first
  read, or between successive reads. Previously, `listCursors()` /
  `ChangeFeedCursor.now()` carried an `ANCHOR_NOW` sentinel; the iterator
  was only resolved (`GetShardIterator(LATEST)`) at the first `readChanges()`
  call, so any events written in the window between mint and first read were
  silently skipped. Likewise, a `readChanges()` that returned zero records
  kept the `ANCHOR_NOW` sentinel — the next call re-resolved LATEST and
  advanced past any events that arrived in the meantime. The reader now:
  - Eagerly resolves a LATEST iterator at `listCursors()` / `now()`-hydrate
    time and persists it in a new `@@ITER:<iterator>` continuation.
  - When a subsequent `readChanges()` returns zero records, persists the
    `nextShardIterator` returned by `GetRecords` in the same `@@ITER:`
    continuation so the next call resumes from exactly where this one left off.
  - When the first record is observed, transitions to a sequence-number
    (`AFTER_SEQUENCE_NUMBER`) continuation, which is good for the full
    24-hour stream-retention window.

  DynamoDB Streams iterators expire after ~5 minutes of inactivity; if no
  records have arrived and the iterator has expired by the next read, the
  call surfaces `CursorExpiredException` with `reason=ITERATOR_EXPIRED`
  and the caller must re-bootstrap via `listCursors()`. This caveat only
  applies to cursors that have not yet observed their first record.
- `absorbClosedShard()` now reports `hasMore=true` whenever child shards
  exist, regardless of whether the call drained events. Previously the flag
  was tied to "drained at least one event AND has children", so callers that
  observed only the child-shard transition would stop draining instead of
  picking up the children's events.
- Javadoc on `DynamoChangeFeedReader` now correctly states that `now()`
  hydrates with `LATEST` (it previously said `TRIM_HORIZON`).
- `readChanges(now())` against a table without DynamoDB Streams enabled now
  fails fast with `UNSUPPORTED_CAPABILITY` (`reason=stream_not_enabled`)
  instead of silently hydrating to an empty partition set and returning
  empty pages indefinitely. Matches the existing `listCursors` behaviour.
- Change-event payloads now preserve the full DynamoDB type system
  (`M`/`L`/`SS`/`NS`/nested attributes) by delegating to the shared
  `DynamoItemMapper.attributeMapToJsonNode(...)` mapper. The previous
  per-reader mapper fell back to `AttributeValue.toString()` for any value
  outside `S`/`N`/`BOOL`/`NUL`, which corrupted maps, lists, and number/string
  sets.

## [0.1.0-beta.1] — 2026-04-23

### Added

- Default sort-key ordering: all DynamoDB scan paths (`executeScan`,
  `executeScanWithFilter`, `queryWithTranslation`) now sort result items by sort
  key ascending within each page before returning the `QueryPage`. This matches
  the behavior of DynamoDB's native `Query` API (which sorts by range key within
  a partition) and mirrors the global sort introduced in the Cosmos provider.
  Note: sorting is per-page only — multi-page scans retain DynamoDB's token-based
  traversal order across pages. See `docs/compatibility.md` for details.

### Changed

- `SORT_KEY_ASC` comparator now handles numeric sort keys using type-aware
  comparison: `Long` pairs use `Long.compare`, `Integer` pairs use
  `Integer.compare`, and all other `Number` types (including mixed) use
  `BigDecimal` comparison to preserve DynamoDB's 38-digit numeric precision.
  Previously all numeric keys were compared via `Double.compare`, which loses
  precision for integers > 2^53.

- The DynamoDB client now stamps the outgoing `User-Agent` header with the
  canonical `multiclouddb-sdk-java/<version>` token via the AWS SDK
  `ClientOverrideConfiguration` API user-agent suffix. When
  `MulticloudDbClientConfig.Builder.userAgentSuffix(String)` is configured,
  the suffix is appended to the header.

#### Provider adapter and client

- `DynamoProviderAdapter` — SPI entry point auto-discovered via `ServiceLoader`;
  registers as `ProviderId.DYNAMO` and supplies `DynamoProviderClient` and
  `DynamoExpressionTranslator`
- `DynamoProviderClient` — full `MulticloudDbProviderClient` implementation
  backed by the AWS SDK for Java v2 DynamoDB client

#### Authentication

- **Static credentials** — when `connection.accessKeyId` and
  `connection.secretAccessKey` are provided, uses `AwsBasicCredentials` with
  `StaticCredentialsProvider`
- **Default credential chain** — when credentials are not explicitly provided,
  falls back to the AWS SDK default credential provider chain (environment
  variables, system properties, IAM roles, etc.)

#### Connection configuration

- Configurable AWS region via `connection.region` (default: `us-east-1`)
- Optional custom endpoint override via `connection.endpoint` for DynamoDB
  Local or compatible emulators

#### CRUD operations

- `create` — conditional `PutItem` with `attribute_not_exists(partitionKey)` to
  enforce uniqueness; automatically injects `partitionKey` and `sortKey`
  attributes
- `read` — `GetItem` with composite key lookup; returns `null` when item is
  missing
- `update` — conditional `PutItem` with `attribute_exists(partitionKey)`;
  `ConditionalCheckFailedException` mapped to portable `NOT_FOUND`
- `upsert` — unconditional `PutItem` (no condition expression)
- `delete` — `DeleteItem`; idempotent (missing items do not raise errors)

#### Query support

- **Smart query routing** with four execution paths:
  1. **Native PartiQL passthrough** — raw PartiQL via `ExecuteStatement` when
     `QueryRequest.nativeExpression()` is set
  2. **Partition-key scoped query** — DynamoDB `Query` with
     `KeyConditionExpression` when partition key is provided without a filter
  3. **Filtered scan** — DynamoDB `Scan` with `FilterExpression` when a
     portable or legacy expression is provided without partition key
  4. **Full table scan** — DynamoDB `Scan` when no expression or partition key
     is provided
- **Portable expression translation** — automatic translation via
  `DynamoExpressionTranslator` in the `queryWithTranslation` path

#### Expression translation (`DynamoExpressionTranslator`)

- Translates the portable AST to DynamoDB PartiQL
  `SELECT * FROM "container" WHERE ...` syntax with positional `?` parameters
- Comparison, logical, NOT, IN, BETWEEN expressions fully supported
- Portable function mapping:
  - `starts_with` → `begins_with(...)`
  - `contains` → `contains(...)`
  - `field_exists` → `field IS NOT MISSING`
  - `string_length` → `char_length(...)`
  - `collection_size` → `size(...)`

#### Item mapping (`DynamoItemMapper`)

- Bidirectional conversion between portable `Map<String, Object>` / Jackson
  `JsonNode` and DynamoDB `AttributeValue` maps
- Supports strings, numbers (integer, long, double), booleans, nulls, nested
  objects, arrays, and DynamoDB set types (`SS`, `NS`) on the read path
- Heuristic number round-tripping: decimal strings → `Double`, integer strings
  → `Int` or `Long`

#### Error mapping (`DynamoErrorMapper`)

- Maps DynamoDB exception types to portable error categories:
  - `ConditionalCheckFailedException` → `CONFLICT`
  - `ResourceNotFoundException` → `NOT_FOUND`
  - `ValidationException` → `INVALID_REQUEST`
  - `AccessDeniedException` → `AUTHORIZATION_FAILED`
  - `UnrecognizedClientException` → `AUTHENTICATION_FAILED`
  - `ProvisionedThroughputExceededException`, `ThrottlingException`,
    `RequestLimitExceeded` → `THROTTLED`
  - `ItemCollectionSizeLimitExceededException` → `PERMANENT_FAILURE`
- HTTP status code fallback mapping for unrecognized exceptions
  (`400` → `INVALID_REQUEST`, `401`/`403` → `AUTHENTICATION_FAILED`,
  `404` → `NOT_FOUND`, `5xx` → `TRANSIENT_FAILURE`)
- Retryable flag set for throttling exceptions and 5xx responses
- Captures error code, service name, and request ID in provider details

#### Pagination (`DynamoContinuationToken`)

- Encodes DynamoDB `LastEvaluatedKey` maps into opaque Base64-URL tokens
  (no padding)
- Decodes tokens back to `Map<String, AttributeValue>` for `ExclusiveStartKey`
- Supports `S` (string) and `N` (number) attribute types in key serialization
- Native PartiQL path uses DynamoDB's built-in `nextToken` for pagination

#### Provisioning

- `ensureDatabase` — no-op (DynamoDB has no database concept)
- `ensureContainer` — creates table with `partitionKey` (hash) and `sortKey`
  (range) as `String` attributes using `PAY_PER_REQUEST` billing mode; handles
  table lifecycle states (`CREATING`, `UPDATING`, `DELETING`) with waiter-based
  polling; ignores `ResourceInUseException` race conditions

#### Table naming

- Logical `ResourceAddress` mapped to physical DynamoDB table name via
  `database__collection` convention (double-underscore separator)

#### Diagnostics

- Point operation logging with request ID and consumed capacity
- Query/scan diagnostics with request ID, HTTP status code, consumed capacity
  (table + GSI/LSI breakdown), item count, duration, and has-more-pages
  indicator

#### Capabilities

- Reports 6 capabilities as supported: continuation-token paging, transactions
  (`TransactWriteItems`/`TransactGetItems`), batch operations
  (`BatchWriteItem`/`BatchGetItem`), strong consistency (for item reads),
  change feed (DynamoDB Streams), portable expression translation
- Reports 7 capabilities as unsupported: cross-partition query, native SQL
  query, LIKE operator, ORDER BY, ENDS_WITH, REGEX_MATCH, case functions

#### Dependencies

- AWS SDK for Java v2 DynamoDB (`software.amazon.awssdk:dynamodb 2.34.0`)

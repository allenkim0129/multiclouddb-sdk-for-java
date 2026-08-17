# Portable API Surface

The Multicloud DB SDK's portable API surface covers capabilities that work
identically across all three providers. The features listed below require no
runtime capability checks - they are guaranteed to work on Azure Cosmos DB,
Amazon DynamoDB, and Google Cloud Spanner. Some providers offer additional
capabilities (e.g., `CROSS_PARTITION_QUERY`, `ORDER_BY`, `LIKE`); use
`client.capabilities()` to discover what the current provider supports.

---

## What Works Everywhere

Every capability listed below is fully supported on **all** providers. There are
no asterisks, no provider-specific caveats, and no runtime checks required.

### CRUD Operations

| Operation | Description |
|-----------|-------------|
| **Create** | Insert a new document (fails if the key already exists) |
| **Read** | Point-read by partition key + sort key |
| **Update** | Replace an existing document (fails if not found) |
| **Upsert** | Create or replace - always succeeds |
| **Delete** | Remove by key (idempotent — silent on missing; use `read()` to detect a missing key, since `read()` returns `null` on every provider) |
| **Patch** | Field-level partial update of an existing document, applied atomically — see [Patch Semantics](#patch-semantics) for the nested-path caveat and cost model |

### Query - Portable Expression DSL

Write a WHERE-clause filter once. The SDK translates it to the native query
language of whichever provider is configured - Cosmos SQL, DynamoDB PartiQL,
or Spanner GoogleSQL.

| Feature | Operators / Functions | Example |
|---------|----------------------|---------|
| Comparison | `=`, `!=`, `<`, `>`, `<=`, `>=` | `status = 'active'` |
| Logical | `AND`, `OR`, `NOT` | `age > 18 AND active = true` |
| String functions | `STARTS_WITH`, `CONTAINS` | `STARTS_WITH(name, 'A')` |
| Field introspection | `FIELD_EXISTS` | `FIELD_EXISTS(metadata)` |
| Length functions | `STRING_LENGTH`, `COLLECTION_SIZE` | `STRING_LENGTH(name) > 3` |
| Named parameters | `@paramName` | `price > @minPrice` |

```java
QueryRequest query = QueryRequest.builder()
    .expression("STARTS_WITH(name, @prefix) AND age >= @minAge")
    .parameter("prefix", "J")
    .parameter("minAge", 21)
    .maxPageSize(50)
    .build();

QueryPage page = client.query(address, query);
```

### Pagination

| Feature | Description |
|---------|-------------|
| **Cursor-based paging** | Continuation-token pagination across all providers |
| **Page size control** | `maxPageSize` to limit results per page |

### Data Management

| Feature | Description |
|---------|-------------|
| **Schema provisioning** | `provisionSchema()` creates databases, containers, and tables portably |
| **Transactions** | Multi-document transactional operations |
| **Batch operations** | Batch read/write for throughput efficiency |
| **Strong consistency** | Strongly-consistent reads |
| **Change feed** | Change feed / change streams — see [guide.md - Change Feeds](guide.md#change-feeds) |

### Diagnostics & Error Handling

| Feature | Description |
|---------|-------------|
| **Structured diagnostics** | Latency, request charge, and provider correlation IDs per operation |
| **Portable error categories** | All provider exceptions mapped to `MulticloudDbErrorCategory` |
| **Capability introspection** | `client.capabilities()` reports what the current provider supports |

---

## Portable Error Mapping

All provider exceptions are mapped to portable `MulticloudDbErrorCategory` values.
The raw HTTP or gRPC status code is also available via `error.statusCode()`.

| Category  | Cosmos DB  | DynamoDB  | Spanner  |
|-----------|------------|-----------|----------|
| `INVALID_REQUEST`  | HTTP 400  | ValidationException, HTTP 400  | INVALID_ARGUMENT, FAILED_PRECONDITION  |
| `AUTHENTICATION_FAILED`  | HTTP 401  | UnrecognizedClientException, HTTP 401/403  | UNAUTHENTICATED  |
| `AUTHORIZATION_FAILED`  | HTTP 403  | AccessDeniedException  | PERMISSION_DENIED  |
| `NOT_FOUND`  | HTTP 404  | ResourceNotFoundException, HTTP 404  | NOT_FOUND  |
| `CONFLICT` (409 - duplicate key)  | HTTP 409  | `ConditionalCheckFailedException` from `create()` - `attribute_not_exists` guard fails when the item already exists  | ALREADY_EXISTS  |
| `CONFLICT` (412 - precondition)  | HTTP 412  | `ConditionalCheckFailedException` from `update()`/`upsert()` with a condition expression¹  | ABORTED  |
| `NOT_FOUND` (missing patch target field)  | Classifying pre-read validation²  | `ConditionalCheckFailedException` with a missing field in its old image | In-transaction field-presence check  |
| `INVALID_REQUEST` (patch numeric target or integral-result overflow) | Classifying pre-read validation² | `ConditionalCheckFailedException` with a nonnumeric or out-of-range old value | In-transaction numeric validation |
| `THROTTLED`  | HTTP 429  | ProvisionedThroughputExceededException, ThrottlingException  | RESOURCE_EXHAUSTED  |
| `TRANSIENT_FAILURE`  | HTTP 449, 500, 502, 503  | HTTP 500–5xx  | UNAVAILABLE  |
| `PERMANENT_FAILURE`  | -  | ItemCollectionSizeLimitExceededException  | -  |
| `UNSUPPORTED_CAPABILITY`  | HTTP 400 with AVAD-not-enabled fingerprint (`providerDetails.reason="avad_not_enabled"`)  | `InvalidArgumentException` / `ResourceNotFoundException` for streams not enabled (`reason="stream_not_enabled"`)  | UNIMPLEMENTED, plus change-stream-not-provisioned (`reason="stream_not_enabled"`)  |
| `CURSOR_EXPIRED` (change-feed) | HTTP 410 GONE (`reason="PROVIDER_TRIMMED"`)  | `TrimmedDataAccessException` (`reason="PROVIDER_TRIMMED"`), `ExpiredIteratorException` (`reason="ITERATOR_EXPIRED"`)  | `INVALID_ARGUMENT` / `OUT_OF_RANGE` / `NOT_FOUND` for partition outside retention (`reason="PROVIDER_TRIMMED"`)  |
| `PROVIDER_ERROR`  | Other  | Other  | INTERNAL, Other  |

> ¹ DynamoDB uses `ConditionalCheckFailedException` for several conditions:
> duplicate-key `create()` maps to `CONFLICT`, an `update()` existence guard
> maps to `NOT_FOUND`, and PATCH is classified from its old image as shown
> above (`NOT_FOUND`, `INVALID_REQUEST`, or `CONFLICT`). The portable API does
> not yet expose caller-supplied ETag-based conditional updates; a future
> precondition API may add a dedicated category (tracked in issue #29).

> ² For Cosmos patches containing a `REPLACE`, `REMOVE`, `INCREMENT`, or nested
> path, the SDK point-reads the required state and validates it before the
> write. A missing document/path becomes `NOT_FOUND`, and a nonnumeric target or
> proven integral-result overflow becomes `INVALID_REQUEST`. That read's ETag is
> attached as an `If-Match` guard **only** for `REPLACE`, `REMOVE`, and nested
> non-increment operations, whose native Cosmos translation cannot enforce the
> portable contract alone; `INCREMENT` is exempt at every depth because
> `CosmosPatchOperations.increment` is atomic server-side. A HTTP 412 after an
> ETag-guarded read is always `CONFLICT`; it is never reread and reclassified.
> Because a pure-`INCREMENT` patch writes unconditionally, an increment target
> deleted or retyped *between* the classifying read and the write is classified
> by Cosmos's native error, so a raced increment may surface as
> `INVALID_REQUEST` where DynamoDB and Spanner report `NOT_FOUND`; non-raced
> classification is identical on all three providers. The exact emulator status
> behavior remains unverified pending T192. DynamoDB follows the same categories
> from its conditional-failure old image.

## Patch Semantics

`patch()` applies field-level changes to an **existing** document atomically:
Cosmos DB and DynamoDB use native partial writes, while Spanner updates its
standard `data` document envelope in a retryable transaction. See
[guide.md - patch](guide.md#patch---field-level-partial-update) for the full
contract and examples.

### Capability declarations

| Capability | Cosmos DB | DynamoDB | Spanner |
|------------|-----------|----------|---------|
| `PATCH` | ✅ Native `patchItem()` — max 10 operations natively | ✅ Native `UpdateItem` with a compiled `UpdateExpression` | ✅ Equivalent atomic retryable `data` document-envelope transaction |
| `NESTED_PATCH` | ✅ Patch paths address the JSON tree directly | ✅ Document paths (`a.b.c`) address nested map attributes | ❌ Nested JSON traversal is deferred from the v1 compatibility scope; a nested path fails fast with `UNSUPPORTED_CAPABILITY` |
| `EXACT_FRACTIONAL_INCREMENT` | ❌ Fractional `INCREMENT` is evaluated in IEEE-754 binary64 | ✅ Fractional `INCREMENT` is evaluated in the DynamoDB `N` type — exact decimal, 38 significant digits | ❌ Fractional `INCREMENT` is evaluated in IEEE-754 binary64 |

Spanner rejects a nested path with `UNSUPPORTED_CAPABILITY` rather than
silently rewriting the parent or doing nothing. Portable code checks
`client.capabilities().isSupported(Capability.NESTED_PATCH)` or patches the
top-level field instead.

`EXACT_FRACTIONAL_INCREMENT` is **informational only**. Every provider accepts
every in-domain fractional delta, and nothing is ever rejected because of this
capability — it exists so callers that need bit-identical fractional totals can
branch. It reports how a fractional increment *accumulates*: seeding
`{"v": 0.1}` and incrementing by `0.2` stores exactly `0.3` on DynamoDB and
`0.30000000000000004` on Cosmos DB and Spanner, and the divergence compounds
across repeated fractional increments. **Integral** increments are exact on all
three providers (the portable domain bounds them to signed 64-bit range).

```java
if (!client.capabilities().isSupported(Capability.EXACT_FRACTIONAL_INCREMENT)) {
    // Provider accumulates in binary64 — keep money as integer minor units,
    // or re-round after reading, if you need identical totals everywhere.
}
```

### Cost model

`patch()` is a latency and concurrency optimisation — **not** a guaranteed
write-cost reduction. Cosmos DB and DynamoDB also reduce request payload; all
providers validate patch requests against the portable 399 KB (408,576-byte)
limit. The byte count is the deterministic serialized operation list, including
every operation's type, path, and optional value; a `REMOVE` contributes its
verb and path even though it has no value. Billing depends on provider pricing,
account configuration, indexes, item shape, and workload; use the provider's
current pricing documentation rather than assuming an equivalence:

| Provider | Write cost | What you actually save |
|----------|-----------|------------------------|
| Cosmos DB | Workload- and indexing-dependent; no replace-equivalence is promised. **A patch containing any `REPLACE`, `REMOVE`, `INCREMENT`, or nested path also costs one point read** (see below) | Request payload, one round trip, and the lost-update window of read-modify-write |
| DynamoDB | Item- and configuration-dependent; no `PutItem`-equivalence is promised. A single `UpdateItem` — no extra read | Request payload, one round trip, and the lost-update window |
| Spanner | The retryable transaction reads and writes the `data` envelope; evaluate it with current Spanner pricing | Serializable atomicity, dynamic top-level fields without DDL, and retry-safe increments |

**Cosmos patch is not always one request.** Cosmos reports a missing path, a
nonnumeric increment target, and a nested-parent violation as an untyped HTTP
`400`, so the adapter point-reads and classifies the document before the write
whenever the request contains a `REPLACE`, `REMOVE`, `INCREMENT`, or nested
path — that read is billed in addition to the patch. A patch made only of
top-level `SET` operations skips the read entirely and costs a single request.
The validating read's ETag is then attached as an `If-Match` guard **only** for
`REPLACE`, `REMOVE`, and nested non-increment operations; a pure-`INCREMENT`
patch still pays for the classifying read but writes unconditionally, so
concurrent increments all land instead of failing as non-retryable `CONFLICT`s.

On every provider, `patch()` is the only portable way to express an atomic
counter: `PatchOperation.increment(...)` is evaluated in the same atomic native
write or retryable transaction, so concurrent increments do not lose updates.

Spanner does its existence check and envelope update in **one retryable
read-write transaction over the row**, so concurrent patches to *disjoint*
fields of the same document contend and one transparently retries — Cosmos DB
and DynamoDB do not. A fractional finite increment of an integral value is
represented in the envelope as a floating-point value, matching the
schemaless providers; because that value no longer fits an `INT64` physical
mirror, the mirror is explicitly written as a **typed NULL** rather than left
stale. (Every incompatible, null, or omitted mirror is cleared the same way,
which is exactly why portable `ORDER BY` now sorts through the authoritative
envelope instead of the physical column.) Integral deltas and their resulting
values must fit signed 64-bit
range; fractional deltas must
round-trip through a finite IEEE-754 `double` without decimal precision loss
and have magnitude no greater than 9,007,199,254,740,991. Values outside that
portable domain are rejected as `INVALID_REQUEST`: invalid deltas before
dispatch, and result overflow atomically with the provider write.

Portable Spanner expressions choose the authoritative `data` envelope or the
physical-column JSON projection once per row. A valid envelope never falls back
per missing field, so stale physical columns cannot reappear after a
replacement or removal; malformed and legacy metadata safely use the physical
projection. Consequently, patched fractional and dynamic fields participate in
the same predicates as values returned by `read()`.

## Change-Feed History Retention

The portable change-feed read path guarantees a **24-hour** history floor on
every provider out of the box — a cursor token minted by `ChangeFeedCursor#toToken()`
can be replayed for 24 hours regardless of which provider produced it.

To request a longer server-side retention window, opt in via
`ChangeFeedConfig.builder().extendedRetention(Duration)` on
`MulticloudDbClientConfig`. The SDK fails fast at client-build time with
`UNSUPPORTED_CAPABILITY` (`reason=extended_retention_unavailable`) if the
target provider does not declare the `EXTENDED_CHANGE_FEED_HISTORY` capability.

| Provider | Declares `EXTENDED_CHANGE_FEED_HISTORY` | How it is honoured | Practical ceiling |
|---|---|---|---|
| Cosmos DB | ✅ | `ensureContainer()` provisions an AVAD `ChangeFeedPolicy` carrying the requested retention. The account must have Continuous Backup enabled; the SDK normalises the "continuous backup required" failure to `UNSUPPORTED_CAPABILITY` (`reason=continuous_backup_required`). | Up to **30 days** on a Continuous Backup 30-day tier; 7 days is the most common ceiling. |
| Spanner | ✅ | `ensureContainer()` emits `CREATE CHANGE STREAM <table>_changes FOR <table> OPTIONS (value_capture_type = 'NEW_ROW', retention_period = '<value>')` after the table-create (the `NEW_ROW` capture type matches what the SDK's change-feed reader requires for full-row payloads). Requests beyond the database's native maximum are normalised to `UNSUPPORTED_CAPABILITY` (`reason=retention_exceeds_native_max`). If a stream of the same name already exists with a different retention, `ensureContainer()` reads back the active retention via `INFORMATION_SCHEMA.CHANGE_STREAM_OPTIONS` and surfaces the mismatch as `UNSUPPORTED_CAPABILITY` (`reason=extended_retention_not_enacted`) so the divergence cannot be silently swallowed. | **7 days** natively; up to **1 year** only on a database explicitly configured for extended retention. |
| DynamoDB | ❌ | DynamoDB Streams is fixed at 24 h server-side. Calling `client(...).provisionSchema(...)` (or any container-create call) with an `extendedRetention` opt-in fails fast at client-build time. | Drain Streams into a customer-provisioned Kafka cluster (outside the SDK) for >24 h today. SDK-managed archive-on-read via Kafka (customer-provisioned brokers) is on the v1.x roadmap. |

**Cost is provider-shaped** — extending the change-feed history window changes
your bill differently on each provider; the windows are not interchangeable.
See `docs/guide.md` → *"Extending change-feed history beyond 24 hours"* for the
per-provider price-driver detail before opting in.
## Default Sort-Key Ordering

All Cosmos DB and DynamoDB query paths return results sorted by the document's
sort key ascending.

> **Design note:** The default `ORDER BY` is applied to **all** Cosmos queries
> (both partition-scoped and cross-partition), not just partition-scoped ones.
> This gives the strongest consistency guarantee: every query, on every provider,
> returns items sorted by sort key. The early PR description mentioned
> partition-scoped only as a starting point; the final implementation was
> intentionally broadened to cover all queries.

### Cosmos DB

Cosmos DB appends `ORDER BY c.id ASC` to every query that does not already carry
an explicit `ORDER BY` clause (and is not an aggregate / `GROUP BY` query). This
is applied server-side, so the order is globally consistent across all pages.

> **⚠️ Custom indexing policy - composite index required**
> If your Cosmos container uses a **custom indexing policy** that does not include
> a composite index on `(filterField ASC, id ASC)`, Cosmos DB will throw a
> `400 Bad Request` at runtime for cross-partition queries that combine `WHERE` and
> the default `ORDER BY c.id ASC`. The default indexing policy includes all paths
> and supports this automatically. If you have tuned your indexing policy, add the
> composite index for every field you filter on:
> ```json
> { "compositeIndexes": [ [{ "path": "/filterField", "order": "ascending" },
>                          { "path": "/id", "order": "ascending" }] ] }
> ```
>
> **⚠️ RU cost**
> Appending `ORDER BY c.id ASC` to all Cosmos queries incurs an additional RU
> charge versus unordered queries, proportional to result-set size. This cost is
> the price of cross-provider consistency and is expected behavior.
>
> **⚠️ Aggregates and GROUP BY**
> Cosmos DB rejects `ORDER BY` on aggregate expressions (`COUNT`, `SUM`, `MIN`,
> `MAX`, `AVG`) and `GROUP BY` queries. The SDK automatically detects these patterns
> and omits the default `ORDER BY` for them.

### DynamoDB

DynamoDB results are sorted in memory per page after fetching (client-side).
Within a single page, items are returned sorted by sort key ascending.
For multi-page scans the overall order across pages is determined by DynamoDB's
internal token-based traversal, not sort key - this is a known limitation.

### Spanner

The Spanner provider does not yet implement default sort-key ordering.
Consumers relying on consistent cross-provider sort behavior should not use
the Spanner provider until this gap is addressed.

> **Tracking**: A follow-up issue will be filed to implement default sort-key
> ordering for the Spanner provider. Until resolved, do not mix Spanner with
> Cosmos or DynamoDB in conformance-sensitive workloads.

## Escape Hatch Policy

The SDK does not expose a `nativeClient()` method. Direct access to the
underlying provider client is intentionally omitted to enforce portability
guarantees - code written against the SDK must remain switchable between
providers by configuration alone.

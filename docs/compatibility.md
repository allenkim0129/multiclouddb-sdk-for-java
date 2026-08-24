# Portable API Surface

The Multicloud DB SDK's baseline API surface covers capabilities that work
identically across all three providers. The features in **What Works
Everywhere** require no runtime capability checks. Capability-gated features,
including PATCH, are documented separately with explicit provider matrices.

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

`patch()` is not in the universal table: Cosmos DB and DynamoDB implement it,
while Spanner declares `Capability.PATCH` unsupported. See
[Patch Semantics](#patch-semantics).

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
| `CONFLICT` (precondition)  | Only from a patch whose conditional HTTP 412 current state cannot explain—the adapter sends no `If-Match`, so a 412 is re-read and classified, and an unprovable one reports `CONFLICT`²  | `ConditionalCheckFailedException` from `update()`/`upsert()` with a condition expression¹  | ABORTED  |
| `NOT_FOUND` (missing patch target field)  | Atomic filter rejection classified from a session-token read²  | `ConditionalCheckFailedException` with a missing field in its old image | Not applicable — PATCH unsupported  |
| `INVALID_REQUEST` (patch numeric target or integral-result overflow) | Atomic filter rejection classified from a session-token read² | `ConditionalCheckFailedException` with a nonnumeric or out-of-range old value | Not applicable — PATCH unsupported |
| `THROTTLED`  | HTTP 429  | ProvisionedThroughputExceededException, ThrottlingException  | RESOURCE_EXHAUSTED  |
| `TRANSIENT_FAILURE`  | HTTP 449, 500, 502, 503  | HTTP 500–5xx  | UNAVAILABLE  |
| `PERMANENT_FAILURE`  | -  | ItemCollectionSizeLimitExceededException  | -  |
| `UNSUPPORTED_CAPABILITY`  | PATCH on an item carrying SDK-managed `ttl` (`providerDetails.reason="patch_on_ttl_item_unsupported"`); HTTP 400 with AVAD-not-enabled fingerprint (`reason="avad_not_enabled"`)  | `InvalidArgumentException` / `ResourceNotFoundException` for streams not enabled (`reason="stream_not_enabled"`)  | SPI default for PATCH; UNIMPLEMENTED and change-stream-not-provisioned (`reason="stream_not_enabled"`) for other features  |
| `CURSOR_EXPIRED` (change-feed) | HTTP 410 GONE (`reason="PROVIDER_TRIMMED"`)  | `TrimmedDataAccessException` (`reason="PROVIDER_TRIMMED"`), `ExpiredIteratorException` (`reason="ITERATOR_EXPIRED"`)  | `INVALID_ARGUMENT` / `OUT_OF_RANGE` / `NOT_FOUND` for partition outside retention (`reason="PROVIDER_TRIMMED"`)  |
| `PROVIDER_ERROR`  | Other  | Other  | INTERNAL, Other  |

> ¹ DynamoDB uses `ConditionalCheckFailedException` for several conditions:
> duplicate-key `create()` maps to `CONFLICT`, an `update()` existence guard
> maps to `NOT_FOUND`, and PATCH is classified from its old image as shown
> above (`NOT_FOUND`, `INVALID_REQUEST`, or `CONFLICT`). The portable API does
> not yet expose caller-supplied ETag-based conditional updates; a future
> precondition API may add a dedicated category (tracked in issue #29).

> ² Cosmos performs no pre-write classification read. Its conditional-patch
> predicate atomically requires addressed-path existence, numeric increment
> targets, signed-64 integral-result bounds, and absence of the SDK-managed
> `ttl` field. A resulting HTTP 412 (or an untyped 400 from a native transition)
> is classified from a point read carrying the rejecting request's session
> token: a vanished path reports `NOT_FOUND`, a retyped or out-of-range
> increment reports `INVALID_REQUEST`, a TTL-bearing item reports
> `UNSUPPORTED_CAPABILITY`, and only an unexplained rejection reports
> `CONFLICT` (or retains the native 400 mapping). DynamoDB derives the equivalent
> state categories from its conditional-failure old image, with a strongly
> consistent point-read fallback if that image is absent. Exact emulator status
> behavior remains unverified pending T192.

## Patch Semantics

`patch()` applies field-level changes to an **existing** document atomically on
providers that declare `Capability.PATCH`. Cosmos DB and DynamoDB use native
partial writes. Spanner currently declares PATCH unsupported and fails before
issuing a provider request. See
[guide.md - patch](guide.md#patch---field-level-partial-update) for the full
contract and examples.

### Capability declarations

| Capability | Cosmos DB | DynamoDB | Spanner |
|------------|-----------|----------|---------|
| `PATCH` | ✅ Native `patchItem()` — max 10 operations natively | ✅ Native `UpdateItem` with a compiled `UpdateExpression` | ❌ Planned follow-up; calls fail with `UNSUPPORTED_CAPABILITY` |
| `NESTED_PATCH` | ✅ Patch paths address the JSON tree directly | ✅ Document paths (`a.b.c`) address nested map attributes | ❌ Depends on the future PATCH implementation |

Portable code checks `Capability.PATCH` before calling the operation and checks
`Capability.NESTED_PATCH` before using a sub-document path. Unsupported calls
fail explicitly rather than silently falling back to read-modify-write.
#### Two rules that are uniform rather than capability-gated

Two cases would have stored different data depending on the provider, so the
portable contract rejects them everywhere instead of declaring a capability.
Neither needs a runtime check: the outcome is the same on Cosmos DB and
DynamoDB.

**Fractional `INCREMENT` deltas are `INVALID_REQUEST`.** DynamoDB accumulates
in its `N` type (exact decimal, 38 significant digits) while Cosmos DB
accumulates in IEEE-754 binary64, so seeding `{"v": 0.1}` and incrementing by
`0.2` would have stored `0.3` on one and `0.30000000000000004` on the other.
Integral deltas are the only deltas the contract accepts. They accumulate
exactly on both providers when the stored value is integral; if the field
already holds a fraction, a whole-number delta can still land one ulp apart,
and closing that gap would require a read that forfeits atomicity. Keep such
quantities in integer minor units.

```java
// Rejected everywhere — the accumulated result is not portable.
client.patch(address, key, List.of(PatchOperation.increment("/balance", 0.25)));

// Portable: keep money in integer minor units.
client.patch(address, key, List.of(PatchOperation.increment("/balanceCents", 25)));

// Also portable: SET writes the operand verbatim, so no arithmetic is involved.
client.patch(address, key, List.of(PatchOperation.set("/price", 19.99)));
```

**Patching an item carrying an SDK-managed TTL is `UNSUPPORTED_CAPABILITY`.**
DynamoDB's `ttlExpiry` is an absolute timestamp that `UpdateItem` leaves
untouched, but Cosmos DB's `ttl` is a relative countdown that a native patch
restarts by advancing `_ts`. Rather than let the same call keep the original
expiry on one provider and extend it on the other, both reject the patch
before any mutation. Rewrite the whole document with `upsert(...)` if you need
to modify a TTL-bearing item.

### Cost model

`patch()` is a latency and concurrency optimisation — **not** a guaranteed
write-cost reduction. Cosmos DB and DynamoDB also reduce request payload and
validate patch requests against the portable 399 KB (408,576-byte) limit. The
byte count is the deterministic serialized operation list, including every
operation's type, path, and optional value; a `REMOVE` contributes its verb and
path even though it has no value. This bounds the request envelope only: the
resulting item is still subject to each provider's native item-size limit, so a
patch that grows an already-large document can be accepted by Cosmos and
rejected by DynamoDB. Billing depends on provider pricing, account
configuration, indexes, item shape, and workload:

| Provider | Write cost | What you actually save |
|----------|-----------|------------------------|
| Cosmos DB | Workload- and indexing-dependent; no replace-equivalence is promised. Successful PATCH is one `patchItem`; a rejected condition adds one classifying point read | Request payload and the lost-update window of read-modify-write |
| DynamoDB | Item- and configuration-dependent; no `PutItem`-equivalence is promised. Success is one `UpdateItem`; rejected conditions normally classify from `ALL_OLD`, with one point-read fallback only if the response omits that image | Request payload, one success-path round trip, and the lost-update window |
| Spanner | No write cost: PATCH is rejected before provider dispatch | Explicit capability failure rather than an unsafe or expensive emulation |

**A successful Cosmos patch is one request.** Required-path existence,
increment numeric type and integral bounds, and the item-TTL guard ride on the
write as a conditional filter predicate. A rejected predicate adds one point
read to classify the portable error. No `If-Match` ETag is sent, so a
concurrent write to an unaddressed field cannot falsify the path terms and
concurrent increments of an in-range counter all land.

On providers that support PATCH, `PatchOperation.increment(...)` is the
portable atomic-counter primitive. Concurrent increments are evaluated inside
the provider's atomic write. A future Spanner implementation must satisfy the
same conformance contract before declaring PATCH supported.

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

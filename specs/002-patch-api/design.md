# Portable PATCH API Design

| Metadata | Value |
|---|---|
| Status | Implemented by `feat/patch-api` / PR #95 |
| Providers | Cosmos DB and DynamoDB |
| Deferred | Spanner |
| Updated | 2026-08-24 |

## 1. Decision

The SDK exposes one provider-neutral field-level PATCH API:

```java
client.patch(address, key, List.of(
        PatchOperation.replace("/status", "SHIPPED"),
        PatchOperation.increment("/revision", 1)));
```

Cosmos DB and DynamoDB use native server-side partial writes. Spanner declares
PATCH unsupported in this release and can implement the same API later.

| Provider | Implementation |
|---|---|
| Cosmos DB | One conditional `patchItem` |
| DynamoDB | One conditional `UpdateItem` |
| Spanner | `UNSUPPORTED_CAPABILITY` before provider I/O |

## 2. Portable contract

All operations in one call are atomic: either all changes are applied or none
are applied. The target document must already exist.

| Operation | Behavior |
|---|---|
| `SET` | Create or overwrite a field. A nested parent must already exist. |
| `REPLACE` | Replace an existing field; otherwise `NOT_FOUND`. |
| `REMOVE` | Remove an existing field; otherwise `NOT_FOUND`. |
| `INCREMENT` | Atomically add to an existing number. |

### Portable limits and rules

Rules 1 through 10 are checkable from the request alone, so the SDK rejects a
violating call before it sends anything to a provider. Rule 11, and the
`INCREMENT`-result half of rule 7, depend on the stored document — which patch
never reads — so the provider enforces them atomically with the write and no
operation in the list is applied. Each row combines the portable restriction,
the provider difference that requires it, and the supporting reference.

| # | Portable limit or rule | Why it exists | Reference |
|---|---|---|---|
| 1 | **Operation list** — non-null, non-empty, free of `null` entries, and at most 10 operations per call, exposed as `MulticloudDbClient.MAX_PATCH_OPERATIONS`. | Cosmos DB caps one patch at 10 operations; DynamoDB has no matching operation-count cap. Applying the lower limit everywhere prevents the same request from succeeding on one provider and failing on the other. An absent, empty, or `null`-bearing list has nothing to translate into a native update, so it is rejected uniformly instead of becoming a provider-specific null-pointer or no-op write. | [Cosmos DB partial document update](https://learn.microsoft.com/en-us/azure/cosmos-db/partial-document-update#supported-modes) |
| 2 | **Absolute JSON Pointer paths** — every path starts with `/` and names a field from the document root. | A root-anchored path has one meaning. A relative path would require provider-specific context that the portable API does not define. | [RFC 6901](https://www.rfc-editor.org/rfc/rfc6901.txt) |
| 3 | **No array indexes** — a purely numeric segment such as `/tags/0` is rejected. | Cosmos DB and DynamoDB expose different array-element update behavior. The portable operation is to `SET` the complete array instead. | [Cosmos DB partial document update](https://learn.microsoft.com/en-us/azure/cosmos-db/partial-document-update#supported-modes); [DynamoDB update expressions](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Expressions.UpdateExpressions.html) |
| 4 | **No `~` escapes** — any path containing `~` is rejected. | JSON Pointer requires `~1` to be decoded before `~0`. Translating escaped names through different native path dialects could address different fields, so the SDK rejects the ambiguous form. | [RFC 6901 §4](https://www.rfc-editor.org/rfc/rfc6901.txt) |
| 5 | **No duplicate, case-only alias, or overlapping paths** — operations cannot target the same field, names differing only by case, or a field and its ancestor. | Native update engines do not share one rule for whether an operation reads the original document or a prior operation's result. Rejecting overlap removes ordering-dependent results. Siblings such as `/a/b` and `/a/c` remain valid. | [Cosmos DB partial document update](https://learn.microsoft.com/en-us/azure/cosmos-db/partial-document-update#supported-modes); [DynamoDB update expressions](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Expressions.UpdateExpressions.html) |
| 6 | **No reserved fields** — `id`, `partitionKey`, `sortKey`, `ttl`, `ttlExpiry`, `data`, and `_`-prefixed names, matched without regard to case. | These fields hold SDK keys, provider TTL state, Spanner metadata, or Cosmos DB system properties. Patching them could desynchronize the document from its key or corrupt provider-managed state. | [Cosmos DB resource model](https://learn.microsoft.com/en-us/azure/cosmos-db/resource-model); [Cosmos DB TTL](https://learn.microsoft.com/en-us/azure/cosmos-db/nosql/time-to-live); [DynamoDB TTL](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/TTL.html) |
| 7 | **Signed 64-bit integral domain** — whole values, deltas, and `INCREMENT` results fit `-2^63` through `2^63 - 1`. | Cosmos DB defines integer values as signed 64-bit, while DynamoDB can store larger exact decimals. Signed 64-bit is therefore the widest common integer domain and matches the Java `long` returned by the portable API. | [Cosmos DB `IS_INTEGER`](https://learn.microsoft.com/cosmos-db/query/is-integer); [DynamoDB data types](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/HowItWorks.NamingRulesDataTypes.html) |
| 8 | **Portable fractional domain** — `0`, or a magnitude from `1E-130` through about `9.007E15` (`2^53 - 1`, exactly `9007199254740991`), with no decimal loss when normalized to `double`. | DynamoDB rejects smaller non-zero magnitudes. The ceiling is the largest magnitude at which binary64 still has unit precision; the round-trip check rejects decimal values that normalization would change. | [DynamoDB data types](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/HowItWorks.NamingRulesDataTypes.html); [Java floating-point types](https://docs.oracle.com/javase/specs/jls/se17/html/jls-4.html#jls-4.2.3) |
| 9 | **Request envelope** — at most 399 KB (`408,576` bytes) serialized. | DynamoDB's 400 KB item limit is the lowest provider ceiling. The SDK reserves 1 KB for provider-injected fields and representation overhead, then applies the remaining 399 KB uniformly. | [DynamoDB data types](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/HowItWorks.NamingRulesDataTypes.html) |
| 10 | **Whole-number `INCREMENT` deltas** — a fractional delta is `INVALID_REQUEST`. | DynamoDB adds in exact decimal, while Cosmos DB's native increment follows binary64 arithmetic: `0.1 + 0.2` can become `0.3` versus `0.30000000000000004`. Rejecting fractional deltas avoids that divergence without a non-atomic read-modify-write. Fractional `SET` and `REPLACE` values remain valid because they perform no arithmetic; `1.0` normalizes to the whole number `1`. | [Cosmos DB partial document update](https://learn.microsoft.com/en-us/azure/cosmos-db/partial-document-update#supported-modes); [DynamoDB update expressions](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Expressions.UpdateExpressions.html); [DynamoDB data types](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/HowItWorks.NamingRulesDataTypes.html) |
| 11 | **No patch on a TTL-bearing document** — `UNSUPPORTED_CAPABILITY`, raised before mutation. | Cosmos DB's `ttl` is relative to the last-write timestamp, so native patch restarts its countdown. DynamoDB's `ttlExpiry` is absolute and would remain unchanged. Both reject the patch so the same call cannot extend expiry on one provider and preserve it on the other. Use `upsert(...)` instead. | [Cosmos DB TTL](https://learn.microsoft.com/en-us/azure/cosmos-db/nosql/time-to-live); [DynamoDB TTL](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/TTL.html) |

Rules 7 and 8 apply recursively to values written by `SET` and `REPLACE`,
including numbers nested inside an object or array operand. Rule 7 also bounds
an `INCREMENT` delta and its result; rule 10 requires that delta to be a whole
number.

#### Why rules 7 and 8 are separate

They look redundant — one caps at `2^63 - 1`, the other at `2^53 - 1` — but they
answer different questions:

- **Rule 7** asks *what whole number can every provider store and return
  unchanged?* The answer is the signed 64-bit range.
- **Rule 8** asks *what fractional magnitude remains within binary64's unit
  precision, before the per-value decimal round-trip check?* The answer is
  `2^53 - 1`.

Merging them either way is worse. Widening rule 8 to `2^63 - 1` would admit
fractional values that Cosmos DB and DynamoDB round differently. Narrowing rule
7 to `2^53 - 1` would reject `long` values that already round-trip perfectly on
both providers, for no portability gain.

Rule 10 keeps the split simple for `INCREMENT`: because a delta must be a whole
number, only rule 7 governs it, bounding both the delta and the result. Rule 8
applies to values written by `SET` and `REPLACE`, where no provider arithmetic
is involved.

#### How the 399 KB envelope is derived

The size calculation is `400 * 1024 - 1 * 1024 = 408,576` bytes: DynamoDB's
400 KB item limit, less 1 KB reserved for provider-injected fields and
representation overhead.

For PATCH, the 399 KB check measures each operation's type, path, and optional
value; a `REMOVE` still contributes its type and path. It bounds the request,
not the resulting document. A patch can still be rejected if its post-image
exceeds a provider's native item limit.

#### What these rules do not cover

Rule 10 removes the divergence the SDK can see: it inspects the delta, and a
fractional delta is rejected. It cannot remove the divergence that depends on
the **stored** value, because the SDK never reads that value — reading it is
exactly what `INCREMENT` exists to avoid.

So one case remains. If a field already holds a fractional number and you add a
whole-number delta, the two providers can still land one ulp apart:

```java
client.upsert(address, Map.of("v", 0.7383633795947941, ...));
client.patch(address, key, List.of(PatchOperation.increment("/v", 3)));
```

| Provider | Arithmetic | Result read back |
|---|---|---|
| DynamoDB | exact decimal: `0.7383633795947941 + 3` = `3.7383633795947941`, parsed back into a `double` | `3.738363379594794` |
| Cosmos DB | binary64: `double(0.7383633795947941) + 3`, rounded once | `3.7383633795947944` |

The value needs enough significant digits to use most of the `double` mantissa
before this shows up — `0.1 + 3` is identical on both. Closing it would require
reading the current value and computing the sum client-side, which forfeits
atomicity and reintroduces the lost-update race that rule 10's own justification
rules out. Trading a guaranteed correctness property for a last-digit
representation detail is the wrong trade, so the SDK keeps atomicity.

The portable guidance is the same as for rule 10: **if the exact digits matter,
do not store the quantity as a fraction.** Use integral minor units — hold
`/balanceCents` and increment by `20`, not `/balance` by `0.20` — and rules 7
and 10 then guarantee an identical result on every provider.

`OperationOptions.ttlSeconds()` is ignored. PATCH never creates or resets an
expiry, and by rule 11 it refuses to touch a document that already has one.

## 3. Workflows

The success and failure paths are shown separately because they have different
behavior and cost. A successful PATCH sends one native write and performs no
read. Rejected writes may need provider-specific evidence before the SDK can
choose the portable error.

Every terminal error in Figure 2 is a `MulticloudDbException`. Application code
checks `exception.error().category()` rather than catching provider SDK
exceptions.

### Request workflow

![Portable PATCH request workflow](images/patch-request-workflow.png)

*Figure 1. On an adapter that implements PATCH the gates run in this order:
client state, then portable validation, then capability. Cosmos DB and
DynamoDB each receive one atomic native write carrying the complete operation
list. Rule 11 is absent from the pre-dispatch box because it depends on stored
state and is enforced by the native condition. Native rejections continue to
Figure 2.*

### Exception propagation workflow

![Portable PATCH exception propagation workflow](images/patch-rejection-workflow.png)

*Figure 2. A provider exception is never returned directly to application code.
The adapter inspects the native response and any rejection evidence,
constructs a structured `MulticloudDbError` — including the `retryable` hint,
which is `true` for `CONFLICT` only — and throws `MulticloudDbException` back
to the caller. Pre-dispatch rejections join the same path with no native
cause.*

Before provider I/O, a portable-rule violation produces `INVALID_REQUEST` and a
missing `PATCH` / `NESTED_PATCH` capability produces `UNSUPPORTED_CAPABILITY`.
Which one a caller sees when both apply depends on where PATCH is refused:

- **On an adapter that implements PATCH** (Cosmos DB, DynamoDB), shared
  validation runs first and the capability gate second. A request that both
  breaks a portable rule and uses a nested path the provider does not declare
  reports `INVALID_REQUEST`.
- **On a provider that does not implement PATCH** (Spanner), the inherited SPI
  default rejects immediately and never runs shared validation, so even an
  invalid operation list reports `UNSUPPORTED_CAPABILITY`. The portable answer
  "this provider cannot patch" does not depend on the request being well
  formed.

No provider request was sent in either case, so the resulting
`MulticloudDbException` has no provider exception as its cause.

## 4. Cosmos DB

Figure 1 shows the request path; Figure 2 expands every failure branch. The
Cosmos adapter translates the operation list into `CosmosPatchOperations` and
attaches one server-side filter predicate to one `patchItem` request. The
predicate atomically checks required paths and nested parents, numeric
increment targets, signed-64-bit increment-result bounds, and absence of
SDK-managed `ttl`.

A successful PATCH performs one write and no read. It does not use an
item-wide `If-Match` ETag; the path-scoped predicate allows concurrent updates
to unrelated fields.

Cosmos may return HTTP 412 or an untyped HTTP 400 when the predicate rejects.
The adapter then performs a session-token point read and maps the observed
state to the exceptions in Figure 2. Cosmos native increment would use
binary64 arithmetic, and native patch advances `_ts`, so rules 10 and 11 reject
the two divergent cases before mutation.

### Error classification

- `NOT_FOUND` — Cosmos returned HTTP 404 directly, or the classification read
  shows that the document, required field, or required nested parent is
  missing.
- `UNSUPPORTED_CAPABILITY` — the classification read finds SDK-managed `ttl`.
  Native patch would advance `_ts` and restart the relative expiry.
- `INVALID_REQUEST` — the target is nonnumeric, an integral result would
  overflow signed 64-bit, or an untyped HTTP 400 cannot be classified more
  specifically.
- `CONFLICT` — HTTP 412 rejected the predicate, but current state satisfies
  every portable precondition or the classification read failed. The original
  cause can no longer be proved after a concurrent transition.

When provider I/O occurred, the resulting `MulticloudDbException` retains the
original `CosmosException` as its cause and copies the native status and request
diagnostics into `MulticloudDbError`.

## 5. DynamoDB

Figures 1 and 2 show the DynamoDB request and failure paths. The adapter
compiles the operation list into one `UpdateExpression` and one
`ConditionExpression`, then sends one `UpdateItem`. The condition atomically
checks document, path, and nested-parent existence; numeric increment targets;
signed-64-bit increment-result bounds; and absence of SDK-managed `ttlExpiry`.
Caller field names use expression-name placeholders, so reserved words and
special characters remain safe.

A successful PATCH performs one write and no read. The request sets
`ReturnValuesOnConditionCheckFailure.ALL_OLD`; when the condition rejects,
DynamoDB normally attaches the unchanged pre-write item to the
`ConditionalCheckFailedException`.

`ALL_OLD` is failure evidence, not a successful-update response or a history
lookup. Because the condition failed, no mutation occurred, so this image is
also the current unchanged item at the time of rejection. It normally avoids a
separate `GetItem`.

If the service, emulator, or exception does not include the `ALL_OLD` image,
the adapter uses one strongly consistent `GetItem` as a rejection-only
fallback, then maps the state to the exceptions in Figure 2.

### Error classification

- `NOT_FOUND` — the unchanged item is absent, or a required field or nested
  parent is missing.
- `UNSUPPORTED_CAPABILITY` — the unchanged item contains SDK-managed
  `ttlExpiry`. The condition deliberately requires
  `attribute_not_exists(ttlExpiry)` so the portable behavior matches Cosmos DB.
- `INVALID_REQUEST` — the increment target is nonnumeric, its integral result
  would overflow signed 64-bit, or the stored number is outside the portable
  numeric domain.
- `CONFLICT` — the response has no `ALL_OLD` image and the fallback read fails,
  or the evidence proves none of the known deterministic causes.

The resulting `MulticloudDbException` retains the original
`ConditionalCheckFailedException` as its cause. The fallback `GetItem` is only
classification evidence; it never changes the already-rejected write.

DynamoDB native arithmetic is exact decimal, and `UpdateItem` would preserve
the absolute `ttlExpiry`. Rules 10 and 11 deliberately constrain those native
advantages so the portable outcome matches Cosmos DB.

## 6. Capability matrix

| Capability | Cosmos DB | DynamoDB | Spanner |
|---|---|---|---|
| `PATCH` | Supported | Supported | Unsupported |
| `NESTED_PATCH` | Supported | Supported | Unsupported |

Only two capabilities gate PATCH. Everything else in the contract is the lowest
common denominator of the implementing providers, enforced uniformly before
dispatch.

That is a deliberate choice. A capability flag is the right tool when a
provider genuinely cannot do something — Spanner has no PATCH at all. It is the
wrong tool when providers all *can* act but would act differently, because the
flag would license one portable call to store different data depending on where
it ran. Rules 10 and 11 cover exactly those two cases, so they are uniform
rejections rather than capabilities.

Applications needing fractional accumulation should use integral minor units
(`increment("/balanceCents", 20)`), which is exact on both providers.

## 7. Concurrency and retry behavior

Both providers evaluate increments on the server, so concurrent increments do
not lose updates.

An SDK-reported PATCH `CONFLICT` is safe to retry because the conditional write
was rejected and no operation was applied. Both adapters therefore set
`error().retryable() == true` on it, and `CONFLICT` is the only portable PATCH
category that carries that hint. Every other PATCH category names a
deterministic cause an identical retry would reproduce, so it is reported as
non-retryable.

PATCH does not provide an idempotency token. Applications must not blindly
replay an `INCREMENT` after an ambiguous transport failure because the original
request may already have committed.

## 8. Cost expectations

PATCH is a latency, payload, and concurrency optimization, not a billing
guarantee.

| Provider | Successful request | Rejection-only work |
|---|---|---|
| Cosmos DB | One `patchItem` | Point read for HTTP 412 or untyped 400 classification |
| DynamoDB | One `UpdateItem` | Point read only when the failed response omits `ALL_OLD` |
| Spanner | No request | No request |

Actual RU/WCU usage depends on provider configuration, indexing, item shape,
and pricing.

## 9. Deferred Spanner implementation

The API and validation are already provider-neutral. A future Spanner
implementation only needs to:

1. implement the same SPI method;
2. satisfy the same error and atomicity contract;
3. pass the shared conformance suite;
4. update its capability declarations.

The detailed Spanner proposal is maintained separately on `spanner/patch` in
`specs/002-patch-api/spanner-design.md`.

## 10. References

- Requirements: `specs/001-clouddb-sdk/spec.md`, FR-181 through FR-192
- Planning decisions: `specs/001-clouddb-sdk/plan.md`
- User guidance: `docs/guide.md`
- Compatibility matrix: `docs/compatibility.md`
- Shared tests:
  `multiclouddb-conformance/.../us28/PatchConformanceTest.java`
- Cosmos implementation:
  `multiclouddb-provider-cosmos/.../CosmosProviderClient.java`
- DynamoDB implementation:
  `multiclouddb-provider-dynamo/.../DynamoProviderClient.java`

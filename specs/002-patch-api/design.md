# Portable PATCH API Design

| Metadata | Value |
|---|---|
| Status | Implemented by `feat/patch-api` / PR #95 |
| Providers | Cosmos DB and DynamoDB |
| Deferred | Spanner |
| Updated | 2026-08-20 |

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

![Portable PATCH request workflow](images/patch-request-workflow.png)

*Figure 1. Shared validation routes the request to one native write or rejects
an unsupported provider before I/O.*

## 2. Portable contract

All operations in one call are atomic: either all changes are applied or none
are applied. The target document must already exist.

| Operation | Behavior |
|---|---|
| `SET` | Create or overwrite a field. A nested parent must already exist. |
| `REPLACE` | Replace an existing field; otherwise `NOT_FOUND`. |
| `REMOVE` | Remove an existing field; otherwise `NOT_FOUND`. |
| `INCREMENT` | Atomically add to an existing number. |

Shared validation applies before provider dispatch:

1. maximum 10 operations;
2. absolute JSON Pointer paths;
3. no array indexes;
4. no `~` escapes;
5. no duplicate, case-only alias, or overlapping paths;
6. no key, TTL, `data`, or `_`-prefixed fields;
7. signed 64-bit integral values, deltas, and results;
8. portable fractional values within the range described below;
9. maximum 399 KB (408,576-byte) serialized request envelope;
10. whole-number `INCREMENT` deltas only;
11. no patch on a document carrying an SDK-managed expiry.

Rules 7 and 8 apply to the value written by `SET` and `REPLACE`, including
numbers nested inside an object or array operand. Rule 7 also bounds an
`INCREMENT` delta and its result; rule 10 restricts that delta further.

### Why these limits?

Every rule exists to stop one portable call from behaving differently on
different providers. Most of them trace back to a handful of documented
provider facts, listed here so the rules below can refer to them without
assuming prior Cosmos DB or DynamoDB knowledge.

| Fact | What the provider does | Source |
|---|---|---|
| **F1** | Cosmos DB caps a single-document patch at **10 operations**. DynamoDB has no operation-count cap. | [Partial document update](https://learn.microsoft.com/en-us/azure/cosmos-db/partial-document-update#supported-modes) |
| **F2** | Cosmos DB stores documents as plain **JSON**, so every number is an IEEE-754 binary64 `double`. Whole numbers are exact only up to `2^53 - 1`; beyond that a value is rounded to the nearest representable `double`. | [Resource model](https://learn.microsoft.com/en-us/azure/cosmos-db/resource-model) |
| **F3** | DynamoDB stores numbers as **exact decimals** with up to 38 digits of precision, and cannot represent a non-zero magnitude outside `1E-130` to `9.9999999999999999999999999999999999999E+125`. | [Data types](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/HowItWorks.NamingRulesDataTypes.html) |
| **F4** | A DynamoDB item may not exceed **400 KB**. | [Data types](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/HowItWorks.NamingRulesDataTypes.html) |
| **F5** | Cosmos DB owns property names beginning with `_` (`_rid`, `_etag`, `_ts`, `_self`); the service generates them. | [Resource model](https://learn.microsoft.com/en-us/azure/cosmos-db/resource-model) |
| **F6** | JSON Pointer requires decoding `~1` to `/` **before** `~0` to `~`. The spec calls the other order incorrect, and native path dialects do not all follow it. | [RFC 6901 §4](https://www.rfc-editor.org/rfc/rfc6901.txt) |
| **F7** | Cosmos DB's item `ttl` is a **relative** countdown measured from `_ts`, the timestamp of the item's last write. A native patch advances `_ts`, which restarts the countdown. | [Time to Live](https://learn.microsoft.com/en-us/azure/cosmos-db/nosql/time-to-live) |
| **F8** | DynamoDB's TTL attribute holds an **absolute** Unix epoch timestamp. An `UpdateItem` that does not name the attribute leaves it unchanged. | [Time to Live](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/TTL.html) |

The key consequence of **F2** and **F3** together: DynamoDB keeps more numeric
precision than Cosmos DB. Any number the SDK accepts must therefore be one that
*both* providers return unchanged, or the same call would store different data
depending on where it ran.

| # | Rule | Why it exists |
|---|---|---|
| 1 | **Operation count** — at most 10 per call, exposed as `MulticloudDbClient.MAX_PATCH_OPERATIONS`. | By **F1** an 11th operation succeeds on DynamoDB and fails on Cosmos DB. Applying the lower cap everywhere means a patch that works on one provider always works on the other. Checking before dispatch also rejects an over-limit call without spending a provider request. |
| 2 | **Absolute JSON Pointer paths** — every path starts with `/` and names a field from the document root. | There is no portable notion of a "current position" inside a document, so a relative path would have to be resolved against provider-specific state. Anchoring at the root makes one path string mean exactly one field on every provider. |
| 3 | **No array indexes** — a purely numeric segment such as `/tags/0` is rejected. | Cosmos DB and DynamoDB expose different insert, replace, and append semantics for array elements, and a future provider may not address array elements at all. Rather than adopt one provider's behaviour and let the others diverge silently, the SDK rejects the whole class and asks you to `SET` the entire array. |
| 4 | **No `~` escapes** — any path containing `~` is rejected. | By **F6** the decoding order matters: a path like `/a~01b` resolves to one field name under the correct order and a different one otherwise. Because native dialects disagree, the same portable path could address two different fields. `~` is rare in field names, so rejecting it outright is cheaper than guessing. |
| 5 | **No duplicate, case-only alias, or overlapping paths** — two operations may not target the same field, names differing only by case, or a field and one of its own ancestors. | Native engines disagree on whether the second operation sees the first one's result, so an overlapping pair would produce a provider-dependent document. Case-only variants are folded together because some providers compare field names without regard to case, which would silently turn `/Total` and `/total` into exactly that conflicting pair. Siblings such as `/a/b` and `/a/c` do not overlap and are allowed. |
| 6 | **No reserved fields** — `id`, `partitionKey`, `sortKey`, `ttl`, `ttlExpiry`, `data`, and any name starting with `_` (matched without regard to case). | The SDK injects `id` / `partitionKey` / `sortKey` from `MulticloudDbKey`, so patching them would desynchronise the document from the key used to find it. `ttl` and `ttlExpiry` back row-level expiry, which Cosmos DB and DynamoDB spell and interpret differently, and `data` is Spanner's internal document-metadata column. By **F5** the `_` prefix belongs to Cosmos DB. Patching any of these would corrupt SDK state or quietly mean something different per provider. |
| 7 | **Signed 64-bit integral domain** — whole numbers, deltas, and `INCREMENT` results must fit `-2^63` to `2^63 - 1`. | This is the widest whole-number range every provider stores and returns unchanged. By **F3** DynamoDB could hold more digits, but by **F2** Cosmos DB rounds anything wider to the nearest `double`. Writing `2^63` would store `9223372036854775808` on DynamoDB and `9223372036854776000` on Cosmos DB — one call, two different documents. Rejecting it up front keeps the stored result identical everywhere. |
| 8 | **Portable fractional domain** — `0`, or a magnitude from `1E-130` up to about `9.007E15` (`2^53 - 1`, exactly `9007199254740991`). | The floor is DynamoDB's smallest non-zero magnitude (**F3**); anything smaller cannot be stored there at all. The ceiling is the largest integer binary64 represents exactly (**F2**), so within it Cosmos DB and DynamoDB agree on the value they received and the SDK can hand both providers the same normalized number. |
| 9 | **Request envelope** — at most 399 KB (`408,576` bytes) serialized. | By **F4** DynamoDB's 400 KB item limit is the lowest provider ceiling. The SDK reserves 1 KB for provider-injected fields and representation overhead, then applies the remainder uniformly so an oversized patch fails the same way on every provider instead of only on DynamoDB. |
| 10 | **Whole-number `INCREMENT` deltas** — a fractional delta is `INVALID_REQUEST`. | Rules 7 and 8 bound the numbers the SDK *sends*; this one bounds the arithmetic the *provider* performs. By **F3** DynamoDB adds in exact decimal, and by **F2** Cosmos DB adds in binary64, so `0.1` incremented by `0.2` stores `0.3` on one and `0.30000000000000004` on the other. The SDK cannot reconcile that without reading the current value first, which would forfeit the atomicity `INCREMENT` exists for. Fractional *values* stay legal — `SET` and `REPLACE` store the operand verbatim and perform no arithmetic. Whole-valued floating deltas normalize to integers, so `increment(path, 1.0)` is accepted and behaves exactly like `increment(path, 1)`. |
| 11 | **No patch on a TTL-bearing document** — `UNSUPPORTED_CAPABILITY`, raised before any mutation. | By **F7** Cosmos DB cannot patch such an item without restarting its expiry countdown, so it must reject. By **F8** DynamoDB *could* preserve the expiry — but then the same portable call would keep the original expiry on one provider and extend it on the other. DynamoDB is constrained up to reject as well. Rewrite the document with `upsert(...)` when you need to modify it. |

#### Why rules 7 and 8 are separate

They look redundant — one caps at `2^63 - 1`, the other at `2^53 - 1` — but they
answer different questions:

- **Rule 7** asks *what whole number can every provider store and return
  unchanged?* The answer is the signed 64-bit range.
- **Rule 8** asks *what decimal survives normalization to a `double` without
  changing value?* The answer is `2^53 - 1`.

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
400 KB item limit (**F4**), less 1 KB reserved for provider-injected fields and
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
| DynamoDB | exact decimal (**F3**): `0.7383633795947941 + 3` = `3.7383633795947941`, parsed back into a `double` | `3.738363379594794` |
| Cosmos DB | binary64 (**F2**): `double(0.7383633795947941) + 3`, rounded once | `3.7383633795947944` |

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

## 3. Cosmos DB

### Request flow

1. Validate the portable request.
2. Translate operations into `CosmosPatchOperations`.
3. Add one server-side filter predicate.
4. Execute one `patchItem`.

The predicate checks:

- required paths and nested parents exist;
- increment targets are numeric;
- integral increment results stay within signed 64-bit range;
- the item does not contain SDK-managed `ttl`.

There is no success-path read and no item-wide `If-Match` ETag. The path-scoped
predicate allows concurrent updates to unrelated fields.

### Failure classification

Cosmos may return HTTP 412 or an untyped HTTP 400 for stored-state failures.
After such a rejection, the adapter uses a session-token point read to classify
the result:

| State | Error |
|---|---|
| Document or required path missing | `NOT_FOUND` |
| Nonnumeric target or integral overflow | `INVALID_REQUEST` |
| SDK-managed `ttl` present | `UNSUPPORTED_CAPABILITY` |
| Condition failed but current state proves no cause | `CONFLICT` |

### Provider differences

- Cosmos DB would evaluate a fractional increment in IEEE-754 binary64
  (**F2**), which is why rule 10 rejects fractional deltas before dispatch.
- Native patch advances `_ts` (**F7**), so an item with SDK-managed relative
  `ttl` is rejected by the atomic filter predicate — its expiry cannot be
  preserved.

## 4. DynamoDB

### Request flow

1. Validate the portable request.
2. Compile one `UpdateExpression`.
3. Add one `ConditionExpression`.
4. Execute one `UpdateItem`.

The condition checks:

- the document exists;
- required paths and nested parents exist;
- integral increment results stay within signed 64-bit range.

Caller field names use expression-name placeholders, so reserved words and
special characters remain safe.

### Failure classification

The request sets
`ReturnValuesOnConditionCheckFailure.ALL_OLD`. When the condition rejects the
update, DynamoDB attaches the item as it existed immediately before the
attempted write to the `ConditionalCheckFailedException`.

`ALL_OLD` is failure evidence, not a successful-update response or a history
lookup. Because the condition failed, no mutation occurred, so this image is
also the current unchanged item at the time of rejection. It normally avoids a
separate `GetItem`.

This matters because one `ConditionExpression` checks several rules, while the
native exception only reports that the overall condition failed. The SDK
inspects the returned image to identify the portable error:

| State | Error |
|---|---|
| Document or required path missing | `NOT_FOUND` |
| Nonnumeric target or integral overflow | `INVALID_REQUEST` |
| Condition failed but the image proves no cause | `CONFLICT` |

If the service, emulator, or exception does not include the `ALL_OLD` image,
the adapter uses one strongly consistent `GetItem` as a rejection-only
fallback. A successful PATCH never performs this read.

### Provider differences

- DynamoDB would evaluate a fractional increment in exact decimal `N`
  (**F3**) — a different result from Cosmos DB for the same call, which is why
  rule 10 rejects the delta on both.
- `UpdateItem` never writes `ttlExpiry`, so DynamoDB alone *could* preserve an
  existing absolute expiry (**F8**). The `ConditionExpression` adds
  `attribute_not_exists(ttlExpiry)` anyway, so rule 11 holds identically here.

![Rejected PATCH classification workflow](images/patch-rejection-workflow.png)

*Figure 2. Cosmos and DynamoDB use different provider evidence but normalize
the rejection to the same portable error categories. DynamoDB's `ALL_OLD` is
the pre-write item attached to the failed conditional request.*

## 5. Capability matrix

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

## 6. Concurrency and retry behavior

Both providers evaluate increments on the server, so concurrent increments do
not lose updates.

An SDK-reported PATCH `CONFLICT` is safe to retry because the conditional write
was rejected and no operation was applied.

PATCH does not provide an idempotency token. Applications must not blindly
replay an `INCREMENT` after an ambiguous transport failure because the original
request may already have committed.

## 7. Cost expectations

PATCH is a latency, payload, and concurrency optimization, not a billing
guarantee.

| Provider | Successful request | Rejection-only work |
|---|---|---|
| Cosmos DB | One `patchItem` | Point read for HTTP 412 or untyped 400 classification |
| DynamoDB | One `UpdateItem` | Point read only when the failed response omits `ALL_OLD` |
| Spanner | No request | No request |

Actual RU/WCU usage depends on provider configuration, indexing, item shape,
and pricing.

## 8. Deferred Spanner implementation

The API and validation are already provider-neutral. A future Spanner
implementation only needs to:

1. implement the same SPI method;
2. satisfy the same error and atomicity contract;
3. pass the shared conformance suite;
4. update its capability declarations.

The detailed Spanner proposal is maintained separately on `spanner/patch` in
`specs/002-patch-api/spanner-design.md`.

## 9. References

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

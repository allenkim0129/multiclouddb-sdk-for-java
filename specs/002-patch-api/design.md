# Patch API — Design Review

**Status:** for team discussion
**Scope:** the portable `patch()` surface added on `feat/patch-api` (PR #95)
**Audience:** SDK maintainers and provider owners

This document describes how the portable patch API is implemented today
across Cosmos DB, DynamoDB, and Spanner, and answers three design
questions raised in review:

1. Read-modify-write vs. server-side patch.
2. How transient errors are retried, given that several patch
   instructions are not idempotent.
3. Whether an ETag precondition option exists.

Every claim below cites the code it came from. Statements about
*provider SDK default behaviour* that this repo does not itself
configure are marked **[unverified]** — they are the highest-value items
to confirm before the discussion.

---

## 1. TL;DR

| Question | Answer today |
|---|---|
| RMW or server-side patch? | **Mixed.** Cosmos and DynamoDB use native server-side partial writes. Spanner uses a read-modify-write, but *inside a Spanner read-write transaction*, so no non-transactional window is exposed. |
| Retry of non-idempotent ops? | **No SDK retry layer exists at all.** There is no retry/backoff code in the repo. Retries come only from the underlying provider SDKs' defaults, which this SDK never configures. `INCREMENT` is server-evaluated on Cosmos and DynamoDB, so a transport-level retry after a lost response **double-applies**. |
| ETag precondition option? | **No.** `OperationOptions` has no precondition field, and the Cosmos adapter deliberately sends no `If-Match` anywhere. `DocumentMetadata.version()` exposes an ETag on read, but only Cosmos populates it, and nothing can send it back. |

There is no `MOVE` operation in the portable contract — see §5.4.

---

## 2. The portable contract

`PatchOperation.Type` has exactly four members
(`PatchOperation.java:92-101`):

| Type | Target must exist | Effect |
|---|---|---|
| `SET` | no | Create the field, or overwrite it if present |
| `REPLACE` | yes | Overwrite; `NOT_FOUND` if absent |
| `REMOVE` | yes | Delete; `NOT_FOUND` if absent |
| `INCREMENT` | yes | Add a numeric delta; `NOT_FOUND` if absent, `INVALID_REQUEST` if non-numeric |

Paths are JSON Pointers rooted at the document. Array indices, `~`
escapes, empty segments, and key/reserved fields are rejected. A
multi-segment path is *nested* and requires `Capability.NESTED_PATCH`,
which Spanner does not declare
(`SpannerCapabilities.java:36` — `NESTED_PATCH_UNSUPPORTED`). Patch never
creates intermediate objects on any provider.

Overlapping paths within a single patch are rejected as
`INVALID_REQUEST` (`PatchConformanceTest.java:460`). **This matters for
the idempotency analysis below:** no two operations in one request can
address the same field, so the only ordering hazard is *between*
requests, not within one.

---

## 3. Design aspect 1 — RMW vs. server-side patch

### 3.1 Summary

| Provider | Mechanism | Server-side mutation? | Round trips (happy path) | Write amplification |
|---|---|---|---|---|
| **Cosmos DB** | `CosmosPatchOperations` + `container.patchItem` | **Yes**, native partial write | **2** when any op requires an existing path or is nested; **1** for a top-level `SET`-only patch | Patch-sized |
| **DynamoDB** | `UpdateItem` with `UpdateExpression` + `ConditionExpression` | **Yes**, native partial write | **1** (a second read happens only on condition failure) | Patch-sized |
| **Spanner** | `readWriteTransaction()` → `readRow` → rewrite envelope | **No** — RMW, but transactional | **1 transaction** (read + buffered mutation + commit) | **Whole-document** |

### 3.2 Cosmos DB — server-side, with a validating pre-read

`CosmosProviderClient.patch` (line 391) translates every operation into a
single `CosmosPatchOperations` batch that Cosmos applies atomically.

Before the write, `validatePatchState` (line ~600) issues a **point read**
when `needsPatchStateValidation(operations)` is true — that is, whenever
any operation is `REPLACE`, `REMOVE`, `INCREMENT`, or nested:

```java
document = container.readItem(cosmosId, partitionKey,
        new CosmosItemRequestOptions(), ObjectNode.class).getItem();
```

The read exists because Cosmos reports a missing patch target as an
untyped `400`, which would surface as `INVALID_REQUEST` where DynamoDB
and Spanner both report `NOT_FOUND`; it also detects integral-result
overflow, which Cosmos would not detect at all.

**This is not a read-modify-write.** The document read is used only to
*validate*; the mutation itself is still computed server-side. But it
does mean most real patches cost **two round trips on Cosmos and one on
DynamoDB** — an asymmetry worth acknowledging explicitly, since a
`SET`-only top-level patch is the only single-RTT shape.

### 3.3 DynamoDB — server-side, single request

`DynamoProviderClient.patch` (line 443) compiles the operation list into
one `UpdateExpression` (`SET ... REMOVE ...`) guarded by a
`ConditionExpression`. The condition always asserts
`attribute_exists(partitionKey)` — because a bare `UpdateItem` would
otherwise *create* the item — plus an `attribute_exists(path)` term per
operation that requires its target, plus a `BETWEEN` bound per integral
increment to catch overflow.

On `ConditionalCheckFailedException` the adapter requests an `ALL_OLD`
image (`returnValuesOnConditionCheckFailure`) and classifies the failure
from that before-image, so a missing target reports `NOT_FOUND` rather
than a bare `CONFLICT`. The extra read is paid **only on failure**.

### 3.4 Spanner — transactional read-modify-write

`SpannerProviderClient.patch` (line 374) is the outlier. It runs a
`readWriteTransaction()`, reads the `data` envelope, applies the
operations to an in-memory map, and buffers an update mutation:

```java
mutation.set(SpannerConstants.FIELD_DATA).to(
        serialiseDocument(documentFields(fields), OperationNames.PATCH));
```

Two consequences the team should weigh:

- **Correctness: this is safe.** The read and the write are in the same
  transaction, and the transaction runner re-executes the whole closure
  on `ABORTED`. There is no non-transactional RMW window. This is why
  `PatchOperation`'s javadoc can claim "no client-side read-modify-write
  window is exposed" (`PatchOperation.java:31-33`).
- **Cost: this is the expensive one.** The entire `data` envelope is
  re-serialised and rewritten on every patch, regardless of how small the
  patch is. Patch on Spanner therefore has roughly the same write cost as
  a full `update()` — the saving is in *round trips and conflict scope*,
  not bytes written. Both adapters' javadoc already warns "Patch is not a
  billing guarantee", but the Spanner case is structural, not
  configuration-dependent.

> **Open question 1.** Should `Capability` or the docs state the
> per-provider cost shape explicitly, so callers do not assume patch is
> universally cheaper than update? Today the warning is generic on all
> three.

---

## 4. Design aspect 2 — transient errors and non-idempotent instructions

This is the weakest area of the current design and deserves the most
discussion time.

### 4.1 There is no retry layer in this SDK

A search of the repository finds **no retry or backoff implementation**
anywhere in `main` sources — no retry policy class, no backoff helper, no
attempt loop around any provider call.

`MulticloudDbError` carries a `retryable` boolean
(`MulticloudDbError.java:23,59`), and each mapper computes it:

- Cosmos: `429, 449, 500, 502, 503 → true` (`CosmosErrorMapper.java:76-81`)
- DynamoDB: throttling exceptions, or any 5xx (`DynamoErrorMapper.java`)
- Spanner: delegates to `SpannerException.isRetryable()` (`SpannerErrorMapper.java:33`)

**But nothing in the SDK consumes that flag.** It is advisory metadata
handed to the caller. All actual retrying is done by the underlying
provider SDK, using its own defaults, which this repo never configures.

> **Open question 2.** Is "no retry layer, provider defaults only" the
> intended design? If yes it should be stated in the docs, because the
> safety of a patch depends entirely on behaviour the SDK does not
> control.

### 4.2 Idempotency of each operation

| Op | Idempotent effect? | Idempotent *reported outcome*? | Why |
|---|---|---|---|
| `SET` | ✅ yes | ✅ yes | Absolute value; re-applying is a no-op |
| `REPLACE` | ✅ yes | ⚠️ no | Absolute value, but a retry after success still sees the field present, so it succeeds — safe *unless* a concurrent writer removed it |
| `REMOVE` | ✅ yes | ❌ **no** | After a successful first attempt the field is gone, so the retry's existence precondition fails and the caller is told `NOT_FOUND` for an operation that actually succeeded |
| `INCREMENT` | ❌ **no** | ❌ no | Relative delta; re-applying adds twice |

### 4.3 Where the delta is computed decides retry safety

This is the crux:

| Provider | Increment evaluated | Retry of the same request |
|---|---|---|
| Cosmos | **Server-side** — `patchOps.increment(path, delta)` (`CosmosProviderClient.java:416-418`) | **Double-applies** |
| DynamoDB | **Server-side** — `SET x = x + :v` (`DynamoProviderClient.java`, `setClauses.add(pathExpr + " = " + pathExpr + " + " + placeholder)`) | **Double-applies** |
| Spanner | **Client-side, inside the transaction** — `addDelta(base, op.value())` where `base` was read in the same transaction | **Safe** — the runner re-executes the closure, re-reads, and recomputes |

Spanner is idempotent *by construction*: its automatic `ABORTED` retry
re-derives the result from freshly read state. Cosmos and DynamoDB are
not, because the delta is applied by the server to whatever value it
finds at the time.

Note that DynamoDB's `BETWEEN` overflow bound does **not** protect
against this. It is computed to leave room for one successful
application, so a duplicate application usually still satisfies it and
commits.

### 4.4 Three distinct retry sources, with different risk

It helps to separate them:

1. **Intra-transaction retry (Spanner `ABORTED`).** Handled by the
   transaction runner, re-reads state, invisible to the caller.
   **Safe today.**
2. **Transport-level retry inside the provider SDK** — a retry issued
   after a request was sent but the response was lost (timeout,
   connection reset, 5xx). The write may already have committed.
   **This is the dangerous one for `INCREMENT` on Cosmos and DynamoDB**,
   and the SDK neither configures nor disables it. **[unverified]** The
   exact default retry conditions for the Azure Cosmos Java SDK and AWS
   SDK v2 `UpdateItem` should be confirmed before the discussion — the
   risk is real in principle but its likelihood depends on those
   defaults.
3. **Caller-level retry** — an application seeing `THROTTLED` or
   `TRANSIENT_FAILURE` and calling `patch()` again. Unsafe for
   `INCREMENT` on all three providers, and turns an already-successful
   `REMOVE` into a spurious `NOT_FOUND` on all three.

### 4.5 Options to discuss

**A. Document it and do nothing.** Declare patch *at-least-once* for
`INCREMENT`, and tell callers to prefer `SET` with a
caller-computed value when exactly-once matters.
*Cheapest; leaves a real correctness trap in the public API.*

**B. Make `INCREMENT` conditional on the observed value (opt-in CAS).**
Cosmos already sends a filter predicate — adding a
`c["counter"] = <observed>` term is nearly free there, DynamoDB can add
the same to its `ConditionExpression`, and Spanner already reads the
value. A retry then fails cleanly as `CONFLICT` instead of
double-applying.
*Cost: it directly contradicts the current, deliberate design goal that
"N concurrent increments all land" (documented at
`CosmosProviderClient.java:352-358`). It would have to be opt-in per
request, which means a new option field and a new capability.*

**C. Suppress transport retries for increment-bearing patches.** Set an
explicit zero/one-attempt policy on the provider SDK call when the
operation list contains an `INCREMENT`, and surface the failure as
`TRANSIENT_FAILURE` with `retryable = false`.
*Keeps concurrent increments working; converts a silent double-count
into a visible "you decide" failure. Requires per-call retry
configuration on two provider SDKs.*

**D. Idempotency token.** None of the three providers offers a native
dedupe token for these operations, so the SDK would have to maintain its
own applied-request record — a stored side table and a format change.
*Highest cost; probably out of scope.*

**E. Fix the `REMOVE`-retry outcome separately.** Regardless of which
option is chosen for `INCREMENT`, consider whether a `REMOVE` whose
target is already absent should be `NOT_FOUND` or a success. The current
choice is defensible and consistent across all three providers, but it
makes `REMOVE` unsafe to retry blindly.

> **Open question 3.** Which of A–E, and is exactly-once `INCREMENT` a
> requirement or a nice-to-have for the first release?

### 4.6 Conformance gap

`PatchConformanceTest` has 40+ scenarios covering semantics, validation,
and error mapping — but **no concurrency or retry test**. Nothing asserts
what happens when the same patch is applied twice, and nothing exercises
a raced patch. The classification logic
(`classifyRacedPatchRejection` on Cosmos, `classifyPatchConditionFailure`
on DynamoDB) is therefore only unit-tested against synthesised
exceptions, never against a real race.

> **Open question 4.** Add a conformance test that applies the same
> `INCREMENT` twice and asserts the documented semantics — whichever
> semantics we choose in §4.5.

---

## 5. Design aspect 3 — ETag precondition

### 5.1 There is no precondition option

`OperationOptions` exposes exactly three fields
(`OperationOptions.java:20-24`):

```java
private final Duration timeout;
private final Integer ttlSeconds;
private final boolean includeMetadata;
```

There is no `ifMatch`, no `etag`, no `precondition`. `patch()` takes no
other parameter that could carry one.

### 5.2 Cosmos deliberately does not send `If-Match`

This is an explicit, documented decision rather than an oversight
(`CosmosProviderClient.java:340-349`):

> An `If-Match` ETag would fail on *any* concurrent mutation of the item,
> including one touching a completely unrelated field, so two threads
> patching disjoint fields would collide on Cosmos alone: DynamoDB's
> `attribute_exists(...)` condition and Spanner's auto-retried read-write
> transaction both let them through.

Instead Cosmos sends a **path-scoped** filter predicate
(`setFilterPredicate`) asserting only that the addressed paths exist.
`CosmosConstants.STATUS_PRECONDITION_FAILED` records the same rationale
and warns against reintroducing an ETag.

The reasoning is sound: an item-scoped ETag would have made Cosmos the
odd one out and broken the portability invariant. **But note what this
implies** — the SDK currently has *no* mechanism for item-scoped
optimistic concurrency on any operation, not just patch.

### 5.3 The read half exists; the write half does not

`DocumentMetadata.version()` is documented as the "provider-native
ETag/version string" (`DocumentMetadata.java:47-49`). Only **Cosmos**
populates it:

```
CosmosProviderClient.java:224:  metaBuilder.version(response.getETag());
```

DynamoDB and Spanner leave it `null`. So a caller can observe a version
on one provider, cannot observe it on the other two, and cannot send it
back anywhere. Half of an optimistic-concurrency primitive is exposed.

> **Open question 5.** Either finish it or hide it. Leaving
> `version()` populated on exactly one provider is itself a portability
> wart — a caller who builds on it silently gets no protection on
> DynamoDB and Spanner.

### 5.4 What a portable conditional write would cost

If the team wants `patch(..., ifMatch = version)`:

| Provider | Native support | Work required |
|---|---|---|
| Cosmos | ✅ native `If-Match` on item requests | Small — plumb an option through `CosmosItemRequestOptions` |
| DynamoDB | ❌ none | Maintain a synthetic version attribute: `ConditionExpression #v = :v` plus `SET #v = :v + 1` on **every** write path, not just patch |
| Spanner | ❌ none | A version column or commit timestamp maintained inside the existing read-write transaction — tractable, since patch is already transactional |

Two of the three need a **stored format change affecting every write
path**, which is breaking for existing data. Per this repo's portability
rules, a partial rollout is not an option: a conditional-write feature
supported on one provider and silently ignored on another is exactly the
silent divergence the conformance contract forbids. It would have to be
gated by a new capability (e.g. `CONDITIONAL_WRITE`) returning
`UNSUPPORTED_CAPABILITY` where unimplemented.

> **Open question 6.** Is item-scoped optimistic concurrency a roadmap
> item at all? If yes it is a cross-cutting feature in its own right, not
> a patch option — and §4.5 option B (path-scoped CAS) may deliver most
> of the practical value at a fraction of the cost.

---

## 6. Consolidated open questions

1. Should per-provider patch cost shape (Spanner rewrites the whole
   document; Cosmos usually costs 2 RTT) be documented or declared?
2. Is "no SDK retry layer, provider SDK defaults only" intentional and
   documented?
3. Which mitigation for non-idempotent `INCREMENT` — A (document),
   B (opt-in CAS), C (suppress transport retry), D (idempotency token),
   E (revisit `REMOVE` retry outcome)? Is exactly-once required for v1?
4. Add a concurrency/double-apply conformance test.
5. Finish or hide `DocumentMetadata.version()`, which only Cosmos
   populates.
6. Is item-scoped optimistic concurrency (`CONDITIONAL_WRITE`) on the
   roadmap, and is it a patch feature or a cross-cutting one?

Item to verify before the meeting: the default transport-retry behaviour
of the Azure Cosmos Java SDK and AWS SDK v2 for `patchItem` / `UpdateItem`
(§4.4 item 2). It determines whether question 3 is urgent or theoretical.

---

## 7. Evidence index

| Claim | Source |
|---|---|
| Four operation types | `multiclouddb-api/.../PatchOperation.java:92-101` |
| Overlapping paths rejected | `multiclouddb-conformance/.../us28/PatchConformanceTest.java:460` |
| Cosmos server-side patch | `multiclouddb-provider-cosmos/.../CosmosProviderClient.java:391-452` |
| Cosmos validating pre-read | `CosmosProviderClient.java` — `validatePatchState`, `needsPatchStateValidation` |
| Cosmos filter predicate, not `If-Match` | `CosmosProviderClient.java:325-349`; `CosmosConstants.java:133-152` |
| Cosmos server-side increment | `CosmosProviderClient.java:414-419` |
| DynamoDB `UpdateItem` + condition | `DynamoProviderClient.java:443-520` |
| DynamoDB server-side increment | `DynamoProviderClient.java` — `SET x = x + :v` |
| DynamoDB overflow bound | `DynamoProviderClient.java` — `addIntegralResultBounds` |
| DynamoDB before-image classification | `DynamoProviderClient.java` — `classifyPatchConditionFailure` |
| Spanner transactional RMW | `SpannerProviderClient.java:374-460` |
| Spanner client-side increment | `SpannerProviderClient.java` — `addDelta(base, op.value())` |
| Spanner whole-envelope rewrite | `SpannerProviderClient.java` — `mutation.set(FIELD_DATA)` |
| Spanner no `NESTED_PATCH` | `SpannerCapabilities.java:36` |
| `retryable` flag exists but unused | `MulticloudDbError.java:23,59`; no retry class in repo |
| Cosmos retryable status codes | `CosmosErrorMapper.java:76-81` |
| No precondition in options | `OperationOptions.java:20-24` |
| `version()` populated only by Cosmos | `DocumentMetadata.java:47-49`; `CosmosProviderClient.java:224` |

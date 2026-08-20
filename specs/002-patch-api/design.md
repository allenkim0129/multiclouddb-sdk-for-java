# Portable PATCH API - Cosmos DB and DynamoDB Design

| Metadata | Value |
|---|---|
| Status | Implemented by `feat/patch-api` / PR #95 |
| Updated | 2026-08-20 |
| Current providers | Cosmos DB and DynamoDB |
| Deferred provider | Spanner |
| Audience | SDK maintainers, provider owners, and reviewers |

## 1. Executive decision

The SDK exposes one provider-neutral field-level PATCH contract:

```java
client.patch(address, key, List.of(
        PatchOperation.replace("/status", "SHIPPED"),
        PatchOperation.set("/trackingNumber", "1Z999"),
        PatchOperation.increment("/revision", 1),
        PatchOperation.remove("/holdReason")));
```

PR #95 implements that contract with each supported provider's native,
server-side partial-write primitive:

- Cosmos DB: one conditional `CosmosContainer.patchItem(...)` on success;
- DynamoDB: one conditional `UpdateItem` on success;
- Spanner: explicit `UNSUPPORTED_CAPABILITY` before provider I/O.

The public API is not specialized for Cosmos DB or DynamoDB. A future Spanner
implementation can override the same SPI method and update its capability
declarations without changing application code.

## 2. Goals

The design has six primary goals:

1. **Atomicity:** every operation in one call succeeds or none succeeds.
2. **No application read-modify-write:** increments and field updates are
   evaluated by the provider, not from a document previously read by the SDK.
3. **Portable strictness:** missing documents and required fields produce the
   same error categories on every provider advertising PATCH.
4. **Concurrency safety:** concurrent server-side increments do not lose
   updates, and disjoint-field patches are not serialized by an item-wide
   version check.
5. **Explicit capability gaps:** nested paths, fractional arithmetic, and TTL
   preservation are declared rather than silently emulated.
6. **Future-provider compatibility:** Spanner and other providers can implement
   the same contract without changing the API.

## 3. Non-goals

The initial PATCH API does not provide:

- array-element addressing;
- JSON Pointer `~0` or `~1` escape handling;
- an ETag, version, or compare-and-set option;
- a caller-supplied idempotency key;
- automatic creation of a missing document;
- automatic creation of missing intermediate objects;
- a guaranteed billing or write-capacity reduction;
- a Spanner implementation in PR #95.

## 4. Public contract

### 4.1 Operations

`PatchOperation` exposes four operation types:

| Operation | Target must exist | Portable behavior |
|---|---|---|
| `SET` | No | Create the target field or overwrite it. A nested parent must already exist. |
| `REPLACE` | Yes | Replace the existing field; otherwise `NOT_FOUND`. |
| `REMOVE` | Yes | Remove the existing field; otherwise `NOT_FOUND`. |
| `INCREMENT` | Yes | Atomically add a numeric delta; missing is `NOT_FOUND`, nonnumeric is `INVALID_REQUEST`. |

Values are normalized into detached JSON-compatible snapshots when a
`PatchOperation` is constructed. Mutating the caller's source map or list after
construction cannot change the request sent to a provider.

### 4.2 Atomicity

One call is one atomic provider operation:

- Cosmos DB sends one `patchItem` containing all operations.
- DynamoDB sends one `UpdateItem` containing one combined update expression.

The result is all-or-nothing. A failed condition does not apply a partial
operation list.

### 4.3 Existing-document requirement

PATCH never creates a document.

- Cosmos DB naturally returns not found when the key does not exist.
- DynamoDB adds `attribute_exists(partitionKey)` because an unconstrained
  `UpdateItem` would otherwise create an item.

Applications use `create()` or `upsert()` when creation is intended.

### 4.4 Return value

`patch()` returns `void`. Applications that need the post-image issue a read.
The provider response is used for diagnostics and error classification, not as
a provider-specific result type exposed through the public API.

## 5. Shared validation

Supported adapters invoke the shared `PatchValidator` immediately after their
lifecycle guard and before building a provider request.

| Rule | Portable outcome | Rationale |
|---|---|---|
| At least one operation | `INVALID_REQUEST` | An empty write has no portable provider meaning. |
| Maximum 10 operations | `INVALID_REQUEST` | Cosmos DB's native per-request limit is the common limit. |
| Absolute JSON Pointer path | `INVALID_REQUEST` | Every path starts with `/`. |
| No empty path segment | `INVALID_REQUEST` | Provider path dialects interpret empty segments differently. |
| No `~` escape | `INVALID_REQUEST` | Native path decoders are not equivalent. |
| No numeric-only segment | `INVALID_REQUEST` | Array mutation semantics differ. |
| Disjoint paths | `INVALID_REQUEST` | Duplicate, case-only alias, and ancestor/descendant paths could be evaluated differently. |
| No SDK-owned root field | `INVALID_REQUEST` | Key, TTL, `data`, and `_`-prefixed fields are provider or SDK metadata. |
| Portable numeric delta | `INVALID_REQUEST` | Prevents provider numeric-domain divergence. |
| Maximum 399 KB request envelope | `INVALID_REQUEST` | Bounds every operation type, path, and optional value before dispatch. |

The deterministic request envelope limit is 408,576 bytes. It is not a promise
that the provider will accept the resulting post-image; native item-size limits
still apply.

## 6. Capability model

| Capability | Cosmos DB | DynamoDB | Spanner in PR #95 |
|---|---|---|---|
| `PATCH` | Supported | Supported | Unsupported |
| `NESTED_PATCH` | Supported | Supported | Unsupported |
| `EXACT_FRACTIONAL_INCREMENT` | Unsupported | Supported | Unsupported |
| `PATCH_PRESERVES_TTL` | Unsupported | Supported | Unsupported |

Capability meaning:

- `PATCH` gates the operation itself.
- `NESTED_PATCH` independently gates paths with more than one segment.
- `EXACT_FRACTIONAL_INCREMENT` describes accumulated fractional arithmetic; it
  does not reject an otherwise portable fractional delta.
- `PATCH_PRESERVES_TTL` describes whether an existing SDK-managed absolute
  expiry remains unchanged.

Unsupported PATCH fails through the SPI default implementation before any
provider request. No adapter silently falls back to a non-transactional
read-modify-write.

## 7. Cosmos DB design

### 7.1 Native request

The Cosmos adapter compiles every operation into one
`CosmosPatchOperations` object:

| Portable operation | Cosmos operation |
|---|---|
| `SET` | `set(path, value)` |
| `REPLACE` | `set(path, value)` plus an atomic path-existence predicate |
| `REMOVE` | `remove(path)` plus an atomic path-existence predicate |
| integral `INCREMENT` | `increment(path, longDelta)` |
| fractional `INCREMENT` | `increment(path, doubleDelta)` |

`REPLACE` intentionally uses native `set` plus the shared predicate. This makes
the existence rule explicit and independent of provider-specific missing-path
error wording.

### 7.2 Atomic filter predicate

Every request carries one path-scoped filter predicate. It includes:

- `NOT IS_DEFINED(c["ttl"])`;
- `IS_DEFINED(...)` for a strict target;
- `IS_DEFINED(...)` for the parent of a nested `SET`;
- `IS_NUMBER(...)` for `INCREMENT`;
- a `BETWEEN` bound for an integral increment's current value.

The bound is calculated from the signed 64-bit delta:

```text
Long.MIN_VALUE - delta <= current <= Long.MAX_VALUE - delta
```

This validates result overflow atomically with the increment. A concurrent
writer cannot move the value outside the portable range between a client read
and the write because no client pre-read is used.

### 7.3 Path rendering

Filter paths use quoted property access:

```sql
c["address"]["city"]
```

Every segment is escaped as a string literal. Caller field names cannot escape
the accessor or alter the predicate.

### 7.4 Concurrency choice: no ETag

The adapter does not send an item-wide `If-Match` ETag.

An ETag would reject a patch whenever any field changed, including a field not
addressed by the request. DynamoDB's path conditions permit disjoint-field
updates, so an ETag would create provider-specific contention.

The Cosmos predicate therefore guards only:

- the addressed paths;
- increment type and range;
- the document-wide SDK-managed TTL incompatibility.

Concurrent in-range increments remain server-side atomic. Concurrent writes to
an unrelated application field do not falsify the path predicate.

Conditional version writes are a separate, cross-provider feature and are
deferred from PATCH v1.

### 7.5 Error classification

A successful Cosmos PATCH is one request. There is no validating pre-read.

Cosmos can report a failed filter as HTTP 412 and some native target-state
failures as an untyped HTTP 400. The adapter classifies those failures using a
point read carrying the rejecting response's session token when available:

| Observed state | Portable category |
|---|---|
| Document missing | `NOT_FOUND` |
| Required target or nested parent missing | `NOT_FOUND` |
| Increment target is not numeric | `INVALID_REQUEST` |
| Integral result is outside signed 64-bit range | `INVALID_REQUEST` |
| Item contains SDK-managed `ttl` | `UNSUPPORTED_CAPABILITY` |
| Current state satisfies every portable predicate after a 412 | `CONFLICT` |
| An untyped 400 cannot be proved as a portable state failure | Native mapped category |

If the classification read itself fails, the adapter preserves the
authoritative rejected-write classification rather than inventing
`NOT_FOUND`.

### 7.6 TTL behavior

Cosmos item TTL is relative to `_ts`, and a native patch advances `_ts`.
Patching an item carrying the SDK-managed `ttl` field would therefore restart
its countdown.

Cosmos declares `PATCH_PRESERVES_TTL` unsupported and rejects such an item
atomically. It does not silently extend the expiry.

An externally configured container default TTL is not represented by an item
`ttl` field and cannot be inferred from the patch request. Applications that
require a fixed absolute expiry should not combine that external policy with
Cosmos PATCH.

### 7.7 Arithmetic

Cosmos evaluates fractional increments as IEEE-754 binary64. Integral
increments remain exact within the signed 64-bit portable range.

It therefore declares `EXACT_FRACTIONAL_INCREMENT` unsupported while continuing
to accept every fractional delta inside the shared portable input domain.

## 8. DynamoDB design

### 8.1 Native request

The DynamoDB adapter compiles the operation list into one `UpdateItem`:

```text
SET <set/replace/increment clauses>
REMOVE <remove clauses>
```

| Portable operation | DynamoDB update expression |
|---|---|
| `SET` | `SET path = :value` |
| `REPLACE` | `SET path = :value` plus `attribute_exists(path)` |
| `REMOVE` | `REMOVE path` plus `attribute_exists(path)` |
| `INCREMENT` | `SET path = path + :delta` plus existence and range conditions |

Every caller-supplied path segment is represented by an expression-attribute
name such as `#n0_0`. This avoids DynamoDB reserved-word collisions and
expression injection.

### 8.2 Condition expression

The request condition contains:

- `attribute_exists(partitionKey)` to prevent implicit item creation;
- `attribute_exists(path)` for `REPLACE`, `REMOVE`, and `INCREMENT`;
- `attribute_exists(parentPath)` for a nested `SET`;
- `BETWEEN :lower AND :upper` for an integral increment.

The condition and update expression are evaluated atomically by DynamoDB.

### 8.3 Error classification

The request asks DynamoDB for
`ReturnValuesOnConditionCheckFailure.ALL_OLD`.

The rejected old image normally distinguishes:

| Observed state | Portable category |
|---|---|
| Empty image / item missing | `NOT_FOUND` |
| Required target or parent missing | `NOT_FOUND` |
| Increment target is not a number | `INVALID_REQUEST` |
| Integral result is outside signed 64-bit range | `INVALID_REQUEST` |
| Condition failed but the image proves no deterministic cause | `CONFLICT` |

If DynamoDB omits the old image, the adapter performs one strongly consistent
`GetItem` as a rejection-only fallback. If that read fails, the adapter reports
`CONFLICT` rather than inventing a missing document.

A successful DynamoDB PATCH remains one `UpdateItem`.

### 8.4 TTL behavior

DynamoDB stores the SDK-managed absolute expiry in `ttlExpiry`. The update
expression never writes that attribute, so an existing expiry remains
unchanged.

DynamoDB therefore declares `PATCH_PRESERVES_TTL` supported.
`OperationOptions.ttlSeconds()` is still ignored by PATCH and cannot create or
replace an expiry.

### 8.5 Arithmetic

DynamoDB evaluates `N` arithmetic as exact decimal arithmetic with up to 38
significant digits. It declares `EXACT_FRACTIONAL_INCREMENT` supported.

Accepted wide integral values written by `SET` remain readable. Values outside
Java `long` range are mapped to a wide integer JSON representation rather than
being truncated or rejected during read-back.

## 9. Cross-provider parity matrix

| Behavior | Cosmos DB | DynamoDB |
|---|---|---|
| Success-path native requests | One `patchItem` | One `UpdateItem` |
| Client pre-read | None | None |
| Atomic operation list | Native patch batch | Single update expression |
| Missing document | Native 404 or rejected-state classification -> `NOT_FOUND` | Item-existence condition -> `NOT_FOUND` |
| Missing strict path | Atomic filter -> classified `NOT_FOUND` | Path-existence condition -> `NOT_FOUND` |
| Nested `SET` missing parent | Atomic parent predicate -> `NOT_FOUND` | Parent-existence condition -> `NOT_FOUND` |
| Nonnumeric increment | Atomic type/native rejection -> `INVALID_REQUEST` | Rejected old image -> `INVALID_REQUEST` |
| Integral overflow | Atomic `BETWEEN` predicate -> `INVALID_REQUEST` | Atomic `BETWEEN` condition -> `INVALID_REQUEST` |
| Concurrent increment | Server-side atomic | Server-side atomic |
| Disjoint field concurrency | No item-wide ETag | No item-wide version condition |
| Fractional accumulation | IEEE-754 binary64 | Exact decimal `N` |
| Existing SDK-managed TTL | Rejected as unsupported | Absolute expiry preserved |
| Rejection-only extra read | Session-token point read for 412/untyped 400 | Only if `ALL_OLD` is absent |

## 10. Error contract

| Category | PATCH meaning |
|---|---|
| `INVALID_REQUEST` | Invalid request shape, invalid path, nonportable delta, nonnumeric target, or integral-result overflow |
| `NOT_FOUND` | Target document, required field, or required nested parent does not exist |
| `UNSUPPORTED_CAPABILITY` | Provider lacks PATCH/nested support, or cannot preserve an existing SDK-managed TTL |
| `CONFLICT` | An atomic condition rejected the write but observed state no longer proves a deterministic cause |
| Provider mapped category | Authentication, throttling, quota, transport, service, and other native failures |

A PATCH `CONFLICT` represents a rejected conditional write: no operation in
the list was applied, so retrying that conflict is safe.

This is different from blindly replaying an operation after an ambiguous
transport failure. PATCH v1 has no idempotency token:

- replaying `SET` or `REPLACE` writes the same value;
- replaying `REMOVE` can change a success into `NOT_FOUND`;
- replaying `INCREMENT` applies the delta again.

Applications must not interpret generic retryable provider metadata as an
exactly-once guarantee for relative operations.

## 11. Numeric domain

`PatchNumericDomain` defines one portable input domain.

### 11.1 Integral delta

- normalized to `long`;
- must fit signed 64-bit;
- result must remain between `Long.MIN_VALUE` and `Long.MAX_VALUE`;
- result range is enforced atomically by both providers.

A whole-valued `Double` or `BigDecimal`, such as `1.0`, normalizes to the
integral path and receives the same result bound as `1L`.

### 11.2 Fractional delta

- finite;
- zero or magnitude at least `1E-130`;
- magnitude no greater than 9,007,199,254,740,991;
- decimal form must round-trip through the normalized IEEE-754 `double`.

The delta is portable, but repeated accumulated results can differ:

```text
seed 0.1, increment 0.2
Cosmos DB -> 0.30000000000000004
DynamoDB  -> 0.3
```

Applications requiring identical financial totals should use integral minor
units, such as cents, and check provider capabilities.

## 12. JSON null and missing fields

JSON null is a present value, not a missing path.

- `REPLACE` may replace a present null.
- `REMOVE` may remove a present null.
- `INCREMENT` on null is `INVALID_REQUEST`, not `NOT_FOUND`.
- `SET` may write null.

Provider predicates and failure classifiers must preserve this distinction.

## 13. Cost and performance

PATCH is a latency, payload, and concurrency optimization. It is not a billing
guarantee.

| Provider | Success path | Rejection path | Cost caveat |
|---|---|---|---|
| Cosmos DB | One native `patchItem` | Point read after a 412 or untyped 400 requiring classification | RU charge depends on item shape, indexing, operation mix, and account configuration. |
| DynamoDB | One native `UpdateItem` | Normally classified from `ALL_OLD`; strongly consistent read only if absent | Capacity depends on native item and table rules, not merely the number of changed fields. |
| Spanner | No request | No request | PATCH is unsupported in PR #95. |

The design intentionally avoids an authoritative success-path read on either
supported provider.

## 14. Observability

Successful provider responses flow through the existing diagnostics logging:

- Cosmos activity ID, request charge, session information, and status;
- DynamoDB request ID and consumed capacity.

When the adapter reclassifies a rejected native request, the portable error
retains available provider diagnostics in `providerDetails`.

## 15. Security and robustness

- Cosmos predicate paths are emitted as escaped quoted accessors.
- DynamoDB paths use expression-attribute-name placeholders.
- Values are bound through provider SDK value objects, not interpolated SQL or
  expression literals.
- Complete request size is validated before dispatch.
- Unsupported adapters fail before provider I/O.
- Follow-up classification failures never become success-shaped fallbacks.

## 16. Conformance strategy

`PatchConformanceTest` is provider-neutral. A provider may declare PATCH only
after it passes the shared behavior.

Coverage includes:

- capability declarations and unsupported-provider pre-dispatch failure;
- all four operations;
- multi-operation atomicity;
- missing document and missing strict target;
- nested paths and missing parents;
- explicit null behavior;
- disjoint and overlapping paths;
- array, escape, reserved-field, and size validation;
- numeric input floor and ceiling;
- integral result bounds;
- wide integer read-back;
- fractional capability behavior;
- TTL preservation or rejection;
- query visibility after patch;
- concurrent increments.

Provider-specific unit tests separately pin:

- Cosmos predicate construction and rejection classification;
- DynamoDB update/condition compilation and old-image classification;
- capability notes and matrix entries.

The remaining live/emulator certification tracked by T192 should be completed
before release claims are finalized.

## 17. Deferred Spanner implementation

Spanner remains a future provider implementation, not an exception to this
design.

The preserved experimental branch is `spanner/patch`. Its supplementary design
targets one server-side GoogleSQL DML update using `JSON_SET` / `JSON_REMOVE`
against the internal document envelope. The experimental Java transactional
read-modify-write is evidence and fallback work, not the recommended final
success path.

The Spanner implementation must preserve:

- this public API;
- strict missing-path semantics;
- atomic result bounds;
- explicit capabilities;
- portable error categories;
- shared conformance coverage.

It must not require changes to Cosmos DB or DynamoDB behavior.

## 18. Alternatives considered

### 18.1 Read-modify-write in the SDK

Rejected for Cosmos DB and DynamoDB. It adds a success-path read, increases
latency, and creates a lost-update window unless wrapped in a provider-native
transaction.

### 18.2 Relax `REMOVE` on a missing path

Rejected. DynamoDB's native no-op is more permissive than Cosmos DB. The SDK
constrains DynamoDB to strict `NOT_FOUND` instead of weakening the shared
contract.

### 18.3 Item-wide ETag on Cosmos

Rejected for PATCH v1. It would make unrelated concurrent field changes
conflict on Cosmos while DynamoDB allows them.

### 18.4 Client-side exact decimal accumulation

Rejected. Reading, adding, and replacing client-side would give up atomic
server-side increment semantics. The arithmetic difference is declared through
`EXACT_FRACTIONAL_INCREMENT`.

### 18.5 Silent full-document fallback for Spanner

Rejected. A hidden fallback would change latency, write shape, and concurrency
semantics without a capability signal.

## 19. Implementation map

| Concern | Primary source |
|---|---|
| Public API | `multiclouddb-api/.../MulticloudDbClient.java` |
| Operation model | `multiclouddb-api/.../PatchOperation.java` |
| Numeric domain | `multiclouddb-api/.../PatchNumericDomain.java` |
| Shared validation | `multiclouddb-api/.../internal/PatchValidator.java` |
| SPI capability gate | `multiclouddb-api/.../spi/MulticloudDbProviderClient.java` |
| Cosmos implementation | `multiclouddb-provider-cosmos/.../CosmosProviderClient.java` |
| DynamoDB implementation | `multiclouddb-provider-dynamo/.../DynamoProviderClient.java` |
| Capability matrix | `CosmosCapabilities.java`, `DynamoCapabilities.java`, `SpannerCapabilities.java` |
| Shared behavior | `multiclouddb-conformance/.../us28/PatchConformanceTest.java` |
| Requirements | `specs/001-clouddb-sdk/spec.md` FR-181 through FR-192 |
| Planning decision | `specs/001-clouddb-sdk/plan.md` Portable Field-Level Patch addendum |

## 20. Final architecture

The released PATCH architecture is:

```text
Application
  -> provider-neutral PatchOperation list
  -> capability gate and shared validation
  -> provider-native atomic compiler
       Cosmos DB: conditional patchItem
       DynamoDB: conditional UpdateItem
       Spanner: unsupported before I/O
  -> normalized diagnostics and errors
```

This design gives Cosmos DB and DynamoDB equivalent portable behavior while
leaving a clean extension point for a separate Spanner implementation.

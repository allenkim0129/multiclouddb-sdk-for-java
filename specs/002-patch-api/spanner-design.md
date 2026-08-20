# Spanner PATCH - Deferred Implementation Design

| Metadata | Value |
|---|---|
| Status | Proposed design for a separate Spanner pull request |
| Updated | 2026-08-20 |
| Current PATCH PR | `feat/patch-api` / PR #95 |
| Canonical PATCH design | [`design.md`](design.md) |
| Preserved Spanner branch | `spanner/patch` |
| Audience | SDK maintainers, Spanner provider owners, and reviewers |

## 1. Executive decision

This document supplements the canonical provider-neutral design in
[`design.md`](design.md). It covers only the deferred Spanner implementation.

PR #95 ships the provider-neutral PATCH API with native implementations for
Cosmos DB and DynamoDB. Spanner explicitly declares these capabilities
unsupported in that PR:

- `PATCH`
- `NESTED_PATCH`
- `EXACT_FRACTIONAL_INCREMENT`
- `PATCH_PRESERVES_TTL`

The experimental Spanner implementation is preserved on `spanner/patch` and
will be delivered, if approved, through a separate PR and release.

The final Spanner implementation should **not** ship the preserved Java
read-modify-write implementation unchanged. The target is a server-side
GoogleSQL DML update over the SDK document envelope:

```sql
UPDATE <table>
SET data = TO_JSON_STRING(<JSON_SET / JSON_REMOVE expression>)
WHERE partitionKey = @partitionKey
  AND sortKey = @sortKey
  AND <portable path/type/range predicates>
```

This is a server-side mutation in the behavioral sense: Spanner evaluates the
existing value, applies the field changes, and commits atomically without the
SDK first reading the document into Java. It is **not** the Spanner Mutation API
and Spanner does not expose a dedicated native PATCH API comparable to Cosmos
`patchItem`.

## 2. Why the Spanner work was separated

The split is intentional, not a loss of functionality.

1. The public PATCH contract is already provider-neutral. A future provider can
   implement `MulticloudDbProviderClient.patch(...)` and change its capability
   declarations without changing application code.
2. Cosmos DB and DynamoDB already have native partial-write primitives that fit
   the contract.
3. The preserved Spanner prototype changes the storage, query, row-mapping, and
   change-feed interpretation of the internal `data` column. That is a larger
   Spanner-specific change than the API addition itself.
4. Spanner is not currently releasable with PR #95. Keeping it in the same PR
   would couple two independently releasable changes and increase risk for the
   existing Cosmos and DynamoDB providers.

No Spanner-specific option, type, or behavior should be added to
`multiclouddb-api`. The future Spanner PR should be confined primarily to:

- `multiclouddb-provider-spanner`
- the Spanner conformance subclasses and test schema
- capability expectations
- Spanner-specific documentation and changelog entries

Cosmos DB and DynamoDB behavior must remain unchanged.

## 3. Current branch state

| Branch | State |
|---|---|
| `feat/patch-api` | Ships Cosmos and DynamoDB PATCH. Spanner fails fast with `UNSUPPORTED_CAPABILITY` before provider I/O. |
| `spanner/patch` | Preserves the complete experimental Spanner implementation and this design document. |
| `test/spanner-emulator-1.5.56` | Preserves the emulator-version experiment used during validation. |

Relevant preserved commits:

- `3bc9f3e` - final PR #95 portability hardening on `feat/patch-api`
- `21fa0f8` - preserved Spanner implementation and design review
- `cd23603` - Spanner emulator `1.5.56` test branch

Before implementation resumes, rebase `spanner/patch` onto the merged PR #95
commit. Keep Spanner capabilities unsupported during the rebase and only flip
them after the new implementation passes all release gates.

## 4. What the preserved prototype does

The preserved prototype is useful evidence, but it is not the recommended
release architecture.

### 4.1 Storage model

The prototype turns the existing `data STRING(MAX)` column into an
authoritative JSON document envelope:

```json
{
  "_mcdbDocument": {
    "title": "example",
    "count": 4
  }
}
```

Physical Spanner columns are treated as optional mirrors for query and
interoperability purposes. The envelope is authoritative when the two disagree.

### 4.2 PATCH execution

The prototype:

1. starts a Spanner read-write transaction;
2. reads the `data` envelope;
3. deserializes it into a Java map;
4. applies `SET`, `REPLACE`, `REMOVE`, or `INCREMENT` in Java;
5. rewrites the complete envelope and compatible physical-column mirrors;
6. commits the transaction.

### 4.3 Correctness properties

The prototype is atomic. The read and write occur in one Spanner read-write
transaction, and an `ABORTED` transaction retry re-runs the closure against a
fresh transaction snapshot. It does not expose a non-transactional lost-update
window.

The prototype passed the Spanner emulator run that was performed during the
experiment:

- 119 tests
- 0 failures
- 3 skipped
- `SpannerPatchConformanceTest`: 47/47

That result validates the prototype only. It does not certify the server-side
DML design proposed in this document.

### 4.4 Why the prototype should not ship unchanged

| Concern | Prototype behavior |
|---|---|
| Success-path reads | Reads the document before every patch. |
| Client CPU and allocation | Parses and reconstructs the complete document in Java. |
| Write shape | Rewrites the complete JSON envelope even for one small field. |
| Query/storage scope | Requires broad changes to row mapping, expression translation, and change feeds. |
| Nested paths | Declared unsupported in the prototype. |
| Portability review | More expensive than the native server-side paths and materially broader than PR #95. |

The prototype remains a valid fallback if server-side DML cannot satisfy the
portable contract, but it should not be the first choice.

## 5. Why Spanner did not use a native PATCH API

Cloud Spanner does not provide a dedicated item/document patch operation.

The Spanner Mutation API writes values supplied by the client:

```java
Mutation.newUpdateBuilder(table)
        .set("data").to(newValue);
```

It cannot, by itself, express:

- read the current JSON value;
- increment one value within it;
- require an addressed field to exist;
- remove one JSON path;
- calculate the new value from the stored value.

GoogleSQL DML can express those operations on the server. Spanner currently
documents `JSON_SET`, `JSON_REMOVE`, `JSON_QUERY`, `JSON_TYPE`, `INT64`,
`FLOAT64`, `PARSE_JSON`, and `TO_JSON_STRING` for transforming JSON values.

References:

- [GoogleSQL JSON functions for Spanner](https://cloud.google.com/spanner/docs/reference/standard-sql/json_functions)
- [GoogleSQL DML syntax for Spanner](https://cloud.google.com/spanner/docs/reference/standard-sql/dml-syntax)
- [Working with JSON in Spanner](https://cloud.google.com/spanner/docs/working-with-json)

Therefore the recommended primitive is GoogleSQL `UPDATE`, executed in a
read-write transaction, rather than `Mutation.newUpdateBuilder(...)`.

## 6. Target architecture

### 6.1 Keep the portable API unchanged

The future implementation must use the API delivered by PR #95:

```java
client.patch(address, key, List.of(
        PatchOperation.set("/status", "active"),
        PatchOperation.increment("/count", 1)));
```

Do not add:

- a Spanner-only patch method;
- a Spanner-only operation type;
- a provider selector inside `PatchOperation`;
- a provider-specific option in `OperationOptions`;
- a fallback that silently weakens the portable contract.

### 6.2 Keep `data` as `STRING(MAX)` for the first Spanner PATCH release

The recommended first release keeps the existing physical type:

```sql
data STRING(MAX)
```

The DML expression can transform it server-side:

```sql
TO_JSON_STRING(
  JSON_SET(
    SAFE.PARSE_JSON(data),
    '$."_mcdbDocument"."status"',
    PARSE_JSON(@statusJson)
  )
)
```

This avoids coupling PATCH delivery to a `STRING(MAX)` to `JSON` schema
migration. Moving `data` to the native `JSON` type may be considered later as
a separate storage-format change.

Spanner's native JSON type is attractive, but it has independent migration,
normalization, indexing, and compatibility consequences. It should not be
required merely to add PATCH.

### 6.3 Compile one atomic DML statement

The adapter should compile the validated operation list into one `UPDATE`
statement. Conceptually:

```sql
UPDATE <table>
SET data = TO_JSON_STRING(
    <expression produced by applying every operation to SAFE.PARSE_JSON(data)>
)
WHERE partitionKey = @partitionKey
  AND sortKey = @sortKey
  AND <all portable preconditions>
```

Properties:

- no pre-write point read on the normal success path;
- all operations are evaluated by Spanner;
- all operations commit or none commits;
- concurrent updates to the same row use normal Spanner transaction conflict
  detection;
- an aborted transaction re-evaluates the expression against the retried
  transaction snapshot;
- a missing or invalid precondition produces zero updated rows and is
  classified before the transaction returns.

The implementation may use `readWriteTransaction().run(...)`, but the closure
should execute DML rather than read and deserialize the document.

### 6.4 Operation translation

All user paths have already passed the shared `PatchValidator`. Convert the
JSON Pointer to a safely escaped JSONPath under `_mcdbDocument`.

| Portable operation | Spanner expression |
|---|---|
| `SET` | `JSON_SET(..., path, value)` |
| `REPLACE` | existence predicate plus `JSON_SET(...)` |
| `REMOVE` | existence predicate plus `JSON_REMOVE(...)` |
| `INCREMENT` | numeric predicate and range predicate plus `JSON_SET(..., path, current + delta)` |

`JSON_SET` creates missing paths by default. The portable contract is narrower:

- top-level `SET` may create its target;
- nested `SET` may create only the final field;
- every intermediate parent must already exist and be an object;
- `REPLACE`, `REMOVE`, and `INCREMENT` require the complete target path;
- array-index paths remain invalid.

The `WHERE` predicates must enforce those rules before the JSON function is
allowed to mutate the row.

### 6.5 Mixed operation lists

`JSON_SET` and `JSON_REMOVE` accept multiple paths, but a patch may mix all four
operation types. Build a nested expression from the validated operations:

```text
base envelope
  -> SET/REPLACE expression
  -> REMOVE expression
  -> INCREMENT expression
```

The shared validator rejects duplicate, aliasing, and ancestor/descendant path
overlap, so the operations are disjoint. Their expression order cannot change
the portable result.

### 6.6 Server-side increment

`INCREMENT` must be calculated from the stored JSON number inside the DML
statement. The Java client sends only the delta and portable bounds.

Required behavior:

- missing path -> `NOT_FOUND`;
- JSON value is not numeric -> `INVALID_REQUEST`;
- integral result outside signed 64-bit range -> `INVALID_REQUEST`;
- fractional delta outside `PatchNumericDomain` -> rejected before provider
  dispatch by shared validation;
- no lost updates under concurrent increments.

For concurrent increments on the same row, Spanner may abort one transaction.
The transaction runner retries the DML, which re-evaluates the current stored
value. This preserves the portable "all increments land" behavior.

Fractional accumulation should initially use Spanner's JSON/FLOAT64 behavior.
Spanner should therefore declare `EXACT_FRACTIONAL_INCREMENT` unsupported,
matching Cosmos rather than claiming DynamoDB's exact-decimal semantics.

## 7. Preconditions and error normalization

A successful DML update returns one affected row. Zero affected rows means
either the key did not exist or a portable precondition was false.

The transaction should classify that failure with a point read **only after**
the rejected DML:

| Stored state | Portable category |
|---|---|
| Row missing | `NOT_FOUND` |
| Required target or nested parent missing | `NOT_FOUND` |
| Increment target not numeric | `INVALID_REQUEST` |
| Integral increment would overflow | `INVALID_REQUEST` |
| Envelope is legacy, malformed, or not migrated | explicit migration error; do not silently reinterpret it |
| State now satisfies every predicate | `CONFLICT` |

This mirrors the finalized PR #95 approach:

- Cosmos: one conditional `patchItem` on success, classifying rejection with a
  session-token point read;
- DynamoDB: one conditional `UpdateItem` on success, normally classifying from
  `ALL_OLD`;
- Spanner target: one conditional DML update on success, classifying a
  zero-row result inside the transaction.

Do not perform an authoritative pre-read and then issue an unguarded update.
The predicates must be evaluated atomically with the write.

## 8. Physical-column mirrors, queries, and change feeds

The `data` envelope and physical columns must never silently disagree.

### 8.1 Authority

The JSON envelope is the portable SDK document. Physical columns are optional
mirrors for typed access, indexing, and interoperability.

### 8.2 Mirror rule

For every top-level root touched by PATCH, the same DML statement must do one
of the following:

1. update the compatible physical column to the same value;
2. recompute its serialized top-level value; or
3. set the physical column to a correctly typed SQL `NULL`.

Leaving an old physical value in place is not allowed.

For nested patches, recomputing or clearing the top-level mirror is required.

### 8.3 Query behavior

Portable query translation must read the authoritative envelope whenever a
physical mirror might be absent or cleared. A query must not return a stale
physical-column value after PATCH.

If maintaining this invariant makes the SQL compiler too broad for the first
Spanner PATCH release, keep `PATCH` unsupported rather than shipping partial
semantics.

### 8.4 Change-feed behavior

Spanner change-stream mapping must expose the same post-image that `read()`
returns. The internal envelope must not leak as an application field, and stale
physical mirrors must not reappear in `ChangeEvent.data()`.

## 9. Storage migration

The upstream Spanner provider historically used `data` as a JSON array of field
names. The preserved prototype uses an object envelope containing
`_mcdbDocument`. These formats must be distinguished explicitly.

### 9.1 Required compatibility states

The row mapper should recognize:

| `data` state | Meaning |
|---|---|
| envelope object with `_mcdbDocument` | PATCH-capable row |
| legacy field-name array | legacy row |
| SQL `NULL` | legacy/uninitialized row |
| malformed JSON or unknown object | corrupted/unsupported format |

### 9.2 Recommended migration policy

1. New SDK writes use the envelope format.
2. Reads remain dual-format during a documented compatibility window.
3. Existing rows are migrated explicitly before PATCH is enabled for that
   deployment.
4. PATCH must not advertise support and then silently produce different
   behavior for legacy rows.
5. Malformed or unknown formats fail with a structured portable error and a
   Spanner-specific `providerDetails.reason`.

Because the current Spanner user base is small, a Spanner-only migration is
acceptable. It must not require a breaking change to the public API or either
other provider.

## 10. Capability plan

Capabilities remain unsupported until their behavior is proven.

| Capability | Target declaration | Condition |
|---|---|---|
| `PATCH` | Supported | Server-side DML implementation passes the full patch conformance suite. |
| `NESTED_PATCH` | Prefer supported | JSONPath parents, quoted names, missing-parent behavior, and nested mirrors pass conformance. Otherwise leave unsupported for the first release. |
| `EXACT_FRACTIONAL_INCREMENT` | Unsupported | Initial design uses JSON/FLOAT64 accumulation, not exact decimal arithmetic. |
| `PATCH_PRESERVES_TTL` | Unsupported / not applicable | Spanner currently declares `ROW_LEVEL_TTL` unsupported. TTL-preservation conformance must be gated by row-level TTL support. |

`PATCH_PRESERVES_TTL` must be added when `spanner/patch` is rebased onto the
final PR #95 contract. Ordinary non-TTL Spanner rows must not be rejected merely
because this capability is unsupported.

## 11. Cross-provider impact

The future Spanner PR should not alter the behavior already released for Cosmos
DB or DynamoDB.

| Surface | Expected future change |
|---|---|
| Public API | None |
| `PatchOperation` and shared validation | None unless a proven provider-neutral bug is found |
| Cosmos provider | None |
| DynamoDB provider | None |
| Spanner provider | DML compiler, envelope/mirror handling, migration, capability declarations |
| Conformance | Add Spanner implementation subclass and Spanner-specific setup; keep shared assertions unchanged |
| Documentation | Update compatibility matrix, guide, architecture, and Spanner changelog |

If the Spanner implementation requires changing portable semantics to make the
provider pass, the implementation is not ready. Keep the capability unsupported
and revise the design instead.

## 12. Implementation sequence

### Step 0 - Rebase without enabling PATCH

Rebase `spanner/patch` onto merged PR #95. Resolve the prototype against:

- the final numeric floor (`1E-130`);
- wide-integer read behavior;
- `PATCH_PRESERVES_TTL`;
- the final Cosmos/Dynamo error categories;
- the request-envelope size rule;
- Spanner's unsupported declarations.

At the end of this step, Spanner must still fail fast as unsupported.

### Step 1 - Prove emulator and service SQL support

Create focused tests for:

- `JSON_SET`;
- `JSON_REMOVE`;
- `SAFE.PARSE_JSON`;
- `TO_JSON_STRING`;
- quoted JSONPath property names;
- numeric extraction and overflow predicates;
- one DML statement updating both `data` and a physical mirror.

Run them against Spanner emulator `1.5.56` and a real Spanner test database
before selecting the final SQL shape. Emulator success alone is not sufficient.

### Step 2 - Implement the DML compiler

Add a Spanner-internal compiler from validated `PatchOperation` values to:

- SQL expression;
- bound parameters;
- portable precondition predicates;
- physical-mirror assignments.

No SQL fragment may contain an unescaped caller field name.

### Step 3 - Implement rejection classification

When DML affects zero rows, classify the row in the same read-write transaction.
Return the portable category with Spanner diagnostics and a stable
`providerDetails.reason`.

### Step 4 - Complete migration and dual-format reads

Document and test legacy-array, envelope, null, and malformed `data` states.
Provide the selected migration mechanism before advertising PATCH.

### Step 5 - Restore Spanner conformance

Restore a Spanner PATCH conformance subclass and run all shared scenarios,
including:

- all operation types;
- all-or-nothing multi-operation patches;
- missing document and missing paths;
- nested behavior matching the capability declaration;
- numeric floor and signed-64 bounds;
- wide integer read-back;
- explicit JSON null vs. remove;
- concurrent increments;
- legacy-row behavior;
- no pre-write read on a normal envelope-row success.

### Step 6 - Flip capabilities and document the release

Only after every gate passes:

- declare `PATCH` supported;
- declare `NESTED_PATCH` according to tested behavior;
- keep `EXACT_FRACTIONAL_INCREMENT` truthful;
- keep TTL capability aligned with actual row-TTL support;
- update all required docs and changelogs.

## 13. Release gates

The separate Spanner PR is not merge-ready until all of these are true:

- shared unit profile passes;
- Spanner provider tests pass;
- full Spanner emulator conformance passes;
- real-service JSON DML smoke tests pass;
- success path proves no client point read;
- concurrent increments lose no updates;
- legacy and malformed rows have explicit behavior;
- physical mirrors cannot remain stale;
- change feed and point read return the same document;
- capability declarations match implementation;
- no Cosmos or DynamoDB files change without a separately justified
  provider-neutral fix;
- compatibility docs, guide, architecture, API reference, specification, tasks,
  and Spanner changelog are aligned.

## 14. Cost and performance expectations

The server-side DML design removes the client read and Java document
reconstruction from the success path. It does **not** guarantee that Spanner
writes only the changed JSON bytes internally.

The entire `data` column receives a new value, so:

- write cost may still scale with the JSON document;
- indexes or generated columns can add write work;
- change streams may emit a large post-image;
- DML transaction and lock cost must be measured.

The correct claim is:

> Spanner PATCH is a server-side atomic field-mutation interface with one
> success-path DML statement, not a guaranteed byte-level or billing reduction.

Benchmarks should compare:

- current full `update()`;
- preserved transactional Java RMW prototype;
- proposed server-side JSON DML;
- small and near-limit documents;
- one and ten operations;
- low and high contention.

## 15. Remaining decisions

1. **Nested PATCH in the first release:** recommended if the JSONPath and mirror
   rules pass conformance; otherwise keep `NESTED_PATCH` unsupported.
2. **Migration mechanism:** explicit migration command/job, deployment flag, or
   another deterministic Spanner-only process.
3. **Physical mirrors:** recompute or clear per touched root; never leave stale
   values.
4. **Native JSON column:** defer unless performance evidence justifies a separate
   schema migration.
5. **Real-service validation:** confirm that the selected functions and
   transaction behavior match the emulator before release.

## 16. Final recommendation

Keep PR #95 as shipped:

- Cosmos DB: native `patchItem`;
- DynamoDB: native `UpdateItem`;
- Spanner: explicit `UNSUPPORTED_CAPABILITY`.

For the separate Spanner PR:

1. rebase the preserved branch;
2. retain the provider-neutral API;
3. replace the Java envelope read-modify-write success path with one
   server-side GoogleSQL DML update;
4. preserve atomic predicates and portable error categories;
5. solve envelope migration, physical mirrors, query behavior, and change-feed
   behavior as one Spanner storage change;
6. advertise capabilities only after emulator and real-service conformance.

This gives Spanner server-side mutation semantics without creating a breaking
change for Cosmos DB or DynamoDB.

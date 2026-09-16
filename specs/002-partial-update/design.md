# Portable Partial Update Design

**Branch**: `002-partial-update`
**Binding**: This document defines the implementation for this feature.

## 1. Decision

Keep the existing Java signatures and change `update()` from ambiguous
provider-specific replacement to shallow set/replace semantics:

```java
void update(
    ResourceAddress address,
    MulticloudDbKey key,
    Map<String, Object> fields,
    OperationOptions options);
```

Cosmos DB and DynamoDB move to native partial-update operations and advertise
the core capability. That capability covers results whose serialized JSON and
portable structural footprint are each at most 390 KiB. Cosmos also advertises the optional extended-result capability for its
larger native envelope; DynamoDB explicitly does not.
TTL-expiry preservation is a third, independent capability: DynamoDB advertises
it because `UpdateItem` leaves `ttlExpiry` unchanged, while Cosmos DB advertises
it unsupported because `patchItem` advances `_ts` and restarts the TTL
countdown.

The Spanner production source remains unchanged. Because it omits all three
Feature 002 capabilities, `CapabilitySet` supplies unsupported defaults. This is a
deliberate release boundary, not an implied temporary validation result; future
portable Spanner update support requires a separate provider release and
validation decision.

## 2. Portable contract

For field names and value shapes supported by the provider mapping:

- each present top-level field is set/replaced;
- omitted fields are preserved;
- map/list values replace the complete top-level value;
- null stores provider-native null and does not remove the field;
- case-distinct non-reserved names are separate literal fields, including when
  present in the same request;
- logical field assignments are atomic and replay-idempotent, while provider-maintained
  metadata and TTL timing remain capability-specific; and
- a missing document returns `NOT_FOUND` without creating it.

The operation does not support nested paths, remove, increment, conditional
field predicates, or update TTL. The core operation also does not promise that
an existing absolute TTL expiry remains fixed; callers requiring that guarantee
must inspect `partial_update_preserves_ttl_expiry`.

### 2.1 Release boundary

Only providers advertising `partial_update` enter a provider data path. Cosmos
DB and DynamoDB advertise it. Spanner omits the capability, so `CapabilitySet`
supplies the unsupported default and the client rejects a valid update after shared
validation and before delegation with:

```text
category=UNSUPPORTED_CAPABILITY
retryable=false
operation=update
capability=partial_update
```

This boundary avoids releasing an unvalidated Spanner data path while keeping
the unsupported behavior explicit and preserving provider-version independence.
No Spanner provider release is required. The Spanner emulator validates shared
preflight/capability rejection and the provider-direct legacy regression, but no
live production Spanner validation is claimed.

## 3. Shared preflight

`DefaultMulticloudDbClient.update()` runs:

```text
checkOpen
  -> validate non-null/non-empty fields
  -> validate names <= 50,000 UTF-8 bytes
  -> reject update TTL
  -> reject binary values and unsafe graphs
  -> snapshot top-level map entries and serialize with a bounded 390 KiB
     SDK-owned Jackson output stream
  -> reject binary values exposed while serializing POJOs
  -> validate serialized size <= 390 KiB
  -> validate replacement map/list depth <= 31
  -> validate every nested field name <= 50,000 UTF-8 bytes
  -> validate structural footprint <= 390 KiB
  -> gate Capability.PARTIAL_UPDATE
  -> delegate once
```

Name validation:

- non-null, non-empty, non-blank;
- not `id`, `partitionKey`, `sortKey`, `ttl`, `ttlExpiry`, or `data`,
  case-insensitively;
- not underscore-prefixed;
- case-distinct non-reserved names remain distinct, even in one field map; and
- accepted names are not trimmed or rewritten.

The validator accepts literal `.`, `/`, `~`, and surrounding spaces. Every nested map key follows the same UTF-8 byte bound. Mapping
constraints apply only after the provider passes the core capability gate.

All validation and serialization failures are non-retryable `INVALID_REQUEST`
and perform zero provider I/O. Serialization failures preserve their cause. A
serialized payload at the portable 390 KiB limit passes; a larger payload fails.
Binary values, including values hidden in serializable POJOs, fail before provider
delegation. Non-collection iterables are rejected before iteration, and serialized
output is bounded while it is generated. Values must be serializable with the
SDK-owned Jackson configuration.

The structural pass reads the already-serialized JSON, so it neither mutates
caller data nor introduces another serialization path. For each supplied field,
the top-level map/list replacement container is depth 1 and 31 containers are
accepted; depth 32 fails with `partial_update_nesting_depth_limit`. A separate
DynamoDB-style estimate sums UTF-8 attribute names and scalar bytes, adds three
bytes per map/list container and one byte per nested element, and rejects totals
above 399,360 bytes with `partial_update_structural_footprint_limit`. Both errors
carry actual/maximum details and occur before capability gating.

Complete documents accepted by `create()`/`upsert()` share the binary-value,
31-level, 50,000-byte field-name, and 390 KiB structural checks. A null complete
document, any case-insensitive top-level provider-owned name (`id`,
`partitionKey`, `sortKey`, `ttl`, `ttlExpiry`, or `data`), and any
underscore-prefixed top-level name fail with non-retryable `INVALID_REQUEST`
before provider I/O; case-distinct non-reserved top-level names remain valid.
`PortableWriteLimits` exposes exactly the five
input/structure limits (serialized bytes, structural footprint, field-name
bytes, nested-container depth, and partial-update field count).

These checks bound only the incoming replacements. Existing omitted fields are
not read or merged, so state-dependent resulting-item failures retain the native
reason-coded path after at most one attempted atomic update.

## 4. Capabilities

| Capability | Meaning |
|---|---|
| `partial_update` | Core shallow set/replace operation when serialized result JSON and structural footprint are each <= 390 KiB. |
| `partial_update_extended_result_size` | Provider supports results above either 390 KiB base bound up to its documented native ceiling. |
| `partial_update_preserves_ttl_expiry` | Provider leaves an existing TTL-bearing item's absolute expiry unchanged. |

The default client gates `partial_update`. Result size depends on existing state, so
the SDK does not read/merge before writing and cannot classify an individual call
before delegation. Callers that require portable behavior keep the logical result
within both 390 KiB bounds. Above either boundary they inspect the extended-result capability and
handle reason-coded native size failures. Case-distinct field identity remains part
of the base operation. Fixed absolute TTL expiry is not part of the base
operation and requires the separate TTL-preservation capability.

| Provider | Core | Extended result size | Preserves absolute TTL expiry |
|---|---|---|---|
| Cosmos DB | supported | supported up to 2 MiB | unsupported; patch advances `_ts` and restarts countdown |
| DynamoDB | supported | unsupported | supported; `ttlExpiry` remains unchanged |
| Spanner | unsupported by API default | unsupported by API default | unsupported by API default |

```text
provider declares partial_update=true
  -> shared gate passes
  -> serialized result and structural footprint <= 390 KiB: portable base envelope
  -> result above either bound: consult partial_update_extended_result_size

provider omits any Feature 002 capability
  -> CapabilitySet inserts that capability's unsupported default
```

Each built-in provider exposes 20 effective rows. Cosmos DB and DynamoDB declare
all 20; an older Spanner provider's 17 declarations receive the three Feature 002
unsupported defaults without a provider release. `CapabilitySet` does not
synthesize unrelated known names omitted by arbitrary partial declarations.

## 5. Cosmos DB design

### 5.1 Literal paths

Each raw field name becomes one RFC 6901 segment:

```java
"/" + rawName.replace("~", "~0").replace("/", "~1")
```

Every assignment uses `CosmosPatchOperations.set`. No key or TTL operation is
added. The patch advances Cosmos DB `_ts`, so Cosmos explicitly reports
`partial_update_preserves_ttl_expiry` unsupported: an existing relative TTL
countdown restarts even though the reserved TTL fields are not assigned.

### 5.2 Single-patch plan

```text
1..10 fields
 -> sort literal names for deterministic operation order
 -> one patchItem (only execution path; no transactional batch)

11+ fields
 -> shared INVALID_REQUEST before provider delegation
```

There is no read, merge, replace, transactional batch, independent patch loop,
or adapter retry loop.

### 5.3 Portable field-count envelope

Shared preflight rejects more than 10 fields before any provider call. The
package-private Cosmos planner repeats this check defensively for direct SPI
integrators. Every accepted Cosmos update therefore incurs exactly one point
patch request, bounding RU asymmetry with DynamoDB.

### 5.4 Service result-item envelope

A fields map can pass shared preflight but push the existing Cosmos
document over the service's 2,097,152-byte item limit. No read/merge preflight
is added. If the one attempted direct patch reports HTTP
413 during `update()`, it maps to non-retryable `UNSUPPORTED_CAPABILITY` with:

- `reason=cosmos_result_item_size_limit`
- `maximumResultBytes`

A thrown direct-patch exception preserves its cause and sanitized native
metadata.  HTTP 413 from
other operations retains the normal Cosmos provider-error mapping.

### 5.5 Response bodies and diagnostics

`contentResponseOnWriteEnabled(false)` is safe because existing write methods
return `void` and consume only response metadata. Tests retain status,
activity ID, request charge, duration, and diagnostics for create, patch,
upsert, and delete paths.


## 6. DynamoDB design

The package-private planner emits one request:

```text
UpdateExpression:
  SET #f0 = :v0, #f1 = :v1, ...

ConditionExpression:
  attribute_exists(#pk)
```

- `#fN` maps to the raw caller field name.
- `:vN` maps through `DynamoItemMapper.objectToAttributeValue`.
- `#pk` maps to the partition-key attribute.
- no raw field name is embedded in the expression;
- no TTL assignment is generated; and
- the request asks for total consumed capacity.

The value mapper preserves STRING/NUMBER/BOOL/NULL/MAP/LIST shapes.
Because the expression does not assign `ttlExpiry`, DynamoDB preserves the
existing absolute expiry and advertises
`partial_update_preserves_ttl_expiry`.


The shared 10-field limit keeps every portable call safely below the DynamoDB
native expression ceiling. The planner still measures the generated expression
and retains a defensive local rejection for direct SPI misuse, but this is not
a caller-visible portable envelope.

The provider executes exactly one `updateItem`. A
`ConditionalCheckFailedException` from the existence guard maps to
`NOT_FOUND`. No read, `PutItem`, or adapter retry loop is used.

The existing item can make an otherwise-valid update exceed DynamoDB's
provider-native resulting-item limit. No read/merge preflight is added. When the
single `UpdateItem` returns the size-specific `ValidationException` message,
only that variant maps to non-retryable `UNSUPPORTED_CAPABILITY` with:

- `reason=dynamodb_result_item_size_limit`
- `maximumResultBytes`

Sanitized native error code, status, request ID, and service details remain
available where supplied. Other `ValidationException` messages remain
`INVALID_REQUEST`, the original cause is preserved, and no payload data is
added to diagnostics.

The module descriptor explicitly reads the AWS utility and identity modules
needed by the pinned SDK so a clean module-path compilation succeeds.

## 7. Test design

### Completed focused unit layer

- API validator/default-client/size/public-contract/capability tests
- Cosmos planner, direct provider, field-limit, error mapping, diagnostics,
  response-body configuration, update-only 413 normalization, and updated
  consistency tests
- Dynamo planner, provider, and structured mapper tests
- Spanner row-mapper coverage for exact logical spelling projection

### Shared layer

All providers inherit shared validation coverage for invalid maps, names, TTL,
and the over-limit rejection because validation precedes the core gate.
Supported behavior—preservation, missing-item handling, replay, concurrency,
field-count rejection, literal-name handling, and same-request case-distinct
identity—runs only when `partial_update` is advertised. Shared complete-write coverage rejects every provider-owned or
underscore-prefixed top-level name and verifies the shared write envelope.
A dedicated shared assertion verifies that an omitted Spanner capability defaults to unsupported and returns
`UNSUPPORTED_CAPABILITY` with `capability=partial_update` and does not mutate
state.

The portable 390 KiB boundary is locked by API validator and recording-provider
tests that prove exact-limit delegation and one-byte-over zero delegation. Shared
live conformance verifies exact-limit create/upsert on every provider and
exact-limit partial update on the capability-supporting Cosmos and DynamoDB paths.

Capability conformance verifies all 20 effective rows for every built-in
provider, the unchanged extended-result matrix (Cosmos supported, DynamoDB and
Spanner unsupported), and the TTL-preservation matrix (DynamoDB supported,
Cosmos DB and Spanner unsupported).

`CosmosConformanceTest` and `DynamoConformanceTest` seed native items below their
service limits, apply small portable updates that would push the results above
those limits, and assert normalized capability errors plus unchanged state.
## 8. Migration

Before this feature, Cosmos and Dynamo `update()` replaced the complete stored
document. Callers that require replacement use:

```java
client.upsert(address, key, completeDocument);
```

`upsert()` creates a missing document. Read-then-upsert is not an atomic
guarded replacement and can recreate a concurrently deleted or expired item.
This release has no exact portable atomic full-document replace-if-present
equivalent.

TTL-bearing updates also move to complete create/upsert writes:

```java
client.upsert(
    address,
    key,
    completeDocument,
    OperationOptions.builder().ttlSeconds(3600).build());
```

This migration sets or replaces TTL through a complete write. It is separate
from preserving an already established absolute expiry during partial update.
Callers requiring the latter must check
`PARTIAL_UPDATE_PRESERVES_TTL_EXPIRY`; the core capability alone is
insufficient.

## 9. Scope boundaries

This design does not add a `replace()` method, general patch model, Spanner
typed-null/DDL/automatic-schema work, native-client escape hatch, cancellation,
configurable retry policy, or changes under `multiclouddb-perf/`.

## 10. Current validation evidence

DynamoDB Local and the Spanner emulator ran in the current environment. The
Spanner run covers the deliberate unsupported gate and provider-direct legacy
path, not live production Spanner. The Cosmos emulator is unavailable, so T061
remains pending for the Cosmos-inclusive post-remediation rerun.

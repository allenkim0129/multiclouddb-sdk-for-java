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
the core capability. The Spanner data path is excluded from this release; only
its explicit unsupported capability declaration and changelog alignment change.
Release-grade Spanner behavior will be enabled after validation against a live
account becomes available.

## 2. Portable contract

For field names and value shapes supported by the provider mapping:

- each present top-level field is set/replaced;
- omitted fields are preserved;
- map/list values replace the complete top-level value;
- null stores provider-native null and does not remove the field;
- the call is atomic and replay-idempotent; and
- a missing document returns `NOT_FOUND` without creating it.

The operation does not support nested paths, remove, increment, conditional
field predicates, or update TTL.

### 2.1 Release boundary

Only providers advertising `partial_update` enter a provider data path. Cosmos
DB and DynamoDB advertise it. Spanner explicitly declares the capability
unsupported, so the default client rejects a valid update after shared
validation and before delegation with:

```text
category=UNSUPPORTED_CAPABILITY
retryable=false
operation=update
capability=partial_update
```

This boundary avoids releasing an unvalidated Spanner data path while keeping
the unsupported behavior explicit. The capability declaration and changelog
alignment are intentional feature-002 changes; implementation follows after
live-account validation is available.

## 3. Shared preflight

`DefaultMulticloudDbClient.update()` runs:

```text
checkOpen
  -> validate non-null/non-empty fields
  -> validate names
  -> reject update TTL
  -> validate serialized size <= 390 KiB
  -> gate Capability.PARTIAL_UPDATE
  -> delegate once
```

Name validation:

- non-null, non-empty, non-blank;
- not `id`, `partitionKey`, `sortKey`, `ttl`, `ttlExpiry`, or `data`,
  case-insensitively;
- not underscore-prefixed;
- unique ignoring case; and
- accepted names are not trimmed or rewritten.

The validator accepts literal `.`, `/`, `~`, and surrounding spaces. Mapping
constraints apply only after the provider passes the core capability gate.

All validation failures are non-retryable `INVALID_REQUEST` and perform zero
provider I/O. A payload at the portable 390 KiB limit passes; a larger payload fails.

## 4. Capability

One declaration is introduced:

| Capability | Meaning |
|---|---|
| `partial_update` | Provider implements the core shallow set/replace operation. |

The default client gates `partial_update`. Native request and resulting-item
limits remain explicit through non-retryable, reason-coded provider errors;
they do not define another capability. Case-distinct field identity is required
by the base operation.

| Provider | Core |
|---|---|
| Cosmos DB | supported |
| DynamoDB | supported |
| Spanner | explicitly unsupported |

All three providers declare all 18 known capability names. Spanner
marks `partial_update` unsupported.

## 5. Cosmos DB design

### 5.1 Literal paths

Each raw field name becomes one RFC 6901 segment:

```java
"/" + rawName.replace("~", "~0").replace("/", "~1")
```

Every assignment uses `CosmosPatchOperations.set`. No key or TTL operation is
added.

### 5.2 Single-patch plan

```text
1..10 fields
 -> sort literal names for deterministic operation order
 -> one patchItem

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
field-count rejection, literal-name handling, and case identity—runs only when `partial_update` is advertised.
A dedicated shared assertion verifies that Spanner explicitly declares the capability unsupported and returns
`UNSUPPORTED_CAPABILITY` with `capability=partial_update` and does not mutate
state.

The portable 390 KiB boundary is locked by API validator tests. A
shared provider-runtime success assertion is intentionally omitted because a
native request or resulting-item limit may reject an otherwise-valid map.
Cosmos and Dynamo exercise those native limits in concrete emulator regressions.

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

TTL-bearing updates also move to complete create/upsert writes:

```java
client.upsert(
    address,
    key,
    completeDocument,
    OperationOptions.builder().ttlSeconds(3600).build());
```

## 9. Scope boundaries

This design does not add a `replace()` method, general patch model, Spanner
typed-null/DDL/automatic-schema work, native-client escape hatch, cancellation,
configurable retry policy, or changes under `multiclouddb-perf/`.

# Java Contract: `MulticloudDbClient.update`

**Branch**: `002-partial-update`

## Signatures

```java
void update(
    ResourceAddress address,
    MulticloudDbKey key,
    Map<String, Object> fields,
    OperationOptions options);

default void update(
    ResourceAddress address,
    MulticloudDbKey key,
    Map<String, Object> fields);
```

The three-argument overload supplies `OperationOptions.defaults()`.

## Shared preconditions

After the closed-client guard and before provider planning:

1. `fields` is non-null and non-empty.
2. `fields` contains at most 10 entries.
3. Names are non-null, non-empty, and non-blank.
4. Names do not match reserved system names ignoring case.
5. Names do not begin with `_`.
6. Names are unique ignoring case.
7. `options.ttlSeconds()` is null.
8. serialized `fields` is at most 408,576 bytes.
9. `partial_update` is supported.

Preconditions 1–8 fail with non-retryable `INVALID_REQUEST`; precondition 9
fails with non-retryable `UNSUPPORTED_CAPABILITY` and
`providerDetails.capability=partial_update`. Every failure performs zero
provider update operations.

Accepted names are not trimmed or rewritten. Punctuation is literal, but
provider mapping remains relevant.

## Postconditions

For a provider-supported field mapping on an existing document:

- named fields equal the supplied values;
- omitted fields retain their values;
- map/list values replace the entire top-level value;
- null stores provider-native null;
- all assignments commit atomically; and
- replaying the assignments is idempotent.

A missing document returns `NOT_FOUND` and is not created.

## Provider release boundary

Cosmos DB and DynamoDB advertise `partial_update` and enter their native update
paths. Spanner explicitly declares the capability unsupported. After shared
preconditions 1–8 pass, the default client rejects a Spanner call with
non-retryable `UNSUPPORTED_CAPABILITY`,
`providerDetails.capability=partial_update`, and zero provider update
operations.
## Capability contract

| Provider | `partial_update` |
|---|---|
| Cosmos DB | supported |
| DynamoDB | supported |
| Spanner | explicitly unsupported |

Native request and resulting-item limits do not define another capability;
they surface as non-retryable, reason-coded provider-limit errors. Cosmos DB, DynamoDB, and Spanner declare all 18 known names.

## Provider execution

| Provider | Accepted plan | Missing document |
|---|---|---|
| Cosmos DB | one direct patch through 10 fields | direct 404 → `NOT_FOUND` |
| DynamoDB | one aliased conditional `UpdateItem` | failed existence guard → `NOT_FOUND` |
| Spanner | no provider plan; shared capability rejection | not reached |

Cosmos and Dynamo add no adapter read, replacement write, or retry loop.

## Cosmos envelope

Maps above 10 fields fail shared preflight with `INVALID_REQUEST` before any
provider call. The Cosmos planner repeats this check for direct SPI use. Field
names use one RFC 6901 segment and are sorted before native plan construction.

An otherwise-valid update can push an existing Cosmos document above the
2,097,152-byte resulting-item limit. No read/merge preflight is performed. If
the one attempted direct patch reports HTTP 413 during `update()`, it maps to
non-retryable `UNSUPPORTED_CAPABILITY` with:

- `reason=cosmos_result_item_size_limit`
- `maximumResultBytes=2097152`

The failed write leaves the document unchanged. Direct exceptions preserve
their cause and sanitized native metadata. HTTP 413 from non-update operations
retains the normal provider-error mapping.

## Dynamo envelope

The provider builds one:

```text
SET #f0 = :v0, #f1 = :v1, ...
```

with `attribute_exists(#pk)`. Values preserve null/scalar/map/list shapes.

An update expression above 4,096 UTF-8 bytes fails locally with:

- `reason=dynamodb_update_expression_limit`
- `actualExpressionBytes`
- `maximumExpressionBytes=4096`

That expression rejection performs zero DynamoDB I/O.

An otherwise-valid update can push an existing item above DynamoDB's
409,600-byte resulting-item limit. No read/merge preflight is performed. If the
single attempted `UpdateItem` returns the size-specific `ValidationException`,
only that variant maps to non-retryable `UNSUPPORTED_CAPABILITY` with:

- `reason=dynamodb_result_item_size_limit`
- `maximumResultBytes=409600`

The original cause and sanitized native error code, HTTP status, request ID,
and service details are retained where available. Other
`ValidationException` messages remain `INVALID_REQUEST`. The failed native
update leaves the stored item unchanged.

## Diagnostics

Diagnostics may contain operation/address, status/substatus, activity/request
ID, request charge/capacity, duration, and native diagnostics. They must not
contain field values, serialized payloads, credentials, or authorization data.

## Migration

Complete replacement moves to:

```java
client.upsert(address, key, completeDocument);
```

`upsert()` creates a missing document and does not preserve update's
`NOT_FOUND` guard. TTL-bearing updates also move to a complete create/upsert
write.

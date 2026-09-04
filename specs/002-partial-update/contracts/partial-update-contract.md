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
2. Names are non-null, non-empty, and non-blank.
3. Names do not match reserved system names ignoring case.
4. Names do not begin with `_`.
5. Names are unique ignoring case.
6. `options.ttlSeconds()` is null.
7. serialized `fields` is at most 408,576 bytes.
8. `partial_update` is supported.

Preconditions 1–7 fail with non-retryable `INVALID_REQUEST`; precondition 8
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

## Spanner fixed-schema contract

Spanner retains its read-write transaction, `FIELD_DATA` merge, null-STRING
binding, and encoded STRING map/list mapping, with one explicit casing guard.

- Fields map only to already provisioned columns and must use exact spelling.
- `update()` cannot create a missing column.
- Existing metadata establishes the logical spelling for a row.
- Previously unseen names are checked against `INFORMATION_SCHEMA.COLUMNS`.
- A case-only alias returns non-retryable `UNSUPPORTED_CAPABILITY` with
  `capability=partial_update_case_sensitive_fields` and
  `reason=spanner_case_insensitive_column_collision` before mutation.
- Reads project the accepted logical spelling stored in `FIELD_DATA`.

Shared conformance uses only existing Spanner fixture columns/shapes. No
typed-null contract, DDL, new schema fixture, or E2E schema helper is part of
this feature.

## Capability contract

| Provider | `partial_update` | `partial_update_extended_payload` | `partial_update_case_sensitive_fields` |
|---|---|---|---|
| Cosmos DB | supported | unsupported | supported |
| DynamoDB | supported | unsupported | supported |
| Spanner | supported | supported for fixed-schema mappings | unsupported |

The extension means that supported provider field mappings do not hit a lower
native request or resulting-item envelope before the common size limit. It is
not a claim that arbitrary Spanner columns or value types exist.

Every built-in provider declares all 20 known capability names.

## Provider execution

| Provider | Accepted plan | Missing document |
|---|---|---|
| Cosmos DB | one direct patch through 10 fields; otherwise one same-item transactional batch | direct/batch root 404 → `NOT_FOUND` |
| DynamoDB | one aliased conditional `UpdateItem` | failed existence guard → `NOT_FOUND` |
| Spanner | read-write transaction, exact-case guard, and partial mutation | existing behavior |

Cosmos and Dynamo add no adapter read, replacement write, or retry loop.

## Cosmos envelope

A prospective wide plan above 100 batch operations or 2,097,152 UTF-8 bytes
fails locally with:

- `reason=cosmos_transactional_batch_limit`
- `capability=partial_update_extended_payload`
- `actualOperations`
- `maximumOperations=100`
- `actualBytes`
- `maximumBytes=2097152`

Field names use one RFC 6901 segment and `set`.

An otherwise-valid update can push an existing Cosmos document above the
2,097,152-byte resulting-item limit. No read/merge preflight is performed. If
the one attempted direct patch or batch reports HTTP 413 during `update()`, it
maps to non-retryable `UNSUPPORTED_CAPABILITY` with:

- `reason=cosmos_result_item_size_limit`
- `capability=partial_update_extended_payload`
- `maximumResultBytes=2097152`

The failed write leaves the document unchanged. Direct exceptions preserve
their cause and sanitized native metadata; batch failures preserve sanitized
aggregate/result diagnostics. HTTP 413 from non-update operations retains the
normal provider-error mapping.

## Cosmos failed-batch selection

1. first failed usable 4xx/5xx operation status other than 424;
2. otherwise usable non-424 aggregate 4xx/5xx status;
3. otherwise sanitized `PROVIDER_ERROR` stating no root status was supplied.

Selected statuses use normal Cosmos mapping. HTTP 408 and 410 are retryable
transient failures; 410 substatus is retained. During `update()`, selected HTTP
413 uses the result-item capability mapping above.

## Dynamo envelope

The provider builds one:

```text
SET #f0 = :v0, #f1 = :v1, ...
```

with `attribute_exists(#pk)`. Values preserve null/scalar/map/list shapes.

An update expression above 4,096 UTF-8 bytes fails locally with:

- `reason=dynamodb_update_expression_limit`
- `capability=partial_update_extended_payload`
- `actualExpressionBytes`
- `maximumExpressionBytes=4096`

That expression rejection performs zero DynamoDB I/O.

An otherwise-valid update can push an existing item above DynamoDB's
409,600-byte resulting-item limit. No read/merge preflight is performed. If the
single attempted `UpdateItem` returns the size-specific `ValidationException`,
only that variant maps to non-retryable `UNSUPPORTED_CAPABILITY` with:

- `reason=dynamodb_result_item_size_limit`
- `capability=partial_update_extended_payload`
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

# Phase 1 Data Model: Portable Partial Update

This feature adds no persisted domain type. It defines a request, validation
state, and provider-native plans.

## 1. Request

| Field | Type | Rule |
|---|---|---|
| `address` | `ResourceAddress` | Existing addressing rules apply. |
| `key` | `MulticloudDbKey` | Identifies an existing document. |
| `fields` | `Map<String,Object>` | Non-null/non-empty literal top-level assignments. |
| `options` | `OperationOptions` | Defaults allowed; `ttlSeconds` must be null. |

### Name rules

- non-null, non-empty, non-blank;
- not a case-insensitive reserved name;
- not underscore-prefixed;
- unique ignoring case; and
- never trimmed or rewritten.

Punctuation remains literal for participating Cosmos DB and DynamoDB mappings.
Spanner never reaches field-name mapping in this release: after shared
validation, the capability gate rejects every otherwise-valid update before provider delegation.

### Value rules

- scalar → replace scalar;
- map/list → replace complete top-level value;
- null → stored null for a supported provider mapping;
- no remove, increment, nested path, condition, or TTL mutation.

## 2. State transition

```text
EXISTING document D
  + fields F
  -> D' where:
       D'[name] = F[name] for name in F
       D'[name] = D[name] for omitted names

MISSING document
  + update(F)
  -> NOT_FOUND; remains missing
```

The transition is atomic and replay-idempotent.

## 3. Shared validation state

```text
RECEIVED
  -> closed                         CLIENT_CLOSED
  -> invalid map/name/TTL/count           INVALID_REQUEST
  -> serialized bytes > 408,576     INVALID_REQUEST
  -> partial_update unsupported     UNSUPPORTED_CAPABILITY
  -> provider plan
```

All local failures delegate zero provider update operations.

## 4. Cosmos plan

### Assignment

| Field | Meaning |
|---|---|
| `rawName` | caller field name |
| `path` | one RFC 6901 segment |
| `value` | absolute `set` value |

### Selection

```text
fieldCount <= 10
 -> DirectPlan(CosmosPatchOperations)

fieldCount > 10
 -> shared INVALID_REQUEST before provider delegation
```

### Result-item limit

```text
resulting document > 2,097,152 bytes
  -> Cosmos reports HTTP 413 after one attempted patch
  -> reason-coded UNSUPPORTED_CAPABILITY
  -> stored document remains unchanged
```

The state-dependent path has no adapter read/merge preflight. HTTP 413 is
specialized only for `update()`.

## 5. Dynamo plan

| Field | Meaning |
|---|---|
| `key` | existing partition/sort-key map |
| `names` | `#fN` aliases plus `#pk` |
| `values` | `:vN` structured `AttributeValue`s |
| `updateExpression` | one `SET` clause |
| `conditionExpression` | `attribute_exists(#pk)` |
| `expressionBytes` | UTF-8 size of the update expression |

```text
expressionBytes <= 4096
  -> one UpdateItem

expressionBytes > 4096
  -> local reason-coded UNSUPPORTED_CAPABILITY

condition failure
  -> NOT_FOUND

resulting item > 409600 bytes
  -> DynamoDB rejects the one attempted UpdateItem atomically
  -> reason-coded UNSUPPORTED_CAPABILITY
```

The result-size path is state-dependent and has no adapter read/merge preflight.
Only the matching update `ValidationException` is normalized; other validation
errors remain `INVALID_REQUEST`.

## 6. Provider release boundary

Spanner is not a feature-002 data model. Its data path remains
unchanged, while its capability set explicitly declares `partial_update` unsupported. The default
client rejects valid calls before provider delegation; no row, schema, metadata,
or mapping behavior is changed by this feature.
## 7. Capability

| Provider | `partial_update` |
|---|---|
| Cosmos DB | supported |
| DynamoDB | supported |
| Spanner | explicitly unsupported |

Native request and resulting-item limits are reason-coded errors, not separate
capabilities. Case-distinct field identity is part of the base contract.

## 8. Structured provider-limit errors

All values in `providerDetails` are strings.

### Cosmos

```text
reason=cosmos_result_item_size_limit
maximumResultBytes=2097152
subStatusCode
requestId                          (when available)
requestCharge
```

The result-item rejection follows one attempted direct
patch; the failed native operation leaves the document unchanged.

### Dynamo

```text
reason=dynamodb_update_expression_limit
actualExpressionBytes
maximumExpressionBytes=4096
```

This expression rejection is local and performs zero DynamoDB I/O.

```text
reason=dynamodb_result_item_size_limit
maximumResultBytes=409600
errorCode=ValidationException       (when available)
requestId                          (when available)
serviceName                        (when available)
```

The Dynamo result-item rejection is returned after one attempted `UpdateItem`;
the failed native operation leaves the item unchanged.

## 9. Conformance fixture rule

Supported partial-update behavior runs only for providers advertising the core
capability. Shared invalid-request checks still run on every provider because
validation precedes the gate. Spanner receives a dedicated assertion for its explicit unsupported declaration and
for non-retryable `UNSUPPORTED_CAPABILITY` with zero provider mutation.

# Provider SDK Surface Comparison (Java) — Cosmos DB vs DynamoDB vs Spanner

This is **supporting, non-normative research** for the Multicloud DB SDK spec. It documents how the *official Java SDKs* expose common operations and diagnostic signals, to help design a portable contract.

Providers / SDKs:
- **Azure Cosmos DB (SQL API)**: `com.azure:azure-cosmos`
- **AWS DynamoDB**: `software.amazon.awssdk:dynamodb` (AWS SDK for Java v2)
- **Google Cloud Spanner**: `com.google.cloud:google-cloud-spanner`

## Comparison Table (Portability-Focused)

| Topic | Cosmos DB (`com.azure:azure-cosmos`) | DynamoDB (AWS SDK v2) | Spanner (`google-cloud-spanner`) |
|---|---|---|---|
| Read-by-key | `CosmosContainer.readItem(id, new PartitionKey(pk), JsonNode.class)` | `DynamoDbClient.getItem(GetItemRequest.builder().tableName(...).key(keyMap).build())` | `DatabaseClient.singleUse().readRow(table, Key.of(...), columns)` (or SQL with primary key filter) |
| Upsert / Put | `CosmosContainer.upsertItem(doc, options)` (replace semantics by key) | `DynamoDbClient.putItem(PutItemRequest.builder()...)` (replace-by-key unless conditional) | `Mutation.newInsertOrUpdateBuilder(table)` plus an authoritative SDK `data` envelope; compatible physical mirrors are written, while omitted/null/incompatible mirrors are cleared to typed null |
| Delete-by-key | `CosmosContainer.deleteItem(id, new PartitionKey(pk), options)` (404 when missing) | `DynamoDbClient.deleteItem(DeleteItemRequest.builder()...)` (idempotent when unconditional) | `Mutation.delete(table, KeySet.singleKey(Key.of(...)))` (missing row is effectively idempotent) |
| Query API shape | SQL string via `CosmosContainer.queryItems(querySpec, options, JsonNode.class)` | Expression-based `QueryRequest` (key condition, filter expressions) / `ScanRequest` | SQL string via `DatabaseClient.singleUse().executeQuery(Statement.of(sql))` |
| Pagination / continuation | `CosmosPagedFlux.byPage(token, preferredPageSize)` continuation tokens are opaque strings | Responses contain `LastEvaluatedKey`; next page uses `ExclusiveStartKey` (requires adapter-defined token serialization) | SQL result sets are streamed; no user-facing continuation token for query paging (capability-gate as unsupported) |
| Request ID / correlation ID | `CosmosDiagnostics` + response headers include request/trace ids (surface sanitized) | AWS response metadata includes request id; exceptions include request id and status code | gRPC/HTTP codes via `SpannerException`; request identifiers are not consistently user-facing; rely on tracing hooks where available |
| Auth failures | Cosmos exceptions for 401/403 (`CosmosException.getStatusCode()`) | `DynamoDbException` / `AwsServiceException` with status 401/403-style failures | `SpannerException` with `ErrorCode.UNAUTHENTICATED` / `PERMISSION_DENIED` |
| Throttling / quota | Cosmos 429 with retry-after; throttling retry options are first-class | Provisioned throughput / throttling exceptions are retryable per AWS retry strategy | `RESOURCE_EXHAUSTED` / `UNAVAILABLE` map to retryable transient failures depending on operation |
| Not found | Cosmos 404 `CosmosException` | Item missing often returns empty `GetItemResponse` rather than an exception; missing table surfaces service errors | `NOT_FOUND` errors for missing database/table (depending on call) |
| Conflict | Cosmos 409 / precondition failures | Conditional check failures (`ConditionalCheckFailedException`) | `ALREADY_EXISTS` / `FAILED_PRECONDITION` / `ABORTED` (ABORTED is usually retryable) |

## Portability Pitfalls (What the Multicloud DB SDK must normalize or flag)

- **Key model mismatch**
  - Cosmos DB point ops require both `id` and `partition_key`.
  - DynamoDB requires partition key and optionally sort key (typed attribute values).
  - Spanner keys are *the full primary key tuple across columns*.

- **“Upsert” isn’t identical**
  - DynamoDB `put_item` is a full replace-by-key unless you add conditions.
  - Cosmos `upsert_item` replaces the document for the key.
  - Raw Spanner `insert_or_update` preserves unspecified columns, but the SDK
    writes a complete authoritative `data` envelope and clears supported
    physical mirrors that are omitted, null, or runtime-incompatible. SDK
    reads and portable queries therefore have full-replacement semantics.

- **Delete idempotency differs by default**
  - DynamoDB unconditional deletes are idempotent.
  - Spanner `Transaction.delete` is idempotent for non-existent rows.
  - Cosmos `delete_item` raises `CosmosResourceNotFoundError` when the item does not exist.

- **Pagination tokens are not portable**
  - Cosmos uses opaque continuation tokens (SDK exposes them via `ItemPaged.by_page(continuation_token=...)`).
  - DynamoDB uses `LastEvaluatedKey` (structured key dict).
  - Spanner query results are streamed; pagination is typically done with `LIMIT` + ordering/keyset patterns, not continuation tokens.

- **Transaction semantics**
  - Spanner is transaction-centric; read-write work is typically wrapped in `run_in_transaction()` and may retry on `Aborted`.
  - DynamoDB and Cosmos have more limited/structured transactional semantics (e.g., per-item conditionals; Cosmos transactional batches are scoped).

## Design Implications for Multicloud DB’s Portable Contract

- Treat **continuation** as an opaque `continuation_token` string in the portable API, even if the provider uses a structured key (DynamoDB) or has no native token (Spanner).
- Make **delete-by-key idempotent** in the portable contract (normalize Cosmos 404-on-delete into success). Callers needing to detect a missing key should use `read()`, which returns `null` on every provider when the key does not exist; an opt-in “strict delete” mode was considered and rejected as unnecessary given that `read()` already provides a portable, non-destructive existence probe.
- Model **upsert semantics explicitly** (e.g., `UPSERT_REPLACE_ALL` vs `UPSERT_PATCH_COLUMNS`) and capability-gate the stronger semantics for providers that can’t match it.
- Expose **field-level patch** as a first-class portable operation rather than leaving callers to read-modify-write. Cosmos uses `patchItem`, DynamoDB uses `UpdateItem`, and Spanner applies its `data` document envelope in a retryable transaction. Their existence semantics differ (Cosmos `replace`/`remove` fail on a missing path; DynamoDB `REMOVE` silently no-ops and `UpdateItem` creates a missing item; Spanner checks the envelope in its transaction). The portable contract therefore takes the **strictest** interpretation as the least common denominator and makes the permissive adapters enforce it: Cosmos validates strict/nested state by point read and then binds `patchItem` to a server-side, *path-scoped* filter predicate — an `IS_DEFINED` check per addressed path, plus a `BETWEEN` result bound for an integral `INCREMENT` — never an item-scoped `If-Match` ETag, so concurrent increments and concurrent writes to unaddressed fields are not lost, DynamoDB uses a condition expression, and Spanner checks in its transaction. Delivered in US28 / FR-181-FR-192.
- Capability-gate **nested-path addressing** separately from patch itself. Cosmos and DynamoDB address a document sub-path natively; Spanner stores nested containers in its document envelope and defers nested JSON traversal from the v1 compatibility scope. This asymmetry is declared (`NESTED_PATCH` + `UNSUPPORTED_CAPABILITY`) rather than emulated; the decision is about supported traversal and compatibility scope, not an assertion that a future transactional implementation must have a lost-update window.

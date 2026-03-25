# Quick-Start Guide: Issue 25 Features

**Branch**: `users/allekim/feature/issue_25`

## Overview

This guide shows how to use the new capabilities introduced in this feature:
1. Result Set Control (Top N / ORDER BY)
2. Document TTL and Write Metadata
3. Uniform Document Size limit
4. (Internal: Spanner diagnostics + SpannerConstants — no API changes)

---

## 1. Result Set Control — Top N and ORDER BY

```java
// Get the 5 most recent positions for a portfolio (ORDER BY requires Cosmos or Spanner)
QueryRequest request = QueryRequest.builder()
    .expression("portfolioId = @pid")
    .parameters(Map.of("pid", "portfolio-alpha"))
    .partitionKey("portfolio-alpha")
    .orderBy("timestamp", SortDirection.DESC)
    .limit(5)
    .build();

QueryPage page = client.query(address, request, OperationOptions.defaults());
page.items().forEach(item -> System.out.println(item.toPrettyString()));
```

```java
// DynamoDB does not support ORDER BY — this will throw UNSUPPORTED_CAPABILITY
QueryRequest badRequest = QueryRequest.builder()
    .expression("status = @s")
    .parameters(Map.of("s", "active"))
    .orderBy("createdAt", SortDirection.ASC)  // capability-gated
    .build();
// On DynamoDB: throws HyperscaleDbException(UNSUPPORTED_CAPABILITY)
```

```java
// Top N without ORDER BY works on all providers
QueryRequest limitOnly = QueryRequest.builder()
    .expression("status = @s")
    .parameters(Map.of("s", "active"))
    .limit(10)
    .build();
```

---

## 2. Document TTL and Write Metadata

### Setting TTL on a document

```java
// Create a document that expires in 300 seconds (5 minutes)
OperationOptions optionsWithTtl = OperationOptions.builder()
    .ttlSeconds(300)
    .build();

client.create(address, key, document, optionsWithTtl);
// On Spanner: throws HyperscaleDbException(UNSUPPORTED_CAPABILITY) — Spanner does not support row-level TTL
```

### Reading document metadata

```java
OperationOptions optionsWithMeta = OperationOptions.builder()
    .includeMetadata(true)
    .build();

DocumentResult result = client.read(address, key, optionsWithMeta);
ObjectNode doc = result.document();

result.metadata().ifPresent(meta -> {
    meta.remainingTtlSeconds().ifPresent(ttl -> System.out.println("TTL remaining: " + ttl + "s"));
    meta.writeTimestamp().ifPresent(ts -> System.out.println("Last written: " + ts));
});
```

### Without metadata (backward compatible)

```java
// Existing callers: pass OperationOptions.defaults() — no metadata overhead
DocumentResult result = client.read(address, key, OperationOptions.defaults());
ObjectNode doc = result.document();  // same as before
```

---

## 3. Uniform Document Size Limit

The SDK enforces a **400 KB** maximum document size across all providers (driven by DynamoDB's limit).

```java
// Check the limit programmatically
int maxBytes = client.getDocumentSizeLimit();
System.out.println("Max document size: " + maxBytes + " bytes");  // 409600

// Oversized documents are rejected before any I/O
ObjectNode largeDoc = buildLargeDocument();  // >400 KB
try {
    client.create(address, key, largeDoc, OperationOptions.defaults());
} catch (HyperscaleDbException e) {
    if (e.error().category() == HyperscaleDbErrorCategory.INVALID_REQUEST) {
        System.out.println("Document too large: " + e.error().message());
    }
}
```

---

## 4. Capability Checking

Before using capability-gated features, check provider support:

```java
// Check if the provider supports ORDER BY
if (client.capabilities().stream().anyMatch(c -> c.name().equals(Capability.ORDER_BY) && c.supported())) {
    // Safe to use ORDER BY
}

// Check if the provider supports row-level TTL
if (client.capabilities().stream().anyMatch(c -> c.name().equals(Capability.ROW_LEVEL_TTL) && c.supported())) {
    // Safe to set TTL on documents
}
```

---

## Provider Configuration

No new configuration keys. Switch providers via existing configuration (see parent quickstart at `specs/001-clouddb-sdk/quickstart.md`).

# Feature Specification: Result Set Control, TTL/Write Metadata, Uniform Document Size, and Provider Diagnostics

**Feature Branch**: `users/allekim/feature/issue_25`  
**Created**: 2026-03-25  
**Status**: Draft  
**Parent Spec**: [`specs/001-clouddb-sdk/spec.md`](../../../../001-clouddb-sdk/spec.md)

## Summary

This feature implements four new capability areas added to the Hyperscale DB SDK spec in the customer gap analysis refinements (commit b54b962). All prior phases (T001–T135) are complete. This feature tracks the remaining spec work.

## Scope

### 1. Provider Constants Centralization — Completion (FR-049, FR-050, FR-051)

**Status**: Partially done.
- `CosmosConstants`, `DynamoConstants`, `OperationNames` exist ✅
- `SpannerConstants` is **missing** ❌ (FR-049)
- `OperationNamesTest` (duplicate-prevention test, SC-017) is **missing** ❌ (FR-050)
- Spanner structured DEBUG-level diagnostics on data-plane operations are **missing** ❌ (FR-051)

### 2. User Story 5 — Result Set Control: Top N and Ordering (Priority: P1)

FR-052–FR-055. `QueryRequest` must support:
- Optional result limit (`limit(int n)`) — Top N, applied post-filter
- Optional ORDER BY (`orderBy(String field, SortDirection direction)`) — capability-gated
- Both combinable with filter expression, partition key scope, and each other

Provider translation:
- **Cosmos DB**: `TOP N` in SELECT, `ORDER BY field ASC/DESC`
- **DynamoDB**: `LIMIT N` clause in PartiQL (`executeStatement`)
- **Spanner**: `LIMIT N`, `ORDER BY` in GoogleSQL

Capability: ORDER BY is capability-gated. DynamoDB does not support ORDER BY.

### 3. User Story 6 — Document TTL and Write Metadata (Priority: P2)

FR-056–FR-059.
- Write operations (`create`, `upsert`) accept optional TTL in seconds
- Row-level TTL is capability-gated; fail-fast on unsupported providers
- Read operations optionally return `DocumentMetadata` envelope: approximate remaining TTL + last write timestamp
- Provider mappings:
  - **Cosmos DB**: `_ttl` field on document, `_ts` for write timestamp
  - **DynamoDB**: `ttlExpiry` attribute (epoch seconds), stream record for write timestamp approximation
  - **Spanner**: no native row-level TTL (capability absent); write timestamp via `PENDING_COMMIT_TIMESTAMP()` column

### 4. User Story 7 — Uniform Document Size and Quota Limits (Priority: P2)

FR-060–FR-064.
- SDK enforces 400 KB uniform document size limit (DynamoDB LCD) before sending to any provider
- Reject oversized documents with `InvalidRequest` category error before any I/O
- Expose limit programmatically
- Surface provider-specific quota failures (partition size, throughput) via standard error model

## Acceptance Criteria

Derived from spec SC-017–SC-024 and FR-049–FR-064. See parent spec checklist sections:
- Uniform Document Size and Quota Limits
- Document TTL and Write Metadata
- Result Set Control (Top N / ORDER BY)

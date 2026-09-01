# Feature Specification: Portable Partial Update

**Feature Branch**: `002-partial-update`  
**Created**: 2026-09-01  
**Status**: Draft  
**Input**: Make `update()` perform portable, shallow partial updates across Cosmos DB, DynamoDB, and Spanner according to the approved design.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Update Selected Fields Without Data Loss (Priority: P1)

As an application developer, I can update selected top-level fields without
resending the complete document, and fields omitted from the request remain
unchanged on every supported provider.

**Why this priority**: Cosmos DB and DynamoDB currently replace the complete
document while Spanner performs a partial update. This silent divergence can
delete data and violates the SDK's portability contract.

**Independent Test**: Store a document containing multiple fields, update one
field, and verify on Cosmos DB, DynamoDB, and Spanner that the requested field
changed and every omitted field retained its original value.

**Acceptance Scenarios**:

1. **Given** an existing document with `status`, `owner`, and `region`, **When**
   the caller updates only `status`, **Then** `status` is overwritten while
   `owner` and `region` are preserved.
2. **Given** an existing document without `priority`, **When** the caller updates
   `priority`, **Then** the new top-level field is created.
3. **Given** no document for the supplied key, **When** the caller invokes
   `update()`, **Then** the operation fails with `NOT_FOUND` and does not create
   a document.

---

### User Story 2 - Receive Predictable Shallow-Update Semantics (Priority: P1)

As an application developer, I receive the same shallow set/replace semantics
for nulls, objects, arrays, and literal field names on every provider.

**Why this priority**: Inferring nested paths or recursively merging values would
produce provider-specific behavior and make updates difficult to replay safely.

**Independent Test**: Apply the same field payload to equivalent documents on
all providers and verify identical stored documents, including null, object,
array, and punctuation-containing field names.

**Acceptance Scenarios**:

1. **Given** a stored object-valued field, **When** the caller supplies a new
   object for that field, **Then** the complete top-level object is replaced
   rather than recursively merged.
2. **Given** a stored array-valued field, **When** the caller supplies a new
   array, **Then** the complete array is replaced rather than appended or merged.
3. **Given** an existing field, **When** the caller supplies `null`, **Then** JSON
   null is stored and the field is not removed.
4. **Given** a field named `name.first`, **When** the caller updates it, **Then**
   it is treated as one literal top-level field and not as a nested path.
5. **Given** the same update is submitted more than once, **When** each attempt
   completes, **Then** the final document is identical to the result of one
   successful application.
6. **Given** concurrent updates to different top-level fields, **When** both
   complete successfully, **Then** both changes are retained.

---

### User Story 3 - Understand Provider Payload Boundaries (Priority: P2)

As an application developer, I can discover whether a provider guarantees very
wide partial updates and receive a consistent, actionable error when a valid
request exceeds that provider's native atomic-update envelope.

**Why this priority**: Provider limits differ by operation count and encoded
size. The SDK must expose those differences without imposing one provider's
limit on all providers or silently changing atomicity.

**Independent Test**: Query the extended-payload capability for each provider,
submit requests inside and outside native envelopes, and verify that ordinary
requests succeed while over-envelope requests fail before provider I/O with
structured limit details.

**Acceptance Scenarios**:

1. **Given** an 11-field update within all size limits, **When** it is submitted
   to any provider, **Then** all 11 fields are updated atomically.
2. **Given** a Cosmos DB update requiring more than 100 patch requests or
   exceeding the 2 MB serialized transactional-batch limit, **When** it is
   submitted, **Then** it fails with `UNSUPPORTED_CAPABILITY`, reason
   `cosmos_transactional_batch_limit`, before provider I/O.
3. **Given** a DynamoDB update whose encoded update expression exceeds 4 KB,
   **When** it is submitted, **Then** it fails with
   `UNSUPPORTED_CAPABILITY`, reason `dynamodb_update_expression_limit`, before
   provider I/O.
4. **Given** a provider that reports the extended-payload capability as
   unsupported, **When** an ordinary update fits its native envelope, **Then**
   the update remains available and succeeds.
5. **Given** a Cosmos DB transactional batch exceeds its five-second service
   execution limit, **When** the vendor SDK does not recover it, **Then** the
   caller receives a normalized timeout or transient operation error rather than
   `UNSUPPORTED_CAPABILITY`.

---

### User Story 4 - Migrate Full-Document Replacement Callers (Priority: P2)

As an existing SDK user, I can identify the `update()` behavior change and move
full-document replacement code to `upsert()`.

**Why this priority**: The corrected behavior is intentionally breaking for
Cosmos DB and DynamoDB callers that relied on omitted fields being deleted.

**Independent Test**: Follow the migration documentation to replace a complete
document using `upsert()` and verify that `update()` now preserves omitted
fields.

**Acceptance Scenarios**:

1. **Given** existing code that used `update()` for complete replacement,
   **When** the user follows the migration guide, **Then** it uses `upsert()`
   instead.
2. **Given** `upsert()` is used as the migration target with a missing key,
   **When** the operation executes, **Then** the documentation clearly warns
   that a new document is created rather than returning `NOT_FOUND`.
3. **Given** a user reviews release documentation, **When** they read the
   partial-update change, **Then** they can identify the old and new behavior,
   affected providers, and required migration.

### Edge Cases

- An empty field map is rejected as `INVALID_REQUEST` before provider I/O.
- Reserved fields (`id`, provider key fields, TTL bookkeeping fields, `data`,
  and `_`-prefixed names) remain rejected before provider I/O.
- A field name containing `.`, `/`, or `~` is handled as literal top-level
  content; provider escaping never appears in the public API.
- TTL consumes one Cosmos patch operation and is included exactly once when a
  wide update is divided into patch requests.
- If one part of a provider-native atomic update fails, none of the requested
  field changes are committed.
- A provider failure status associated with an atomic batch is mapped from the
  underlying failed operation; dependent failure statuses do not replace the
  actual cause.
- The common 399 KB payload validation still applies before provider-specific
  atomic-update envelope checks.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: `update()` MUST interpret its payload as top-level fields to set or
  replace, not as a complete replacement document.
- **FR-002**: `update()` MUST preserve every stored top-level field omitted from
  the request.
- **FR-003**: `update()` MUST overwrite a stored top-level field present in the
  request.
- **FR-004**: `update()` MUST create a requested top-level field that does not
  yet exist in the stored document.
- **FR-005**: `update()` on a missing document MUST return `NOT_FOUND` and MUST
  NOT create the document.
- **FR-006**: The merge MUST be shallow. Object and array values MUST replace the
  complete value of their named top-level field.
- **FR-007**: A supplied null MUST store JSON null and MUST NOT remove the field.
- **FR-008**: Field-name punctuation, including `.`, `/`, and `~`, MUST be
  interpreted literally and MUST NOT imply nested-path syntax.
- **FR-009**: All fields in one `update()` call MUST be applied atomically.
- **FR-010**: Reapplying the same field update MUST produce the same stored
  document as applying it once.
- **FR-011**: Concurrent successful updates to disjoint top-level fields MUST
  preserve both changes.
- **FR-012**: Existing reserved-field and common 399 KB payload validation MUST
  remain in effect and fail before provider I/O.
- **FR-013**: The payload parameter in `update()` declarations and documentation
  MUST be named `fields`; the public payload type MUST remain
  `Map<String, Object>`.
- **FR-014**: Base set/replace partial update MUST remain universally available
  without a capability gate when a request fits the selected provider's native
  atomic-update envelope.
- **FR-015**: The SDK MUST expose `PARTIAL_UPDATE_EXTENDED_PAYLOAD` to indicate
  whether a provider guarantees every otherwise-valid partial update through the
  common 399 KB payload limit.
- **FR-016**: Cosmos DB and DynamoDB MUST declare
  `PARTIAL_UPDATE_EXTENDED_PAYLOAD` unsupported; Spanner MUST declare it
  supported.
- **FR-017**: Cosmos DB MUST support updates wider than 10 field operations as
  one atomic operation when they fit within 100 patch requests and a 2 MB
  serialized request.
- **FR-018**: A Cosmos DB request exceeding 100 patch requests or 2 MB MUST fail
  before provider I/O with `UNSUPPORTED_CAPABILITY`, reason
  `cosmos_transactional_batch_limit`, and actual/maximum operation counts and
  bytes where measurable.
- **FR-019**: A DynamoDB request with an encoded update expression exceeding
  4 KB MUST fail before provider I/O with `UNSUPPORTED_CAPABILITY`, reason
  `dynamodb_update_expression_limit`, and actual/maximum expression bytes where
  measurable.
- **FR-020**: Operational timeout, throttling, routing, and transient failures
  MUST use the existing normalized operation error categories and MUST NOT be
  reported as unsupported capabilities.
- **FR-021**: Provider adapters MUST rely on vendor SDK retry behavior and MUST
  NOT add a separate partial-update retry loop.
- **FR-022**: A provider retry of a wide atomic update MUST replay the complete
  atomic operation and MUST NOT submit individual portions independently.
- **FR-023**: The feature MUST NOT add `replace()`, nested-path operations,
  field-removal operations, arithmetic operations, or conditional writes.
- **FR-024**: Full-document replacement migration guidance MUST direct callers
  to `upsert()` and MUST state that `upsert()` creates a missing document.
- **FR-025**: API, provider, guide, compatibility, and changelog documentation
  MUST describe the behavior change, provider envelopes, capability declarations,
  error reasons, request/cost differences, and migration path.
- **FR-026**: The same portable conformance suite MUST verify the required
  observable semantics on Cosmos DB, DynamoDB, and Spanner.

### Key Entities

- **Partial Update Field Set**: A map of literal top-level field names to
  absolute replacement values.
- **Document Key**: The portable partition and sort-key identity of the existing
  document to update.
- **Extended Payload Capability**: A provider declaration indicating whether all
  otherwise-valid field shapes through the common payload limit are guaranteed.
- **Provider Limit Details**: Structured error data containing the provider
  reason and measured versus maximum operation or byte limits.

### Assumptions

- The SDK remains in beta, so the team accepts a documented hard behavior change
  without a compatibility flag.
- Known consumers do not require `update()` to preserve full-replacement
  `NOT_FOUND` behavior; `upsert()` is an acceptable migration target.
- Spanner's existing update implementation already satisfies the observable
  shallow partial-update contract.
- Vendor SDK default retry policies remain responsible for retry execution.

### Out of Scope

- A portable Patch API or explicit nested document path type
- Field removal and server-side arithmetic
- Conditional or compare-and-set updates
- A new full-document `replace()` operation
- A unified document payload type replacing `Map<String, Object>`
- Portable or provider-specific retry configuration
- Spanner update-path cost optimization

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: All portable partial-update conformance scenarios pass unchanged
  against Cosmos DB, DynamoDB, and Spanner.
- **SC-002**: On every provider, updating one field in a document containing at
  least three fields preserves 100% of the omitted fields.
- **SC-003**: An ordinary 11-field update succeeds atomically on every provider.
- **SC-004**: Repeating the same update twice produces a document byte-equivalent
  in logical JSON content to applying it once.
- **SC-005**: Concurrent updates to two disjoint fields retain both changes in
  all provider conformance runs.
- **SC-006**: Provider-envelope rejection tests observe zero provider network
  calls and return the required `UNSUPPORTED_CAPABILITY` reason and limit
  details.
- **SC-007**: All affected public API declarations, provider adapters,
  compatibility documentation, guides, and changelogs consistently describe
  partial-update semantics and the `upsert()` migration path.

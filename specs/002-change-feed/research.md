# Change Feed (User Story 8) — Design Plan

## Problem

Spec FR-065..070 / US8 describes a portable change-feed abstraction. Today only the
`Capability.CHANGE_FEED` flag exists; there is no API/SPI surface, no provider impl,
and no tests. Need an LCD design that fits all three providers.

## Provider native surfaces (LCD-relevant facts)

| Aspect | Cosmos (azure-cosmos 4.78.0) | Dynamo Streams (sdk v2 2.34.0) | Spanner Change Streams (6.62.0) |
|---|---|---|---|
| Pull API | `queryChangeFeed(CosmosChangeFeedRequestOptions, FeedRange)` | `GetShardIterator` + `GetRecords` | `SELECT * FROM READ_<stream>(...)` |
| Start: beginning | `createForProcessingFromBeginning()` | `TRIM_HORIZON` (24h) | `start_timestamp = epoch` |
| Start: point-in-time | ✅ `createForProcessingFromPointInTime(Instant)` | ❌ no timestamp iterator | ✅ `start_timestamp` |
| Start: checkpoint | ✅ `createForProcessingFromContinuation(String)` | ✅ `AFTER_SEQUENCE_NUMBER` | ✅ `partition_token` + watermark |
| Logical partition-key scope | ✅ `FeedRange.forLogicalPartition(pk)` | ❌ shards are physical | ❌ partition tokens are physical row ranges |
| Delete events | ⚠ require `AllVersionsAndDeletes` provisioning | ✅ native `REMOVE` | ✅ native |
| Full new-item image | ✅ default in `LatestVersion` | ⚠ requires `NEW_IMAGE` / `NEW_AND_OLD_IMAGES` | ⚠ requires `NEW_ROW` / `NEW_ROW_AND_OLD_VALUES` |
| Event timestamp | UTC | `ApproximateCreationDateTime` (UTC) | `commit_timestamp` (UTC) |
| Retention | container-driven | 24 h hard cap | configurable |
| Push alternative (out of scope v1) | Change Feed Processor + lease container | KCL | Dataflow connector |

## Programming model: synchronous pull, with a polling iterator helper

Two layers:

1. **SPI primitive** — `readChanges(request)` returns one page + opaque token.
2. **API helper** — `ChangeFeedIterator` wraps the primitive in a polling loop
   with idle backoff, cancellation, and checkpoint hand-off. Internally it
   only ever calls the SPI primitive — it adds no provider-specific code.

**Why pull as the SPI:**
- Pull is the LCD: every provider exposes it natively and lightly.
- Push semantics differ wildly — Cosmos needs a lease container, Dynamo needs KCL +
  DynamoDB lease tables, Spanner steers users to Dataflow. We would have to ship
  three bespoke runtimes to make a portable push API. Defer.
- Application owns checkpoint persistence (matches CRUD/query model already used
  by `QueryPage.continuationToken`).

**Why add the iterator helper:**
- Spec US8 independent test calls for "subscribe to changes" UX. A bare one-shot
  pull primitive forces every user to reinvent polling/backoff/idle handling and
  often leads to busy loops. The helper closes that gap without adopting a managed
  push runtime.

## Public API (added to `MulticloudDbClient` and SPI)

```java
ChangeFeedPage readChanges(ChangeFeedRequest request, OperationOptions options);
```

### Types (in `multiclouddb-api`)

```java
public final class ChangeFeedRequest {
    ResourceAddress address;                // required
    StartPosition start;                    // required
    MulticloudDbKey partitionKeyScope;      // optional; capability-gated
    boolean includeDeletes;                 // capability-gated
    NewItemStateMode newItemStateMode;      // default INCLUDE_IF_AVAILABLE
    int maxBatchSize;                       // default 100; provider clamps
}

public enum NewItemStateMode {
    OMIT,                  // never include
    INCLUDE_IF_AVAILABLE,  // include when provider/config provides it; null otherwise
    REQUIRE                // include or fail with UNSUPPORTED_CAPABILITY
}

public sealed interface StartPosition {
    record BeginningOfAvailableChanges() implements StartPosition {}  // see retention note
    record AtTimestamp(Instant utc) implements StartPosition {}        // capability-gated
    record FromCheckpoint(String token) implements StartPosition {}
}

public enum ChangeType {
    WRITE,         // create or update — LCD type when provider can't distinguish
    CREATE,        // distinct create — only when CHANGE_FEED_DISTINCT_CREATE_UPDATE supported
    UPDATE,        // distinct update — only when CHANGE_FEED_DISTINCT_CREATE_UPDATE supported
    DELETE
}

public final class ChangeEvent {
    String eventId;                          // provider sequence/LSN, as string; for dedup
    MulticloudDbKey key;
    ChangeType type;
    Instant timestamp;                       // UTC, RFC3339 on toString
    Map<String, Object> newItemState;        // null on DELETE or when OMIT/unavailable
}

public final class ChangeFeedPage {
    List<ChangeEvent> events;                // possibly empty
    String continuationToken;                // never null; opaque; resume here
    boolean idle;                            // true when provider returned an empty batch
    OperationDiagnostics diagnostics;        // optional: lag estimate, RU/RCU charge, watermark
}
```

### Capabilities (extends existing `CHANGE_FEED`)

| Capability | Gates |
|---|---|
| `CHANGE_FEED` (existing) | basic feed: `WRITE` events, beginning + checkpoint start |
| `CHANGE_FEED_DISTINCT_CREATE_UPDATE` | event types `CREATE` vs `UPDATE` rather than `WRITE` |
| `CHANGE_FEED_DELETE_EVENTS` | `includeDeletes=true` |
| `CHANGE_FEED_POINT_IN_TIME` | `StartPosition.AtTimestamp` |
| `CHANGE_FEED_FULL_ITEM_IMAGE` | `newItemStateMode=REQUIRE` |
| `CHANGE_FEED_PARTITION_KEY_SCOPE` | non-null `partitionKeyScope` (requires spec amendment of FR-069) |

Capability matrix:

| Capability | Cosmos | Dynamo | Spanner |
|---|---|---|---|
| `CHANGE_FEED` | ✅ | ✅ | ✅ |
| `CHANGE_FEED_DISTINCT_CREATE_UPDATE` | only with `AllVersionsAndDeletes` mode | ✅ (`INSERT` vs `MODIFY`) | ✅ (mod_type) |
| `CHANGE_FEED_DELETE_EVENTS` | only with `AllVersionsAndDeletes` mode | ✅ | ✅ |
| `CHANGE_FEED_POINT_IN_TIME` | ✅ | ❌ | ✅ |
| `CHANGE_FEED_FULL_ITEM_IMAGE` | ✅ (default Latest mode) | only with `NEW_IMAGE` / `NEW_AND_OLD_IMAGES` | only with `NEW_ROW` / `NEW_ROW_AND_OLD_VALUES` |
| `CHANGE_FEED_PARTITION_KEY_SCOPE` | ✅ | ❌ | ❌ |

> **Cosmos `LatestVersion` caveat:** the default change feed mode does not reliably
> distinguish create from update — only the post-image is exposed. The basic
> `CHANGE_FEED` capability therefore promises only `WRITE` events; precise
> create/update labelling is a separate capability that on Cosmos requires
> `AllVersionsAndDeletes` provisioning.

When an unsupported capability is requested, the SPI raises a
`MulticloudDbException` of category `UNSUPPORTED` with an actionable message
(matching how other capability gates already work). No silent degradation.

## Continuation token strategy

Token is opaque to the application; SDK chooses the encoding. Envelope is
base64 of a versioned JSON document with the same shape across providers:

```
{
  "v": 1,                       // schema version
  "p": "cosmos|dynamo|spanner", // provider id
  "r": "<resource fingerprint>",// e.g. Dynamo stream ARN, Spanner change stream
                                // FQN, Cosmos container RID/feed mode
  "c": <provider cursor>        // Cosmos: native string; Dynamo: array of
                                // {shardId, sequenceNumber, parent?, closed?};
                                // Spanner: array of {partitionToken,
                                // watermarkUtc, child?, retired?}
}
```

`readChanges()` validates `v` (known versions), `p` (matches current provider),
and `r` (matches addressed resource fingerprint). Mismatches fail with
`INVALID_REQUEST`. Future provider-cursor evolutions bump `v`.

**Token bloat reality:** both Dynamo and Spanner accumulate per-shard /
per-partition cursors after splits, not just Spanner. Document as: tokens are
opaque blobs, possibly several KB after long-lived streams cross many splits;
persist as `TEXT` / blob, not `varchar(255)` / HTTP header.

## Ordering contract

Documented as: **events within a single page are ordered by the provider's
native commit/ingest order within the cursor's scope. No global ordering
across the collection is guaranteed.**

Cursor scope:
- Cosmos with `partitionKeyScope` set: logical partition key.
- Cosmos otherwise / Dynamo / Spanner: provider's native physical
  shard/partition. `partitionKeyScope` is rejected as `UNSUPPORTED_CAPABILITY`
  on Dynamo and Spanner — we do **not** silently emulate via full-feed scan.

**Spec impact:** FR-069's wording ("MUST support partition-scoped consumption")
needs to be amended to capability-gated alignment with FR-068. Tracked as part
of this work.

## Delivery semantics

**At-least-once.** Providers may redeliver events around checkpoint boundaries,
shard/partition transitions, and retries. Each `ChangeEvent.eventId` is a
stable, provider-supplied identifier (Cosmos: `_lsn`/`_etag`; Dynamo:
`SequenceNumber`; Spanner: `commit_timestamp + record_sequence`) that
applications can use for downstream deduplication.

## Stream lifecycle / expired checkpoints

Add a new error category `CHECKPOINT_EXPIRED` to `MulticloudDbErrorCategory`
(matches existing `expandable string enum` pattern in
`MulticloudDbErrorCategory.java:46`). Surfaced when a provider rejects a
checkpoint as trimmed/expired (Dynamo 24-h retention, Cosmos retention policy,
Spanner change-stream retention window).

If a stream's underlying resource is recreated under the same logical name,
the resource fingerprint in the token will mismatch → `INVALID_REQUEST`.

## Provisioning prerequisites (documentation only)

These are surfaced as preconditions in the docs, not in code:

- Cosmos delete events → enable `AllVersionsAndDeletes` on the change feed mode.
- Dynamo full image → set table `StreamSpecification.StreamViewType` to
  `NEW_IMAGE` or `NEW_AND_OLD_IMAGES`.
- Spanner full image → `CREATE CHANGE STREAM ... OPTIONS (value_capture_type =
  'NEW_ROW')` (or `NEW_ROW_AND_OLD_VALUES`).
- Spanner & Cosmos: change streams must exist before `readChanges()`.

## Out of scope (v1)

- Push / managed processor model (lease container, KCL, Dataflow). Tracked as
  follow-up.
- Replaying a stream from a sequence number/checkpoint that has been trimmed
  — surface as `MulticloudDbErrorCategory.PROVIDER_ERROR` with a message
  matching FR-066's edge case in spec.
- Bulk replay / backfill (separate concern).
- Old-image / pre-update state (asymmetric across providers).

## Conformance test plan

- `multiclouddb-conformance/src/test/java/com/multiclouddb/conformance/us8/ChangeFeedConformanceTest.java`
  - basic create/update propagation
  - checkpoint roundtrip resumes from after the last event
  - capability-gated tests skip when capability not supported (matches `us2`
    pattern for capability tests)
  - point-in-time start (Cosmos + Spanner only — Dynamo skipped via capability)
  - delete-event gating (Cosmos default mode → expect UNSUPPORTED)

## Tasks (rough)

1. API types: `ChangeEvent`, `ChangeFeedRequest`, `ChangeFeedPage`,
   `StartPosition`, `ChangeType` in `multiclouddb-api`.
2. New capability constants in `Capability.java`.
3. SPI: add `readChanges(...)` to `MulticloudDbProviderClient`; default
   throws `UNSUPPORTED`.
4. Default client wiring in `DefaultMulticloudDbClient`.
5. Cosmos provider: implement using `queryChangeFeed` + `FeedRange`.
6. Dynamo provider: implement using `GetShardIterator` + `GetRecords`,
   internal token = `{streamArn, shardId, sequenceNumber}`.
7. Spanner provider: implement TVF read with internal multi-partition cursor.
8. Conformance tests under `us8/`.
9. Docs: `docs/configuration.md` (provisioning prerequisites per provider),
   per-module CHANGELOG entries.
10. Spec: tick the now-implemented checkboxes in `spec.md` US8 checklist;
    record FR-067/068 implementation status notes inline (matches the
    convention used for FR-077).

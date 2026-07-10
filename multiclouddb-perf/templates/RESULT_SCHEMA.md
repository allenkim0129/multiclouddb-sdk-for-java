# Raw result CSV schema

One row per single measured operation (append-only). Written by the harness to
`perf/results/raw/<run_id>.csv`. A JSON Lines mirror may be written alongside.

| Column | Type | Description |
|---|---|---|
| `run_id` | string | Groups all rows from one harness invocation (e.g. `2026-07-08T21-00Z-dynamo-S1`). |
| `timestamp_utc` | ISO-8601 | When the op completed. |
| `provider` | enum | `cosmos` \| `dynamo` \| `spanner`. |
| `region` | string | Cloud region of the DB resource. |
| `host_label` | string | Client host descriptor (e.g. `gh-runner-linux`, `mac-m3-local`). |
| `jdk` | string | JDK vendor+version. |
| `operation` | enum | `create` \| `read` \| `update` \| `upsert` \| `delete` \| `query` \| `readChanges`. |
| `scenario` | string | Scenario id `S1`..`S7`. |
| `doc_size_bytes` | int | Payload size for the op (0 for reads/deletes). |
| `page_size` | int | `maxPageSize` used (blank if N/A). |
| `threads` | int | Concurrency level of the run. |
| `iteration` | int | Per-op sequence within the run (post-warmup). |
| `latency_ms` | float | Wall-clock latency of this single op, milliseconds. |
| `success` | bool | `true`/`false`. |
| `error_category` | string | `MulticloudDbErrorCategory` when `success=false`, else blank. |
| `cost_unit` | string | `RU` \| `RCU` \| `WCU` \| `PU-ms` \| blank. |
| `cost_value` | float | Provider-reported cost from diagnostics (blank if unavailable). |
| `provisioned_capacity` | string | e.g. `400RU/s`, `on-demand`, `100PU`. |
| `sdk_version` | string | multiclouddb SDK version under test. |
| `notes` | string | Free-form (cold-start, throttled, cross-region, etc.). |

Warmup iterations MUST NOT be written (or must be marked and excluded by aggregation).

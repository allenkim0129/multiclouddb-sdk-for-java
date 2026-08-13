# Raw result CSV schema

One row per measured operation. Warmup iterations are never written.

| Column | Type | Description |
|---|---|---|
| `run_id` | string | Groups rows from one harness invocation. |
| `timestamp_utc` | ISO-8601 | Completion time of the measured op. |
| `provider` | enum | `cosmos` \| `dynamo` \| `spanner`. |
| `region` | string | Probed/config region recorded for the provider resource. |
| `comparison_region` | string | Colocation label used for fairness checks/reporting. |
| `host_label` | string | Client host descriptor. |
| `jdk` | string | JDK vendor+version. |
| `operation` | enum | `create` \| `read` \| `update` \| `upsert` \| `delete` \| `query` \| `readChanges`. |
| `workload` | enum | `mixed` \| `read` \| `write` \| `query` \| `changefeed`. |
| `scenario` | string | Scenario id (existing `S1..S7` or equivalent). |
| `doc_size_bytes` | int | Payload size for the op (0 for read/delete). |
| `page_size` | int | Query/change-feed page size (blank if N/A). |
| `threads` | int | Worker-thread count for the run. |
| `iteration` | int | Post-warmup sequence number within the phase. |
| `start_offset_ms` | float | Actual start offset from the first measured start in this phase. |
| `end_offset_ms` | float | Actual completion offset from the first measured start in this phase. |
| `latency_ms` | float | Wall-clock latency of this operation. |
| `success` | bool | `true`/`false`. |
| `error_category` | string | `MulticloudDbErrorCategory` when `success=false`. |
| `cost_unit` | string | Provider-native consumed-unit dimension for the op (`RU`, `RCU`, `WCU`, `PU-ms`, blank). |
| `cost_value` | float | Provider-reported consumed units for the op (blank if unavailable). |
| `retry_count` | int | Provider-observed retries for the op when exposed; blank otherwise. |
| `capacity_limit_unit` | string | Applicable capacity dimension for utilization (`RU/s`, `RCU/s`, `WCU/s`, blank). |
| `capacity_limit_value` | float | Numeric applicable capacity limit for this op dimension when known. |
| `billing_mode` | string | Probed billing/provisioning mode (`manual`, `autoscale`, `PROVISIONED`, `PAY_PER_REQUEST`, `unknown`, ...). |
| `provisioned_capacity` | string | Human-readable probed/config capacity description. |
| `sdk_version` | string | multiclouddb SDK version under test. |
| `target_ops_per_sec` | float | Requested offered-load target; blank/0 means unbounded mode. |
| `notes` | string | Free-form annotations (e.g. scoped query, unsupported capability). |

These fields are sufficient to derive offered ops/s, achieved/offered ratio,
provider-native consumed units/sec, capacity utilization when a numeric limit is
known, throttled count/rate, retry totals, and validity.

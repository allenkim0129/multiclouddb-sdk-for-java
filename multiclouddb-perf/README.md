# Performance tests (live accounts) — fair cross-provider method

> Live accounts only. Do **not** run in CI. Compare providers only when client placement,
> offered load, workload profile, and provisioned capacity are deliberately matched.

The `multiclouddb-perf` harness runs the real portable `MulticloudDbClient` in one JVM,
records one CSV row per measured operation, then renders Markdown + HTML reports.

## Fair-test checklist

Use the same for every provider in a comparison set:

- **Offered load**: `--target-ops-per-sec N` to pace actual operation starts across worker threads.
  Leave it unset/`0` only for max-throughput sweeps (`--threads 1,8,32`).
- **Workload profile**: `--workload read|write|mixed|query` so you do not accidentally compare a
  read-heavy run with a mixed lifecycle.
- **Client placement**: same host/JDK, plus matching `comparison_region` labels.
- **Deterministic capacity**: probe/report actual billing mode + capacity. Use opt-in admin flags to
  pin capacity first when needed.
- **Separate metrics**: compare latency, throughput, and provider-native cost separately.
  **Do not compare Cosmos RU directly with Dynamo RCU/WCU.**

Default validity rule: a row is reported **invalid** when throttled operations exceed **0.1%**.
Override with `--invalid-throttle-rate-pct` when re-rendering reports.

## Configure live accounts (never committed)

Copy the templates to `*.live.properties` (gitignored) and fill in real values.
You can optionally set `multiclouddb.comparisonRegion` to declare cross-cloud regions colocated
(e.g. `westus2` + `us-west-2` → `comparisonRegion=west-us-2-colo`).

## Run examples

### Fair offered-load comparison

```bash
multiclouddb-perf/perf.sh run \
  --providers cosmos,dynamo \
  --workload read \
  --scenarios S1,S6 \
  --threads 8 \
  --target-ops-per-sec 1500 \
  --iterations 500 \
  --region-policy warn
```

### Deterministic Dynamo provisioned-capacity run

```bash
multiclouddb-perf/perf.sh run \
  --providers dynamo \
  --workload write \
  --threads 8 \
  --dynamo-rcu 2000 --dynamo-wcu 2000 \
  --iterations 500
```

### Deterministic Cosmos manual-throughput run

```bash
multiclouddb-perf/perf.sh run \
  --providers cosmos \
  --threads 8 \
  --target-ops-per-sec 1500 \
  --cosmos-ru 20000 \
  --split-wait-seconds 480
```

### Query-only comparison

```bash
multiclouddb-perf/perf.sh run --workload query --threads 8 --target-ops-per-sec 400
```

## Key CLI options

- `--target-ops-per-sec N` — pace actual starts across worker threads; `0`/unset keeps legacy unbounded mode.
- `--threads 1,8,32` — concurrency sweep for saturation/max-throughput analysis.
- `--workload read|write|mixed|query` — explicit workload profiles.
- `--dynamo-rcu N --dynamo-wcu N` — switch/update Dynamo to `PROVISIONED` and wait for `ACTIVE`.
- `--cosmos-ru N` — set Cosmos manual throughput before the run.
- `--enable-dynamo-streams` — opt-in Dynamo Streams for change-feed scenarios.
- `--region-policy warn|fail|ignore` — handle config/probed region or comparison-label mismatches before measurement.
- `--invalid-throttle-rate-pct PCT` — report validity threshold (default `0.1`).

## What the report now shows

- Target offered load, actual offered ops/s, achieved throughput, and achieved/offered ratio.
- Provider-native consumed units/sec and capacity-utilization percentage when a numeric relevant limit is known.
- Probed billing mode (`manual`, `autoscale`, `PROVISIONED`, `PAY_PER_REQUEST`, ...).
- Throttled count/rate, retry totals when surfaced by diagnostics, and row validity.
- Environment metadata including `comparison_region`.

## Offline re-rendering

```bash
multiclouddb-perf/perf.sh report --run 2026-08-13T12-00-00Z-batch
multiclouddb-perf/perf.sh report --combined --invalid-throttle-rate-pct 0.05
```

## Cleanup

```bash
multiclouddb-perf/perf.sh cleanup
```

The harness cleans up its own seeded items, but interrupted runs may leave rows behind.
Provisioning changes are **not** reverted automatically.

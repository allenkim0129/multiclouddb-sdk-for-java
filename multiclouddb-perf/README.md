# Performance tests (live accounts) — fair cross-provider method

> Live accounts only. Do **not** run in CI. Compare providers only when client placement,
> offered load, workload profile, and provisioned capacity are deliberately matched.

The `multiclouddb-perf` harness runs the real portable `MulticloudDbClient` in one JVM,
records one CSV row per measured operation, then renders Markdown + HTML reports.

## Fair-test checklist

Use the same for every provider in a comparison set:

- **Offered load**: set `multiclouddb.perf.targetOpsPerSec` identically in every provider
  property file. `--target-ops-per-sec N` overrides all configs. Use `0` only for
  max-throughput sweeps (`--threads 1,8,32`).
- **Workload profile**: `--workload read|write|mixed|query` selects one profile.
  `--workload all` runs the read, write, and query profiles in one batch and one report.
- **Client placement**: same host/JDK, plus matching `comparison_region` labels. A single client
  cannot be colocated with two clouds at once, so the harness probes each endpoint's TCP RTT at
  run start and the report also presents **service time** (`latency − RTT`). Compare service time
  when the client sits outside both clouds; compare raw latency only from a colocated client.
- **Transport profile**: use Cosmos Gateway HTTP/1.1 and Dynamo Apache HTTP/1.1 with the same
  connection-pool size for the primary comparison. Treat Cosmos HTTP/2 and Direct/RNTBD as
  separate optimization profiles.
- **Deterministic capacity**: configure `multiclouddb.perf.cosmosRu` or the paired
  `multiclouddb.perf.dynamoRcu` / `multiclouddb.perf.dynamoWcu` properties. The harness applies
  them before warmup, waits where required, and probes the actual resulting capacity.
- **Separate metrics**: compare latency, throughput, and provider-native cost separately.
  **Do not compare Cosmos RU directly with Dynamo RCU/WCU.**

Default validity rule: a row is reported **invalid** when throttled operations exceed **0.1%**.
Override with `--invalid-throttle-rate-pct` when re-rendering reports.

## Configure live accounts (never committed)

Copy the templates to `*.live.properties` (gitignored) and fill in real values.
You can optionally set `multiclouddb.comparisonRegion` to declare cross-cloud regions colocated
(e.g. `westus2` + `us-west-2` → `comparisonRegion=west-us-2-colo`).

The templates also contain the reproducible fairness controls:

```properties
# Same value in every provider config
multiclouddb.perf.targetOpsPerSec=100

# Cosmos config
multiclouddb.perf.cosmosRu=1000

# Dynamo config
multiclouddb.perf.dynamoRcu=100
multiclouddb.perf.dynamoWcu=100

# Transport-equivalent HTTP/1.1 profile
# Cosmos config
multiclouddb.connection.connectionMode=gateway
multiclouddb.connection.gatewayMaxConnectionPoolSize=64
multiclouddb.connection.gatewayHttp2Enabled=false
multiclouddb.connection.contentResponseOnWriteEnabled=false

# Dynamo config
multiclouddb.connection.maxConnections=64
```

HTTP/2 is the provider default, so `gatewayHttp2Enabled=false` is now a deliberate opt-out
required for HTTP/1.1 parity with Dynamo's synchronous client — omitting it compares HTTP/2
against HTTP/1.1. For a Cosmos-optimised (non-parity) run instead set `gatewayHttp2Enabled=true`
and `thinClientEnabled=true` to exercise Gateway V2.

`contentResponseOnWriteEnabled=false` suppresses the document body Cosmos otherwise returns on
every write. DynamoDB's `PutItem` returns no item, so leaving it enabled charges Cosmos for
bandwidth its counterpart never pays.

CLI capacity and offered-load options override these properties for one-off experiments.

## Run examples

### Fair offered-load comparison

```bash
multiclouddb-perf/perf.sh run \
  --providers cosmos,dynamo \
  --workload all \
  --scenarios S1,S3,S4,S5 \
  --threads 8 \
  --target-ops-per-sec 80 \
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
- `--workload read|write|mixed|query|all` — explicit workload profile, or all read/write/query
  profiles in one batch and report.
- `--dynamo-rcu N --dynamo-wcu N` — switch/update Dynamo to `PROVISIONED` and wait for `ACTIVE`.
- `--cosmos-ru N` — set Cosmos manual throughput before the run.
- `--enable-dynamo-streams` — opt-in Dynamo Streams for change-feed scenarios.
- `--region-policy warn|fail|ignore` — handle config/probed region or comparison-label mismatches before measurement.
- `--invalid-throttle-rate-pct PCT` — report validity threshold (default `0.1`).

## Property-file controls

- `multiclouddb.perf.targetOpsPerSec` — paced offered load; must match across provider configs.
- `multiclouddb.perf.cosmosRu` — Cosmos manual RU/s applied before warmup.
- `multiclouddb.perf.dynamoRcu` and `multiclouddb.perf.dynamoWcu` — paired Dynamo provisioned
  capacity applied before warmup.
- `multiclouddb.connection.gatewayMaxConnectionPoolSize` — Cosmos Gateway HTTP/1.1 pool.
- `multiclouddb.connection.gatewayHttp2Enabled` — enable the separate Cosmos HTTP/2 profile.
- `multiclouddb.connection.gatewayHttp2MinConnectionPoolSize`,
  `gatewayHttp2MaxConnectionPoolSize`, and `gatewayHttp2MaxConcurrentStreams` — Cosmos HTTP/2
  pool and multiplexing controls.
- `multiclouddb.connection.maxConnections` — Dynamo synchronous Apache HTTP/1.1 pool.

The CLI forms have precedence. The older `multiclouddb.provisionedCapacity` property is only
fallback report text and does not control provisioning.

## What the report now shows

- Target offered load, actual offered ops/s, achieved throughput, and achieved/offered ratio.
- Provider-native consumed units/sec and capacity-utilization percentage when a numeric relevant limit is known.
- Probed billing mode (`manual`, `autoscale`, `PROVISIONED`, `PAY_PER_REQUEST`, ...).
- Throttled count/rate, retry totals when surfaced by diagnostics, and row validity.
- Environment metadata including `comparison_region`.
- Effective transport profile and configured connection pool.
- Measured endpoint RTT per provider, plus RTT-normalised `svc p50` / `svc p99` service time.

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

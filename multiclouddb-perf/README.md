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
  The three query scenarios each vary one dimension: `S3` partition scope, `S4` page size
  (quarter of baseline), `S5` item size (8x baseline, page shrunk 8x to hold bytes per page
  near baseline). `--doc-size` / `--page-size` set the baseline the scenarios derive from.
  `--workload all` runs the read, write, and query profiles in one batch and one report.
- **Client placement**: same host/JDK, plus matching `comparison_region` labels. A single client
  cannot be colocated with two clouds at once, so the harness probes each endpoint's TCP RTT at
  run start and the report also presents **service time** (`latency − RTT`). Compare service time
  when the client sits outside both clouds; compare raw latency only from a colocated client.
- **Transport profile**: each provider runs its recommended data path — Cosmos Gateway V2
  (thin client) over HTTP/2, Dynamo's Apache HTTP/1.1 client. Protocol parity is deliberately
  not a goal, since Cosmos is optimized for HTTP/2 and the AWS synchronous client offers no
  HTTP/2 transport. Gateway V1 HTTP/1.1 and Direct/RNTBD are separate diagnostic profiles.
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

# Transport: each provider's recommended data path
# Cosmos config — Gateway V2 (thin client) over HTTP/2
multiclouddb.connection.connectionMode=gateway
multiclouddb.connection.gatewayHttp2Enabled=true
multiclouddb.connection.thinClientEnabled=true
multiclouddb.connection.gatewayHttp2MaxConnectionPoolSize=64
multiclouddb.connection.gatewayHttp2MinConnectionPoolSize=8
multiclouddb.connection.gatewayHttp2MaxConcurrentStreams=32
multiclouddb.connection.contentResponseOnWriteEnabled=false

# Dynamo config
multiclouddb.connection.maxConnections=64
```

Cosmos DB is optimized for HTTP/2 and Gateway V2 requires it, while Dynamo's synchronous client
is HTTP/1.1-only, so protocol parity is deliberately not a goal — each provider runs the path its
service recommends. To attribute a Cosmos change to the transport rather than the service, set
`gatewayHttp2Enabled=false` and `thinClientEnabled=false` for a diagnostic Gateway V1 run. The
`transport_profile` column records which profile produced each row and aggregation refuses to mix
them, so a diagnostic run needs its own `--title`.

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
- `multiclouddb.connection.gatewayHttp2Enabled` — Cosmos Gateway HTTP/2; **on by default**,
  set `false` only for the diagnostic Gateway V1 profile.
- `multiclouddb.connection.thinClientEnabled` — Cosmos Gateway V2 (thin client); requires
  HTTP/2 and is rejected without it.
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
- A **What was tested** section: every scenario/workload/operation profile that ran, with its
  partition scope, document size, page size, thread count, and measured operation count, plus
  what each scenario is for and how the measurement is taken.
- A **Scope** column separating single-partition from cross-partition queries. These are
  aggregated as distinct measurements, so a partition-scoped query is never averaged together
  with a cross-partition fan-out.

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

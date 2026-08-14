# Multicloud DB SDK — fair cross-provider performance test plan

## Goal

Measure the portable SDK under **fair, repeatable** conditions across Cosmos DB,
DynamoDB, and Spanner:

1. Same **offered load**.
2. Same **workload profile**.
3. Same **client placement / comparison region**.
4. Deterministic, reported **capacity / billing mode**.
5. Separate comparison of **latency**, **throughput**, and **provider-native cost**.

## Required fairness method

- Use `--target-ops-per-sec` whenever the objective is a matched-load comparison.
  The harness paces **actual operation starts** across worker threads and records
  both target and achieved/offered results.
- Use `--threads` sweeps without `--target-ops-per-sec` only for max-throughput / saturation studies.
- Use `--workload read|write|mixed|query` for one profile, or `--workload all` to run the
  read, write, and query profiles in one batch and generate one report.
- Check `comparison_region` in the environment table. `--region-policy fail` should be used for
  final fairness-sensitive runs.
- Use opt-in provisioning flags first when capacity must be pinned:
  - Cosmos: `--cosmos-ru`
  - Dynamo: `--dynamo-rcu` + `--dynamo-wcu`
- Never compare Cosmos RU numerically against Dynamo RCU/WCU; compare each provider’s cost only in
  its own unit system.

## Workload profiles

- `mixed` — existing lifecycle-style point workload (create/read/update/delete) for backward compatibility.
- `read` — seeded point reads; seeding occurs outside the measured interval.
- `write` — point writes only (create/update/upsert/delete) with independent seeded keysets and cleanup.
- `query` — query-only scenarios (`S3/S4/S5`).
- `changefeed` — capability-gated `S7` reporting path.

## Deterministic provisioning and metadata

The harness probes and reports:

- Cosmos actual manual/autoscale throughput when visible.
- Dynamo actual billing mode plus provisioned RCU/WCU, and on-demand max read/write throughput when the AWS SDK exposes it.
- Comparison-region label (`multiclouddb.comparisonRegion` when configured, else normalized probed/config region).

After any opt-in provisioning update, metadata is re-probed before measurements continue.

Capacity and offered load are normally declared in each provider's live property file:

- `multiclouddb.perf.targetOpsPerSec` (identical across all compared providers)
- `multiclouddb.perf.cosmosRu`
- `multiclouddb.perf.dynamoRcu` and `multiclouddb.perf.dynamoWcu` (required together)

Equivalent CLI options override the property values for one run. The harness applies capacity
before cache priming and warmup, then records the probed capacity rather than trusting configured
display text.

### Transport fairness

The primary cross-provider profile uses HTTP/1.1 with an equal connection-pool size:

- Cosmos Gateway: `gatewayMaxConnectionPoolSize=64`, `gatewayHttp2Enabled=false`
- Dynamo synchronous Apache client: `maxConnections=64`

Cosmos Gateway HTTP/2 is a separate optimization profile configured with
`gatewayHttp2Enabled`, `gatewayHttp2MinConnectionPoolSize`,
`gatewayHttp2MaxConnectionPoolSize`, and `gatewayHttp2MaxConcurrentStreams`. Cosmos Direct mode
uses RNTBD rather than HTTP and is also reported separately. Reports record the effective
transport profile so results with different protocols or pools are not silently compared.

## Recorded raw data

See [`templates/RESULT_SCHEMA.md`](templates/RESULT_SCHEMA.md). Raw rows include timing offsets,
consumed units, retries (when exposed), target offered load, applicable capacity dimension/limit,
and environment metadata needed to derive:

- offered ops/s
- achieved ops/s
- achieved/offered ratio
- provider-native consumed units/sec
- capacity utilization percentage when a numeric applicable limit is known
- throttled count/rate
- retry totals
- validity

## Validity rule

Default rule: a result row is **invalid** when throttled operations exceed **0.1%**.
The reporting CLI can override this with `--invalid-throttle-rate-pct`.

## Example commands

```bash
# Matched offered-load read comparison
multiclouddb-perf/perf.sh run --providers cosmos,dynamo,spanner --workload read \
  --threads 8 --target-ops-per-sec 1500 --iterations 500 --region-policy fail

# Max-throughput saturation sweep
multiclouddb-perf/perf.sh run --providers cosmos,dynamo --workload mixed \
  --scenarios S1,S6 --threads 1,8,32 --iterations 500

# Deterministic Dynamo capacity
multiclouddb-perf/perf.sh run --providers dynamo --workload write \
  --dynamo-rcu 2000 --dynamo-wcu 2000 --threads 8 --iterations 500
```

## Safety

- Live accounts only; cost-incurring operations are opt-in.
- No CI execution.
- No automatic branch/commit/push behavior.
- No live cloud tests should be run from automation without explicit operator intent.

# Performance tests (live accounts) — quick start

> All perf code and results live on the `perf/live-performance-tests` branch and are
> **never merged to `main`**. Tests run against **live cloud accounts**, not emulators.

The entire harness is **Java** (`multiclouddb-perf`): the measurement, the statistics, and
the report rendering. The measurement path drives the real portable `MulticloudDbClient` —
the same code a customer writes — so it reflects actual SDK behaviour and is debuggable in an
IDE. `multiclouddb-perf/perf.sh` is a thin launcher that builds once and invokes the Java CLI
(`com.microsoft.multiclouddb.perf.PerfMain`); there is no bash/awk orchestration or reporting.

Full design: [`PERFORMANCE_TEST_PLAN.md`](PERFORMANCE_TEST_PLAN.md).

## 1. Configure live accounts (never committed)

Copy each template, then edit with real endpoints/keys (`*.live.properties` is gitignored):

```bash
cp multiclouddb-perf/config/cosmos.live.properties.template multiclouddb-perf/config/cosmos.live.properties
```

```bash
cp multiclouddb-perf/config/dynamo.live.properties.template multiclouddb-perf/config/dynamo.live.properties
```

```bash
cp multiclouddb-perf/config/spanner.live.properties.template multiclouddb-perf/config/spanner.live.properties
```

## 2. Run

Run the full matrix — every provider with a live config present, all scenarios, then pool
statistics and render the reports (all in one JVM):

```bash
multiclouddb-perf/perf.sh run --iterations 500
```

One provider / one scenario (Cosmos):

```bash
multiclouddb-perf/perf.sh run --providers cosmos --scenarios S1 --threads 8 --iterations 500
```

One provider / one scenario (DynamoDB):

```bash
multiclouddb-perf/perf.sh run --providers dynamo --scenarios S1 --threads 8 --iterations 500
```

One provider / one scenario (Spanner):

```bash
multiclouddb-perf/perf.sh run --providers spanner --scenarios S1 --threads 8 --iterations 500
```

Sweep concurrency across all listed scenarios by passing multiple thread levels:

```bash
multiclouddb-perf/perf.sh run --scenarios S1,S6 --threads 1,8,32 --iterations 500
```

Every `run` writes raw CSVs **and** renders one Markdown + HTML report **for that run**, covering
all providers it exercised — that single doc is what you compare across Cosmos / DynamoDB / Spanner.

## 3. Re-render reports without re-running (offline)

Re-aggregate existing raw CSVs into fresh reports (no live calls) — e.g. after editing:

```bash
# Default: one report PER RUN found under --raw, named <batchId>-REPORT.{md,html}
multiclouddb-perf/perf.sh report

# Just one run
multiclouddb-perf/perf.sh report --run 2026-07-09T19-38-29Z-batch

# Pool every run into a single cross-run comparison report
multiclouddb-perf/perf.sh report --combined --title crossrun
```

**One report per run by default.** Each run (`run` batch) is written as its own
`<batchId>-REPORT.{md,html}`, so operations from unrelated runs are never mixed into the same
tables. Use `--run BATCH_ID` to render a single run, or `--combined` to pool everything.

Open the `.html` in any browser and use the browser's Print dialog to save a PDF — the report
is self-contained (inline SVG charts, no external assets), so no other tools are needed.

**Why one operation can appear on several rows within a single report:** the per-provider table
breaks results out by *scenario*, and the same operation is exercised by more than one scenario
(e.g. `create`/`read`/`update`/`delete` run under both S1 and S6; `query` runs under S3/S4/S5).
Each `(operation, scenario, threads, doc_size, page)` combination is its own row — that is the
intended breakdown, not duplication.

**Repeated runs of the same scenario are pooled, not duplicated.** Within a report, aggregation
groups by `provider,operation,scenario,threads,doc_size,page` (run id is *not* part of the key),
recomputes percentiles over the **combined** sample, and records how many runs contributed in the
`Runs` / `run_count` column. Pooling raw samples is statistically stronger than averaging per-run
percentiles.

Output:
- Raw per-op rows → `multiclouddb-perf/results/raw/<title>-<provider>.csv` (gitignored)
- Markdown report → `multiclouddb-perf/results/reports/<title>-REPORT.md` (gitignored; share/attach manually)
- HTML report (charts) → `multiclouddb-perf/results/reports/<title>-REPORT.html` (gitignored)

## Performance-test goals the report answers

1. **Throughput** achievable per provider — the `ops/s` columns and the cross-provider
   throughput matrix.
2. **Request / end-to-end latency** — `latency_ms` per op, pooled into p50/p90/p99/max, plus
   provider-reported cost where available.
3. **Migration must not need more threads** — the **Thread-scaling & migration parity** section.
   Sweep thread levels and the report checks, at each *matched* thread count, that a target
   provider reaches ≥ the baseline throughput and ≤ baseline p99 (±10%); rows that would force
   more threads are flagged ⚠️.

```bash
# Populate the parity/scaling tables with a concurrency sweep
multiclouddb-perf/perf.sh run --scenarios S1,S6 --threads 1,8,32 --iterations 500

# Pick the migration source (defaults to the first non-cosmos provider)
multiclouddb-perf/perf.sh report --baseline dynamo
```

## Delete live items (cost control)

The harness deletes its own documents at the end of each run (point-op delete phase; query
seed docs in a `finally`). But an **interrupted** run (Ctrl-C, crash, timeout) can leave items
behind that keep costing live-account storage. Clear them with:

```bash
multiclouddb-perf/perf.sh cleanup
```

That sweeps every provider with a live config and deletes only harness-created rows (each is
stamped with a `perfHarness` marker — other data is never touched). Options:

```bash
multiclouddb-perf/perf.sh cleanup --config multiclouddb-perf/config/cosmos.live.properties
```

```bash
multiclouddb-perf/perf.sh cleanup --dry-run
```

> This clears **items** (storage). It does not deprovision the container's throughput — the
> portable API exposes no container-drop. To fully stop cost, delete the perf database /
> container in the provider console.

## Fresh start (clear previous results)

Wipe all raw data and generated reports before a clean run. The `.gitkeep` directory markers
are preserved; live configs and templates are untouched:

```bash
find multiclouddb-perf/results -type f ! -name '.gitkeep' -delete
```

Then re-run from §2. Nothing under `results/` is committed (all gitignored), so this only
removes local files.

## Pipeline (all Java — one JVM per `run`)
```
PerfMain run                          orchestrates the matrix in one JVM
  ScenarioRunner (real SDK client) -> results/raw/*.csv        (measurement)
  Statistics  (pooled percentiles) -> in-memory StatRows       (aggregation)
  MarkdownReport / HtmlReport      -> results/reports/*-REPORT.{md,html}  (reporting)

PerfMain report                       re-aggregates existing raw CSVs offline
PerfMain cleanup                      deletes perf-created items (cost control)
```

## Status
- ✅ `multiclouddb-perf` Java harness — measurement, statistics, and report rendering are all
  Java (`PerfMain` CLI: `run` / `report` / `cleanup`). Reuses the e2e `ConfigLoader` +
  `MulticloudDbClientFactory`; runs S1 point-ops / S3–S5 queries / S6 concurrency / S7
  change-feed (capability-gated); captures `requestCharge` cost from diagnostics; raw CSV
  schema documented in `templates/RESULT_SCHEMA.md`. In-JVM matrix reuses one client per
  provider across scenarios — the real customer data path, debuggable in an IDE. Refuses to
  run against live accounts in CI (override `PERF_ALLOW_CI=1`).
- ✅ `multiclouddb-perf/perf.sh` thin launcher builds the harness (`mvn install -DskipTests`) then runs it
  in a perf-only reactor. Pass `--skip-build` to reuse an already-built harness.
- ⬜ Layer A JMH microbenchmarks (parser/translator, no cloud) — optional, not yet built.

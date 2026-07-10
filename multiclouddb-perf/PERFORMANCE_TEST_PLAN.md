# Multicloud DB SDK — Cross-Provider Performance Test Plan

**Status:** Draft v1
**Branch:** `perf/live-performance-tests` (all perf code and results live here — never merged to `main`)
**Scope:** Live cloud accounts for Azure Cosmos DB, Amazon DynamoDB, and Google Cloud Spanner
**Owner:** _<fill in>_

---

## 1. Objective

Measure and compare the runtime performance and **cost** of the portable
`MulticloudDbClient` surface across all three providers, running against **live cloud
accounts** (not emulators), so we can:

1. Quantify per-operation latency and throughput on each provider.
2. Capture provider-reported cost (Cosmos **RU**, DynamoDB **RCU/WCU**, Spanner **CPU/processing**).
3. Enforce the **cost-efficiency parity** invariant — a functionally portable operation
   that costs an order of magnitude more on one provider is a portability defect.
4. Detect SDK-layer overhead and regressions over time.

**Non-goals:** micro-optimizing a single provider's native SDK; load/soak testing to
failure; testing the emulators (explicitly out of scope — live accounts only).

### 1.1 Primary performance-test goals

These three goals drive what the harness measures and how the report is read:

1. **Throughput achievable from a provider (esp. Cosmos DB).** The sustained ops/s a
   provider delivers for each operation at a given provisioned capacity. Reported as the
   `ops/s` column (per-provider tables and the cross-provider throughput matrix) and,
   across a `--threads` sweep, in the **Thread-scaling** table.
2. **Request latency / end-to-end latency.** Every measured operation records the full
   client-observed end-to-end call time (`latency_ms` — SDK translation + network +
   service, including retries), pooled into p50/p90/p99/max. Provider-reported cost
   (`requestCharge`) is captured alongside where the SPI exposes it.
3. **Migration must not require more threads.** When an application migrates onto the
   portable SDK (or between providers through it), it must **not** have to raise its
   thread/concurrency count to keep the same throughput. The **Migration parity** table
   validates this: at each *matched* thread count a target provider must reach ≥ the
   baseline throughput and no worse than baseline p99 latency (±10% tolerance). If it needs
   more threads to catch up, the row is flagged ⚠️ — a migration regression. See §7.1.

---

## 2. Why live accounts (not emulators)

Emulators measure local IPC + SDK/translation overhead and report little-to-no real cost.
Latency, throughput, throttling, and RU/RCU numbers are only meaningful against live
regional endpoints. **All measured runs in this plan target live accounts.** Emulators may
still be used ad hoc for harness debugging, but never for recorded statistics.

> ⚠️ **Cost & safety:** live runs consume billable capacity and can trigger throttling.
> See §9 (Cost controls & safety).

---

## 3. Test environment

### 3.1 Accounts / resources (provision once, per provider)

| Provider | Resource to provision | Region | Config keys (multiclouddb-perf/config/*.live.properties) |
|---|---|---|---|
| Cosmos DB | 1 database + 1 container, dedicated throughput (document manual vs autoscale RU) | e.g. `eastus` | `connection.endpoint`, `connection.key`, `connection.connectionMode` (gateway/direct), `connection.requestTimeoutSeconds` |
| DynamoDB | 1 table, `PAY_PER_REQUEST` **and** a provisioned variant | e.g. `us-east-1` | `connection.region`, `auth.accessKeyId`, `auth.secretAccessKey` (or role) |
| Spanner | 1 instance + 1 database, N processing units | e.g. `us-central1` | `connection.projectId`, `connection.instanceId`, `connection.databaseId` |

Record the exact provisioned capacity (RU/s, WCU/RCU or on-demand, Spanner PUs) in every
report — throughput ceilings dominate results.

### 3.2 Client host

Record for every run: host type (laptop vs cloud VM), region relative to the DB region,
CPU/RAM, JDK vendor+version (target **JDK 17**), network (wifi/ethernet/cloud), and
`-Xss`/heap flags. **Run the client in the same cloud region as the DB** for stable numbers,
or clearly label cross-region runs.

### 3.3 Fairness rules

- Identical logical workload, document schema, key distribution, and dataset size on every provider.
- Same client host and JDK for a comparison set.
- One provider measured at a time (no shared host contention).
- Separate provisioning/`ensureContainer` from measured operations.

---

## 4. What we measure

### 4.1 Operations (from `MulticloudDbClient`)

`create`, `read`, `update`, `upsert`, `delete`, `query` (single-partition),
`query` (unscoped / cross-partition), `query` (paged), filtered `query`,
N-operation batches (loop), and — where capability-declared — `readChanges` / `listCursors`
(change-feed). `ensureContainer` / `provisionSchema` are setup-only (timed separately, not in the op stats).

### 4.2 Scenarios (workload matrix)

| ID | Scenario | Parameters |
|---|---|---|
| S1 | Point-op throughput | create/read/update/delete over a fixed keyset (e.g. 1,000 docs) |
| S2 | Document-size sweep | payload 1 KB / 16 KB / ~300 KB (near provider item limits) |
| S3 | Partition-scoped vs unscoped query | same result set, `partitionKey` set vs not — **cost parity probe** |
| S4 | Page-size sweep | `maxPageSize` 1 / 10 / 100 / 1000 over an N-doc result |
| S5 | Predicate-count sweep | filter expression with 1 / 5 / 20 predicates |
| S6 | Concurrency sweep | 1 / 8 / 32 / 64 client threads (shared singleton client — also a thread-safety probe) |
| S7 | Change-feed read (capability-gated) | read N changes; skip+record when `UNSUPPORTED_CAPABILITY` |

### 4.3 Metrics (per op × scenario × provider)

- **Latency:** p50, p90, p99, max, mean (HdrHistogram).
- **Throughput:** operations/second at each concurrency level.
- **Cost:** from `QueryPage.diagnostics()` / `DocumentResult` diagnostics —
  Cosmos RU charge, DynamoDB `ConsumedCapacity`, Spanner where exposed.
- **Reliability:** count of `THROTTLED` / `TRANSIENT_FAILURE` / other errors, and retry count.
- **Derived:** cost-per-1k-ops, latency-per-KB (for S2), RU-per-query (S3/S4).

---

## 5. Methodology

1. **Warmup:** discard the first `perf.warmup` iterations (JIT + connection pool + provider cold start).
2. **Steady state:** measure `perf.iterations` timed iterations.
3. **Repeat:** ≥ 3 independent runs per (provider, scenario); report **median across runs + variance**.
4. **Isolation:** dedicated resource, no other traffic; note time-of-day (shared-tenant variance).
5. **Determinism:** fixed RNG seed; pre-generated dataset reused across providers.
6. **Recording:** every timed op appends a row to a raw results file (§6). No manual transcription.
7. **Teardown:** the harness deletes its own docs at end of run; an interrupted run may leave
   items behind. Run `multiclouddb-perf/perf.sh cleanup` (the Java `PerfMain cleanup` command) to delete all
   harness-marked docs (cost control).
   Every perf doc carries a `perfHarness` marker + its exact key, so cleanup is provider-correct
   and never touches non-perf data.

---

## 6. Recording results (for statistics)

### 6.1 Raw results — one row per run/op (append-only)

Harness writes **CSV** (and mirror **JSON Lines**) to `multiclouddb-perf/results/raw/<run_id>.csv`.
Schema (see `multiclouddb-perf/templates/RESULT_SCHEMA.md`):

```
run_id,timestamp_utc,provider,region,host_label,jdk,operation,scenario,doc_size_bytes,
page_size,threads,iteration,latency_ms,success,error_category,cost_unit,cost_value,
provisioned_capacity,sdk_version,notes
```

- `run_id` groups a full invocation; `iteration` is per-op sequence.
- `latency_ms` is per single operation; aggregation computes percentiles.
- `cost_unit`/`cost_value` = `RU`/`RCU`/`WCU`/`PU-ms` etc. from diagnostics (blank if unavailable).

### 6.2 Statistics — aggregated per group

`Statistics` (invoked in-process by `PerfMain`, or offline via `multiclouddb-perf/perf.sh report`) pools all
raw CSV rows into one `StatRow` per (provider, operation, scenario, threads, doc_size, page_size):

```
run_count, count, latency_p50, latency_p90, latency_p99, latency_max, latency_mean,
latency_stdev, throughput_ops_sec, cost_mean, cost_p99, error_rate
```

**Runs are pooled, not duplicated.** The group key deliberately excludes `run_id`, so repeating
a scenario N times does not create N rows — the raw latency/cost samples are pooled and
percentiles are recomputed over the combined sample (statistically stronger than averaging
per-run percentiles). `run_count` records how many distinct runs contributed. This is the
statistics layer the report is built from; it is deterministic and re-runnable.

---

## 7. Reporting

After a run completes, `PerfMain` renders (in the same JVM) **one report for that run**,
covering every provider it exercised — that single doc is what you compare across Cosmos /
DynamoDB / Spanner.

`multiclouddb-perf/perf.sh report` re-renders offline from existing raw CSVs. It writes **one
report per run (batch)** by default — each `<batchId>-REPORT.{md,html}` pools only that run's
rows, so operations from unrelated runs are never mixed into the same tables. Batch id is
derived from the raw file name `<batchId>-<provider>.csv`. Flags:

- `--run BATCH_ID` — render only the matching run.
- `--combined [--title NAME]` — pool **every** run under `--raw` into one cross-run report.

Within a single report, one operation may still appear on several rows because the tables break
results out by *scenario* and the same operation runs under multiple scenarios (e.g. point ops
under both S1 and S6; `query` under S3/S4/S5). Each `(operation, scenario, threads, doc, page)`
combination is its own row — intended breakdown, not duplication.

- `MarkdownReport` → `multiclouddb-perf/results/reports/<batchId>-REPORT.md`.
- `HtmlReport` → `multiclouddb-perf/results/reports/<batchId>-REPORT.html` — a self-contained page with
  **inline SVG bar charts** (p99 latency, throughput, cost) and no external dependencies.
  Open in any browser; use the browser Print dialog to save a PDF.

Both formats contain:

- Environment & provisioned-capacity table (per provider).
- Per-provider latency/throughput tables (p50/p90/p99), including a `Runs` column.
- **Cross-provider comparison** tables with the **winner highlighted** per operation
  (fastest p99, highest throughput, cheapest cost) and relative multipliers vs the winner.
- The **cost-efficiency sub-matrix** (parity check): functional-equal but ≥10× cost on one
  provider is flagged 🔴.
- Reliability (throttle/error rates).
- Caveats & anomalies (cross-region, cold start, throttling episodes).
- **Thread-scaling & migration-parity** section (§7.1) driven by the `--threads` sweep.

The report is a committed artifact on this branch (`multiclouddb-perf/results/reports/`), so results are
versioned and comparable run-over-run.

### 7.1 Thread-scaling & migration parity (goal 3)

To validate that **migrated applications do not need more threads**, sweep several thread
levels and let the report compare providers at matched concurrency:

```bash
# One sweep, all providers with a live config; baseline defaults to the first non-cosmos provider
multiclouddb-perf/perf.sh run --scenarios S1,S6 --threads 1,8,32 --iterations 500

# Re-render offline, choosing the migration source explicitly
multiclouddb-perf/perf.sh report --baseline dynamo
```

The report then renders two tables:

- **Migration parity vs baseline `<provider>`** — per `(operation, scenario, threads)` it
  shows the baseline throughput/p99 and, for every other provider, the value and its ratio
  to baseline (`×base`). Verdict is ✅ when the target reaches ≥ baseline throughput
  (`≥ 1.0×`, within ±10%) **and** ≤ baseline p99 (`≤ 1.0×`) at the *same* thread count;
  otherwise ⚠️ — the target would need extra threads to keep up, i.e. a migration regression.
- **Thread-scaling** — throughput per thread level for each provider, with a `Scale` factor
  (peak ÷ lowest-thread throughput) and the `Peak` thread count. A factor near `1.0×` means
  the workload is already saturated, so adding threads would not help — evidence that an
  application's current thread count is sufficient post-migration.

Choose the baseline with `--baseline <provider>` (the system being migrated *from*). The
analysis needs ≥ 2 thread levels for the scaling table and ≥ 2 providers for parity.

---

## 8. Running the tests (programmatic, not hand-run)

Everything is script-driven so no one runs operations by hand. Two layers:

### Layer A — CPU-bound portable code (no cloud, runs anywhere)
JMH microbenchmarks for `ExpressionParser.parse` (length/depth sweep) and each provider's
`ExpressionTranslator.translate`. Deterministic; host-comparable; useful as a fast pre-check
and directly relevant to parser/translator recursion cost.

### Layer B — live operation perf (the main event)
A Java harness module `multiclouddb-perf` (Java 17) that reuses the e2e
`ConfigLoader` + `MulticloudDbClientFactory` + `*.properties` selection.

Run one provider/scenario:
```bash
multiclouddb-perf/perf.sh run --providers dynamo --scenarios S1 --warmup 50 --iterations 500 --threads 8
```

Run the full matrix for all providers (single JVM, one client per provider):
```bash
multiclouddb-perf/perf.sh run --config-dir multiclouddb-perf/config --out multiclouddb-perf/results/raw
```

Aggregation + Markdown/HTML reports are produced automatically at the end of every `run`.
To re-render offline from existing raw CSVs:
```bash
multiclouddb-perf/perf.sh report            # one report per run (batch)
multiclouddb-perf/perf.sh report --combined # or pool all runs into one
```

**Copilot-runnable:** these same commands can be executed by an agent end-to-end
(run → aggregate → report) given live `*.live.properties` are present and credentials are in
the environment. Credentials are NEVER committed (see §9).

Harness knobs (system properties): `-Dmulticlouddb.config`, `-Dperf.warmup`,
`-Dperf.iterations`, `-Dperf.threads`, `-Dperf.docSize`, `-Dperf.pageSize`,
`-Dperf.scenario`, `-Dperf.runId`, `-Dperf.out`.

---

## 9. Cost controls & safety

- **Credentials:** provided via env vars / untracked `*.live.properties`; `multiclouddb-perf/config/*.live.properties`
  is gitignored. Only `*.template` files are committed. Never log keys.
- **Budget guardrails:** cap dataset size and iteration counts; start small (S1 only) before the full matrix.
- **Throttling:** record throttle events rather than hammering; back off. Consider a rate cap knob.
- **Cleanup:** teardown step deletes the perf dataset after each run (idempotent `delete`).
- **Blast radius:** dedicated perf resources, never shared with prod.

---

## 10. Directory layout (this branch)

```
multiclouddb-perf/              # Java 17 live harness module (branch-only) — all perf assets live here
  PERFORMANCE_TEST_PLAN.md      # this document
  README.md                     # quick start
  perf.sh                       # thin launcher: build once + invoke the Java CLI (PerfMain)
  config/                       # *.live.properties.template (real ones gitignored)
  templates/
    RESULT_SCHEMA.md            # raw CSV column definitions
  results/
    raw/                        # per-run raw CSVs (gitignored)
    reports/                    # generated REPORT.md + REPORT.html (gitignored)
  pom.xml                       # exec:java -> PerfMain; api+e2e+providers deps
  src/main/java/com/microsoft/multiclouddb/perf/
    PerfMain.java               # CLI entry: run / report / cleanup; in-JVM matrix orchestrator
    ScenarioRunner.java         # scenario dispatch + threaded measurement loop (real SDK client)
    PerfCleanup.java            # delete harness-marked docs to stop live storage cost
    Statistics.java             # pool raw rows -> percentiles/throughput/run_count (aggregation)
    MarkdownReport.java         # render Markdown report (winner-highlighted comparisons)
    HtmlReport.java             # render self-contained HTML report with inline SVG charts
    Reports.java / ReportMeta.java   # shared report helpers + header metadata
    CsvResultWriter.java        # thread-safe append writer for the raw CSV schema
    ResultRow.java / StatRow.java / EnvRow.java / RunContext.java   # data records
```

---

## 11. Phases & milestones

| Phase | Deliverable |
|---|---|
| P0 ✅ | Branch + this plan + harness/templates/config scaffolding (result recording works on synthetic data) |
| P1 ⬜ | Layer A JMH microbenchmarks (parser/translator, no cloud) — not yet built |
| P2 ✅ | Layer B point-ops (S1) vs one live provider; raw CSV recorded end-to-end (`ScenarioRunner`) |
| P3 ✅ | Query scenarios (S3/S4/S5) + cost-diagnostics capture (`requestCharge` from `QueryPage.diagnostics()`) |
| P4 ✅ | All three providers wired (ServiceLoader); full scenario matrix (`PerfMain run`, single JVM) |
| P5 ◑ | Concurrency sweeps (S6, via `--threads`) + cost sub-matrix + report generation done; needs live-run tuning |
| P6 | (optional) repeatable baseline + run-over-run comparison |

---

## 12. Open items to confirm

- Exact regions and provisioned capacity per provider (drives ceilings).
- Client host: local vs in-cloud same-region VM.
- Dataset size and doc-size targets for v1 (budget-bound).
- Which scenarios are in v1 vs later.
- Credential delivery mechanism (env vars vs untracked files vs secrets manager).

---

## 13. Repository placement decision (open)

Where should the perf harness live? Evaluated options:

| Option | Pros | Cons |
|---|---|---|
| **A. In-repo branch** (`perf/live-performance-tests`, current) | Compiles against SDK HEAD, auto-catches API breaks; reuses e2e config/factory; never merged so no release/doc-alignment burden | Branch can drift from `main`; must rebase periodically |
| **B. Release-excluded module** on `main` (`multiclouddb-perf`, gated by Maven profile) | Same coupling benefits; discoverable | Now on `main` → subject to portability doc-alignment obligations; adds provider+JMH deps to the graph |
| **C. Separate `multiclouddb-benchmarks` repo** | Clean lifecycle/CI/secrets isolation | Must pin & bump SDK version; API breaks surface late; duplicates config plumbing |
| **D. Samples repo** | — | Poor fit: samples target end users; perf tooling is internal quality work with credentials/cost/stats concerns |

**Recommendation:** stay with **A** (branch) while the harness is maintainer-only quality tooling
tightly coupled to SDK APIs. Move to **C** only if end users will run it, an independent
release/CI cadence is required, or org policy separates billable benchmark tooling. Avoid **D**.

**Flip-to-separate triggers:** (1) non-maintainers run it as guidance; (2) independent CI/secrets/cadence needed; (3) org policy.

---

## 14. CI isolation & results handling

Perf tests are **manual, live-account, billable** — they must never run in CI. Guarantees:

1. **Branch isolation.** `.github/workflows/ci.yml` triggers only on `push`/`pull_request`
   to `main`. Perf code lives on the `perf/live-performance-tests` branch and is never
   merged to `main`, so CI never checks it out or builds it.
2. **Aggregator membership is branch-only.** `multiclouddb-perf` is listed in the root
   `pom.xml` `<modules>` **on the `perf/*` branch only** (Maven's `-pl <module> -am` requires
   reactor membership to resolve the SDK SNAPSHOT dependencies). Because the branch is never
   merged to `main`, `main`'s `pom.xml` never contains the perf module and the CI build never
   sees it. Even if a `perf → main` PR is opened, CI runs the `verify` lifecycle only —
   `exec:java` (the live runner) is never bound to a build phase, so nothing live executes;
   at worst the module is *compiled*, which is harmless.
3. **`exec:java` is never phase-bound.** The live runner is invoked explicitly by the run
   scripts (`process-resources exec:java`), never wired into `compile`/`test`/`verify`. A plain
   `mvn install`/`mvn verify` builds the module but runs no operations against any account.
4. **Harness CI guard.** `PerfMain run` / `cleanup` abort (exit 3) when `CI`,
   `GITHUB_ACTIONS`, or `BUILD_ID` is set, unless `PERF_ALLOW_CI=1` is explicitly exported.
5. **No live config in CI.** Runs require `multiclouddb-perf/config/*.live.properties`, which is gitignored
   and absent in CI — the harness skips/fails closed without it.

**Results are gitignored.** `multiclouddb-perf/.gitignore` ignores `config/*.live.properties` and everything
under `results/` (raw, stats, and generated reports) — only `.gitkeep` files are tracked to
preserve the directory structure. To retain a specific report, copy it out of the repo or
share it externally; nothing under `results/` is committed.

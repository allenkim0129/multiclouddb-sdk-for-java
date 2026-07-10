// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.perf;

import com.microsoft.multiclouddb.e2e.ConfigLoader;
import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbClientFactory;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.QueryRequest;
import com.multiclouddb.api.ResourceAddress;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Single-JVM CLI entry point for the MANUAL, live-account performance harness — the Java
 * replacement for the former {@code run-suite.sh} / {@code run-all.sh} /
 * {@code aggregate-results.sh} / {@code generate-report*.sh} bash pipeline.
 *
 * <p>Subcommands:
 * <pre>
 *   run      Run the provider &times; scenario &times; thread matrix against live accounts
 *            (one client per provider, reused across scenarios — the real customer data
 *            path), then pool statistics and render Markdown + HTML reports.
 *   report   Re-aggregate existing raw CSVs and re-render the reports (no live calls).
 *   cleanup  Delete perf-created items to stop live-account cost (see {@link PerfCleanup}).
 * </pre>
 *
 * <p>Example:
 * <pre>
 *   run  --config-dir multiclouddb-perf/config --scenarios S1,S6 --threads 1,8,32 \
 *        --iterations 500 --out multiclouddb-perf/results/raw --reports multiclouddb-perf/results/reports
 *   report  --raw multiclouddb-perf/results/raw --reports multiclouddb-perf/results/reports --title myrun
 *   cleanup --config multiclouddb-perf/config/cosmos.live.properties --dry-run
 * </pre>
 *
 * <p><b>Refuses to run against live accounts in CI</b> (see {@link #assertNotCi()}); the
 * {@code run} and {@code cleanup} commands are billable and touch live cloud resources.
 */
public final class PerfMain {

    private PerfMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || "help".equals(args[0]) || "-h".equals(args[0]) || "--help".equals(args[0])) {
            printUsage();
            return;
        }
        String command = args[0];
        Map<String, String> opt = parseOpts(Arrays.copyOfRange(args, 1, args.length));
        switch (command) {
            case "run" -> run(opt);
            case "report" -> report(opt);
            case "cleanup" -> cleanup(opt);
            default -> {
                System.err.println("Unknown command: " + command);
                printUsage();
                System.exit(2);
            }
        }
    }

    // ── run ──────────────────────────────────────────────────────────────────

    private static void run(Map<String, String> opt) throws Exception {
        assertNotCi();
        Path configDir = Path.of(opt.getOrDefault("config-dir", "multiclouddb-perf/config"));
        Path outDir    = Path.of(opt.getOrDefault("out", "multiclouddb-perf/results/raw"));
        Path reportDir = Path.of(opt.getOrDefault("reports", "multiclouddb-perf/results/reports"));
        List<String> providers = splitCsv(opt.getOrDefault("providers", "cosmos,dynamo,spanner"));
        List<String> scenarios = splitCsv(opt.getOrDefault("scenarios", "S1,S3,S4,S5,S6"));
        List<Integer> threadLevels = splitCsv(opt.getOrDefault("threads", "8")).stream()
                .map(Integer::parseInt).toList();
        int warmup     = intOpt(opt, "warmup", 50);
        int iterations = intOpt(opt, "iterations", 500);
        int docSize    = intOpt(opt, "doc-size", 1024);
        int pageSize   = intOpt(opt, "page-size", 100);
        int repeat     = Math.max(1, intOpt(opt, "repeat", 1));
        int cosmosRu   = intOpt(opt, "cosmos-ru", 0);           // 0 = leave provisioning as-is
        boolean enableDynamoStreams = opt.containsKey("enable-dynamo-streams");
        int splitWaitSeconds = intOpt(opt, "split-wait-seconds", 0); // pause after a Cosmos RU raise

        String batchId = opt.getOrDefault("title",
                Instant.now().toString().replace(":", "-").replaceAll("\\..*", "Z") + "-batch");
        String jdk = System.getProperty("java.vendor", "?") + " " + System.getProperty("java.version", "?");
        String host = hostname();

        List<ResultRow> all = new ArrayList<>();
        int ran = 0;
        for (String provider : providers) {
            Path cfgPath = configDir.resolve(provider + ".live.properties");
            if (!Files.exists(cfgPath)) {
                System.out.printf(Locale.ROOT, "!! Skipping %s — no live config at %s%n", provider, cfgPath);
                continue;
            }
            System.setProperty("multiclouddb.config", cfgPath.toString());
            ConfigLoader.AppConfig cfg = ConfigLoader.load(cfgPath.toString());
            String providerId = cfg.sdk().provider().id();
            String database   = cfg.get("multiclouddb.database", "perfdb");
            String collection = cfg.get("multiclouddb.collection", "perf");
            ResourceAddress address = new ResourceAddress(database, collection);
            String sdkVersion = cfg.get("multiclouddb.sdkVersion", "dev");
            String cfgRegion = cfg.get("multiclouddb.region", "unknown");
            String cfgProvisioned = cfg.get("multiclouddb.provisionedCapacity", "");
            MetadataProbe.Meta meta = MetadataProbe.probe(
                    providerId, cfg, database, collection, cfgRegion, cfgProvisioned);
            String region = meta.region();
            String provisioned = meta.provisionedCapacity();
            System.out.printf(Locale.ROOT, "-- %s metadata: region=%s provisioned=%s%n",
                    providerId, region, provisioned.isBlank() ? "(none)" : provisioned);

            Path csv = outDir.resolve(batchId + "-" + providerId + ".csv");
            try (MulticloudDbClient client = MulticloudDbClientFactory.create(cfg.sdk());
                 CsvResultWriter writer = new CsvResultWriter(csv)) {
                client.ensureDatabase(database);
                client.ensureContainer(address);
                primeCaches(client, address);

                // Opt-in, cost-incurring provisioning admin (only when the operator asks).
                if (cosmosRu > 0 && "cosmos".equals(providerId)) {
                    ProvisioningAdmin.ensureCosmosThroughput(cfg, database, collection, cosmosRu);
                    // A Cosmos physical-partition split is asynchronous and takes minutes to
                    // complete after the RU raise. Waiting here lets listCursors observe the new
                    // partition count within THIS run (needed for the multi-partition change feed).
                    if (splitWaitSeconds > 0) {
                        System.out.printf(Locale.ROOT,
                                "-- waiting %ds for the Cosmos partition split to complete ...%n",
                                splitWaitSeconds);
                        try {
                            Thread.sleep(splitWaitSeconds * 1000L);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                        // Re-probe so the report records the post-split provisioning/partition state.
                        MetadataProbe.Meta after = MetadataProbe.probe(
                                providerId, cfg, database, collection, region, provisioned);
                        region = after.region();
                        provisioned = after.provisionedCapacity();
                        System.out.printf(Locale.ROOT,
                                "-- post-split metadata: region=%s provisioned=%s%n",
                                region, provisioned.isBlank() ? "(none)" : provisioned);
                    }
                }
                if (enableDynamoStreams && "dynamo".equals(providerId)) {
                    ProvisioningAdmin.ensureDynamoStreams(cfg, database, collection);
                }

                Consumer<ResultRow> sink = r -> {
                    writer.write(r);
                    synchronized (all) {
                        all.add(r);
                    }
                };

                for (int rep = 1; rep <= repeat; rep++) {
                    for (String scenario : scenarios) {
                        for (int threads : threadLevels) {
                            // Each repeat gets a distinct run_id so the report pools all
                            // repeats of the same (provider, op, scenario, threads) into one
                            // averaged row (Runs column = repeat count); percentiles are
                            // recomputed over the combined sample.
                            String runId = batchId + "-" + providerId + "-" + scenario + "-" + threads + "t"
                                    + (repeat > 1 ? "-rep" + rep : "");
                            RunContext ctx = new RunContext(runId, providerId, scenario, threads,
                                    warmup, iterations, docSize, pageSize,
                                    region, host, jdk, sdkVersion, provisioned);
                            System.out.printf(Locale.ROOT,
                                    "== %s / %s / %d threads (warmup=%d iter=%d)%s ==%n",
                                    providerId, scenario, threads, warmup, iterations,
                                    repeat > 1 ? " [repeat " + rep + "/" + repeat + "]" : "");
                            try {
                                new ScenarioRunner(client, address, sink, ctx).run();
                                ran++;
                            } catch (RuntimeException scenarioFailure) {
                                System.out.printf(Locale.ROOT,
                                        "!! %s / %s / %dt aborted: %s — continuing with next scenario%n",
                                        providerId, scenario, threads, scenarioFailure);
                            }
                        }
                    }
                }
            }
            System.out.printf(Locale.ROOT, "-> %s raw rows written to %s%n", providerId, csv);
        }

        if (all.isEmpty()) {
            System.out.println("No results produced (no provider configs found?). Nothing to report.");
            return;
        }
        renderReports(all, reportDir, batchId, "in-memory results from this run", opt.get("baseline"));
        System.out.printf(Locale.ROOT, "== done == %d scenario-runs, %d raw rows.%n", ran, all.size());
    }

    // ── report (offline re-aggregation) ──────────────────────────────────────

    private static void report(Map<String, String> opt) {
        Path rawDir    = Path.of(opt.getOrDefault("raw", "multiclouddb-perf/results/raw"));
        Path reportDir = Path.of(opt.getOrDefault("reports", "multiclouddb-perf/results/reports"));

        // --combined pools every run into one report (cross-run comparison); the default
        // emits a separate report per run so operations aren't mixed across unrelated runs.
        if (opt.containsKey("combined")) {
            String title = opt.getOrDefault("title",
                    Instant.now().toString().replace(":", "-").replaceAll("\\..*", "Z") + "-combined");
            List<ResultRow> rows = Statistics.readRawCsv(rawDir);
            if (rows.isEmpty()) {
                System.err.println("No raw CSV rows found under " + rawDir);
                System.exit(1);
            }
            renderReports(rows, reportDir, title, rawDir + " (all runs, pooled)", opt.get("baseline"));
            return;
        }

        Map<String, List<ResultRow>> byBatch = Statistics.readRawByBatch(rawDir);
        if (byBatch.isEmpty()) {
            System.err.println("No raw CSV rows found under " + rawDir);
            System.exit(1);
        }
        String only = opt.get("run");   // optional substring filter to report a single run
        int made = 0;
        for (Map.Entry<String, List<ResultRow>> e : byBatch.entrySet()) {
            if (only != null && !only.isBlank() && !e.getKey().contains(only)) {
                continue;
            }
            renderReports(e.getValue(), reportDir, e.getKey(), rawDir + " (run " + e.getKey() + ")", opt.get("baseline"));
            made++;
        }
        if (made == 0) {
            System.err.println("No run matching --run '" + only + "' under " + rawDir
                    + ". Available runs: " + String.join(", ", byBatch.keySet()));
            System.exit(1);
        }
        System.out.printf(Locale.ROOT, "== done == %d per-run report(s) written to %s.%n", made, reportDir);
    }

    private static void renderReports(List<ResultRow> rows, Path reportDir, String title, String source,
                                     String baselineReq) {
        List<StatRow> stats = Statistics.aggregate(rows);
        List<EnvRow> env = Statistics.environment(rows);
        String baseline = ThreadAnalysis.resolveBaseline(baselineReq, Reports.providerOrder(stats));
        ReportMeta meta = new ReportMeta(title,
                Instant.now().toString().replaceAll("\\..*", "Z"), source, baseline);
        Path md = MarkdownReport.write(stats, env, meta, reportDir);
        Path html = HtmlReport.write(stats, env, meta, reportDir);
        System.out.println("Pooled " + rows.size() + " raw rows into " + stats.size() + " group(s).");
        System.out.println("Markdown report: " + md);
        System.out.println("HTML report (charts, open in browser): " + html);
    }

    // ── cleanup ──────────────────────────────────────────────────────────────

    private static void cleanup(Map<String, String> opt) throws Exception {
        assertNotCi();
        boolean dryRun = opt.containsKey("dry-run");
        System.setProperty("perf.dryRun", Boolean.toString(dryRun));
        List<Path> configs = new ArrayList<>();
        if (opt.containsKey("config")) {
            configs.add(Path.of(opt.get("config")));
        } else {
            Path configDir = Path.of(opt.getOrDefault("config-dir", "multiclouddb-perf/config"));
            for (String provider : splitCsv(opt.getOrDefault("providers", "cosmos,dynamo,spanner"))) {
                Path p = configDir.resolve(provider + ".live.properties");
                if (Files.exists(p)) {
                    configs.add(p);
                }
            }
        }
        if (configs.isEmpty()) {
            System.err.println("No live config found to clean up.");
            System.exit(1);
        }
        for (Path cfg : configs) {
            System.setProperty("multiclouddb.config", cfg.toString());
            PerfCleanup.main(new String[0]);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Aborts if a CI environment is detected, unless {@code PERF_ALLOW_CI=1}. */
    static void assertNotCi() {
        boolean ci = System.getenv("CI") != null || System.getenv("GITHUB_ACTIONS") != null
                || System.getenv("BUILD_ID") != null;
        if (ci && !"1".equals(System.getenv("PERF_ALLOW_CI"))) {
            System.err.println("Refusing to run live perf tests in a CI environment "
                    + "(CI/GITHUB_ACTIONS/BUILD_ID detected). These are manual, billable, live-account "
                    + "tests. Set PERF_ALLOW_CI=1 only if you truly intend this.");
            System.exit(3);
        }
    }

    private static void primeCaches(MulticloudDbClient client, ResourceAddress address) {
        client.query(address, QueryRequest.builder().maxPageSize(1).build());
        MulticloudDbKey warm = MulticloudDbKey.of("__warmup__", "__warmup__");
        client.upsert(address, warm, Map.of("id", "__warmup__", "category", "perf"));
        client.delete(address, warm);
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-host";
        }
    }

    private static Map<String, String> parseOpts(String[] args) {
        Map<String, String> opt = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) {
                throw new IllegalArgumentException("Expected --flag but got: " + a);
            }
            String key = a.substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                opt.put(key, args[++i]);
            } else {
                opt.put(key, "");   // boolean flag, e.g. --dry-run
            }
        }
        return opt;
    }

    private static List<String> splitCsv(String s) {
        List<String> out = new ArrayList<>();
        for (String part : s.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static int intOpt(Map<String, String> opt, String key, int def) {
        String v = opt.get(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        return Integer.parseInt(v.trim());
    }

    private static void printUsage() {
        System.out.println("""
            Multicloud DB perf harness (MANUAL, live accounts only).

            Usage:
              run     [--config-dir DIR] [--providers cosmos,dynamo,spanner]
                      [--scenarios S1,S3,S4,S5,S6] [--threads 1,8,32]
                      [--warmup N] [--iterations N] [--doc-size BYTES] [--page-size N]
                      [--repeat N] [--cosmos-ru RU] [--split-wait-seconds N] [--enable-dynamo-streams]
                      [--out multiclouddb-perf/results/raw] [--reports multiclouddb-perf/results/reports] [--title NAME]
              report  [--raw multiclouddb-perf/results/raw] [--reports multiclouddb-perf/results/reports]
                      [--run BATCH_ID] [--combined [--title NAME]] [--baseline PROVIDER]
              cleanup [--config FILE | --config-dir DIR --providers ...] [--dry-run]

            --repeat N runs the whole scenario matrix N times within one batch; the report pools
            all repeats of a scenario into a single averaged row (Runs column = N) to smooth noise.

            --cosmos-ru RU raises the Cosmos container to RU manual throughput before running
            (splits into multiple physical partitions above ~10K RU/s) — COSTS MONEY.
            --enable-dynamo-streams turns on a NEW_AND_OLD_IMAGES stream on the Dynamo table so the
            portable change feed (S7) is supported — COSTS MONEY. All are opt-in and off by default.
            --split-wait-seconds N pauses N seconds after a --cosmos-ru raise so the asynchronous
            physical-partition split completes before scenarios run (use ~480 for a 4000->11000 split),
            letting the change feed observe the extra partitions within the same run.

            'run' and 'cleanup' hit live accounts and refuse to run in CI (override: PERF_ALLOW_CI=1).
            'report' is offline. By default it writes ONE report per run (batch) found under --raw,
            named <batchId>-REPORT.{md,html}. Use --run BATCH_ID to report a single run, or
            --combined to pool every run into one cross-run report named by --title.
            --baseline PROVIDER sets the migration-source provider for the thread-parity analysis
            (goal 3: migrated apps must not need more threads); defaults to the first non-cosmos
            provider. Sweep several thread levels (e.g. --threads 1,8,32) to populate it.
            """);
    }
}

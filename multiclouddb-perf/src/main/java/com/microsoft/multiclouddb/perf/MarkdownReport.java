// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.perf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders a Markdown perf report — the Java replacement for the former
 * {@code generate-report.sh}/awk stage. Cross-provider tables highlight the
 * winner (fastest p99 / highest throughput / cheapest cost) per operation.
 */
final class MarkdownReport {

    private MarkdownReport() {
    }

    static Path write(List<StatRow> stats, List<EnvRow> env, ReportMeta meta, Path outDir) {
        StringBuilder b = new StringBuilder();
        b.append("# Performance Report\n\n");
        b.append("- **Run:** ").append(meta.title()).append('\n');
        b.append("- **Generated:** ").append(meta.generatedUtc()).append('\n');
        b.append("- **Source:** `").append(meta.sourceLabel()).append("`\n\n");
        b.append("> Live cloud accounts. Latency & cost depend on region, provisioned capacity, ")
                .append("client-host location, and time-of-day tenant load. Repeated runs of a scenario are ")
                .append("**pooled** (percentiles recomputed over the combined sample); the `Runs` column ")
                .append("shows how many runs contributed.\n\n");

        preface(b, stats);

        List<String> providers = Reports.providerOrder(stats);

        b.append("## 1. Environment\n\n");
        b.append("| Provider | Region | Provisioned capacity | Client host | JDK | SDK version |\n");
        b.append("|---|---|---|---|---|---|\n");
        Map<String, EnvRow> envByProvider = new LinkedHashMap<>();
        for (EnvRow e : env) {
            envByProvider.put(e.provider(), e);
        }
        for (String p : providers) {
            EnvRow e = envByProvider.get(p);
            if (e == null) {
                continue;
            }
            b.append("| ").append(p).append(" | ").append(orDash(e.region()))
                    .append(" | ").append(orDash(e.provisionedCapacity()))
                    .append(" | ").append(orDash(e.hostLabel()))
                    .append(" | ").append(orDash(e.jdk()))
                    .append(" | ").append(orDash(e.sdkVersion())).append(" |\n");
        }

        b.append("\n## 2. Per-provider detail\n\n");
        for (String p : providers) {
            b.append("### ").append(p).append("\n\n");
            b.append("| Operation | Measures | Scenario | Threads | Doc size | Page | Runs | Count | ")
                    .append("p50 ms | p90 ms | p99 ms | max ms | ops/s | cost mean | err rate |\n");
            b.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
            for (StatRow s : stats) {
                if (!s.provider().equals(p)) {
                    continue;
                }
                b.append("| ").append(s.operation())
                        .append(" | ").append(Reports.opMeasures(s.operation()))
                        .append(" | ").append(s.scenario())
                        .append(" | ").append(s.threads()).append(" | ").append(s.docSizeBytes())
                        .append(" | ").append(s.pageSize() == null ? "" : s.pageSize())
                        .append(" | ").append(s.runCount()).append(" | ").append(s.count())
                        .append(" | ").append(Reports.num(s.p50())).append(" | ").append(Reports.num(s.p90()))
                        .append(" | ").append(Reports.num(s.p99())).append(" | ").append(Reports.num(s.max()))
                        .append(" | ").append(Reports.num(s.throughputOpsSec()))
                        .append(" | ").append(Reports.numOrDash(s.costMean()))
                        .append(" | ").append(String.format(Locale.ROOT, "%.4f", s.errorRate()))
                        .append(" |\n");
            }
            b.append('\n');
        }

        b.append("## 3. Cross-provider comparison\n\n");
        b.append("### p99 latency — lower is better (winner in **bold**)\n\n");
        crossTable(b, stats, providers, /*byScenarioThreads*/ true, /*lowerBetter*/ true, StatRow::p99);
        b.append("\n### Throughput — higher is better\n\n");
        crossTable(b, stats, providers, true, false, StatRow::throughputOpsSec);
        b.append("\n### Cost (mean, provider units) — lower is better\n\n");
        b.append("_Units differ by provider (Cosmos RU, DynamoDB RCU/WCU, Spanner PU-ms). ")
                .append("Compare within an operation; cross-unit ratios are only rough._\n\n");
        costTable(b, stats, providers);

        b.append("\n## 4. Thread-scaling & migration parity\n\n");
        parityAndScaling(b, stats, providers, meta.baseline());

        Path dest = outDir.resolve(sanitize(meta.title()) + "-REPORT.md");
        try {
            Files.createDirectories(outDir);
            Files.writeString(dest, b.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed writing Markdown report", e);
        }
        return dest;
    }

    @FunctionalInterface
    interface MetricFn {
        double apply(StatRow s);
    }

    private static void preface(StringBuilder b, List<StatRow> stats) {
        LinkedHashSet<String> scenarios = new LinkedHashSet<>();
        LinkedHashSet<String> ops = new LinkedHashSet<>();
        for (StatRow s : stats) {
            scenarios.add(s.scenario());
            ops.add(s.operation());
        }
        b.append("## What was tested & how to read this\n\n");
        b.append("Each **operation** below was executed many times against a live account through the ")
                .append("portable `MulticloudDbClient`; every call is timed end-to-end (SDK translation + ")
                .append("network + service, including retries) and its provider-reported cost captured where ")
                .append("available. Operations run inside these **scenarios**:\n\n");
        for (String sc : scenarios) {
            b.append("- **").append(sc).append("** \u2014 ").append(scenarioDesc(sc)).append('\n');
        }
        b.append("\nOperations exercised this run: ").append(String.join(", ", ops)).append(".\n\n");
        b.append("**Reading the numbers:**\n\n");
        b.append("- **p50 / p90 / p99 / max ms** \u2014 end-to-end latency percentiles; ")
                .append("p99 is the tail 1% of calls. **Lower is better.**\n");
        b.append("- **ops/s** \u2014 sustained throughput at the stated thread count. **Higher is better.**\n");
        b.append("- **cost mean** \u2014 provider-reported request charge in native units ")
                .append("(Cosmos RU, DynamoDB RCU/WCU, Spanner PU-ms). Compare *within* an operation only.\n");
        b.append("- **Runs / Count** \u2014 pooled runs contributing, and total sampled calls. ")
                .append("**err rate** \u2014 fraction of failed calls (throttling/errors); values > 0 make ")
                .append("latency optimistic.\n");
        b.append("- **Cross-provider tables** bold the winner per operation; the **cost sub-matrix** ")
                .append("flags functional-equal operations that cost \u226510\u00d7 more on one provider (\uD83D\uDD34).\n");
        b.append("- **Migration parity** answers goal 3 \u2014 \u2705 means a provider keeps up with the baseline ")
                .append("at the *same* thread count (no need to add threads when migrating); \u26a0\ufe0f means it ")
                .append("would need more threads.\n\n");
    }

    private static String scenarioDesc(String sc) {
        switch (sc) {
            case "S1":
                return "point operations \u2014 create / read / update / delete of a single item";
            case "S2":
                return "point operations (secondary variant)";
            case "S3":
                return "query \u2014 partition-scoped vs unscoped (single-partition read vs cross-partition scan)";
            case "S4":
                return "query \u2014 page-size sweep";
            case "S5":
                return "query \u2014 predicate-count sweep";
            case "S6":
                return "concurrency sweep \u2014 point operations repeated across thread levels";
            case "S7":
                return "change feed \u2014 incremental read of changes (capability-gated)";
            default:
                return "custom scenario";
        }
    }

    private static void parityAndScaling(StringBuilder b, List<StatRow> stats,
                                        List<String> providers, String baseline) {
        b.append("**Goal 3 — migrated apps must not need more threads.** At each matched thread ")
                .append("count, a target provider must reach \u2265 the baseline throughput and no worse ")
                .append("than baseline p99 latency; otherwise it would need extra threads to keep up ")
                .append("(a migration regression). Tolerance \u00b1")
                .append(String.format(Locale.ROOT, "%.0f%%", ThreadAnalysis.TOLERANCE * 100))
                .append(".\n\n");

        List<ThreadAnalysis.ParityRow> parity = ThreadAnalysis.parity(stats, providers, baseline);
        if (parity.isEmpty()) {
            b.append("_Need at least two providers (baseline + target) with overlapping operations ")
                    .append("to compute parity._\n\n");
        } else {
            b.append("### Migration parity vs baseline `").append(baseline)
                    .append("` (migration source)\n\n");
            List<String> targets = new java.util.ArrayList<>();
            for (String p : providers) {
                if (!p.equals(baseline)) {
                    targets.add(p);
                }
            }
            b.append("| Operation | Scenario | Threads | ").append(baseline).append(" ops/s | ")
                    .append(baseline).append(" p99 |");
            for (String t : targets) {
                b.append(' ').append(t).append(" ops/s (\u00d7base) | ").append(t).append(" p99 (\u00d7base) |");
            }
            b.append(" Verdict |\n|---|---|---|---|---|");
            for (int i = 0; i < targets.size(); i++) {
                b.append("---|---|");
            }
            b.append("---|\n");
            for (ThreadAnalysis.ParityRow r : parity) {
                b.append("| ").append(r.operation()).append(" | ").append(r.scenario())
                        .append(" | ").append(r.threads()).append(" | ").append(Reports.num(r.baseTput()))
                        .append(" | ").append(Reports.num(r.baseP99())).append(" | ");
                for (String t : targets) {
                    Double tt = r.targetTput().get(t);
                    Double tp = r.targetP99().get(t);
                    if (tt == null) {
                        b.append("\u2014 | \u2014 | ");
                    } else {
                        b.append(Reports.num(tt)).append(" (").append(ratio(tt, r.baseTput())).append(") | ")
                                .append(Reports.num(tp)).append(" (").append(ratio(tp, r.baseP99())).append(") | ");
                    }
                }
                b.append(r.pass() ? "\u2705" : "\u26a0\ufe0f").append(" |\n");
            }
            b.append("\n> \u00d7base = target \u00f7 baseline. Throughput \u2265 1.0\u00d7 and p99 \u2264 1.0\u00d7 means the ")
                    .append("migration keeps up at the same thread count.\n\n");
        }

        List<ThreadAnalysis.ScalingRow> scaling = ThreadAnalysis.scaling(stats, providers);
        if (!ThreadAnalysis.multiThread(stats) || scaling.isEmpty()) {
            b.append("### Thread-scaling\n\n_Single thread level in this run \u2014 re-run with a sweep ")
                    .append("(e.g. `--threads 1,8,32`) to see how throughput scales with concurrency._\n\n");
            return;
        }
        List<Integer> levels = ThreadAnalysis.threadLevels(stats);
        b.append("### Thread-scaling (throughput ops/s by thread count)\n\n");
        b.append("| Provider | Operation | Scenario |");
        for (Integer t : levels) {
            b.append(' ').append(t).append("t |");
        }
        b.append(" Peak | Scale |\n|---|---|---|");
        for (int i = 0; i < levels.size(); i++) {
            b.append("---|");
        }
        b.append("---|---|\n");
        for (ThreadAnalysis.ScalingRow r : scaling) {
            b.append("| ").append(r.provider()).append(" | ").append(r.operation())
                    .append(" | ").append(r.scenario()).append(" | ");
            for (Integer t : levels) {
                Double v = r.tputByThreads().get(t);
                b.append(v == null ? "\u2014" : Reports.num(v)).append(" | ");
            }
            b.append(r.peakThreads()).append("t | ")
                    .append(String.format(Locale.ROOT, "%.1f\u00d7", r.scalingFactor())).append(" |\n");
        }
        b.append("\n> Scale = peak throughput \u00f7 throughput at the lowest thread count. A factor near ")
                .append("1.0\u00d7 means the workload is already saturated (adding threads will not help); ")
                .append("Peak names the thread count where throughput topped out.\n\n");
    }

    private static String ratio(double target, double base) {
        if (base <= 0) {
            return "\u2014";
        }
        return String.format(Locale.ROOT, "%.2f\u00d7", target / base);
    }

    private static void crossTable(StringBuilder b, List<StatRow> stats, List<String> providers,
                                   boolean withThreads, boolean lowerBetter, MetricFn metric) {
        b.append("| Operation | Scenario | Threads |");
        for (String p : providers) {
            b.append(' ').append(p).append(" |");
        }
        b.append(lowerBetter ? " Fastest |\n" : " Highest |\n");
        b.append("|---|---|---|");
        for (int i = 0; i < providers.size(); i++) {
            b.append("---|");
        }
        b.append("---|\n");

        Map<String, Map<String, Double>> grouped = new LinkedHashMap<>();
        Map<String, StatRow> anyRow = new LinkedHashMap<>();
        for (StatRow s : stats) {
            String key = s.operation() + "\u0001" + s.scenario() + "\u0001" + s.threads();
            grouped.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(s.provider(), metric.apply(s));
            anyRow.putIfAbsent(key, s);
        }
        for (Map.Entry<String, Map<String, Double>> e : grouped.entrySet()) {
            StatRow r = anyRow.get(e.getKey());
            Map<String, Double> vals = e.getValue();
            String best = bestProvider(vals, lowerBetter);
            b.append("| ").append(r.operation()).append(" | ").append(r.scenario())
                    .append(" | ").append(r.threads()).append(" | ");
            for (String p : providers) {
                Double v = vals.get(p);
                if (v == null) {
                    b.append("— | ");
                } else if (p.equals(best)) {
                    b.append("**").append(Reports.num(v)).append("** | ");
                } else {
                    b.append(Reports.num(v)).append(" | ");
                }
            }
            b.append(best == null ? "—" : best).append(" |\n");
        }
    }

    private static void costTable(StringBuilder b, List<StatRow> stats, List<String> providers) {
        b.append("| Operation | Scenario |");
        for (String p : providers) {
            b.append(' ').append(p).append(" cost |");
        }
        b.append(" Cheapest | Max/Min ratio | Parity |\n");
        b.append("|---|---|");
        for (int i = 0; i < providers.size(); i++) {
            b.append("---|");
        }
        b.append("---|---|---|\n");

        Map<String, Map<String, Double>> grouped = new LinkedHashMap<>();
        Map<String, StatRow> anyRow = new LinkedHashMap<>();
        for (StatRow s : stats) {
            String key = s.operation() + "\u0001" + s.scenario();
            if (s.costMean() != null) {
                grouped.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(s.provider(), s.costMean());
            }
            anyRow.putIfAbsent(key, s);
        }
        for (Map.Entry<String, StatRow> entry : anyRow.entrySet()) {
            StatRow r = entry.getValue();
            Map<String, Double> vals = grouped.getOrDefault(entry.getKey(), Map.of());
            String cheapest = bestProvider(vals, true);
            b.append("| ").append(r.operation()).append(" | ").append(r.scenario()).append(" | ");
            double min = Double.MAX_VALUE, max = 0;
            for (String p : providers) {
                Double v = vals.get(p);
                if (v == null) {
                    b.append("— | ");
                } else {
                    if (v > 0 && v < min) {
                        min = v;
                    }
                    if (v > max) {
                        max = v;
                    }
                    if (p.equals(cheapest)) {
                        b.append("**").append(Reports.num(v)).append("** | ");
                    } else {
                        b.append(Reports.num(v)).append(" | ");
                    }
                }
            }
            String ratio = "—";
            String flag = "—";
            if (min != Double.MAX_VALUE && max > 0 && min > 0) {
                double r2 = max / min;
                ratio = String.format(Locale.ROOT, "%.1fx", r2);
                flag = r2 >= 10 ? "\uD83D\uDD34" : (r2 >= 3 ? "\uD83D\uDFE1" : "\u2705");
            }
            b.append(cheapest == null ? "—" : cheapest).append(" | ")
                    .append(ratio).append(" | ").append(flag).append(" |\n");
        }
    }

    private static String bestProvider(Map<String, Double> vals, boolean lowerBetter) {
        String best = null;
        double bestVal = 0;
        for (Map.Entry<String, Double> e : vals.entrySet()) {
            double v = e.getValue();
            if (lowerBetter && v <= 0) {
                continue;
            }
            if (best == null || (lowerBetter ? v < bestVal : v > bestVal)) {
                best = e.getKey();
                bestVal = v;
            }
        }
        return best;
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    static String sanitize(String s) {
        return s.replaceAll("[/:\\\\]", "-");
    }
}

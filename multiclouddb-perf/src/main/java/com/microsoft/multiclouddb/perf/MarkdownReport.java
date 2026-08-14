// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.perf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Renders a Markdown perf report. */
final class MarkdownReport {

    private MarkdownReport() {
    }

    static Path write(List<StatRow> stats, List<EnvRow> env, ReportMeta meta, Path outDir) {
        StringBuilder b = new StringBuilder();
        b.append("# Performance Report\n\n");
        b.append("- **Run:** ").append(meta.title()).append('\n');
        b.append("- **Generated:** ").append(meta.generatedUtc()).append('\n');
        b.append("- **Source:** `").append(meta.sourceLabel()).append("`\n");
        b.append("- **Invalid if throttled-op rate exceeds:** ")
                .append(String.format(Locale.ROOT, "%.3f%%", meta.invalidThrottleRate() * 100.0))
                .append("\n\n");
        b.append("> Fair comparisons require the same offered load, workload profile, client placement, and deterministic capacity. "
                + "Provider capacity units are **not equivalent** (Cosmos RU vs Dynamo RCU/WCU).\n\n");

        List<String> providers = Reports.providerOrder(stats);
        environment(b, env, providers);
        perProvider(b, stats, providers, meta.invalidThrottleRate());
        crossProvider(b, stats, providers);
        parityAndScaling(b, stats, providers, meta);

        Path dest = outDir.resolve(sanitize(meta.title()) + "-REPORT.md");
        try {
            Files.createDirectories(outDir);
            Files.writeString(dest, b.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed writing Markdown report", e);
        }
        return dest;
    }

    private static void environment(StringBuilder b, List<EnvRow> env, List<String> providers) {
        b.append("## 1. Environment\n\n");
        b.append("| Provider | Region | Comparison region | Transport | Billing mode | Provisioned capacity | Client host | JDK | SDK version |\n");
        b.append("|---|---|---|---|---|---|---|---|---|\n");
        Map<String, EnvRow> byProvider = new LinkedHashMap<>();
        for (EnvRow row : env) {
            byProvider.put(row.provider(), row);
        }
        for (String provider : providers) {
            EnvRow row = byProvider.get(provider);
            if (row == null) {
                continue;
            }
            b.append("| ").append(provider)
                    .append(" | ").append(orDash(row.region()))
                    .append(" | ").append(orDash(row.comparisonRegion()))
                    .append(" | ").append(orDash(row.transportProfile()))
                    .append(" | ").append(orDash(row.billingMode()))
                    .append(" | ").append(orDash(row.provisionedCapacity()))
                    .append(" | ").append(orDash(row.hostLabel()))
                    .append(" | ").append(orDash(row.jdk()))
                    .append(" | ").append(orDash(row.sdkVersion())).append(" |\n");
        }
        b.append('\n');
    }

    private static void perProvider(StringBuilder b, List<StatRow> stats, List<String> providers,
                                    double invalidThrottleRate) {
        b.append("## 2. Per-provider detail\n\n");
        for (String provider : providers) {
            b.append("### ").append(provider).append("\n\n");
            b.append("| Workload | Operation | Scenario | Threads | Target ops/s | Offered ops/s | Achieved ops/s | Achieved/Offered | p50 ms | p90 ms | p99 ms | Cost | Consumed units/s | Capacity util | Throttled | Retries | Valid |\n");
            b.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
            for (StatRow row : stats) {
                if (!provider.equals(row.provider())) {
                    continue;
                }
                b.append("| ").append(row.workload())
                        .append(" | ").append(row.operation())
                        .append(" | ").append(row.scenario())
                        .append(" | ").append(row.threads())
                        .append(" | ").append(Reports.numOrDash(row.targetOpsPerSec()))
                        .append(" | ").append(Reports.num(row.offeredOpsSec()))
                        .append(" | ").append(Reports.num(row.throughputOpsSec()))
                        .append(" | ").append(Reports.num(row.achievedOfferedRatio())).append("x")
                        .append(" | ").append(Reports.num(row.p50()))
                        .append(" | ").append(Reports.num(row.p90()))
                        .append(" | ").append(Reports.num(row.p99()))
                        .append(" | ").append(costSummary(row))
                        .append(" | ").append(Reports.numOrDash(row.consumedUnitsPerSec()))
                        .append(" | ").append(utilSummary(row))
                        .append(" | ").append(String.format(Locale.ROOT, "%.3f%%", row.throttledRate() * 100.0))
                        .append(" | ").append(row.retryCountTotal() == null ? "—" : row.retryCountTotal())
                        .append(" | ").append(Reports.validity(row, invalidThrottleRate)).append(" |\n");
            }
            b.append('\n');
        }
    }

    private static void crossProvider(StringBuilder b, List<StatRow> stats, List<String> providers) {
        b.append("## 3. Cross-provider comparison\n\n");
        comparisonTable(b, "p99 latency (lower is better)", stats, providers, true,
                row -> row.p99(), false);
        comparisonTable(b, "throughput (higher is better)", stats, providers, false,
                StatRow::throughputOpsSec, false);
        comparisonTable(b, "cost mean (lower is better within provider units)", stats, providers, true,
                row -> row.costMean() == null ? -1.0 : row.costMean(), true);
    }

    private static void comparisonTable(StringBuilder b, String title, List<StatRow> stats,
                                        List<String> providers, boolean lowerBetter,
                                        Metric metric, boolean skipMissingMetric) {
        b.append("### ").append(title).append("\n\n");
        b.append("| Workload | Operation | Scenario | Threads | ");
        for (String provider : providers) {
            b.append(provider).append(" | ");
        }
        b.append("Best |\n");
        b.append("|---|---|---|---|");
        for (int i = 0; i < providers.size(); i++) {
            b.append("---|");
        }
        b.append("---|\n");

        Map<String, Map<String, Double>> grouped = new LinkedHashMap<>();
        Map<String, StatRow> sample = new LinkedHashMap<>();
        for (StatRow row : stats) {
            double value = metric.apply(row);
            if (skipMissingMetric && value < 0.0) {
                continue;
            }
            String key = row.workload() + "\u0001" + row.operation() + "\u0001" + row.scenario() + "\u0001" + row.threads();
            grouped.computeIfAbsent(key, ignored -> new LinkedHashMap<>()).put(row.provider(), value);
            sample.putIfAbsent(key, row);
        }
        for (Map.Entry<String, Map<String, Double>> entry : grouped.entrySet()) {
            StatRow row = sample.get(entry.getKey());
            String best = bestProvider(entry.getValue(), lowerBetter);
            b.append("| ").append(row.workload())
                    .append(" | ").append(row.operation())
                    .append(" | ").append(row.scenario())
                    .append(" | ").append(row.threads()).append(" | ");
            for (String provider : providers) {
                Double value = entry.getValue().get(provider);
                if (value == null) {
                    b.append("— | ");
                } else if (provider.equals(best)) {
                    b.append("**").append(Reports.num(value)).append("** | ");
                } else {
                    b.append(Reports.num(value)).append(" | ");
                }
            }
            b.append(best == null ? "—" : best).append(" |\n");
        }
        b.append('\n');
    }

    private static void parityAndScaling(StringBuilder b, List<StatRow> stats,
                                         List<String> providers, ReportMeta meta) {
        b.append("## 4. Thread-scaling & migration parity\n\n");
        List<ThreadAnalysis.ParityRow> parity = ThreadAnalysis.parity(stats, providers, meta.baseline());
        if (!parity.isEmpty()) {
            b.append("### Migration parity vs baseline `").append(meta.baseline()).append("`\n\n");
            b.append("| Workload | Operation | Scenario | Threads | Baseline ops/s | Baseline p99 | Verdict |\n");
            b.append("|---|---|---|---|---|---|---|\n");
            for (ThreadAnalysis.ParityRow row : parity) {
                b.append("| ").append(row.workload())
                        .append(" | ").append(row.operation())
                        .append(" | ").append(row.scenario())
                        .append(" | ").append(row.threads())
                        .append(" | ").append(Reports.num(row.baseTput()))
                        .append(" | ").append(Reports.num(row.baseP99()))
                        .append(" | ").append(row.pass() ? "✅" : "⚠️").append(" |\n");
            }
            b.append('\n');
        }
        List<ThreadAnalysis.ScalingRow> scaling = ThreadAnalysis.scaling(stats, providers);
        if (scaling.isEmpty()) {
            b.append("_Need at least two thread levels for scaling analysis._\n\n");
            return;
        }
        b.append("### Thread scaling\n\n");
        b.append("| Provider | Workload | Operation | Scenario | Peak threads | Scale |\n");
        b.append("|---|---|---|---|---|---|\n");
        for (ThreadAnalysis.ScalingRow row : scaling) {
            b.append("| ").append(row.provider())
                    .append(" | ").append(row.workload())
                    .append(" | ").append(row.operation())
                    .append(" | ").append(row.scenario())
                    .append(" | ").append(row.peakThreads())
                    .append(" | ").append(String.format(Locale.ROOT, "%.2fx", row.scalingFactor()))
                    .append(" |\n");
        }
        b.append('\n');
    }

    @FunctionalInterface
    private interface Metric {
        double apply(StatRow row);
    }

    private static String bestProvider(Map<String, Double> values, boolean lowerBetter) {
        String best = null;
        double bestValue = 0.0;
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            double value = entry.getValue();
            if (value < 0.0) {
                continue;
            }
            if (best == null || (lowerBetter ? value < bestValue : value > bestValue)) {
                best = entry.getKey();
                bestValue = value;
            }
        }
        return best;
    }

    private static String costSummary(StatRow row) {
        if (row.costMean() == null || row.costUnit() == null || row.costUnit().isBlank()) {
            return "—";
        }
        return Reports.num(row.costMean()) + " " + row.costUnit();
    }

    private static String utilSummary(StatRow row) {
        if (row.capacityUtilizationPct() == null) {
            return "—";
        }
        return Reports.num(row.capacityUtilizationPct()) + "%";
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]+", "_");
    }
}

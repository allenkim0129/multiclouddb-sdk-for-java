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

/** Renders a self-contained HTML perf report. */
final class HtmlReport {

    private HtmlReport() {
    }

    static Path write(List<StatRow> stats, List<EnvRow> env, ReportMeta meta, Path outDir) {
        List<String> providers = Reports.providerOrder(stats);
        StringBuilder b = new StringBuilder();
        b.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">");
        b.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        b.append("<title>Perf Report — ").append(Reports.esc(meta.title())).append("</title>");
        b.append(css()).append("</head><body>");
        b.append("<h1>Performance Report</h1>");
        b.append("<p><strong>").append(Reports.esc(meta.title())).append("</strong><br>")
                .append("Generated ").append(Reports.esc(meta.generatedUtc())).append("<br>")
                .append("Source <code>").append(Reports.esc(meta.sourceLabel())).append("</code><br>")
                .append("Invalid if throttled-op rate exceeds ")
                .append(String.format(Locale.ROOT, "%.3f%%", meta.invalidThrottleRate() * 100.0))
                .append("</p>");
        b.append("<p class=\"note\">Fair comparisons require the same offered load, workload profile, client placement, and deterministic capacity. Provider capacity units are not equivalent.</p>");

        envTable(b, env, providers);
        perProviderTables(b, stats, providers, meta.invalidThrottleRate());
        comparisonTables(b, stats, providers);
        parityAndScaling(b, stats, providers, meta);

        b.append("</body></html>");
        Path dest = outDir.resolve(MarkdownReport.sanitize(meta.title()) + "-REPORT.html");
        try {
            Files.createDirectories(outDir);
            Files.writeString(dest, b.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed writing HTML report", e);
        }
        return dest;
    }

    private static void envTable(StringBuilder b, List<EnvRow> env, List<String> providers) {
        b.append("<h2>1. Environment</h2><table><thead><tr><th>Provider</th><th>Region</th><th>Comparison region</th><th>Billing mode</th><th>Provisioned capacity</th><th>Client host</th><th>JDK</th><th>SDK version</th></tr></thead><tbody>");
        Map<String, EnvRow> byProvider = new LinkedHashMap<>();
        for (EnvRow row : env) {
            byProvider.put(row.provider(), row);
        }
        for (String provider : providers) {
            EnvRow row = byProvider.get(provider);
            if (row == null) {
                continue;
            }
            b.append("<tr><td>").append(Reports.esc(row.provider()))
                    .append("</td><td>").append(Reports.esc(orDash(row.region())))
                    .append("</td><td>").append(Reports.esc(orDash(row.comparisonRegion())))
                    .append("</td><td>").append(Reports.esc(orDash(row.billingMode())))
                    .append("</td><td>").append(Reports.esc(orDash(row.provisionedCapacity())))
                    .append("</td><td>").append(Reports.esc(orDash(row.hostLabel())))
                    .append("</td><td>").append(Reports.esc(orDash(row.jdk())))
                    .append("</td><td>").append(Reports.esc(orDash(row.sdkVersion())))
                    .append("</td></tr>");
        }
        b.append("</tbody></table>");
    }

    private static void perProviderTables(StringBuilder b, List<StatRow> stats, List<String> providers,
                                          double invalidThrottleRate) {
        b.append("<h2>2. Per-provider detail</h2>");
        for (String provider : providers) {
            b.append("<h3>").append(Reports.esc(provider)).append("</h3>");
            b.append("<table><thead><tr><th>Workload</th><th>Operation</th><th>Scenario</th><th>Threads</th><th>Target ops/s</th><th>Offered ops/s</th><th>Achieved ops/s</th><th>Achieved/Offered</th><th>p50 ms</th><th>p90 ms</th><th>p99 ms</th><th>Cost</th><th>Consumed units/s</th><th>Capacity util</th><th>Throttled</th><th>Retries</th><th>Valid</th></tr></thead><tbody>");
            for (StatRow row : stats) {
                if (!provider.equals(row.provider())) {
                    continue;
                }
                b.append("<tr><td>").append(Reports.esc(row.workload()))
                        .append("</td><td>").append(Reports.esc(row.operation()))
                        .append("</td><td>").append(Reports.esc(row.scenario()))
                        .append("</td><td>").append(row.threads())
                        .append("</td><td>").append(Reports.esc(Reports.numOrDash(row.targetOpsPerSec())))
                        .append("</td><td>").append(Reports.num(row.offeredOpsSec()))
                        .append("</td><td>").append(Reports.num(row.throughputOpsSec()))
                        .append("</td><td>").append(Reports.num(row.achievedOfferedRatio())).append("x")
                        .append("</td><td>").append(Reports.num(row.p50()))
                        .append("</td><td>").append(Reports.num(row.p90()))
                        .append("</td><td>").append(Reports.num(row.p99()))
                        .append("</td><td>").append(Reports.esc(costSummary(row)))
                        .append("</td><td>").append(Reports.esc(Reports.numOrDash(row.consumedUnitsPerSec())))
                        .append("</td><td>").append(Reports.esc(utilSummary(row)))
                        .append("</td><td>").append(String.format(Locale.ROOT, "%.3f%%", row.throttledRate() * 100.0))
                        .append("</td><td>").append(row.retryCountTotal() == null ? "&mdash;" : row.retryCountTotal())
                        .append("</td><td>").append(Reports.esc(Reports.validity(row, invalidThrottleRate)))
                        .append("</td></tr>");
            }
            b.append("</tbody></table>");
        }
    }

    private static void comparisonTables(StringBuilder b, List<StatRow> stats, List<String> providers) {
        b.append("<h2>3. Cross-provider comparison</h2>");
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
        b.append("<h3>").append(Reports.esc(title)).append("</h3>");
        b.append("<table><thead><tr><th>Workload</th><th>Operation</th><th>Scenario</th><th>Threads</th>");
        for (String provider : providers) {
            b.append("<th>").append(Reports.esc(provider)).append("</th>");
        }
        b.append("<th>Best</th></tr></thead><tbody>");

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
            b.append("<tr><td>").append(Reports.esc(row.workload()))
                    .append("</td><td>").append(Reports.esc(row.operation()))
                    .append("</td><td>").append(Reports.esc(row.scenario()))
                    .append("</td><td>").append(row.threads()).append("</td>");
            for (String provider : providers) {
                Double value = entry.getValue().get(provider);
                if (value == null) {
                    b.append("<td>&mdash;</td>");
                } else if (provider.equals(best)) {
                    b.append("<td class=\"best\">").append(Reports.num(value)).append("</td>");
                } else {
                    b.append("<td>").append(Reports.num(value)).append("</td>");
                }
            }
            b.append("<td>").append(Reports.esc(best == null ? "—" : best)).append("</td></tr>");
        }
        b.append("</tbody></table>");
    }

    private static void parityAndScaling(StringBuilder b, List<StatRow> stats,
                                         List<String> providers, ReportMeta meta) {
        b.append("<h2>4. Thread-scaling &amp; migration parity</h2>");
        List<ThreadAnalysis.ParityRow> parity = ThreadAnalysis.parity(stats, providers, meta.baseline());
        if (!parity.isEmpty()) {
            b.append("<h3>Migration parity vs baseline <code>").append(Reports.esc(meta.baseline())).append("</code></h3>");
            b.append("<table><thead><tr><th>Workload</th><th>Operation</th><th>Scenario</th><th>Threads</th><th>Baseline ops/s</th><th>Baseline p99</th><th>Verdict</th></tr></thead><tbody>");
            for (ThreadAnalysis.ParityRow row : parity) {
                b.append("<tr><td>").append(Reports.esc(row.workload()))
                        .append("</td><td>").append(Reports.esc(row.operation()))
                        .append("</td><td>").append(Reports.esc(row.scenario()))
                        .append("</td><td>").append(row.threads())
                        .append("</td><td>").append(Reports.num(row.baseTput()))
                        .append("</td><td>").append(Reports.num(row.baseP99()))
                        .append("</td><td>").append(row.pass() ? "PASS" : "CHECK")
                        .append("</td></tr>");
            }
            b.append("</tbody></table>");
        }
        List<ThreadAnalysis.ScalingRow> scaling = ThreadAnalysis.scaling(stats, providers);
        if (scaling.isEmpty()) {
            b.append("<p class=\"note\">Need at least two thread levels for scaling analysis.</p>");
            return;
        }
        b.append("<h3>Thread scaling</h3>");
        b.append("<table><thead><tr><th>Provider</th><th>Workload</th><th>Operation</th><th>Scenario</th><th>Peak threads</th><th>Scale</th></tr></thead><tbody>");
        for (ThreadAnalysis.ScalingRow row : scaling) {
            b.append("<tr><td>").append(Reports.esc(row.provider()))
                    .append("</td><td>").append(Reports.esc(row.workload()))
                    .append("</td><td>").append(Reports.esc(row.operation()))
                    .append("</td><td>").append(Reports.esc(row.scenario()))
                    .append("</td><td>").append(row.peakThreads())
                    .append("</td><td>").append(String.format(Locale.ROOT, "%.2fx", row.scalingFactor()))
                    .append("</td></tr>");
        }
        b.append("</tbody></table>");
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
        return Reports.num(row.costMean()) + ' ' + row.costUnit();
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

    private static String css() {
        return "<style>body{font-family:-apple-system,BlinkMacSystemFont,Segoe UI,sans-serif;margin:24px;line-height:1.4}table{border-collapse:collapse;width:100%;margin:16px 0}th,td{border:1px solid #ddd;padding:6px 8px;text-align:left}th{background:#f6f8fa}.best{font-weight:700;background:#eef6ff}.note{color:#555}</style>";
    }
}

// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.perf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Pooled aggregation of raw {@link ResultRow}s into {@link StatRow}s — the Java
 * replacement for the former {@code aggregate-results.sh}/awk stage.
 *
 * <p>Rows are grouped by {@code (provider, operation, scenario, threads, docSizeBytes,
 * pageSize)} — the run id is deliberately excluded, so repeating a scenario N times
 * pools all raw samples into one row (percentiles recomputed over the combined sample,
 * which is statistically stronger than averaging per-run percentiles) and records the
 * contributing run count.
 */
final class Statistics {

    private Statistics() {
    }

    /** Reads every {@code *.csv} under {@code rawDir} into raw rows, parsed by header name. */
    static List<ResultRow> readRawCsv(Path rawDir) {
        List<ResultRow> rows = new ArrayList<>();
        for (List<ResultRow> batch : readRawByBatch(rawDir).values()) {
            rows.addAll(batch);
        }
        return rows;
    }

    /**
     * Reads raw CSVs grouped by <em>run</em> (batch), preserving file order. The batch id is
     * derived from the file name {@code <batchId>-<provider>.csv} by stripping the trailing
     * {@code -<provider>} segment, so every distinct run can be reported on its own instead of
     * being pooled together with unrelated runs.
     */
    static Map<String, List<ResultRow>> readRawByBatch(Path rawDir) {
        Map<String, List<ResultRow>> byBatch = new LinkedHashMap<>();
        if (!Files.isDirectory(rawDir)) {
            return byBatch;
        }
        try (Stream<Path> files = Files.list(rawDir)) {
            List<Path> csvs = files.filter(p -> p.toString().endsWith(".csv")).sorted().toList();
            for (Path csv : csvs) {
                List<ResultRow> rows = parseCsv(csv);
                if (!rows.isEmpty()) {
                    byBatch.computeIfAbsent(batchIdOf(csv), k -> new ArrayList<>()).addAll(rows);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading raw CSVs under " + rawDir, e);
        }
        return byBatch;
    }

    /** {@code <batchId>-<provider>.csv} -> {@code <batchId>} (provider ids never contain '-'). */
    private static String batchIdOf(Path csv) {
        String name = csv.getFileName().toString();
        if (name.endsWith(".csv")) {
            name = name.substring(0, name.length() - 4);
        }
        int dash = name.lastIndexOf('-');
        return dash > 0 ? name.substring(0, dash) : name;
    }

    private static List<ResultRow> parseCsv(Path csv) throws IOException {
        List<ResultRow> rows = new ArrayList<>();
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return rows;
        }
        Map<String, Integer> col = headerIndex(lines.get(0));
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) {
                continue;
            }
            List<String> f = parseCsvLine(lines.get(i));
            rows.add(new ResultRow(
                    get(f, col, "run_id"), get(f, col, "timestamp_utc"),
                    get(f, col, "provider"), get(f, col, "region"),
                    get(f, col, "host_label"), get(f, col, "jdk"),
                    get(f, col, "operation"), get(f, col, "scenario"),
                    intOr(get(f, col, "doc_size_bytes"), 0),
                    intOrNull(get(f, col, "page_size")),
                    intOr(get(f, col, "threads"), 1),
                    intOr(get(f, col, "iteration"), 0),
                    doubleOr(get(f, col, "latency_ms"), 0.0),
                    "true".equalsIgnoreCase(get(f, col, "success")),
                    get(f, col, "error_category"), get(f, col, "cost_unit"),
                    doubleOrNull(get(f, col, "cost_value")),
                    get(f, col, "provisioned_capacity"), get(f, col, "sdk_version"),
                    get(f, col, "notes")));
        }
        return rows;
    }

    /** Pools raw rows into per-group statistics, preserving first-seen group order. */
    static List<StatRow> aggregate(List<ResultRow> rows) {
        Map<String, Group> groups = new LinkedHashMap<>();
        for (ResultRow r : rows) {
            String key = String.join("\u0001", r.provider(), r.operation(), r.scenario(),
                    Integer.toString(r.threads()), Integer.toString(r.docSizeBytes()),
                    r.pageSize() == null ? "" : Integer.toString(r.pageSize()));
            groups.computeIfAbsent(key, k -> new Group(r)).add(r);
        }
        List<StatRow> out = new ArrayList<>(groups.size());
        for (Group g : groups.values()) {
            out.add(g.toStatRow());
        }
        return out;
    }

    /** Distinct per-provider environment metadata, first value seen wins. */
    static List<EnvRow> environment(List<ResultRow> rows) {
        Map<String, EnvRow> env = new LinkedHashMap<>();
        for (ResultRow r : rows) {
            env.computeIfAbsent(r.provider(), p -> new EnvRow(
                    r.provider(), r.region(), r.hostLabel(), r.jdk(),
                    r.provisionedCapacity(), r.sdkVersion()));
        }
        return new ArrayList<>(env.values());
    }

    // ── Grouping accumulator ─────────────────────────────────────────────────

    private static final class Group {
        final String provider, operation, scenario;
        final int threads, docSize;
        final Integer pageSize;
        final List<Double> latencies = new ArrayList<>();
        final List<Double> costs = new ArrayList<>();
        final java.util.Set<String> runIds = new java.util.HashSet<>();
        int count, success, errors;
        double latencySum;

        Group(ResultRow r) {
            this.provider = r.provider();
            this.operation = r.operation();
            this.scenario = r.scenario();
            this.threads = r.threads();
            this.docSize = r.docSizeBytes();
            this.pageSize = r.pageSize();
        }

        void add(ResultRow r) {
            runIds.add(r.runId());
            count++;
            if (r.success()) {
                success++;
            } else {
                errors++;
            }
            latencies.add(r.latencyMs());
            latencySum += r.latencyMs();
            if (r.costValue() != null) {
                costs.add(r.costValue());
            }
        }

        StatRow toStatRow() {
            List<Double> lat = new ArrayList<>(latencies);
            Collections.sort(lat);
            double mean = lat.isEmpty() ? 0.0 : latencySum / lat.size();
            double sd = 0.0;
            if (lat.size() > 1) {
                double ss = 0.0;
                for (double v : lat) {
                    double d = v - mean;
                    ss += d * d;
                }
                sd = Math.sqrt(ss / (lat.size() - 1));
            }
            int th = Math.max(1, threads);
            double throughput = 0.0;
            double wallSec = (latencySum / 1000.0) / th;
            if (wallSec > 0) {
                throughput = success / wallSec;
            }
            List<Double> cs = new ArrayList<>(costs);
            Collections.sort(cs);
            Double costMean = cs.isEmpty() ? null : cs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            Double costP99 = cs.isEmpty() ? null : percentile(cs, 99);
            double errorRate = count > 0 ? (double) errors / count : 0.0;
            return new StatRow(provider, operation, scenario, threads, docSize, pageSize,
                    runIds.size(), count, success,
                    percentile(lat, 50), percentile(lat, 90), percentile(lat, 99),
                    lat.isEmpty() ? 0.0 : lat.get(lat.size() - 1), mean, sd,
                    throughput, costMean, costP99, errorRate);
        }
    }

    /** Linear-interpolation percentile over a pre-sorted list (matches the prior awk). */
    static double percentile(List<Double> sorted, double p) {
        int n = sorted.size();
        if (n == 0) {
            return 0.0;
        }
        if (n == 1) {
            return sorted.get(0);
        }
        double k = (n - 1) * (p / 100.0);
        int lo = (int) Math.floor(k);
        int hi = (k > lo) ? lo + 1 : lo;
        if (lo == hi) {
            return sorted.get(lo);
        }
        return sorted.get(lo) + (sorted.get(hi) - sorted.get(lo)) * (k - lo);
    }

    // ── CSV helpers ──────────────────────────────────────────────────────────

    private static Map<String, Integer> headerIndex(String header) {
        Map<String, Integer> col = new LinkedHashMap<>();
        List<String> names = parseCsvLine(header);
        for (int i = 0; i < names.size(); i++) {
            col.put(names.get(i).trim(), i);
        }
        return col;
    }

    private static String get(List<String> fields, Map<String, Integer> col, String name) {
        Integer idx = col.get(name);
        if (idx == null || idx >= fields.size()) {
            return "";
        }
        return fields.get(idx);
    }

    /** Minimal RFC-4180 line parser: handles quoted fields and doubled quotes. */
    static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    sb.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                out.add(sb.toString());
                sb.setLength(0);
            } else if (c != '\r') {
                sb.append(c);
            }
        }
        out.add(sb.toString());
        return out;
    }

    private static int intOr(String s, int def) {
        try {
            return s == null || s.isBlank() ? def : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Integer intOrNull(String s) {
        try {
            return s == null || s.isBlank() ? null : Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double doubleOr(String s, double def) {
        try {
            return s == null || s.isBlank() ? def : Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Double doubleOrNull(String s) {
        try {
            return s == null || s.isBlank() ? null : Double.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

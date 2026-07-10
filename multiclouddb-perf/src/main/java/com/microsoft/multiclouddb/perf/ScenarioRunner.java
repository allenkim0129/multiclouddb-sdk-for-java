// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.perf;

import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbException;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.OperationDiagnostics;
import com.multiclouddb.api.QueryPage;
import com.multiclouddb.api.QueryRequest;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.changefeed.ChangeFeedCursor;
import com.multiclouddb.api.changefeed.ChangeFeedPage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Runs one scenario for one provider against a live account, timing each portable
 * {@link MulticloudDbClient} operation and emitting one {@link ResultRow} per measured op
 * to the supplied sink. Warmup iterations are not recorded.
 *
 * <p>The client, address, and run metadata are injected by {@link PerfMain}, which owns the
 * client lifecycle and drives the full provider &times; scenario &times; thread-level matrix
 * in a single JVM (the real customer data path — no per-run process re-exec).
 */
final class ScenarioRunner {

    /** Marker fields written on every perf-created doc so {@link PerfCleanup} can find and
     *  reconstruct the exact key ({@code partitionKey}/{@code sortKey}) to delete it. */
    static final String MARKER_TAG = "perfHarness";
    static final String MARKER_PK  = "perfPartitionKey";
    static final String MARKER_SK  = "perfSortKey";

    private final MulticloudDbClient client;
    private final ResourceAddress address;
    private final Consumer<ResultRow> sink;
    private final RunContext ctx;
    private final String payload;

    ScenarioRunner(MulticloudDbClient client, ResourceAddress address,
                   Consumer<ResultRow> sink, RunContext ctx) {
        this.client  = client;
        this.address = address;
        this.sink    = sink;
        this.ctx     = ctx;
        this.payload = "x".repeat(Math.max(0, ctx.docSize() - 128));
    }

    /** Builds a perf document for {@code key}, stamped with cleanup markers. Reuses the shared
     *  payload string so per-op allocation stays negligible relative to network latency. */
    private Map<String, Object> docFor(MulticloudDbKey key) {
        Map<String, Object> d = new HashMap<>();
        d.put("category", "perf");
        d.put("inStock", true);
        d.put("price", 9.99);
        d.put("payload", payload);
        d.put(MARKER_TAG, "true");
        d.put(MARKER_PK, key.partitionKey());
        d.put(MARKER_SK, key.sortKey() == null ? "" : key.sortKey());
        return d;
    }

    void run() {
        switch (ctx.scenario()) {
            case "S3" -> {          // partition-scoped vs unscoped query (cost parity probe)
                queryPhase(true);
                queryPhase(false);
            }
            case "S4", "S5" -> queryPhase(false);   // page-size / predicate-count sweeps
            case "S7" -> changeFeedPhase();          // capability-gated change feed
            default   -> pointOpsPhase();            // S1/S2/S6 and any unknown id
        }
    }

    // ── Scenario phases ──────────────────────────────────────────────────────

    /** create → read → update → delete lifecycle, each op type measured separately. */
    private void pointOpsPhase() {
        int total = ctx.warmup() + ctx.iterations();
        forEachKey("create", null, total, (key, i) -> { client.create(address, key, docFor(key)); return null; });
        forEachKey("read",   null, total, (key, i) -> { client.read(address, key);               return null; });
        forEachKey("update", null, total, (key, i) -> { client.update(address, key, docFor(key)); return null; });
        forEachKey("delete", null, total, (key, i) -> { client.delete(address, key);              return null; });
    }

    /** Seed a fixed result set, then measure repeated first-page queries. */
    private void queryPhase(boolean scoped) {
        String pk = ctx.runId() + "-qpk";
        int seed = Math.max(ctx.pageSize() * 2, 200);
        // Shared partition key, unique sort key -> all rows in ONE partition, so the
        // scoped query (partitionKey=pk) is a genuine single-partition read.
        List<MulticloudDbKey> seeded = new ArrayList<>(seed);
        try {
            for (int i = 0; i < seed; i++) {
                MulticloudDbKey k = MulticloudDbKey.of(pk, "item-" + i);
                client.create(address, k, docFor(k));
                seeded.add(k);
            }
        } catch (RuntimeException seedFailure) {
            // Seeding is a prerequisite for the query measurement. If it fails on a
            // provider (e.g. the target table lacks a composite sort key), record a
            // single skip row and bail out gracefully rather than aborting the whole
            // run — mirrors changeFeedPhase's UNSUPPORTED handling.
            String cat = seedFailure instanceof MulticloudDbException me
                    ? errorCategory(me) : "PROVIDER_ERROR";
            sink.accept(row("query", ctx.pageSize(), 0, 0.0, false, cat, null,
                    "query seeding failed: " + seedFailure.getMessage()));
            System.out.println("   query seeding failed — recorded skip row and skipping query phase.");
            for (MulticloudDbKey k : seeded) {
                try { client.delete(address, k); } catch (RuntimeException ignore) { /* best-effort */ }
            }
            return;
        }
        try {
            String note = scoped ? "scoped" : "unscoped";
            forEachIteration("query", ctx.pageSize(), note, (i) -> {
                QueryRequest.Builder qb = QueryRequest.builder()
                        .expression("category = @cat")
                        .parameter("cat", "perf")
                        .maxPageSize(ctx.pageSize());
                if (scoped) {
                    qb.partitionKey(pk);
                }
                QueryPage page = client.query(address, qb.build());
                return requestCharge(page.diagnostics());
            });
        } finally {
            for (MulticloudDbKey k : seeded) {
                try { client.delete(address, k); } catch (RuntimeException ignore) { /* best-effort cleanup */ }
            }
        }
    }

    /** Change-feed read; records a single UNSUPPORTED_CAPABILITY row when unavailable. */
    private void changeFeedPhase() {
        List<ChangeFeedCursor> cursors;
        try {
            cursors = client.listCursors(address);
        } catch (MulticloudDbException e) {
            sink.accept(row("readChanges", null, 0, 0.0, false,
                    errorCategory(e), null, "change feed unsupported: " + e.error().category().getValue()));
            System.out.println("   change feed unsupported on this provider — recorded skip row.");
            return;
        }
        if (cursors.isEmpty()) {
            sink.accept(row("readChanges", null, 0, 0.0, false, "PROVIDER_ERROR", null, "no cursors returned"));
            return;
        }
        // One cursor per physical partition (Cosmos feed range / DynamoDB Streams shard).
        // The count tells us how many physical partitions the container is spread across.
        final ChangeFeedCursor[] cur = cursors.toArray(new ChangeFeedCursor[0]);
        final int partitions = cur.length;
        System.out.printf(Locale.ROOT,
                "   change feed: %d physical partition(s)/cursor(s) at tip%n", partitions);

        // Cursors are minted at the tip, so we must produce changes AFTER listing them for
        // there to be anything to read. Seed writes across many unique partition keys so the
        // changes spread across every physical partition. Seed count is capped to keep the
        // live cost bounded while still filling several pages per partition.
        int seed = Math.min(ctx.warmup() + ctx.iterations(), Math.max(ctx.pageSize() * 5, 500));
        List<MulticloudDbKey> seeded = new ArrayList<>(seed);
        try {
            for (int i = 0; i < seed; i++) {
                MulticloudDbKey k = MulticloudDbKey.of(ctx.runId() + "-cf-" + i);
                client.create(address, k, docFor(k));
                seeded.add(k);
            }
        } catch (RuntimeException seedFailure) {
            String cat = seedFailure instanceof MulticloudDbException me
                    ? errorCategory(me) : "PROVIDER_ERROR";
            sink.accept(row("readChanges", null, 0, 0.0, false, cat, null,
                    "change feed seeding failed: " + seedFailure.getMessage()));
            for (MulticloudDbKey k : seeded) {
                try { client.delete(address, k); } catch (RuntimeException ignore) { /* best-effort */ }
            }
            return;
        }
        try {
            // Rotate reads across ALL physical partitions (not just cursor 0). Each cursor is
            // immutable and read independently, so this is safe under the threaded pool; we
            // measure the latency/throughput of reading a page of changes from each partition.
            forEachIteration("readChanges", null, partitions + "part", (i) -> {
                ChangeFeedPage page = client.readChanges(address, cur[i % partitions]);
                return page == null ? null : requestCharge(page.diagnostics());
            });
        } finally {
            for (MulticloudDbKey k : seeded) {
                try { client.delete(address, k); } catch (RuntimeException ignore) { /* best-effort */ }
            }
        }
    }

    // ── Measurement primitives ───────────────────────────────────────────────

    @FunctionalInterface
    private interface KeyedOp { Double apply(MulticloudDbKey key, int index) throws Exception; }

    @FunctionalInterface
    private interface IterOp { Double apply(int index) throws Exception; }

    /** Runs {@code total} keyed ops across the thread pool; records only post-warmup ops. */
    private void forEachKey(String op, Integer pageSize, int total, KeyedOp fn) {
        System.out.printf(Locale.ROOT, "   %-11s ...", op);
        System.out.flush();
        runPool(total, (i) -> {
            MulticloudDbKey key = MulticloudDbKey.of(ctx.runId() + "-" + i);
            measure(op, pageSize, i, () -> fn.apply(key, i));
        });
        System.out.println(" done");
    }

    /** Runs {@code warmup+iterations} indexed ops (no per-key semantics). */
    private void forEachIteration(String op, Integer pageSize, String note, IterOp fn) {
        int total = ctx.warmup() + ctx.iterations();
        System.out.printf(Locale.ROOT, "   %-11s (%s) ...", op, note);
        System.out.flush();
        runPool(total, (i) -> measure(op, pageSize, i, note, () -> fn.apply(i)));
        System.out.println(" done");
    }

    private void measure(String op, Integer pageSize, int i, ThrowingSupplier<Double> call) {
        measure(op, pageSize, i, "", call);
    }

    private void measure(String op, Integer pageSize, int i, String note, ThrowingSupplier<Double> call) {
        boolean record = i >= ctx.warmup();
        int iteration = i - ctx.warmup();
        long start = System.nanoTime();
        Double cost = null;
        boolean success = true;
        String errCat = "";
        try {
            cost = call.get();
        } catch (MulticloudDbException e) {
            success = false;
            errCat = errorCategory(e);
        } catch (Exception e) {
            success = false;
            errCat = "PROVIDER_ERROR";
        }
        double latencyMs = (System.nanoTime() - start) / 1_000_000.0;
        if (record) {
            sink.accept(row(op, pageSize, iteration, latencyMs, success, errCat, cost, note));
        }
    }

    private void runPool(int total, java.util.function.IntConsumer task) {
        ExecutorService pool = Executors.newFixedThreadPool(ctx.threads());
        try {
            List<Future<?>> futures = new ArrayList<>(total);
            for (int i = 0; i < total; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> task.accept(idx)));
            }
            for (Future<?> f : futures) {
                try { f.get(); } catch (Exception e) { /* per-op errors already recorded */ }
            }
        } finally {
            pool.shutdown();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ResultRow row(String op, Integer pageSize, int iteration, double latencyMs,
                          boolean success, String errCat, Double cost, String notes) {
        String unit = (cost != null && cost > 0.0) ? costUnit(ctx.provider(), op) : "";
        return new ResultRow(
                ctx.runId(), Instant.now().toString(), ctx.provider(), ctx.region(),
                ctx.hostLabel(), ctx.jdk(), op, ctx.scenario(),
                op.equals("read") || op.equals("delete") ? 0 : ctx.docSize(),
                pageSize, ctx.threads(), iteration, latencyMs, success, errCat,
                unit, (cost != null && cost > 0.0) ? cost : null,
                ctx.provisionedCapacity(), ctx.sdkVersion(), notes);
    }

    private static Double requestCharge(OperationDiagnostics diag) {
        if (diag == null) {
            return null;
        }
        double rc = diag.requestCharge();
        return rc > 0.0 ? rc : null;
    }

    private static String costUnit(String provider, String op) {
        boolean write = op.equals("create") || op.equals("update")
                || op.equals("upsert") || op.equals("delete");
        return switch (provider) {
            case "cosmos"  -> "RU";
            case "dynamo"  -> write ? "WCU" : "RCU";
            case "spanner" -> "PU-ms";
            default        -> "";
        };
    }

    private static String errorCategory(MulticloudDbException e) {
        try {
            return e.error().category().getValue();
        } catch (RuntimeException ignore) {
            return "PROVIDER_ERROR";
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> { T get() throws Exception; }
}

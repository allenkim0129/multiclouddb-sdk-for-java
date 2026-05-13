// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.e2e;

import com.multiclouddb.api.Capability;
import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbClientFactory;
import com.multiclouddb.api.MulticloudDbKey;
import com.multiclouddb.api.QueryRequest;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.changefeed.ChangeEvent;
import com.multiclouddb.api.changefeed.ChangeFeedPage;
import com.multiclouddb.api.changefeed.ChangeFeedRequest;
import com.multiclouddb.api.changefeed.ChangeType;
import com.multiclouddb.api.changefeed.FeedScope;
import com.multiclouddb.api.changefeed.StartPosition;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * End-to-end test for the portable Change Feed API.
 *
 * <p>Exercises {@link MulticloudDbClient#readChanges} against whichever provider
 * is configured in the active properties file (Cosmos / Dynamo / Spanner). The
 * test:
 * <ol>
 *   <li>Captures a continuation token at {@link StartPosition#now()} so it only
 *       observes changes produced by this run.</li>
 *   <li>Seeds one CREATE, one UPDATE, and one DELETE via {@code client.create},
 *       {@code client.upsert}, and {@code client.delete}.</li>
 *   <li>Polls {@code readChanges} until every seeded event has been observed
 *       or the drain budget is exhausted.</li>
 *   <li>Asserts CREATE, UPDATE, DELETE were all surfaced for the seeded keys
 *       and that the resumption token can be reused.</li>
 * </ol>
 *
 * <p>Run with:
 * <pre>
 *   mvn -pl multiclouddb-e2e process-resources exec:java \
 *       -Dexec.mainClass=com.microsoft.multiclouddb.e2e.ChangeFeedMain
 *
 *   mvn -pl multiclouddb-e2e process-resources exec:java \
 *       -Dexec.mainClass=com.microsoft.multiclouddb.e2e.ChangeFeedMain \
 *       -Dmulticlouddb.config=dynamo.properties
 * </pre>
 *
 * <p>Skips cleanly (with a clear message and exit code 0) when the configured
 * provider does not advertise {@link Capability#CHANGE_FEED}.
 */
public class ChangeFeedMain {

    /** Total wall-clock budget to observe the seeded events. */
    private static final long DRAIN_BUDGET_MS = 60_000;
    /** Sleep between empty pages while draining. */
    private static final long IDLE_SLEEP_MS = 500;

    private final MulticloudDbClient client;
    private final ResourceAddress address;

    ChangeFeedMain(MulticloudDbClient client, ResourceAddress address) {
        this.client = client;
        this.address = address;
    }

    public static void main(String[] args) throws Exception {

        ConfigLoader.AppConfig cfg = ConfigLoader.load("cosmos.properties");
        String database   = cfg.get("multiclouddb.database",   "e2etest");
        String collection = cfg.get("multiclouddb.collection", "products");

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║       Multicloud DB SDK — Change Feed E2E Test               ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.printf(" Provider  : %s%n", cfg.sdk().provider().displayName());
        System.out.printf(" Database  : %s%n", database);
        System.out.printf(" Collection: %s%n", collection);
        System.out.println();

        try (MulticloudDbClient c = MulticloudDbClientFactory.create(cfg.sdk())) {
            new ChangeFeedMain(c, new ResourceAddress(database, collection)).run(cfg);
        }
    }

    void run(ConfigLoader.AppConfig cfg) throws Exception {

        // ── Capability gate ───────────────────────────────────────────
        System.out.println("── Capability check ───────────────────────────────────────────");
        boolean supported = client.capabilities().isSupported(Capability.CHANGE_FEED);
        System.out.printf("  CHANGE_FEED supported by %s : %s%n",
                cfg.sdk().provider().displayName(), supported ? "yes" : "no");
        if (!supported) {
            System.out.println();
            System.out.println("  Provider does not advertise CHANGE_FEED — skipping test.");
            System.out.println();
            return;
        }
        System.out.println();

        // ── Schema provisioning + warm-up ─────────────────────────────
        System.out.println("── Schema provisioning ────────────────────────────────────────");
        client.ensureDatabase(address.database());
        client.ensureContainer(address);
        System.out.println("  Database and collection are ready.");
        System.out.println();

        System.out.println("── SDK warm-up ────────────────────────────────────────────────");
        client.query(address, QueryRequest.builder().maxPageSize(1).build());
        MulticloudDbKey warmupKey = MulticloudDbKey.of("__cf_warmup__", "__cf_warmup__");
        client.upsert(address, warmupKey, Map.of("id", "__cf_warmup__"));
        client.delete(address, warmupKey);
        System.out.println("  Read and write metadata cached.");
        System.out.println();

        // ── Capture a "now" cursor so we only see this run's events ───
        System.out.println("── Anchor change-feed cursor at StartPosition.now() ───────────");
        ChangeFeedRequest anchorReq = ChangeFeedRequest.builder(address)
                .scope(FeedScope.entireCollection())
                .startPosition(StartPosition.now())
                .maxPageSize(100)
                .build();
        ChangeFeedPage anchor = client.readChanges(anchorReq);
        String token = anchor.continuationToken();
        System.out.printf("  anchor token captured (len=%d, hasMore=%s)%n",
                token != null ? token.length() : 0, anchor.hasMore());
        if (token == null) {
            throw new AssertionError(
                    "Expected non-null continuation token from StartPosition.now() seed page");
        }
        System.out.println();

        // ── Seed CREATE / UPDATE / DELETE on unique keys ──────────────
        String prefix = "cf-e2e-" + UUID.randomUUID();
        String pkCreate = prefix + "-create";
        String pkUpdate = prefix + "-update";
        String pkDelete = prefix + "-delete";
        Set<String> seededKeys = Set.of(pkCreate, pkUpdate, pkDelete);

        System.out.println("── Seed events ────────────────────────────────────────────────");
        MulticloudDbKey kCreate = MulticloudDbKey.of(pkCreate, pkCreate);
        MulticloudDbKey kUpdate = MulticloudDbKey.of(pkUpdate, pkUpdate);
        MulticloudDbKey kDelete = MulticloudDbKey.of(pkDelete, pkDelete);

        client.create(address, kCreate, doc(pkCreate, "created", 1));
        System.out.printf("  client.create(%s)            → CREATE expected%n", pkCreate);

        client.create(address, kUpdate, doc(pkUpdate, "initial", 1));
        client.upsert(address, kUpdate, doc(pkUpdate, "modified", 2));
        System.out.printf("  client.create + upsert(%s)   → CREATE+UPDATE expected%n", pkUpdate);

        client.create(address, kDelete, doc(pkDelete, "tombstone-source", 1));
        client.delete(address, kDelete);
        System.out.printf("  client.create + delete(%s)   → CREATE+DELETE expected%n", pkDelete);
        System.out.println();

        // ── Drain the feed until we see CREATE+UPDATE+DELETE ──────────
        System.out.println("── Drain change feed (budget=" + DRAIN_BUDGET_MS + "ms) ─────────────────");
        Set<ChangeType> typesSeen = EnumSet.noneOf(ChangeType.class);
        Set<String>      keysSeen = new HashSet<>();
        Set<ChangeType> required = EnumSet.of(ChangeType.CREATE, ChangeType.UPDATE, ChangeType.DELETE);

        int pages = 0;
        int totalEvents = 0;
        long deadline = System.currentTimeMillis() + DRAIN_BUDGET_MS;
        while (System.currentTimeMillis() < deadline && !typesSeen.containsAll(required)) {
            ChangeFeedRequest next = ChangeFeedRequest.builder(address)
                    .scope(FeedScope.entireCollection())
                    .startPosition(StartPosition.fromContinuationToken(token))
                    .maxPageSize(100)
                    .build();
            ChangeFeedPage page = client.readChanges(next);
            pages++;
            totalEvents += page.events().size();

            for (ChangeEvent ev : page.events()) {
                String pk = ev.key().partitionKey();
                if (seededKeys.contains(pk)) {
                    typesSeen.add(ev.eventType());
                    keysSeen.add(pk);
                    System.out.printf("    ← %-6s %s (eventId=%s)%n",
                            ev.eventType(), pk, ev.eventId());
                }
            }

            if (page.continuationToken() != null) {
                token = page.continuationToken();
            }
            if (page.events().isEmpty()) {
                Thread.sleep(IDLE_SLEEP_MS);
            }
        }
        System.out.printf("  pages=%d totalEvents=%d typesSeen=%s keysSeen=%s%n",
                pages, totalEvents, typesSeen, keysSeen);
        System.out.println();

        // ── Assertions ────────────────────────────────────────────────
        if (!typesSeen.containsAll(required)) {
            throw new AssertionError(
                    "Change feed did not surface all of CREATE/UPDATE/DELETE within "
                            + DRAIN_BUDGET_MS + "ms. Got " + typesSeen);
        }
        if (!keysSeen.containsAll(seededKeys)) {
            throw new AssertionError(
                    "Change feed did not surface every seeded key. Expected "
                            + seededKeys + " but saw " + keysSeen);
        }

        // ── Resumption: read again from the latest token, expect no
        // events for the seeded keys (idempotent resume past the drained
        // cursor). Re-delivery of any other event is allowed (at-least-once).
        System.out.println("── Verify continuation token can be resumed ───────────────────");
        ChangeFeedRequest resume = ChangeFeedRequest.builder(address)
                .startPosition(StartPosition.fromContinuationToken(token))
                .maxPageSize(100)
                .build();
        ChangeFeedPage resumed = client.readChanges(resume);
        long redelivered = resumed.events().stream()
                .filter(e -> seededKeys.contains(e.key().partitionKey()))
                .count();
        System.out.printf("  resumed page: %d event(s), %d for seeded keys, token=%s%n",
                resumed.events().size(),
                redelivered,
                resumed.continuationToken() != null ? "present" : "null");
        // At-least-once delivery may resurface 0+ of the seeded events; the
        // primary assertion here is that the resume call succeeded against
        // the token we previously persisted.
        System.out.println();

        // ── Cleanup ───────────────────────────────────────────────────
        System.out.println("── CLEANUP ────────────────────────────────────────────────────");
        client.delete(address, kCreate);
        client.delete(address, kUpdate);
        // kDelete already deleted above.
        System.out.println("  Seeded test items removed.");
        System.out.println();

        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.printf ("║  ✓  Change Feed E2E test completed on %-21s  ║%n",
                cfg.sdk().provider().displayName());
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
    }

    private static Map<String, Object> doc(String id, String state, int version) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("state", state);
        m.put("version", version);
        return m;
    }
}

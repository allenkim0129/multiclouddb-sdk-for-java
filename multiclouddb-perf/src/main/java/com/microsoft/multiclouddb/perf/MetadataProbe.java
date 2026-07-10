package com.microsoft.multiclouddb.perf;

import java.util.Locale;
import java.util.Set;

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosDatabase;
import com.azure.cosmos.CosmosDiagnostics;
import com.azure.cosmos.models.ThroughputProperties;
import com.azure.cosmos.models.ThroughputResponse;
import com.microsoft.multiclouddb.e2e.ConfigLoader;

/**
 * Read-only provider metadata probe.
 *
 * <p>The portable {@link com.microsoft.multiclouddb.api.MulticloudDbClient} deliberately
 * exposes no native handle, region, or provisioned-throughput accessor, so the perf
 * harness cannot obtain those through the portable API. This helper builds a throwaway
 * native admin client from the <em>same</em> live config and reads region / provisioned
 * capacity directly. Every call is best-effort: any failure (missing permission,
 * serverless account, unreachable endpoint) falls back to the value already sourced from
 * config, so a probe failure never aborts a run.
 *
 * <p>Dynamo already carries {@code multiclouddb.region} and
 * {@code multiclouddb.provisionedCapacity} in its config, so it is served from config;
 * only Cosmos (which lacks those keys) is probed natively here.
 */
final class MetadataProbe {

    private MetadataProbe() {
    }

    record Meta(String region, String provisionedCapacity) {
    }

    static Meta probe(String providerId, ConfigLoader.AppConfig cfg,
                      String database, String collection,
                      String cfgRegion, String cfgProvisioned) {
        String region = cfgRegion;
        String provisioned = cfgProvisioned;
        try {
            if ("cosmos".equals(providerId)) {
                Meta m = probeCosmos(cfg, database, collection);
                if (m.region() != null) {
                    region = m.region();
                }
                if (m.provisionedCapacity() != null) {
                    provisioned = m.provisionedCapacity();
                }
            }
        } catch (Throwable t) {
            System.out.printf(Locale.ROOT,
                    "!! metadata probe skipped for %s (%s); using config values%n",
                    providerId, t.getClass().getSimpleName());
        }
        return new Meta(region, provisioned);
    }

    private static Meta probeCosmos(ConfigLoader.AppConfig cfg, String database, String collection) {
        String endpoint = cfg.get("multiclouddb.connection.endpoint", "");
        String key = cfg.get("multiclouddb.connection.key", "");
        if (endpoint.isBlank()) {
            return new Meta(null, null);
        }
        CosmosClientBuilder builder = new CosmosClientBuilder().endpoint(endpoint).gatewayMode();
        if (!key.isBlank()) {
            builder.key(key);
        }
        String region = null;
        String provisioned = null;
        try (CosmosClient client = builder.buildClient()) {
            CosmosDatabase db = client.getDatabase(database);
            CosmosContainer container = db.getContainer(collection);
            try {
                ThroughputResponse tr = container.readThroughput();
                provisioned = formatThroughput(tr.getProperties(), "container");
                region = fromDiagnostics(tr.getDiagnostics());
            } catch (Throwable containerLevel) {
                try {
                    ThroughputResponse tr = db.readThroughput();
                    provisioned = formatThroughput(tr.getProperties(), "database");
                    region = fromDiagnostics(tr.getDiagnostics());
                } catch (Throwable databaseLevel) {
                    provisioned = "serverless/shared (no provisioned RU/s)";
                }
            }
            if (region == null) {
                try {
                    region = fromDiagnostics(container.read().getDiagnostics());
                } catch (Throwable ignore) {
                    // region stays unknown; not fatal
                }
            }
        }
        return new Meta(region, provisioned);
    }

    private static String formatThroughput(ThroughputProperties tp, String scope) {
        if (tp == null) {
            return null;
        }
        int autoscale = 0;
        int manual = 0;
        try {
            autoscale = tp.getAutoscaleMaxThroughput();
        } catch (Throwable ignore) {
            // not autoscale
        }
        try {
            manual = tp.getManualThroughput();
        } catch (Throwable ignore) {
            // not manual
        }
        if (autoscale > 0) {
            return autoscale + " RU/s (autoscale max, " + scope + ")";
        }
        if (manual > 0) {
            return manual + " RU/s (manual, " + scope + ")";
        }
        return null;
    }

    private static String fromDiagnostics(CosmosDiagnostics diagnostics) {
        if (diagnostics == null) {
            return null;
        }
        Set<String> regions = diagnostics.getContactedRegionNames();
        if (regions == null || regions.isEmpty()) {
            return null;
        }
        return String.join(",", regions);
    }
}

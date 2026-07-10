package com.microsoft.multiclouddb.perf;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosDatabase;
import com.azure.cosmos.models.ThroughputProperties;
import com.azure.cosmos.models.ThroughputResponse;
import com.microsoft.multiclouddb.e2e.ConfigLoader;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.StreamSpecification;
import software.amazon.awssdk.services.dynamodb.model.StreamViewType;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;

/**
 * Opt-in, best-effort provisioning admin used only when the operator passes the
 * corresponding CLI flags. These operations change live provisioning and therefore
 * <em>cost real money</em>, so they never run by default. Both use throwaway native admin
 * clients built from the same live config as {@link MetadataProbe}; any failure is logged
 * and swallowed so a provisioning hiccup never aborts the run.
 *
 * <ul>
 *   <li>{@code ensureCosmosThroughput} — raises the container to a target manual RU/s so
 *       Cosmos splits into multiple physical partitions (splits happen above ~10K RU/s or
 *       50&nbsp;GB). Scaling up is applied immediately; the background partition split can
 *       take minutes, so callers should allow time before reading cursors.</li>
 *   <li>{@code ensureDynamoStreams} — enables a DynamoDB Stream
 *       ({@code NEW_AND_OLD_IMAGES}) on the table so the portable change feed is supported,
 *       then waits for the table to return to {@code ACTIVE}.</li>
 * </ul>
 */
final class ProvisioningAdmin {

    private ProvisioningAdmin() {
    }

    /** Raises the Cosmos container (falling back to the database) to {@code targetRu} manual RU/s. */
    static void ensureCosmosThroughput(ConfigLoader.AppConfig cfg, String database,
                                       String collection, int targetRu) {
        String endpoint = cfg.get("multiclouddb.connection.endpoint", "");
        String key = cfg.get("multiclouddb.connection.key", "");
        if (endpoint.isBlank()) {
            System.out.println("!! cosmos throughput admin skipped — no endpoint in config");
            return;
        }
        CosmosClientBuilder builder = new CosmosClientBuilder().endpoint(endpoint).gatewayMode();
        if (!key.isBlank()) {
            builder.key(key);
        }
        try (CosmosClient client = builder.buildClient()) {
            CosmosDatabase db = client.getDatabase(database);
            CosmosContainer container = db.getContainer(collection);
            ThroughputProperties target = ThroughputProperties.createManualThroughput(targetRu);
            try {
                ThroughputResponse before = container.readThroughput();
                ThroughputResponse after = container.replaceThroughput(target);
                System.out.printf(Locale.ROOT,
                        "-- cosmos throughput: container %d -> %d RU/s (manual); "
                        + "partition split may take minutes%n",
                        manual(before), manual(after));
            } catch (Throwable containerLevel) {
                ThroughputResponse after = db.replaceThroughput(target);
                System.out.printf(Locale.ROOT,
                        "-- cosmos throughput: database -> %d RU/s (manual, container is shared)%n",
                        manual(after));
            }
        } catch (Throwable t) {
            System.out.printf(Locale.ROOT,
                    "!! cosmos throughput admin failed (%s: %s) — leaving provisioning unchanged%n",
                    t.getClass().getSimpleName(), t.getMessage());
        }
    }

    private static int manual(ThroughputResponse r) {
        try {
            return r.getProperties().getManualThroughput();
        } catch (Throwable ignore) {
            return -1;
        }
    }

    /** Enables a {@code NEW_AND_OLD_IMAGES} stream on the Dynamo table and waits for ACTIVE. */
    static void ensureDynamoStreams(ConfigLoader.AppConfig cfg, String database, String collection) {
        String region = cfg.get("multiclouddb.connection.region",
                cfg.get("multiclouddb.region", ""));
        String accessKey = cfg.get("multiclouddb.auth.accessKeyId", "");
        String secretKey = cfg.get("multiclouddb.auth.secretAccessKey", "");
        String table = database + "__" + collection;
        if (region.isBlank()) {
            System.out.println("!! dynamo streams admin skipped — no region in config");
            return;
        }
        var builder = DynamoDbClient.builder().region(Region.of(region));
        if (!accessKey.isBlank() && !secretKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey)));
        }
        try (DynamoDbClient ddb = builder.build()) {
            DescribeTableResponse desc = ddb.describeTable(b -> b.tableName(table));
            StreamSpecification spec = desc.table().streamSpecification();
            boolean enabled = spec != null && Boolean.TRUE.equals(spec.streamEnabled())
                    && spec.streamViewType() == StreamViewType.NEW_AND_OLD_IMAGES;
            if (enabled) {
                System.out.printf(Locale.ROOT,
                        "-- dynamo streams already enabled on %s (NEW_AND_OLD_IMAGES)%n", table);
                return;
            }
            ddb.updateTable(b -> b.tableName(table).streamSpecification(
                    StreamSpecification.builder()
                            .streamEnabled(true)
                            .streamViewType(StreamViewType.NEW_AND_OLD_IMAGES)
                            .build()));
            System.out.printf(Locale.ROOT,
                    "-- dynamo streams: enabling NEW_AND_OLD_IMAGES on %s ...%n", table);
            waitActive(ddb, table);
        } catch (Throwable t) {
            System.out.printf(Locale.ROOT,
                    "!! dynamo streams admin failed (%s: %s) — change feed will record a skip row%n",
                    t.getClass().getSimpleName(), t.getMessage());
        }
    }

    private static void waitActive(DynamoDbClient ddb, String table) {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(5));
        while (Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            DescribeTableResponse d = ddb.describeTable(b -> b.tableName(table));
            if (d.table().tableStatus() == TableStatus.ACTIVE) {
                System.out.printf(Locale.ROOT, "-- dynamo streams active on %s%n", table);
                return;
            }
        }
        System.out.printf(Locale.ROOT,
                "!! dynamo streams on %s did not reach ACTIVE within 5 min — continuing anyway%n", table);
    }
}

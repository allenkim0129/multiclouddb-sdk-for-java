// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us14;

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.cosmos.GatewayConnectionConfig;
import com.azure.cosmos.models.CosmosContainerProperties;
import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbClientFactory;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;

/**
 * Cosmos DB change-feed conformance, running against the Cosmos DB Emulator.
 * <p>
 * <b>Provisioning</b>: a plain container ({@code todoapp}/{@code todos-cf} by
 * default) is created on first use via {@link #ensureContainer()}. The
 * emulator does not support the All-Versions-and-Deletes (AVAD) change-feed
 * mode, so the default LatestVersion mode is used. In LatestVersion mode the
 * Cosmos provider surfaces all writes as {@link com.multiclouddb.api.changefeed.ChangeType#UPDATE}
 * and does not surface delete events at all — this subclass therefore opts out
 * of FR-cf-003 (CREATE distinction) and FR-cf-005 (DELETE) via
 * {@link #supportsCreateUpdateDeleteDistinction()}.
 * <p>
 * To run the full distinction suite, point at an AVAD-enabled container on a
 * real account:
 * {@code -Dcosmos.endpoint=... -Dcosmos.key=... -Dcosmos.changefeed.container=<avad-container>}
 * and override {@code supportsCreateUpdateDeleteDistinction()} in a subclass
 * that targets that environment.
 */
@Tag("cosmos")
@Tag("emulator")
@Tag("changefeed")
class CosmosChangeFeedConformanceTest extends ChangeFeedConformanceTest {

    private static final String ENDPOINT = System.getProperty(
            "cosmos.endpoint", "https://localhost:8081");
    private static final String KEY = System.getProperty(
            "cosmos.key",
            "C2y6yDjf5/R+ob0N8A7Cgv30VRDJIWEHLM+4QDU5DE2nQ9nDuVTqobD4b8mGGyPMbIZnqyMsEcaGQy67XIw/Jw==");
    private static final String DATABASE = System.getProperty(
            "cosmos.database", "todoapp");
    private static final String CONTAINER = System.getProperty(
            "cosmos.changefeed.container", "todos-cf");

    /**
     * Provision the database and container on the emulator if they don't
     * already exist. The CI workflow only provisions the default
     * {@code todos} container; this subclass owns its own
     * {@code todos-cf} container so the change-feed tests can run in
     * isolation without touching the CRUD test data.
     */
    @BeforeAll
    static void ensureContainer() {
        try (CosmosClient bootstrap = new CosmosClientBuilder()
                .endpoint(ENDPOINT)
                .key(KEY)
                .gatewayMode(new GatewayConnectionConfig())
                .buildClient()) {
            bootstrap.createDatabaseIfNotExists(DATABASE);
            CosmosContainerProperties props =
                    new CosmosContainerProperties(CONTAINER, "/partitionKey");
            bootstrap.getDatabase(DATABASE).createContainerIfNotExists(props);
        }
    }

    @Override
    protected MulticloudDbClient createClient() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection("endpoint", ENDPOINT)
                .connection("key", KEY)
                .connection("connectionMode", "gateway")
                .build();
        return MulticloudDbClientFactory.create(config);
    }

    @Override
    protected ResourceAddress getAddress() {
        return new ResourceAddress(DATABASE, CONTAINER);
    }

    /**
     * Cosmos emulator runs in LatestVersion mode (AVAD is not supported on the
     * emulator). In that mode all writes surface as UPDATE and deletes never
     * surface, so the CREATE/UPDATE/DELETE-distinction tests would always fail.
     */
    @Override
    protected boolean supportsCreateUpdateDeleteDistinction() {
        return false;
    }
}

// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us14;

import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbClientFactory;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import org.junit.jupiter.api.Tag;

/**
 * Cosmos DB change-feed conformance, running against the Cosmos DB Emulator.
 * <p>
 * <b>Provisioning prerequisite</b>: the container under test must be created with
 * the All-Versions-and-Deletes (AVAD) change-feed mode and the account must have
 * continuous backup enabled. The default emulator container does NOT have this —
 * use {@code -Dcosmos.changefeed.container=todos-cf} to point at a properly-provisioned
 * container.
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
}

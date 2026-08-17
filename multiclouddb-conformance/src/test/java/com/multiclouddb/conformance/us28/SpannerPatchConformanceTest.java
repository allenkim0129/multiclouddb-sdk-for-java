// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us28;

import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbClientFactory;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.conformance.SpannerTestSchema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;

import java.util.concurrent.ExecutionException;

/**
 * Spanner patch conformance, running against the Spanner Emulator.
 * <p>
 * Uses its own table provisioned from the shared conformance schema. The
 * standard {@code data} document envelope also carries dynamic top-level
 * fields, so the suite verifies PATCH independently of physical DDL columns.
 * <p>
 * This provider declares {@code NESTED_PATCH} unsupported, so the nested-path
 * test in the base class asserts the {@code UNSUPPORTED_CAPABILITY} branch here.
 */
@Tag("spanner")
@Tag("emulator")
class SpannerPatchConformanceTest extends PatchConformanceTest {

    private static final String DATABASE_ID = "testdb";
    private static final String TABLE = "todos_patch";

    @BeforeAll
    static void ensureSchema() throws ExecutionException, InterruptedException {
        SpannerTestSchema.ensureSchema(DATABASE_ID, TABLE);
    }

    @Override
    protected MulticloudDbClient createClient() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.SPANNER)
                .connection("projectId", SpannerTestSchema.PROJECT_ID)
                .connection("instanceId", SpannerTestSchema.INSTANCE_ID)
                .connection("databaseId", DATABASE_ID)
                .connection("emulatorHost", SpannerTestSchema.EMULATOR_HOST)
                .build();
        return MulticloudDbClientFactory.create(config);
    }

    @Override
    protected ResourceAddress getAddress() {
        return new ResourceAddress(DATABASE_ID, TABLE);
    }

    @Override
    protected boolean expectedNestedPatchSupport() {
        return false;
    }

}
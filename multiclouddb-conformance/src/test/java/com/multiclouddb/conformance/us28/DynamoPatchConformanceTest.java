// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us28;

import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbClientFactory;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.net.URI;

/**
 * DynamoDB patch conformance, running against DynamoDB Local.
 * <p>
 * Prerequisites: DynamoDB Local on http://localhost:8000. The table
 * {@code local__todos_patch} is created if absent. A dedicated table is used so
 * the suite does not race the CRUD conformance table, which is dropped and
 * recreated in its own {@code @BeforeAll}.
 */
@Tag("dynamo")
@Tag("emulator")
class DynamoPatchConformanceTest extends PatchConformanceTest {

    private static final String DATABASE = "local";
    private static final String COLLECTION = "todos_patch";
    /** Physical table name: database__collection (DynamoDB convention). */
    private static final String TABLE = DATABASE + "__" + COLLECTION;

    private static final String ENDPOINT = System.getProperty("dynamo.endpoint", "http://localhost:8000");
    private static final String REGION = System.getProperty("dynamo.region", "us-east-1");

    @BeforeAll
    static void ensureTable() {
        try (DynamoDbClient ddb = DynamoDbClient.builder()
                .endpointOverride(URI.create(ENDPOINT))
                .region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("fakeMyKeyId", "fakeSecretAccessKey")))
                .build()) {
            try {
                ddb.createTable(CreateTableRequest.builder()
                        .tableName(TABLE)
                        .keySchema(
                                KeySchemaElement.builder()
                                        .attributeName("partitionKey").keyType(KeyType.HASH).build(),
                                KeySchemaElement.builder()
                                        .attributeName("sortKey").keyType(KeyType.RANGE).build())
                        .attributeDefinitions(
                                AttributeDefinition.builder()
                                        .attributeName("partitionKey")
                                        .attributeType(ScalarAttributeType.S).build(),
                                AttributeDefinition.builder()
                                        .attributeName("sortKey")
                                        .attributeType(ScalarAttributeType.S).build())
                        .billingMode(BillingMode.PAY_PER_REQUEST)
                        .build());
            } catch (ResourceInUseException alreadyExists) {
                // Table already provisioned by an earlier run — reuse it.
            }
        }
    }

    @Override
    protected MulticloudDbClient createClient() {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.DYNAMO)
                .connection("endpoint", ENDPOINT)
                .connection("region", REGION)
                .auth("accessKeyId", "fakeMyKeyId")
                .auth("secretAccessKey", "fakeSecretAccessKey")
                .build();
        return MulticloudDbClientFactory.create(config);
    }

    @Override
    protected ResourceAddress getAddress() {
        return new ResourceAddress(DATABASE, COLLECTION);
    }

    @Override
    protected boolean expectedNestedPatchSupport() {
        return true;
    }
}
// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import com.multiclouddb.api.Capability;
import com.multiclouddb.api.CapabilitySet;

import java.util.List;

/**
 * DynamoDB capabilities declaration.
 */
public final class DynamoCapabilities {

    private DynamoCapabilities() {
    }

    public static final CapabilitySet CAPABILITIES = new CapabilitySet(List.of(
            Capability.CONTINUATION_TOKEN_PAGING_CAP.withNotes("Uses LastEvaluatedKey serialized as opaque token"),
            Capability.CROSS_PARTITION_QUERY_UNSUPPORTED.withNotes("DynamoDB scans are not partition-targeted queries"),
            Capability.TRANSACTIONS_CAP.withNotes("TransactWriteItems / TransactGetItems (up to 100 items)"),
            Capability.BATCH_OPERATIONS_CAP.withNotes("BatchWriteItem and BatchGetItem (up to 25/100 items)"),
            Capability.STRONG_CONSISTENCY_CAP.withNotes("Strongly consistent reads supported on individual items"),
            Capability.NATIVE_SQL_QUERY_UNSUPPORTED.withNotes("PartiQL is available but not SQL; filter expressions used for scans"),
            Capability.CHANGE_FEED_CAP.withNotes("DynamoDB Streams for change data capture"),
            Capability.EXTENDED_CHANGE_FEED_HISTORY_UNSUPPORTED.withNotes(
                    "DynamoDB Streams is fixed at 24h server-side. SDK-managed archive-on-read "
                    + "via Kafka (customer-provisioned brokers) is on the v1.x roadmap; for now drain "
                    + "Streams into a customer-provisioned Kafka cluster outside the SDK for >24h."),
            Capability.PATCH_CAP.withNotes(
                    "UpdateItem with a compiled UpdateExpression; all operations applied atomically. "
                    + "Capacity cost depends on item shape and account configuration; patch is a "
                    + "payload-size and lost-update-safety optimization, not a guaranteed WCU saving"),
            Capability.NESTED_PATCH_CAP.withNotes(
                    "Document paths (a.b.c) address nested map attributes directly in the "
                    + "UpdateExpression"),
            Capability.EXACT_FRACTIONAL_INCREMENT_CAP.withNotes(
                    "fractional INCREMENT is evaluated in the DynamoDB N type, which is exact "
                    + "decimal arithmetic with 38 significant digits, so accumulated results carry "
                    + "no binary rounding drift; Cosmos and Spanner evaluate in IEEE-754 binary64"),
            // Query DSL capabilities
            Capability.PORTABLE_QUERY_EXPRESSION_CAP.withNotes("Portable expression translation to DynamoDB PartiQL"),
            Capability.LIKE_OPERATOR_UNSUPPORTED.withNotes("LIKE not natively supported in PartiQL on DynamoDB"),
            Capability.ORDER_BY_UNSUPPORTED.withNotes("ORDER BY not supported in DynamoDB PartiQL scans; use validateResultSetControl to fail fast"),
            Capability.ENDS_WITH_UNSUPPORTED.withNotes("No native ends_with in DynamoDB PartiQL"),
            Capability.REGEX_MATCH_UNSUPPORTED.withNotes("No native regex support in DynamoDB PartiQL"),
            Capability.CASE_FUNCTIONS_UNSUPPORTED.withNotes("No native UPPER/LOWER in DynamoDB PartiQL"),
            Capability.of(Capability.RESULT_LIMIT, false,
                    "DynamoDB limit parameter caps the current scan/query page only; "
                    + "pagination via continuation tokens can exceed the stated limit. "
                    + "A true server-side total cap (like Cosmos SELECT TOP N) is not supported."),
            Capability.of(Capability.ROW_LEVEL_TTL, true,
                    "Item-level TTL via " + DynamoConstants.ATTR_TTL_EXPIRY + " epoch-seconds attribute; "
                    + "requires DynamoDB table TTL enabled on that attribute — silently ignored otherwise"),
            Capability.of(Capability.WRITE_TIMESTAMP, false,
                    "DynamoDB does not expose per-item write timestamps via GetItem")));
}

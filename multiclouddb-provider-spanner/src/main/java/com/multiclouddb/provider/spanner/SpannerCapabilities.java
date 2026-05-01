// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.multiclouddb.api.Capability;
import com.multiclouddb.api.CapabilitySet;

import java.util.List;

/**
 * Spanner capabilities declaration — fully implemented provider.
 */
public final class SpannerCapabilities {

    private SpannerCapabilities() {
    }

    public static final CapabilitySet CAPABILITIES = new CapabilitySet(List.of(
            Capability.CONTINUATION_TOKEN_PAGING_CAP.withNotes("Offset-based continuation token paging"),
            Capability.CROSS_PARTITION_QUERY_CAP.withNotes("Spanner supports distributed queries natively"),
            Capability.TRANSACTIONS_CAP.withNotes("Spanner supports ACID transactions across rows"),
            Capability.BATCH_OPERATIONS_CAP.withNotes("Spanner mutation batches"),
            Capability.STRONG_CONSISTENCY_CAP.withNotes("External consistency (linearizability)"),
            Capability.NATIVE_SQL_QUERY_CAP.withNotes("Full GoogleSQL or PostgreSQL-dialect SQL"),
            Capability.CHANGE_FEED_UNSUPPORTED.withNotes("Change Streams require CREATE CHANGE STREAM DDL and the Spanner emulator does not support them; planned for a follow-up release. Tracked in specs/002-change-feed/research.md"),
            Capability.CHANGE_FEED_POINT_IN_TIME_UNSUPPORTED.withNotes("Deferred with CHANGE_FEED — Spanner Change Streams TVF supports start_timestamp natively, will be enabled when CHANGE_FEED ships"),
            Capability.CHANGE_FEED_LOGICAL_PARTITION_SCOPE_UNSUPPORTED.withNotes("Spanner Change Streams expose physical partition tokens only — no logical partition-key scoping"),
            // Query DSL capabilities
            Capability.PORTABLE_QUERY_EXPRESSION_CAP.withNotes("Portable expression translation to Spanner GoogleSQL"),
            Capability.LIKE_OPERATOR_CAP.withNotes("LIKE operator supported in GoogleSQL"),
            Capability.ORDER_BY_CAP.withNotes("ORDER BY supported in GoogleSQL queries"),
            Capability.ENDS_WITH_CAP.withNotes("ENDS_WITH function available in GoogleSQL"),
            Capability.REGEX_MATCH_CAP.withNotes("REGEXP_CONTAINS available in GoogleSQL"),
            Capability.CASE_FUNCTIONS_CAP.withNotes("UPPER/LOWER functions available in GoogleSQL"),
            Capability.of(Capability.RESULT_LIMIT, true,
                    "Per-page LIMIT N supported in GoogleSQL queries; "
                    + "cap is per-page only, not a hard total across pagination"),
            Capability.of(Capability.ROW_LEVEL_TTL, false,
                    "Spanner TTL requires ROW_DELETION_POLICY DDL on the table schema; "
                    + "not implementable as a runtime write — SDK does not manage schema"),
            Capability.of(Capability.WRITE_TIMESTAMP, false,
                    "Full commit-timestamp metadata requires allow_commit_timestamp=true DDL; "
                    + "deferred — current impl returns empty metadata shell")));
}

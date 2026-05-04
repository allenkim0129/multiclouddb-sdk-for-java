// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed;

import com.multiclouddb.api.OperationDiagnostics;

import java.util.Collections;
import java.util.List;

/**
 * A single page of change-feed events with optional partition-lifecycle hints
 * and diagnostics.
 * <p>
 * {@link #continuationToken()} is the opaque resumption cursor; {@code null}
 * means no further events are available <em>at this moment</em> (an empty page
 * with a non-null token indicates an idle feed; an empty page with a null
 * token means the partition has been retired or the requested time window
 * has been fully consumed).
 *
 * <p>Partition lifecycle ({@link FeedScope.PhysicalPartition} only):
 * <ul>
 *   <li>{@link #partitionRetired()} — {@code true} when the underlying
 *       provider partition has split or merged and will produce no further
 *       events. Callers should resume by reading from each entry of
 *       {@link #childPartitions()} (e.g. via
 *       {@link FeedScope#physicalPartition(String)}).</li>
 *   <li>{@link #childPartitions()} — replacement partition IDs to fan out
 *       to. Empty list when {@code partitionRetired} is {@code false}.</li>
 * </ul>
 *
 * In {@link FeedScope.EntireCollection} mode the SDK handles splits/merges
 * internally and these fields are always {@code false}/empty.
 */
public final class ChangeFeedPage {

    private final List<ChangeEvent> events;
    private final String continuationToken;
    private final boolean partitionRetired;
    private final List<String> childPartitions;
    private final OperationDiagnostics diagnostics;

    public ChangeFeedPage(
            List<ChangeEvent> events,
            String continuationToken,
            boolean partitionRetired,
            List<String> childPartitions,
            OperationDiagnostics diagnostics) {
        this.events = events != null ? List.copyOf(events) : Collections.emptyList();
        this.continuationToken = (continuationToken != null && !continuationToken.isEmpty())
                ? continuationToken : null;
        this.partitionRetired = partitionRetired;
        this.childPartitions = childPartitions != null
                ? List.copyOf(childPartitions) : Collections.emptyList();
        this.diagnostics = diagnostics;
    }

    /** Convenience for the common (no-lifecycle) case. */
    public ChangeFeedPage(List<ChangeEvent> events, String continuationToken,
                          OperationDiagnostics diagnostics) {
        this(events, continuationToken, false, Collections.emptyList(), diagnostics);
    }

    public List<ChangeEvent> events() { return events; }
    public String continuationToken() { return continuationToken; }

    /** {@code true} when consuming a single retired physical partition. */
    public boolean partitionRetired() { return partitionRetired; }

    /** Child partition IDs to fan out to after a retirement. */
    public List<String> childPartitions() { return childPartitions; }

    public OperationDiagnostics diagnostics() { return diagnostics; }

    public boolean hasMore() {
        return continuationToken != null;
    }
}

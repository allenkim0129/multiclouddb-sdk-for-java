// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed;

/**
 * Defines what slice of a collection's change feed a {@code readChanges} call
 * should consume.
 * <p>
 * The portable scope is {@link EntireCollection}: the SDK fans out across all
 * provider-native partitions internally; per-partition order is preserved but
 * no global ordering is guaranteed. This is the default if no scope is
 * specified.
 *
 * <p>This is a <em>sealed</em> interface — no third-party implementations.
 * Use {@code instanceof} pattern matching:
 * <pre>{@code
 * if (request.scope() instanceof FeedScope.EntireCollection) {
 *     ...
 * }
 * }</pre>
 */
public sealed interface FeedScope
        permits FeedScope.EntireCollection {

    /** Read every change in the collection across every partition (default). */
    static FeedScope entireCollection() {
        return EntireCollection.INSTANCE;
    }

    /** Entire-collection scope (singleton). */
    final class EntireCollection implements FeedScope {
        static final EntireCollection INSTANCE = new EntireCollection();

        private EntireCollection() {
        }

        @Override
        public String toString() {
            return "FeedScope.EntireCollection";
        }
    }
}

// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed.internal;

import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Decoded form of the opaque token carried by
 * {@link com.multiclouddb.api.changefeed.ChangeFeedCursor#toToken()}.
 * <p>
 * The token's wire format is Base64URL-encoded JSON; see {@link CursorTokenCodec}
 * for the canonical encoding. This class is the in-memory representation that
 * provider adapters consume.
 *
 * <h3>Fields</h3>
 * <ul>
 *   <li>{@code providerId} — the provider that minted the token. Resuming with
 *       a different provider throws
 *       {@link com.multiclouddb.api.changefeed.CursorExpiredException}.</li>
 *   <li>{@code resource} — the {@link ResourceAddress} the cursor reads from,
 *       or {@code null} for an unhydrated {@code now()} sentinel. Resuming
 *       against a different address throws
 *       {@link com.multiclouddb.api.changefeed.CursorExpiredException}.</li>
 *   <li>{@code issuedAtEpochMillis} — wall-clock millisecond when the SDK
 *       last issued this token. Refreshed on every {@code readChanges}
 *       → {@code nextCursor()}. Tokens older than
 *       {@link CursorTokenCodec#MAX_TOKEN_AGE_MILLIS} fail client-side as
 *       expired.</li>
 *   <li>{@code anchor} — see {@link CursorAnchor}.</li>
 *   <li>{@code partitions} — list of {@link PartitionPosition}; empty for a
 *       {@code now()} sentinel that has not yet been read.</li>
 * </ul>
 *
 * Instances are immutable; {@code partitions} is defensively copied.
 */
public final class CursorToken {

    /** Current token codec version. */
    public static final int VERSION = 1;

    private final ProviderId providerId;
    private final ResourceAddress resource;
    private final long issuedAtEpochMillis;
    private final CursorAnchor anchor;
    private final List<PartitionPosition> partitions;

    public CursorToken(ProviderId providerId,
                       ResourceAddress resource,
                       long issuedAtEpochMillis,
                       CursorAnchor anchor,
                       List<PartitionPosition> partitions) {
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.resource = resource; // nullable for unhydrated sentinel
        this.issuedAtEpochMillis = issuedAtEpochMillis;
        this.anchor = Objects.requireNonNull(anchor, "anchor");
        this.partitions = partitions == null
                ? Collections.emptyList()
                : List.copyOf(partitions);
    }

    public ProviderId providerId() {
        return providerId;
    }

    /** May be {@code null} for an unhydrated {@code now()} sentinel. */
    public ResourceAddress resource() {
        return resource;
    }

    public long issuedAtEpochMillis() {
        return issuedAtEpochMillis;
    }

    public CursorAnchor anchor() {
        return anchor;
    }

    /** Unmodifiable; never {@code null} (empty list for an unhydrated sentinel). */
    public List<PartitionPosition> partitions() {
        return partitions;
    }

    /**
     * Return a copy of this token with {@code issuedAtEpochMillis} refreshed
     * to {@code newIssuedAt}, preserving all other fields. Used by providers
     * when minting the {@code nextCursor()} after a successful read.
     */
    public CursorToken withIssuedAt(long newIssuedAt) {
        return new CursorToken(providerId, resource, newIssuedAt, anchor, partitions);
    }

    /**
     * Return a copy of this token with the partitions list replaced, anchor
     * set to {@link CursorAnchor#CONTINUING}, and {@code issuedAtEpochMillis}
     * refreshed. Used by providers to mint the {@code nextCursor()} after a
     * read advances or after a split is absorbed.
     */
    public CursorToken withPartitions(List<PartitionPosition> newPartitions, long newIssuedAt) {
        return new CursorToken(providerId, resource, newIssuedAt,
                CursorAnchor.CONTINUING, newPartitions);
    }

    /**
     * Return a copy of this token bound to {@code addr}, with anchor set to
     * {@link CursorAnchor#CONTINUING} and the supplied partitions /
     * {@code issuedAtEpochMillis}. Used to hydrate a {@code now()} sentinel
     * the first time it is read against a concrete address.
     */
    public CursorToken hydratedAt(ResourceAddress addr,
                                  List<PartitionPosition> newPartitions,
                                  long newIssuedAt) {
        Objects.requireNonNull(addr, "addr");
        return new CursorToken(providerId, addr, newIssuedAt,
                CursorAnchor.CONTINUING, newPartitions);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CursorToken that)) return false;
        return issuedAtEpochMillis == that.issuedAtEpochMillis
                && providerId.equals(that.providerId)
                && Objects.equals(resource, that.resource)
                && anchor == that.anchor
                && partitions.equals(that.partitions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(providerId, resource, issuedAtEpochMillis, anchor, partitions);
    }

    @Override
    public String toString() {
        return "CursorToken{v=" + VERSION
                + ", p=" + providerId.id()
                + ", r=" + resource
                + ", i=" + issuedAtEpochMillis
                + ", anchor=" + anchor
                + ", positions=" + partitions.size()
                + '}';
    }
}

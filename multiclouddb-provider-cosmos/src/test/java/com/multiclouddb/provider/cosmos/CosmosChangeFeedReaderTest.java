// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.models.CosmosChangeFeedRequestOptions;
import com.azure.cosmos.models.FeedRange;
import com.azure.cosmos.models.FeedResponse;
import com.azure.cosmos.util.CosmosPagedIterable;
import com.fasterxml.jackson.databind.JsonNode;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.changefeed.ChangeFeedCursor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CosmosChangeFeedReader#listCursors(CosmosContainer, ResourceAddress)}.
 * <p>
 * Focuses on the invariant that the {@code issuedAtEpochMillis} stamped on each
 * minted {@link com.multiclouddb.api.changefeed.internal.CursorToken} reflects
 * the instant the continuation bookmark is actually effective:
 * <ul>
 *   <li><b>Warmup success</b>: {@code issuedAt} is captured <em>after</em>
 *       {@code FeedResponse.getContinuationToken()} returns, so
 *       {@code issuedAt >= preCallMs}.</li>
 *   <li><b>PIT fallback</b>: when warmup throws or returns no continuation,
 *       {@code issuedAt} equals the numeric suffix of the {@code @@PIT:} sentinel
 *       — the two values must agree exactly by construction.</li>
 *   <li><b>Multi-range</b>: each cursor's {@code issuedAt} is captured
 *       independently per iteration (no single pre-loop timestamp shared by
 *       every cursor).</li>
 * </ul>
 */
class CosmosChangeFeedReaderTest {

    private static final String PIT_PREFIX = "@@PIT:";
    private static final ResourceAddress ADDR = new ResourceAddress("test-db", "test-collection");

    private CosmosChangeFeedReader newReader() {
        return new CosmosChangeFeedReader(ProviderId.COSMOS, Map.of());
    }

    private static FeedRange mockRange(String label) {
        FeedRange r = mock(FeedRange.class);
        when(r.toString()).thenReturn(label);
        return r;
    }

    // ── PIT fallback path ───────────────────────────────────────────────────

    @Test
    @DisplayName("PIT fallback: issuedAt equals @@PIT:<suffix> exactly (warmup throws)")
    void pitFallback_issuedAtEqualsPitSuffix_warmupThrows() {
        CosmosContainer container = mock(CosmosContainer.class);
        FeedRange range = mockRange("range-0");
        when(container.getFeedRanges()).thenReturn(List.of(range));
        when(container.queryChangeFeed(any(CosmosChangeFeedRequestOptions.class), eq(JsonNode.class)))
                .thenThrow(new RuntimeException("simulated warmup failure"));

        List<ChangeFeedCursor> cursors = newReader().listCursors(container, ADDR);

        assertEquals(1, cursors.size(), "one cursor per feed range");
        ChangeFeedCursor c = cursors.get(0);
        long issuedAt = c.token().issuedAtEpochMillis();
        String cont = c.token().partitions().get(0).continuation();
        assertTrue(cont.startsWith(PIT_PREFIX),
                "fallback continuation must use @@PIT: prefix; was " + cont);
        long pitSuffix = Long.parseLong(cont.substring(PIT_PREFIX.length()));
        assertEquals(pitSuffix, issuedAt,
                "issuedAt must equal the PIT suffix by construction (got issuedAt="
                        + issuedAt + ", pitSuffix=" + pitSuffix + ")");
    }

    @Test
    @DisplayName("PIT fallback: issuedAt equals @@PIT:<suffix> exactly (warmup returns blank continuation)")
    @SuppressWarnings("unchecked")
    void pitFallback_issuedAtEqualsPitSuffix_blankContinuation() {
        CosmosContainer container = mock(CosmosContainer.class);
        FeedRange range = mockRange("range-0");
        when(container.getFeedRanges()).thenReturn(List.of(range));

        CosmosPagedIterable<JsonNode> paged = mock(CosmosPagedIterable.class);
        Iterable<FeedResponse<JsonNode>> pages = mock(Iterable.class);
        Iterator<FeedResponse<JsonNode>> it = mock(Iterator.class);
        FeedResponse<JsonNode> resp = mock(FeedResponse.class);
        when(container.queryChangeFeed(any(CosmosChangeFeedRequestOptions.class), eq(JsonNode.class)))
                .thenReturn(paged);
        when(paged.iterableByPage()).thenReturn(pages);
        when(pages.iterator()).thenReturn(it);
        when(it.hasNext()).thenReturn(true);
        when(it.next()).thenReturn(resp);
        when(resp.getContinuationToken()).thenReturn("   "); // blank → triggers fallback

        List<ChangeFeedCursor> cursors = newReader().listCursors(container, ADDR);

        assertEquals(1, cursors.size());
        long issuedAt = cursors.get(0).token().issuedAtEpochMillis();
        String cont = cursors.get(0).token().partitions().get(0).continuation();
        assertTrue(cont.startsWith(PIT_PREFIX),
                "blank-continuation case must fall back to @@PIT:; was " + cont);
        long pitSuffix = Long.parseLong(cont.substring(PIT_PREFIX.length()));
        assertEquals(pitSuffix, issuedAt,
                "issuedAt must equal the PIT suffix exactly by construction");
    }

    // ── Warmup success path ─────────────────────────────────────────────────

    @Test
    @DisplayName("Warmup success: issuedAt is captured after getContinuationToken() returns")
    @SuppressWarnings("unchecked")
    void warmupSuccess_issuedAtCapturedAfterContinuation() {
        CosmosContainer container = mock(CosmosContainer.class);
        // Use a real FeedRange — CosmosChangeFeedRequestOptions.createForProcessingFromNow
        // casts the FeedRange to its concrete internal type, so a Mockito-mocked FeedRange
        // throws inside the warmup and silently sends the call down the PIT fallback path
        // (which would defeat the purpose of this test).
        FeedRange range = FeedRange.forFullRange();
        when(container.getFeedRanges()).thenReturn(List.of(range));

        CosmosPagedIterable<JsonNode> paged = mock(CosmosPagedIterable.class);
        Iterable<FeedResponse<JsonNode>> pages = mock(Iterable.class);
        Iterator<FeedResponse<JsonNode>> it = mock(Iterator.class);
        FeedResponse<JsonNode> resp = mock(FeedResponse.class);
        when(container.queryChangeFeed(any(CosmosChangeFeedRequestOptions.class), eq(JsonNode.class)))
                .thenReturn(paged);
        when(paged.iterableByPage()).thenReturn(pages);
        when(pages.iterator()).thenReturn(it);
        when(it.hasNext()).thenReturn(true);
        when(it.next()).thenReturn(resp);
        when(resp.getContinuationToken()).thenReturn("real-cosmos-continuation-token-xyz");

        long preCall = System.currentTimeMillis();
        List<ChangeFeedCursor> cursors = newReader().listCursors(container, ADDR);
        long postCall = System.currentTimeMillis();

        assertEquals(1, cursors.size());
        ChangeFeedCursor c = cursors.get(0);
        long issuedAt = c.token().issuedAtEpochMillis();
        String cont = c.token().partitions().get(0).continuation();
        assertEquals("real-cosmos-continuation-token-xyz", cont,
                "real continuation token must be persisted on success path");
        assertFalse(cont.startsWith(PIT_PREFIX),
                "success path must NOT use @@PIT: prefix; was " + cont);
        assertTrue(issuedAt >= preCall,
                "issuedAt (" + issuedAt + ") must be at or after preCall (" + preCall + ")");
        assertTrue(issuedAt <= postCall,
                "issuedAt (" + issuedAt + ") must not exceed postCall (" + postCall + ")");
    }

    // ── Multi-range invariant ───────────────────────────────────────────────

    @Test
    @DisplayName("Multi-range: each cursor carries its own issuedAt (not a shared pre-loop timestamp)")
    void multiRange_eachCursorHasIndependentIssuedAt() {
        CosmosContainer container = mock(CosmosContainer.class);
        FeedRange r1 = mockRange("range-1");
        FeedRange r2 = mockRange("range-2");
        FeedRange r3 = mockRange("range-3");
        when(container.getFeedRanges()).thenReturn(List.of(r1, r2, r3));
        // Force all three ranges down the PIT fallback path; each cursor's
        // issuedAt is then deterministically equal to the @@PIT: suffix captured
        // for that range's iteration. The point of this test is that each
        // iteration captures its own nowMs — not that the values are necessarily
        // distinct (they may coincide on a fast machine within 1ms).
        when(container.queryChangeFeed(any(CosmosChangeFeedRequestOptions.class), eq(JsonNode.class)))
                .thenThrow(new RuntimeException("forced PIT fallback"));

        long preLoop = System.currentTimeMillis();
        List<ChangeFeedCursor> cursors = newReader().listCursors(container, ADDR);
        long postLoop = System.currentTimeMillis();

        assertEquals(3, cursors.size(), "one cursor per feed range");
        // Invariant 1: every cursor's issuedAt equals its own @@PIT: suffix
        // (proves issuedAt was captured per-iteration, not once before the loop).
        for (ChangeFeedCursor c : cursors) {
            long issuedAt = c.token().issuedAtEpochMillis();
            String cont = c.token().partitions().get(0).continuation();
            assertTrue(cont.startsWith(PIT_PREFIX),
                    "expected @@PIT: continuation; was " + cont);
            long pitSuffix = Long.parseLong(cont.substring(PIT_PREFIX.length()));
            assertEquals(pitSuffix, issuedAt,
                    "per-iteration: issuedAt must equal its own PIT suffix");
            assertTrue(issuedAt >= preLoop && issuedAt <= postLoop,
                    "issuedAt (" + issuedAt + ") must be within [preLoop=" + preLoop
                            + ", postLoop=" + postLoop + "]");
        }
        // Invariant 2: cursors are distinct partitions (sanity).
        Set<String> partitionIds = cursors.stream()
                .map(c -> c.token().partitions().get(0).partitionId())
                .collect(Collectors.toSet());
        assertEquals(3, partitionIds.size(), "each cursor should bind a distinct feed range");
    }

    // ── Empty-ranges fallback ───────────────────────────────────────────────

    @Test
    @DisplayName("Empty getFeedRanges() falls back to FeedRange.forFullRange() and still mints a cursor")
    void emptyRanges_fallsBackToFullRange() {
        CosmosContainer container = mock(CosmosContainer.class);
        when(container.getFeedRanges()).thenReturn(Collections.emptyList());
        when(container.queryChangeFeed(any(CosmosChangeFeedRequestOptions.class), eq(JsonNode.class)))
                .thenThrow(new RuntimeException("forced PIT fallback"));

        List<ChangeFeedCursor> cursors = newReader().listCursors(container, ADDR);

        assertEquals(1, cursors.size(),
                "empty getFeedRanges() must still produce one cursor (forFullRange fallback)");
        ChangeFeedCursor c = cursors.get(0);
        assertNotNull(c.token().partitions().get(0).partitionId());
    }
}

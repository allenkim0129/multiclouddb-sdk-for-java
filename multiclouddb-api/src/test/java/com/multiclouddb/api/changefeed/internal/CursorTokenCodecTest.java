// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api.changefeed.internal;

import com.multiclouddb.api.MulticloudDbErrorCategory;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.ResourceAddress;
import com.multiclouddb.api.changefeed.CursorExpiredException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CursorTokenCodecTest {

    private static final ProviderId COSMOS = ProviderId.fromId("cosmos");
    private static final ProviderId DYNAMO = ProviderId.fromId("dynamo");
    private static final ResourceAddress ADDR = new ResourceAddress("todoapp", "todos");
    private static final ResourceAddress OTHER_ADDR = new ResourceAddress("todoapp", "audit");

    private static CursorToken token(ProviderId p, ResourceAddress r, long issuedAt,
                                     CursorAnchor anchor, List<PartitionPosition> parts) {
        return new CursorToken(p, r, issuedAt, anchor, parts);
    }

    @Test
    @DisplayName("round-trip: encode then decode yields the same token")
    void roundTrip() {
        List<PartitionPosition> parts = List.of(
                new PartitionPosition("p0", "continuation-a"),
                new PartitionPosition("p1", null));
        CursorToken original = token(COSMOS, ADDR, 1_700_000_000_000L,
                CursorAnchor.CONTINUING, parts);

        String wire = CursorTokenCodec.encode(original);
        CursorToken decoded = CursorTokenCodec.decode(wire, 1_700_000_000_000L);

        assertEquals(original, decoded);
    }

    @Test
    @DisplayName("round-trip: unhydrated sentinel (resource=null) preserves null resource")
    void roundTripSentinel() {
        CursorToken sentinel = token(ProviderId.fromId("multicloud"), null,
                System.currentTimeMillis(), CursorAnchor.NOW, List.of());
        String wire = CursorTokenCodec.encode(sentinel);
        CursorToken decoded = CursorTokenCodec.decode(wire);
        assertNull(decoded.resource(), "sentinel must keep null resource binding");
        assertEquals(CursorAnchor.NOW, decoded.anchor());
        assertTrue(decoded.partitions().isEmpty());
    }

    @Test
    @DisplayName("decode: null/blank token raises MALFORMED")
    void decodeBlankMalformed() {
        CursorExpiredException ex = assertThrows(CursorExpiredException.class,
                () -> CursorTokenCodec.decode(""));
        assertEquals(MulticloudDbErrorCategory.CURSOR_EXPIRED, ex.error().category());
        assertEquals(CursorTokenCodec.REASON_MALFORMED,
                ex.error().providerDetails().get("reason"));
    }

    @Test
    @DisplayName("decode: garbage base64 raises MALFORMED")
    void decodeGarbageMalformed() {
        CursorExpiredException ex = assertThrows(CursorExpiredException.class,
                () -> CursorTokenCodec.decode("!!!not-base64!!!"));
        assertEquals(CursorTokenCodec.REASON_MALFORMED,
                ex.error().providerDetails().get("reason"));
    }

    @Test
    @DisplayName("decode: valid base64 of non-JSON raises MALFORMED")
    void decodeNonJsonMalformed() {
        String wire = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("hello world".getBytes(StandardCharsets.UTF_8));
        CursorExpiredException ex = assertThrows(CursorExpiredException.class,
                () -> CursorTokenCodec.decode(wire));
        assertEquals(CursorTokenCodec.REASON_MALFORMED,
                ex.error().providerDetails().get("reason"));
    }

    @Test
    @DisplayName("decode: future codec version raises VERSION_UNSUPPORTED")
    void decodeFutureVersionUnsupported() {
        // Hand-craft a payload with v=999
        String json = "{\"v\":999,\"p\":\"cosmos\",\"r\":\"todoapp/todos\","
                + "\"i\":1700000000000,\"a\":\"CONTINUING\",\"s\":[]}";
        String wire = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        CursorExpiredException ex = assertThrows(CursorExpiredException.class,
                () -> CursorTokenCodec.decode(wire, 1_700_000_000_000L));
        assertEquals(CursorTokenCodec.REASON_VERSION_UNSUPPORTED,
                ex.error().providerDetails().get("reason"));
    }

    @Test
    @DisplayName("decode: token older than 24h raises TOKEN_AGED_OUT")
    void decodeAgedOut() {
        long issuedAt = 1_000_000_000_000L;
        long now = issuedAt + CursorTokenCodec.MAX_TOKEN_AGE_MILLIS + 1;
        CursorToken old = token(COSMOS, ADDR, issuedAt, CursorAnchor.CONTINUING,
                List.of(new PartitionPosition("p0", "c")));
        String wire = CursorTokenCodec.encode(old);
        CursorExpiredException ex = assertThrows(CursorExpiredException.class,
                () -> CursorTokenCodec.decode(wire, now));
        assertEquals(CursorTokenCodec.REASON_TOKEN_AGED_OUT,
                ex.error().providerDetails().get("reason"));
    }

    @Test
    @DisplayName("decode: token within 24h (boundary) succeeds")
    void decodeBoundaryOk() {
        long issuedAt = 1_000_000_000_000L;
        long now = issuedAt + CursorTokenCodec.MAX_TOKEN_AGE_MILLIS; // exactly at boundary, not >
        CursorToken at = token(COSMOS, ADDR, issuedAt, CursorAnchor.CONTINUING,
                List.of(new PartitionPosition("p0", null)));
        String wire = CursorTokenCodec.encode(at);
        // Should NOT throw at the exact 24h boundary (boundary is strictly >)
        assertDoesNotThrow(() -> CursorTokenCodec.decode(wire, now));
    }

    @Test
    @DisplayName("validateProviderMatch: cross-provider token raises PROVIDER_MISMATCH")
    void providerMismatch() {
        CursorToken minted = token(DYNAMO, ADDR, System.currentTimeMillis(),
                CursorAnchor.CONTINUING, List.of(new PartitionPosition("p0", null)));
        CursorExpiredException ex = assertThrows(CursorExpiredException.class,
                () -> CursorTokenCodec.validateProviderMatch(minted, COSMOS));
        assertEquals(CursorTokenCodec.REASON_PROVIDER_MISMATCH,
                ex.error().providerDetails().get("reason"));
    }

    @Test
    @DisplayName("validateProviderMatch: matching provider succeeds")
    void providerMatchOk() {
        CursorToken minted = token(COSMOS, ADDR, System.currentTimeMillis(),
                CursorAnchor.CONTINUING, List.of(new PartitionPosition("p0", null)));
        assertDoesNotThrow(() -> CursorTokenCodec.validateProviderMatch(minted, COSMOS));
    }

    @Test
    @DisplayName("validateResourceMatch: wrong resource raises RESOURCE_MISMATCH")
    void resourceMismatch() {
        CursorToken minted = token(COSMOS, ADDR, System.currentTimeMillis(),
                CursorAnchor.CONTINUING, List.of(new PartitionPosition("p0", null)));
        CursorExpiredException ex = assertThrows(CursorExpiredException.class,
                () -> CursorTokenCodec.validateResourceMatch(minted, OTHER_ADDR));
        assertEquals(CursorTokenCodec.REASON_RESOURCE_MISMATCH,
                ex.error().providerDetails().get("reason"));
    }

    @Test
    @DisplayName("validateResourceMatch: unhydrated sentinel (resource=null) is always accepted")
    void resourceMatchSentinel() {
        CursorToken sentinel = token(ProviderId.fromId("multicloud"), null,
                System.currentTimeMillis(), CursorAnchor.NOW, List.of());
        assertDoesNotThrow(() -> CursorTokenCodec.validateResourceMatch(sentinel, ADDR));
        assertDoesNotThrow(() -> CursorTokenCodec.validateResourceMatch(sentinel, OTHER_ADDR));
    }

    @Test
    @DisplayName("decode: missing required fields raise MALFORMED")
    void decodeMissingFieldsMalformed() {
        // Missing 'p' (provider)
        String missingP = "{\"v\":1,\"i\":1700000000000,\"a\":\"CONTINUING\",\"s\":[]}";
        String wire = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(missingP.getBytes(StandardCharsets.UTF_8));
        CursorExpiredException ex = assertThrows(CursorExpiredException.class,
                () -> CursorTokenCodec.decode(wire, 1_700_000_000_000L));
        assertEquals(CursorTokenCodec.REASON_MALFORMED,
                ex.error().providerDetails().get("reason"));
    }

    @Test
    @DisplayName("decode: malformed resource binding raises MALFORMED")
    void decodeBadResourceBindingMalformed() {
        // 'r' is not in 'database/collection' form
        String bad = "{\"v\":1,\"p\":\"cosmos\",\"r\":\"justonepart\","
                + "\"i\":1700000000000,\"a\":\"CONTINUING\",\"s\":[]}";
        String wire = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(bad.getBytes(StandardCharsets.UTF_8));
        CursorExpiredException ex = assertThrows(CursorExpiredException.class,
                () -> CursorTokenCodec.decode(wire, 1_700_000_000_000L));
        assertEquals(CursorTokenCodec.REASON_MALFORMED,
                ex.error().providerDetails().get("reason"));
    }
}

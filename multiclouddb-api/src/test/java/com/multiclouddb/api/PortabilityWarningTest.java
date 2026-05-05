// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PortabilityWarningTest {

    @Test
    void ofRejectsNullArgs() {
        assertThrows(NullPointerException.class, () -> PortabilityWarning.of(
                null, "m", PortabilityWarning.Scope.CLIENT_CONFIG,
                ProviderId.COSMOS, PortabilityWarning.Category.PROVIDER_SPECIFIC_CONFIG));
        assertThrows(NullPointerException.class, () -> PortabilityWarning.of(
                "c", null, PortabilityWarning.Scope.CLIENT_CONFIG,
                ProviderId.COSMOS, PortabilityWarning.Category.PROVIDER_SPECIFIC_CONFIG));
        assertThrows(NullPointerException.class, () -> PortabilityWarning.of(
                "c", "m", null,
                ProviderId.COSMOS, PortabilityWarning.Category.PROVIDER_SPECIFIC_CONFIG));
        assertThrows(NullPointerException.class, () -> PortabilityWarning.of(
                "c", "m", PortabilityWarning.Scope.CLIENT_CONFIG,
                null, PortabilityWarning.Category.PROVIDER_SPECIFIC_CONFIG));
        assertThrows(NullPointerException.class, () -> PortabilityWarning.of(
                "c", "m", PortabilityWarning.Scope.CLIENT_CONFIG,
                ProviderId.COSMOS, null));
    }

    @Test
    void ofRejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> PortabilityWarning.of(
                "  ", "m", PortabilityWarning.Scope.CLIENT_CONFIG,
                ProviderId.COSMOS, PortabilityWarning.Category.PROVIDER_SPECIFIC_CONFIG));
        assertThrows(IllegalArgumentException.class, () -> PortabilityWarning.of(
                "c", "  ", PortabilityWarning.Scope.CLIENT_CONFIG,
                ProviderId.COSMOS, PortabilityWarning.Category.PROVIDER_SPECIFIC_CONFIG));
    }

    @Test
    void providerConfigConvenienceFactory() {
        PortabilityWarning w = PortabilityWarning.providerConfig(
                "x.y", "non-portable opt-in enabled", ProviderId.DYNAMO);
        assertEquals("x.y", w.code());
        assertEquals(ProviderId.DYNAMO, w.provider());
        assertEquals(PortabilityWarning.Scope.CLIENT_CONFIG, w.scope());
        assertEquals(PortabilityWarning.Category.PROVIDER_SPECIFIC_CONFIG, w.category());
    }

    @Test
    void valueEqualityAndHashCode() {
        PortabilityWarning a = PortabilityWarning.providerConfig("c", "m", ProviderId.SPANNER);
        PortabilityWarning b = PortabilityWarning.providerConfig("c", "m", ProviderId.SPANNER);
        PortabilityWarning c = PortabilityWarning.providerConfig("c2", "m", ProviderId.SPANNER);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }

    @Test
    void toStringExposesKeyFields() {
        PortabilityWarning w = PortabilityWarning.providerConfig("foo.bar", "msg", ProviderId.COSMOS);
        String s = w.toString();
        assertTrue(s.contains("foo.bar"));
        assertTrue(s.contains("cosmos"));
        assertTrue(s.contains("PROVIDER_SPECIFIC_CONFIG"));
    }
}

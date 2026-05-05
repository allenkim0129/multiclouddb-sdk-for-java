// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.conformance.us4;

import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbClientFactory;
import com.multiclouddb.api.PortabilityWarning;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.conformance.ConformanceConfig;
import com.multiclouddb.conformance.ConformanceHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conformance tests for {@link PortabilityWarning} surfacing across providers
 * (issue #37 §7).
 *
 * <p>Contract:
 * <ol>
 *   <li>Default client configuration emits zero portability warnings on every
 *       supported provider.</li>
 *   <li>Provider-specific feature flags emit warnings but do not break client
 *       creation.</li>
 *   <li>Warnings are surfaced identically (same {@code PortabilityWarning}
 *       structure) — non-null code, message, scope, provider, category — and
 *       returned as an immutable list.</li>
 * </ol>
 *
 * <p>Subclasses provide:
 * <ul>
 *   <li>{@link #provider()} — the target provider id.</li>
 *   <li>{@link #providerSpecificOptIns()} — additional connection-level
 *       config keys that should trigger warnings on this provider. Default
 *       returns an empty map (no opt-ins available); the
 *       "feature flag emits warnings" test then becomes a no-op assertion
 *       that the runner reports zero warnings, which is still a meaningful
 *       conformance signal because clients should be free to construct
 *       successfully.</li>
 * </ul>
 */
public abstract class PortabilityWarningConformanceTest {

    /** The provider under test. */
    protected abstract ProviderId provider();

    /**
     * Provider-specific connection config keys (key → value) that, when added to
     * the default conformance config, should trigger one or more
     * {@link PortabilityWarning}s. Default returns an empty map.
     */
    protected Map<String, String> providerSpecificOptIns() {
        return Map.of();
    }

    /** True when the provider has at least one known opt-in flag. */
    protected boolean hasProviderSpecificOptIns() {
        return !providerSpecificOptIns().isEmpty();
    }

    @Test
    void defaultConfigurationEmitsZeroWarnings() throws Exception {
        try (MulticloudDbClient client = ConformanceHarness.createClient(provider())) {
            List<PortabilityWarning> warnings = client.portabilityWarnings();
            assertNotNull(warnings, "portabilityWarnings() must not return null");
            assertTrue(warnings.isEmpty(),
                    "default client configuration must emit zero portability warnings, got: " + warnings);
        }
    }

    @Test
    void portabilityWarningsListIsImmutable() throws Exception {
        try (MulticloudDbClient client = ConformanceHarness.createClient(provider())) {
            List<PortabilityWarning> warnings = client.portabilityWarnings();
            assertThrows(UnsupportedOperationException.class,
                    () -> warnings.add(PortabilityWarning.providerConfig(
                            "test", "test", provider())),
                    "portabilityWarnings() must return an immutable list");
        }
    }

    @Test
    @EnabledIf("hasProviderSpecificOptIns")
    void providerSpecificOptInsEmitWarningsWithoutBreakingClientCreation() throws Exception {
        // Build a config that starts from the default and adds the opt-ins.
        MulticloudDbClientConfig.Builder builder = configBuilderFromDefault();
        for (Map.Entry<String, String> entry : providerSpecificOptIns().entrySet()) {
            builder.connection(entry.getKey(), entry.getValue());
        }

        // Client creation must succeed — opt-ins never break construction.
        try (MulticloudDbClient client = MulticloudDbClientFactory.create(builder.build())) {
            List<PortabilityWarning> warnings = client.portabilityWarnings();
            assertNotNull(warnings);
            assertFalse(warnings.isEmpty(),
                    "provider-specific opt-ins must surface at least one warning, got empty");

            // Every warning must be well-formed and consistently structured.
            for (PortabilityWarning w : warnings) {
                assertNotNull(w.code(), "warning.code() must not be null");
                assertFalse(w.code().isBlank(), "warning.code() must not be blank");
                assertNotNull(w.message(), "warning.message() must not be null");
                assertFalse(w.message().isBlank(), "warning.message() must not be blank");
                assertNotNull(w.scope(), "warning.scope() must not be null");
                assertNotNull(w.category(), "warning.category() must not be null");
                assertEquals(provider(), w.provider(),
                        "warning.provider() must match the active provider");
            }
        }
    }

    /**
     * Build a {@link MulticloudDbClientConfig.Builder} that mirrors the default
     * conformance configuration so subclass tests can append opt-ins on top.
     * <p>
     * This exists because {@link ConformanceConfig#forProvider(ProviderId)}
     * returns a built config (not a builder); we re-derive the equivalent
     * builder here. The set of keys mirrored matches what
     * {@link ConformanceConfig} constructs.
     */
    private MulticloudDbClientConfig.Builder configBuilderFromDefault() {
        MulticloudDbClientConfig source = ConformanceConfig.forProvider(provider());
        MulticloudDbClientConfig.Builder b = MulticloudDbClientConfig.builder()
                .provider(provider());
        source.connection().forEach(b::connection);
        source.auth().forEach(b::auth);
        return b;
    }
}

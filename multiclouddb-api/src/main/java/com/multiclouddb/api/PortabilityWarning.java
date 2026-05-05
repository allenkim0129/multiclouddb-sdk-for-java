// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;

import java.util.Objects;

/**
 * A structured signal that an aspect of the current {@link MulticloudDbClient}
 * configuration or operation may reduce portability across providers.
 * <p>
 * Portability warnings are <b>informational</b> — they never break client
 * creation or fail an operation. They surface places where the user has
 * explicitly opted into provider-specific behaviour (e.g., Cosmos
 * {@code consistencyLevel}, {@code connectionMode=direct}) so that the
 * "write once, run anywhere" boundary is visible at runtime.
 * <p>
 * Conformance contract (see issue #37 §7):
 * <ul>
 *   <li>Default client configuration emits <b>zero</b> portability warnings on
 *       every supported provider.</li>
 *   <li>Provider-specific feature flags emit warnings but do not break
 *       client creation.</li>
 *   <li>Warnings are surfaced identically (same {@code PortabilityWarning}
 *       structure) across providers.</li>
 * </ul>
 *
 * <h2>Stable fields</h2>
 * <ul>
 *   <li>{@link #code()} — short, machine-readable identifier (e.g.
 *       {@code "cosmos.consistencyLevel"}). Use these for filtering or
 *       suppression. Codes are stable across releases for a given concept.</li>
 *   <li>{@link #message()} — human-readable explanation of why portability
 *       may be reduced.</li>
 *   <li>{@link #scope()} — what the warning applies to (e.g.
 *       {@link Scope#CLIENT_CONFIG}, {@link Scope#OPERATION}).</li>
 *   <li>{@link #provider()} — which provider emitted the warning.</li>
 *   <li>{@link #category()} — the kind of non-portability
 *       (provider-specific config, native expression, behavioural
 *       divergence, etc.).</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * try (MulticloudDbClient client = MulticloudDbClientFactory.create(config)) {
 *     for (PortabilityWarning w : client.portabilityWarnings()) {
 *         logger.warn("[portability] {} ({}): {}",
 *                 w.code(), w.provider().id(), w.message());
 *     }
 * }
 * }</pre>
 *
 * <p>Warnings are immutable, value-based, and safe to share across threads.
 */
public final class PortabilityWarning {

    /**
     * What the warning applies to.
     */
    public enum Scope {
        /** Warning applies to the client configuration as a whole. */
        CLIENT_CONFIG,
        /** Warning applies to a single operation invocation. */
        OPERATION,
        /** Warning applies to a specific resource (database/collection). */
        RESOURCE
    }

    /**
     * The kind of non-portable behaviour the warning reports.
     */
    public enum Category {
        /**
         * A provider-specific configuration option has been enabled (e.g.
         * Cosmos {@code consistencyLevel}, {@code connectionMode=direct}).
         */
        PROVIDER_SPECIFIC_CONFIG,
        /**
         * A native (provider-specific) query expression is being executed in
         * place of a portable expression. The query is not translatable across
         * providers.
         */
        NATIVE_EXPRESSION,
        /**
         * A portable feature is supported by the active provider but exhibits
         * behavioural divergence from the LCD contract (e.g., a feature
         * surfaces only "latest-version" semantics on this provider).
         */
        BEHAVIORAL_DIVERGENCE,
        /**
         * Provider-specific feature flag enabled at the application level (an
         * extension hook or opt-in toggle) — see FR-021.
         */
        PROVIDER_SPECIFIC_FEATURE
    }

    private final String code;
    private final String message;
    private final Scope scope;
    private final ProviderId provider;
    private final Category category;

    private PortabilityWarning(String code, String message, Scope scope,
                               ProviderId provider, Category category) {
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.message = Objects.requireNonNull(message, "message must not be null");
        this.scope = Objects.requireNonNull(scope, "scope must not be null");
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.category = Objects.requireNonNull(category, "category must not be null");
        if (code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }

    /**
     * Construct a portability warning. All arguments are required.
     *
     * @param code     short, stable, machine-readable identifier; non-blank
     * @param message  human-readable explanation; non-blank
     * @param scope    what the warning applies to
     * @param provider which provider emitted the warning
     * @param category the kind of non-portable behaviour
     * @return a new {@link PortabilityWarning}
     * @throws NullPointerException     if any argument is null
     * @throws IllegalArgumentException if {@code code} or {@code message} is blank
     */
    public static PortabilityWarning of(String code, String message, Scope scope,
                                        ProviderId provider, Category category) {
        return new PortabilityWarning(code, message, scope, provider, category);
    }

    /**
     * Convenience factory for the common case: a client-config-scoped warning
     * about a provider-specific configuration option.
     */
    public static PortabilityWarning providerConfig(String code, String message, ProviderId provider) {
        return of(code, message, Scope.CLIENT_CONFIG, provider, Category.PROVIDER_SPECIFIC_CONFIG);
    }

    /** Short, stable, machine-readable identifier (non-blank). */
    public String code() { return code; }

    /** Human-readable explanation of why portability may be reduced (non-blank). */
    public String message() { return message; }

    /** What the warning applies to. */
    public Scope scope() { return scope; }

    /** The provider that emitted the warning. */
    public ProviderId provider() { return provider; }

    /** The kind of non-portable behaviour. */
    public Category category() { return category; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PortabilityWarning that)) return false;
        return code.equals(that.code)
                && message.equals(that.message)
                && scope == that.scope
                && provider.equals(that.provider)
                && category == that.category;
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, message, scope, provider, category);
    }

    @Override
    public String toString() {
        return "PortabilityWarning{"
                + "code='" + code + '\''
                + ", provider=" + provider.id()
                + ", scope=" + scope
                + ", category=" + category
                + ", message='" + message + '\''
                + '}';
    }
}

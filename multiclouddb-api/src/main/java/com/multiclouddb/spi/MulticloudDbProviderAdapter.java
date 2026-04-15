// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.spi;

import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.query.ExpressionTranslator;

/**
 * SPI contract for provider adapter discovery via
 * {@link java.util.ServiceLoader}.
 * <p>
 * Each provider module registers an implementation of this interface in
 * {@code META-INF/services/com.multiclouddb.spi.MulticloudDbProviderAdapter}.
 */
public interface MulticloudDbProviderAdapter {

    /**
     * The provider this adapter handles.
     */
    ProviderId providerId();

    /**
     * Create a provider client for the given configuration.
     *
     * @param config client configuration (connection, auth, options)
     * @return a new provider client instance
     */
    MulticloudDbProviderClient createClient(MulticloudDbClientConfig config);

    /**
     * Create an expression translator for portable query support.
     * Providers that support portable query expressions should override this
     * method.
     *
     * @return the expression translator, or {@code null} if portable expressions
     *         are not supported
     */
    default ExpressionTranslator createExpressionTranslator() {
        return null;
    }
}

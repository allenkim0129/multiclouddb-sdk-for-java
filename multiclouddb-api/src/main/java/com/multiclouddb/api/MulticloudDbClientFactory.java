// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.api;

import com.multiclouddb.api.internal.DefaultMulticloudDbClient;
import com.multiclouddb.api.query.ExpressionTranslator;
import com.multiclouddb.spi.MulticloudDbProviderAdapter;
import com.multiclouddb.spi.MulticloudDbProviderClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ServiceLoader;

/**
 * Factory that creates {@link MulticloudDbClient} instances by discovering
 * provider adapters via {@link ServiceLoader}.
 */
public final class MulticloudDbClientFactory {

    private static final Logger LOG = LoggerFactory.getLogger(MulticloudDbClientFactory.class);

    private MulticloudDbClientFactory() {
    }

    /**
     * Create a {@link MulticloudDbClient} from configuration.
     * Discovers the matching provider adapter via {@link ServiceLoader} and
     * delegates client creation.
     *
     * @param config client configuration specifying provider, connection, and auth
     * @return a fully configured {@link MulticloudDbClient}
     * @throws MulticloudDbException if no adapter is found for the requested provider
     */
    public static MulticloudDbClient create(MulticloudDbClientConfig config) {
        ProviderId requestedProvider = config.provider();
        LOG.info("Creating Multicloud DB client for provider: {}", requestedProvider.id());

        ServiceLoader<MulticloudDbProviderAdapter> loader =
                ServiceLoader.load(MulticloudDbProviderAdapter.class);

        for (MulticloudDbProviderAdapter adapter : loader) {
            if (adapter.providerId() == requestedProvider) {
                LOG.info("Found adapter: {} for provider: {}",
                        adapter.getClass().getName(), requestedProvider.id());

                MulticloudDbProviderClient providerClient = adapter.createClient(config);
                DefaultMulticloudDbClient client = new DefaultMulticloudDbClient(providerClient, config);

                ExpressionTranslator translator = adapter.createExpressionTranslator();
                if (translator != null) {
                    client.setExpressionTranslator(translator);
                    LOG.info("Expression translator wired for provider: {}", requestedProvider.id());
                }

                return client;
            }
        }

        throw new MulticloudDbException(new MulticloudDbError(
                MulticloudDbErrorCategory.INVALID_REQUEST,
                "No provider adapter found for: " + requestedProvider.id()
                        + ". Ensure the provider module is on the classpath.",
                requestedProvider,
                "create",
                false,
                null));
    }
}

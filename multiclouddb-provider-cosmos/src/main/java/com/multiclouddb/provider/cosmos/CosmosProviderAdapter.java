// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.cosmos;

import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.query.ExpressionTranslator;
import com.multiclouddb.spi.MulticloudDbProviderAdapter;
import com.multiclouddb.spi.MulticloudDbProviderClient;

/**
 * ServiceLoader-discovered adapter for Azure Cosmos DB.
 */
public class CosmosProviderAdapter implements MulticloudDbProviderAdapter {

    @Override
    public ProviderId providerId() {
        return ProviderId.COSMOS;
    }

    @Override
    public MulticloudDbProviderClient createClient(MulticloudDbClientConfig config) {
        return new CosmosProviderClient(config);
    }

    @Override
    public ExpressionTranslator createExpressionTranslator() {
        return new CosmosExpressionTranslator();
    }
}

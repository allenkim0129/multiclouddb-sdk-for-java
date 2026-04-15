// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.dynamo;

import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.query.ExpressionTranslator;
import com.multiclouddb.spi.MulticloudDbProviderAdapter;
import com.multiclouddb.spi.MulticloudDbProviderClient;

/**
 * ServiceLoader-discovered adapter for Amazon DynamoDB.
 */
public class DynamoProviderAdapter implements MulticloudDbProviderAdapter {

    @Override
    public ProviderId providerId() {
        return ProviderId.DYNAMO;
    }

    @Override
    public MulticloudDbProviderClient createClient(MulticloudDbClientConfig config) {
        return new DynamoProviderClient(config);
    }

    @Override
    public ExpressionTranslator createExpressionTranslator() {
        return new DynamoExpressionTranslator();
    }
}

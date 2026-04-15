// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.multiclouddb.provider.spanner;

import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.ProviderId;
import com.multiclouddb.api.query.ExpressionTranslator;
import com.multiclouddb.spi.MulticloudDbProviderAdapter;
import com.multiclouddb.spi.MulticloudDbProviderClient;

/**
 * ServiceLoader-discovered adapter for Google Cloud Spanner.
 */
public class SpannerProviderAdapter implements MulticloudDbProviderAdapter {

    @Override
    public ProviderId providerId() {
        return ProviderId.SPANNER;
    }

    @Override
    public MulticloudDbProviderClient createClient(MulticloudDbClientConfig config) {
        return new SpannerProviderClient(config);
    }

    @Override
    public ExpressionTranslator createExpressionTranslator() {
        return new SpannerExpressionTranslator();
    }
}

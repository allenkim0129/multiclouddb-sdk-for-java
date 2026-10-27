// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

/** Explicit descriptor for the packaged private Cosmos v4 runtime. */
module com.multiclouddb.provider.cosmos {
    requires com.multiclouddb.api;
    requires org.slf4j;
    requires com.sun.jna;
    requires com.sun.jna.platform;
    requires java.desktop;
    requires java.naming;
    requires java.sql;
    requires java.xml;
    requires java.logging;
    requires java.management;
    requires java.security.jgss;
    requires jdk.httpserver;
    requires jdk.management;
    requires jdk.unsupported;

    uses com.multiclouddb.provider.cosmos.shaded.com.azure.core.http.HttpClientProvider;
    uses com.multiclouddb.provider.cosmos.shaded.com.azure.core.http.policy.AfterRetryPolicyProvider;
    uses com.multiclouddb.provider.cosmos.shaded.com.azure.core.http.policy.BeforeRetryPolicyProvider;
    uses com.multiclouddb.provider.cosmos.shaded.com.azure.core.util.metrics.MeterProvider;
    uses com.multiclouddb.provider.cosmos.shaded.com.azure.core.util.serializer.JsonSerializerProvider;
    uses com.multiclouddb.provider.cosmos.shaded.com.azure.core.util.serializer.MemberNameConverterProvider;
    uses com.multiclouddb.provider.cosmos.shaded.com.azure.core.util.tracing.Tracer;
    uses com.multiclouddb.provider.cosmos.shaded.com.azure.core.util.tracing.TracerProvider;
    uses com.multiclouddb.provider.cosmos.shaded.com.azure.cosmos.implementation.guava25.base.PatternCompiler;
    uses com.multiclouddb.provider.cosmos.shaded.com.azure.json.JsonProvider;
    uses com.multiclouddb.provider.cosmos.shaded.com.fasterxml.jackson.databind.Module;
    uses com.multiclouddb.provider.cosmos.shaded.com.fasterxml.jackson.core.JsonFactory;
    uses com.multiclouddb.provider.cosmos.shaded.com.fasterxml.jackson.core.ObjectCodec;

    provides com.multiclouddb.spi.MulticloudDbProviderAdapter
        with com.multiclouddb.provider.cosmos.CosmosProviderAdapter;
    provides com.multiclouddb.provider.cosmos.shaded.com.azure.core.http.HttpClientProvider
        with com.multiclouddb.provider.cosmos.shaded.com.azure.core.http.netty.NettyAsyncHttpClientProvider;
    provides com.multiclouddb.provider.cosmos.shaded.com.fasterxml.jackson.databind.Module
        with com.multiclouddb.provider.cosmos.shaded.com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
    provides com.multiclouddb.provider.cosmos.shaded.com.fasterxml.jackson.core.JsonFactory
        with com.multiclouddb.provider.cosmos.shaded.com.fasterxml.jackson.core.JsonFactory;
    provides com.multiclouddb.provider.cosmos.shaded.com.fasterxml.jackson.core.ObjectCodec
        with com.multiclouddb.provider.cosmos.shaded.com.fasterxml.jackson.databind.ObjectMapper;
}

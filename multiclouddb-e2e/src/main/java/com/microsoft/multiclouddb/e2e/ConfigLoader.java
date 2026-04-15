// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.microsoft.multiclouddb.e2e;

import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.ProviderId;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Loads provider configuration from a properties file.
 *
 * <p>Priority order (highest first):
 * <ol>
 *   <li>System property {@code multiclouddb.config} pointing to a file path or
 *       classpath resource</li>
 *   <li>The {@code defaultConfigFile} argument passed to {@link #load(String)}</li>
 * </ol>
 *
 * <p>To switch providers, run with a different config file — no code changes needed:
 * <pre>
 *   mvn -pl multiclouddb-e2e exec:java -Dmulticlouddb.config=cosmos.properties
 *   mvn -pl multiclouddb-e2e exec:java -Dmulticlouddb.config=dynamo.properties
 *   mvn -pl multiclouddb-e2e exec:java -Dmulticlouddb.config=spanner.properties
 * </pre>
 */
public final class ConfigLoader {

    private ConfigLoader() {}

    /**
     * Holds the SDK client configuration and any application-level properties
     * (e.g., {@code multiclouddb.database}, {@code multiclouddb.collection}).
     */
    public record AppConfig(MulticloudDbClientConfig sdk, Properties raw) {
        /** Returns the property value, or {@code defaultValue} if absent. */
        public String get(String key, String defaultValue) {
            return raw.getProperty(key, defaultValue);
        }
    }

    /** Load config using the given default file name (classpath resource). */
    public static AppConfig load(String defaultConfigFile) throws IOException {
        String configFile = System.getProperty("multiclouddb.config",
                defaultConfigFile != null ? defaultConfigFile : "cosmos.properties");

        Properties props = loadProperties(configFile);

        // System properties prefixed with multiclouddb. override file values.
        for (String key : System.getProperties().stringPropertyNames()) {
            if (key.startsWith("multiclouddb.")) {
                props.setProperty(key, System.getProperty(key));
            }
        }

        return new AppConfig(buildSdkConfig(props), props);
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private static MulticloudDbClientConfig buildSdkConfig(Properties props) {
        String providerName = props.getProperty("multiclouddb.provider", "cosmos");
        MulticloudDbClientConfig.Builder builder = MulticloudDbClientConfig.builder()
                .provider(ProviderId.fromId(providerName));

        for (String key : props.stringPropertyNames()) {
            if (key.startsWith("multiclouddb.connection.")) {
                builder.connection(
                        key.substring("multiclouddb.connection.".length()),
                        props.getProperty(key));
            }
            if (key.startsWith("multiclouddb.auth.")) {
                builder.auth(
                        key.substring("multiclouddb.auth.".length()),
                        props.getProperty(key));
            }
        }

        return builder.build();
    }

    /**
     * Loads a properties file. Tries absolute/relative file path first,
     * then falls back to classpath resource. Throws if the file cannot be found
     * or read and no system-property overrides are present.
     */
    private static Properties loadProperties(String name) throws IOException {
        Properties props = new Properties();
        Path filePath = Path.of(name);
        if (Files.exists(filePath)) {
            try (InputStream is = Files.newInputStream(filePath)) {
                props.load(is);
                System.out.println("[config] Loaded from file: " + filePath.toAbsolutePath());
            }
        } else {
            InputStream is = ConfigLoader.class.getClassLoader().getResourceAsStream(name);
            if (is == null) {
                throw new IllegalArgumentException(
                        "[config] Config file not found: '" + name + "'. " +
                        "Copy the appropriate *.properties.template to *.properties and fill in your credentials.");
            }
            try (is) {
                props.load(is);
                System.out.println("[config] Loaded from classpath: " + name);
            }
        }
        return props;
    }
}

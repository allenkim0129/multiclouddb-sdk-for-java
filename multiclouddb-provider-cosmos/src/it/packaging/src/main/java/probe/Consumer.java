// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package probe;

import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbClientFactory;
import com.multiclouddb.api.ProviderId;
import java.util.Map;

public final class Consumer {
    private static final String PRIVATE = "com.multiclouddb.provider.cosmos.shaded.";
    private static int failures;

    public static void main(String[] args) {
        boolean collisions = java.util.Arrays.asList(args).contains("collisions");
        boolean publicOnly = java.util.Arrays.asList(args).contains("public");
        check("artifact origin and isolation", () -> {
            Class<?> type = Class.forName(PRIVATE + "com.azure.cosmos.CosmosClientBuilder");
            String origin = type.getProtectionDomain().getCodeSource().getLocation().toString();
            require(!origin.contains("/source/") && origin.endsWith("multiclouddb-provider-cosmos-0.1.0-beta.2-SNAPSHOT.jar"), origin);
            require(!origin.contains("/source/") && !origin.contains("/target/"), origin);
            System.out.println("PRIVATE_ORIGIN " + origin);
            System.out.println("PROVIDER_MODULE " + type.getModule());
            if (!collisions) {
                for (String name : new String[] {"com.azure.core.http.HttpClient", "com.fasterxml.jackson.databind.ObjectMapper",
                        "reactor.core.publisher.Mono", "io.netty.handler.ssl.OpenSsl"}) {
                    try { Class.forName(name); throw new AssertionError("Unexpected original dependency: " + name); }
                    catch (ClassNotFoundException expected) { }
                }
            }
        });
        check("public factory ServiceLoader and qualified SPI access", () -> {
            try {
                MulticloudDbClientFactory.create(MulticloudDbClientConfig.builder().provider(ProviderId.COSMOS).build());
                throw new AssertionError("Missing endpoint accepted");
            } catch (IllegalArgumentException expected) {
                require(expected.getMessage().contains("Cosmos connection.endpoint is required"), expected.toString());
            }
        });
        if (publicOnly) {
            if (collisions) check("original and private named-module types coexist", () -> {
                for (String name : new String[] {"com.azure.core.http.HttpClient", "com.fasterxml.jackson.databind.ObjectMapper",
                        "reactor.core.publisher.Mono", "io.netty.handler.ssl.OpenSsl"}) {
                    Class<?> original = Class.forName(name);
                    Class<?> relocated = Class.forName(PRIVATE + name);
                    require(original != relocated && original.getModule() != relocated.getModule(), name);
                    require(original.getModule().isNamed() && relocated.getModule().isNamed(), name);
                }
            });
            check("explicit provider module with no exports", () -> {
                Module provider = ModuleLayer.boot().findModule("com.multiclouddb.provider.cosmos").orElseThrow();
                require(!provider.getDescriptor().isAutomatic(), "Automatic module is not acceptable");
                require(provider.getDescriptor().exports().isEmpty(), "Provider has exports");
                Class<?> internal = Class.forName(PRIVATE + "com.fasterxml.jackson.databind.ObjectMapper");
                try {
                    internal.getConstructor().newInstance();
                    throw new AssertionError("Private implementation constructor is accessible");
                } catch (IllegalAccessException expected) { }
            });
            finish();
            return;
        }
        nativeChecks(PRIVATE);
        check("private and app native resource isolation", () -> {
            boolean windows = System.getProperty("os.name").startsWith("Windows");
            String original = windows ? "netty_tcnative_windows_x86_64.dll" : "libnetty_tcnative_linux_x86_64.so";
            String relocated = original.replace("netty_", "com_multiclouddb_provider_cosmos_shaded_netty_");
            var loader = Consumer.class.getClassLoader();
            var privateResource = loader.getResource("META-INF/native/" + relocated);
            require(privateResource != null && privateResource.toString().contains("multiclouddb-provider-cosmos-0.1.0-beta.2-SNAPSHOT.jar"), "Missing private binary");
            var appResource = loader.getResource("META-INF/native/" + original);
            require(collisions ? appResource != null && !appResource.toString().contains("multiclouddb-provider-cosmos")
                    : appResource == null, "Original native resource leaked from private artifact");
        });
        check("private platform TLS native library", () -> {
            Class<?> type = Class.forName(PRIVATE + "io.netty.handler.ssl.OpenSsl");
            boolean available = (boolean) type.getMethod("isAvailable").invoke(null);
            require(available, "OpenSSL unavailable: " + type.getMethod("unavailabilityCause").invoke(null));
            System.out.println("PRIVATE_TLS " + type.getMethod("versionString").invoke(null));
            require((boolean) type.getMethod("isAlpnSupported").invoke(null), "ALPN unavailable");
        });
        check("external JNA native runtime leaf", () -> {
            Class<?> type = Class.forName("com.sun.jna.Native");
            require(type.getField("POINTER_SIZE").getInt(null) == 8, "Unexpected pointer size");
            System.out.println("EXTERNAL_JNA " + type.getProtectionDomain().getCodeSource().getLocation());
            if (System.getProperty("os.name").startsWith("Windows")) {
                Class<?> kernel = Class.forName("com.sun.jna.platform.win32.Kernel32");
                Object library = kernel.getField("INSTANCE").get(null);
                require((int) kernel.getMethod("GetCurrentProcessId").invoke(library) > 0, "Invalid native pid");
            } else {
                Class<?> libraries = Class.forName("com.sun.jna.NativeLibrary");
                Object libc = libraries.getMethod("getInstance", String.class).invoke(null, "c");
                Object getpid = libraries.getMethod("getFunction", String.class).invoke(libc, "getpid");
                require((int) getpid.getClass().getMethod("invokeInt", Object[].class)
                        .invoke(getpid, (Object) new Object[0]) > 0, "Invalid native pid");
            }
        });
        check("private Netty version metadata", () -> {
            Class<?> version = Class.forName(PRIVATE + "io.netty.util.Version");
            Map<?, ?> versions = (Map<?, ?>) version.getMethod("identify").invoke(null);
            Object common = versions.get("netty-common");
            String actual = (String) version.getMethod("artifactVersion").invoke(common);
            require("4.1.137.Final".equals(actual), "Private metadata crossed into app: " + actual);
            System.out.println("PRIVATE_NETTY_VERSION " + actual);
        });
        if (collisions) {
            nativeChecks("");
            check("app platform native libraries control", () -> {
                Class<?> jna = Class.forName("com.sun.jna.Native");
                require(jna.getField("POINTER_SIZE").getInt(null) == 8, "Original JNA pointer size");
                Class<?> tls = Class.forName("io.netty.handler.ssl.OpenSsl");
                require((boolean) tls.getMethod("isAvailable").invoke(null),
                        "Original TLS unavailable: " + tls.getMethod("unavailabilityCause").invoke(null));
            });
            check("app Netty version metadata", () -> {
                Class<?> version = Class.forName("io.netty.util.Version");
                Map<?, ?> versions = (Map<?, ?>) version.getMethod("identify").invoke(null);
                String actual = (String) version.getMethod("artifactVersion").invoke(versions.get("netty-common"));
                require("4.1.135.Final".equals(actual), "App metadata crossed into private: " + actual);
                System.out.println("APP_NETTY_VERSION " + actual);
            });
            check("app/private class identities", () -> {
                for (String name : new String[] {"com.azure.core.http.HttpClient", "com.fasterxml.jackson.databind.ObjectMapper",
                        "reactor.core.publisher.Mono", "io.netty.handler.ssl.OpenSsl"}) {
                    Class<?> app = Class.forName(name);
                    Class<?> sdk = Class.forName(PRIVATE + name);
                    require(app != sdk && !app.isAssignableFrom(sdk), name);
                    System.out.println("APP_ORIGIN " + name + " " + app.getProtectionDomain().getCodeSource().getLocation());
                }
            });
        }
        finish();
    }

    private static void finish() {
        System.out.println("TOTAL_FAILURES " + failures);
        if (failures != 0) System.exit(1);
    }
    private static void nativeChecks(String prefix) {
        check(prefix.isEmpty() ? "app HTTP/identity construction" : "private HTTP/identity construction", () -> {
            Class<?> http = Class.forName(prefix + "com.azure.core.http.HttpClient");
            Object client = http.getMethod("createDefault").invoke(null);
            require(client != null && client.getClass().getName().startsWith(prefix + "com.azure"), "HTTP provider mismatch");
            Class<?> builderType = Class.forName(prefix + "com.azure.identity.DefaultAzureCredentialBuilder");
            Object builder = builderType.getConstructor().newInstance();
            builderType.getMethod("httpClient", http).invoke(builder, client);
            Object credential = builderType.getMethod("build").invoke(builder);
            require(credential != null, "credential missing");
            System.out.println("HTTP_PROVIDER " + client.getClass().getName());
            System.out.println("CREDENTIAL_BUILT_NO_TOKEN " + credential.getClass().getName());
        });
        check(prefix.isEmpty() ? "app JSON/Reactor" : "private JSON/Reactor", () -> {
            Class<?> mapperType = Class.forName(prefix + "com.fasterxml.jackson.databind.ObjectMapper");
            Object mapper = mapperType.getConstructor().newInstance();
            Map<?, ?> data = (Map<?, ?>) mapperType.getMethod("readValue", String.class, Class.class)
                    .invoke(mapper, "{\"n\":3,\"s\":\"value\"}", Map.class);
            require(data.get("n").equals(3) && data.get("s").equals("value"), "JSON mismatch");
            Class<?> mono = Class.forName(prefix + "reactor.core.publisher.Mono");
            Object value = mono.getMethod("just", Object.class).invoke(null, "value");
            require("value".equals(mono.getMethod("block").invoke(value)), "Reactor mismatch");
        });
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void check(String name, Checked action) {
        try {
            action.run();
            System.out.println("PASS " + name);
        } catch (Throwable failure) {
            failures++;
            System.err.println("FAIL " + name);
            failure.printStackTrace();
        }
    }

    private interface Checked { void run() throws Exception; }
}

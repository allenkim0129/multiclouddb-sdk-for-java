// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package probe;

import com.multiclouddb.api.MulticloudDbClient;
import com.multiclouddb.api.MulticloudDbClientConfig;
import com.multiclouddb.api.MulticloudDbClientFactory;
import com.multiclouddb.api.ProviderId;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.Permission;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings("removal")
public final class KeyModeProbe {
    public static void main(String[] args) throws Exception {
        System.setProperty("COSMOS.DISABLE_IMDS_ACCESS", "true");
        System.setProperty("COSMOS.GLOBAL_ENDPOINT_MANAGER_MAX_INIT_TIME_IN_SECONDS", "8");
        System.setProperty("COSMOS.HTTP_RESPONSE_TIMEOUT_IN_SECONDS", "8");
        System.setProperty("COSMOS.HTTP_MAX_REQUEST_TIMEOUT", "8");
        System.setProperty("javax.net.ssl.trustStore", args[0]);
        System.setProperty("javax.net.ssl.trustStorePassword", "probe-only");
        System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
        System.setSecurityManager(new SecurityManager() {
            @Override public void checkPermission(Permission permission) { }
            @Override public void checkConnect(String host, int port) {
                if (!host.equals("127.0.0.1") && !host.equals("localhost") && !host.equals("::1")
                        && !host.equals("0:0:0:0:0:0:0:1")) {
                    System.err.println("BLOCKED_NON_LOOPBACK " + host);
                    throw new SecurityException("Probe forbids non-loopback connection: " + host);
                }
            }
        });
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(25000); }
            catch (InterruptedException stopped) { return; }
            System.err.println("FAIL key-mode constructor exceeded 25-second limit");
            System.exit(2);
        });
        watchdog.setDaemon(true);
        watchdog.start();
        if (args.length > 1) {
            if (!args[1].startsWith("https://127.0.0.1:")) throw new IllegalArgumentException("Only local fixture allowed");
            try {
                createAndClose(args[1]);
            } finally { watchdog.interrupt(); }
            return;
        }
        AtomicInteger requests = new AtomicInteger();
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(Path.of(args[0]))) { keys.load(input, "probe-only".toCharArray()); }
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, "probe-only".toCharArray());
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(managers.getKeyManagers(), null, null);
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl));
        String endpoint = "https://127.0.0.1:" + server.getAddress().getPort() + "/";
        String account = "{\"id\":\"packaging-probe\",\"_rid\":\"probe\","
                + "\"writableLocations\":[{\"name\":\"West US\",\"databaseAccountEndpoint\":\"" + endpoint + "\"}],"
                + "\"readableLocations\":[{\"name\":\"West US\",\"databaseAccountEndpoint\":\"" + endpoint + "\"}],"
                + "\"enableMultipleWriteLocations\":false,"
                + "\"userConsistencyPolicy\":{\"defaultConsistencyLevel\":\"Session\"},"
                + "\"userReplicationPolicy\":{\"maxReplicaSetSize\":4,\"minReplicaSetSize\":3},"
                + "\"systemReplicationPolicy\":{\"maxReplicaSetSize\":4,\"minReplicaSetSize\":3}}";
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            System.out.println("LOOPBACK_REQUEST " + exchange.getRequestMethod() + " " + exchange.getRequestURI());
            byte[] body = account.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("x-ms-activity-id", "00000000-0000-0000-0000-000000000001");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            createAndClose(endpoint);
            System.out.println("PASS public client close; loopback requests=" + requests.get());
            System.out.println("NOT_REAL_COSMOS_NO_AUTH_VALIDATION_NO_CRUD");
        } finally {
            server.stop(0);
            watchdog.interrupt();
        }
    }

    private static void createAndClose(String endpoint) throws Exception {
        MulticloudDbClientConfig config = MulticloudDbClientConfig.builder()
                .provider(ProviderId.COSMOS)
                .connection("endpoint", endpoint)
                .connection("key", Base64.getEncoder().encodeToString(new byte[64]))
                .build();
        try (MulticloudDbClient client = MulticloudDbClientFactory.create(config)) {
            if (client == null) throw new AssertionError("Client missing");
            System.out.println("PASS public key-mode constructor with synthetic local account");
        }
        System.out.println("PASS public client closed");
    }
}

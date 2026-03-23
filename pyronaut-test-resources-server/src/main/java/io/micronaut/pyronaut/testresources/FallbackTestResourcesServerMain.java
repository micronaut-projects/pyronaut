/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.pyronaut.testresources;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

final class FallbackTestResourcesServerMain {
    private FallbackTestResourcesServerMain() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> parsed = parseArgs(args);
        int requestedPort = Integer.parseInt(parsed.getOrDefault("port", "0"));
        Path portFile = Path.of(require(parsed, "port-file"));
        String accessToken = parsed.get("access-token");
        int idleTimeoutMinutes = Integer.parseInt(parsed.getOrDefault("idle-timeout-minutes", "60"));

        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), requestedPort), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        AtomicLong lastAccess = new AtomicLong(System.currentTimeMillis());
        List<InsightContainer> containers = Collections.synchronizedList(new ArrayList<>());
        List<InsightProperty> properties = Collections.synchronizedList(new ArrayList<>());
        List<InsightError> errors = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);

        server.createContext("/health", exchange -> {
            lastAccess.set(System.currentTimeMillis());
            writeResponse(exchange, 200, "UP");
        });
        server.createContext("/", exchange -> {
            lastAccess.set(System.currentTimeMillis());
            writeResponse(exchange, 200, "test-resources-server");
        });
        server.createContext("/api/test-resources/health", exchange -> {
            lastAccess.set(System.currentTimeMillis());
            if (!authorizeInsightsRequest(exchange, accessToken)) {
                return;
            }
            String body = "{\"health\":{\"status\":\"UP\",\"uri\":\"" +
                escapeJson("http://localhost:" + server.getAddress().getPort()) +
                "\",\"port\":" + server.getAddress().getPort() + "}}";
            writeJsonResponse(exchange, 200, body);
        });
        server.createContext("/api/test-resources/containers", exchange -> {
            lastAccess.set(System.currentTimeMillis());
            if (!authorizeInsightsRequest(exchange, accessToken)) {
                return;
            }
            writeJsonResponse(exchange, 200, "{\"containers\":" + containersToJson(containers) + "}");
        });
        server.createContext("/api/test-resources/properties", exchange -> {
            lastAccess.set(System.currentTimeMillis());
            if (!authorizeInsightsRequest(exchange, accessToken)) {
                return;
            }
            writeJsonResponse(exchange, 200, "{\"properties\":" + propertiesToJson(properties) + "}");
        });
        server.createContext("/api/test-resources/errors", exchange -> {
            lastAccess.set(System.currentTimeMillis());
            if (!authorizeInsightsRequest(exchange, accessToken)) {
                return;
            }
            writeJsonResponse(exchange, 200, "{\"errors\":" + errorsToJson(errors) + "}");
        });
        server.createContext("/stop", exchange -> {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                writeResponse(exchange, 405, "method-not-allowed");
                return;
            }
            if (accessToken != null && !accessToken.isBlank()) {
                String requestToken = exchange.getRequestHeaders().getFirst("Access-Token");
                if (!Objects.equals(accessToken, requestToken)) {
                    writeResponse(exchange, 403, "forbidden");
                    return;
                }
            }
            writeResponse(exchange, 200, "stopping");
            new Thread(() -> {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                server.stop(0);
                latch.countDown();
            }, "fallback-test-resources-stop").start();
        });

        server.start();
        Files.createDirectories(portFile.getParent());
        Files.writeString(portFile, Integer.toString(server.getAddress().getPort()), StandardCharsets.UTF_8);

        Thread idleThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(Duration.ofMinutes(1).toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                long inactiveFor = System.currentTimeMillis() - lastAccess.get();
                if (inactiveFor > Duration.ofMinutes(idleTimeoutMinutes).toMillis()) {
                    server.stop(0);
                    latch.countDown();
                    return;
                }
            }
        }, "fallback-test-resources-idle");
        idleThread.setDaemon(true);
        idleThread.start();

        latch.await();
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> parsed = new HashMap<>();
        for (String arg : args) {
            if (arg == null || !arg.startsWith("--")) {
                continue;
            }
            int equals = arg.indexOf('=');
            if (equals < 0) {
                parsed.put(arg.substring(2), "true");
            } else {
                parsed.put(arg.substring(2, equals), arg.substring(equals + 1));
            }
        }
        return parsed;
    }

    private static String require(Map<String, String> args, String key) {
        String value = args.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required argument --" + key);
        }
        return value;
    }

    private static boolean authorizeInsightsRequest(HttpExchange exchange, String accessToken) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJsonResponse(exchange, 405, "{\"error\":\"method-not-allowed\"}");
            return false;
        }
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String token = extractBearerToken(header);
        if (token == null || token.isBlank()) {
            writeJsonResponse(exchange, 401, "{\"error\":\"missing-token\"}");
            return false;
        }
        if (accessToken == null || accessToken.isBlank() || !Objects.equals(accessToken, token)) {
            writeJsonResponse(exchange, 403, "{\"error\":\"invalid-token\"}");
            return false;
        }
        return true;
    }

    private static String extractBearerToken(String header) {
        if (header == null) {
            return null;
        }
        String prefix = "Bearer ";
        if (!header.startsWith(prefix)) {
            return null;
        }
        return header.substring(prefix.length()).trim();
    }

    private static String containersToJson(List<InsightContainer> containers) {
        StringBuilder builder = new StringBuilder("[");
        synchronized (containers) {
            for (int i = 0; i < containers.size(); i++) {
                InsightContainer container = containers.get(i);
                if (i > 0) {
                    builder.append(',');
                }
                builder.append("{\"id\":\"").append(escapeJson(container.id())).append("\"")
                    .append(",\"name\":\"").append(escapeJson(container.name())).append("\"")
                    .append(",\"image\":\"").append(escapeJson(container.image())).append("\"")
                    .append(",\"scope\":\"").append(escapeJson(container.scope())).append("\"")
                    .append(",\"status\":\"").append(escapeJson(container.status())).append("\"}");
            }
        }
        return builder.append(']').toString();
    }

    private static String propertiesToJson(List<InsightProperty> properties) {
        StringBuilder builder = new StringBuilder("[");
        synchronized (properties) {
            for (int i = 0; i < properties.size(); i++) {
                InsightProperty property = properties.get(i);
                if (i > 0) {
                    builder.append(',');
                }
                builder.append("{\"key\":\"").append(escapeJson(property.key())).append("\"")
                    .append(",\"value\":\"").append(escapeJson(property.value())).append("\"")
                    .append(",\"resolver\":\"").append(escapeJson(property.resolver())).append("\"")
                    .append(",\"scope\":\"").append(escapeJson(property.scope())).append("\"}");
            }
        }
        return builder.append(']').toString();
    }

    private static String errorsToJson(List<InsightError> errors) {
        StringBuilder builder = new StringBuilder("[");
        synchronized (errors) {
            for (int i = 0; i < errors.size(); i++) {
                InsightError error = errors.get(i);
                if (i > 0) {
                    builder.append(',');
                }
                builder.append("{\"property\":\"").append(escapeJson(error.property())).append("\"")
                    .append(",\"resolver\":\"").append(escapeJson(error.resolver())).append("\"")
                    .append(",\"message\":\"").append(escapeJson(error.message())).append("\"}");
            }
        }
        return builder.append(']').toString();
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void writeResponse(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void writeJsonResponse(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        writeResponse(exchange, status, body);
    }

    private record InsightContainer(String id, String name, String image, String scope, String status) {
    }

    private record InsightProperty(String key, String value, String resolver, String scope) {
    }

    private record InsightError(String property, String resolver, String message) {
    }
}

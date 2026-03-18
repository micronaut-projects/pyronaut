/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
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
import java.util.HashMap;
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
        CountDownLatch latch = new CountDownLatch(1);

        server.createContext("/health", exchange -> {
            lastAccess.set(System.currentTimeMillis());
            writeResponse(exchange, 200, "UP");
        });
        server.createContext("/", exchange -> {
            lastAccess.set(System.currentTimeMillis());
            writeResponse(exchange, 200, "test-resources-server");
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

    private static void writeResponse(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}

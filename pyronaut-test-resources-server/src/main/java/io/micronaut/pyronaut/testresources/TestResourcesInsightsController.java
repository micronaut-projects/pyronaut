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

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Controller("/api/test-resources")
final class TestResourcesInsightsController {
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Get(uri = "/health", produces = MediaType.APPLICATION_JSON)
    HttpResponse<Map<String, Object>> health(HttpRequest<?> request) {
        int authStatus = authorize(request);
        if (authStatus != 0) {
            return HttpResponse.status(HttpStatus.valueOf(authStatus));
        }
        int port = parsePort();
        String uri = port > 0 ? "http://localhost:" + port : "";
        return HttpResponse.ok(Map.of(
            "health", Map.of(
                "status", "UP",
                "uri", uri,
                "port", port
            )
        ));
    }

    @Get(uri = "/containers", produces = MediaType.APPLICATION_JSON)
    HttpResponse<Map<String, Object>> containers(HttpRequest<?> request) {
        int authStatus = authorize(request);
        if (authStatus != 0) {
            return HttpResponse.status(HttpStatus.valueOf(authStatus));
        }
        int port = parsePort();
        if (port <= 0) {
            return HttpResponse.ok(Map.of("containers", List.of()));
        }
        String body = fetchControlPanel(port, "/control-panel/docker");
        List<Map<String, String>> mapped = new ArrayList<>();
        for (Map<String, String> entry : parseObjectArray(body, "managedContainers")) {
            mapped.add(Map.of(
                "id", firstNonBlank(entry.get("id"), "unknown"),
                "name", firstNonBlank(entry.get("name"), "<unknown>"),
                "image", firstNonBlank(entry.get("imageName"), "unknown-image"),
                "scope", firstNonBlank(entry.get("scope"), "default"),
                "status", "running"
            ));
        }
        return HttpResponse.ok(Map.of("containers", mapped));
    }

    @Get(uri = "/properties", produces = MediaType.APPLICATION_JSON)
    HttpResponse<Map<String, Object>> properties(HttpRequest<?> request) {
        int authStatus = authorize(request);
        if (authStatus != 0) {
            return HttpResponse.status(HttpStatus.valueOf(authStatus));
        }
        int port = parsePort();
        if (port <= 0) {
            return HttpResponse.ok(Map.of("properties", List.of()));
        }
        String body = fetchControlPanel(port, "/control-panel");
        List<Map<String, String>> mapped = new ArrayList<>();
        for (Map<String, String> entry : parseObjectArray(body, "resolvedProperties")) {
            mapped.add(Map.of(
                "key", firstNonBlank(entry.get("property"), "<key>"),
                "value", firstNonBlank(entry.get("resolvedValue"), "<value>"),
                "resolver", firstNonBlank(entry.get("resolver"), "resolver"),
                "scope", firstNonBlank(entry.get("scope"), "default")
            ));
        }
        return HttpResponse.ok(Map.of("properties", mapped));
    }

    @Get(uri = "/errors", produces = MediaType.APPLICATION_JSON)
    HttpResponse<Map<String, Object>> errors(HttpRequest<?> request) {
        int authStatus = authorize(request);
        if (authStatus != 0) {
            return HttpResponse.status(HttpStatus.valueOf(authStatus));
        }
        int port = parsePort();
        if (port <= 0) {
            return HttpResponse.ok(Map.of("errors", List.of()));
        }
        String body = fetchControlPanel(port, "/control-panel");
        List<Map<String, String>> mapped = new ArrayList<>();
        for (Map<String, String> entry : parseObjectArray(body, "errors")) {
            mapped.add(Map.of(
                "property", firstNonBlank(entry.get("property"), "<property>"),
                "resolver", firstNonBlank(entry.get("resolver"), "resolver"),
                "message", firstNonBlank(entry.get("message"), "unknown error")
            ));
        }
        return HttpResponse.ok(Map.of("errors", mapped));
    }

    private int authorize(HttpRequest<?> request) {
        String expectedToken = normalized(System.getProperty("server.access-token"));
        if (expectedToken == null) {
            return 0;
        }
        String authorization = normalized(request.getHeaders().get("Authorization"));
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return 401;
        }
        String token = normalized(authorization.substring("Bearer ".length()));
        if (!Objects.equals(expectedToken, token)) {
            return 403;
        }
        return 0;
    }

    private int parsePort() {
        String value = normalized(System.getProperty("micronaut.server.port"));
        if (value == null) {
            return -1;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String fetchControlPanel(int port, String path) {
        try {
            var request = java.net.http.HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
            var response = httpClient.send(request, BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return "";
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "";
        }
    }

    private static List<Map<String, String>> parseObjectArray(String body, String key) {
        String array = extractArrayContent(body, key);
        if (array == null || array.isBlank()) {
            return List.of();
        }
        String trimmed = array.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return List.of();
        }
        String[] split = trimmed.split("\\},\\{");
        List<Map<String, String>> entries = new ArrayList<>(split.length);
        for (String raw : split) {
            String object = raw;
            if (!object.startsWith("{")) {
                object = "{" + object;
            }
            if (!object.endsWith("}")) {
                object = object + "}";
            }
            entries.add(parseFlatObject(object));
        }
        return entries;
    }

    private static Map<String, String> parseFlatObject(String body) {
        Map<String, String> values = new LinkedHashMap<>();
        int cursor = 0;
        while (cursor < body.length()) {
            int keyStart = body.indexOf('"', cursor);
            if (keyStart < 0) {
                break;
            }
            int keyEnd = body.indexOf('"', keyStart + 1);
            if (keyEnd < 0) {
                break;
            }
            String key = body.substring(keyStart + 1, keyEnd);
            int colon = body.indexOf(':', keyEnd);
            if (colon < 0) {
                break;
            }
            int valueQuote = body.indexOf('"', colon + 1);
            if (valueQuote < 0) {
                cursor = colon + 1;
                continue;
            }
            int valueEnd = body.indexOf('"', valueQuote + 1);
            if (valueEnd < 0) {
                break;
            }
            values.put(key, body.substring(valueQuote + 1, valueEnd));
            cursor = valueEnd + 1;
        }
        return values;
    }

    private static String extractArrayContent(String body, String key) {
        String marker = "\"" + key + "\"";
        int keyIndex = body.indexOf(marker);
        if (keyIndex < 0) {
            return null;
        }
        int colon = body.indexOf(':', keyIndex + marker.length());
        if (colon < 0) {
            return null;
        }
        int start = body.indexOf('[', colon + 1);
        if (start < 0) {
            return null;
        }
        int depth = 0;
        for (int i = start; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch == '[') {
                depth++;
            } else if (ch == ']') {
                depth--;
                if (depth == 0) {
                    return body.substring(start + 1, i);
                }
            }
        }
        return null;
    }

    private static String firstNonBlank(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private static String normalized(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed;
    }
}

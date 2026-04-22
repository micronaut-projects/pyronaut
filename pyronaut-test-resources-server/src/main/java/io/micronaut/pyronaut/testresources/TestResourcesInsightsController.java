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
import io.micronaut.testresources.controlpanel.ControlPanelPropertyResolutionListener;
import io.micronaut.testresources.controlpanel.DockerHealth;
import io.micronaut.testresources.controlpanel.DockerHealthControlPanel;
import io.micronaut.testresources.controlpanel.TestResourcesContainer;
import io.micronaut.testresources.core.ResolverLoader;
import io.micronaut.testresources.core.TestResourcesResolver;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Controller("/api/test-resources")
final class TestResourcesInsightsController {
    private static final String ACCESS_TOKEN_HEADER = "Access-Token";

    private final DockerHealthControlPanel dockerHealthControlPanel;
    private final ResolverLoader resolverLoader;
    private final ControlPanelPropertyResolutionListener resolutionListener;

    TestResourcesInsightsController(DockerHealthControlPanel dockerHealthControlPanel,
                                    ResolverLoader resolverLoader,
                                    ControlPanelPropertyResolutionListener resolutionListener) {
        this.dockerHealthControlPanel = dockerHealthControlPanel;
        this.resolverLoader = resolverLoader;
        this.resolutionListener = resolutionListener;
    }

    @Get(uri = "/health", produces = MediaType.APPLICATION_JSON)
    HttpResponse<Map<String, Object>> health(HttpRequest<?> request) {
        int authStatus = authorize(request);
        if (authStatus != 0) {
            return HttpResponse.status(HttpStatus.valueOf(authStatus));
        }
        var serverAddress = request.getServerAddress();
        int port = serverAddress == null ? -1 : serverAddress.getPort();
        String host = normalizeHost(serverAddress);
        String scheme = request.isSecure() ? "https" : "http";
        String uri = host != null && scheme != null && port > 0
            ? scheme + "://" + host + ":" + port
            : "";
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
        DockerHealth dockerHealth = dockerHealthControlPanel.getBody();
        List<Map<String, String>> mapped = new ArrayList<>();
        for (TestResourcesContainer container : dockerHealth.managedContainers()) {
            mapped.add(Map.of(
                "id", firstNonBlank(container.id(), "unknown"),
                "name", firstNonBlank(container.name(), "<unknown>"),
                "image", firstNonBlank(container.imageName(), "unknown-image"),
                "scope", firstNonBlank(container.scope(), "default"),
                "status", "running"
            ));
        }
        for (String container : dockerHealth.startingContainers()) {
            mapped.add(Map.of(
                "id", "unknown",
                "name", firstNonBlank(container, "<unknown>"),
                "image", firstNonBlank(container, "unknown-image"),
                "scope", "default",
                "status", "starting"
            ));
        }
        for (String container : dockerHealth.pullingContainers()) {
            mapped.add(Map.of(
                "id", "unknown",
                "name", firstNonBlank(container, "<unknown>"),
                "image", firstNonBlank(container, "unknown-image"),
                "scope", "default",
                "status", "pulling"
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
        List<Map<String, String>> mapped = new ArrayList<>();
        for (TestResourcesResolver resolver : resolverLoader.getResolvers()) {
            String resolverName = firstNonBlank(resolver.getDisplayName(), resolver.getId());
            for (var resolution : resolutionListener.findByResolver(resolver)) {
                mapped.add(Map.of(
                    "key", firstNonBlank(resolution.property(), "<key>"),
                    "value", firstNonBlank(resolution.resolvedValue(), "<value>"),
                    "resolver", resolverName,
                    "scope", inferScope(resolution.properties())
                ));
            }
        }
        return HttpResponse.ok(Map.of("properties", mapped));
    }

    @Get(uri = "/errors", produces = MediaType.APPLICATION_JSON)
    HttpResponse<Map<String, Object>> errors(HttpRequest<?> request) {
        int authStatus = authorize(request);
        if (authStatus != 0) {
            return HttpResponse.status(HttpStatus.valueOf(authStatus));
        }
        List<Map<String, String>> mapped = new ArrayList<>();
        for (TestResourcesResolver resolver : resolverLoader.getResolvers()) {
            String resolverName = firstNonBlank(resolver.getDisplayName(), resolver.getId());
            for (var error : resolutionListener.findErrorsById(resolver.getId())) {
                mapped.add(Map.of(
                    "property", firstNonBlank(error.property(), "<property>"),
                    "resolver", resolverName,
                    "message", firstNonBlank(firstStackTraceLine(error.stackTrace()), "unknown error")
                ));
            }
        }
        return HttpResponse.ok(Map.of("errors", mapped));
    }

    private int authorize(HttpRequest<?> request) {
        String expectedToken = normalized(System.getProperty("server.access-token"));
        if (expectedToken == null) {
            return 0;
        }
        String token = normalized(request.getHeaders().get(ACCESS_TOKEN_HEADER));
        if (token == null || !expectedToken.equals(token)) {
            return 401;
        }
        return 0;
    }

    private static String inferScope(Map<String, String> properties) {
        if (properties == null || properties.isEmpty()) {
            return "default";
        }
        if (properties.containsKey("scope")) {
            return firstNonBlank(properties.get("scope"), "default");
        }
        if (properties.containsKey("datasources")) {
            return firstNonBlank(properties.get("datasources"), "default");
        }
        return "default";
    }

    private static String firstStackTraceLine(String stackTrace) {
        if (stackTrace == null || stackTrace.isBlank()) {
            return null;
        }
        String normalized = stackTrace.replace('\r', '\n');
        int newline = normalized.indexOf('\n');
        if (newline >= 0) {
            normalized = normalized.substring(0, newline);
        }
        return normalized.trim();
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

    private static String normalizeHost(java.net.InetSocketAddress serverAddress) {
        if (serverAddress == null) {
            return null;
        }
        InetAddress address = serverAddress.getAddress();
        if (address != null && (address.isLoopbackAddress() || address.isAnyLocalAddress())) {
            return "localhost";
        }
        String host = normalized(serverAddress.getHostString());
        if (host == null) {
            return null;
        }
        return switch (host) {
            case "::1", "0:0:0:0:0:0:0:1", "127.0.0.1", "0.0.0.0" -> "localhost";
            default -> host;
        };
    }
}

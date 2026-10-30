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
package io.micronaut.pyronaut.install;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micronaut.pyronaut.config.model.PyprojectModel;
import io.micronaut.pyronaut.config.model.PyprojectModelException;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenHttpRetryTest {
    private static final String COORDINATE = "example:retry:1.0";
    private static final byte[] JAR = "retry-artifact".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    private String previousPyronautHome;
    private HttpServer server;
    private AtomicInteger jarRequests;
    private volatile boolean succeedAfterFirstAttempt;

    @BeforeEach
    void startRepository() throws IOException {
        previousPyronautHome = System.getProperty("pyronaut.home");
        Files.createDirectories(tempDir.resolve("home"));
        System.setProperty("pyronaut.home", tempDir.resolve("home").toString());
        jarRequests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::serve);
        server.start();
    }

    @AfterEach
    void stopRepository() {
        if (server != null) {
            server.stop(0);
        }
        if (previousPyronautHome == null) {
            System.clearProperty("pyronaut.home");
        } else {
            System.setProperty("pyronaut.home", previousPyronautHome);
        }
    }

    @Test
    void retryCountZeroDoesNotRetryA503() throws Exception {
        writeSettings(0);

        PyprojectModelException failure = assertThrows(
            PyprojectModelException.class,
            () -> resolver().resolveToolArtifacts(
                model(),
                List.of(new DefaultArtifact(COORDINATE)),
                tempDir.resolve("local"),
                false,
                false
            )
        );

        assertTrue(failure.getMessage().contains("retry"), failure.getMessage());
        assertEquals(1, jarRequests.get());
    }

    @Test
    void retryCountOneRetriesOnceAndReturnsTheSuccessfulArtifact() throws Exception {
        succeedAfterFirstAttempt = true;
        writeSettings(1);

        Path local = tempDir.resolve("fresh-local");
        List<Path> resolved = resolver().resolveToolArtifacts(
            model(),
            List.of(new DefaultArtifact(COORDINATE)),
            local,
            false,
            false
        );

        assertEquals(2, jarRequests.get());
        assertEquals(1, resolved.size());
        assertArrayEquals(JAR, Files.readAllBytes(resolved.getFirst()));
    }

    private MavenClasspathResolver resolver() {
        return new MavenClasspathResolver(new ProxyConfigurationLoader(
            Map.of(),
            tempDir.resolve("missing-proxy.toml"),
            tempDir.resolve("missing-maven-settings.xml")
        ));
    }

    private void writeSettings(int retryCount) throws IOException {
        String repository = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        Files.writeString(
            tempDir.resolve("home/settings.toml"),
            "[maven]\nrepositories = [\"" + repository + "\"]\nretry-count = " + retryCount + "\n",
            StandardCharsets.UTF_8
        );
    }

    private static PyprojectModel model() {
        return new PyprojectModel(null, null, new PyprojectModel.Pyronaut(
            null,
            null,
            List.of(),
            null,
            new PyprojectModel.Dependencies(List.of(COORDINATE), List.of(), List.of(), List.of()),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false
        ));
    }

    private void serve(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.endsWith(".jar")) {
            int attempt = jarRequests.incrementAndGet();
            if (!succeedAfterFirstAttempt || attempt == 1) {
                exchange.getResponseHeaders().set("Retry-After", "0");
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            respond(exchange, 200, JAR);
            return;
        }
        if (path.endsWith(".pom")) {
            respond(exchange, 200, """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId>
                  <artifactId>retry</artifactId>
                  <version>1.0</version>
                </project>
                """.getBytes(StandardCharsets.UTF_8));
            return;
        }
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try {
            exchange.getResponseBody().write(body);
        } finally {
            exchange.close();
        }
    }
}

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

import io.micronaut.core.annotation.Internal;
import io.micronaut.pyronaut.config.model.PyprojectModelReader;
import io.micronaut.pyronaut.config.testresources.TestResourcesLogMirror;
import io.micronaut.testresources.buildtools.ServerSettings;
import io.micronaut.testresources.buildtools.ServerUtils;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * An attached or owned Test Resources server session for a direct-source launch.
 */
@Internal
public final class DirectSourceTestResourcesSession implements AutoCloseable {
    private static final int DEFAULT_CLIENT_TIMEOUT_SECONDS = 60;
    private static final String SETTINGS_DIRECTORY = ".micronaut/test-resources";
    private static final String LOGS_DIRECTORY = "logs";
    private static final String RESTARTABLE_PROPERTY = "pyronaut.dev.direct.restartable";
    private static final String SERVER_CLASSPATH_MANIFEST =
        "__pyronaut__/resolved-test-resources-server-dependencies";

    private final PyronautTestResourcesServerMain.ServerManager serverManager;
    private final Path settingsDirectory;
    private final boolean owned;
    private final Map<String, String> clientProperties;
    private final Consumer<String> statusSink;
    private final TestResourcesLogMirror logMirror;
    private final Thread shutdownHook;
    private boolean closed;

    private DirectSourceTestResourcesSession(PyronautTestResourcesServerMain.ServerManager serverManager,
                                             Path settingsDirectory,
                                             boolean owned,
                                             Map<String, String> clientProperties,
                                             Consumer<String> statusSink,
                                             TestResourcesLogMirror logMirror) {
        this.serverManager = serverManager;
        this.settingsDirectory = settingsDirectory;
        this.owned = owned;
        this.clientProperties = Map.copyOf(clientProperties);
        this.statusSink = statusSink;
        this.logMirror = logMirror;
        this.shutdownHook = owned
            ? new Thread(this::close, "pyronaut-direct-test-resources-shutdown")
            : null;
        if (shutdownHook != null) {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        }
    }

    /**
     * Attaches to a healthy project-local server or starts an ephemeral owned server.
     *
     * @param projectRoot direct-source project root
     * @return the active session
     * @throws IOException when the server cannot be started or inspected
     */
    public static DirectSourceTestResourcesSession open(Path projectRoot) throws IOException {
        return open(
            projectRoot,
            new PyronautTestResourcesServerMain.DefaultServerManager(),
            // Resolved per line, not captured: a launcher's live progress
            // region replaces System.err after the session is opened, and a
            // line written to the old stream tears the region.
            line -> System.err.println(line)
        );
    }

    static DirectSourceTestResourcesSession open(Path projectRoot,
                                                 PyronautTestResourcesServerMain.ServerManager serverManager) throws IOException {
        return open(projectRoot, serverManager, line -> System.err.println(line));
    }

    static DirectSourceTestResourcesSession open(Path projectRoot,
                                                 PyronautTestResourcesServerMain.ServerManager serverManager,
                                                 Consumer<String> statusSink) throws IOException {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path settingsDirectory = root.resolve(SETTINGS_DIRECTORY).normalize();
        PyronautTestResourcesServerMain.ServerStatus existing = serverManager.status(settingsDirectory);
        if (existing.running()) {
            statusSink.accept("[test-resources] attach external server");
            ServerSettings settings = readSettings(settingsDirectory);
            return new DirectSourceTestResourcesSession(
                serverManager,
                settingsDirectory,
                false,
                clientProperties(existing.uri(), settings),
                statusSink,
                null
            );
        }

        String requestedToken = UUID.randomUUID().toString();
        Path logsDirectory = settingsDirectory.resolve(LOGS_DIRECTORY);
        // Watch before the server starts so a failure it logs on the way up is
        // reported rather than skipped.
        TestResourcesLogMirror logMirror =
            TestResourcesLogMirror.watch(logsDirectory, entry -> statusSink.accept(entry.message()));
        PyronautTestResourcesServerMain.ServerStartRequest request =
            new PyronautTestResourcesServerMain.ServerStartRequest(
                settingsDirectory,
                logsDirectory,
                settingsDirectory.resolve("server.port"),
                root.resolve(SERVER_CLASSPATH_MANIFEST),
                null,
                requestedToken,
                null,
                clientTimeout(root),
                null,
                Map.of(),
                Map.of(),
                false,
                null
            );
        statusSink.accept("[test-resources] start owned server");
        try {
            PyronautTestResourcesServerMain.ServerStatus started = serverManager.start(request);
            ServerSettings settings = readSettings(settingsDirectory);
            boolean owned = settings.getAccessToken().filter(requestedToken::equals).isPresent();
            statusSink.accept("[test-resources] server running on port " + started.port()
                + " (" + started.uri() + "); logs: " + logsDirectory);
            logMirror.start();
            DirectSourceTestResourcesSession session = new DirectSourceTestResourcesSession(
                serverManager,
                settingsDirectory,
                owned,
                clientProperties(started.uri(), settings),
                statusSink,
                logMirror
            );
            return session;
        } catch (IOException | RuntimeException e) {
            logMirror.close();
            cleanupFailedStart(serverManager, settingsDirectory, requestedToken);
            throw e;
        }
    }

    private static int clientTimeout(Path projectRoot) {
        Path pyproject = projectRoot.resolve("pyproject.toml");
        if (!Files.isRegularFile(pyproject)) {
            return DEFAULT_CLIENT_TIMEOUT_SECONDS;
        }
        Integer configured = new PyprojectModelReader()
            .readFile(pyproject)
            .pyronaut()
            .testResources()
            .clientTimeout();
        return configured == null ? DEFAULT_CLIENT_TIMEOUT_SECONDS : configured;
    }

    /**
     * Returns the temporary Test Resources client properties for this session.
     *
     * @return system properties required by the Test Resources client
     */
    public Map<String, String> clientProperties() {
        return clientProperties;
    }

    /**
     * Returns whether this session started and owns the server process.
     *
     * @return whether this session owns the server process
     */
    public boolean owned() {
        return owned;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (shutdownHook != null && Thread.currentThread() != shutdownHook) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM shutdown is already in progress.
            }
        }
        if (logMirror != null) {
            logMirror.close();
        }
        if (!owned) {
            return;
        }
        if (Boolean.getBoolean(RESTARTABLE_PROPERTY)) {
            statusSink.accept("[test-resources] keeping owned server for direct-source restart");
            return;
        }
        statusSink.accept("[test-resources] stop owned server");
        try {
            serverManager.stop(settingsDirectory);
        } catch (IOException e) {
            if (!isAlreadyStoppedFailure(e)) {
                System.err.println("Unable to stop direct-source Test Resources server: " + e.getMessage());
            }
        } finally {
            deleteServerSettings(settingsDirectory);
        }
    }

    private static ServerSettings readSettings(Path settingsDirectory) throws IOException {
        Optional<ServerSettings> settings = ServerUtils.readServerSettings(settingsDirectory);
        if (settings.isEmpty()) {
            throw new IOException("Test Resources server did not write settings in " + settingsDirectory);
        }
        return settings.get();
    }

    private static Map<String, String> clientProperties(String uri, ServerSettings settings) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("micronaut.test.resources.enabled", "true");
        properties.put("micronaut.test.resources.server.uri", uri);
        settings.getAccessToken().ifPresent(token ->
            properties.put("micronaut.test.resources.server.access.token", token)
        );
        properties.put(
            "micronaut.test.resources.server.client.read.timeout",
            Integer.toString(settings.getClientTimeout().orElse(DEFAULT_CLIENT_TIMEOUT_SECONDS))
        );
        return Map.copyOf(properties);
    }

    private static void cleanupFailedStart(PyronautTestResourcesServerMain.ServerManager serverManager,
                                           Path settingsDirectory,
                                           String requestedToken) {
        try {
            Optional<ServerSettings> settings = ServerUtils.readServerSettings(settingsDirectory);
            if (settings.flatMap(ServerSettings::getAccessToken).filter(requestedToken::equals).isPresent()) {
                serverManager.stop(settingsDirectory);
            }
        } catch (IOException ignored) {
        } finally {
            Optional<ServerSettings> settings = ServerUtils.readServerSettings(settingsDirectory);
            if (settings.isEmpty()
                || settings.flatMap(ServerSettings::getAccessToken).filter(requestedToken::equals).isPresent()) {
                deleteServerSettings(settingsDirectory);
            }
        }
    }

    private static void deleteServerSettings(Path settingsDirectory) {
        try {
            Files.deleteIfExists(settingsDirectory.resolve("server.port"));
            Files.deleteIfExists(settingsDirectory.resolve("test-resources.properties"));
            if (Files.isDirectory(settingsDirectory)) {
                try (var entries = Files.list(settingsDirectory)) {
                    if (entries.findAny().isEmpty()) {
                        Files.deleteIfExists(settingsDirectory);
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static boolean isAlreadyStoppedFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConnectException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("connection refused") || normalized.contains("connection reset")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}

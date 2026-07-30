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
import io.micronaut.testresources.buildtools.ServerSettings;
import io.micronaut.testresources.buildtools.ServerUtils;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    private static final String SERVER_LOG_FILE = "test-resources.log";
    private static final String STDIO_LOG_FILE = "launcher-stdio.log";
    private static final String SERVER_CLASSPATH_MANIFEST =
        "__pyronaut__/resolved-test-resources-server-dependencies";

    private final PyronautTestResourcesServerMain.ServerManager serverManager;
    private final Path settingsDirectory;
    private final boolean owned;
    private final Map<String, String> clientProperties;
    private final Consumer<String> statusSink;
    private final LogMirror logMirror;
    private final Thread shutdownHook;
    private boolean closed;

    private DirectSourceTestResourcesSession(PyronautTestResourcesServerMain.ServerManager serverManager,
                                             Path settingsDirectory,
                                             boolean owned,
                                             Map<String, String> clientProperties,
                                             Consumer<String> statusSink,
                                             LogMirror logMirror) {
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
            System.err::println
        );
    }

    static DirectSourceTestResourcesSession open(Path projectRoot,
                                                 PyronautTestResourcesServerMain.ServerManager serverManager) throws IOException {
        return open(projectRoot, serverManager, System.err::println);
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
        LogMirror logMirror = new LogMirror(logsDirectory, statusSink);
        PyronautTestResourcesServerMain.ServerStartRequest request =
            new PyronautTestResourcesServerMain.ServerStartRequest(
                settingsDirectory,
                logsDirectory,
                settingsDirectory.resolve("server.port"),
                root.resolve(SERVER_CLASSPATH_MANIFEST),
                null,
                requestedToken,
                null,
                DEFAULT_CLIENT_TIMEOUT_SECONDS,
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

    private static final class LogMirror implements AutoCloseable {
        private static final long POLL_INTERVAL_MILLIS = 100;
        private static final String IMAGE_PULL_MARKER = "Pulling docker image:";
        private static final String CONTAINER_CREATE_MARKER = "Creating container for image:";
        private static final String CONTAINER_STARTED_MARKER = " started in PT";

        private final List<Path> logFiles;
        private final Map<Path, Long> initialPositions;
        private final Consumer<String> statusSink;
        private final Thread thread;
        private volatile boolean closed;
        private Path activeLogFile;
        private long position;

        private LogMirror(Path logsDirectory, Consumer<String> statusSink) {
            this.logFiles = List.of(
                logsDirectory.resolve(SERVER_LOG_FILE),
                logsDirectory.resolve(STDIO_LOG_FILE)
            );
            this.initialPositions = new LinkedHashMap<>();
            for (Path logFile : logFiles) {
                initialPositions.put(logFile, fileSize(logFile));
            }
            this.statusSink = statusSink;
            this.thread = new Thread(this::run, "pyronaut-direct-test-resources-log-mirror");
            this.thread.setDaemon(true);
        }

        private void start() {
            thread.start();
        }

        private void run() {
            while (!closed) {
                mirrorAvailable();
                try {
                    Thread.sleep(POLL_INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        private synchronized void mirrorAvailable() {
            Path candidate = activeLogFile();
            if (candidate == null) {
                return;
            }
            if (!candidate.equals(activeLogFile)) {
                activeLogFile = candidate;
                position = initialPositions.getOrDefault(candidate, 0L);
            }
            long currentSize = fileSize(activeLogFile);
            if (currentSize < position) {
                position = 0;
            }
            if (currentSize <= position) {
                return;
            }
            try (RandomAccessFile log = new RandomAccessFile(activeLogFile.toFile(), "r")) {
                log.seek(position);
                String line;
                while ((line = log.readLine()) != null) {
                    String decoded = new String(
                        line.getBytes(StandardCharsets.ISO_8859_1),
                        StandardCharsets.UTF_8
                    ).strip();
                    if (!decoded.isEmpty() && shouldMirror(decoded)) {
                        statusSink.accept(decoded);
                    }
                }
                position = log.getFilePointer();
            } catch (IOException ignored) {
                // The server owns log rotation; retry on the next poll.
            }
        }

        private Path activeLogFile() {
            for (Path logFile : logFiles) {
                if (Files.exists(logFile)) {
                    return logFile;
                }
            }
            return null;
        }

        private static long fileSize(Path path) {
            try {
                return Files.exists(path) ? Files.size(path) : 0;
            } catch (IOException ignored) {
                return 0;
            }
        }

        private static boolean shouldMirror(String line) {
            return line.contains(" ERROR ")
                || line.contains(IMAGE_PULL_MARKER)
                || line.contains(CONTAINER_CREATE_MARKER)
                || line.contains(CONTAINER_STARTED_MARKER);
        }

        @Override
        public void close() {
            closed = true;
            thread.interrupt();
            if (thread.isAlive() && Thread.currentThread() != thread) {
                try {
                    thread.join(1_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            mirrorAvailable();
        }
    }
}

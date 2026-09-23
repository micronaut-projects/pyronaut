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
package io.micronaut.pyronaut.config.testresources;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Tails a Test Resources server's log files and reports the lines that explain
 * a wait: image pulls, container creation, container startup and errors.
 *
 * <p>The server runs in its own process and logs to a file, so the wait it
 * causes is invisible to whichever process is blocked on it. Only the process
 * that owns the terminal may write to it — a second writer tears a live
 * progress region and its lines are repainted away — so the mirror is started
 * by the launcher that is waiting and its lines are handed to that launcher's
 * reporter through the {@code sink}.
 *
 * <p>The CLI passes the resolved log directory in
 * {@value #LOGS_DIR_ENVIRONMENT}; {@link #start(Consumer)} does nothing when
 * that is absent, which is the case for a run without Test Resources.
 */
public final class TestResourcesLogMirror implements AutoCloseable {
    /**
     * Environment variable naming the directory holding the server's logs.
     */
    public static final String LOGS_DIR_ENVIRONMENT = "PYRONAUT_TEST_RESOURCES_LOGS_DIR";
    private static final String SERVER_LOG_FILE = "test-resources.log";
    private static final String STDIO_LOG_FILE = "launcher-stdio.log";
    private static final String IMAGE_PULL_MARKER = "Pulling docker image:";
    private static final String CONTAINER_CREATE_MARKER = "Creating container for image:";
    private static final String CONTAINER_STARTED_MARKER = " started in PT";
    private static final String ERROR_MARKER = " ERROR ";
    private static final long POLL_INTERVAL_MILLIS = 100;

    private final List<Path> logFiles;
    private final Map<Path, Long> initialPositions;
    private final Consumer<Entry> sink;
    private final Thread thread;
    private volatile boolean closed;
    private Path activeLogFile;
    private long position;

    private TestResourcesLogMirror(Path logsDirectory, Consumer<Entry> sink) {
        this.logFiles = logsDirectory == null
            ? List.of()
            : List.of(logsDirectory.resolve(SERVER_LOG_FILE), logsDirectory.resolve(STDIO_LOG_FILE));
        this.initialPositions = new LinkedHashMap<>();
        for (Path logFile : logFiles) {
            // Only report what happens from here on: earlier lines belong to a
            // previous command and have already been shown.
            initialPositions.put(logFile, fileSize(logFile));
        }
        this.sink = sink;
        this.thread = logFiles.isEmpty() ? null : new Thread(this::run, "pyronaut-test-resources-log-mirror");
        if (thread != null) {
            thread.setDaemon(true);
        }
    }

    /**
     * Start mirroring the log directory named in the environment, or nothing
     * when no directory is named.
     *
     * @param sink receives each mirrored line; it must be safe to call from
     *             another thread, and on a terminal it must write through the
     *             live region rather than straight to the stream
     * @return the mirror, to be closed when the wait is over
     */
    public static TestResourcesLogMirror start(Consumer<Entry> sink) {
        return start(configuredLogsDirectory(), sink);
    }

    /**
     * Start mirroring a log directory.
     *
     * @param logsDirectory the server's log directory, or {@code null} for no mirroring
     * @param sink receives each mirrored line
     * @return the mirror, to be closed when the wait is over
     */
    public static TestResourcesLogMirror start(Path logsDirectory, Consumer<Entry> sink) {
        return watch(logsDirectory, sink).start();
    }

    /**
     * Note where the log files currently end without reporting anything yet.
     * A caller that is about to start the server watches first, so that what
     * the server logs while starting is reported once {@link #start()} runs.
     *
     * @param logsDirectory the server's log directory, or {@code null} for no mirroring
     * @param sink receives each mirrored line
     * @return the mirror, not yet reporting
     */
    public static TestResourcesLogMirror watch(Path logsDirectory, Consumer<Entry> sink) {
        return new TestResourcesLogMirror(logsDirectory, sink);
    }

    /**
     * Begin reporting the lines written since this mirror started watching.
     *
     * @return this mirror, to be closed when the wait is over
     */
    public TestResourcesLogMirror start() {
        if (thread != null) {
            thread.start();
        }
        return this;
    }

    /**
     * @return the log directory named in the environment, or {@code null}
     */
    public static Path configuredLogsDirectory() {
        String configured = System.getenv(LOGS_DIR_ENVIRONMENT);
        if (configured == null || configured.isBlank()) {
            return null;
        }
        return Path.of(configured.trim()).toAbsolutePath().normalize();
    }

    /**
     * @return whether this mirror is watching anything
     */
    public boolean active() {
        return thread != null;
    }

    @Override
    public void close() {
        if (thread == null || closed) {
            return;
        }
        closed = true;
        thread.interrupt();
        if (thread.isAlive() && Thread.currentThread() != thread) {
            try {
                thread.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // A failure reported as the wait ended is the reason it ended.
        mirrorAvailable();
    }

    private void run() {
        while (!closed) {
            mirrorAvailable();
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
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
                String decoded = new String(line.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8).strip();
                Entry entry = entry(decoded);
                if (entry != null) {
                    sink.accept(entry);
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

    /**
     * Classify a server log line, reducing it to its message. The mirrored line
     * is shown next to a launcher's own progress lines, where the server's
     * timestamp, thread and logger name are noise.
     *
     * @param line the log line
     * @return the entry to report, or {@code null} when the line explains no wait
     */
    static Entry entry(String line) {
        if (line.isEmpty()) {
            return null;
        }
        boolean error = line.contains(ERROR_MARKER);
        if (!error && !explainsWait(line)) {
            return null;
        }
        return new Entry(message(line), error);
    }

    private static String message(String line) {
        int bracket = line.indexOf("] ");
        if (bracket < 0) {
            return line;
        }
        int separator = line.indexOf(" - ", bracket);
        if (separator < 0) {
            return line;
        }
        String message = line.substring(separator + 3).strip();
        return message.isEmpty() ? line : message;
    }

    private static boolean explainsWait(String line) {
        return line.contains(IMAGE_PULL_MARKER)
            || line.contains(CONTAINER_CREATE_MARKER)
            || line.contains(CONTAINER_STARTED_MARKER);
    }

    /**
     * A mirrored log line.
     *
     * @param message the line's message, without the server's logger prefix
     * @param error whether the server logged it as a failure
     */
    public record Entry(String message, boolean error) {
    }
}

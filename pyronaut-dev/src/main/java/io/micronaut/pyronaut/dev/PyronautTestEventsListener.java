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
package io.micronaut.pyronaut.dev;

import io.micronaut.dev.test.TestFailure;
import io.micronaut.dev.test.TestId;
import io.micronaut.dev.test.TestOutcome;
import io.micronaut.dev.test.TestOutput;
import io.micronaut.dev.test.TestReportListener;
import io.micronaut.dev.test.TestRunStarted;
import io.micronaut.dev.test.TestRunSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Writes the events of every run of {@code pyronaut test -t} on the JVM toolchain to the {@code events.ndjson} of
 * the test reports, as the test command's pytest engine writes them, so that the terminal UI and the editor
 * integrations follow the runs of test mode: each run truncates the file and starts with {@code session_started}
 * under a new {@code runId}, then {@code test_started}, {@code test_output} and {@code test_finished} for each test,
 * of every engine, and {@code session_finished}. A pytest test is identified by its node id, as pytest names it from
 * the project directory, {@code tests/test_app.py::test_hello}; another test by its class and method,
 * {@code com.example.AppTest::hello}.
 *
 * <p>Registered as a service of the development runtime; it writes nothing unless {@link PyronautTestReload} named
 * the file.</p>
 */
public final class PyronautTestEventsListener implements TestReportListener {

    private static final Logger LOG = LoggerFactory.getLogger(PyronautTestEventsListener.class);
    private static final String PYTEST_TEST_SEGMENT = "[test:";

    private final Path file;
    private final Path projectDir;
    private final String testsRoot;
    private final Supplier<String> runIds;
    private String runId = "";
    private long sequence;

    /**
     * Created by the service loader.
     */
    public PyronautTestEventsListener() {
        this(path(System.getProperty(PyronautTestReload.EVENTS_PROPERTY)),
            path(System.getProperty(PyronautTestReload.PROJECT_DIR_PROPERTY)),
            System.getProperty(PyronautTestReload.TESTS_ROOT_PROPERTY, ""),
            () -> UUID.randomUUID().toString());
    }

    PyronautTestEventsListener(Path file, Path projectDir, String testsRoot, Supplier<String> runIds) {
        this.file = file;
        this.projectDir = projectDir == null ? null : projectDir.toAbsolutePath().normalize();
        this.testsRoot = testsRoot.isEmpty() || testsRoot.endsWith("/") ? testsRoot : testsRoot + "/";
        this.runIds = runIds;
    }

    @Override
    public synchronized void runStarted(TestRunStarted event) {
        if (file == null) {
            return;
        }
        runId = runIds.get();
        sequence = 0;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            LOG.debug("Cannot start the events report {}: {}", file, e.getMessage());
        }
        write("session_started", null, null, Map.of());
    }

    @Override
    public synchronized void testStarted(TestId test) {
        write("test_started", testId(test), null, Map.of());
    }

    @Override
    public synchronized void output(TestId test, TestOutput stream, String text) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("stream", stream == TestOutput.STDERR ? "stderr" : "stdout");
        payload.put("text", text);
        write("test_output", testId(test), null, payload);
    }

    @Override
    public synchronized void testFinished(TestId test, TestOutcome outcome) {
        Map<String, String> payload = new LinkedHashMap<>();
        String status = switch (outcome.status()) {
            case PASSED -> "SUCCESSFUL";
            case FAILED, ERRORED -> "FAILED";
            case SKIPPED -> "ABORTED";
        };
        TestFailure failure = outcome.failure();
        if (failure != null) {
            payload.put("failure", failure.stackTrace().isBlank() ? describe(failure) : failure.stackTrace());
        } else if (outcome.skipReason() != null) {
            payload.put("reason", outcome.skipReason());
        }
        write("test_finished", testId(test), status, payload);
    }

    @Override
    public synchronized void runFinished(TestRunSummary summary) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("total", Integer.toString(summary.total()));
        payload.put("passed", Integer.toString(summary.passed()));
        payload.put("failed", Integer.toString(summary.failed() + summary.errored()));
        payload.put("skipped", Integer.toString(summary.skipped()));
        write("session_finished", null, summary.isSuccess() ? "SUCCESSFUL" : "FAILED", payload);
    }

    /**
     * The test's identifier in the events: a pytest test's node id, from the project directory, or the class and
     * method of another. A pytest test's results are grouped under its file, by its absolute path, as the pytest
     * engine gives it, or relative to the tests directory.
     */
    String testId(TestId test) {
        String uniqueId = test.uniqueId();
        String className = test.className();
        int segment = uniqueId.lastIndexOf(PYTEST_TEST_SEGMENT);
        if (className.endsWith(".py") && segment >= 0) {
            int end = uniqueId.indexOf(']', segment);
            String name = uniqueId.substring(segment + PYTEST_TEST_SEGMENT.length(), end < 0 ? uniqueId.length() : end);
            return pytestFile(className) + "::" + name;
        }
        return className + "::" + test.name();
    }

    private String pytestFile(String className) {
        Path file = Path.of(className);
        if (!file.isAbsolute()) {
            return testsRoot + className.replace('\\', '/');
        }
        Path normalized = file.normalize();
        if (projectDir != null && normalized.startsWith(projectDir)) {
            return projectDir.relativize(normalized).toString().replace('\\', '/');
        }
        return normalized.toString().replace('\\', '/');
    }

    private void write(String eventType, String testId, String status, Map<String, String> payload) {
        if (file == null) {
            return;
        }
        StringBuilder line = new StringBuilder(256).append('{');
        field(line, "runId", runId).append(',');
        line.append("\"seq\":").append(++sequence).append(',');
        field(line, "eventType", eventType).append(',');
        field(line, "testId", testId).append(',');
        field(line, "status", status).append(',');
        field(line, "timestamp", Instant.now().toString()).append(',');
        line.append("\"payload\":{");
        boolean first = true;
        for (Map.Entry<String, String> entry : payload.entrySet()) {
            if (!first) {
                line.append(',');
            }
            field(line, entry.getKey(), entry.getValue());
            first = false;
        }
        line.append("}}\n");
        try {
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.debug("Cannot append to the events report {}: {}", file, e.getMessage());
        }
    }

    private static StringBuilder field(StringBuilder builder, String name, String value) {
        builder.append('"').append(escape(name)).append("\":");
        if (value == null) {
            return builder.append("null");
        }
        return builder.append('"').append(escape(value)).append('"');
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static String describe(TestFailure failure) {
        return failure.message() == null ? failure.type() : failure.type() + ": " + failure.message();
    }

    private static Path path(String value) {
        return value == null || value.isBlank() ? null : Path.of(value);
    }
}

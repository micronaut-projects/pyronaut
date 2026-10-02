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
package io.micronaut.test.pytest.execution;

import io.micronaut.test.pytest.FailureDiagnostics;
import io.micronaut.test.pytest.PytestTestDescriptor;
import io.micronaut.test.pytest.PythonAssertionError;
import io.micronaut.test.pytest.extension.PytestMicronautExtension;
import io.micronaut.test.pytest.listener.PytestTestListener;
import org.graalvm.polyglot.Value;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.reporting.ReportEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;


/**
 * Adapter that implements PytestTestListener and forwards events to JUnit EngineExecutionListener.
 */
@SuppressWarnings("checkstyle:DesignForExtension")
public class JUnitPytestTestListener implements PytestTestListener {

    private static final Logger LOG = LoggerFactory.getLogger(JUnitPytestTestListener.class);
    private static final String FAILURE_OUTPUT_PROPERTY = "pyronaut.test.render-failure-output";
    private final EngineExecutionListener junitListener;
    private final Set<? extends TestDescriptor> children;
    private final List<TestDescriptor> allDescriptors;
    private final Path htmlReportPath;
    private final Path lastNodeIdPath;
    private final Path eventsReportPath;
    private final String runId;
    private final AtomicLong eventSequence = new AtomicLong();
    private final List<TestOutcome> outcomes = new ArrayList<>();
    private final Set<String> writtenNodeIds = new HashSet<>();
    private final Map<String, TestStreamOutput> outputByTest = new LinkedHashMap<>();
    private final Map<String, TestExecutionResult> lifecycleFailures = new LinkedHashMap<>();
    private final Map<String, Long> testStartNanos = new HashMap<>();
    private final Instant sessionStartedAt = Instant.now();
    private final long sessionStartNanos = System.nanoTime();
    private TestExecutionResult sessionResult = TestExecutionResult.successful();
    private boolean failedTestReported;
    private boolean nonTestFailureReported;

    public JUnitPytestTestListener(
        EngineExecutionListener junitListener,
        Set<? extends TestDescriptor> testDescriptors) {
        this(junitListener, testDescriptors, null, null, null);
    }

    public JUnitPytestTestListener(
        EngineExecutionListener junitListener,
        Set<? extends TestDescriptor> testDescriptors,
        String htmlReportPath,
        String lastNodeIdPath
    ) {
        this(junitListener, testDescriptors, htmlReportPath, lastNodeIdPath, null);
    }

    public JUnitPytestTestListener(
        EngineExecutionListener junitListener,
        Set<? extends TestDescriptor> testDescriptors,
        String htmlReportPath,
        String lastNodeIdPath,
        String eventsReportPath
    ) {
        this.junitListener = junitListener;
        this.children = testDescriptors;
        this.allDescriptors = new ArrayList<>();
        this.htmlReportPath = toPath(htmlReportPath);
        this.lastNodeIdPath = toPath(lastNodeIdPath);
        this.eventsReportPath = toPath(eventsReportPath);
        this.runId = UUID.randomUUID().toString();
        initializeNodeIdReport();
        initializeEventsReport();
        for (TestDescriptor td : testDescriptors) {
            flatten(td);
        }
    }

    private static Path toPath(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Paths.get(value);
    }

    private void flatten(TestDescriptor d) {
        allDescriptors.add(d);
        for (TestDescriptor c : d.getChildren()) {
            flatten(c);
        }
    }

    @Override
    public void beforeFile(String file) {
        LOG.debug("Pytest starting file: {}", file);
        // File-level events are not used in the new architecture
    }

    @Override
    public void afterFile(String file, TestExecutionResult result) {
        LOG.debug("Pytest finished file: {} ({})", file, result);
        if (result.getStatus() != TestExecutionResult.Status.SUCCESSFUL) {
            nonTestFailureReported = true;
            recordSessionResult(result);
            outcomes.add(new TestOutcome(file, result, -1));
            var payload = new LinkedHashMap<String, String>();
            result.getThrowable().ifPresent(throwable -> payload.put("failure", FailureDiagnostics.render(throwable)));
            writeEvent("file_finished", file, result.getStatus().name(), payload);
        }
    }

    @Override
    public void beforeTest(String testId, Value item) {
        LOG.debug("Pytest starting test: {}", testId);
        writeNodeId(testId);
        writeEvent("test_started", testId, null, Map.of());
        testStartNanos.put(testId, System.nanoTime());

        PytestTestDescriptor testDescriptor = findDescriptor(testId);
        if (testDescriptor != null) {
            junitListener.executionStarted(testDescriptor);
            runBeforeEach(testId, item);
        }
    }

    @Override
    public void afterTest(String testId, Value item, TestExecutionResult result) {
        LOG.debug("Pytest finished test: {} with result: {}", testId, result);
        TestExecutionResult finalResult = mergeLifecycleFailure(testId, result);
        PytestTestDescriptor testDescriptor = findDescriptor(testId);
        if (testDescriptor != null) {
            runAfterEach(testId, item);
        }
        finalResult = mergeLifecycleFailure(testId, finalResult);
        writeNodeId(testId);
        Long startNanos = testStartNanos.remove(testId);
        outcomes.add(new TestOutcome(testId, finalResult, startNanos == null ? -1 : System.nanoTime() - startNanos));
        var payload = new LinkedHashMap<String, String>();
        if (finalResult.getStatus() == TestExecutionResult.Status.FAILED) {
            if (testDescriptor != null) {
                // Only treat the failure as already reported to JUnit when a descriptor matched;
                // otherwise the non-zero pytest exit code must surface as an execution failure.
                failedTestReported = true;
            } else {
                LOG.warn("Pytest reported a failure for {} but no discovered test descriptor matched it", testId);
            }
            finalResult.getThrowable().ifPresent(throwable -> payload.put("failure", FailureDiagnostics.render(throwable)));
        } else if (finalResult.getStatus() == TestExecutionResult.Status.ABORTED) {
            finalResult.getThrowable().ifPresent(throwable -> payload.put("reason", FailureDiagnostics.render(throwable)));
        }
        writeEvent("test_finished", testId, finalResult.getStatus().name(), payload);
        if (finalResult.getStatus() == TestExecutionResult.Status.FAILED && renderFailureOutputEnabled()) {
            emitFailureDiagnostics(testId, finalResult);
        }
        if (testDescriptor != null) {
            junitListener.executionFinished(testDescriptor, finalResult);
        }
        lifecycleFailures.remove(testId);
    }

    private PytestTestDescriptor findDescriptor(String testId) {
        for (TestDescriptor child : allDescriptors) {
            if (child instanceof PytestTestDescriptor ptd && ptd.matchesId(testId)) {
                return ptd;
            }
        }
        return null;
    }

    @Override
    public void onResult(TestExecutionResult result) {
        LOG.debug("Pytest session completed");
        recordSessionResult(result);
        long passed = outcomes.stream().filter(outcome -> outcome.result().getStatus() == TestExecutionResult.Status.SUCCESSFUL).count();
        long failed = outcomes.stream().filter(outcome -> outcome.result().getStatus() == TestExecutionResult.Status.FAILED).count();
        long skipped = outcomes.stream().filter(outcome -> outcome.result().getStatus() == TestExecutionResult.Status.ABORTED).count();
        var payload = new LinkedHashMap<String, String>();
        payload.put("total", Long.toString(outcomes.size()));
        payload.put("passed", Long.toString(passed));
        payload.put("failed", Long.toString(failed));
        payload.put("skipped", Long.toString(skipped));
        writeEvent("session_finished", null, result.getStatus().name(), payload);
        writeHtmlReport();
    }

    TestExecutionResult sessionResult() {
        return sessionResult;
    }

    boolean hasReportedTestFailures() {
        return failedTestReported;
    }

    boolean hasNonTestFailures() {
        return nonTestFailureReported;
    }

    private void recordSessionResult(TestExecutionResult result) {
        if (result == null || result.getStatus() == TestExecutionResult.Status.SUCCESSFUL) {
            return;
        }
        if (sessionResult.getStatus() == TestExecutionResult.Status.SUCCESSFUL) {
            sessionResult = result;
        }
    }

    @Override
    public String drainConsoleOutput(String testId, String stream) {
        return ConsoleCapture.drainActive(stream);
    }

    @Override
    public void stashSessionConsoleOutput() {
        ConsoleCapture.stashSessionIfActive();
    }

    @Override
    public void onOutput(String testId, String stream, String text) {
        if (testId != null && text != null && !text.isBlank()) {
            outputByTest.computeIfAbsent(testId, ignored -> new TestStreamOutput())
                .append(stream, text);
            var payload = new LinkedHashMap<String, String>();
            payload.put("stream", stream == null ? "stdout" : stream);
            payload.put("text", text);
            writeEvent("test_output", testId, null, payload);
        }
        allDescriptors
            .stream()
            .filter(child -> child instanceof PytestTestDescriptor ptd && ptd.matchesId(testId))
            .findAny().ifPresent(td -> junitListener.reportingEntryPublished(td, ReportEntry.from(stream, text)));
    }

    private void runBeforeEach(String testId, Value item) {
        PytestMicronautExtension extension = getExtension(item);
        if (extension == null) {
            return;
        }
        try {
            extension.beforeEach(item, null, null, List.of());
        } catch (Throwable e) {
            recordLifecycleFailure(testId, "Micronaut beforeEach failed", e);
        }
    }

    private void runAfterEach(String testId, Value item) {
        PytestMicronautExtension extension = getExtension(item);
        if (extension == null) {
            return;
        }
        try {
            extension.afterEach(item);
        } catch (Throwable e) {
            recordLifecycleFailure(testId, "Micronaut afterEach failed", e);
        }
    }

    private PytestMicronautExtension getExtension(Value item) {
        if (item == null || item.isNull() || !item.hasMembers()) {
            return null;
        }
        Value extValue = item.getMember(PytestMicronautExtension.ID);
        if (extValue == null || extValue.isNull()) {
            return null;
        }
        return extValue.as(PytestMicronautExtension.class);
    }

    private void recordLifecycleFailure(String testId, String phase, Throwable e) {
        LOG.error("{} for {}: {}", phase, testId, e.getMessage(), e);
        TestExecutionResult result = TestExecutionResult.failed(new PythonAssertionError(compactMessage(phase, e), e));
        lifecycleFailures.put(testId, result);
        onOutput(testId, "log", compactMessage(phase, e));
    }

    private TestExecutionResult mergeLifecycleFailure(String testId, TestExecutionResult result) {
        TestExecutionResult lifecycleFailure = lifecycleFailures.get(testId);
        if (lifecycleFailure == null) {
            return result;
        }
        if (result == null || result.getStatus() == TestExecutionResult.Status.SUCCESSFUL) {
            return lifecycleFailure;
        }
        return result;
    }

    private static String compactMessage(String phase, Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getName();
        }
        Throwable cause = e.getCause();
        if (cause == null || cause == e) {
            return phase + ": " + message;
        }
        String causeMessage = cause.getMessage();
        if (causeMessage == null || causeMessage.isBlank()) {
            causeMessage = cause.getClass().getName();
        }
        if (message.equals(causeMessage)) {
            return phase + ": " + message;
        }
        return phase + ": " + message + System.lineSeparator() + causeMessage;
    }

    private void writeNodeId(String testId) {
        if (lastNodeIdPath == null) {
            return;
        }
        if (!writtenNodeIds.add(testId)) {
            return;
        }
        try {
            Path parent = lastNodeIdPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                lastNodeIdPath,
                testId + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
        } catch (Exception e) {
            LOG.debug("Unable to write last nodeid report: {}", lastNodeIdPath, e);
        }
    }

    private void emitFailureDiagnostics(String testId, TestExecutionResult result) {
        String rendered = renderFailureDiagnostics(testId, result);
        if (!rendered.isBlank()) {
            System.err.print(rendered);
            if (!rendered.endsWith("\n")) {
                System.err.println();
            }
        }
    }

    private static boolean renderFailureOutputEnabled() {
        return Boolean.getBoolean(FAILURE_OUTPUT_PROPERTY);
    }

    private String renderFailureDiagnostics(String testId, TestExecutionResult result) {
        TestStreamOutput details = outputByTest.getOrDefault(testId, new TestStreamOutput());
        String failure = result.getThrowable().map(FailureDiagnostics::render).orElse("");
        StringBuilder message = new StringBuilder(256);
        message.append("\n=== Pyronaut test failure: ").append(testId).append(" ===\n");
        appendConsoleSection(message, "Failure", failure);
        appendConsoleSection(message, "Framework Log", details.log());
        appendConsoleSection(message, "System Out", details.stdout());
        appendConsoleSection(message, "System Err", details.stderr());
        return message.toString();
    }

    private void initializeNodeIdReport() {
        if (lastNodeIdPath == null) {
            return;
        }
        try {
            Path parent = lastNodeIdPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(lastNodeIdPath, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            LOG.debug("Unable to initialize last nodeid report: {}", lastNodeIdPath, e);
        }
    }

    private void initializeEventsReport() {
        if (eventsReportPath == null) {
            return;
        }
        try {
            Path parent = eventsReportPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(eventsReportPath, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            writeEvent("session_started", null, null, Map.of());
        } catch (Exception e) {
            LOG.debug("Unable to initialize events report: {}", eventsReportPath, e);
        }
    }

    private synchronized void writeEvent(String eventType, String testId, String status, Map<String, String> payload) {
        if (eventsReportPath == null) {
            return;
        }
        try {
            var line = toJsonEvent(eventType, testId, status, payload) + "\n";
            Files.writeString(eventsReportPath, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            LOG.debug("Unable to append events report: {}", eventsReportPath, e);
        }
    }

    private String toJsonEvent(String eventType, String testId, String status, Map<String, String> payload) {
        var seq = eventSequence.incrementAndGet();
        var safePayload = payload == null ? Map.<String, String>of() : payload;
        var builder = new StringBuilder(256);
        builder.append('{');
        appendJsonField(builder, "runId", runId);
        builder.append(',');
        builder.append("\"seq\":").append(seq);
        builder.append(',');
        appendJsonField(builder, "eventType", eventType);
        builder.append(',');
        appendJsonNullableField(builder, "testId", testId);
        builder.append(',');
        appendJsonNullableField(builder, "status", status);
        builder.append(',');
        appendJsonField(builder, "timestamp", Instant.now().toString());
        builder.append(',');
        builder.append("\"payload\":{");
        boolean first = true;
        for (var entry : safePayload.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            appendJsonField(builder, entry.getKey(), entry.getValue());
            first = false;
        }
        builder.append("}}");
        return builder.toString();
    }

    private static void appendJsonField(StringBuilder builder, String name, String value) {
        builder.append('"').append(escapeJson(name)).append("\":\"")
            .append(escapeJson(value == null ? "" : value))
            .append('"');
    }

    private static void appendJsonNullableField(StringBuilder builder, String name, String value) {
        builder.append('"').append(escapeJson(name)).append("\":");
        if (value == null) {
            builder.append("null");
        } else {
            builder.append('"').append(escapeJson(value)).append('"');
        }
    }

    private static String escapeJson(String value) {
        var builder = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        builder.append("\\u");
                        String hex = Integer.toHexString(ch);
                        for (int j = hex.length(); j < 4; j++) {
                            builder.append('0');
                        }
                        builder.append(hex);
                    } else {
                        builder.append(ch);
                    }
                }
            }
        }
        return builder.toString();
    }

    private void writeHtmlReport() {
        if (htmlReportPath == null) {
            ensureNodeIdFileExists();
            return;
        }
        try {
            Path parent = htmlReportPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(htmlReportPath, renderHtmlReport(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            LOG.debug("Unable to write HTML report: {}", htmlReportPath, e);
        } finally {
            ensureNodeIdFileExists();
        }
    }

    private String renderHtmlReport() {
        List<HtmlTestReport.Entry> entries = new ArrayList<>(outcomes.size());
        for (TestOutcome outcome : outcomes) {
            TestStreamOutput details = outputByTest.getOrDefault(outcome.testId(), new TestStreamOutput());
            JUnitReportWriter.Status status = switch (outcome.result().getStatus()) {
                case SUCCESSFUL -> JUnitReportWriter.Status.PASSED;
                case ABORTED -> JUnitReportWriter.Status.SKIPPED;
                case FAILED -> JUnitReportWriter.Status.FAILED;
            };
            entries.add(new HtmlTestReport.Entry(
                outcome.testId(),
                status,
                false,
                outcome.durationNanos(),
                outcome.result().getThrowable().map(FailureDiagnostics::render).orElse(""),
                details.log(),
                details.stdout(),
                details.stderr()
            ));
        }
        return HtmlTestReport.render(entries, sessionStartedAt, System.nanoTime() - sessionStartNanos);
    }

    private static void appendConsoleSection(StringBuilder message, String title, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        message.append(title)
            .append(":\n")
            .append(content);
        if (!content.endsWith("\n")) {
            message.append('\n');
        }
    }

    private void ensureNodeIdFileExists() {
        if (lastNodeIdPath == null || Files.exists(lastNodeIdPath)) {
            return;
        }
        try {
            Path parent = lastNodeIdPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(lastNodeIdPath, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            LOG.debug("Unable to initialize last nodeid report: {}", lastNodeIdPath, e);
        }
    }

    private record TestOutcome(String testId, TestExecutionResult result, long durationNanos) {
    }

    private static final class TestStreamOutput {
        private final StringBuilder stdout = new StringBuilder();
        private final StringBuilder stderr = new StringBuilder();
        private final StringBuilder log = new StringBuilder();

        private void append(String stream, String text) {
            if (text == null || text.isEmpty()) {
                return;
            }
            if ("stderr".equals(stream)) {
                stderr.append(text);
            } else if ("log".equals(stream)) {
                log.append(text);
            } else {
                stdout.append(text);
            }
        }

        private String stdout() {
            return stdout.toString();
        }

        private String stderr() {
            return stderr.toString();
        }

        private String log() {
            return log.toString();
        }
    }
}

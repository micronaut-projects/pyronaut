/*
 * Copyright 2017-2024 original authors
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
    private static final String MICRONAUT_LOGO_URL =
        "https://micronaut.io/wp-content/uploads/2020/11/MIcronautLogo_Horizontal.svg";
    private static final String MICRONAUT_LOGO_MARKUP = """
        <div class="micronaut-brand" aria-label="Micronaut">
          <img
            class="micronaut-logo"
            src="%s"
            alt="Micronaut"
            loading="lazy"
            referrerpolicy="no-referrer"
            onerror="this.style.display='none';this.parentElement?.classList.add('micronaut-logo--failed');"
          >
          <div class="micronaut-logo-fallback" data-pyronaut-logo-fallback>
            Micronaut
            <span class="text-body-secondary">(logo unavailable — offline-safe fallback)</span>
            <span class="visually-hidden">Official logo source: https://micronaut.io</span>
          </div>
        </div>
        """.formatted(MICRONAUT_LOGO_URL);
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
            outcomes.add(new TestOutcome(file, result));
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
        outcomes.add(new TestOutcome(testId, finalResult));
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

            long total = outcomes.size();
            long passed = outcomes.stream().filter(outcome -> outcome.result().getStatus() == TestExecutionResult.Status.SUCCESSFUL).count();
            long failed = outcomes.stream().filter(outcome -> outcome.result().getStatus() == TestExecutionResult.Status.FAILED).count();
            long skipped = outcomes.stream().filter(outcome -> outcome.result().getStatus() == TestExecutionResult.Status.ABORTED).count();

            StringBuilder html = new StringBuilder(4096);
            html.append("<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\">\n");
            html.append("<title>Pyronaut Test Report</title>\n");
            html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
            html.append("<link rel=\"stylesheet\" href=\"https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css\" crossorigin=\"anonymous\">\n");
            html.append("<style>")
                .append("body{margin:24px;background:#f8f9fa;} ")
                .append(".report-shell{max-width:1100px;margin:0 auto;} ")
                .append(".micronaut-logo{width:320px;max-width:100%;height:auto;display:block;margin:0 auto 1rem auto;} ")
                .append(".micronaut-logo-fallback{display:none;text-align:center;font-weight:800;font-size:1.75rem;letter-spacing:.02em;color:#ff4500;margin:0 auto 1rem auto;} ")
                .append(".micronaut-logo--failed .micronaut-logo-fallback{display:block;} ")
                .append("details>summary{cursor:pointer;list-style:none;} ")
                .append("details>summary::-webkit-details-marker{display:none;} ")
                .append("pre{white-space:pre-wrap;word-break:break-word;} ")
                .append(".status-badge{font-size:.85rem;} ")
                .append("</style>\n");
            html.append("</head><body>\n");
            html.append("<main class=\"report-shell\">\n")
                .append("<div class=\"card shadow-sm\"><div class=\"card-body\">\n")
                .append(MICRONAUT_LOGO_MARKUP)
                .append("<h1 class=\"h3 text-center mb-3\">Pyronaut Test Report</h1>\n")
                .append("<div class=\"d-flex flex-wrap justify-content-center gap-2 mb-4\">\n")
                .append("<span class=\"badge text-bg-secondary\">Total: ").append(total).append("</span>")
                .append("<span class=\"badge text-bg-success\">Passed: ").append(passed).append("</span>")
                .append("<span class=\"badge text-bg-danger\">Failed: ").append(failed).append("</span>")
                .append("<span class=\"badge text-bg-warning\">Skipped: ").append(skipped).append("</span>")
                .append("</div>\n");

            for (TestOutcome outcome : outcomes) {
                String status = displayStatus(outcome.result().getStatus());
                TestStreamOutput details = outputByTest.getOrDefault(outcome.testId(), new TestStreamOutput());
                String failure = outcome.result().getThrowable().map(FailureDiagnostics::render).orElse("");
                String badgeClass = badgeClass(outcome.result().getStatus());

                html.append("<details class=\"card mb-2\">\n")
                    .append("<summary class=\"card-header d-flex justify-content-between align-items-center\">\n")
                    .append("<span class=\"fw-semibold text-break\">").append(escapeHtml(outcome.testId())).append("</span>")
                    .append("<span class=\"badge status-badge ").append(badgeClass).append("\">")
                    .append(status)
                    .append("</span></summary>\n")
                    .append("<div class=\"card-body\">\n");

                appendDetailsSection(html, "Failure", failure, "danger");
                appendDetailsSection(html, "Framework Log", details.log(), "warning");
                appendDetailsSection(html, "System Out", details.stdout(), "primary");
                appendDetailsSection(html, "System Err", details.stderr(), "secondary");

                if (failure.isBlank() && details.log().isBlank() && details.stdout().isBlank() && details.stderr().isBlank()) {
                    html.append("<p class=\"text-body-secondary mb-0\">No additional diagnostics captured for this test.</p>\n");
                }

                html.append("</div></details>\n");
            }

            html.append("</div></div></main>\n</body></html>\n");
            Files.writeString(htmlReportPath, html.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            LOG.debug("Unable to write HTML report: {}", htmlReportPath, e);
        } finally {
            ensureNodeIdFileExists();
        }
    }

    private static void appendDetailsSection(StringBuilder html, String title, String content, String color) {
        if (content == null || content.isBlank()) {
            return;
        }
        html.append("<section class=\"mb-3\">\n")
            .append("<h2 class=\"h6 text-").append(color).append("\">")
            .append(escapeHtml(title))
            .append("</h2>\n")
            .append("<pre class=\"bg-light border rounded p-2\"><code>")
            .append(escapeHtml(content))
            .append("</code></pre>\n")
            .append("</section>\n");
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

    private static String displayStatus(TestExecutionResult.Status status) {
        return switch (status) {
            case SUCCESSFUL -> "PASSED";
            case ABORTED -> "SKIPPED";
            case FAILED -> "FAILED";
        };
    }

    private static String badgeClass(TestExecutionResult.Status status) {
        return switch (status) {
            case SUCCESSFUL -> "text-bg-success";
            case ABORTED -> "text-bg-warning";
            case FAILED -> "text-bg-danger";
        };
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

    private static String escapeHtml(String text) {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    private record TestOutcome(String testId, TestExecutionResult result) {
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

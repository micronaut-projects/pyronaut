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

import io.micronaut.test.pytest.PytestTestDescriptor;
import io.micronaut.test.pytest.extension.PytestMicronautExtension;
import io.micronaut.test.pytest.listener.PytestTestListener;
import org.graalvm.polyglot.Value;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.reporting.ReportEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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


/**
 * Adapter that implements PytestTestListener and forwards events to JUnit EngineExecutionListener.
 */
public class JUnitPytestTestListener implements PytestTestListener {

    private static final Logger LOG = LoggerFactory.getLogger(JUnitPytestTestListener.class);
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
    private final List<TestOutcome> outcomes = new ArrayList<>();
    private final Set<String> writtenNodeIds = new HashSet<>();
    private final Map<String, TestStreamOutput> outputByTest = new LinkedHashMap<>();

    public JUnitPytestTestListener(
        EngineExecutionListener junitListener,
        Set<? extends TestDescriptor> testDescriptors) {
        this(junitListener, testDescriptors, null, null);
    }

    public JUnitPytestTestListener(
        EngineExecutionListener junitListener,
        Set<? extends TestDescriptor> testDescriptors,
        String htmlReportPath,
        String lastNodeIdPath
    ) {
        this.junitListener = junitListener;
        this.children = testDescriptors;
        this.allDescriptors = new ArrayList<>();
        this.htmlReportPath = toPath(htmlReportPath);
        this.lastNodeIdPath = toPath(lastNodeIdPath);
        initializeNodeIdReport();
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
        // File-level events are not used in the new architecture
    }

    @Override
    public void beforeTest(String testId, Value item) {
        LOG.debug("Pytest starting test: {}", testId);
        writeNodeId(testId);

        allDescriptors
            .stream()
            .filter(child -> child instanceof PytestTestDescriptor ptd && ptd.matchesId(testId))
            .findAny().ifPresent(testDescriptor -> {
                junitListener.executionStarted(testDescriptor);
                Value extValue = item.getMember(PytestMicronautExtension.ID);
                if (extValue != null) {
                    PytestMicronautExtension extension = extValue.as(PytestMicronautExtension.class);
                    extension.beforeEach(item, null, null, List.of());
                }
            });
    }

    @Override
    public void afterTest(String testId, Value item, TestExecutionResult result) {
        LOG.debug("Pytest finished test: {} with result: {}", testId, result);
        writeNodeId(testId);
        outcomes.add(new TestOutcome(testId, result));
        allDescriptors
            .stream()
            .filter(child -> child instanceof PytestTestDescriptor ptd && ptd.matchesId(testId))
            .findAny().ifPresent(td -> {
                junitListener.executionFinished(td, result);
                Value extValue = item.getMember(PytestMicronautExtension.ID);
                if (extValue != null) {
                    PytestMicronautExtension extension = extValue.as(PytestMicronautExtension.class);
                    try {
                        extension.afterEach(item);
                    } catch (Exception e) {
                        if (e instanceof RuntimeException re) {
                            throw re;
                        } else {
                            throw new RuntimeException(e);
                        }
                    }
                }
            });
    }

    @Override
    public void onResult(TestExecutionResult result) {
        LOG.debug("Pytest session completed");
        writeHtmlReport();
    }

    @Override
    public void onOutput(String testId, String stream, String text) {
        if (testId != null && text != null && !text.isBlank()) {
            outputByTest.computeIfAbsent(testId, ignored -> new TestStreamOutput())
                .append(stream, text);
        }
        allDescriptors
            .stream()
            .filter(child -> child instanceof PytestTestDescriptor ptd && ptd.matchesId(testId))
            .findAny().ifPresent(td -> junitListener.reportingEntryPublished(td, ReportEntry.from(stream, text)));
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
                .append("</div>\n");

            for (TestOutcome outcome : outcomes) {
                boolean isSuccess = outcome.result().getStatus() == TestExecutionResult.Status.SUCCESSFUL;
                String status = isSuccess ? "PASSED" : "FAILED";
                TestStreamOutput details = outputByTest.getOrDefault(outcome.testId(), new TestStreamOutput());
                String failure = outcome.result().getThrowable().map(Throwable::toString).orElse("");
                String badgeClass = isSuccess ? "text-bg-success" : "text-bg-danger";

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

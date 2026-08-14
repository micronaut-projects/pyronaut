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

import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Writes the common Pyronaut JUnit report artifacts. */
public final class JUnitReportWriter {
    private JUnitReportWriter() {
    }

    /**
     * Writes the XML and HTML summary reports.
     *
     * @param directory The report directory
     * @param summary The JUnit execution summary
     * @throws IOException If a report cannot be written
     */
    public static void write(Path directory, TestExecutionSummary summary) throws IOException {
        write(directory, summary, List.of());
    }

    public static void write(Path directory, TestExecutionSummary summary, List<TestResult> results) throws IOException {
        Files.createDirectories(directory);
        long tests = summary.getTestsFoundCount();
        long skipped = summary.getTestsSkippedCount();
        long failed = summary.getTestsFailedCount();
        long successful = summary.getTestsSucceededCount();
        List<TestExecutionSummary.Failure> failures = summary.getFailures();
        List<TestExecutionSummary.Failure> containerFailures = failures.stream()
            .filter(failure -> !failure.getTestIdentifier().isTest())
            .toList();
        long errors = containerFailures.size();
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<testsuite name=\"Pyronaut direct tests\" tests=\"" + tests + "\" skipped=\"" + skipped
            + "\" failures=\"" + failed + "\" errors=\"" + errors + "\"><properties/>";
        for (TestResult result : results) {
            String name = result.name();
            String details = result.details();
            if (result.status() == Status.PASSED) {
                xml += "<testcase name=\"" + escapeXml(name) + "\"><system-out>" + escapeXml(result.stdout())
                    + "</system-out><system-err>" + escapeXml(result.stderr()) + "</system-err></testcase>";
                continue;
            }
            if (result.status() == Status.SKIPPED) {
                xml += "<testcase name=\"" + escapeXml(name) + "\"><skipped message=\""
                    + escapeXml(details) + "\"/></testcase>";
                continue;
            }
            xml += "<testcase name=\"" + escapeXml(name) + "\"><failure message=\""
                + escapeXml(details) + "\">" + escapeXml(details) + "</failure><system-out>"
                + escapeXml(result.stdout()) + "</system-out><system-err>" + escapeXml(result.stderr()) + "</system-err></testcase>";
        }
        List<TestExecutionSummary.Failure> additionalFailures = results.isEmpty() ? failures : containerFailures;
        for (TestExecutionSummary.Failure failure : additionalFailures) {
            String name = failure.getTestIdentifier().getDisplayName();
            String details = failureDetails(failure.getException());
            String element = failure.getTestIdentifier().isTest() ? "failure" : "error";
            xml += "<testcase name=\"" + escapeXml(name) + "\"><" + element + " message=\""
                + escapeXml(details) + "\">" + escapeXml(details) + "</" + element + "></testcase>";
        }
        xml += "</testsuite>\n";
        Files.writeString(directory.resolve("junit.xml"), xml, StandardCharsets.UTF_8);
        StringBuilder html = new StringBuilder("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>Pyronaut Test Report</title><style>body{margin:24px;background:#f8f9fa;font-family:system-ui}.report-shell{max-width:1100px;margin:auto}.card{background:white;border:1px solid #ddd;border-radius:6px;padding:20px}.badge{display:inline-block;padding:6px 10px;margin:4px;border-radius:12px;background:#6c757d;color:white;cursor:pointer}.passed{background:#198754}.failed{background:#dc3545}details{border:1px solid #ddd;border-radius:6px;margin-top:10px;padding:10px}pre{white-space:pre-wrap;background:#f8f9fa;padding:10px}</style></head><body><main class=\"report-shell\"><div class=\"card\"><h1>Pyronaut Test Report</h1>")
            .append("<span class=\"badge\" onclick=\"filterReports('total')\">Total: ").append(tests).append("</span><span class=\"badge passed\" onclick=\"filterReports('passed')\">Passed: ").append(successful)
            .append("</span><span class=\"badge failed\" onclick=\"filterReports('failed')\">Failed: ").append(failed)
            .append("</span><span class=\"badge failed\" onclick=\"filterReports('failed')\">Errors: ").append(errors)
            .append("</span><span class=\"badge\" onclick=\"filterReports('skipped')\">Skipped: ").append(skipped).append("</span>");
        if (!results.isEmpty()) {
            for (TestResult result : results) {
                String status = result.status().name().toLowerCase(Locale.ROOT);
                String badge = result.status() == Status.PASSED ? "passed" : result.status() == Status.FAILED ? "failed" : "";
                html.append("<details data-status=\"").append(status).append("\"><summary><strong>")
                    .append(escapeHtml(result.name())).append("</strong> <span class=\"badge ").append(badge).append("\">")
                    .append(escapeHtml(result.status().name())).append("</span></summary>");
                appendSection(html, "Failure", result.details());
                appendSection(html, "System Out", result.stdout());
                appendSection(html, "System Err", result.stderr());
                html.append("</details>");
            }
        }
        for (TestExecutionSummary.Failure failure : additionalFailures) {
            String details = failureDetails(failure.getException());
            html.append("<details data-status=\"failed\"><summary><strong>").append(escapeHtml(failure.getTestIdentifier().getDisplayName()))
                .append("</strong> <span class=\"badge failed\">FAILED</span></summary><pre>")
                .append(escapeHtml(details)).append("</pre></details>");
        }
        html.append("</div></main><script>function filterReports(status){document.querySelectorAll('[data-status]').forEach(function(e){e.hidden=status!=='total'&&e.dataset.status!==status;});}</script></body></html>\n");
        Files.writeString(directory.resolve("index.html"), html.toString(), StandardCharsets.UTF_8);
    }

    private static String failureDetails(Throwable failure) {
        StringWriter details = new StringWriter();
        failure.printStackTrace(new PrintWriter(details));
        return details.toString();
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String escapeHtml(String value) {
        return escapeXml(value);
    }

    private static void appendSection(StringBuilder html, String title, String content) {
        if (content != null && !content.isBlank()) {
            html.append("<h3>").append(title).append("</h3><pre>").append(escapeHtml(content)).append("</pre>");
        }
    }

    /** Result status rendered in the report. */
    public enum Status { PASSED, FAILED, SKIPPED }

    /** One test result rendered in the report.
     *
     * @param name the test name
     * @param status the test status
     * @param details diagnostic details
     * @param stdout captured standard output
     * @param stderr captured standard error
     */
    public record TestResult(String name, Status status, String details, String stdout, String stderr) {
        public TestResult {
            Objects.requireNonNull(name);
            Objects.requireNonNull(status);
            details = details == null ? "" : details;
            stdout = stdout == null ? "" : stdout;
            stderr = stderr == null ? "" : stderr;
        }
    }
}

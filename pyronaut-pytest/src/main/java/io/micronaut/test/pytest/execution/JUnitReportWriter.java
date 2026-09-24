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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

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
        List<HtmlTestReport.Entry> entries = new ArrayList<>(results.size() + additionalFailures.size());
        for (TestResult result : results) {
            entries.add(new HtmlTestReport.Entry(result.name(), result.status(), false, result.durationNanos(),
                result.status() == Status.PASSED ? "" : result.details(), "", result.stdout(), result.stderr()));
        }
        for (TestExecutionSummary.Failure failure : additionalFailures) {
            entries.add(new HtmlTestReport.Entry(failure.getTestIdentifier().getDisplayName(), Status.FAILED,
                !failure.getTestIdentifier().isTest(), -1, failureDetails(failure.getException()), "", "", ""));
        }
        long startedAt = summary.getTimeStarted();
        long finishedAt = summary.getTimeFinished();
        String html = HtmlTestReport.render(
            entries,
            startedAt > 0 ? Instant.ofEpochMilli(startedAt) : Instant.now(),
            startedAt > 0 && finishedAt >= startedAt ? TimeUnit.MILLISECONDS.toNanos(finishedAt - startedAt) : 0
        );
        Files.writeString(directory.resolve("index.html"), html, StandardCharsets.UTF_8);
    }

    private static String failureDetails(Throwable failure) {
        StringWriter details = new StringWriter();
        failure.printStackTrace(new PrintWriter(details));
        return details.toString();
    }

    private static String escapeXml(String value) {
        // Control characters other than tab/newline are not legal XML 1.0 text.
        String legal = value.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");
        return legal.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;");
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
     * @param durationNanos the test duration in nanoseconds, or a negative value when unknown
     */
    public record TestResult(String name, Status status, String details, String stdout, String stderr, long durationNanos) {
        public TestResult(String name, Status status, String details, String stdout, String stderr) {
            this(name, status, details, stdout, stderr, -1);
        }

        public TestResult {
            Objects.requireNonNull(name);
            Objects.requireNonNull(status);
            details = details == null ? "" : details;
            stdout = stdout == null ? "" : stdout;
            stderr = stderr == null ? "" : stderr;
        }
    }
}

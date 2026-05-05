package io.micronaut.test.pytest.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.TestExecutionResult;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JUnitPytestTestListenerTest {
    private static final String FAILURE_OUTPUT_PROPERTY = "pyronaut.test.render-failure-output";

    @TempDir
    Path tempDir;

    @Test
    void writesLastNodeIdIntoConfiguredReportsDirectory() throws Exception {
        Path reportsDir = tempDir.resolve("__pyronaut__/reports/tests");
        Path lastNodeId = reportsDir.resolve(".pyronaut-last-nodeid.txt");

        JUnitPytestTestListener listener = new JUnitPytestTestListener(
            EngineExecutionListener.NOOP,
            Set.of(),
            reportsDir.resolve("index.html").toString(),
            lastNodeId.toString()
        );

        listener.afterTest("tests/test_health.py::test_ping", null, TestExecutionResult.successful());

        assertTrue(Files.exists(lastNodeId));
        String content = Files.readString(lastNodeId, StandardCharsets.UTF_8);
        assertTrue(content.contains("tests/test_health.py::test_ping"));
        assertFalse(Files.exists(tempDir.resolve(".pyronaut-last-nodeid.txt")));
    }

    @Test
    void truncatesNodeIdReportAtSessionStart() throws Exception {
        Path reportsDir = tempDir.resolve("__pyronaut__/reports/tests");
        Path lastNodeId = reportsDir.resolve(".pyronaut-last-nodeid.txt");
        Files.createDirectories(reportsDir);
        Files.writeString(lastNodeId, "stale::entry\n", StandardCharsets.UTF_8);

        JUnitPytestTestListener listener = new JUnitPytestTestListener(
            EngineExecutionListener.NOOP,
            Set.of(),
            reportsDir.resolve("index.html").toString(),
            lastNodeId.toString()
        );

        listener.afterTest("tests/test_health.py::test_fresh", null, TestExecutionResult.successful());

        String content = Files.readString(lastNodeId, StandardCharsets.UTF_8);
        assertFalse(content.contains("stale::entry"));
        assertTrue(content.contains("tests/test_health.py::test_fresh"));
    }

    @Test
    void writesHtmlReportAtConfiguredLocationOnSessionResult() throws Exception {
        Path reportsDir = tempDir.resolve("__pyronaut__/reports/tests");
        Path htmlReport = reportsDir.resolve("index.html");
        Path nodeIdReport = reportsDir.resolve(".pyronaut-last-nodeid.txt");

        JUnitPytestTestListener listener = new JUnitPytestTestListener(
            EngineExecutionListener.NOOP,
            Set.of(),
            htmlReport.toString(),
            nodeIdReport.toString()
        );

        listener.afterTest("tests/test_health.py::test_ping", null, TestExecutionResult.successful());
        listener.onResult(TestExecutionResult.successful());

        assertTrue(Files.exists(htmlReport));
        assertTrue(Files.exists(nodeIdReport));
        String html = Files.readString(htmlReport, StandardCharsets.UTF_8);
        assertTrue(html.contains("Pyronaut Test Report"));
        assertTrue(html.contains("cdn.jsdelivr.net/npm/bootstrap@5.3.3"));
        assertTrue(html.contains("micronaut-logo"));
        assertTrue(html.contains("MIcronautLogo_Horizontal.svg"));
        assertTrue(html.contains("data-pyronaut-logo-fallback"));
        assertTrue(html.contains("<details class=\"card mb-2\">"));
        assertTrue(html.contains("tests/test_health.py::test_ping"));
    }

    @Test
    void htmlReportIncludesExpandableFailureAndOutputDetailsWithEscaping() throws Exception {
        Path reportsDir = tempDir.resolve("__pyronaut__/reports/tests");
        Path htmlReport = reportsDir.resolve("index.html");

        JUnitPytestTestListener listener = new JUnitPytestTestListener(
            EngineExecutionListener.NOOP,
            Set.of(),
            htmlReport.toString(),
            reportsDir.resolve(".pyronaut-last-nodeid.txt").toString()
        );

        listener.onOutput("tests/test_html.py::test_fail", "stdout", "line<one>\n");
        listener.onOutput("tests/test_html.py::test_fail", "stderr", "err<&>\n");
        listener.onOutput("tests/test_html.py::test_fail", "log", "AssertionError: expected <x>\n");
        listener.afterTest(
            "tests/test_html.py::test_fail",
            null,
            TestExecutionResult.failed(new RuntimeException("boom <bad>"))
        );
        listener.onResult(TestExecutionResult.failed(new RuntimeException("session failed")));

        String html = Files.readString(htmlReport, StandardCharsets.UTF_8);
        assertTrue(html.contains("Failure"));
        assertTrue(html.contains("Framework Log"));
        assertTrue(html.contains("System Out"));
        assertTrue(html.contains("System Err"));
        assertTrue(html.contains("RuntimeException: boom &lt;bad&gt;"));
        assertTrue(html.contains("AssertionError: expected &lt;x&gt;"));
        assertTrue(html.contains("line&lt;one&gt;"));
        assertTrue(html.contains("err&lt;&amp;&gt;"));
    }

    @Test
    void initializesNodeIdReportEvenWithoutAnyCapturedOutput() throws Exception {
        Path reportsDir = tempDir.resolve("__pyronaut__/reports/tests");
        Path nodeIdReport = reportsDir.resolve(".pyronaut-last-nodeid.txt");

        JUnitPytestTestListener listener = new JUnitPytestTestListener(
            EngineExecutionListener.NOOP,
            Set.of(),
            null,
            nodeIdReport.toString()
        );

        listener.onResult(TestExecutionResult.successful());

        assertTrue(Files.exists(nodeIdReport));
        assertTrue(Files.readString(nodeIdReport, StandardCharsets.UTF_8).isEmpty());
    }

    @Test
    void writesIncrementalEventsNdjsonDuringSession() throws Exception {
        Path reportsDir = tempDir.resolve("__pyronaut__/reports/tests");
        Path eventsReport = reportsDir.resolve("events.ndjson");

        JUnitPytestTestListener listener = new JUnitPytestTestListener(
            EngineExecutionListener.NOOP,
            Set.of(),
            reportsDir.resolve("index.html").toString(),
            reportsDir.resolve(".pyronaut-last-nodeid.txt").toString(),
            eventsReport.toString()
        );

        listener.beforeTest("tests/test_stream.py::test_one", null);
        listener.onOutput("tests/test_stream.py::test_one", "stdout", "hello\n");
        listener.afterTest("tests/test_stream.py::test_one", null, TestExecutionResult.successful());
        listener.onResult(TestExecutionResult.successful());

        assertTrue(Files.exists(eventsReport));
        String content = Files.readString(eventsReport, StandardCharsets.UTF_8);
        assertTrue(content.contains("\"eventType\":\"session_started\""));
        assertTrue(content.contains("\"eventType\":\"test_started\""));
        assertTrue(content.contains("\"eventType\":\"test_output\""));
        assertTrue(content.contains("\"eventType\":\"test_finished\""));
        assertTrue(content.contains("\"eventType\":\"session_finished\""));
        assertTrue(content.contains("\"stream\":\"stdout\""));
        assertTrue(content.contains("\"text\":\"hello\\n\""));
        assertTrue(content.contains("\"testId\":\"tests/test_stream.py::test_one\""));
        assertFalse(content.contains("\"eventType\":\"unknown\""));
    }

    @Test
    void reportsAbortedTestsAsSkippedInEventsAndHtmlReport() throws Exception {
        Path reportsDir = tempDir.resolve("__pyronaut__/reports/tests");
        Path eventsReport = reportsDir.resolve("events.ndjson");
        Path htmlReport = reportsDir.resolve("index.html");

        JUnitPytestTestListener listener = new JUnitPytestTestListener(
            EngineExecutionListener.NOOP,
            Set.of(),
            htmlReport.toString(),
            reportsDir.resolve(".pyronaut-last-nodeid.txt").toString(),
            eventsReport.toString()
        );

        listener.beforeTest("tests/test_pending.py::test_pending_fix", null);
        listener.afterTest(
            "tests/test_pending.py::test_pending_fix",
            null,
            TestExecutionResult.aborted(new RuntimeException("Pending fix"))
        );
        listener.onResult(TestExecutionResult.successful());

        String events = Files.readString(eventsReport, StandardCharsets.UTF_8);
        assertTrue(events.contains("\"status\":\"ABORTED\""));
        assertTrue(events.contains("\"skipped\":\"1\""));
        assertFalse(events.contains("\"failure\":\"java.lang.RuntimeException: Pending fix\""));

        String html = Files.readString(htmlReport, StandardCharsets.UTF_8);
        assertTrue(html.contains("Skipped: 1"));
        assertTrue(html.contains(">SKIPPED<"));
        assertFalse(html.contains("Failed: 1"));
    }

    @Test
    void writesFailedTestDiagnosticsToStandardError() throws Exception {
        JUnitPytestTestListener listener = new JUnitPytestTestListener(
            EngineExecutionListener.NOOP,
            Set.of()
        );
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        String originalProperty = System.getProperty(FAILURE_OUTPUT_PROPERTY);
        try {
            System.setProperty(FAILURE_OUTPUT_PROPERTY, "true");
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            listener.onOutput("tests/test_console.py::test_fail", "stdout", "hello stdout\n");
            listener.onOutput("tests/test_console.py::test_fail", "stderr", "hello stderr\n");
            listener.onOutput("tests/test_console.py::test_fail", "log", "traceback details\n");
            listener.afterTest(
                "tests/test_console.py::test_fail",
                null,
                TestExecutionResult.failed(new RuntimeException("boom"))
            );
        } finally {
            System.setErr(originalErr);
            restoreFailureOutputProperty(originalProperty);
        }

        String output = err.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Pyronaut test failure: tests/test_console.py::test_fail"));
        assertTrue(output.contains("Failure:\njava.lang.RuntimeException: boom"));
        assertTrue(output.contains("Framework Log:\ntraceback details"));
        assertTrue(output.contains("System Out:\nhello stdout"));
        assertTrue(output.contains("System Err:\nhello stderr"));
    }

    @Test
    void doesNotWriteSuccessfulTestDiagnosticsToStandardError() throws Exception {
        JUnitPytestTestListener listener = new JUnitPytestTestListener(
            EngineExecutionListener.NOOP,
            Set.of()
        );
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            listener.onOutput("tests/test_console.py::test_ok", "stdout", "hello stdout\n");
            listener.afterTest(
                "tests/test_console.py::test_ok",
                null,
                TestExecutionResult.successful()
            );
        } finally {
            System.setErr(originalErr);
        }

        assertTrue(err.toString(StandardCharsets.UTF_8).isBlank());
    }

    @Test
    void doesNotWriteFailedTestDiagnosticsWhenPropertyIsDisabled() throws Exception {
        JUnitPytestTestListener listener = new JUnitPytestTestListener(
            EngineExecutionListener.NOOP,
            Set.of()
        );
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        String originalProperty = System.getProperty(FAILURE_OUTPUT_PROPERTY);
        try {
            System.clearProperty(FAILURE_OUTPUT_PROPERTY);
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            listener.onOutput("tests/test_console.py::test_fail", "log", "traceback details\n");
            listener.afterTest(
                "tests/test_console.py::test_fail",
                null,
                TestExecutionResult.failed(new RuntimeException("boom"))
            );
        } finally {
            System.setErr(originalErr);
            restoreFailureOutputProperty(originalProperty);
        }

        assertTrue(err.toString(StandardCharsets.UTF_8).isBlank());
    }

    private static void restoreFailureOutputProperty(String value) {
        if (value == null) {
            System.clearProperty(FAILURE_OUTPUT_PROPERTY);
        } else {
            System.setProperty(FAILURE_OUTPUT_PROPERTY, value);
        }
    }
}

package io.micronaut.test.pytest.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.TestExecutionResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JUnitPytestTestListenerTest {

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
}

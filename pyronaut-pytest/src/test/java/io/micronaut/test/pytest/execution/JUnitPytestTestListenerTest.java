package io.micronaut.test.pytest.execution;

import io.micronaut.test.pytest.PytestTestDescriptor;
import io.micronaut.test.pytest.PythonAssertionError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
        assertFalse(html.contains("cdn.jsdelivr.net"), "report must be self-contained");
        assertTrue(html.contains("pyronaut-logo"));
        assertTrue(html.contains("data:image/png;base64,"));
        assertTrue(html.contains("All tests passed"));
        assertTrue(html.contains("<details class=\"test passed\""));
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
    void failureReportsIncludeCauseChainDiagnostics() throws Exception {
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

        RuntimeException failure = new RuntimeException(
            "fixture setup failed",
            new IllegalStateException("root cause detail")
        );
        listener.afterTest("tests/test_fixture.py::test_context", null, TestExecutionResult.failed(failure));
        listener.onResult(TestExecutionResult.failed(failure));

        String events = Files.readString(eventsReport, StandardCharsets.UTF_8);
        assertTrue(events.contains("java.lang.RuntimeException: fixture setup failed"));
        assertTrue(events.contains("Caused by: java.lang.IllegalStateException: root cause detail"));

        String html = Files.readString(htmlReport, StandardCharsets.UTF_8);
        assertTrue(html.contains("java.lang.RuntimeException: fixture setup failed"));
        assertTrue(html.contains("Caused by: java.lang.IllegalStateException: root cause detail"));
    }

    @Test
    void fileCollectionFailuresAreRecordedAsSessionFailures() throws Exception {
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

        listener.onOutput("tests/test_bad.py", "log", "Collection failed: ImportError: bad import\n");
        listener.afterFile(
            "tests/test_bad.py",
            TestExecutionResult.failed(new PythonAssertionError("Collection failed: ImportError: bad import"))
        );
        listener.onResult(TestExecutionResult.failed(new RuntimeException("Pytest session failed with exit code: 2")));

        assertEquals(TestExecutionResult.Status.FAILED, listener.sessionResult().getStatus());
        assertTrue(listener.sessionResult().getThrowable().orElseThrow().getMessage().contains("bad import"));

        String events = Files.readString(eventsReport, StandardCharsets.UTF_8);
        assertTrue(events.contains("\"eventType\":\"file_finished\""));
        assertTrue(events.contains("\"testId\":\"tests/test_bad.py\""));
        assertTrue(events.contains("bad import"));

        String html = Files.readString(htmlReport, StandardCharsets.UTF_8);
        assertTrue(html.contains("tests/test_bad.py"));
        assertTrue(html.contains("Collection failed: ImportError: bad import"));
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
    void parametrizedNodeIdsMatchTheirDiscoveredDescriptor() {
        PytestTestDescriptor descriptor = testDescriptor("tests/test_params.py", "test_square");

        assertTrue(descriptor.matchesId("tests/test_params.py::test_square"));
        assertTrue(descriptor.matchesId("tests/test_params.py::test_square[2]"));
        assertTrue(descriptor.matchesId("tests/test_params.py::test_square[a-b]"));
        assertTrue(descriptor.matchesId("/workspace/tests/test_params.py::test_square[x[0]]"));
        assertFalse(descriptor.matchesId("tests/test_params.py::test_square_other[2]"));
    }

    @Test
    void parametrizedResultsAreForwardedToJUnit() {
        PytestTestDescriptor descriptor = testDescriptor("tests/test_params.py", "test_square");
        AtomicReference<TestDescriptor> started = new AtomicReference<>();
        AtomicReference<TestExecutionResult> finishedResult = new AtomicReference<>();
        JUnitPytestTestListener listener = new JUnitPytestTestListener(
            new EngineExecutionListener() {
                @Override
                public void executionStarted(TestDescriptor testDescriptor) {
                    started.set(testDescriptor);
                }

                @Override
                public void executionFinished(TestDescriptor testDescriptor, TestExecutionResult testExecutionResult) {
                    finishedResult.set(testExecutionResult);
                }
            },
            Set.of(descriptor)
        );

        listener.beforeTest("tests/test_params.py::test_square[2]", null);
        listener.afterTest(
            "tests/test_params.py::test_square[2]",
            null,
            TestExecutionResult.failed(new PythonAssertionError("assert 4 == 5"))
        );

        assertEquals(descriptor, started.get().getParent().orElseThrow());
        assertEquals("tests/test_params.py::test_square[2]", started.get().getDisplayName());
        assertEquals(descriptor.getSource(), started.get().getSource());
        assertTrue(started.get().isTest());
        assertEquals(TestExecutionResult.Status.FAILED, finishedResult.get().getStatus());
        assertTrue(listener.hasReportedTestFailures());
    }

    @Test
    void reportsJavaLifecycleExceptionAsFailedTestWithoutThrowingIntoPytest() throws Exception {
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

        recordLifecycleFailure(
            listener,
            "tests/test_java_lifecycle.py::test_context",
            "Micronaut beforeEach failed",
            new IllegalStateException("java failure escaped through polyglot")
        );
        listener.afterTest(
            "tests/test_java_lifecycle.py::test_context",
            null,
            TestExecutionResult.successful()
        );
        listener.onResult(TestExecutionResult.failed(new RuntimeException("session failed")));

        String events = Files.readString(eventsReport, StandardCharsets.UTF_8);
        assertTrue(events.contains("\"status\":\"FAILED\""));
        assertTrue(events.contains("java failure escaped through polyglot"));
        assertFalse(events.contains("ForeignException"));

        String html = Files.readString(htmlReport, StandardCharsets.UTF_8);
        assertTrue(html.contains("Failed: 1"));
        assertTrue(html.contains("Micronaut beforeEach failed"));
        assertTrue(html.contains("java failure escaped through polyglot"));
        assertFalse(html.contains("ForeignException"));
    }

    @Test
    void forwardsJavaLifecycleExceptionToJUnitAsTestFailure() throws Exception {
        String testId = "tests/test_java_lifecycle.py::test_context";
        PytestTestDescriptor descriptor = testDescriptor("tests/test_java_lifecycle.py", "test_context");
        AtomicReference<TestExecutionResult> finishedResult = new AtomicReference<>();
        JUnitPytestTestListener listener = new JUnitPytestTestListener(
            new EngineExecutionListener() {
                @Override
                public void executionFinished(TestDescriptor testDescriptor, TestExecutionResult testExecutionResult) {
                    finishedResult.set(testExecutionResult);
                }
            },
            Set.of(descriptor)
        );

        recordLifecycleFailure(
            listener,
            testId,
            "Micronaut beforeEach failed",
            new IllegalStateException("java failure escaped through polyglot")
        );
        listener.afterTest(testId, null, TestExecutionResult.successful());

        TestExecutionResult result = finishedResult.get();
        assertEquals(TestExecutionResult.Status.FAILED, result.getStatus());
        Throwable failure = result.getThrowable().orElseThrow();
        assertInstanceOf(PythonAssertionError.class, failure);
        assertTrue(failure.getMessage().contains("Micronaut beforeEach failed"));
        assertTrue(failure.getMessage().contains("java failure escaped through polyglot"));
        assertFalse(failure.toString().contains("ForeignException"));
    }

    private static PytestTestDescriptor testDescriptor(String source, String testName) {
        Path filePath = Path.of(source);
        return new PytestTestDescriptor(
            UniqueId.forEngine("pytest-engine")
                .append(PytestTestDescriptor.SEGMENT_SOURCE, source)
                .append(PytestTestDescriptor.SEGMENT_TEST, testName),
            source + "::" + testName,
            MethodSource.from(source, testName),
            filePath,
            1,
            1,
            0,
            0
        );
    }

    private static void recordLifecycleFailure(
        JUnitPytestTestListener listener,
        String testId,
        String phase,
        Throwable throwable
    ) throws Exception {
        Method method = JUnitPytestTestListener.class.getDeclaredMethod(
            "recordLifecycleFailure",
            String.class,
            String.class,
            Throwable.class
        );
        method.setAccessible(true);
        method.invoke(listener, testId, phase, throwable);
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

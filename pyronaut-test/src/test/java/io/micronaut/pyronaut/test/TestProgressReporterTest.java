package io.micronaut.pyronaut.test;

import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestIdentifier;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestProgressReporterTest {

    private static String visible(String output) {
        return output.replaceAll("\\u001B\\[[0-9;?]*[A-Za-z]", "")
            .replaceAll("\\u001B]8;;[^\\u001B]*\\u001B\\\\", "")
            .replace("\r", "");
    }

    private static TestIdentifier pytest(String nodeId) {
        return TestIdentifier.from(new AbstractTestDescriptor(UniqueId.forEngine("pyronaut-pytest").append("test", nodeId), nodeId) {
            @Override
            public Type getType() {
                return Type.TEST;
            }
        });
    }

    private static TestIdentifier javaTest(String className, String method) {
        return TestIdentifier.from(new AbstractTestDescriptor(UniqueId.forEngine("junit-jupiter").append("method", method), method + "()",
            MethodSource.from(className, method)) {
            @Override
            public Type getType() {
                return Type.TEST;
            }
        });
    }

    private static TestIdentifier container(String name) {
        return TestIdentifier.from(new AbstractTestDescriptor(UniqueId.forEngine("pyronaut-pytest").append("file", name), name) {
            @Override
            public Type getType() {
                return TestDescriptor.Type.CONTAINER;
            }
        });
    }

    @Test
    void plainModePrintsDeterministicLines() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        TestIdentifier index = pytest("tests/test_app.py::test_index");
        TestIdentifier broken = pytest("tests/test_app.py::test_broken");
        try (TestProgressReporter reporter = new TestProgressReporter(new PrintStream(buffer), false, false, false)) {
            reporter.executionStarted(index);
            reporter.executionFinished(index, TestExecutionResult.successful());
            reporter.executionStarted(broken);
            reporter.executionFinished(broken, TestExecutionResult.failed(new AssertionError("assert 1 == 2\n +  where 2 = len([])")));
            reporter.executionSkipped(pytest("tests/test_app.py::test_later"), "not yet");
            reporter.summary(Path.of("reports"));
        }

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("> tests/test_app.py::test_index"), output);
        assertTrue(output.matches("(?s).*PASSED tests/test_app.py::test_index \\(\\d+\\.\\ds\\).*"), output);
        assertTrue(output.contains("FAILED tests/test_app.py::test_broken"), output);
        assertTrue(output.contains("      AssertionError: assert 1 == 2"), output);
        assertTrue(output.contains("      +  where 2 = len([])"), output);
        assertTrue(output.contains("SKIPPED tests/test_app.py::test_later (skipped: not yet)"), output);
        assertTrue(output.matches("(?s).*1 of 2 tests failed, 1 skipped in \\d+\\.\\ds.*"), output);
        assertTrue(output.contains("Test reports directory: reports"), output);
        assertTrue(output.contains("Test report: "), output);
        assertFalse(output.contains(""), output);
    }

    @Test
    void interactiveModeCollapsesTestsIntoStatusLinesWithReportLink() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        TestIdentifier index = pytest("tests/test_app.py::test_index");
        TestIdentifier converter = javaTest("com.example.ConverterTest", "convertsValues");
        try (TestProgressReporter reporter = new TestProgressReporter(new PrintStream(buffer), true, false, false)) {
            reporter.error("force a frame");
            assertTrue(visible(buffer.toString(StandardCharsets.UTF_8)).contains("Starting test runtime"));
            reporter.planStarted(2);
            reporter.executionStarted(index);
            reporter.executionStarted(converter);
            reporter.error("force a frame");
            String mid = visible(buffer.toString(StandardCharsets.UTF_8));
            assertTrue(mid.contains("Running tests - 0/2 done"), mid);
            assertTrue(mid.contains("L tests/test_app.py::test_index"), mid);
            assertTrue(mid.contains("L ConverterTest.convertsValues()"), mid);

            reporter.executionFinished(index, TestExecutionResult.successful());
            reporter.executionFinished(converter, TestExecutionResult.failed(new IllegalStateException("boom")));
            reporter.executionFinished(container("tests/test_setup.py"), TestExecutionResult.failed(new RuntimeException("import failed")));
            reporter.summary(Path.of("/tmp/reports"));
        }

        String output = visible(buffer.toString(StandardCharsets.UTF_8));
        assertTrue(output.contains("  + tests/test_app.py::test_index ("), output);
        assertTrue(output.contains("  x ConverterTest.convertsValues() ("), output);
        assertTrue(output.contains("      IllegalStateException: boom"), output);
        assertTrue(output.contains("  x tests/test_setup.py (error)"), output);
        assertTrue(output.contains("      RuntimeException: import failed"), output);
        assertTrue(output.matches("(?s).*\\[\\d+\\.\\ds] x 1 of 2 tests failed, 1 error \\(\\d+\\.\\ds\\).*"), output);
        assertTrue(output.contains("  Report: /tmp/reports/index.html"), output);
        assertTrue(buffer.toString(StandardCharsets.UTF_8).contains("]8;;file:///tmp/reports/index.html"));
    }

    @Test
    void interactiveSuccessSummaryUsesCheckGlyphs() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        TestIdentifier index = pytest("tests/test_app.py::test_index");
        try (TestProgressReporter reporter = new TestProgressReporter(new PrintStream(buffer, true, StandardCharsets.UTF_8), true, true, true)) {
            reporter.executionStarted(index);
            reporter.executionFinished(index, TestExecutionResult.successful());
            reporter.summary(null);
        }

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("[32m✓[0m tests/test_app.py::test_index"), output);
        assertTrue(visible(output).matches("(?s).*✓ 1 test passed \\(\\d+\\.\\ds\\).*"), output);
    }

    @Test
    void failureLinesKeepPythonMessagesAndTrimJavaStacks() {
        RuntimeException failure = new RuntimeException("first\nsecond");
        List<String> lines = TestProgressReporter.failureLines(failure);
        assertEquals("RuntimeException: first", lines.get(0));
        assertEquals("second", lines.get(1));
        assertTrue(lines.stream().filter(line -> line.startsWith("  at ")).count() <= 6, lines.toString());
        assertTrue(lines.size() <= 31);
    }
}

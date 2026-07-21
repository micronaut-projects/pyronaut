package io.micronaut.test.pytest.execution;

import io.micronaut.test.pytest.PytestFileDescriptor;
import io.micronaut.test.pytest.PytestTestDescriptor;
import io.micronaut.test.pytest.PythonAssertionError;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PytestTestExecutorTest {

    @Test
    void usesFallbackReportPathWhenConfiguredPathIsMissing() {
        Path fallback = Path.of("/tmp/reports/junit.xml");
        assertEquals(fallback.toString(), PytestTestExecutor.resolveReportPath(null, fallback));
        assertEquals(fallback.toString(), PytestTestExecutor.resolveReportPath("   ", fallback));
    }

    @Test
    void keepsConfiguredReportPathWhenProvided() {
        Path fallback = Path.of("/tmp/reports/junit.xml");
        assertEquals("/custom/reports/junit.xml", PytestTestExecutor.resolveReportPath("/custom/reports/junit.xml", fallback));
    }

    @Test
    void buildsExactPytestNodeIdFromDescriptor() {
        PytestTestDescriptor descriptor = descriptor(
            Path.of("/workspace/tests/test_demo.py"),
            "test_demo.py",
            "TestHealth::test_ok"
        );

        assertEquals(
            "/workspace/tests/test_demo.py::TestHealth::test_ok",
            PytestTestExecutor.pytestNodeId(descriptor)
        );
    }

    @Test
    void usesExactNodeIdsForFilteredDescriptors() {
        PytestTestDescriptor first = descriptor(Path.of("/workspace/tests/test_demo.py"), "test_demo.py", "test_one");
        PytestTestDescriptor second = descriptor(Path.of("/workspace/tests/test_demo.py"), "test_demo.py", "test_two");

        assertArrayEquals(
            new String[]{
                "/workspace/tests/test_demo.py::test_one",
                "/workspace/tests/test_demo.py::test_two"
            },
            PytestTestExecutor.pytestArgumentsForDescriptors(List.of(first, second), List.of()).clone()
        );
    }

    @Test
    void fileDescriptorFallsBackToFilePathWhenItHasNoChildren() {
        PytestFileDescriptor file = new PytestFileDescriptor(
            UniqueId.forEngine("pytest-engine").append("source", "/workspace/tests/test_demo.py"),
            "test_demo.py",
            null
        );

        assertArrayEquals(
            new String[]{"/workspace/tests/test_demo.py"},
            PytestTestExecutor.pytestArgumentsForDescriptors(List.of(file), List.of("/workspace/tests/test_demo.py"))
        );
    }

    @Test
    void nonZeroPytestExitCodeFailsExecution() {
        JUnitPytestTestListener listener = new JUnitPytestTestListener(
            EngineExecutionListener.NOOP,
            Set.of()
        );

        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> PytestTestExecutor.failIfPytestFailed(listener, Value.asValue(2))
        );

        assertTrue(failure.getMessage().contains("Pytest session failed with exit code: 2"));
    }

    @Test
    void listenerSessionFailureDiagnosticsWinOverGenericExitCode() {
        JUnitPytestTestListener listener = new JUnitPytestTestListener(
            EngineExecutionListener.NOOP,
            Set.of()
        );
        listener.afterFile(
            "tests/test_bad.py",
            TestExecutionResult.failed(new PythonAssertionError("Collection failed: ImportError: bad import"))
        );

        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> PytestTestExecutor.failIfPytestFailed(listener, Value.asValue(2))
        );

        assertTrue(failure.getMessage().contains("Collection failed: ImportError: bad import"));
    }

    @Test
    void reportedTestFailuresDoNotFailSessionAgain() {
        JUnitPytestTestListener listener = new JUnitPytestTestListener(
            EngineExecutionListener.NOOP,
            Set.of()
        );
        listener.afterTest(
            "tests/test_demo.py::test_failure",
            Value.asValue(null),
            TestExecutionResult.failed(new PythonAssertionError("assert 1 == 2"))
        );
        listener.onResult(TestExecutionResult.failed(new RuntimeException("Pytest session failed with exit code: 1")));

        assertDoesNotThrow(() -> PytestTestExecutor.failIfPytestFailed(listener, Value.asValue(1)));
    }

    @Test
    void collectionFailuresStillFailSessionWhenTestsAlsoFail() {
        JUnitPytestTestListener listener = new JUnitPytestTestListener(
            EngineExecutionListener.NOOP,
            Set.of()
        );
        listener.afterTest(
            "tests/test_demo.py::test_failure",
            Value.asValue(null),
            TestExecutionResult.failed(new PythonAssertionError("assert 1 == 2"))
        );
        listener.afterFile(
            "tests/test_bad.py",
            TestExecutionResult.failed(new PythonAssertionError("Collection failed: ImportError: bad import"))
        );
        listener.onResult(TestExecutionResult.failed(new RuntimeException("Pytest session failed with exit code: 1")));

        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> PytestTestExecutor.failIfPytestFailed(listener, Value.asValue(1))
        );

        assertTrue(failure.getMessage().contains("Collection failed: ImportError: bad import"));
    }

    @Test
    void detectsMissingPytestFromCausalChain() {
        RuntimeException failure = new RuntimeException(
            "outer",
            new IllegalStateException("ModuleNotFoundError: No module named 'pytest'")
        );

        assertTrue(PytestTestExecutor.isMissingPytest(failure));
    }

    private static PytestTestDescriptor descriptor(Path filePath, String source, String testName) {
        return new PytestTestDescriptor(
            UniqueId.forEngine("pytest-engine")
                .append(PytestTestDescriptor.SEGMENT_SOURCE, source)
                .append(PytestTestDescriptor.SEGMENT_TEST, testName),
            source + "::" + testName,
            null,
            filePath,
            1,
            1,
            0,
            0
        );
    }
}

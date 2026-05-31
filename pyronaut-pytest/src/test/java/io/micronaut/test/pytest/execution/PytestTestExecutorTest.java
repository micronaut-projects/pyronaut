package io.micronaut.test.pytest.execution;

import io.micronaut.test.pytest.PythonAssertionError;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.TestExecutionResult;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}

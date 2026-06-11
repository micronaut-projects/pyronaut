package io.micronaut.test.pytest;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PythonAssertionErrorTest {

    @Test
    void filtersLauncherAndCliStackFramesFromPythonAssertionFailures() {
        StackTraceElement[] filtered = PythonAssertionError.filterStackTrace(new StackTraceElement[] {
            new StackTraceElement("tests.test_demo", "test_failure", "test_demo.py", 3),
            new StackTraceElement("io.micronaut.test.pytest.execution.PytestTestExecutor", "execute", "PytestTestExecutor.java", 123),
            new StackTraceElement("io.micronaut.test.pytest.PytestTestEngine", "execute", "PytestTestEngine.java", 159),
            new StackTraceElement("org.junit.platform.launcher.core.EngineExecutionOrchestrator", "executeEngine", "EngineExecutionOrchestrator.java", 246),
            new StackTraceElement("io.micronaut.pyronaut.test.PyronautTestMain", "call", "PyronautTestMain.java", 212),
            new StackTraceElement("picocli.CommandLine", "execute", "CommandLine.java", 2174)
        });

        assertEquals(1, filtered.length);
        assertEquals("tests.test_demo", filtered[0].getClassName());
        assertFalse(Arrays.toString(filtered).contains("PytestTestExecutor"));
        assertFalse(Arrays.toString(filtered).contains("PytestTestEngine"));
        assertFalse(Arrays.toString(filtered).contains("EngineExecutionOrchestrator"));
        assertFalse(Arrays.toString(filtered).contains("PyronautTestMain"));
        assertFalse(Arrays.toString(filtered).contains("picocli"));
    }
}

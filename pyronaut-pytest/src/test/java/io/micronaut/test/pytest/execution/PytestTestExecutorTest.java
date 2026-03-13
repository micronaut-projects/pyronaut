package io.micronaut.test.pytest.execution;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}

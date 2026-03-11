package io.micronaut.pyronaut.install;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallProgressReporterTest {

    @Test
    void nonInteractiveAutoModeProducesDeterministicLines() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InstallProgressReporter reporter = new InstallProgressReporter(new PrintStream(buffer), InstallProgressReporter.ProgressMode.AUTO, false)) {
            reporter.startScope(InstallScope.RUNTIME);
            reporter.finishScope(InstallScope.RUNTIME, 3);
        }

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Resolving runtime dependencies..."));
        assertTrue(output.contains("Resolved runtime dependencies (3 artifacts)"));
        assertFalse(output.contains("\r"));
    }

    @Test
    void interactiveOnModeEmitsSpinnerFrames() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InstallProgressReporter reporter = new InstallProgressReporter(new PrintStream(buffer), InstallProgressReporter.ProgressMode.ON, true)) {
            reporter.startScope(InstallScope.BUILD);
            Thread.sleep(150L);
            reporter.finishScope(InstallScope.BUILD, 1);
        }

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("\r"));
        assertTrue(output.contains("Resolved build dependencies (1 artifacts)"));
    }

    @Test
    void offModeSuppressesProgressOutput() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InstallProgressReporter reporter = new InstallProgressReporter(new PrintStream(buffer), InstallProgressReporter.ProgressMode.OFF, true)) {
            reporter.cacheHit();
            reporter.startScope(InstallScope.TEST);
            reporter.finishScope(InstallScope.TEST, 2);
        }

        assertEquals("", buffer.toString(StandardCharsets.UTF_8));
    }
}

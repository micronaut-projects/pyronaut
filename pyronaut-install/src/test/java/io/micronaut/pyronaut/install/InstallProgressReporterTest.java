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
            reporter.generatedApplicationSchema(12);
            reporter.directSourceSelection("Java", 2);
            reporter.directSourceDeclarations(1, 2, 1);
            reporter.directSourceDependencies(4);
            reporter.directSourceEditorSupport("Java");
        }

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Resolving runtime dependencies..."));
        assertTrue(output.contains("Resolved runtime dependencies (3 artifacts)"));
        assertTrue(output.contains("Generated application schema from runtime classpath (12 fragments)"));
        assertTrue(output.contains("Installing IDE support for 2 direct Java sources..."));
        assertTrue(output.contains("Discovered direct-source declarations (1 build, 2 runtime, 1 repositories)"));
        assertTrue(output.contains("Resolved direct-source dependencies (4 artifacts)"));
        assertTrue(output.contains("Generated Java IDE support"));
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
            reporter.generatedApplicationSchema(5);
            reporter.directSourceSelection("Java", 2);
            reporter.directSourceDeclarations(1, 2, 1);
            reporter.directSourceDependencies(4);
            reporter.directSourceEditorSupport("Java");
        }

        assertEquals("", buffer.toString(StandardCharsets.UTF_8));
    }
}

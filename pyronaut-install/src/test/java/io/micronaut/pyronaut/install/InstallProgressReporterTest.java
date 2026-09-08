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
    void interactiveModeRendersArtifactProgressAndBasename() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InstallProgressReporter reporter = new InstallProgressReporter(new PrintStream(buffer), InstallProgressReporter.ProgressMode.ON, true)) {
            reporter.startScope(InstallScope.RUNTIME);
            reporter.artifactPlanned(InstallScope.RUNTIME, "https://repo.example.invalid/a/b/runtime-dependency-1.0.jar");
            reporter.artifactStarted(InstallScope.RUNTIME, "https://repo.example.invalid/a/b/runtime-dependency-1.0.jar");
            assertTrue(buffer.toString(StandardCharsets.UTF_8).contains(" 0%"));
            reporter.artifactCompleted(InstallScope.RUNTIME, "https://repo.example.invalid/a/b/runtime-dependency-1.0.jar");
            assertTrue(buffer.toString(StandardCharsets.UTF_8).contains(" 99%"));
            reporter.artifactTransferFinished(InstallScope.RUNTIME, "https://repo.example.invalid/a/b/runtime-dependency-1.0.jar");
            reporter.finishScope(InstallScope.RUNTIME, 1);
            assertTrue(buffer.toString(StandardCharsets.UTF_8).contains("100%"));
        }

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("runtime-dependency-1.0.jar"));
        assertTrue(output.contains("100%"));
        assertTrue(output.contains("[#############]"));
        assertFalse(output.contains("FAILED"));
        assertTrue(output.contains("Resolved runtime dependencies (1 artifacts)"));
    }

    @Test
    void interactiveModeTruncatesLongArtifactNamesToKeepRowsFromWrapping() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        String coordinate = "io.micronaut.pyronaut:micronaut-pyronaut-config-model:jar:0.0.2-SNAPSHOT";
        try (InstallProgressReporter reporter = new InstallProgressReporter(new PrintStream(buffer), InstallProgressReporter.ProgressMode.ON, true)) {
            reporter.startScope(InstallScope.DEVELOPMENT_RUNTIME);
            reporter.artifactPlanned(InstallScope.DEVELOPMENT_RUNTIME, coordinate);
        }

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertFalse(output.contains(coordinate));
        for (String frame : output.split("\\r")) {
            String visible = frame.replaceAll("\\u001B\\[[0-9;]*[A-Za-z]", "");
            if (!visible.isBlank()) {
                assertTrue(visible.length() <= 79, () -> "Progress frame wrapped: " + visible);
            }
        }
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

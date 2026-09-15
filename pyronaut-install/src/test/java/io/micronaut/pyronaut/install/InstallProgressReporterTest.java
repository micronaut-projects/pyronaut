package io.micronaut.pyronaut.install;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallProgressReporterTest {

    private static String visible(String output) {
        return output.replaceAll("\\u001B\\[[0-9;?]*[A-Za-z]", "").replace("\r", "");
    }

    @Test
    void nonInteractiveAutoModeProducesDeterministicLines() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InstallProgressReporter reporter = new InstallProgressReporter(new PrintStream(buffer), InstallProgressReporter.ProgressMode.AUTO, false)) {
            reporter.startScope(InstallScope.RUNTIME);
            reporter.artifactStarted(InstallScope.RUNTIME, "a/b/runtime-dependency-1.0.jar");
            reporter.artifactProgressed(InstallScope.RUNTIME, "a/b/runtime-dependency-1.0.jar", 10, 100);
            reporter.finishScope(InstallScope.RUNTIME, 3);
            reporter.generatedApplicationSchema(12);
            reporter.directSourceSelection("Java", 2);
            reporter.directSourceDeclarations(1, 2, 1);
            reporter.directSourceDependencies(4);
            reporter.directSourceEditorSupport("Java");
            reporter.warn("something odd");
        }

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Resolving runtime dependencies..."));
        assertTrue(output.contains("Resolved runtime dependencies (3 artifacts)"));
        assertTrue(output.contains("Generated application schema from runtime classpath (12 fragments)"));
        assertTrue(output.contains("Installing IDE support for 2 direct Java sources..."));
        assertTrue(output.contains("Discovered direct-source declarations (1 build, 2 runtime, 1 repositories)"));
        assertTrue(output.contains("Resolved direct-source dependencies (4 artifacts)"));
        assertTrue(output.contains("Generated Java IDE support"));
        assertTrue(output.contains("WARNING: something odd"));
        assertFalse(output.contains("\r"));
        assertFalse(output.contains(""));
        assertFalse(output.contains("runtime-dependency-1.0.jar"), "transfer rows are interactive only");
    }

    @Test
    void interactiveOnModeEmitsSpinnerFrames() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InstallProgressReporter reporter = new InstallProgressReporter(new PrintStream(buffer), InstallProgressReporter.ProgressMode.ON, true)) {
            reporter.startScope(InstallScope.BUILD);
            Thread.sleep(300L);
            reporter.finishScope(InstallScope.BUILD, 1);
        }

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("\r"));
        String visible = visible(output);
        assertTrue(visible.contains("Collecting build dependencies"));
        // The ticker repaints the header without any resolver events.
        assertTrue(visible.split("Collecting build dependencies", -1).length > 2, () -> "expected repeated frames: " + visible);
        assertTrue(visible.contains("+ Resolved 1 build dependencies"));
    }

    @Test
    void interactiveModeRendersTransferBytesAndArtifactCounts() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        String resource = "io/micronaut/micronaut-core/4.0.0/micronaut-core-4.0.0.jar";
        try (InstallProgressReporter reporter = new InstallProgressReporter(new PrintStream(buffer), InstallProgressReporter.ProgressMode.ON, true)) {
            reporter.startScope(InstallScope.RUNTIME);
            reporter.artifactCompleted(InstallScope.RUNTIME, "io.micronaut.platform:micronaut-platform:pom:4.0.0");
            reporter.resetScope(InstallScope.RUNTIME);
            reporter.artifactStarted(InstallScope.RUNTIME, "io/micronaut/micronaut-core/4.0.0/micronaut-core-4.0.0.pom");
            reporter.artifactProgressed(InstallScope.RUNTIME, "io/micronaut/micronaut-core/4.0.0/micronaut-core-4.0.0.pom", 512, 2048);
            reporter.artifactCompleted(InstallScope.RUNTIME, "io.micronaut:micronaut-core:pom:4.0.0");
            reporter.warn("force a frame");
            String collecting = visible(buffer.toString(StandardCharsets.UTF_8));
            assertTrue(collecting.contains("Collecting runtime dependencies - 2 descriptors"), collecting);
            assertTrue(collecting.contains("L Fetch micronaut-core-4.0.0.pom"), collecting);
            assertTrue(collecting.contains("512B/2.0KB (25%)"), collecting);

            reporter.artifactTransferFinished(InstallScope.RUNTIME, "io/micronaut/micronaut-core/4.0.0/micronaut-core-4.0.0.pom");
            reporter.beginScope(InstallScope.RUNTIME);
            reporter.artifactPlanned(InstallScope.RUNTIME, "io.micronaut:micronaut-core:jar:4.0.0");
            reporter.artifactPlanned(InstallScope.RUNTIME, "io.micronaut:micronaut-inject:jar:4.0.0");
            reporter.artifactStarted(InstallScope.RUNTIME, resource);
            reporter.artifactProgressed(InstallScope.RUNTIME, resource, 1_500_000, 3_000_000);
            reporter.artifactCompleted(InstallScope.RUNTIME, "io.micronaut:micronaut-inject:jar:4.0.0");
            reporter.warn("force another frame");
            String resolving = visible(buffer.toString(StandardCharsets.UTF_8));
            assertTrue(resolving.contains("Resolving runtime dependencies - 1/2 artifacts"), resolving);
            assertTrue(resolving.contains("L Fetch micronaut-core-4.0.0.jar"), resolving);
            assertTrue(resolving.contains("#######-------"), resolving);
            assertTrue(resolving.contains("1.5MB/3.0MB (50%)"), resolving);

            reporter.artifactTransferFinished(InstallScope.RUNTIME, resource);
            reporter.artifactCompleted(InstallScope.RUNTIME, "io.micronaut:micronaut-core:jar:4.0.0");
            reporter.finishScope(InstallScope.RUNTIME, 2);
        }

        String output = visible(buffer.toString(StandardCharsets.UTF_8));
        assertTrue(output.contains("+ Resolved 2 runtime dependencies"), output);
        assertTrue(output.matches("(?s).*\\[\\d+\\.\\ds] \\+ Resolved 2 runtime dependencies \\(\\d+\\.\\ds\\).*"), output);
        assertFalse(output.contains("Failed"));
    }

    @Test
    void interactiveFailureLeavesDiagnosticOnANewLine() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InstallProgressReporter reporter = new InstallProgressReporter(new PrintStream(buffer), InstallProgressReporter.ProgressMode.ON, true)) {
            reporter.startScope(InstallScope.RUNTIME);
            reporter.failScope(InstallScope.RUNTIME);
        }

        String output = buffer.toString(StandardCharsets.UTF_8);
        String visible = visible(output);
        assertTrue(visible.contains("x Failed resolving runtime dependencies"), visible);
        assertTrue(visible.endsWith("\n"), visible);
        // The live region is cleared, leaving the cursor on an empty line.
        assertTrue(output.endsWith("\n") || output.endsWith("[J"), output);
    }

    @Test
    void interactiveModeKeepsRowsWithinTerminalWidth() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        String coordinate = "org/example/really-long-artifact-name/0.0.2-SNAPSHOT/really-long-artifact-name-0.0.2-20260914.123456-1-with-classifier.jar";
        try (InstallProgressReporter reporter = new InstallProgressReporter(new PrintStream(buffer), InstallProgressReporter.ProgressMode.ON, true)) {
            reporter.startScope(InstallScope.DEVELOPMENT_RUNTIME);
            reporter.artifactStarted(InstallScope.DEVELOPMENT_RUNTIME, coordinate);
            reporter.artifactProgressed(InstallScope.DEVELOPMENT_RUNTIME, coordinate, 123_456_789L, 987_654_321L);
            reporter.warn("force a frame");
        }

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertFalse(output.contains("with-classifier.jar"));
        int width = io.micronaut.pyronaut.config.terminal.Terminal.width();
        for (String line : visible(output).split("\n")) {
            assertTrue(line.length() < width, () -> "Progress row wrapped: '" + line + "'");
        }
        assertTrue(visible(output).contains("123.5MB/987.7MB (12%)"), visible(output));
    }

    @Test
    void interactiveModeCapsTransferRows() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InstallProgressReporter reporter = new InstallProgressReporter(new PrintStream(buffer), InstallProgressReporter.ProgressMode.ON, true)) {
            reporter.startScope(InstallScope.TEST);
            for (int i = 0; i < InstallProgressReporter.MAX_TRANSFER_ROWS + 3; i++) {
                reporter.artifactStarted(InstallScope.TEST, "a/b/artifact-" + i + ".jar");
            }
            reporter.warn("force a frame");
        }

        String output = visible(buffer.toString(StandardCharsets.UTF_8));
        assertTrue(output.contains("artifact-0.jar"));
        assertTrue(output.contains("... 3 more"), output);
        assertFalse(output.contains("artifact-" + (InstallProgressReporter.MAX_TRANSFER_ROWS + 1) + ".jar"));
    }

    @Test
    void colorAndUnicodeModeUsesGlyphsAndAnsiColors() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InstallProgressReporter reporter = new InstallProgressReporter(new PrintStream(buffer, true, StandardCharsets.UTF_8), InstallProgressReporter.ProgressMode.ON, true, true, true)) {
            reporter.startScope(InstallScope.BUILD);
            reporter.artifactStarted(InstallScope.BUILD, "a/b/c.jar");
            reporter.artifactProgressed(InstallScope.BUILD, "a/b/c.jar", 50, 100);
            reporter.warn("force a frame");
            reporter.finishScope(InstallScope.BUILD, 1);
        }

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("[32m✓[0m"), output);
        assertTrue(visible(output).contains("└ Fetch c.jar"), output);
        assertTrue(visible(output).contains("███████░░░░░░░"), output);
    }

    @Test
    void offModeSuppressesProgressOutput() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InstallProgressReporter reporter = new InstallProgressReporter(new PrintStream(buffer), InstallProgressReporter.ProgressMode.OFF, true)) {
            reporter.cacheHit();
            reporter.startScope(InstallScope.TEST);
            reporter.artifactStarted(InstallScope.TEST, "a/b/c.jar");
            reporter.finishScope(InstallScope.TEST, 2);
            reporter.generatedApplicationSchema(5);
            reporter.directSourceSelection("Java", 2);
            reporter.directSourceDeclarations(1, 2, 1);
            reporter.directSourceDependencies(4);
            reporter.directSourceEditorSupport("Java");
            reporter.warn("nothing");
        }

        assertEquals("", buffer.toString(StandardCharsets.UTF_8));
    }

    @Test
    void formatsDurationsAndSizes() {
        assertEquals("0.0s", io.micronaut.pyronaut.config.terminal.Terminal.formatDuration(0L));
        assertEquals("3.2s", io.micronaut.pyronaut.config.terminal.Terminal.formatDuration(3_200_000_000L));
        assertEquals("1m 05s", io.micronaut.pyronaut.config.terminal.Terminal.formatDuration(65_000_000_000L));
        assertEquals("999B", io.micronaut.pyronaut.config.terminal.Terminal.formatBytes(999L));
        assertEquals("1.0KB", io.micronaut.pyronaut.config.terminal.Terminal.formatBytes(1000L));
        assertEquals("2.7MB", io.micronaut.pyronaut.config.terminal.Terminal.formatBytes(2_700_000L));
        assertEquals("1.5GB", io.micronaut.pyronaut.config.terminal.Terminal.formatBytes(1_500_000_000L));
    }
}

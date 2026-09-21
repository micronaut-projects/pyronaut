package io.micronaut.pyronaut.config.terminal;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhaseReporterTest {

    @Test
    void plainOutputPrintsDeterministicLines() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PhaseReporter reporter = new PhaseReporter(new PrintStream(buffer, true, StandardCharsets.UTF_8), TerminalInfo.plain())) {
            PhaseReporter.Phase phase = reporter.start("Packaging FAT JAR");
            phase.detail("3/7 dependencies");
            phase.done("Packaged app.jar");
            reporter.note("Reachability metadata repository: disabled");
            reporter.hint("Run it with: java -jar app.jar");
            reporter.start("Building native image").fail("native-image exited with status 1");
            reporter.warn("something odd");
        }

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(output.startsWith("Packaging FAT JAR..."), output);
        assertTrue(output.contains("Packaged app.jar ("), output);
        assertTrue(output.contains("Reachability metadata repository: disabled"), output);
        assertTrue(output.contains("  Run it with: java -jar app.jar"), output);
        assertTrue(output.contains("Building native image..."), output);
        assertTrue(output.contains("native-image exited with status 1 ("), output);
        assertTrue(output.contains("WARNING: something odd"), output);
        assertFalse(output.contains("\033"), output);
        assertFalse(output.contains("3/7 dependencies"), output);
    }

    @Test
    void interactiveOutputDrawsRowsAndCollapsesThemIntoStampedLines() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        TerminalInfo terminal = new TerminalInfo(true, false, true, 80, System.currentTimeMillis());
        try (PhaseReporter reporter = new PhaseReporter(new PrintStream(buffer, true, StandardCharsets.UTF_8), terminal)) {
            PhaseReporter.Phase phase = reporter.start("Building native image");
            phase.detail("[2/8] Performing analysis");
            Thread.sleep(150L);
            reporter.print("Warning: something native-image said");
            phase.done("Built native image app");
        }

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Building native image · [2/8] Performing analysis"), output);
        assertTrue(output.contains("Warning: something native-image said"), output);
        assertTrue(output.contains("✓ Built native image app ("), output);
        assertTrue(output.contains("\033[2K"), output);
        // The region is cleared on close so nothing lingers below the last line.
        assertTrue(output.endsWith("\033[J") || output.endsWith("\n"), output);
    }
}

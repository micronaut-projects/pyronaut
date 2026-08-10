package io.micronaut.pyronaut.processor;

import io.micronaut.python.compiler.PyronautCompiler;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessorProgressReporterTest {

    @Test
    void nonInteractiveAutoModeProducesDeterministicLines() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ProcessorProgressReporter reporter = new ProcessorProgressReporter(new PrintStream(buffer), ProcessorProgressReporter.ProgressMode.AUTO, false)) {
            reporter.startPass("main", 3, false);
            reporter.finishPass("main", 3);
            reporter.cacheHit("test", 2);
            reporter.complete("processed", "cache hit");
        }

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Processing main sources (3 files)..."));
        assertTrue(output.contains("Processed main sources (3 files)"));
        assertTrue(output.contains("Skipped test sources (2 files, cache hit)"));
        assertTrue(output.contains("Processing completed (main: processed, test: cache hit)"));
        assertFalse(output.contains("\r"));
    }

    @Test
    void interactiveOnModeEmitsSpinnerFrames() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ProcessorProgressReporter reporter = new ProcessorProgressReporter(new PrintStream(buffer), ProcessorProgressReporter.ProgressMode.ON, true)) {
            reporter.startPass("test", 4, false);
            Thread.sleep(150L);
            reporter.finishPass("test", 4);
        }

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("\r"));
        assertTrue(output.contains("Processed test sources (4 files)"));
    }

    @Test
    void offModeSuppressesProgressOutput() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ProcessorProgressReporter reporter = new ProcessorProgressReporter(new PrintStream(buffer), ProcessorProgressReporter.ProgressMode.OFF, true)) {
            reporter.startPass("main", 1, false);
            reporter.finishPass("main", 1);
            reporter.noSources("test");
            reporter.complete("processed", "no sources");
        }

        assertEquals("", buffer.toString(StandardCharsets.UTF_8));
    }

    @Test
    void incrementalModeReportsTheFilesActuallySelected() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Path project = Path.of("/project");
        try (ProcessorProgressReporter reporter = new ProcessorProgressReporter(
            new PrintStream(buffer),
            ProcessorProgressReporter.ProgressMode.AUTO,
            false
        )) {
            reporter.startPass("main", 10, true);
            reporter.incrementalPlan(
                "main",
                project,
                10,
                new PyronautCompiler.IncrementalCompilationPlan(
                    false,
                    false,
                    List.of(project.resolve("src/seed.py"))
                )
            );
            reporter.finishPass("main", 10);
        }

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Checking main sources (10 files)..."));
        assertTrue(output.contains("Incrementally compiling main sources (1 of 10 files):"));
        assertTrue(output.contains("  - src/seed.py"));
        assertTrue(output.contains("Processed main sources (1 of 10 files recompiled incrementally)"));
        assertFalse(output.contains("Processing main sources (10 files)..."));
    }
}

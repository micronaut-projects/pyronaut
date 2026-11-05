package io.micronaut.pyronaut.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticCompilationSummaryTest {

    @TempDir
    Path report;

    @Test
    void countsTheOutcomesAndNamesTheMostCommonReason() throws Exception {
        Files.writeString(report.resolve(StaticCompilationSummary.DECISIONS_FILE), """
            {"record":"plan","mode":"all"}
            {"outcome":"COMPILED","reasons":[]}
            {"outcome":"COMPILED","reasons":[]}
            {"outcome":"SKIPPED","reasons":[{"rule":"unhinted-parameter"},{"rule":"generator-function"}]}
            {"outcome":"SKIPPED","reasons":[{"rule":"unhinted-parameter"}]}
            {"outcome":"NOT_CANDIDATE","reasons":[{"rule":"generator-function"},{"rule":"unhinted-parameter"}]}
            {"outcome":"EXCLUDED","reasons":[]}
            """);

        assertEquals(
            "Static compilation: 2 compiled, 2 skipped, 1 excluded, 1 not candidate"
                + " · top reason unhinted-parameter (3) · report " + report.resolve(StaticCompilationSummary.SUMMARY_FILE),
            StaticCompilationSummary.summarize(report));
    }

    @Test
    void breaksATieBetweenReasonsByName() throws Exception {
        Files.writeString(report.resolve(StaticCompilationSummary.DECISIONS_FILE), """
            {"outcome":"CANDIDATE","reasons":[{"rule":"second"}]}
            {"outcome":"CANDIDATE","reasons":[{"rule":"first"}]}
            """);

        String summary = StaticCompilationSummary.summarize(report);
        assertTrue(summary.startsWith("Static compilation: 0 compiled, 2 candidate · top reason first (1)"), summary);
    }

    @Test
    void summarizesNothingWithoutAReport() {
        assertNull(StaticCompilationSummary.summarize(report));
    }

    @Test
    void reportsTheSummaryOfAPassThatWroteOne() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        try (ProcessorProgressReporter reporter = new ProcessorProgressReporter(output, ProcessorProgressReporter.ProgressMode.AUTO, false)) {
            StaticCompilationSummary.report(reporter, null);
            StaticCompilationSummary.report(reporter, report);
            assertEquals("", bytes.toString(StandardCharsets.UTF_8), "no directory, or no report in it, says nothing");

            reporter.startPass("main", false, "static all");
            Files.writeString(report.resolve(StaticCompilationSummary.DECISIONS_FILE), "{\"outcome\":\"COMPILED\"}\n");
            StaticCompilationSummary.report(reporter, report);
        }

        String printed = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("main sources · static all"), printed);
        assertTrue(printed.contains("Static compilation: 1 compiled · report "), printed);
        assertFalse(printed.contains("top reason"), printed);
    }
}

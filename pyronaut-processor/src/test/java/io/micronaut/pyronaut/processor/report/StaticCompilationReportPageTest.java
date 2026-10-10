package io.micronaut.pyronaut.processor.report;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticCompilationReportPageTest {

    @Test
    void readsThePlanAndTheDecisionsTheProcessorWrites() throws Exception {
        StaticCompilationDecisions report = StaticCompilationDecisions.read(reportDirectory());

        assertNotNull(report);
        assertEquals("all", report.plan().mode());
        assertEquals("full", report.plan().coverage());
        assertEquals(Instant.parse("2026-09-28T09:15:30Z"), report.plan().written());
        assertEquals(5, report.decisions().size());

        StaticCompilationDecisions.Decision compiled = report.decisions().get(0);
        assertEquals("OwnerService.total", compiled.name());
        assertEquals("COMPILED", compiled.outcome());
        assertEquals("src/main/python/app/owners.py:12:4", compiled.location());
        assertEquals(new StaticCompilationDecisions.Stats(3, 2, 1, 0), compiled.stats());
        assertTrue(compiled.reasons().isEmpty());

        StaticCompilationDecisions.Decision skipped = report.decisions().get(1);
        assertEquals("SKIPPED", skipped.outcome());
        assertEquals(2, skipped.reasons().size());
        StaticCompilationDecisions.Reason reason = skipped.reasons().get(0);
        assertEquals("unhinted-parameter", reason.rule());
        assertEquals("src/main/python/app/owners.py:20:24", reason.location());
        assertEquals("Add a type hint to the parameter, such as name: str.", reason.hint());

        StaticCompilationDecisions.Decision unlocated = report.decisions().get(4);
        assertNull(unlocated.source());
        assertNull(unlocated.location());
        assertEquals("NOT_CANDIDATE", unlocated.outcome());
    }

    @Test
    void writesNoPageWithoutDecisions() throws Exception {
        Path empty = Files.createTempDirectory("static-compilation-empty");

        assertNull(StaticCompilationDecisions.read(empty));
        assertNull(StaticCompilationReportPage.write(empty));
        assertFalse(Files.exists(empty.resolve(StaticCompilationReportPage.PAGE_FILE)));
    }

    @Test
    void rendersThePageInTheDesignOfTheTestReport() throws Exception {
        Path directory = reportDirectory();
        Path page = StaticCompilationReportPage.write(directory);

        assertNotNull(page);
        assertEquals(directory.resolve("index.html"), page);
        String html = Files.readString(page);
        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.contains("<title>Pyronaut Static Compilation Report</title>"));
        // the shared stylesheet and script of the test report are inlined
        assertTrue(html.contains("<style>"));
        assertTrue(html.contains("--candidate"));
        assertTrue(html.contains("<script>"));
        assertTrue(html.contains("class=\"wordmark pyronaut-logo\""));
        assertTrue(html.contains("class=\"mascot\""));
        // the hero: the plan and the verdict
        assertTrue(html.contains("mode <code>all</code> &middot; full build &middot; experimental"));
        assertTrue(html.contains("<div class=\"verdict skipped\"><span class=\"dot\"></span>1 compiled, 2 skipped</div>"));
        // the stats
        assertTrue(html.contains("aria-label=\"Total: 5\""));
        assertTrue(html.contains("aria-label=\"Compiled: 1\""));
        assertTrue(html.contains("aria-label=\"Skipped: 2\""));
        assertTrue(html.contains("aria-label=\"Excluded: 1\""));
        assertTrue(html.contains("aria-label=\"Not candidate: 1\""));
        assertFalse(html.contains("aria-label=\"Candidate:"), "no candidates in a compilation that is on");
        assertTrue(html.contains("<span class=\"label\">Bridge calls</span><span class=\"value\">1</span>"));
        assertTrue(html.contains("33%<small>compile rate</small>"));
        // the blockers, most frequent first, with the fix of each rule
        int blockers = html.indexOf("What keeps functions in Python, and how to fix it");
        assertTrue(blockers > 0);
        assertTrue(html.contains("<span class=\"pill skipped\">3 reasons</span>"));
        int unhinted = html.indexOf("<span class=\"name\">unhinted-parameter</span><span class=\"time\">2 functions</span>", blockers);
        int builtin = html.indexOf("<span class=\"name\">python-builtin-not-lowered</span><span class=\"time\">1 function</span>", blockers);
        assertTrue(unhinted > 0 && builtin > unhinted, "the rules are sorted by the number of functions they keep in Python");
        assertTrue(html.contains("<p class=\"hint\">Add a type hint to the parameter, such as name: str.</p>"));
        assertTrue(html.contains("<p class=\"hint\">Pass a default to dict.get (d.get(key, 0)), or hint the values of the dict as objects.</p>"));
        // the decisions grouped by source, the skipped ones open
        assertTrue(html.contains("src/main/python/app/owners.py</span><span class=\"pills\"><span class=\"pill compiled\">1 compiled</span><span class=\"pill skipped\">1 skipped</span>"));
        assertTrue(html.contains("<details class=\"test skipped\" data-status=\"skipped\" data-search=\"ownerservice.find src/main/python/app/owners.py unhinted-parameter python-builtin-not-lowered\" open>"));
        assertTrue(html.contains("<details class=\"test compiled\" data-status=\"compiled\" data-search=\"ownerservice.total src/main/python/app/owners.py\">"));
        assertTrue(html.contains("3 statements · 2 Java calls · 1 bridge calls · 0 helpers · decided by the mode of the compilation"));
        assertTrue(html.contains("decided by the switch on the function: switched off"));
        assertTrue(html.contains("<code class=\"rule-id\">unhinted-parameter</code> <span class=\"where\">src/main/python/app/owners.py:20:24</span> <span class=\"message\">parameter &#39;name&#39; has no type hint</span>"));
        assertTrue(html.contains("<h2>Why it is not a candidate</h2>"));
        assertTrue(html.contains("Functions</span><span class=\"pills\"><span class=\"pill ineligible\">1 not candidate</span>"));
        assertTrue(html.contains("Static compilation is experimental</footer>"));
    }

    @Test
    void rendersAnEmptyCompilation() {
        StaticCompilationDecisions report = new StaticCompilationDecisions(new StaticCompilationDecisions.Plan("annotated", "incremental", null), List.of());
        String html = StaticCompilationReportPage.render(report);

        assertTrue(html.contains("mode <code>annotated</code> &middot; incremental build"));
        assertTrue(html.contains("Nothing was compiled"));
        assertTrue(html.contains("&ndash;<small>compile rate</small>"));
        assertTrue(html.contains("<div id=\"empty\" class=\"empty-state\">No candidate functions were found.</div>"));
        assertFalse(html.contains("What keeps functions in Python"));
    }

    private static Path reportDirectory() throws Exception {
        Path directory = Files.createTempDirectory("static-compilation");
        try (InputStream in = StaticCompilationReportPageTest.class.getResourceAsStream("decisions.jsonl")) {
            assertNotNull(in);
            Files.copy(in, directory.resolve(StaticCompilationDecisions.DECISIONS_FILE));
        }
        return directory;
    }
}

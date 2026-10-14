/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.test.pytest.execution;

import io.micronaut.pyronaut.report.ReportAssets;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Renders the self-contained Pyronaut HTML test report shared by the pytest engine and the JUnit launchers.
 * Styles, script and the Pyronaut artwork are inlined from the shared report assets so the report works offline.
 */
final class HtmlTestReport {

    private static final String DEFAULT_GROUP = "Tests";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm:ss z", Locale.ENGLISH);
    private static final String ICON_PASSED = "<svg width=\"12\" height=\"12\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"3.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M5 12.5l4.5 4.5L19 7.5\"/></svg>";
    private static final String ICON_FAILED = "<svg width=\"12\" height=\"12\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"3.5\" stroke-linecap=\"round\"><path d=\"M6 6l12 12M18 6L6 18\"/></svg>";
    private static final String ICON_SKIPPED = "<svg width=\"12\" height=\"12\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"3.5\" stroke-linecap=\"round\"><path d=\"M6 12h12\"/></svg>";
    private static final String ICON_CHEVRON = "<svg class=\"chevron\" width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M9 6l6 6-6 6\"/></svg>";
    private static final String ICON_FILE = "<svg width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z\"/><path d=\"M14 3v5h5\"/></svg>";
    private static final String ICON_SEARCH = "<svg width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\"><circle cx=\"11\" cy=\"11\" r=\"7\"/><path d=\"M20 20l-3.5-3.5\"/></svg>";

    private HtmlTestReport() {
    }

    /**
     * Renders the report.
     *
     * @param entries the test entries in execution order
     * @param startedAt when the session started
     * @param durationNanos the session duration in nanoseconds
     * @return the HTML document
     */
    static String render(List<Entry> entries, Instant startedAt, long durationNanos) {
        long total = entries.size();
        long passed = count(entries, JUnitReportWriter.Status.PASSED);
        long skipped = count(entries, JUnitReportWriter.Status.SKIPPED);
        long errors = entries.stream().filter(Entry::error).count();
        long failed = count(entries, JUnitReportWriter.Status.FAILED) - errors;

        var groups = new LinkedHashMap<String, List<Entry>>();
        for (Entry entry : entries) {
            groups.computeIfAbsent(groupOf(entry.name()), k -> new ArrayList<>()).add(entry);
        }

        StringBuilder html = new StringBuilder(16384);
        html.append("<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n")
            .append("<title>Pyronaut Test Report</title>\n")
            .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
            .append("<link rel=\"icon\" href=\"").append(ReportAssets.FAVICON).append("\">\n");
        String mascot = ReportAssets.mascot();
        html.append("<style>\n").append(ReportAssets.stylesheet()).append("</style>\n")
            .append("</head><body>\n");

        appendHero(html, mascot, total, failed + errors, startedAt, durationNanos);

        html.append("<main class=\"wrap\">\n<section class=\"summary\">\n<div class=\"stats\">\n");
        appendStat(html, "all", "total", "Total", total);
        appendStat(html, "passed", "passed", "Passed", passed);
        appendStat(html, "failed", failed > 0 ? "failed nonzero" : "failed", "Failed", failed);
        if (errors > 0) {
            appendStat(html, "failed", "failed nonzero", "Errors", errors);
        }
        appendStat(html, "skipped", "skipped", "Skipped", skipped);
        html.append("<div class=\"stat static duration\"><span class=\"label\">Duration</span><span class=\"value\">")
            .append(escapeHtml(formatDuration(durationNanos))).append("</span></div>\n");
        html.append("</div>\n<div class=\"progress\"><div class=\"bar\" role=\"img\" aria-label=\"")
            .append(passed).append(" passed, ").append(failed + errors).append(" failed, ").append(skipped).append(" skipped\">");
        appendBarSegment(html, "passed", passed);
        appendBarSegment(html, "failed", failed + errors);
        appendBarSegment(html, "skipped", skipped);
        long executed = total - skipped;
        String rate = executed == 0 ? "&ndash;" : Math.round(passed * 100.0 / executed) + "%";
        html.append("</div><span class=\"rate\">").append(rate).append("<small>pass rate</small></span></div>\n")
            .append("</section>\n");

        html.append("<div class=\"toolbar\">\n<div class=\"filters\" role=\"group\" aria-label=\"Filter by status\">");
        appendFilter(html, "all", "All", total);
        appendFilter(html, "failed", "Failed", failed + errors);
        appendFilter(html, "passed", "Passed", passed);
        appendFilter(html, "skipped", "Skipped", skipped);
        html.append("</div>\n<label class=\"search\">").append(ICON_SEARCH)
            .append("<input id=\"search\" type=\"search\" placeholder=\"Filter tests  (press /)\" aria-label=\"Filter tests\"></label>\n")
            .append("<button type=\"button\" class=\"ghost\" id=\"toggle-all\">Expand all</button>\n</div>\n");

        for (var group : groups.entrySet()) {
            List<Entry> groupEntries = group.getValue();
            html.append("<section class=\"group\">\n<div class=\"group-head\"><span class=\"file\">").append(ICON_FILE)
                .append(escapeHtml(group.getKey())).append("</span><span class=\"pills\">");
            appendPill(html, "passed", groupEntries, JUnitReportWriter.Status.PASSED);
            appendPill(html, "failed", groupEntries, JUnitReportWriter.Status.FAILED);
            appendPill(html, "skipped", groupEntries, JUnitReportWriter.Status.SKIPPED);
            html.append("</span></div>\n");
            for (Entry entry : groupEntries) {
                appendEntry(html, entry);
            }
            html.append("</section>\n");
        }
        html.append("<div id=\"empty\" class=\"empty-state").append(total == 0 ? "" : " hidden").append("\">")
            .append(total == 0 ? "No tests were run." : "No tests match the current filter.")
            .append("</div>\n");

        html.append("</main>\n<footer>Generated by <strong>Pyronaut</strong> &middot; Powered by Micronaut &amp; GraalPy</footer>\n")
            .append("<script>\n").append(ReportAssets.script()).append("</script>\n")
            .append("</body></html>\n");
        return html.toString();
    }

    private static void appendHero(StringBuilder html, String mascot, long total, long failures, Instant startedAt, long durationNanos) {
        html.append("<header class=\"hero\"><div class=\"wrap\">\n<div class=\"hero-text\">\n");
        String wordmark = ReportAssets.wordmark();
        if (wordmark != null) {
            html.append("<img class=\"wordmark pyronaut-logo\" src=\"").append(wordmark).append("\" alt=\"Pyronaut\">\n");
        } else {
            html.append("<div class=\"wordmark-fallback pyronaut-logo\">PYRONAUT</div>\n");
        }
        html.append("<h1>Test Report</h1>\n")
            .append("<div class=\"hero-meta\">")
            .append(escapeHtml(TIMESTAMP.format(startedAt.atZone(ZoneId.systemDefault()))))
            .append(" &middot; ").append(escapeHtml(formatDuration(durationNanos)))
            .append("</div>\n");
        String verdictClass;
        String verdictText;
        if (failures > 0) {
            verdictClass = "failed";
            verdictText = failures + (failures == 1 ? " test failed" : " tests failed");
        } else if (total == 0) {
            verdictClass = "empty";
            verdictText = "No tests were run";
        } else {
            verdictClass = "passed";
            verdictText = "All tests passed";
        }
        html.append("<div class=\"verdict ").append(verdictClass).append("\"><span class=\"dot\"></span>")
            .append(verdictText).append("</div>\n</div>\n");
        if (mascot != null) {
            html.append("<img class=\"mascot\" src=\"").append(mascot).append("\" alt=\"\" aria-hidden=\"true\">\n");
        }
        html.append("</div></header>\n");
    }

    private static void appendEntry(StringBuilder html, Entry entry) {
        String tone = tone(entry.status());
        boolean failed = entry.status() == JUnitReportWriter.Status.FAILED;
        html.append("<details class=\"test ").append(tone).append("\" data-status=\"").append(tone)
            .append("\" data-search=\"").append(escapeHtml(entry.name().toLowerCase(Locale.ROOT))).append("\"")
            .append(failed ? " open" : "").append(">\n")
            .append("<summary><span class=\"icon\">").append(icon(entry.status())).append("</span>")
            .append("<span class=\"name\" title=\"").append(escapeHtml(entry.name())).append("\">")
            .append(escapeHtml(shortName(entry.name()))).append("</span>");
        if (entry.durationNanos() >= 0) {
            html.append("<span class=\"time\">").append(escapeHtml(formatDuration(entry.durationNanos()))).append("</span>");
        }
        html.append("<span class=\"status-badge\">").append(entry.error() ? "ERROR" : entry.status().name()).append("</span>")
            .append(ICON_CHEVRON).append("</summary>\n<div class=\"detail\">\n");

        appendSection(html, "Failure", entry.failure(), "failure");
        appendSection(html, "Framework Log", entry.log(), "log");
        appendSection(html, "System Out", entry.stdout(), "stdout");
        appendSection(html, "System Err", entry.stderr(), "stderr");

        if (entry.failure().isBlank() && entry.log().isBlank() && entry.stdout().isBlank() && entry.stderr().isBlank()) {
            html.append("<p class=\"none\">No additional diagnostics captured for this test.</p>\n");
        }
        html.append("</div></details>\n");
    }

    private static void appendSection(StringBuilder html, String title, String content, String kind) {
        if (content.isBlank()) {
            return;
        }
        html.append("<section class=\"section ").append(kind).append("\">\n")
            .append("<div class=\"section-head\"><h2>").append(escapeHtml(title)).append("</h2>")
            .append("<button type=\"button\" class=\"copy\">Copy</button></div>\n")
            .append("<pre><code>").append(escapeHtml(content)).append("</code></pre>\n")
            .append("</section>\n");
    }

    private static void appendStat(StringBuilder html, String filter, String cssClass, String label, long value) {
        html.append("<button type=\"button\" class=\"stat ").append(cssClass).append("\" data-filter=\"").append(filter)
            .append("\" aria-pressed=\"false\" aria-label=\"").append(label).append(": ").append(value)
            .append("\"><span class=\"label\">").append(label).append("</span><span class=\"value\">")
            .append(value).append("</span></button>\n");
    }

    private static void appendFilter(StringBuilder html, String filter, String label, long count) {
        html.append("<button type=\"button\" data-filter=\"").append(filter).append("\" aria-pressed=\"false\">")
            .append(label).append("<span class=\"count\">").append(count).append("</span></button>");
    }

    private static void appendBarSegment(StringBuilder html, String cssClass, long count) {
        if (count > 0) {
            html.append("<span class=\"").append(cssClass).append("\" style=\"flex:").append(count).append("\"></span>");
        }
    }

    private static void appendPill(StringBuilder html, String cssClass, List<Entry> entries, JUnitReportWriter.Status status) {
        long count = count(entries, status);
        if (count > 0) {
            html.append("<span class=\"pill ").append(cssClass).append("\">").append(count).append(' ')
                .append(cssClass).append("</span>");
        }
    }

    private static long count(List<Entry> entries, JUnitReportWriter.Status status) {
        return entries.stream().filter(entry -> entry.status() == status).count();
    }

    /**
     * Pytest node ids group by file ({@code tests/test_x.py::test_y}); JUnit names group by
     * class ({@code AppTest.works()}).
     */
    private static String groupOf(String name) {
        int separator = name.indexOf("::");
        if (separator >= 0) {
            return name.substring(0, separator);
        }
        int dot = javaMemberSeparator(name);
        return dot < 0 ? DEFAULT_GROUP : name.substring(0, dot);
    }

    private static String shortName(String name) {
        int separator = name.indexOf("::");
        if (separator >= 0) {
            return name.substring(separator + 2);
        }
        int dot = javaMemberSeparator(name);
        return dot < 0 ? name : name.substring(dot + 1);
    }

    private static int javaMemberSeparator(String name) {
        int paren = name.indexOf('(');
        String head = paren < 0 ? name : name.substring(0, paren);
        if (head.indexOf(' ') >= 0 || head.indexOf('/') >= 0) {
            return -1;
        }
        return head.lastIndexOf('.');
    }

    private static String tone(JUnitReportWriter.Status status) {
        return switch (status) {
            case PASSED -> "passed";
            case SKIPPED -> "skipped";
            case FAILED -> "failed";
        };
    }

    private static String icon(JUnitReportWriter.Status status) {
        return switch (status) {
            case PASSED -> ICON_PASSED;
            case SKIPPED -> ICON_SKIPPED;
            case FAILED -> ICON_FAILED;
        };
    }

    static String formatDuration(long nanos) {
        Duration duration = Duration.ofNanos(Math.max(0, nanos));
        long millis = duration.toMillis();
        if (millis < 1) {
            return "<1 ms";
        } else if (millis < 1000) {
            return millis + " ms";
        } else if (millis < 60_000) {
            return String.format(Locale.ROOT, "%.2f s", millis / 1000.0);
        }
        return String.format(Locale.ROOT, "%dm %02ds", duration.toMinutes(), duration.toSecondsPart());
    }

    static String escapeHtml(String text) {
        return ReportAssets.escapeHtml(text);
    }

    /**
     * One row in the report.
     *
     * @param name the test name: a pytest node id or a JUnit {@code Class.method()} name
     * @param status the outcome
     * @param error whether the failure came from a container rather than a test
     * @param durationNanos the duration, or a negative value when unknown
     * @param failure the rendered failure
     * @param log captured framework log output
     * @param stdout captured standard output
     * @param stderr captured standard error
     */
    record Entry(String name, JUnitReportWriter.Status status, boolean error, long durationNanos,
                 String failure, String log, String stdout, String stderr) {
        Entry {
            failure = failure == null ? "" : failure;
            log = log == null ? "" : log;
            stdout = stdout == null ? "" : stdout;
            stderr = stderr == null ? "" : stderr;
        }
    }
}

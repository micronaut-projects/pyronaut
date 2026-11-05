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
package io.micronaut.pyronaut.processor.report;

import io.micronaut.pyronaut.processor.report.StaticCompilationDecisions.Decision;
import io.micronaut.pyronaut.processor.report.StaticCompilationDecisions.Reason;
import io.micronaut.pyronaut.report.ReportAssets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.micronaut.pyronaut.report.ReportAssets.escapeHtml;

/**
 * Renders the decisions of a static compilation as one self-contained HTML page in the design of
 * the Pyronaut test report: the stylesheet, the script and the artwork are inlined, so the page
 * works offline. The page lists what keeps functions in Python, with a fix and the affected
 * functions for every reason, then every decision grouped by source, filtered by outcome and
 * searched by name.
 */
public final class StaticCompilationReportPage {

    /**
     * The name of the page written next to the decisions.
     */
    public static final String PAGE_FILE = "index.html";

    private static final String DEFAULT_GROUP = "Functions";
    private static final String SECTION_END = "</section>\n";
    private static final String SPAN_END = "</span>";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm:ss z", Locale.ENGLISH);
    private static final String ICON_COMPILED = "<svg width=\"12\" height=\"12\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"3.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M5 12.5l4.5 4.5L19 7.5\"/></svg>";
    private static final String ICON_SKIPPED = "<svg width=\"12\" height=\"12\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"3.5\" stroke-linecap=\"round\"><path d=\"M6 6l12 12M18 6L6 18\"/></svg>";
    private static final String ICON_CANDIDATE = "<svg width=\"12\" height=\"12\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"3.5\" stroke-linecap=\"round\"><circle cx=\"12\" cy=\"12\" r=\"7\"/></svg>";
    private static final String ICON_EXCLUDED = "<svg width=\"12\" height=\"12\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"3.5\" stroke-linecap=\"round\"><path d=\"M6 12h12\"/></svg>";
    private static final String ICON_INELIGIBLE = "<svg width=\"12\" height=\"12\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"3\" stroke-linecap=\"round\"><circle cx=\"12\" cy=\"12\" r=\"7\"/><path d=\"M7 17L17 7\"/></svg>";
    private static final String ICON_RULE = "<svg width=\"12\" height=\"12\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"3\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M12 4v9\"/><circle cx=\"12\" cy=\"18\" r=\"1.2\"/></svg>";
    private static final String ICON_CHEVRON = "<svg class=\"chevron\" width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M9 6l6 6-6 6\"/></svg>";
    private static final String ICON_FILE = "<svg width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z\"/><path d=\"M14 3v5h5\"/></svg>";
    private static final String ICON_WRENCH = "<svg width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M14.7 6.3a4 4 0 0 0 5 5L13 18a2.1 2.1 0 0 1-3-3l6.7-6.7z\"/><path d=\"M3 21l6-6\"/></svg>";
    private static final String ICON_SEARCH = "<svg width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\"><circle cx=\"11\" cy=\"11\" r=\"7\"/><path d=\"M20 20l-3.5-3.5\"/></svg>";

    private StaticCompilationReportPage() {
    }

    /**
     * Renders the page of a report directory and writes it next to the decisions.
     *
     * @param directory The report directory the annotation processor wrote
     * @return The page written, or {@code null} when the directory holds no decisions
     * @throws IOException When the decisions cannot be read or the page cannot be written
     */
    public static Path write(Path directory) throws IOException {
        StaticCompilationDecisions decisions = StaticCompilationDecisions.read(directory);
        if (decisions == null) {
            return null;
        }
        Path page = directory.resolve(PAGE_FILE);
        Files.writeString(page, render(decisions), StandardCharsets.UTF_8);
        return page;
    }

    /**
     * Renders the page.
     *
     * @param report The decisions of the compilation
     * @return The HTML document
     */
    public static String render(StaticCompilationDecisions report) {
        List<Decision> decisions = report.decisions();
        String mode = report.plan().mode();
        boolean incremental = "incremental".equals(report.plan().coverage());
        Instant written = report.plan().written() == null ? Instant.now() : report.plan().written();
        Summary summary = Summary.of(decisions, mode);

        StringBuilder html = new StringBuilder(32768);
        appendHead(html);
        appendHero(html, mode, incremental, written, summary);
        html.append("<main class=\"wrap\">\n");
        appendSummary(html, summary);
        appendToolbar(html, summary);
        appendBlockers(html, decisions);
        appendGroups(html, decisions);
        appendFoot(html, summary.total());
        return html.toString();
    }

    private static void appendHead(StringBuilder html) {
        html.append("<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n")
            .append("<title>Pyronaut Static Compilation Report</title>\n")
            .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
            .append("<link rel=\"icon\" href=\"").append(ReportAssets.FAVICON).append("\">\n")
            .append("<style>\n").append(ReportAssets.stylesheet()).append("</style>\n")
            .append("</head><body>\n");
    }

    private static void appendHero(StringBuilder html, String mode, boolean incremental, Instant written, Summary summary) {
        html.append("<header class=\"hero\"><div class=\"wrap\">\n<div class=\"hero-text\">\n");
        String wordmark = ReportAssets.wordmark();
        if (wordmark != null) {
            html.append("<img class=\"wordmark pyronaut-logo\" src=\"").append(wordmark).append("\" alt=\"Pyronaut\">\n");
        } else {
            html.append("<div class=\"wordmark-fallback pyronaut-logo\">PYRONAUT</div>\n");
        }
        html.append("<h1>Static Compilation Report</h1>\n")
            .append("<div class=\"hero-meta\">")
            .append(escapeHtml(TIMESTAMP.format(written.atZone(ZoneId.systemDefault()))))
            .append(" &middot; mode <code>").append(escapeHtml(mode)).append("</code>")
            .append(" &middot; ").append(incremental ? "incremental build" : "full build")
            .append(" &middot; experimental")
            .append("</div>\n");
        appendVerdict(html, summary);
        String mascot = ReportAssets.mascot();
        if (mascot != null) {
            html.append("<img class=\"mascot\" src=\"").append(mascot).append("\" alt=\"\" aria-hidden=\"true\">\n");
        }
        html.append("</div></header>\n");
    }

    private static void appendVerdict(StringBuilder html, Summary summary) {
        long compiled = summary.count(Outcome.COMPILED);
        long skipped = summary.count(Outcome.SKIPPED);
        long candidates = summary.count(Outcome.CANDIDATE);
        String verdictClass;
        String verdictText;
        if (skipped > 0) {
            verdictClass = Outcome.SKIPPED.key;
            verdictText = compiled + " compiled, " + skipped + " skipped";
        } else if (compiled > 0) {
            verdictClass = "passed";
            verdictText = compiled == 1 ? "The candidate was compiled" : "All " + compiled + " candidates compiled";
        } else if (candidates > 0) {
            verdictClass = Outcome.CANDIDATE.key;
            verdictText = candidates + (candidates == 1 ? " function would compile" : " functions would compile");
        } else {
            verdictClass = "empty";
            verdictText = "Nothing was compiled";
        }
        html.append("<div class=\"verdict ").append(verdictClass).append("\"><span class=\"dot\"></span>")
            .append(verdictText).append("</div>\n</div>\n");
    }

    private static void appendSummary(StringBuilder html, Summary summary) {
        html.append("<section class=\"summary\">\n<div class=\"stats\">\n");
        appendStat(html, "all", "total", "Total", summary.total());
        for (Outcome outcome : summary.shownOutcomes()) {
            long count = summary.count(outcome);
            appendStat(html, outcome.key, outcome == Outcome.SKIPPED && count > 0 ? outcome.key + " nonzero" : outcome.key, outcome.label, count);
        }
        html.append("<div class=\"stat static bridge\"><span class=\"label\">Bridge calls</span><span class=\"value\">")
            .append(summary.bridgeCalls()).append("</span></div>\n");
        html.append("</div>\n<div class=\"progress\"><div class=\"bar\" role=\"img\" aria-label=\"")
            .append(summary.count(Outcome.COMPILED)).append(" compiled, ")
            .append(summary.count(Outcome.SKIPPED)).append(" skipped, ")
            .append(summary.count(Outcome.CANDIDATE)).append(" candidates, ")
            .append(summary.count(Outcome.EXCLUDED)).append(" excluded, ")
            .append(summary.count(Outcome.NOT_CANDIDATE)).append(" not candidates\">");
        for (Outcome outcome : Outcome.values()) {
            appendBarSegment(html, outcome.key, summary.count(outcome));
        }
        html.append("</div><span class=\"rate\">").append(summary.compileRate()).append("<small>compile rate</small></span></div>\n")
            .append(SECTION_END);
    }

    private static void appendToolbar(StringBuilder html, Summary summary) {
        html.append("<div class=\"toolbar\">\n<div class=\"filters\" role=\"group\" aria-label=\"Filter by outcome\">");
        appendFilter(html, "all", "All", summary.total());
        for (Outcome outcome : summary.shownOutcomes()) {
            appendFilter(html, outcome.key, outcome.label, summary.count(outcome));
        }
        html.append("</div>\n<label class=\"search\">").append(ICON_SEARCH)
            .append("<input id=\"search\" type=\"search\" placeholder=\"Filter functions  (press /)\" aria-label=\"Filter functions\"></label>\n")
            .append("<button type=\"button\" class=\"ghost\" id=\"toggle-all\">Expand all</button>\n</div>\n");
    }

    /**
     * Gathers the functions and the fixes of each reason given and lists them, when any reason was given.
     */
    private static void appendBlockers(StringBuilder html, List<Decision> decisions) {
        Map<String, List<Decision>> byRule = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> hintsByRule = new HashMap<>();
        for (Decision decision : decisions) {
            for (Reason reason : decision.reasons()) {
                addBlocker(decision, reason, byRule, hintsByRule);
            }
        }
        if (!byRule.isEmpty()) {
            appendRules(html, byRule, hintsByRule);
        }
    }

    private static void addBlocker(Decision decision, Reason reason, Map<String, List<Decision>> byRule, Map<String, Map<String, Integer>> hintsByRule) {
        List<Decision> affected = byRule.computeIfAbsent(reason.rule(), k -> new ArrayList<>());
        if (affected.isEmpty() || affected.get(affected.size() - 1) != decision) {
            affected.add(decision);
        }
        if (reason.hint() != null) {
            hintsByRule.computeIfAbsent(reason.rule(), k -> new LinkedHashMap<>()).merge(reason.hint(), 1, Integer::sum);
        }
    }

    /**
     * The reasons given, most frequent first, each with the fix the processor gives most often for it
     * and the functions it applies to.
     */
    private static void appendRules(StringBuilder html, Map<String, List<Decision>> byRule, Map<String, Map<String, Integer>> hintsByRule) {
        List<Map.Entry<String, List<Decision>>> rules = new ArrayList<>(byRule.entrySet());
        rules.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));
        html.append("<section class=\"group blockers\">\n<div class=\"group-head\"><span class=\"file\">").append(ICON_WRENCH)
            .append("What keeps functions in Python, and how to fix it</span><span class=\"pills\"><span class=\"pill skipped\">")
            .append(rules.size()).append(rules.size() == 1 ? " reason" : " reasons").append("</span></span></div>\n");
        for (var rule : rules) {
            List<Decision> affected = rule.getValue();
            String hint = commonHint(hintsByRule.get(rule.getKey()));
            html.append("<details class=\"rule\"><summary><span class=\"icon\">").append(ICON_RULE).append(SPAN_END)
                .append("<span class=\"name\">").append(escapeHtml(rule.getKey())).append(SPAN_END)
                .append("<span class=\"time\">").append(affected.size()).append(affected.size() == 1 ? " function" : " functions").append(SPAN_END)
                .append(ICON_CHEVRON).append("</summary>\n<div class=\"detail\">\n");
            if (hint != null) {
                html.append("<p class=\"hint\">").append(escapeHtml(hint)).append("</p>\n");
            }
            html.append("<ul class=\"affected\">\n");
            for (Decision decision : affected) {
                html.append("<li><span class=\"name\">").append(escapeHtml(decision.name())).append(SPAN_END);
                if (decision.location() != null) {
                    html.append(" <span class=\"where\">").append(escapeHtml(decision.location())).append(SPAN_END);
                }
                html.append("</li>\n");
            }
            html.append("</ul>\n</div></details>\n");
        }
        html.append(SECTION_END);
    }

    private static String commonHint(Map<String, Integer> hints) {
        if (hints == null) {
            return null;
        }
        String common = null;
        int best = 0;
        for (var hint : hints.entrySet()) {
            if (hint.getValue() > best) {
                best = hint.getValue();
                common = hint.getKey();
            }
        }
        return common;
    }

    /**
     * Every decision, grouped by the source it was taken for, in the order the sources first appear.
     */
    private static void appendGroups(StringBuilder html, List<Decision> decisions) {
        Map<String, List<Decision>> groups = new LinkedHashMap<>();
        for (Decision decision : decisions) {
            groups.computeIfAbsent(decision.source() == null ? DEFAULT_GROUP : decision.source(), k -> new ArrayList<>()).add(decision);
        }
        for (var group : groups.entrySet()) {
            List<Decision> groupDecisions = group.getValue();
            html.append("<section class=\"group\">\n<div class=\"group-head\"><span class=\"file\">").append(ICON_FILE)
                .append(escapeHtml(group.getKey())).append("</span><span class=\"pills\">");
            for (Outcome outcome : Outcome.values()) {
                appendPill(html, outcome, groupDecisions);
            }
            html.append("</span></div>\n");
            for (Decision decision : groupDecisions) {
                appendEntry(html, decision);
            }
            html.append(SECTION_END);
        }
    }

    private static void appendFoot(StringBuilder html, long total) {
        html.append("<div id=\"empty\" class=\"empty-state").append(total == 0 ? "" : " hidden").append("\">")
            .append(total == 0 ? "No candidate functions were found." : "No functions match the current filter.")
            .append("</div>\n");
        html.append("</main>\n<footer>Generated by <strong>Pyronaut</strong> &middot; Powered by Micronaut &amp; GraalPy &middot; Static compilation is experimental</footer>\n")
            .append("<script>\n").append(ReportAssets.script()).append("</script>\n")
            .append("</body></html>\n");
    }

    private static void appendEntry(StringBuilder html, Decision decision) {
        Outcome outcome = Outcome.shownFor(decision);
        StringBuilder search = new StringBuilder(decision.name().toLowerCase(Locale.ROOT));
        if (decision.source() != null) {
            search.append(' ').append(decision.source().toLowerCase(Locale.ROOT));
        }
        for (Reason reason : decision.reasons()) {
            search.append(' ').append(reason.rule());
        }
        html.append("<details class=\"test ").append(outcome.key).append("\" data-status=\"").append(outcome.key)
            .append("\" data-search=\"").append(escapeHtml(search.toString())).append("\"")
            .append(Outcome.SKIPPED.matches(decision) ? " open" : "").append(">\n")
            .append("<summary><span class=\"icon\">").append(outcome.icon).append(SPAN_END)
            .append("<span class=\"name\" title=\"").append(escapeHtml(decision.name())).append("\">")
            .append(escapeHtml(decision.name())).append(SPAN_END);
        if (decision.location() != null) {
            html.append("<span class=\"time\">").append(escapeHtml(decision.location())).append(SPAN_END);
        }
        html.append("<span class=\"status-badge\">").append(outcome.label.toUpperCase(Locale.ROOT)).append(SPAN_END)
            .append(ICON_CHEVRON).append("</summary>\n<div class=\"detail\">\n");

        html.append("<p class=\"facts\">").append(escapeHtml(facts(decision))).append("</p>\n");
        if (!decision.reasons().isEmpty()) {
            html.append("<section class=\"section reasons\">\n<div class=\"section-head\"><h2>")
                .append(Outcome.NOT_CANDIDATE.matches(decision) ? "Why it is not a candidate" : "Why it is not compiled")
                .append("</h2></div>\n<ol class=\"reasons\">\n");
            for (Reason reason : decision.reasons()) {
                html.append("<li><code class=\"rule-id\">").append(escapeHtml(reason.rule())).append("</code> ");
                if (reason.location() != null) {
                    html.append("<span class=\"where\">").append(escapeHtml(reason.location())).append("</span> ");
                }
                html.append("<span class=\"message\">").append(escapeHtml(reason.message())).append(SPAN_END);
                if (reason.hint() != null) {
                    html.append("\n<p class=\"hint\">").append(escapeHtml(reason.hint())).append("</p>");
                }
                html.append("</li>\n");
            }
            html.append("</ol>\n</section>\n");
        }
        html.append("</div></details>\n");
    }

    /**
     * One line saying what the body contains and which declaration decided the outcome.
     */
    private static String facts(Decision decision) {
        StringBuilder facts = new StringBuilder();
        if (Outcome.COMPILED.matches(decision) || Outcome.CANDIDATE.matches(decision)) {
            facts.append(decision.stats().statements()).append(" statements · ")
                .append(decision.stats().javaCalls()).append(" Java calls · ")
                .append(decision.stats().bridgeCalls()).append(" bridge calls · ")
                .append(decision.stats().helperCalls()).append(" helpers · ");
        }
        facts.append(switch (decision.scope()) {
            case "MODULE" -> "decided by the switch on the module";
            case "CLASS" -> "decided by the switch on the class";
            case "FUNCTION" -> "decided by the switch on the function";
            default -> "decided by the mode of the compilation";
        });
        if (Outcome.CANDIDATE.matches(decision)) {
            facts.append(": the body would compile");
        } else if (Outcome.EXCLUDED.matches(decision)) {
            facts.append(": switched off");
        }
        return facts.toString();
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

    private static void appendPill(StringBuilder html, Outcome outcome, List<Decision> decisions) {
        long count = outcome.count(decisions);
        if (count > 0) {
            html.append("<span class=\"pill ").append(outcome.key).append("\">").append(count).append(' ')
                .append(outcome.label.toLowerCase(Locale.ROOT)).append(SPAN_END);
        }
    }

    /**
     * The outcomes of a decision, named as the processor writes them, in the order of the bar and the pills.
     */
    private enum Outcome {
        COMPILED("compiled", "Compiled", ICON_COMPILED),
        CANDIDATE("candidate", "Candidate", ICON_CANDIDATE),
        SKIPPED("skipped", "Skipped", ICON_SKIPPED),
        EXCLUDED("excluded", "Excluded", ICON_EXCLUDED),
        NOT_CANDIDATE("ineligible", "Not candidate", ICON_INELIGIBLE);

        /**
         * The key of the outcome in the page: its CSS tone, its filter and its {@code data-status}.
         */
        private final String key;
        private final String label;
        private final String icon;

        Outcome(String key, String label, String icon) {
            this.key = key;
            this.label = label;
            this.icon = icon;
        }

        /**
         * The outcome an entry is shown with. One the page does not know looks skipped, without
         * counting as skipped: the counts and the open entries take only the outcomes named here.
         */
        static Outcome shownFor(Decision decision) {
            for (Outcome outcome : values()) {
                if (outcome.matches(decision)) {
                    return outcome;
                }
            }
            return SKIPPED;
        }

        boolean matches(Decision decision) {
            return name().equals(decision.outcome());
        }

        long count(List<Decision> decisions) {
            return decisions.stream().filter(this::matches).count();
        }
    }

    /**
     * What the summary, the toolbar and the verdict of the page say about the decisions.
     *
     * @param total          The number of decisions
     * @param counts         The number of decisions with each outcome
     * @param bridgeCalls    The bridge calls of the compiled functions
     * @param showCandidates Whether candidates have a stat and a filter: there are some, or the compilation is off
     */
    private record Summary(long total, Map<Outcome, Long> counts, long bridgeCalls, boolean showCandidates) {

        static Summary of(List<Decision> decisions, String mode) {
            Map<Outcome, Long> counts = new EnumMap<>(Outcome.class);
            for (Outcome outcome : Outcome.values()) {
                counts.put(outcome, outcome.count(decisions));
            }
            long bridgeCalls = decisions.stream()
                .filter(Outcome.COMPILED::matches)
                .mapToLong(decision -> decision.stats().bridgeCalls())
                .sum();
            return new Summary(decisions.size(), counts, bridgeCalls, counts.get(Outcome.CANDIDATE) > 0 || "off".equals(mode));
        }

        long count(Outcome outcome) {
            return counts.get(outcome);
        }

        /**
         * @return The outcomes with a stat and a filter, in the order they are shown
         */
        List<Outcome> shownOutcomes() {
            return showCandidates
                ? List.of(Outcome.COMPILED, Outcome.SKIPPED, Outcome.CANDIDATE, Outcome.EXCLUDED, Outcome.NOT_CANDIDATE)
                : List.of(Outcome.COMPILED, Outcome.SKIPPED, Outcome.EXCLUDED, Outcome.NOT_CANDIDATE);
        }

        String compileRate() {
            long compiled = count(Outcome.COMPILED);
            long candidates = count(Outcome.CANDIDATE);
            long attempted = compiled + candidates + count(Outcome.SKIPPED);
            return attempted == 0 ? "&ndash;" : Math.round((compiled + candidates) * 100.0 / attempted) + "%";
        }
    }
}

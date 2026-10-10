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
    private static final List<String> OUTCOMES = List.of("COMPILED", "CANDIDATE", "SKIPPED", "EXCLUDED", "NOT_CANDIDATE");
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

        long bridgeCalls = 0;
        for (Decision decision : decisions) {
            if ("COMPILED".equals(decision.outcome())) {
                bridgeCalls += decision.stats().bridgeCalls();
            }
        }
        long total = decisions.size();
        long compiled = count(decisions, "COMPILED");
        long skipped = count(decisions, "SKIPPED");
        long candidates = count(decisions, "CANDIDATE");
        long excluded = count(decisions, "EXCLUDED");
        long ineligible = count(decisions, "NOT_CANDIDATE");
        boolean showCandidates = candidates > 0 || "off".equals(mode);

        Map<String, List<Decision>> groups = new LinkedHashMap<>();
        for (Decision decision : decisions) {
            groups.computeIfAbsent(decision.source() == null ? DEFAULT_GROUP : decision.source(), k -> new ArrayList<>()).add(decision);
        }
        Map<String, List<Decision>> byRule = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> hintsByRule = new HashMap<>();
        for (Decision decision : decisions) {
            for (Reason reason : decision.reasons()) {
                List<Decision> affected = byRule.computeIfAbsent(reason.rule(), k -> new ArrayList<>());
                if (affected.isEmpty() || affected.get(affected.size() - 1) != decision) {
                    affected.add(decision);
                }
                if (reason.hint() != null) {
                    hintsByRule.computeIfAbsent(reason.rule(), k -> new LinkedHashMap<>()).merge(reason.hint(), 1, Integer::sum);
                }
            }
        }

        StringBuilder html = new StringBuilder(32768);
        html.append("<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n")
            .append("<title>Pyronaut Static Compilation Report</title>\n")
            .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
            .append("<link rel=\"icon\" href=\"").append(ReportAssets.FAVICON).append("\">\n")
            .append("<style>\n").append(ReportAssets.stylesheet()).append("</style>\n")
            .append("</head><body>\n");

        appendHero(html, mode, incremental, written, compiled, skipped, candidates);

        html.append("<main class=\"wrap\">\n<section class=\"summary\">\n<div class=\"stats\">\n");
        appendStat(html, "all", "total", "Total", total);
        appendStat(html, "compiled", "compiled", "Compiled", compiled);
        appendStat(html, "skipped", skipped > 0 ? "skipped nonzero" : "skipped", "Skipped", skipped);
        if (showCandidates) {
            appendStat(html, "candidate", "candidate", "Candidate", candidates);
        }
        appendStat(html, "excluded", "excluded", "Excluded", excluded);
        appendStat(html, "ineligible", "ineligible", "Not candidate", ineligible);
        html.append("<div class=\"stat static bridge\"><span class=\"label\">Bridge calls</span><span class=\"value\">")
            .append(bridgeCalls).append("</span></div>\n");
        html.append("</div>\n<div class=\"progress\"><div class=\"bar\" role=\"img\" aria-label=\"")
            .append(compiled).append(" compiled, ").append(skipped).append(" skipped, ").append(candidates).append(" candidates, ")
            .append(excluded).append(" excluded, ").append(ineligible).append(" not candidates\">");
        appendBarSegment(html, "compiled", compiled);
        appendBarSegment(html, "candidate", candidates);
        appendBarSegment(html, "skipped", skipped);
        appendBarSegment(html, "excluded", excluded);
        appendBarSegment(html, "ineligible", ineligible);
        long attempted = compiled + candidates + skipped;
        String rate = attempted == 0 ? "&ndash;" : Math.round((compiled + candidates) * 100.0 / attempted) + "%";
        html.append("</div><span class=\"rate\">").append(rate).append("<small>compile rate</small></span></div>\n")
            .append("</section>\n");

        html.append("<div class=\"toolbar\">\n<div class=\"filters\" role=\"group\" aria-label=\"Filter by outcome\">");
        appendFilter(html, "all", "All", total);
        appendFilter(html, "compiled", "Compiled", compiled);
        appendFilter(html, "skipped", "Skipped", skipped);
        if (showCandidates) {
            appendFilter(html, "candidate", "Candidate", candidates);
        }
        appendFilter(html, "excluded", "Excluded", excluded);
        appendFilter(html, "ineligible", "Not candidate", ineligible);
        html.append("</div>\n<label class=\"search\">").append(ICON_SEARCH)
            .append("<input id=\"search\" type=\"search\" placeholder=\"Filter functions  (press /)\" aria-label=\"Filter functions\"></label>\n")
            .append("<button type=\"button\" class=\"ghost\" id=\"toggle-all\">Expand all</button>\n</div>\n");

        if (!byRule.isEmpty()) {
            appendBlockers(html, byRule, hintsByRule);
        }

        for (var group : groups.entrySet()) {
            List<Decision> groupDecisions = group.getValue();
            html.append("<section class=\"group\">\n<div class=\"group-head\"><span class=\"file\">").append(ICON_FILE)
                .append(escapeHtml(group.getKey())).append("</span><span class=\"pills\">");
            for (String outcome : OUTCOMES) {
                appendPill(html, outcome, groupDecisions);
            }
            html.append("</span></div>\n");
            for (Decision decision : groupDecisions) {
                appendEntry(html, decision);
            }
            html.append("</section>\n");
        }
        html.append("<div id=\"empty\" class=\"empty-state").append(total == 0 ? "" : " hidden").append("\">")
            .append(total == 0 ? "No candidate functions were found." : "No functions match the current filter.")
            .append("</div>\n");

        html.append("</main>\n<footer>Generated by <strong>Pyronaut</strong> &middot; Powered by Micronaut &amp; GraalPy &middot; Static compilation is experimental</footer>\n")
            .append("<script>\n").append(ReportAssets.script()).append("</script>\n")
            .append("</body></html>\n");
        return html.toString();
    }

    private static void appendHero(StringBuilder html, String mode, boolean incremental, Instant written, long compiled, long skipped, long candidates) {
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
        String verdictClass;
        String verdictText;
        if (skipped > 0) {
            verdictClass = "skipped";
            verdictText = compiled + " compiled, " + skipped + " skipped";
        } else if (compiled > 0) {
            verdictClass = "passed";
            verdictText = compiled == 1 ? "The candidate was compiled" : "All " + compiled + " candidates compiled";
        } else if (candidates > 0) {
            verdictClass = "candidate";
            verdictText = candidates + (candidates == 1 ? " function would compile" : " functions would compile");
        } else {
            verdictClass = "empty";
            verdictText = "Nothing was compiled";
        }
        html.append("<div class=\"verdict ").append(verdictClass).append("\"><span class=\"dot\"></span>")
            .append(verdictText).append("</div>\n</div>\n");
        String mascot = ReportAssets.mascot();
        if (mascot != null) {
            html.append("<img class=\"mascot\" src=\"").append(mascot).append("\" alt=\"\" aria-hidden=\"true\">\n");
        }
        html.append("</div></header>\n");
    }

    /**
     * The reasons given, most frequent first, each with the fix the processor gives most often for it
     * and the functions it applies to.
     */
    private static void appendBlockers(StringBuilder html, Map<String, List<Decision>> byRule, Map<String, Map<String, Integer>> hintsByRule) {
        List<Map.Entry<String, List<Decision>>> rules = new ArrayList<>(byRule.entrySet());
        rules.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));
        html.append("<section class=\"group blockers\">\n<div class=\"group-head\"><span class=\"file\">").append(ICON_WRENCH)
            .append("What keeps functions in Python, and how to fix it</span><span class=\"pills\"><span class=\"pill skipped\">")
            .append(rules.size()).append(rules.size() == 1 ? " reason" : " reasons").append("</span></span></div>\n");
        for (var rule : rules) {
            List<Decision> affected = rule.getValue();
            String hint = commonHint(hintsByRule.get(rule.getKey()));
            html.append("<details class=\"rule\"><summary><span class=\"icon\">").append(ICON_RULE).append("</span>")
                .append("<span class=\"name\">").append(escapeHtml(rule.getKey())).append("</span>")
                .append("<span class=\"time\">").append(affected.size()).append(affected.size() == 1 ? " function" : " functions").append("</span>")
                .append(ICON_CHEVRON).append("</summary>\n<div class=\"detail\">\n");
            if (hint != null) {
                html.append("<p class=\"hint\">").append(escapeHtml(hint)).append("</p>\n");
            }
            html.append("<ul class=\"affected\">\n");
            for (Decision decision : affected) {
                html.append("<li><span class=\"name\">").append(escapeHtml(decision.name())).append("</span>");
                if (decision.location() != null) {
                    html.append(" <span class=\"where\">").append(escapeHtml(decision.location())).append("</span>");
                }
                html.append("</li>\n");
            }
            html.append("</ul>\n</div></details>\n");
        }
        html.append("</section>\n");
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

    private static void appendEntry(StringBuilder html, Decision decision) {
        String key = key(decision.outcome());
        StringBuilder search = new StringBuilder(decision.name().toLowerCase(Locale.ROOT));
        if (decision.source() != null) {
            search.append(' ').append(decision.source().toLowerCase(Locale.ROOT));
        }
        for (Reason reason : decision.reasons()) {
            search.append(' ').append(reason.rule());
        }
        html.append("<details class=\"test ").append(key).append("\" data-status=\"").append(key)
            .append("\" data-search=\"").append(escapeHtml(search.toString())).append("\"")
            .append("SKIPPED".equals(decision.outcome()) ? " open" : "").append(">\n")
            .append("<summary><span class=\"icon\">").append(icon(decision.outcome())).append("</span>")
            .append("<span class=\"name\" title=\"").append(escapeHtml(decision.name())).append("\">")
            .append(escapeHtml(decision.name())).append("</span>");
        if (decision.location() != null) {
            html.append("<span class=\"time\">").append(escapeHtml(decision.location())).append("</span>");
        }
        html.append("<span class=\"status-badge\">").append(label(decision.outcome()).toUpperCase(Locale.ROOT)).append("</span>")
            .append(ICON_CHEVRON).append("</summary>\n<div class=\"detail\">\n");

        html.append("<p class=\"facts\">").append(escapeHtml(facts(decision))).append("</p>\n");
        if (!decision.reasons().isEmpty()) {
            html.append("<section class=\"section reasons\">\n<div class=\"section-head\"><h2>")
                .append("NOT_CANDIDATE".equals(decision.outcome()) ? "Why it is not a candidate" : "Why it is not compiled")
                .append("</h2></div>\n<ol class=\"reasons\">\n");
            for (Reason reason : decision.reasons()) {
                html.append("<li><code class=\"rule-id\">").append(escapeHtml(reason.rule())).append("</code> ");
                if (reason.location() != null) {
                    html.append("<span class=\"where\">").append(escapeHtml(reason.location())).append("</span> ");
                }
                html.append("<span class=\"message\">").append(escapeHtml(reason.message())).append("</span>");
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
        if ("COMPILED".equals(decision.outcome()) || "CANDIDATE".equals(decision.outcome())) {
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
        if ("CANDIDATE".equals(decision.outcome())) {
            facts.append(": the body would compile");
        } else if ("EXCLUDED".equals(decision.outcome())) {
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

    private static long count(List<Decision> decisions, String outcome) {
        return decisions.stream().filter(decision -> outcome.equals(decision.outcome())).count();
    }

    private static void appendPill(StringBuilder html, String outcome, List<Decision> decisions) {
        long count = count(decisions, outcome);
        if (count > 0) {
            html.append("<span class=\"pill ").append(key(outcome)).append("\">").append(count).append(' ')
                .append(label(outcome).toLowerCase(Locale.ROOT)).append("</span>");
        }
    }

    /**
     * The key of an outcome in the page: its CSS tone, its filter and its {@code data-status}.
     */
    static String key(String outcome) {
        return switch (outcome) {
            case "COMPILED" -> "compiled";
            case "CANDIDATE" -> "candidate";
            case "EXCLUDED" -> "excluded";
            case "NOT_CANDIDATE" -> "ineligible";
            default -> "skipped";
        };
    }

    private static String label(String outcome) {
        return switch (outcome) {
            case "COMPILED" -> "Compiled";
            case "CANDIDATE" -> "Candidate";
            case "EXCLUDED" -> "Excluded";
            case "NOT_CANDIDATE" -> "Not candidate";
            default -> "Skipped";
        };
    }

    private static String icon(String outcome) {
        return switch (outcome) {
            case "COMPILED" -> ICON_COMPILED;
            case "CANDIDATE" -> ICON_CANDIDATE;
            case "EXCLUDED" -> ICON_EXCLUDED;
            case "NOT_CANDIDATE" -> ICON_INELIGIBLE;
            default -> ICON_SKIPPED;
        };
    }
}

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
package io.micronaut.pyronaut.processor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one-line summary of a static compilation report after a pass: the counts per outcome, the
 * most common reason and where the report is. Read from the {@code decisions.jsonl} the compiler
 * writes, one JSON object per line.
 */
final class StaticCompilationSummary {

    static final String DECISIONS_FILE = "decisions.jsonl";
    static final String SUMMARY_FILE = "summary.txt";
    private static final Pattern OUTCOME = Pattern.compile("\"outcome\"\\s*:\\s*\"([A-Z_]+)\"");
    private static final Pattern RULE = Pattern.compile("\"rule\"\\s*:\\s*\"([a-z0-9-]+)\"");

    private StaticCompilationSummary() {
    }

    /**
     * @param reporter        The progress reporter of the run
     * @param reportDirectory The report directory of the pass, or {@code null} when nothing was compiled
     */
    static void report(ProcessorProgressReporter reporter, Path reportDirectory) {
        if (reportDirectory == null) {
            return;
        }
        String line = summarize(reportDirectory);
        if (line != null) {
            reporter.info(line);
        }
    }

    /**
     * @param reportDirectory The report directory of a pass
     * @return The summary line, or {@code null} when the directory holds no report
     */
    static String summarize(Path reportDirectory) {
        Path decisions = reportDirectory.resolve(DECISIONS_FILE);
        if (!Files.isRegularFile(decisions)) {
            return null;
        }
        Map<String, Integer> outcomes = new LinkedHashMap<>();
        Map<String, Integer> rules = new TreeMap<>();
        try {
            for (String record : Files.readAllLines(decisions, StandardCharsets.UTF_8)) {
                Matcher outcome = OUTCOME.matcher(record);
                if (!outcome.find()) {
                    continue;
                }
                outcomes.merge(outcome.group(1).toLowerCase(java.util.Locale.ROOT), 1, Integer::sum);
                Matcher rule = RULE.matcher(record);
                while (rule.find()) {
                    rules.merge(rule.group(1), 1, Integer::sum);
                }
            }
        } catch (IOException e) {
            return null;
        }
        StringBuilder line = new StringBuilder("Static compilation: ");
        line.append(outcomes.getOrDefault("compiled", 0)).append(" compiled");
        for (String outcome : new String[] {"skipped", "excluded", "candidate", "not_candidate"}) {
            int count = outcomes.getOrDefault(outcome, 0);
            if (count > 0) {
                line.append(", ").append(count).append(' ').append(outcome.replace('_', ' '));
            }
        }
        rules.entrySet().stream()
            .max((a, b) -> a.getValue().equals(b.getValue()) ? b.getKey().compareTo(a.getKey()) : a.getValue().compareTo(b.getValue()))
            .ifPresent(top -> line.append(" · top reason ").append(top.getKey()).append(" (").append(top.getValue()).append(')'));
        line.append(" · report ").append(reportDirectory.resolve(SUMMARY_FILE));
        return line.toString();
    }
}

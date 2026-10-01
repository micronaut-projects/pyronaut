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

import io.micronaut.jackson.core.tree.JsonNodeTreeCodec;
import io.micronaut.json.tree.JsonNode;
import tools.jackson.core.JsonParser;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.json.JsonFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * The decisions of a static compilation as the Python annotation processor writes them: the
 * {@code decisions.jsonl} of its report directory, one JSON object per line, the first one the plan
 * of the compilation and every other one the decision taken for a candidate function.
 *
 * @param plan      The plan of the compilation, or the defaults when the file starts without one
 * @param decisions The decisions, in the order the file lists them
 */
public record StaticCompilationDecisions(Plan plan, List<Decision> decisions) {

    /**
     * The name of the file the annotation processor writes.
     */
    public static final String DECISIONS_FILE = "decisions.jsonl";

    private static final JsonFactory JSON = JsonFactory.builder().build();

    /**
     * Reads the decisions of a report directory.
     *
     * @param directory The report directory
     * @return The decisions, or {@code null} when the directory holds no decisions file
     * @throws IOException When the file cannot be read or is not the processor's JSON
     */
    public static StaticCompilationDecisions read(Path directory) throws IOException {
        Path file = directory.resolve(DECISIONS_FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        Plan plan = new Plan("off", "full", null);
        List<Decision> decisions = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode record = parse(line);
            String kind = string(record, "record");
            if ("plan".equals(kind)) {
                plan = new Plan(string(record, "mode", "off"), string(record, "coverage", "full"), instant(string(record, "written")));
            } else if ("decision".equals(kind)) {
                decisions.add(decision(record));
            }
        }
        return new StaticCompilationDecisions(plan, List.copyOf(decisions));
    }

    private static JsonNode parse(String line) throws IOException {
        try (JsonParser parser = JSON.createParser(ObjectReadContext.empty(), line)) {
            return JsonNodeTreeCodec.getInstance().readTree(parser);
        }
    }

    private static Decision decision(JsonNode record) {
        List<Reason> reasons = new ArrayList<>();
        JsonNode reasonNodes = record.get("reasons");
        if (reasonNodes != null && reasonNodes.isArray()) {
            for (JsonNode reason : reasonNodes.values()) {
                reasons.add(new Reason(string(reason, "rule", "?"), string(reason, "message", ""), string(reason, "location"), string(reason, "hint")));
            }
        }
        JsonNode statNodes = record.get("stats");
        Stats stats = statNodes == null || !statNodes.isObject()
            ? Stats.NONE
            : new Stats(number(statNodes, "statements"), number(statNodes, "javaCalls"), number(statNodes, "bridgeCalls"), number(statNodes, "helperCalls"));
        return new Decision(
            string(record, "name", "?"),
            string(record, "source"),
            number(record, "line"),
            number(record, "column"),
            string(record, "outcome", "SKIPPED"),
            string(record, "scope", "MODE"),
            List.copyOf(reasons),
            stats);
    }

    private static String string(JsonNode node, String field) {
        return string(node, field, null);
    }

    private static String string(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.coerceStringValue();
    }

    private static int number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? 0 : value.getIntValue();
    }

    private static Instant instant(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * The plan of the compilation.
     *
     * @param mode     The mode of the compilation: {@code off}, {@code annotated} or {@code all}
     * @param coverage {@code full} when every source was planned, {@code incremental} when the affected ones were
     * @param written  When the report was written, or {@code null} when the processor did not say
     */
    public record Plan(String mode, String coverage, Instant written) {
    }

    /**
     * The decision taken for one candidate function.
     *
     * @param name    The function, as {@code Class.method} or the name of a module-level function
     * @param source  The path of the source the function is declared in, or {@code null} when unknown
     * @param line    The line of the declaration, 0 when unknown
     * @param column  The column of the declaration, 0 when unknown
     * @param outcome The outcome: {@code COMPILED}, {@code CANDIDATE}, {@code SKIPPED}, {@code EXCLUDED} or {@code NOT_CANDIDATE}
     * @param scope   The declaration that decided whether compilation was attempted: {@code MODE}, {@code MODULE}, {@code CLASS} or {@code FUNCTION}
     * @param reasons Why the body is not compiled; empty when it is
     * @param stats   What the body contains
     */
    public record Decision(String name, String source, int line, int column, String outcome, String scope, List<Reason> reasons, Stats stats) {

        /**
         * @return Where the function is declared, as {@code path:line:column}, or {@code null} when unknown
         */
        public String location() {
            return source == null ? null : source + ":" + line + ":" + column;
        }
    }

    /**
     * Why a body is not compiled.
     *
     * @param rule     The stable identifier of the reason
     * @param message  What was found
     * @param location Where, as {@code path:line:column}, or {@code null} when unknown
     * @param hint     What to change so that the reason goes away, or {@code null} when the processor gave none
     */
    public record Reason(String rule, String message, String location, String hint) {
    }

    /**
     * What a body contains.
     *
     * @param statements  The statements of the body
     * @param javaCalls   The calls to Java methods and constructors
     * @param bridgeCalls The calls that cross into Python
     * @param helperCalls The calls to the runtime's Python-semantics helpers
     */
    public record Stats(int statements, int javaCalls, int bridgeCalls, int helperCalls) {

        /**
         * No statistics.
         */
        public static final Stats NONE = new Stats(0, 0, 0, 0);
    }
}

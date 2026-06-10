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
package io.micronaut.pyronaut.projectgen;

import io.micronaut.projectgen.core.feature.config.Configuration;
import io.micronaut.projectgen.core.template.DefaultTemplate;
import io.micronaut.projectgen.core.template.TomlTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class PyprojectTomlTemplate extends DefaultTemplate {
    private static final String DEPENDENCIES_TABLE = "[tool.pyronaut.dependencies]";
    private static final Set<String> DEPENDENCY_SCOPES = Set.of("runtime", "build", "test");

    private final TomlTemplate delegate;

    PyprojectTomlTemplate(String path, Configuration config) {
        super(path);
        this.delegate = new TomlTemplate(path, config);
    }

    @Override
    public void write(OutputStream outputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        delegate.write(buffer);
        String toml = buffer.toString(StandardCharsets.UTF_8);
        outputStream.write(formatDependencyLists(toml).getBytes(StandardCharsets.UTF_8));
    }

    private static String formatDependencyLists(String toml) {
        StringBuilder result = new StringBuilder(toml.length());
        boolean dependenciesTable = false;
        for (String line : toml.split("\\R", -1)) {
            if (line.startsWith("[") && line.endsWith("]")) {
                dependenciesTable = DEPENDENCIES_TABLE.equals(line);
            }
            if (dependenciesTable && isInlineDependencyList(line)) {
                appendMultiLineList(result, line);
            } else {
                result.append(line).append('\n');
            }
        }
        return result.toString();
    }

    private static boolean isInlineDependencyList(String line) {
        int assignmentIndex = line.indexOf(" = [");
        if (assignmentIndex < 0 || !line.endsWith("]")) {
            return false;
        }
        return DEPENDENCY_SCOPES.contains(line.substring(0, assignmentIndex));
    }

    private static void appendMultiLineList(StringBuilder result, String line) {
        int assignmentIndex = line.indexOf(" = [");
        String scope = line.substring(0, assignmentIndex);
        String values = line.substring(assignmentIndex + " = [".length(), line.length() - 1);
        result.append(scope).append(" = [\n");
        if (!values.isBlank()) {
            List<String> items = commaSeparatedValues(values);
            for (int i = 0; i < items.size(); i++) {
                result.append("  ").append(items.get(i));
                if (i < items.size() - 1) {
                    result.append(',');
                }
                result.append('\n');
            }
        }
        result.append("]\n");
    }

    private static List<String> commaSeparatedValues(String values) {
        List<String> items = new ArrayList<>();
        int start = 0;
        int next;
        while ((next = values.indexOf(", ", start)) >= 0) {
            items.add(values.substring(start, next));
            start = next + 2;
        }
        items.add(values.substring(start));
        return items;
    }
}

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PyronautConfigurationTomlTemplate extends DefaultTemplate {
    private final TomlTemplate delegate;

    PyronautConfigurationTomlTemplate(String path, Configuration config) {
        super(path);
        this.delegate = new TomlTemplate(path, config);
    }

    @Override
    public void write(OutputStream outputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        delegate.write(buffer);
        String toml = buffer.toString(StandardCharsets.UTF_8);
        outputStream.write(formatDottedAssignments(toml).getBytes(StandardCharsets.UTF_8));
    }

    private static String formatDottedAssignments(String toml) {
        String[] lines = toml.split("\\R", -1);
        Map<String, List<String>> tables = new LinkedHashMap<>();
        List<String> normalLines = new ArrayList<>();
        int insertionIndex = -1;
        for (String line : lines) {
            DottedAssignment assignment = dottedAssignment(line);
            if (assignment != null) {
                if (insertionIndex < 0) {
                    insertionIndex = normalLines.size();
                }
                tables.computeIfAbsent(assignment.table(), ignored -> new ArrayList<>())
                    .add(assignment.keyAndValue());
            } else {
                normalLines.add(line);
            }
        }
        if (tables.isEmpty()) {
            return toml;
        }

        StringBuilder result = new StringBuilder(toml.length() + tables.size() * 8);
        for (int i = 0; i < normalLines.size(); i++) {
            if (i == insertionIndex) {
                appendTables(result, tables);
            }
            result.append(normalLines.get(i)).append('\n');
        }
        if (insertionIndex == normalLines.size()) {
            appendTables(result, tables);
        }
        return result.toString();
    }

    private static DottedAssignment dottedAssignment(String line) {
        int assignmentIndex = line.indexOf(" = ");
        if (assignmentIndex < 0 || line.startsWith("[") || line.startsWith(" ")) {
            return null;
        }
        String key = line.substring(0, assignmentIndex);
        int dotIndex = key.lastIndexOf('.');
        if (dotIndex < 0) {
            return null;
        }
        String table = key.substring(0, dotIndex);
        String keyAndValue = key.substring(dotIndex + 1) + line.substring(assignmentIndex);
        return new DottedAssignment(table, keyAndValue);
    }

    private static void appendTables(StringBuilder result, Map<String, List<String>> tables) {
        for (Map.Entry<String, List<String>> entry : tables.entrySet()) {
            result.append('[').append(entry.getKey()).append("]\n");
            for (String keyAndValue : entry.getValue()) {
                result.append(keyAndValue).append('\n');
            }
            result.append('\n');
        }
    }

    private record DottedAssignment(String table, String keyAndValue) {
    }
}

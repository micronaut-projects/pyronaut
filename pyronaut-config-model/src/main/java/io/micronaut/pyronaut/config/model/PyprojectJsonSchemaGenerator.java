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
package io.micronaut.pyronaut.config.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates a JSON schema for {@code pyproject.toml} with a strict {@code [tool.pyronaut]} subtree.
 */
public final class PyprojectJsonSchemaGenerator {
    public static final String SCHEMA_FILE_NAME = "pyronaut-pyproject.schema.json";

    public String generate() {
        ObjectSchema root = objectSchema(true);
        root.put("$schema", "http://json-schema.org/draft-07/schema#");
        root.put("title", "Pyronaut pyproject.toml schema");
        root.put("description", "Schema for Pyronaut configuration under [tool.pyronaut] in pyproject.toml.");

        for (PyprojectConfigSpec.FieldSpec field : PyprojectConfigSpec.FIELDS) {
            insertField(root, field.canonicalPath(), fieldSchema(field, false));
            for (String aliasPath : field.aliasPaths()) {
                insertField(root, aliasPath, fieldSchema(field, true));
            }
        }
        for (PyprojectConfigSpec.SectionSpec section : PyprojectConfigSpec.STRICT_SECTIONS) {
            markSection(root, section.canonicalPath(), section.strict());
            for (String aliasPath : section.aliasPaths()) {
                markSection(root, aliasPath, section.strict());
            }
        }
        ensureObject(root, "project");
        ensureObject(root, "build-system");
        ensureObject(root, "tool");

        return JsonWriter.write(root.values);
    }

    public Path write(Path schemaFile) throws IOException {
        Files.createDirectories(schemaFile.getParent());
        Files.writeString(schemaFile, generate(), StandardCharsets.UTF_8);
        return schemaFile;
    }

    private static Object fieldSchema(PyprojectConfigSpec.FieldSpec field, boolean alias) {
        LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        switch (field.type()) {
            case STRING -> schema.put("type", "string");
            case BOOLEAN -> schema.put("type", "boolean");
            case INTEGER -> schema.put("type", "integer");
            case STRING_ARRAY -> {
                schema.put("type", "array");
                schema.put("items", Map.of("type", "string"));
            }
            case STRING_MAP -> {
                schema.put("type", "object");
                schema.put("additionalProperties", Map.of("type", "string"));
            }
            default -> throw new IllegalArgumentException("Unsupported field type: " + field.type());
        }
        if (field.description() != null && !field.description().isBlank()) {
            String description = field.description();
            if (alias) {
                description = description + " Deprecated alias for '" + leafName(field.canonicalPath()) + "'.";
            }
            schema.put("description", description);
        }
        if (!field.enumValues().isEmpty()) {
            schema.put("enum", field.enumValues());
        }
        if (field.defaultValue() != null) {
            schema.put("default", field.defaultValue());
        }
        if (alias) {
            schema.put("deprecated", Boolean.TRUE);
            schema.put("x-taplo", Map.of("hidden", Boolean.TRUE));
        }
        return schema;
    }

    private static void insertField(ObjectSchema root, String path, Object leafSchema) {
        String[] segments = path.split("\\.");
        ObjectSchema current = root;
        for (int i = 0; i < segments.length - 1; i++) {
            current = current.child(segments[i]);
        }
        current.properties().putIfAbsent(segments[segments.length - 1], leafSchema);
    }

    private static void markSection(ObjectSchema root, String path, boolean strict) {
        ensureObject(root, path).setAdditionalProperties(strict ? Boolean.FALSE : Boolean.TRUE);
    }

    private static ObjectSchema ensureObject(ObjectSchema root, String path) {
        String[] segments = path.split("\\.");
        ObjectSchema current = root;
        for (String segment : segments) {
            current = current.child(segment);
        }
        return current;
    }

    private static ObjectSchema objectSchema(boolean additionalProperties) {
        ObjectSchema schema = new ObjectSchema();
        schema.setAdditionalProperties(Boolean.valueOf(additionalProperties));
        return schema;
    }

    private static String leafName(String path) {
        int index = path.lastIndexOf('.');
        return index >= 0 ? path.substring(index + 1) : path;
    }

    private static final class ObjectSchema {
        private final LinkedHashMap<String, Object> values;

        private ObjectSchema() {
            this.values = new LinkedHashMap<>();
            values.put("type", "object");
            values.put("properties", new LinkedHashMap<String, Object>());
        }

        private ObjectSchema(LinkedHashMap<String, Object> values) {
            this.values = values;
            this.values.putIfAbsent("type", "object");
            this.values.putIfAbsent("properties", new LinkedHashMap<String, Object>());
        }

        @SuppressWarnings("unchecked")
        private LinkedHashMap<String, Object> properties() {
            return (LinkedHashMap<String, Object>) values.get("properties");
        }

        private ObjectSchema child(String name) {
            Object existing = properties().get(name);
            if (existing instanceof LinkedHashMap<?, ?> existingMap) {
                return new ObjectSchema((LinkedHashMap<String, Object>) existingMap);
            }
            ObjectSchema child = objectSchema(true);
            properties().put(name, child.values);
            return child;
        }

        private void setAdditionalProperties(Object value) {
            values.put("additionalProperties", value);
        }

        private void put(String key, Object value) {
            values.put(key, value);
        }
    }

    private static final class JsonWriter {
        private JsonWriter() {
        }

        static String write(Object value) {
            StringBuilder builder = new StringBuilder();
            append(builder, value, 0);
            builder.append('\n');
            return builder.toString();
        }

        @SuppressWarnings("unchecked")
        private static void append(StringBuilder builder, Object value, int indent) {
            if (value == null) {
                builder.append("null");
                return;
            }
            if (value instanceof String stringValue) {
                builder.append('"').append(escape(stringValue)).append('"');
                return;
            }
            if (value instanceof Number || value instanceof Boolean) {
                builder.append(value);
                return;
            }
            if (value instanceof Map<?, ?> map) {
                builder.append("{\n");
                List<Map.Entry<String, Object>> entries = new ArrayList<>(((Map<String, Object>) map).entrySet());
                for (int i = 0; i < entries.size(); i++) {
                    Map.Entry<String, Object> entry = entries.get(i);
                    indent(builder, indent + 2);
                    builder.append('"').append(escape(entry.getKey())).append("\": ");
                    append(builder, entry.getValue(), indent + 2);
                    if (i + 1 < entries.size()) {
                        builder.append(',');
                    }
                    builder.append('\n');
                }
                indent(builder, indent);
                builder.append('}');
                return;
            }
            if (value instanceof List<?> list) {
                builder.append("[");
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        builder.append(", ");
                    }
                    append(builder, list.get(i), indent);
                }
                builder.append("]");
                return;
            }
            throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass());
        }

        private static void indent(StringBuilder builder, int indent) {
            builder.append(" ".repeat(Math.max(0, indent)));
        }

        private static String escape(String value) {
            StringBuilder escaped = new StringBuilder(value.length());
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                switch (ch) {
                    case '\\' -> escaped.append("\\\\");
                    case '"' -> escaped.append("\\\"");
                    case '\n' -> escaped.append("\\n");
                    case '\r' -> escaped.append("\\r");
                    case '\t' -> escaped.append("\\t");
                    default -> escaped.append(ch);
                }
            }
            return escaped.toString();
        }
    }
}

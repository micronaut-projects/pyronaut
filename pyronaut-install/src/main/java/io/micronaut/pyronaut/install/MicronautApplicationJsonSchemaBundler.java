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
package io.micronaut.pyronaut.install;

import io.micronaut.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipException;

/**
 * Bundles Micronaut configuration schemas discovered on the runtime classpath
 * into a single application schema rooted by {@code x-micronaut.prefix}.
 */
final class MicronautApplicationJsonSchemaBundler {
    static final String SCHEMA_FILE_NAME = "micronaut-application.schema.json";
    static final String STATE_FILE_NAME = "micronaut-application.schema.state";

    private static final String SCHEMA_RESOURCE_PREFIX = "META-INF/micronaut-configuration-schemas/";
    private static final String ROOT_SCHEMA = "https://json-schema.org/draft/2020-12/schema";

    private final JsonMapper jsonMapper;

    MicronautApplicationJsonSchemaBundler() {
        this(JsonMapper.createDefault());
    }

    MicronautApplicationJsonSchemaBundler(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    SchemaWriteResult writeFromManifest(Path manifestFile, Path schemaFile) throws IOException {
        if (!Files.exists(manifestFile)) {
            return SchemaWriteResult.none();
        }
        return write(Files.readAllLines(manifestFile, StandardCharsets.UTF_8), schemaFile);
    }

    SchemaWriteResult write(List<String> runtimeClasspath, Path schemaFile) throws IOException {
        List<String> normalizedClasspath = runtimeClasspath.stream()
            .filter(entry -> entry != null && !entry.isBlank() && entry.endsWith(".jar"))
            .distinct()
            .sorted()
            .toList();
        String classpathHash = hashClasspath(normalizedClasspath);
        Path stateFile = schemaFile.resolveSibling(STATE_FILE_NAME);
        String presentState = "present:" + classpathHash;
        String emptyState = "empty:" + classpathHash;
        if (Files.exists(stateFile)) {
            String existingState = Files.readString(stateFile, StandardCharsets.UTF_8).trim();
            if (existingState.equals(presentState) && Files.exists(schemaFile)) {
                return SchemaWriteResult.cached();
            }
            if (existingState.equals(emptyState)) {
                return SchemaWriteResult.none();
            }
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("$schema", ROOT_SCHEMA);
        root.put("title", "Micronaut application configuration schema");
        root.put("type", "object");
        root.put("properties", new LinkedHashMap<String, Object>());

        int merged = 0;
        for (String entry : new TreeSet<>(normalizedClasspath)) {
            Path jar = Path.of(entry);
            if (!Files.isRegularFile(jar)) {
                continue;
            }
            try {
                merged += mergeJarSchemas(root, jar);
            } catch (ZipException ignored) {
                // Not every test fixture jar is a real zip; ignore classpath entries that
                // do not expose Micronaut schema resources.
            }
        }
        if (merged == 0) {
            Files.deleteIfExists(schemaFile);
            Files.createDirectories(stateFile.getParent());
            Files.writeString(stateFile, emptyState, StandardCharsets.UTF_8);
            return SchemaWriteResult.none();
        }
        Files.createDirectories(schemaFile.getParent());
        Files.writeString(schemaFile, jsonMapper.writeValueAsString(root), StandardCharsets.UTF_8);
        Files.writeString(stateFile, presentState, StandardCharsets.UTF_8);
        return SchemaWriteResult.generated(merged);
    }

    private static String hashClasspath(List<String> runtimeClasspath) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String entry : runtimeClasspath) {
                digest.update(entry.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 algorithm is unavailable", e);
        }
    }

    @SuppressWarnings("unchecked")
    private int mergeJarSchemas(Map<String, Object> root, Path jarFile) throws IOException {
        int merged = 0;
        try (ZipFile zipFile = new ZipFile(jarFile.toFile())) {
            List<String> entries = zipFile.stream()
                .map(ZipEntry::getName)
                .filter(name -> name.startsWith(SCHEMA_RESOURCE_PREFIX) && name.endsWith(".json"))
                .sorted()
                .toList();
            for (String entryName : entries) {
                ZipEntry entry = zipFile.getEntry(entryName);
                if (entry == null) {
                    continue;
                }
                try (InputStream inputStream = zipFile.getInputStream(entry)) {
                    Map<String, Object> schema = jsonMapper.readValue(inputStream, Map.class);
                    String prefix = readPrefix(schema);
                    if (prefix == null || prefix.isBlank()) {
                        continue;
                    }
                    Map<String, Object> normalized = normalizeSchema(schema);
                    mergeIntoPath((Map<String, Object>) root.get("properties"), prefix, normalized);
                    merged++;
                }
            }
        }
        return merged;
    }

    @SuppressWarnings("unchecked")
    private static String readPrefix(Map<String, Object> schema) {
        Object micronaut = schema.get("x-micronaut");
        if (micronaut instanceof Map<?, ?> values) {
            Object prefix = values.get("prefix");
            if (prefix instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeSchema(Map<String, Object> schema) {
        Map<String, Object> copy = deepCopyMap(schema);
        Object defsObject = copy.remove("$defs");
        Map<String, Object> defs = defsObject instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        copy.remove("$schema");
        copy.remove("$id");
        return inlineLocalRefs(copy, defs);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> inlineLocalRefs(Map<String, Object> node, Map<String, Object> defs) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            resolved.put(entry.getKey(), inlineValue(entry.getValue(), defs));
        }
        return resolved;
    }

    @SuppressWarnings("unchecked")
    private static Object inlineValue(Object value, Map<String, Object> defs) {
        if (value instanceof Map<?, ?> mapValue) {
            Object ref = mapValue.get("$ref");
            if (ref instanceof String refValue && refValue.startsWith("#/$defs/")) {
                String name = refValue.substring("#/$defs/".length());
                Object referenced = defs.get(name);
                if (referenced instanceof Map<?, ?> referencedMap) {
                    return inlineLocalRefs((Map<String, Object>) deepCopyMap((Map<String, Object>) referencedMap), defs);
                }
                return referenced;
            }
            return inlineLocalRefs((Map<String, Object>) deepCopyMap((Map<String, Object>) mapValue), defs);
        }
        if (value instanceof List<?> list) {
            List<Object> resolved = new ArrayList<>(list.size());
            for (Object element : list) {
                resolved.add(inlineValue(element, defs));
            }
            return resolved;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static void mergeIntoPath(Map<String, Object> rootProperties, String prefix, Map<String, Object> schema) {
        String[] segments = prefix.split("\\.");
        Map<String, Object> properties = rootProperties;
        for (int i = 0; i < segments.length - 1; i++) {
            Map<String, Object> segmentSchema = ensureObjectSchema(properties, segments[i]);
            properties = ensureProperties(segmentSchema);
        }
        String leaf = segments[segments.length - 1];
        Map<String, Object> existing = (Map<String, Object>) properties.get(leaf);
        if (existing == null) {
            properties.put(leaf, schema);
            return;
        }
        mergeSchema(existing, schema);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ensureObjectSchema(Map<String, Object> properties, String key) {
        Object existing = properties.get(key);
        if (existing instanceof Map<?, ?> map) {
            Map<String, Object> schema = (Map<String, Object>) map;
            schema.putIfAbsent("type", "object");
            ensureProperties(schema);
            return schema;
        }
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("type", "object");
        created.put("properties", new LinkedHashMap<String, Object>());
        properties.put(key, created);
        return created;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ensureProperties(Map<String, Object> schema) {
        Object properties = schema.get("properties");
        if (properties instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        Map<String, Object> created = new LinkedHashMap<>();
        schema.put("properties", created);
        return created;
    }

    @SuppressWarnings("unchecked")
    private static void mergeSchema(Map<String, Object> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object sourceValue = entry.getValue();
            Object targetValue = target.get(key);
            if (targetValue == null) {
                target.put(key, deepCopyValue(sourceValue));
                continue;
            }
            if ("properties".equals(key) && targetValue instanceof Map<?, ?> targetMap && sourceValue instanceof Map<?, ?> sourceMap) {
                mergeProperties((Map<String, Object>) targetMap, (Map<String, Object>) sourceMap);
                continue;
            }
            if ("additionalProperties".equals(key) && targetValue instanceof Map<?, ?> targetMap && sourceValue instanceof Map<?, ?> sourceMap) {
                mergeSchema((Map<String, Object>) targetMap, (Map<String, Object>) sourceMap);
                continue;
            }
            if ("items".equals(key) && targetValue instanceof Map<?, ?> targetMap && sourceValue instanceof Map<?, ?> sourceMap) {
                mergeSchema((Map<String, Object>) targetMap, (Map<String, Object>) sourceMap);
                continue;
            }
            if (targetValue instanceof Map<?, ?> targetMap && sourceValue instanceof Map<?, ?> sourceMap) {
                mergeSchema((Map<String, Object>) targetMap, (Map<String, Object>) sourceMap);
                continue;
            }
            if (targetValue instanceof List<?> targetList && sourceValue instanceof List<?> sourceList) {
                target.put(key, mergeLists(targetList, sourceList));
                continue;
            }
            if (Objects.equals(targetValue, sourceValue)) {
                continue;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void mergeProperties(Map<String, Object> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object existing = target.get(entry.getKey());
            if (existing instanceof Map<?, ?> existingMap && entry.getValue() instanceof Map<?, ?> newMap) {
                mergeSchema((Map<String, Object>) existingMap, (Map<String, Object>) newMap);
            } else if (existing == null) {
                target.put(entry.getKey(), deepCopyValue(entry.getValue()));
            }
        }
    }

    private static List<Object> mergeLists(List<?> target, List<?> source) {
        LinkedHashSet<Object> merged = new LinkedHashSet<>();
        for (Object element : target) {
            merged.add(deepCopyValue(element));
        }
        for (Object element : source) {
            merged.add(deepCopyValue(element));
        }
        return new ArrayList<>(merged);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopyMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            copy.put(entry.getKey(), deepCopyValue(entry.getValue()));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return deepCopyMap((Map<String, Object>) map);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> copy = new ArrayList<>(collection.size());
            for (Object element : collection) {
                copy.add(deepCopyValue(element));
            }
            return copy;
        }
        return value;
    }

    record SchemaWriteResult(Status status, int mergedSchemas) {
        static SchemaWriteResult generated(int mergedSchemas) {
            return new SchemaWriteResult(Status.GENERATED, mergedSchemas);
        }

        static SchemaWriteResult cached() {
            return new SchemaWriteResult(Status.CACHED, 0);
        }

        static SchemaWriteResult none() {
            return new SchemaWriteResult(Status.NONE, 0);
        }

        boolean available() {
            return status != Status.NONE;
        }
    }

    enum Status {
        GENERATED,
        CACHED,
        NONE
    }
}

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stores editor-oriented artifact metadata for stub generation.
 */
final class EditorArtifactManifest {
    static final String FILE_NAME = "resolved-editor-artifacts.json";

    private final JsonMapper jsonMapper;

    EditorArtifactManifest() {
        this(JsonMapper.createDefault());
    }

    EditorArtifactManifest(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    void write(Path cacheDir, List<Entry> entries) throws IOException {
        Files.createDirectories(cacheDir);
        List<Map<String, Object>> serializedEntries = entries.stream()
            .sorted(Comparator.comparing(Entry::scope).thenComparing(Entry::binaryJar))
            .map(EditorArtifactManifest::toMap)
            .toList();
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("artifacts", serializedEntries);
        Files.writeString(
            cacheDir.resolve(FILE_NAME),
            jsonMapper.writeValueAsString(manifest),
            StandardCharsets.UTF_8
        );
    }

    List<Entry> read(Path cacheDir) throws IOException {
        Path manifestFile = cacheDir.resolve(FILE_NAME);
        if (!Files.exists(manifestFile)) {
            return List.of();
        }
        try (InputStream inputStream = Files.newInputStream(manifestFile)) {
            Object parsed = jsonMapper.readValue(inputStream, Map.class);
            if (!(parsed instanceof Map<?, ?> manifest)) {
                return List.of();
            }
            Object artifacts = manifest.get("artifacts");
            if (!(artifacts instanceof List<?> artifactList)) {
                return List.of();
            }
            List<Entry> entries = new ArrayList<>();
            for (Object artifact : artifactList) {
                Entry entry = fromObject(artifact);
                if (entry != null) {
                    entries.add(entry);
                }
            }
            return List.copyOf(entries);
        } catch (Exception e) {
            return List.of();
        }
    }

    List<Entry> entriesForClasspath(InstallScope scope, List<String> classpath) {
        if (classpath == null || classpath.isEmpty()) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        for (String entry : classpath) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            Path binaryJar = Path.of(entry).toAbsolutePath().normalize();
            if (!Files.isRegularFile(binaryJar) || !binaryJar.getFileName().toString().endsWith(".jar")) {
                continue;
            }
            entries.add(new Entry(
                scope.cliValue(),
                null,
                null,
                null,
                binaryJar.toString(),
                probeSourceJar(binaryJar)
            ));
        }
        return List.copyOf(entries);
    }

    List<Entry> entriesForResolvedArtifacts(InstallScope scope, List<MavenClasspathResolver.ResolvedEditorArtifact> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        for (MavenClasspathResolver.ResolvedEditorArtifact artifact : artifacts) {
            if (artifact == null || artifact.binaryJar() == null || !Files.isRegularFile(artifact.binaryJar())) {
                continue;
            }
            Path sourcePath = artifact.sourceJar();
            entries.add(new Entry(
                scope.cliValue(),
                artifact.groupId(),
                artifact.artifactId(),
                artifact.version(),
                artifact.binaryJar().toAbsolutePath().normalize().toString(),
                sourcePath != null && Files.isRegularFile(sourcePath)
                    ? sourcePath.toAbsolutePath().normalize().toString()
                    : null
            ));
        }
        return List.copyOf(entries);
    }

    private static String probeSourceJar(Path binaryJar) {
        String fileName = binaryJar.getFileName().toString();
        if (!fileName.endsWith(".jar") || fileName.endsWith("-sources.jar")) {
            return null;
        }
        Path sourceJar = binaryJar.resolveSibling(fileName.substring(0, fileName.length() - 4) + "-sources.jar");
        return Files.isRegularFile(sourceJar) ? sourceJar.toAbsolutePath().normalize().toString() : null;
    }

    private static Map<String, Object> toMap(Entry entry) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("scope", entry.scope());
        if (entry.groupId() != null && !entry.groupId().isBlank()) {
            values.put("groupId", entry.groupId());
        }
        if (entry.artifactId() != null && !entry.artifactId().isBlank()) {
            values.put("artifactId", entry.artifactId());
        }
        if (entry.version() != null && !entry.version().isBlank()) {
            values.put("version", entry.version());
        }
        values.put("binaryJar", entry.binaryJar());
        if (entry.sourceJar() != null && !entry.sourceJar().isBlank()) {
            values.put("sourceJar", entry.sourceJar());
        }
        return values;
    }

    private static Entry fromObject(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Object scope = map.get("scope");
        Object binaryJar = map.get("binaryJar");
        if (!(scope instanceof String scopeValue) || !(binaryJar instanceof String binaryJarValue)) {
            return null;
        }
        Object groupId = map.get("groupId");
        Object artifactId = map.get("artifactId");
        Object version = map.get("version");
        Object sourceJar = map.get("sourceJar");
        String sourceJarValue = sourceJar instanceof String text && !text.isBlank() ? text : null;
        return new Entry(
            scopeValue,
            groupId instanceof String text && !text.isBlank() ? text : null,
            artifactId instanceof String text && !text.isBlank() ? text : null,
            version instanceof String text && !text.isBlank() ? text : null,
            binaryJarValue,
            sourceJarValue
        );
    }

    record Entry(String scope, String groupId, String artifactId, String version, String binaryJar, String sourceJar) {
        Path binaryPath() {
            return Path.of(binaryJar).toAbsolutePath().normalize();
        }

        Path sourcePath() {
            if (sourceJar == null || sourceJar.isBlank()) {
                return null;
            }
            return Path.of(sourceJar).toAbsolutePath().normalize();
        }

        boolean matchesScope(InstallScope installScope) {
            return Objects.equals(scope, installScope.cliValue());
        }
    }
}

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the Java project model consumed by the VS Code Java language server.
 */
final class VsCodeJavaSettingsWriter {
    private static final String SOURCE_PATHS = "java.project.sourcePaths";
    private static final String REFERENCED_LIBRARIES = "java.project.referencedLibraries";

    private final JsonMapper jsonMapper;

    VsCodeJavaSettingsWriter() {
        this(JsonMapper.createDefault());
    }

    VsCodeJavaSettingsWriter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    void ensureWritten(Path projectDir,
                       List<Path> sourceRoots,
                       List<JavaEditorSupport.Library> libraries) throws IOException {
        Path settingsFile = projectDir.resolve(VsCodeSettingsWriter.VSCODE_DIR)
            .resolve(VsCodeSettingsWriter.SETTINGS_FILE);
        Map<String, Object> settings = readSettings(settingsFile);
        if (settings == null) {
            return;
        }
        settings.put(SOURCE_PATHS, sourceRoots.stream()
            .map(path -> editorPath(projectDir, path))
            .distinct()
            .sorted()
            .toList());
        settings.put(REFERENCED_LIBRARIES, libraries.stream()
            .map(JavaEditorSupport.Library::binary)
            .map(Path::toAbsolutePath)
            .map(Path::normalize)
            .map(Path::toString)
            .map(path -> path.replace('\\', '/'))
            .distinct()
            .sorted()
            .toList());
        String content = jsonMapper.writeValueAsString(settings);
        if (!Files.isRegularFile(settingsFile) || !Files.readString(settingsFile).equals(content)) {
            Files.createDirectories(settingsFile.getParent());
            Files.writeString(settingsFile, content, StandardCharsets.UTF_8);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readSettings(Path settingsFile) throws IOException {
        if (!Files.exists(settingsFile)) {
            return new LinkedHashMap<>();
        }
        try (var input = Files.newInputStream(settingsFile)) {
            Object parsed = jsonMapper.readValue(input, Map.class);
            if (parsed instanceof Map<?, ?> map) {
                return new LinkedHashMap<>((Map<String, Object>) map);
            }
            System.err.println("Warning: skipped VS Code Java settings update because settings.json is not a JSON object.");
        } catch (Exception e) {
            System.err.println("Warning: skipped VS Code Java settings update because settings.json could not be parsed: "
                + e.getMessage());
        }
        return null;
    }

    private static String editorPath(Path projectDir, Path path) {
        Path normalizedProject = projectDir.toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(normalizedProject)) {
            String relative = normalizedProject.relativize(normalized).toString().replace('\\', '/');
            return relative.isEmpty() ? "." : relative;
        }
        return normalized.toString();
    }
}

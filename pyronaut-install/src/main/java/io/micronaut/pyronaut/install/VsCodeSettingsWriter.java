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
import io.micronaut.pyronaut.config.model.PyprojectModelReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes VS Code settings needed for Pyright/Pylance import resolution.
 */
final class VsCodeSettingsWriter implements EditorSettingsWriter {
    static final String VSCODE_DIR = ".vscode";
    static final String SETTINGS_FILE = "settings.json";

    private static final String PYRIGHT_CONFIG_FILE = "pyrightconfig.json";
    private static final String TOOL_PYRIGHT_MARKER = "[tool.pyright]";
    private static final String EXTRA_PATHS_KEY = "python.analysis.extraPaths";

    private final JsonMapper jsonMapper;

    VsCodeSettingsWriter() {
        this(JsonMapper.createDefault());
    }

    VsCodeSettingsWriter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public SettingsResult ensureConfigured(Path projectDir, String stubPath) throws IOException {
        List<PythonIdeStubGenerator.WarningDetail> warnings = new ArrayList<>();
        if (Files.exists(projectDir.resolve(PYRIGHT_CONFIG_FILE))) {
            warnings.add(PythonIdeStubGenerator.WarningDetail.of(
                "Skipped VS Code Python extra path update because pyrightconfig.json is already present."
            ));
            return new SettingsResult(SettingsStatus.SKIPPED_USER_CONFIG, warnings);
        }
        Path pyprojectFile = projectDir.resolve(PyprojectModelReader.FILE_NAME);
        if (Files.exists(pyprojectFile)) {
            String pyproject = Files.readString(pyprojectFile, StandardCharsets.UTF_8);
            if (pyproject.contains(TOOL_PYRIGHT_MARKER)) {
                warnings.add(PythonIdeStubGenerator.WarningDetail.of(
                    "Skipped VS Code Python extra path update because [tool.pyright] is already present in pyproject.toml."
                ));
                return new SettingsResult(SettingsStatus.SKIPPED_USER_CONFIG, warnings);
            }
        }

        Path settingsFile = projectDir.resolve(VSCODE_DIR).resolve(SETTINGS_FILE);
        Map<String, Object> settings = readSettings(settingsFile, warnings);
        if (settings == null) {
            return new SettingsResult(SettingsStatus.UNCHANGED, warnings);
        }
        Object current = settings.get(EXTRA_PATHS_KEY);
        List<String> extraPaths = new ArrayList<>();
        if (current instanceof List<?> list) {
            for (Object value : list) {
                if (value instanceof String text && !text.isBlank()) {
                    extraPaths.add(text);
                }
            }
        } else if (current instanceof String text && !text.isBlank()) {
            extraPaths.add(text);
        } else if (current != null) {
            warnings.add(PythonIdeStubGenerator.WarningDetail.of(
                "Skipped VS Code Python extra path update because python.analysis.extraPaths is not a string or list."
            ));
            return new SettingsResult(SettingsStatus.UNCHANGED, warnings);
        }
        if (!extraPaths.contains(stubPath)) {
            extraPaths.add(stubPath);
        } else if (Files.exists(settingsFile)) {
            return new SettingsResult(SettingsStatus.UNCHANGED, warnings);
        }
        settings.put(EXTRA_PATHS_KEY, extraPaths);
        Files.createDirectories(settingsFile.getParent());
        Files.writeString(settingsFile, jsonMapper.writeValueAsString(settings), StandardCharsets.UTF_8);
        return new SettingsResult(SettingsStatus.UPDATED, warnings);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readSettings(Path settingsFile,
                                             List<PythonIdeStubGenerator.WarningDetail> warnings) throws IOException {
        if (!Files.exists(settingsFile)) {
            return new LinkedHashMap<>();
        }
        try {
            Object parsed = jsonMapper.readValue(Files.newInputStream(settingsFile), Map.class);
            if (parsed instanceof Map<?, ?> map) {
                return new LinkedHashMap<>((Map<String, Object>) map);
            }
            warnings.add(PythonIdeStubGenerator.WarningDetail.of(
                "Skipped VS Code settings update because settings.json is not a JSON object."
            ));
        } catch (Exception e) {
            warnings.add(PythonIdeStubGenerator.WarningDetail.fromThrowable(
                "Skipped VS Code settings update because settings.json could not be parsed.",
                e
            ));
        }
        return null;
    }

}

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes VS Code launch configurations that delegate Java execution to Pyronaut.
 */
final class VsCodeLaunchWriter {
    static final String LAUNCH_FILE = "launch.json";
    static final String RUN_NAME = "Pyronaut: Run Direct Sources";
    static final String TEST_NAME = "Pyronaut: Test Direct Sources";
    private static final Set<String> MANAGED_NAMES = Set.of(RUN_NAME, TEST_NAME);

    private final JsonMapper jsonMapper;

    VsCodeLaunchWriter() {
        this(JsonMapper.createDefault());
    }

    VsCodeLaunchWriter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    void ensureWritten(Path projectDir, List<Path> sourceFiles) throws IOException {
        Path launchFile = projectDir.resolve(VsCodeSettingsWriter.VSCODE_DIR).resolve(LAUNCH_FILE);
        Map<String, Object> launch = readLaunch(launchFile);
        if (launch == null) {
            return;
        }
        List<Object> configurations = configurations(launch);
        if (configurations == null) {
            return;
        }
        configurations.removeIf(VsCodeLaunchWriter::isManaged);
        DirectSourceLaunchCommand.Commands commands = DirectSourceLaunchCommand.build(projectDir, sourceFiles);
        configurations.add(configuration(
            RUN_NAME,
            commands.development()
        ));
        if (commands.test() != null) {
            configurations.add(configuration(TEST_NAME, commands.test()));
        }
        launch.putIfAbsent("version", "0.2.0");
        launch.put("configurations", configurations);
        String content = jsonMapper.writeValueAsString(launch);
        if (!Files.isRegularFile(launchFile) || !Files.readString(launchFile).equals(content)) {
            Files.createDirectories(launchFile.getParent());
            Files.writeString(launchFile, content, StandardCharsets.UTF_8);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readLaunch(Path launchFile) throws IOException {
        if (!Files.exists(launchFile)) {
            return new LinkedHashMap<>();
        }
        try (var input = Files.newInputStream(launchFile)) {
            Object parsed = jsonMapper.readValue(input, Map.class);
            if (parsed instanceof Map<?, ?> map) {
                return new LinkedHashMap<>((Map<String, Object>) map);
            }
            System.err.println("Warning: skipped VS Code launch update because launch.json is not a JSON object.");
        } catch (Exception e) {
            System.err.println("Warning: skipped VS Code launch update because launch.json could not be parsed: "
                + e.getMessage());
        }
        return null;
    }

    private static List<Object> configurations(Map<String, Object> launch) {
        Object current = launch.get("configurations");
        if (current == null) {
            return new ArrayList<>();
        }
        if (current instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        System.err.println("Warning: skipped VS Code launch update because configurations is not a JSON array.");
        return null;
    }

    private static boolean isManaged(Object candidate) {
        return candidate instanceof Map<?, ?> map && MANAGED_NAMES.contains(map.get("name"));
    }

    private static Map<String, Object> configuration(String name, String command) {
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("name", name);
        configuration.put("type", "node-terminal");
        configuration.put("request", "launch");
        configuration.put("command", command);
        configuration.put("cwd", "${workspaceFolder}");
        return configuration;
    }
}

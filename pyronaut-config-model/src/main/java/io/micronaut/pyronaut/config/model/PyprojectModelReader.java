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

import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlInvalidTypeException;
import org.tomlj.TomlParseError;
import org.tomlj.TomlParseResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reader that validates and maps {@code pyproject.toml} into {@link PyprojectModel}.
 */
public final class PyprojectModelReader {
    public static final String FILE_NAME = "pyproject.toml";

    public PyprojectModel readProjectDirectory(Path projectDirectory) {
        return readFile(projectDirectory.resolve(FILE_NAME));
    }

    public PyprojectModel readFile(Path file) {
        validateFileName(file);
        if (!Files.exists(file)) {
            throw new PyprojectModelException("Missing required file 'pyproject.toml' at: " + file);
        }
        TomlParseResult parsed;
        try {
            parsed = Toml.parse(file);
        } catch (IOException e) {
            throw new PyprojectModelException("Failed reading pyproject.toml: " + file, e);
        }
        failOnParseErrors(file, parsed.errors());
        return map(parsed);
    }

    private static void validateFileName(Path file) {
        if (!FILE_NAME.equals(file.getFileName().toString())) {
            throw new PyprojectModelException("Invalid config filename '" + file.getFileName() + "'. Expected 'pyproject.toml'");
        }
    }

    private static void failOnParseErrors(Path file, List<TomlParseError> errors) {
        if (!errors.isEmpty()) {
            TomlParseError first = errors.get(0);
            String message = "Invalid TOML in " + file + " at line " + first.position().line() + ", column "
                + first.position().column() + ": " + first.getMessage();
            throw new PyprojectModelException(message);
        }
    }

    private static PyprojectModel map(TomlParseResult parsed) {
        PyprojectModel.Project project = new PyprojectModel.Project(
            readString(parsed, "project.name"),
            readString(parsed, "project.version"),
            readStringList(parsed, "project.dynamic")
        );

        PyprojectModel.BuildSystem buildSystem = new PyprojectModel.BuildSystem(
            readStringList(parsed, "build-system.requires"),
            readString(parsed, "build-system.build-backend")
        );

        List<String> runtime = readStringList(parsed, "tool.pyronaut.dependencies.runtime");
        if (runtime.isEmpty()) {
            runtime = readStringList(parsed, "tool.pyronaut.dependencies.compile");
        }

        List<String> build = readStringList(parsed, "tool.pyronaut.dependencies.build");
        if (build.isEmpty()) {
            build = readStringList(parsed, "tool.pyronaut.dependencies.annotationProcessor");
        }
        if (build.isEmpty()) {
            build = readStringList(parsed, "tool.pyronaut.annotationProcessor");
        }

        PyprojectModel.Pyronaut pyronaut = new PyprojectModel.Pyronaut(
            readString(parsed, "tool.pyronaut.version"),
            readStringList(parsed, "tool.pyronaut.repositories"),
            new PyprojectModel.Dependencies(
                runtime,
                build,
                readStringList(parsed, "tool.pyronaut.dependencies.test")
            ),
            new PyprojectModel.Build(resolveBuildMode(parsed), resolveBuildMetadata(parsed)),
            resolveValidation(parsed)
        );

        return new PyprojectModel(project, buildSystem, pyronaut);
    }

    private static String readString(TomlParseResult parsed, String key) {
        try {
            return parsed.getString(key);
        } catch (TomlInvalidTypeException e) {
            throw invalidType(key, "string", e);
        }
    }

    private static List<String> readStringList(TomlParseResult parsed, String key) {
        TomlArray array;
        try {
            array = parsed.getArray(key);
        } catch (TomlInvalidTypeException e) {
            throw invalidType(key, "array", e);
        }
        if (array == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            Object value = array.get(i);
            if (!(value instanceof String stringValue)) {
                throw new PyprojectModelException("Invalid type for '" + key + "[" + i + "]': expected string");
            }
            values.add(stringValue);
        }
        return List.copyOf(values);
    }

    private static PyprojectModelException invalidType(String key, String expected, TomlInvalidTypeException e) {
        return new PyprojectModelException("Invalid type for '" + key + "': expected " + expected, e);
    }

    private static String resolveBuildMode(TomlParseResult parsed) {
        String mode = readString(parsed, "tool.pyronaut.build.mode");
        if (mode == null || mode.isBlank()) {
            return "jvm";
        }
        String normalized = mode.trim().toLowerCase();
        if ("jvm".equals(normalized) || "native".equals(normalized)) {
            return normalized;
        }
        throw new PyprojectModelException("Invalid value for 'tool.pyronaut.build.mode': expected 'jvm' or 'native'");
    }

    private static PyprojectModel.Metadata resolveBuildMetadata(TomlParseResult parsed) {
        Boolean enabled;
        try {
            enabled = parsed.getBoolean("tool.pyronaut.build.metadata.enabled");
        } catch (TomlInvalidTypeException e) {
            throw invalidType("tool.pyronaut.build.metadata.enabled", "boolean", e);
        }

        String version = readString(parsed, "tool.pyronaut.build.metadata.version");
        String repositoryUrl = readString(parsed, "tool.pyronaut.build.metadata.repositoryUrl");
        List<String> excludedModules = readStringList(parsed, "tool.pyronaut.build.metadata.excludedModules");
        return new PyprojectModel.Metadata(enabled, version, repositoryUrl, excludedModules);
    }

    private static PyprojectModel.Validation resolveValidation(TomlParseResult parsed) {
        Boolean enabled = readBoolean(parsed, "tool.pyronaut.validation.enabled", true);
        Boolean failOnNotPresent = readBoolean(parsed, "tool.pyronaut.validation.failOnNotPresent", true);
        Boolean deduceEnvironments = readBoolean(parsed, "tool.pyronaut.validation.deduceEnvironments", false);
        Boolean validateDependencyInjection = readBoolean(parsed, "tool.pyronaut.validation.validateDependencyInjection", false);
        String diStrategy = readEnum(
            parsed,
            "tool.pyronaut.validation.dependencyInjectionValidationStrategy",
            List.of("reachable", "application-beans", "all-beans"),
            "reachable"
        );
        String format = readEnum(parsed, "tool.pyronaut.validation.format", List.of("json", "html", "both"), "both");
        List<String> suppressions = readStringList(parsed, "tool.pyronaut.validation.suppressions");
        List<String> suppressInjectErrors = readStringList(parsed, "tool.pyronaut.validation.suppressInjectErrors");
        String projectBaseDir = readString(parsed, "tool.pyronaut.validation.projectBaseDir");
        List<String> resourcesDirs = readStringList(parsed, "tool.pyronaut.validation.resourcesDirs");

        PyprojectModel.ValidationScenario run = resolveValidationScenario(
            parsed,
            "tool.pyronaut.validation.run",
            List.of("dev"),
            List.of("src/main/resources")
        );
        PyprojectModel.ValidationScenario test = resolveValidationScenario(
            parsed,
            "tool.pyronaut.validation.test",
            List.of("test"),
            List.of("src/main/resources", "src/test/resources")
        );
        PyprojectModel.ValidationScenario production = resolveValidationScenario(
            parsed,
            "tool.pyronaut.validation.production",
            List.of(),
            List.of("src/main/resources")
        );

        return new PyprojectModel.Validation(
            enabled,
            failOnNotPresent,
            deduceEnvironments,
            validateDependencyInjection,
            diStrategy,
            format,
            suppressions,
            suppressInjectErrors,
            projectBaseDir,
            resourcesDirs,
            run,
            test,
            production
        );
    }

    private static PyprojectModel.ValidationScenario resolveValidationScenario(
        TomlParseResult parsed,
        String prefix,
        List<String> defaultEnvironments,
        List<String> defaultResourcesDirs
    ) {
        Boolean enabled = readBoolean(parsed, prefix + ".enabled", true);
        List<String> environments = readStringList(parsed, prefix + ".environments");
        if (environments.isEmpty()) {
            environments = defaultEnvironments;
        }
        Boolean includeDefaultEnvironment = readBoolean(parsed, prefix + ".includeDefaultEnvironment", true);
        Boolean overrideClasspath = readBoolean(parsed, prefix + ".overrideClasspath", false);
        List<String> classpath = readStringList(parsed, prefix + ".classpath");
        List<String> additionalClasspath = readStringList(parsed, prefix + ".additionalClasspath");
        List<String> resourcesDirs = readStringList(parsed, prefix + ".resourcesDirs");
        if (resourcesDirs.isEmpty()) {
            resourcesDirs = defaultResourcesDirs;
        }
        String outputDir = readString(parsed, prefix + ".outputDir");
        return new PyprojectModel.ValidationScenario(
            enabled,
            environments,
            includeDefaultEnvironment,
            overrideClasspath,
            classpath,
            additionalClasspath,
            resourcesDirs,
            outputDir
        );
    }

    private static Boolean readBoolean(TomlParseResult parsed, String key, boolean defaultValue) {
        try {
            Boolean value = parsed.getBoolean(key);
            return value == null ? defaultValue : value;
        } catch (TomlInvalidTypeException e) {
            throw invalidType(key, "boolean", e);
        }
    }

    private static String readEnum(TomlParseResult parsed, String key, List<String> accepted, String defaultValue) {
        String raw = readString(parsed, key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        String normalized = raw.trim();
        if (!accepted.contains(normalized)) {
            throw new PyprojectModelException("Invalid value for '" + key + "': expected one of " + accepted);
        }
        return normalized;
    }
}

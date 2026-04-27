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
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reader that validates and maps {@code pyproject.toml} into {@link PyprojectModel}.
 */
public final class PyprojectModelReader {
    private static final List<String> DEFAULT_MAIN_VALIDATION_RESOURCE_DIRS = List.of("config", "src/main/resources");
    private static final List<String> DEFAULT_TEST_VALIDATION_RESOURCE_DIRS = List.of("config", "src/main/resources", "src/test/resources");

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
            readString(parsed, PyprojectConfigSpec.PROJECT_NAME),
            readString(parsed, PyprojectConfigSpec.PROJECT_VERSION),
            readStringList(parsed, PyprojectConfigSpec.PROJECT_DYNAMIC)
        );

        PyprojectModel.BuildSystem buildSystem = new PyprojectModel.BuildSystem(
            readStringList(parsed, PyprojectConfigSpec.BUILD_SYSTEM_REQUIRES),
            readString(parsed, PyprojectConfigSpec.BUILD_SYSTEM_BUILD_BACKEND)
        );

        List<String> runtime = readStringList(parsed, PyprojectConfigSpec.PYRONAUT_DEPENDENCIES_RUNTIME);
        List<String> build = readStringList(parsed, PyprojectConfigSpec.PYRONAUT_DEPENDENCIES_BUILD);

        PyprojectModel.Pyronaut pyronaut = new PyprojectModel.Pyronaut(
            readString(parsed, PyprojectConfigSpec.PYRONAUT_VERSION),
            readStringList(parsed, PyprojectConfigSpec.PYRONAUT_REPOSITORIES),
            new PyprojectModel.Dependencies(
                runtime,
                build,
                readStringList(parsed, PyprojectConfigSpec.PYRONAUT_DEPENDENCIES_TEST)
            ),
            new PyprojectModel.Build(resolveBuildMode(parsed), resolveBuildMetadata(parsed), resolveBuildDocker(parsed)),
            new PyprojectModel.Processor(resolveProcessorMode(parsed)),
            resolveIdeStubs(parsed),
            resolveValidation(parsed),
            resolveTestResources(parsed)
        );

        return new PyprojectModel(project, buildSystem, pyronaut);
    }

    private static String readString(TomlParseResult parsed, PyprojectConfigSpec.FieldSpec field) {
        for (String path : field.allPaths()) {
            try {
                String value = parsed.getString(path);
                if (value != null) {
                    return value;
                }
            } catch (TomlInvalidTypeException e) {
                throw invalidType(field.canonicalPath(), "string", e);
            }
        }
        if (field.defaultValue() instanceof String defaultValue) {
            return defaultValue;
        }
        return null;
    }

    private static List<String> readStringList(TomlParseResult parsed, PyprojectConfigSpec.FieldSpec field) {
        for (String path : field.allPaths()) {
            TomlArray array;
            try {
                array = parsed.getArray(path);
            } catch (TomlInvalidTypeException e) {
                throw invalidType(field.canonicalPath(), "array", e);
            }
            if (array == null) {
                continue;
            }
            List<String> values = new ArrayList<>(array.size());
            for (int i = 0; i < array.size(); i++) {
                Object value = array.get(i);
                if (!(value instanceof String stringValue)) {
                    throw new PyprojectModelException("Invalid type for '" + field.canonicalPath() + "[" + i + "]': expected string");
                }
                values.add(stringValue);
            }
            return List.copyOf(values);
        }
        if (field.defaultValue() instanceof List<?> defaultValues) {
            @SuppressWarnings("unchecked")
            List<String> strings = (List<String>) defaultValues;
            return strings;
        }
        return List.of();
    }

    private static PyprojectModelException invalidType(String key, String expected, TomlInvalidTypeException e) {
        return new PyprojectModelException("Invalid type for '" + key + "': expected " + expected, e);
    }

    private static String resolveBuildMode(TomlParseResult parsed) {
        String mode = readString(parsed, PyprojectConfigSpec.PYRONAUT_BUILD_MODE);
        if (mode == null || mode.isBlank()) {
            return (String) PyprojectConfigSpec.PYRONAUT_BUILD_MODE.defaultValue();
        }
        String normalized = mode.trim().toLowerCase();
        if (PyprojectConfigSpec.PYRONAUT_BUILD_MODE.enumValues().contains(normalized)) {
            return normalized;
        }
        throw new PyprojectModelException(
            "Invalid value for '" + PyprojectConfigSpec.PYRONAUT_BUILD_MODE.canonicalPath()
                + "': expected one of " + PyprojectConfigSpec.PYRONAUT_BUILD_MODE.enumValues()
        );
    }

    private static PyprojectModel.Metadata resolveBuildMetadata(TomlParseResult parsed) {
        Boolean enabled = readBoolean(parsed, PyprojectConfigSpec.PYRONAUT_BUILD_METADATA_ENABLED);
        String version = readString(parsed, PyprojectConfigSpec.PYRONAUT_BUILD_METADATA_VERSION);
        String repositoryUrl = readString(parsed, PyprojectConfigSpec.PYRONAUT_BUILD_METADATA_REPOSITORY_URL);
        List<String> excludedModules = readStringList(parsed, PyprojectConfigSpec.PYRONAUT_BUILD_METADATA_EXCLUDED_MODULES);
        return new PyprojectModel.Metadata(enabled, version, repositoryUrl, excludedModules);
    }

    private static String resolveProcessorMode(TomlParseResult parsed) {
        String mode = readString(parsed, PyprojectConfigSpec.PYRONAUT_PROCESSOR_MODE);
        if (mode == null || mode.isBlank()) {
            return (String) PyprojectConfigSpec.PYRONAUT_PROCESSOR_MODE.defaultValue();
        }
        String normalized = mode.trim().toLowerCase();
        if (PyprojectConfigSpec.PYRONAUT_PROCESSOR_MODE.enumValues().contains(normalized)) {
            return normalized;
        }
        throw new PyprojectModelException(
            "Invalid value for '" + PyprojectConfigSpec.PYRONAUT_PROCESSOR_MODE.canonicalPath()
                + "': expected one of " + PyprojectConfigSpec.PYRONAUT_PROCESSOR_MODE.enumValues()
        );
    }

    private static PyprojectModel.Docker resolveBuildDocker(TomlParseResult parsed) {
        return new PyprojectModel.Docker(
            readString(parsed, PyprojectConfigSpec.PYRONAUT_BUILD_DOCKER_IMAGE_NAME),
            readString(parsed, PyprojectConfigSpec.PYRONAUT_BUILD_DOCKER_DOCKERFILE),
            readString(parsed, PyprojectConfigSpec.PYRONAUT_BUILD_DOCKER_DOCKERFILE_NATIVE),
            readString(parsed, PyprojectConfigSpec.PYRONAUT_BUILD_DOCKER_JVM_BASE_IMAGE),
            readString(parsed, PyprojectConfigSpec.PYRONAUT_BUILD_DOCKER_NATIVE_BUILDER_IMAGE),
            readString(parsed, PyprojectConfigSpec.PYRONAUT_BUILD_DOCKER_NATIVE_BASE_IMAGE),
            readString(parsed, PyprojectConfigSpec.PYRONAUT_BUILD_DOCKER_STATIC_NATIVE_BUILDER_IMAGE),
            readString(parsed, PyprojectConfigSpec.PYRONAUT_BUILD_DOCKER_STATIC_NATIVE_BASE_IMAGE)
        );
    }

    private static PyprojectModel.IdeStubs resolveIdeStubs(TomlParseResult parsed) {
        return new PyprojectModel.IdeStubs(
            readBoolean(parsed, PyprojectConfigSpec.PYRONAUT_IDE_STUBS_ENABLED),
            readString(parsed, PyprojectConfigSpec.PYRONAUT_IDE_STUBS_IDE),
            readStringList(parsed, PyprojectConfigSpec.PYRONAUT_IDE_STUBS_PACKAGES),
            readStringList(parsed, PyprojectConfigSpec.PYRONAUT_IDE_STUBS_EXCLUDE_PATTERNS),
            readString(parsed, PyprojectConfigSpec.PYRONAUT_IDE_STUBS_DESTINATION_DIR)
        );
    }

    private static PyprojectModel.Validation resolveValidation(TomlParseResult parsed) {
        Boolean enabled = readBoolean(parsed, PyprojectConfigSpec.PYRONAUT_VALIDATION_ENABLED);
        Boolean failOnNotPresent = readBoolean(parsed, PyprojectConfigSpec.PYRONAUT_VALIDATION_FAIL_ON_NOT_PRESENT);
        Boolean deduceEnvironments = readBoolean(parsed, PyprojectConfigSpec.PYRONAUT_VALIDATION_DEDUCE_ENVIRONMENTS);
        Boolean validateDependencyInjection = readBoolean(parsed, PyprojectConfigSpec.PYRONAUT_VALIDATION_VALIDATE_DEPENDENCY_INJECTION);
        String diStrategy = readEnum(parsed, PyprojectConfigSpec.PYRONAUT_VALIDATION_STRATEGY);
        String format = readEnum(parsed, PyprojectConfigSpec.PYRONAUT_VALIDATION_FORMAT);
        List<String> suppressions = readStringList(parsed, PyprojectConfigSpec.PYRONAUT_VALIDATION_SUPPRESSIONS);
        List<String> suppressInjectErrors = readStringList(parsed, PyprojectConfigSpec.PYRONAUT_VALIDATION_SUPPRESS_INJECT_ERRORS);
        String projectBaseDir = readString(parsed, PyprojectConfigSpec.PYRONAUT_VALIDATION_PROJECT_BASE_DIR);
        List<String> resourcesDirs = readStringList(parsed, PyprojectConfigSpec.PYRONAUT_VALIDATION_RESOURCES_DIRS);

        PyprojectModel.ValidationScenario run = resolveValidationScenario(
            parsed,
            PyprojectConfigSpec.PYRONAUT_VALIDATION_RUN_ENABLED,
            PyprojectConfigSpec.PYRONAUT_VALIDATION_RUN_ENVIRONMENTS,
            PyprojectConfigSpec.PYRONAUT_VALIDATION_RUN_INCLUDE_DEFAULT_ENVIRONMENT,
            PyprojectConfigSpec.PYRONAUT_VALIDATION_RUN_OVERRIDE_CLASSPATH,
            PyprojectConfigSpec.PYRONAUT_VALIDATION_RUN_CLASSPATH,
            PyprojectConfigSpec.PYRONAUT_VALIDATION_RUN_ADDITIONAL_CLASSPATH,
            PyprojectConfigSpec.PYRONAUT_VALIDATION_RUN_RESOURCES_DIRS,
            PyprojectConfigSpec.PYRONAUT_VALIDATION_RUN_OUTPUT_DIR,
            List.of("dev"),
            DEFAULT_MAIN_VALIDATION_RESOURCE_DIRS
        );
        PyprojectModel.ValidationScenario test = resolveValidationScenario(
            parsed,
            PyprojectConfigSpec.PYRONAUT_VALIDATION_TEST_ENABLED,
            PyprojectConfigSpec.PYRONAUT_VALIDATION_TEST_ENVIRONMENTS,
            PyprojectConfigSpec.PYRONAUT_VALIDATION_TEST_INCLUDE_DEFAULT_ENVIRONMENT,
            PyprojectConfigSpec.PYRONAUT_VALIDATION_TEST_OVERRIDE_CLASSPATH,
            PyprojectConfigSpec.PYRONAUT_VALIDATION_TEST_CLASSPATH,
            PyprojectConfigSpec.PYRONAUT_VALIDATION_TEST_ADDITIONAL_CLASSPATH,
            PyprojectConfigSpec.PYRONAUT_VALIDATION_TEST_RESOURCES_DIRS,
            PyprojectConfigSpec.PYRONAUT_VALIDATION_TEST_OUTPUT_DIR,
            List.of("test"),
            DEFAULT_TEST_VALIDATION_RESOURCE_DIRS
        );
        PyprojectModel.ValidationScenario production = resolveValidationScenario(
            parsed,
            PyprojectConfigSpec.PYRONAUT_VALIDATION_PRODUCTION_ENABLED,
            PyprojectConfigSpec.PYRONAUT_VALIDATION_PRODUCTION_ENVIRONMENTS,
            PyprojectConfigSpec.PYRONAUT_VALIDATION_PRODUCTION_INCLUDE_DEFAULT_ENVIRONMENT,
            PyprojectConfigSpec.PYRONAUT_VALIDATION_PRODUCTION_OVERRIDE_CLASSPATH,
            PyprojectConfigSpec.PYRONAUT_VALIDATION_PRODUCTION_CLASSPATH,
            PyprojectConfigSpec.PYRONAUT_VALIDATION_PRODUCTION_ADDITIONAL_CLASSPATH,
            PyprojectConfigSpec.PYRONAUT_VALIDATION_PRODUCTION_RESOURCES_DIRS,
            PyprojectConfigSpec.PYRONAUT_VALIDATION_PRODUCTION_OUTPUT_DIR,
            List.of(),
            DEFAULT_MAIN_VALIDATION_RESOURCE_DIRS
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
        PyprojectConfigSpec.FieldSpec enabledField,
        PyprojectConfigSpec.FieldSpec environmentsField,
        PyprojectConfigSpec.FieldSpec includeDefaultEnvironmentField,
        PyprojectConfigSpec.FieldSpec overrideClasspathField,
        PyprojectConfigSpec.FieldSpec classpathField,
        PyprojectConfigSpec.FieldSpec additionalClasspathField,
        PyprojectConfigSpec.FieldSpec resourcesDirsField,
        PyprojectConfigSpec.FieldSpec outputDirField,
        List<String> defaultEnvironments,
        List<String> defaultResourcesDirs
    ) {
        Boolean enabled = readBoolean(parsed, enabledField);
        List<String> environments = readStringList(parsed, environmentsField);
        if (environments.isEmpty()) {
            environments = defaultEnvironments;
        }
        Boolean includeDefaultEnvironment = readBoolean(parsed, includeDefaultEnvironmentField);
        Boolean overrideClasspath = readBoolean(parsed, overrideClasspathField);
        List<String> classpath = readStringList(parsed, classpathField);
        List<String> additionalClasspath = readStringList(parsed, additionalClasspathField);
        List<String> resourcesDirs = readStringList(parsed, resourcesDirsField);
        if (resourcesDirs.isEmpty()) {
            resourcesDirs = defaultResourcesDirs;
        }
        String outputDir = readString(parsed, outputDirField);
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

    private static Boolean readBoolean(TomlParseResult parsed, PyprojectConfigSpec.FieldSpec field) {
        for (String path : field.allPaths()) {
            try {
                Boolean value = parsed.getBoolean(path);
                if (value != null) {
                    return value;
                }
            } catch (TomlInvalidTypeException e) {
                throw invalidType(field.canonicalPath(), "boolean", e);
            }
        }
        return (Boolean) field.defaultValue();
    }

    private static String readEnum(TomlParseResult parsed, PyprojectConfigSpec.FieldSpec field) {
        String raw = readString(parsed, field);
        if (raw == null || raw.isBlank()) {
            return (String) field.defaultValue();
        }
        String normalized = raw.trim();
        if (!field.enumValues().contains(normalized)) {
            throw new PyprojectModelException("Invalid value for '" + field.canonicalPath() + "': expected one of " + field.enumValues());
        }
        return normalized;
    }

    private static Integer readInteger(TomlParseResult parsed, PyprojectConfigSpec.FieldSpec field) {
        for (String path : field.allPaths()) {
            Long raw;
            try {
                raw = parsed.getLong(path);
            } catch (TomlInvalidTypeException e) {
                throw invalidType(field.canonicalPath(), "integer", e);
            }
            if (raw == null) {
                continue;
            }
            if (raw > Integer.MAX_VALUE || raw < Integer.MIN_VALUE) {
                throw new PyprojectModelException("Invalid value for '" + field.canonicalPath() + "': integer out of range");
            }
            return raw.intValue();
        }
        if (field.defaultValue() instanceof Integer defaultValue) {
            return defaultValue;
        }
        return null;
    }

    private static Map<String, String> readStringMap(TomlParseResult parsed, PyprojectConfigSpec.FieldSpec field) {
        for (String path : field.allPaths()) {
            TomlTable table;
            try {
                table = parsed.getTable(path);
            } catch (TomlInvalidTypeException e) {
                throw invalidType(field.canonicalPath(), "table", e);
            }
            if (table == null) {
                continue;
            }
            Map<String, Object> raw = table.toMap();
            if (raw.isEmpty()) {
                return Map.of();
            }
            var out = new java.util.LinkedHashMap<String, String>(raw.size());
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                if (!(entry.getValue() instanceof String value)) {
                    throw new PyprojectModelException("Invalid type for '" + field.canonicalPath() + "." + entry.getKey() + "': expected string");
                }
                out.put(entry.getKey(), value);
            }
            return Map.copyOf(out);
        }
        return Map.of();
    }

    private static PyprojectModel.TestResources resolveTestResources(TomlParseResult parsed) {
        boolean configured = hasSection(parsed, PyprojectConfigSpec.PYRONAUT_TEST_RESOURCES_SECTION);
        Boolean enabled = readBoolean(parsed, PyprojectConfigSpec.PYRONAUT_TEST_RESOURCES_ENABLED);
        String version = readString(parsed, PyprojectConfigSpec.PYRONAUT_TEST_RESOURCES_VERSION);
        Integer explicitPort = readInteger(parsed, PyprojectConfigSpec.PYRONAUT_TEST_RESOURCES_EXPLICIT_PORT);
        Boolean inferClasspath = readBoolean(parsed, PyprojectConfigSpec.PYRONAUT_TEST_RESOURCES_INFER_CLASSPATH);
        List<String> additionalModules = readStringList(parsed, PyprojectConfigSpec.PYRONAUT_TEST_RESOURCES_ADDITIONAL_MODULES);
        Integer clientTimeout = readInteger(parsed, PyprojectConfigSpec.PYRONAUT_TEST_RESOURCES_CLIENT_TIMEOUT);
        Boolean sharedServer = readBoolean(parsed, PyprojectConfigSpec.PYRONAUT_TEST_RESOURCES_SHARED_SERVER);
        String sharedServerNamespace = readString(parsed, PyprojectConfigSpec.PYRONAUT_TEST_RESOURCES_SHARED_SERVER_NAMESPACE);
        String logsDir = readString(parsed, PyprojectConfigSpec.PYRONAUT_TEST_RESOURCES_LOGS_DIR);
        Integer serverIdleTimeoutMinutes = readInteger(parsed, PyprojectConfigSpec.PYRONAUT_TEST_RESOURCES_SERVER_IDLE_TIMEOUT_MINUTES);
        Map<String, String> serverSystemProperties = readStringMap(parsed, PyprojectConfigSpec.PYRONAUT_TEST_RESOURCES_SERVER_SYSTEM_PROPERTIES);
        Map<String, String> serverEnvironment = readStringMap(parsed, PyprojectConfigSpec.PYRONAUT_TEST_RESOURCES_SERVER_ENVIRONMENT);
        Boolean debugServer = readBoolean(parsed, PyprojectConfigSpec.PYRONAUT_TEST_RESOURCES_DEBUG_SERVER);
        String javaExecutable = readString(parsed, PyprojectConfigSpec.PYRONAUT_TEST_RESOURCES_JAVA_EXECUTABLE);
        String startupOptimization = readEnum(parsed, PyprojectConfigSpec.PYRONAUT_TEST_RESOURCES_STARTUP_OPTIMIZATION);
        List<String> leydenJvmArgs = readStringList(parsed, PyprojectConfigSpec.PYRONAUT_TEST_RESOURCES_LEYDEN_JVM_ARGS);

        return new PyprojectModel.TestResources(
            configured,
            enabled,
            version,
            explicitPort,
            inferClasspath,
            additionalModules,
            clientTimeout,
            sharedServer,
            sharedServerNamespace,
            logsDir,
            serverIdleTimeoutMinutes,
            serverSystemProperties,
            serverEnvironment,
            debugServer,
            javaExecutable,
            startupOptimization,
            leydenJvmArgs
        );
    }

    private static boolean hasSection(TomlParseResult parsed, PyprojectConfigSpec.SectionSpec section) {
        for (String path : section.allPaths()) {
            if (parsed.getTable(path) != null) {
                return true;
            }
        }
        return false;
    }
}

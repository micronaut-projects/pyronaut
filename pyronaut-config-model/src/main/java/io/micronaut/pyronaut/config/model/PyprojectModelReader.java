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

import io.micronaut.json.tree.JsonNode;
import io.micronaut.toml.Parser;
import io.micronaut.toml.TomlStreamReadException;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
        return read(file, "pyproject.toml");
    }

    /**
     * Reads the shared Pyronaut configuration from an external Java project's project.toml.
     *
     * @param file project.toml file
     * @return parsed project model
     */
    public PyprojectModel readProjectToml(Path file) {
        if (!"project.toml".equals(file.getFileName().toString())) {
            throw new PyprojectModelException("Invalid config filename '" + file.getFileName() + "'. Expected 'project.toml'");
        }
        return read(file, "project.toml");
    }

    private PyprojectModel read(Path file, String descriptorName) {
        if (!Files.exists(file)) {
            throw new PyprojectModelException("Missing required file '" + descriptorName + "' at: " + file);
        }
        String contents;
        try {
            contents = Files.readString(file);
        } catch (IOException e) {
            throw new PyprojectModelException("Failed reading " + descriptorName + ": " + file, e);
        }
        JsonNode parsed;
        try {
            parsed = Parser.parse(contents);
        } catch (TomlStreamReadException e) {
            throw new PyprojectModelException("Invalid TOML in " + file + ": " + e.getMessage(), e);
        } catch (IOException e) {
            throw new PyprojectModelException("Failed reading " + descriptorName + ": " + file, e);
        }
        return map(parsed);
    }

    private static void validateFileName(Path file) {
        if (!FILE_NAME.equals(file.getFileName().toString())) {
            throw new PyprojectModelException("Invalid config filename '" + file.getFileName() + "'. Expected 'pyproject.toml'");
        }
    }

    private static PyprojectModel map(JsonNode parsed) {
        PyprojectModel.Project project = new PyprojectModel.Project(
            readString(parsed, PyprojectConfigSpec.PROJECT_NAME),
            readString(parsed, PyprojectConfigSpec.PROJECT_VERSION),
            readStringList(parsed, PyprojectConfigSpec.PROJECT_DYNAMIC)
        );

        PyprojectModel.BuildSystem buildSystem = new PyprojectModel.BuildSystem(
            readStringList(parsed, PyprojectConfigSpec.BUILD_SYSTEM_REQUIRES),
            readString(parsed, PyprojectConfigSpec.BUILD_SYSTEM_BUILD_BACKEND)
        );
        PyprojectModel.Sources sources = resolveSources(parsed);

        List<String> runtime = readStringList(parsed, PyprojectConfigSpec.PYRONAUT_DEPENDENCIES_RUNTIME);
        List<String> developmentRuntime = readStringList(parsed, PyprojectConfigSpec.PYRONAUT_DEPENDENCIES_DEVELOPMENT_RUNTIME);
        List<String> build = readStringList(parsed, PyprojectConfigSpec.PYRONAUT_DEPENDENCIES_BUILD);
        PyprojectModel.Packaging packaging = resolvePackaging(parsed);

        PyprojectModel.Pyronaut pyronaut = new PyprojectModel.Pyronaut(
            readString(parsed, PyprojectConfigSpec.PYRONAUT_CORE_VERSION),
            readString(parsed, PyprojectConfigSpec.PYRONAUT_PLATFORM_VERSION),
            readStringList(parsed, PyprojectConfigSpec.PYRONAUT_REPOSITORIES),
            packaging,
            new PyprojectModel.Dependencies(
                runtime,
                developmentRuntime,
                build,
                readStringList(parsed, PyprojectConfigSpec.PYRONAUT_DEPENDENCIES_TEST),
                readStringList(parsed, PyprojectConfigSpec.PYRONAUT_DEPENDENCIES_BOMS),
                readStringList(parsed, PyprojectConfigSpec.PYRONAUT_DEPENDENCIES_EXCLUSIONS),
                readStringArrayMap(parsed, PyprojectConfigSpec.PYRONAUT_DEPENDENCIES_ARTIFACT_EXCLUSIONS)
            ),
            new PyprojectModel.Run(readBoolean(parsed, PyprojectConfigSpec.PYRONAUT_RUN_BANNER_ENABLED)),
            resolveControlPanel(parsed),
            new PyprojectModel.Build(
                packaging.format().legacyMode(),
                readBoolean(parsed, PyprojectConfigSpec.PYRONAUT_BUILD_PYTHON_BYTECODE_ENABLED),
                readString(parsed, PyprojectConfigSpec.PYRONAUT_BUILD_NATIVE_BASE),
                resolveBuildMetadata(parsed),
                resolveBuildDocker(parsed)
            ),
            new PyprojectModel.Processor(
                resolveProcessorMode(parsed),
                readBoolean(parsed, PyprojectConfigSpec.PYRONAUT_PROCESSOR_INCREMENTAL),
                readEnum(parsed, PyprojectConfigSpec.PYRONAUT_PROCESSOR_PYTHON_INCREMENTAL_MODE),
                readBoolean(parsed, PyprojectConfigSpec.PYRONAUT_PROCESSOR_DAEMON)
            ),
            new PyprojectModel.Test(resolveTestMode(parsed), resolveTestEngine(parsed), readBoolean(parsed, PyprojectConfigSpec.PYRONAUT_TEST_VERBOSE),
                readEnum(parsed, PyprojectConfigSpec.PYRONAUT_TEST_CONTINUOUS), readString(parsed, PyprojectConfigSpec.PYRONAUT_TEST_REPORT_PATH)),
            sources,
            resolveToolchain(parsed),
            resolveIdeStubs(parsed),
            resolveValidation(parsed, sources),
            resolveTestResources(parsed),
            contains(parsed, "tool.pyronaut.control-panel") || contains(parsed, "tool.pyronaut.controlPanel")
        );

        return new PyprojectModel(project, buildSystem, pyronaut);
    }

    private static PyprojectModel.ControlPanel resolveControlPanel(JsonNode parsed) {
        String path = readString(parsed, PyprojectConfigSpec.PYRONAUT_CONTROL_PANEL_PATH);
        if (path == null || path.isBlank() || !path.startsWith("/")) {
            throw new PyprojectModelException(
                "Invalid value for '" + PyprojectConfigSpec.PYRONAUT_CONTROL_PANEL_PATH.canonicalPath()
                    + "': expected an absolute URL path starting with '/'"
            );
        }
        return new PyprojectModel.ControlPanel(
            readBoolean(parsed, PyprojectConfigSpec.PYRONAUT_CONTROL_PANEL_ENABLED),
            path,
            readBoolean(parsed, PyprojectConfigSpec.PYRONAUT_CONTROL_PANEL_PRODUCTION_ENABLED)
        );
    }

    private static String readString(JsonNode parsed, PyprojectConfigSpec.FieldSpec field) {
        for (String path : field.allPaths()) {
            JsonNode value = node(parsed, path);
            if (value == null) {
                continue;
            }
            if (!value.isString()) {
                throw invalidType(field.canonicalPath(), "string");
            }
            return value.getStringValue();
        }
        if (field.defaultValue() instanceof String defaultValue) {
            return defaultValue;
        }
        return null;
    }

    private static PyprojectModel.Sources resolveSources(JsonNode parsed) {
        return new PyprojectModel.Sources(
            readString(parsed, PyprojectConfigSpec.PYRONAUT_SOURCES_PYTHON),
            readString(parsed, PyprojectConfigSpec.PYRONAUT_SOURCES_PYTHON_TEST),
            readString(parsed, PyprojectConfigSpec.PYRONAUT_SOURCES_JAVA),
            readString(parsed, PyprojectConfigSpec.PYRONAUT_SOURCES_JAVA_TEST),
            readString(parsed, PyprojectConfigSpec.PYRONAUT_SOURCES_RESOURCES),
            readString(parsed, PyprojectConfigSpec.PYRONAUT_SOURCES_TEST_RESOURCES),
            readStringList(parsed, PyprojectConfigSpec.PYRONAUT_SOURCES_ADDITIONAL_RESOURCES),
            readStringList(parsed, PyprojectConfigSpec.PYRONAUT_SOURCES_ADDITIONAL_TEST_RESOURCES)
        );
    }

    private static List<String> readStringList(JsonNode parsed, PyprojectConfigSpec.FieldSpec field) {
        for (String path : field.allPaths()) {
            JsonNode array = node(parsed, path);
            if (array == null) {
                continue;
            }
            if (!array.isArray()) {
                throw invalidType(field.canonicalPath(), "array");
            }
            List<String> values = new ArrayList<>(array.size());
            for (int i = 0; i < array.size(); i++) {
                JsonNode value = array.get(i);
                if (value == null || !value.isString()) {
                    throw new PyprojectModelException("Invalid type for '" + field.canonicalPath() + "[" + i + "]': expected string");
                }
                values.add(value.getStringValue());
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

    private static PyprojectModelException invalidType(String key, String expected) {
        return new PyprojectModelException("Invalid type for '" + key + "': expected " + expected);
    }

    private static PyprojectModel.Packaging resolvePackaging(JsonNode parsed) {
        if (contains(parsed, "tool.pyronaut.build.base-image")) {
            throw new PyprojectModelException(
                "Unsupported configuration 'tool.pyronaut.build.base-image'; use 'tool.pyronaut.build.native-base'"
            );
        }
        if (contains(parsed, "tool.pyronaut.build.mode")) {
            throw new PyprojectModelException(
                "Unsupported configuration 'tool.pyronaut.build.mode'; use 'tool.pyronaut.packaging.format'"
            );
        }
        String value = readString(parsed, PyprojectConfigSpec.PYRONAUT_PACKAGING_FORMAT);
        if (value == null) {
            value = (String) PyprojectConfigSpec.PYRONAUT_PACKAGING_FORMAT.defaultValue();
        } else {
            value = value.trim();
            if (!PyprojectConfigSpec.PYRONAUT_PACKAGING_FORMAT.enumValues().contains(value)) {
                throw new PyprojectModelException(
                    "Invalid value for '" + PyprojectConfigSpec.PYRONAUT_PACKAGING_FORMAT.canonicalPath()
                        + "': expected one of " + PyprojectConfigSpec.PYRONAUT_PACKAGING_FORMAT.enumValues()
                );
            }
        }
        return new PyprojectModel.Packaging(PyprojectModel.PackagingFormat.fromValue(value));
    }

    private static PyprojectModel.Metadata resolveBuildMetadata(JsonNode parsed) {
        Boolean enabled = readBoolean(parsed, PyprojectConfigSpec.PYRONAUT_BUILD_METADATA_ENABLED);
        String version = readString(parsed, PyprojectConfigSpec.PYRONAUT_BUILD_METADATA_VERSION);
        String repositoryUrl = readString(parsed, PyprojectConfigSpec.PYRONAUT_BUILD_METADATA_REPOSITORY_URL);
        List<String> excludedModules = readStringList(parsed, PyprojectConfigSpec.PYRONAUT_BUILD_METADATA_EXCLUDED_MODULES);
        return new PyprojectModel.Metadata(enabled, version, repositoryUrl, excludedModules);
    }

    private static String resolveProcessorMode(JsonNode parsed) {
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

    private static String resolveTestMode(JsonNode parsed) {
        String mode = readString(parsed, PyprojectConfigSpec.PYRONAUT_TEST_MODE);
        if (mode == null || mode.isBlank()) {
            return (String) PyprojectConfigSpec.PYRONAUT_TEST_MODE.defaultValue();
        }
        String normalized = mode.trim().toLowerCase();
        if (PyprojectConfigSpec.PYRONAUT_TEST_MODE.enumValues().contains(normalized)) {
            return normalized;
        }
        throw new PyprojectModelException(
            "Invalid value for '" + PyprojectConfigSpec.PYRONAUT_TEST_MODE.canonicalPath()
                + "': expected one of " + PyprojectConfigSpec.PYRONAUT_TEST_MODE.enumValues()
        );
    }

    private static PyprojectModel.TestEngine resolveTestEngine(JsonNode parsed) {
        String engine = readString(parsed, PyprojectConfigSpec.PYRONAUT_TEST_ENGINE);
        if (engine == null || engine.isBlank()) {
            engine = (String) PyprojectConfigSpec.PYRONAUT_TEST_ENGINE.defaultValue();
        }
        String normalized = engine.trim().toUpperCase(java.util.Locale.ROOT);
        try {
            return PyprojectModel.TestEngine.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new PyprojectModelException(
                "Invalid value for '" + PyprojectConfigSpec.PYRONAUT_TEST_ENGINE.canonicalPath()
                    + "': expected one of " + PyprojectConfigSpec.PYRONAUT_TEST_ENGINE.enumValues()
            );
        }
    }

    private static PyprojectModel.Toolchain resolveToolchain(JsonNode parsed) {
        return new PyprojectModel.Toolchain(
            readEnum(parsed, PyprojectConfigSpec.PYRONAUT_TOOLCHAIN_DISTRIBUTION),
            readEnum(parsed, PyprojectConfigSpec.PYRONAUT_TOOLCHAIN_TYPE),
            readString(parsed, PyprojectConfigSpec.PYRONAUT_TOOLCHAIN_VERSION),
            readInteger(parsed, PyprojectConfigSpec.PYRONAUT_TOOLCHAIN_JAVA_VERSION),
            readString(parsed, PyprojectConfigSpec.PYRONAUT_TOOLCHAIN_RELEASE_TAG),
            readString(parsed, PyprojectConfigSpec.PYRONAUT_TOOLCHAIN_DOWNLOAD_URL)
        );
    }

    private static PyprojectModel.Docker resolveBuildDocker(JsonNode parsed) {
        return new PyprojectModel.Docker(
            readString(parsed, PyprojectConfigSpec.PYRONAUT_BUILD_DOCKER_IMAGE_NAME),
            readString(parsed, PyprojectConfigSpec.PYRONAUT_BUILD_DOCKER_DOCKERFILE),
            readString(parsed, PyprojectConfigSpec.PYRONAUT_BUILD_DOCKER_DOCKERFILE_NATIVE),
            readString(parsed, PyprojectConfigSpec.PYRONAUT_BUILD_DOCKER_JVM_BASE_IMAGE),
            readString(parsed, PyprojectConfigSpec.PYRONAUT_BUILD_DOCKER_NATIVE_BUILDER_IMAGE),
            readString(parsed, PyprojectConfigSpec.PYRONAUT_BUILD_DOCKER_NATIVE_BASE_IMAGE),
            readString(parsed, PyprojectConfigSpec.PYRONAUT_BUILD_DOCKER_STATIC_NATIVE_BUILDER_IMAGE),
            readString(parsed, PyprojectConfigSpec.PYRONAUT_BUILD_DOCKER_STATIC_NATIVE_BASE_IMAGE),
            readString(parsed, PyprojectConfigSpec.PYRONAUT_BUILD_DOCKER_BASE_IMAGE)
        );
    }

    private static PyprojectModel.IdeStubs resolveIdeStubs(JsonNode parsed) {
        return new PyprojectModel.IdeStubs(
            readBoolean(parsed, PyprojectConfigSpec.PYRONAUT_IDE_STUBS_ENABLED),
            readString(parsed, PyprojectConfigSpec.PYRONAUT_IDE_STUBS_IDE),
            readStringList(parsed, PyprojectConfigSpec.PYRONAUT_IDE_STUBS_PACKAGES),
            readStringList(parsed, PyprojectConfigSpec.PYRONAUT_IDE_STUBS_EXCLUDE_PATTERNS),
            readString(parsed, PyprojectConfigSpec.PYRONAUT_IDE_STUBS_DESTINATION_DIR)
        );
    }

    private static PyprojectModel.Validation resolveValidation(JsonNode parsed, PyprojectModel.Sources sources) {
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
            defaultMainValidationResourceDirs(sources)
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
            defaultTestValidationResourceDirs(sources)
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
            defaultMainValidationResourceDirs(sources)
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

    private static List<String> defaultMainValidationResourceDirs(PyprojectModel.Sources sources) {
        List<String> resourcesDirs = new ArrayList<>(2 + sources.additionalResources().size());
        resourcesDirs.add(sources.resources());
        resourcesDirs.addAll(sources.additionalResources());
        resourcesDirs.add("src/main/resources");
        return List.copyOf(resourcesDirs);
    }

    private static List<String> defaultTestValidationResourceDirs(PyprojectModel.Sources sources) {
        List<String> resourcesDirs = new ArrayList<>(3 + sources.additionalResources().size() + sources.additionalTestResources().size());
        resourcesDirs.add(sources.resources());
        resourcesDirs.addAll(sources.additionalResources());
        resourcesDirs.add("src/main/resources");
        resourcesDirs.add(sources.testResources());
        resourcesDirs.addAll(sources.additionalTestResources());
        return List.copyOf(resourcesDirs);
    }

    private static PyprojectModel.ValidationScenario resolveValidationScenario(
        JsonNode parsed,
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

    private static Boolean readBoolean(JsonNode parsed, PyprojectConfigSpec.FieldSpec field) {
        for (String path : field.allPaths()) {
            JsonNode value = node(parsed, path);
            if (value == null) {
                continue;
            }
            if (!value.isBoolean()) {
                throw invalidType(field.canonicalPath(), "boolean");
            }
            return value.getBooleanValue();
        }
        return (Boolean) field.defaultValue();
    }

    private static String readEnum(JsonNode parsed, PyprojectConfigSpec.FieldSpec field) {
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

    private static Integer readInteger(JsonNode parsed, PyprojectConfigSpec.FieldSpec field) {
        for (String path : field.allPaths()) {
            JsonNode value = node(parsed, path);
            if (value == null) {
                continue;
            }
            Number raw = value.isNumber() ? value.getNumberValue() : null;
            if (!(raw instanceof Integer || raw instanceof Long || raw instanceof BigInteger)) {
                throw invalidType(field.canonicalPath(), "integer");
            }
            BigInteger integer = raw instanceof BigInteger bigInteger
                ? bigInteger
                : BigInteger.valueOf(raw.longValue());
            if (integer.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0
                || integer.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) < 0) {
                throw new PyprojectModelException("Invalid value for '" + field.canonicalPath() + "': integer out of range");
            }
            return integer.intValue();
        }
        if (field.defaultValue() instanceof Integer defaultValue) {
            return defaultValue;
        }
        return null;
    }

    private static Map<String, String> readStringMap(JsonNode parsed, PyprojectConfigSpec.FieldSpec field) {
        for (String path : field.allPaths()) {
            JsonNode table = node(parsed, path);
            if (table == null) {
                continue;
            }
            if (!table.isObject()) {
                throw invalidType(field.canonicalPath(), "table");
            }
            var out = new java.util.LinkedHashMap<String, String>(table.size());
            for (Map.Entry<String, JsonNode> entry : table.entries()) {
                if (!entry.getValue().isString()) {
                    throw new PyprojectModelException("Invalid type for '" + field.canonicalPath() + "." + entry.getKey() + "': expected string");
                }
                out.put(entry.getKey(), entry.getValue().getStringValue());
            }
            return Map.copyOf(out);
        }
        return Map.of();
    }

    private static Map<String, List<String>> readStringArrayMap(JsonNode parsed, PyprojectConfigSpec.FieldSpec field) {
        JsonNode table = node(parsed, field.canonicalPath());
        if (table == null) {
            return Map.of();
        }
        if (!table.isObject()) {
            throw invalidType(field.canonicalPath(), "table");
        }
        var out = new java.util.LinkedHashMap<String, List<String>>();
        for (Map.Entry<String, JsonNode> entry : table.entries()) {
            JsonNode array = entry.getValue();
            if (!array.isArray()) {
                throw new PyprojectModelException("Invalid type for '" + field.canonicalPath() + "." + entry.getKey() + "': expected array");
            }
            var values = new ArrayList<String>();
            for (JsonNode value : array.values()) {
                if (!value.isString()) {
                    throw new PyprojectModelException("Invalid type for '" + field.canonicalPath() + "." + entry.getKey() + "': expected string array");
                }
                values.add(value.getStringValue());
            }
            out.put(entry.getKey(), List.copyOf(values));
        }
        return Map.copyOf(out);
    }

    private static PyprojectModel.TestResources resolveTestResources(JsonNode parsed) {
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

    private static boolean hasSection(JsonNode parsed, PyprojectConfigSpec.SectionSpec section) {
        for (String path : section.allPaths()) {
            JsonNode value = node(parsed, path);
            if (value != null) {
                if (!value.isObject()) {
                    throw invalidType(section.canonicalPath(), "table");
                }
                return true;
            }
        }
        return false;
    }

    private static boolean contains(JsonNode parsed, String path) {
        return node(parsed, path) != null;
    }

    private static JsonNode node(JsonNode parsed, String path) {
        JsonNode current = parsed;
        for (String segment : path.split("\\.")) {
            if (current == null || !current.isObject()) {
                return null;
            }
            current = current.get(segment);
        }
        return current;
    }
}

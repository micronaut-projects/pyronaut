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

import java.util.List;

/**
 * Shared specification for {@code pyproject.toml} parsing and JSON schema generation.
 */
public final class PyprojectConfigSpec {

    private PyprojectConfigSpec() {
    }

    public enum ValueType {
        STRING,
        BOOLEAN,
        INTEGER,
        STRING_ARRAY,
        STRING_MAP
    }

    public record FieldSpec(
        String canonicalPath,
        ValueType type,
        String description,
        Object defaultValue,
        List<String> enumValues,
        List<String> aliasPaths
    ) {
        public FieldSpec {
            enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
            aliasPaths = aliasPaths == null ? List.of() : List.copyOf(aliasPaths);
        }

        public List<String> allPaths() {
            if (aliasPaths.isEmpty()) {
                return List.of(canonicalPath);
            }
            var paths = new java.util.ArrayList<String>(1 + aliasPaths.size());
            paths.add(canonicalPath);
            paths.addAll(aliasPaths);
            return List.copyOf(paths);
        }
    }

    public record SectionSpec(
        String canonicalPath,
        List<String> aliasPaths,
        boolean strict
    ) {
        public SectionSpec {
            aliasPaths = aliasPaths == null ? List.of() : List.copyOf(aliasPaths);
        }

        public List<String> allPaths() {
            if (aliasPaths.isEmpty()) {
                return List.of(canonicalPath);
            }
            var paths = new java.util.ArrayList<String>(1 + aliasPaths.size());
            paths.add(canonicalPath);
            paths.addAll(aliasPaths);
            return List.copyOf(paths);
        }
    }

    public static final FieldSpec PROJECT_NAME = string(
        "project.name",
        "Project name."
    );
    public static final FieldSpec PROJECT_VERSION = string(
        "project.version",
        "Project version."
    );
    public static final FieldSpec PROJECT_DYNAMIC = stringArray(
        "project.dynamic",
        "Dynamic project metadata fields."
    );
    public static final FieldSpec BUILD_SYSTEM_REQUIRES = stringArray(
        "build-system.requires",
        "Build requirements."
    );
    public static final FieldSpec BUILD_SYSTEM_BUILD_BACKEND = string(
        "build-system.build-backend",
        "Build backend."
    );

    public static final FieldSpec PYRONAUT_VERSION = string(
        "tool.pyronaut.version",
        "Pyronaut version used for managed dependency resolution."
    );
    public static final FieldSpec PYRONAUT_REPOSITORIES = stringArray(
        "tool.pyronaut.repositories",
        "Remote repositories used for Maven dependency resolution."
    );
    public static final FieldSpec PYRONAUT_DEPENDENCIES_RUNTIME = stringArray(
        "tool.pyronaut.dependencies.runtime",
        "Runtime dependencies.",
        null,
        List.of(),
        List.of("tool.pyronaut.dependencies.compile")
    );
    public static final FieldSpec PYRONAUT_DEPENDENCIES_BUILD = stringArray(
        "tool.pyronaut.dependencies.build",
        "Build-time dependencies used during processing.",
        null,
        List.of(),
        List.of(
            "tool.pyronaut.dependencies.annotationProcessor",
            "tool.pyronaut.annotationProcessor"
        )
    );
    public static final FieldSpec PYRONAUT_DEPENDENCIES_TEST = stringArray(
        "tool.pyronaut.dependencies.test",
        "Test dependencies."
    );
    public static final FieldSpec PYRONAUT_BUILD_MODE = enumString(
        "tool.pyronaut.build.mode",
        "Default build mode.",
        "jvm",
        List.of("jvm", "native")
    );
    public static final FieldSpec PYRONAUT_PROCESSOR_MODE = enumString(
        "tool.pyronaut.processor.mode",
        "Processor execution mode.",
        "jit",
        List.of("jit", "native")
    );
    public static final FieldSpec PYRONAUT_TEST_MODE = enumString(
        "tool.pyronaut.test.mode",
        "Test execution mode.",
        "jit",
        List.of("jit", "native")
    );
    public static final FieldSpec PYRONAUT_BUILD_METADATA_ENABLED = bool(
        "tool.pyronaut.build.metadata.enabled",
        "Whether native image metadata repository lookup is enabled."
    );
    public static final FieldSpec PYRONAUT_BUILD_METADATA_VERSION = string(
        "tool.pyronaut.build.metadata.version",
        "Native image metadata repository version override."
    );
    public static final FieldSpec PYRONAUT_BUILD_METADATA_REPOSITORY_URL = string(
        "tool.pyronaut.build.metadata.repository-url",
        "Native image metadata repository URL override.",
        null,
        List.of(),
        List.of("tool.pyronaut.build.metadata.repositoryUrl")
    );
    public static final FieldSpec PYRONAUT_BUILD_METADATA_EXCLUDED_MODULES = stringArray(
        "tool.pyronaut.build.metadata.excluded-modules",
        "Modules excluded from metadata resolution.",
        null,
        List.of(),
        List.of("tool.pyronaut.build.metadata.excludedModules")
    );
    public static final FieldSpec PYRONAUT_BUILD_DOCKER_IMAGE_NAME = string(
        "tool.pyronaut.build.docker.image-name",
        "Docker image name for container builds.",
        null,
        List.of(),
        List.of("tool.pyronaut.build.docker.imageName")
    );
    public static final FieldSpec PYRONAUT_BUILD_DOCKER_DOCKERFILE = string(
        "tool.pyronaut.build.docker.dockerfile",
        "Custom Dockerfile path for JVM container builds."
    );
    public static final FieldSpec PYRONAUT_BUILD_DOCKER_DOCKERFILE_NATIVE = string(
        "tool.pyronaut.build.docker.dockerfile-native",
        "Custom Dockerfile path for native container builds.",
        null,
        List.of(),
        List.of("tool.pyronaut.build.docker.dockerfileNative")
    );
    public static final FieldSpec PYRONAUT_BUILD_DOCKER_JVM_BASE_IMAGE = string(
        "tool.pyronaut.build.docker.jvm-base-image",
        "Base image for JVM container builds.",
        null,
        List.of(),
        List.of("tool.pyronaut.build.docker.jvmBaseImage")
    );
    public static final FieldSpec PYRONAUT_BUILD_DOCKER_NATIVE_BUILDER_IMAGE = string(
        "tool.pyronaut.build.docker.native-builder-image",
        "Builder image for native container builds.",
        null,
        List.of(),
        List.of("tool.pyronaut.build.docker.nativeBuilderImage")
    );
    public static final FieldSpec PYRONAUT_BUILD_DOCKER_NATIVE_BASE_IMAGE = string(
        "tool.pyronaut.build.docker.native-base-image",
        "Runtime base image for non-static native container builds.",
        null,
        List.of(),
        List.of("tool.pyronaut.build.docker.nativeBaseImage")
    );
    public static final FieldSpec PYRONAUT_BUILD_DOCKER_STATIC_NATIVE_BUILDER_IMAGE = string(
        "tool.pyronaut.build.docker.static-native-builder-image",
        "Builder image for static native container builds.",
        null,
        List.of(),
        List.of("tool.pyronaut.build.docker.staticNativeBuilderImage")
    );
    public static final FieldSpec PYRONAUT_BUILD_DOCKER_STATIC_NATIVE_BASE_IMAGE = string(
        "tool.pyronaut.build.docker.static-native-base-image",
        "Runtime base image for static native container builds.",
        null,
        List.of(),
        List.of("tool.pyronaut.build.docker.staticNativeBaseImage")
    );
    public static final SectionSpec PYRONAUT_IDE_STUBS_SECTION = section(
        "tool.pyronaut.ide-stubs",
        true,
        "tool.pyronaut.ideStubs"
    );
    public static final FieldSpec PYRONAUT_IDE_STUBS_ENABLED = bool(
        "tool.pyronaut.ide-stubs.enabled",
        "Whether best-effort Python IDE stub generation is enabled during install.",
        Boolean.TRUE,
        List.of(),
        List.of("tool.pyronaut.ideStubs.enabled")
    );
    public static final FieldSpec PYRONAUT_IDE_STUBS_IDE = enumString(
        "tool.pyronaut.ide-stubs.ide",
        "IDE to configure for generated Python IDE stubs.",
        "vscode",
        List.of("vscode", "pycharm"),
        List.of("tool.pyronaut.ideStubs.ide")
    );
    public static final FieldSpec PYRONAUT_IDE_STUBS_PACKAGES = stringArray(
        "tool.pyronaut.ide-stubs.packages",
        "Java package roots to process for Python IDE stub generation.",
        List.of("io.micronaut", "jakarta"),
        List.of(),
        List.of("tool.pyronaut.ideStubs.packages")
    );
    public static final FieldSpec PYRONAUT_IDE_STUBS_EXCLUDE_PATTERNS = stringArray(
        "tool.pyronaut.ide-stubs.exclude-patterns",
        "Fully qualified Java type-name wildcard patterns excluded from Python IDE stub generation.",
        List.of("*ModuleInfo"),
        List.of(),
        List.of("tool.pyronaut.ideStubs.excludePatterns")
    );
    public static final FieldSpec PYRONAUT_IDE_STUBS_DESTINATION_DIR = string(
        "tool.pyronaut.ide-stubs.destination-dir",
        "Destination directory for generated Python IDE stubs.",
        "__pyronaut__/ide-stubs",
        List.of(),
        List.of("tool.pyronaut.ideStubs.destinationDir")
    );

    public static final FieldSpec PYRONAUT_VALIDATION_ENABLED = bool(
        "tool.pyronaut.validation.enabled",
        "Whether validation is enabled.",
        Boolean.TRUE
    );
    public static final FieldSpec PYRONAUT_VALIDATION_FAIL_ON_NOT_PRESENT = bool(
        "tool.pyronaut.validation.fail-on-not-present",
        "Fail when expected config or classpath entries are missing.",
        Boolean.TRUE,
        List.of(),
        List.of("tool.pyronaut.validation.failOnNotPresent")
    );
    public static final FieldSpec PYRONAUT_VALIDATION_DEDUCE_ENVIRONMENTS = bool(
        "tool.pyronaut.validation.deduce-environments",
        "Infer environments from runtime context.",
        Boolean.FALSE,
        List.of(),
        List.of("tool.pyronaut.validation.deduceEnvironments")
    );
    public static final FieldSpec PYRONAUT_VALIDATION_VALIDATE_DEPENDENCY_INJECTION = bool(
        "tool.pyronaut.validation.validate-dependency-injection",
        "Enable dependency-injection validation.",
        Boolean.FALSE,
        List.of(),
        List.of("tool.pyronaut.validation.validateDependencyInjection")
    );
    public static final FieldSpec PYRONAUT_VALIDATION_STRATEGY = enumString(
        "tool.pyronaut.validation.dependency-injection-validation-strategy",
        "Dependency-injection validation strategy.",
        "reachable",
        List.of("reachable", "application-beans", "all-beans"),
        List.of("tool.pyronaut.validation.dependencyInjectionValidationStrategy")
    );
    public static final FieldSpec PYRONAUT_VALIDATION_FORMAT = enumString(
        "tool.pyronaut.validation.format",
        "Validation report format.",
        "both",
        List.of("json", "html", "both")
    );
    public static final FieldSpec PYRONAUT_VALIDATION_SUPPRESSIONS = stringArray(
        "tool.pyronaut.validation.suppressions",
        "Validation warning or error suppressions."
    );
    public static final FieldSpec PYRONAUT_VALIDATION_SUPPRESS_INJECT_ERRORS = stringArray(
        "tool.pyronaut.validation.suppress-inject-errors",
        "Dependency-injection error suppressions.",
        null,
        List.of(),
        List.of("tool.pyronaut.validation.suppressInjectErrors")
    );
    public static final FieldSpec PYRONAUT_VALIDATION_PROJECT_BASE_DIR = string(
        "tool.pyronaut.validation.project-base-dir",
        "Project base directory used for validation path normalization.",
        null,
        List.of(),
        List.of("tool.pyronaut.validation.projectBaseDir")
    );
    public static final FieldSpec PYRONAUT_VALIDATION_RESOURCES_DIRS = stringArray(
        "tool.pyronaut.validation.resources-dirs",
        "Default resources directories used for validation.",
        null,
        List.of(),
        List.of("tool.pyronaut.validation.resourcesDirs")
    );

    public static final FieldSpec PYRONAUT_VALIDATION_RUN_ENABLED = bool(
        "tool.pyronaut.validation.run.enabled",
        "Whether run validation is enabled.",
        Boolean.TRUE
    );
    public static final FieldSpec PYRONAUT_VALIDATION_RUN_ENVIRONMENTS = stringArray(
        "tool.pyronaut.validation.run.environments",
        "Run validation environments."
    );
    public static final FieldSpec PYRONAUT_VALIDATION_RUN_INCLUDE_DEFAULT_ENVIRONMENT = bool(
        "tool.pyronaut.validation.run.include-default-environment",
        "Whether to include the Micronaut default environment for run validation.",
        Boolean.TRUE,
        List.of(),
        List.of("tool.pyronaut.validation.run.includeDefaultEnvironment")
    );
    public static final FieldSpec PYRONAUT_VALIDATION_RUN_OVERRIDE_CLASSPATH = bool(
        "tool.pyronaut.validation.run.override-classpath",
        "Whether run validation should use only the explicit classpath.",
        Boolean.FALSE,
        List.of(),
        List.of("tool.pyronaut.validation.run.overrideClasspath")
    );
    public static final FieldSpec PYRONAUT_VALIDATION_RUN_CLASSPATH = stringArray(
        "tool.pyronaut.validation.run.classpath",
        "Explicit run validation classpath."
    );
    public static final FieldSpec PYRONAUT_VALIDATION_RUN_ADDITIONAL_CLASSPATH = stringArray(
        "tool.pyronaut.validation.run.additional-classpath",
        "Additional run validation classpath entries.",
        null,
        List.of(),
        List.of("tool.pyronaut.validation.run.additionalClasspath")
    );
    public static final FieldSpec PYRONAUT_VALIDATION_RUN_RESOURCES_DIRS = stringArray(
        "tool.pyronaut.validation.run.resources-dirs",
        "Run validation resources directories.",
        null,
        List.of(),
        List.of("tool.pyronaut.validation.run.resourcesDirs")
    );
    public static final FieldSpec PYRONAUT_VALIDATION_RUN_OUTPUT_DIR = string(
        "tool.pyronaut.validation.run.output-dir",
        "Run validation output directory.",
        null,
        List.of(),
        List.of("tool.pyronaut.validation.run.outputDir")
    );

    public static final FieldSpec PYRONAUT_VALIDATION_TEST_ENABLED = bool(
        "tool.pyronaut.validation.test.enabled",
        "Whether test validation is enabled.",
        Boolean.TRUE
    );
    public static final FieldSpec PYRONAUT_VALIDATION_TEST_ENVIRONMENTS = stringArray(
        "tool.pyronaut.validation.test.environments",
        "Test validation environments."
    );
    public static final FieldSpec PYRONAUT_VALIDATION_TEST_INCLUDE_DEFAULT_ENVIRONMENT = bool(
        "tool.pyronaut.validation.test.include-default-environment",
        "Whether to include the Micronaut default environment for test validation.",
        Boolean.TRUE,
        List.of(),
        List.of("tool.pyronaut.validation.test.includeDefaultEnvironment")
    );
    public static final FieldSpec PYRONAUT_VALIDATION_TEST_OVERRIDE_CLASSPATH = bool(
        "tool.pyronaut.validation.test.override-classpath",
        "Whether test validation should use only the explicit classpath.",
        Boolean.FALSE,
        List.of(),
        List.of("tool.pyronaut.validation.test.overrideClasspath")
    );
    public static final FieldSpec PYRONAUT_VALIDATION_TEST_CLASSPATH = stringArray(
        "tool.pyronaut.validation.test.classpath",
        "Explicit test validation classpath."
    );
    public static final FieldSpec PYRONAUT_VALIDATION_TEST_ADDITIONAL_CLASSPATH = stringArray(
        "tool.pyronaut.validation.test.additional-classpath",
        "Additional test validation classpath entries.",
        null,
        List.of(),
        List.of("tool.pyronaut.validation.test.additionalClasspath")
    );
    public static final FieldSpec PYRONAUT_VALIDATION_TEST_RESOURCES_DIRS = stringArray(
        "tool.pyronaut.validation.test.resources-dirs",
        "Test validation resources directories.",
        null,
        List.of(),
        List.of("tool.pyronaut.validation.test.resourcesDirs")
    );
    public static final FieldSpec PYRONAUT_VALIDATION_TEST_OUTPUT_DIR = string(
        "tool.pyronaut.validation.test.output-dir",
        "Test validation output directory.",
        null,
        List.of(),
        List.of("tool.pyronaut.validation.test.outputDir")
    );

    public static final FieldSpec PYRONAUT_VALIDATION_PRODUCTION_ENABLED = bool(
        "tool.pyronaut.validation.production.enabled",
        "Whether production validation is enabled.",
        Boolean.TRUE
    );
    public static final FieldSpec PYRONAUT_VALIDATION_PRODUCTION_ENVIRONMENTS = stringArray(
        "tool.pyronaut.validation.production.environments",
        "Production validation environments."
    );
    public static final FieldSpec PYRONAUT_VALIDATION_PRODUCTION_INCLUDE_DEFAULT_ENVIRONMENT = bool(
        "tool.pyronaut.validation.production.include-default-environment",
        "Whether to include the Micronaut default environment for production validation.",
        Boolean.TRUE,
        List.of(),
        List.of("tool.pyronaut.validation.production.includeDefaultEnvironment")
    );
    public static final FieldSpec PYRONAUT_VALIDATION_PRODUCTION_OVERRIDE_CLASSPATH = bool(
        "tool.pyronaut.validation.production.override-classpath",
        "Whether production validation should use only the explicit classpath.",
        Boolean.FALSE,
        List.of(),
        List.of("tool.pyronaut.validation.production.overrideClasspath")
    );
    public static final FieldSpec PYRONAUT_VALIDATION_PRODUCTION_CLASSPATH = stringArray(
        "tool.pyronaut.validation.production.classpath",
        "Explicit production validation classpath."
    );
    public static final FieldSpec PYRONAUT_VALIDATION_PRODUCTION_ADDITIONAL_CLASSPATH = stringArray(
        "tool.pyronaut.validation.production.additional-classpath",
        "Additional production validation classpath entries.",
        null,
        List.of(),
        List.of("tool.pyronaut.validation.production.additionalClasspath")
    );
    public static final FieldSpec PYRONAUT_VALIDATION_PRODUCTION_RESOURCES_DIRS = stringArray(
        "tool.pyronaut.validation.production.resources-dirs",
        "Production validation resources directories.",
        null,
        List.of(),
        List.of("tool.pyronaut.validation.production.resourcesDirs")
    );
    public static final FieldSpec PYRONAUT_VALIDATION_PRODUCTION_OUTPUT_DIR = string(
        "tool.pyronaut.validation.production.output-dir",
        "Production validation output directory.",
        null,
        List.of(),
        List.of("tool.pyronaut.validation.production.outputDir")
    );

    public static final SectionSpec PYRONAUT_TEST_RESOURCES_SECTION = section(
        "tool.pyronaut.test-resources",
        true,
        "tool.pyronaut.testResources"
    );
    public static final FieldSpec PYRONAUT_TEST_RESOURCES_ENABLED = bool(
        "tool.pyronaut.test-resources.enabled",
        "Whether standalone test resources server support is enabled.",
        Boolean.TRUE,
        List.of(),
        List.of("tool.pyronaut.testResources.enabled")
    );
    public static final FieldSpec PYRONAUT_TEST_RESOURCES_VERSION = string(
        "tool.pyronaut.test-resources.version",
        "Requested test resources tooling version.",
        null,
        List.of(),
        List.of("tool.pyronaut.testResources.version")
    );
    public static final FieldSpec PYRONAUT_TEST_RESOURCES_EXPLICIT_PORT = integer(
        "tool.pyronaut.test-resources.explicit-port",
        "Explicit test resources server port.",
        null,
        List.of(),
        List.of("tool.pyronaut.testResources.explicitPort")
    );
    public static final FieldSpec PYRONAUT_TEST_RESOURCES_INFER_CLASSPATH = bool(
        "tool.pyronaut.test-resources.infer-classpath",
        "Infer launcher classpath from runtime dependencies.",
        Boolean.TRUE,
        List.of(),
        List.of("tool.pyronaut.testResources.inferClasspath")
    );
    public static final FieldSpec PYRONAUT_TEST_RESOURCES_ADDITIONAL_MODULES = stringArray(
        "tool.pyronaut.test-resources.additional-modules",
        "Additional test resources modules.",
        null,
        List.of(),
        List.of("tool.pyronaut.testResources.additionalModules")
    );
    public static final FieldSpec PYRONAUT_TEST_RESOURCES_CLIENT_TIMEOUT = integer(
        "tool.pyronaut.test-resources.client-timeout",
        "Test resources client timeout in seconds.",
        Integer.valueOf(60),
        List.of(),
        List.of("tool.pyronaut.testResources.clientTimeout")
    );
    public static final FieldSpec PYRONAUT_TEST_RESOURCES_SHARED_SERVER = bool(
        "tool.pyronaut.test-resources.shared-server",
        "Use shared server settings under the user home directory.",
        Boolean.FALSE,
        List.of(),
        List.of("tool.pyronaut.testResources.sharedServer")
    );
    public static final FieldSpec PYRONAUT_TEST_RESOURCES_SHARED_SERVER_NAMESPACE = string(
        "tool.pyronaut.test-resources.shared-server-namespace",
        "Namespace suffix for the shared server settings directory.",
        null,
        List.of(),
        List.of("tool.pyronaut.testResources.sharedServerNamespace")
    );
    public static final FieldSpec PYRONAUT_TEST_RESOURCES_LOGS_DIR = string(
        "tool.pyronaut.test-resources.logs-dir",
        "Directory for test resources server logs.",
        null,
        List.of(),
        List.of("tool.pyronaut.testResources.logsDir")
    );
    public static final FieldSpec PYRONAUT_TEST_RESOURCES_SERVER_IDLE_TIMEOUT_MINUTES = integer(
        "tool.pyronaut.test-resources.server-idle-timeout-minutes",
        "Idle timeout before the server self-shuts down.",
        null,
        List.of(),
        List.of("tool.pyronaut.testResources.serverIdleTimeoutMinutes")
    );
    public static final FieldSpec PYRONAUT_TEST_RESOURCES_SERVER_SYSTEM_PROPERTIES = stringMap(
        "tool.pyronaut.test-resources.server-system-properties",
        "JVM system properties passed to the test resources server process.",
        List.of("tool.pyronaut.testResources.serverSystemProperties")
    );
    public static final FieldSpec PYRONAUT_TEST_RESOURCES_SERVER_ENVIRONMENT = stringMap(
        "tool.pyronaut.test-resources.server-environment",
        "Environment variables passed to the test resources server process.",
        List.of("tool.pyronaut.testResources.serverEnvironment")
    );
    public static final FieldSpec PYRONAUT_TEST_RESOURCES_DEBUG_SERVER = bool(
        "tool.pyronaut.test-resources.debug-server",
        "Enable debug mode for the test resources server.",
        Boolean.FALSE,
        List.of(),
        List.of("tool.pyronaut.testResources.debugServer")
    );
    public static final FieldSpec PYRONAUT_TEST_RESOURCES_JAVA_EXECUTABLE = string(
        "tool.pyronaut.test-resources.java-executable",
        "Explicit Java executable for the test resources server process.",
        null,
        List.of(),
        List.of("tool.pyronaut.testResources.javaExecutable")
    );
    public static final FieldSpec PYRONAUT_TEST_RESOURCES_STARTUP_OPTIMIZATION = enumString(
        "tool.pyronaut.test-resources.startup-optimization",
        "Startup optimization mode for the test resources server.",
        "none",
        List.of("auto", "leyden", "cds", "none"),
        List.of("tool.pyronaut.testResources.startupOptimization")
    );
    public static final FieldSpec PYRONAUT_TEST_RESOURCES_LEYDEN_JVM_ARGS = stringArray(
        "tool.pyronaut.test-resources.leyden-jvm-args",
        "Leyden JVM arguments used for startup optimization.",
        null,
        List.of(),
        List.of("tool.pyronaut.testResources.leydenJvmArgs")
    );

    public static final List<FieldSpec> FIELDS = List.of(
        PROJECT_NAME,
        PROJECT_VERSION,
        PROJECT_DYNAMIC,
        BUILD_SYSTEM_REQUIRES,
        BUILD_SYSTEM_BUILD_BACKEND,
        PYRONAUT_VERSION,
        PYRONAUT_REPOSITORIES,
        PYRONAUT_DEPENDENCIES_RUNTIME,
        PYRONAUT_DEPENDENCIES_BUILD,
        PYRONAUT_DEPENDENCIES_TEST,
        PYRONAUT_BUILD_MODE,
        PYRONAUT_PROCESSOR_MODE,
        PYRONAUT_TEST_MODE,
        PYRONAUT_BUILD_METADATA_ENABLED,
        PYRONAUT_BUILD_METADATA_VERSION,
        PYRONAUT_BUILD_METADATA_REPOSITORY_URL,
        PYRONAUT_BUILD_METADATA_EXCLUDED_MODULES,
        PYRONAUT_BUILD_DOCKER_IMAGE_NAME,
        PYRONAUT_BUILD_DOCKER_DOCKERFILE,
        PYRONAUT_BUILD_DOCKER_DOCKERFILE_NATIVE,
        PYRONAUT_BUILD_DOCKER_JVM_BASE_IMAGE,
        PYRONAUT_BUILD_DOCKER_NATIVE_BUILDER_IMAGE,
        PYRONAUT_BUILD_DOCKER_NATIVE_BASE_IMAGE,
        PYRONAUT_BUILD_DOCKER_STATIC_NATIVE_BUILDER_IMAGE,
        PYRONAUT_BUILD_DOCKER_STATIC_NATIVE_BASE_IMAGE,
        PYRONAUT_IDE_STUBS_ENABLED,
        PYRONAUT_IDE_STUBS_IDE,
        PYRONAUT_IDE_STUBS_PACKAGES,
        PYRONAUT_IDE_STUBS_EXCLUDE_PATTERNS,
        PYRONAUT_IDE_STUBS_DESTINATION_DIR,
        PYRONAUT_VALIDATION_ENABLED,
        PYRONAUT_VALIDATION_FAIL_ON_NOT_PRESENT,
        PYRONAUT_VALIDATION_DEDUCE_ENVIRONMENTS,
        PYRONAUT_VALIDATION_VALIDATE_DEPENDENCY_INJECTION,
        PYRONAUT_VALIDATION_STRATEGY,
        PYRONAUT_VALIDATION_FORMAT,
        PYRONAUT_VALIDATION_SUPPRESSIONS,
        PYRONAUT_VALIDATION_SUPPRESS_INJECT_ERRORS,
        PYRONAUT_VALIDATION_PROJECT_BASE_DIR,
        PYRONAUT_VALIDATION_RESOURCES_DIRS,
        PYRONAUT_VALIDATION_RUN_ENABLED,
        PYRONAUT_VALIDATION_RUN_ENVIRONMENTS,
        PYRONAUT_VALIDATION_RUN_INCLUDE_DEFAULT_ENVIRONMENT,
        PYRONAUT_VALIDATION_RUN_OVERRIDE_CLASSPATH,
        PYRONAUT_VALIDATION_RUN_CLASSPATH,
        PYRONAUT_VALIDATION_RUN_ADDITIONAL_CLASSPATH,
        PYRONAUT_VALIDATION_RUN_RESOURCES_DIRS,
        PYRONAUT_VALIDATION_RUN_OUTPUT_DIR,
        PYRONAUT_VALIDATION_TEST_ENABLED,
        PYRONAUT_VALIDATION_TEST_ENVIRONMENTS,
        PYRONAUT_VALIDATION_TEST_INCLUDE_DEFAULT_ENVIRONMENT,
        PYRONAUT_VALIDATION_TEST_OVERRIDE_CLASSPATH,
        PYRONAUT_VALIDATION_TEST_CLASSPATH,
        PYRONAUT_VALIDATION_TEST_ADDITIONAL_CLASSPATH,
        PYRONAUT_VALIDATION_TEST_RESOURCES_DIRS,
        PYRONAUT_VALIDATION_TEST_OUTPUT_DIR,
        PYRONAUT_VALIDATION_PRODUCTION_ENABLED,
        PYRONAUT_VALIDATION_PRODUCTION_ENVIRONMENTS,
        PYRONAUT_VALIDATION_PRODUCTION_INCLUDE_DEFAULT_ENVIRONMENT,
        PYRONAUT_VALIDATION_PRODUCTION_OVERRIDE_CLASSPATH,
        PYRONAUT_VALIDATION_PRODUCTION_CLASSPATH,
        PYRONAUT_VALIDATION_PRODUCTION_ADDITIONAL_CLASSPATH,
        PYRONAUT_VALIDATION_PRODUCTION_RESOURCES_DIRS,
        PYRONAUT_VALIDATION_PRODUCTION_OUTPUT_DIR,
        PYRONAUT_TEST_RESOURCES_ENABLED,
        PYRONAUT_TEST_RESOURCES_VERSION,
        PYRONAUT_TEST_RESOURCES_EXPLICIT_PORT,
        PYRONAUT_TEST_RESOURCES_INFER_CLASSPATH,
        PYRONAUT_TEST_RESOURCES_ADDITIONAL_MODULES,
        PYRONAUT_TEST_RESOURCES_CLIENT_TIMEOUT,
        PYRONAUT_TEST_RESOURCES_SHARED_SERVER,
        PYRONAUT_TEST_RESOURCES_SHARED_SERVER_NAMESPACE,
        PYRONAUT_TEST_RESOURCES_LOGS_DIR,
        PYRONAUT_TEST_RESOURCES_SERVER_IDLE_TIMEOUT_MINUTES,
        PYRONAUT_TEST_RESOURCES_SERVER_SYSTEM_PROPERTIES,
        PYRONAUT_TEST_RESOURCES_SERVER_ENVIRONMENT,
        PYRONAUT_TEST_RESOURCES_DEBUG_SERVER,
        PYRONAUT_TEST_RESOURCES_JAVA_EXECUTABLE,
        PYRONAUT_TEST_RESOURCES_STARTUP_OPTIMIZATION,
        PYRONAUT_TEST_RESOURCES_LEYDEN_JVM_ARGS
    );

    public static final List<SectionSpec> STRICT_SECTIONS = List.of(
        section("tool.pyronaut", true),
        section("tool.pyronaut.dependencies", true),
        section("tool.pyronaut.build", true),
        section("tool.pyronaut.processor", true),
        section("tool.pyronaut.build.metadata", true),
        section("tool.pyronaut.build.docker", true),
        PYRONAUT_IDE_STUBS_SECTION,
        section("tool.pyronaut.validation", true),
        section("tool.pyronaut.validation.run", true),
        section("tool.pyronaut.validation.test", true),
        section("tool.pyronaut.validation.production", true),
        PYRONAUT_TEST_RESOURCES_SECTION
    );

    private static FieldSpec string(String canonicalPath, String description) {
        return new FieldSpec(canonicalPath, ValueType.STRING, description, null, List.of(), List.of());
    }

    private static FieldSpec string(String canonicalPath, String description, Object defaultValue, List<String> enumValues, List<String> aliases) {
        return new FieldSpec(canonicalPath, ValueType.STRING, description, defaultValue, enumValues, aliases);
    }

    private static FieldSpec stringArray(String canonicalPath, String description) {
        return new FieldSpec(canonicalPath, ValueType.STRING_ARRAY, description, null, List.of(), List.of());
    }

    private static FieldSpec stringArray(String canonicalPath, String description, Object defaultValue, List<String> enumValues, List<String> aliases) {
        return new FieldSpec(canonicalPath, ValueType.STRING_ARRAY, description, defaultValue, enumValues, aliases);
    }

    private static FieldSpec bool(String canonicalPath, String description) {
        return new FieldSpec(canonicalPath, ValueType.BOOLEAN, description, null, List.of(), List.of());
    }

    private static FieldSpec bool(String canonicalPath, String description, Object defaultValue) {
        return new FieldSpec(canonicalPath, ValueType.BOOLEAN, description, defaultValue, List.of(), List.of());
    }

    private static FieldSpec bool(String canonicalPath, String description, Object defaultValue, List<String> enumValues, List<String> aliases) {
        return new FieldSpec(canonicalPath, ValueType.BOOLEAN, description, defaultValue, enumValues, aliases);
    }

    private static FieldSpec integer(String canonicalPath, String description, Object defaultValue, List<String> enumValues, List<String> aliases) {
        return new FieldSpec(canonicalPath, ValueType.INTEGER, description, defaultValue, enumValues, aliases);
    }

    private static FieldSpec stringMap(String canonicalPath, String description, List<String> aliases) {
        return new FieldSpec(canonicalPath, ValueType.STRING_MAP, description, null, List.of(), aliases);
    }

    private static FieldSpec enumString(String canonicalPath, String description, Object defaultValue, List<String> enumValues) {
        return new FieldSpec(canonicalPath, ValueType.STRING, description, defaultValue, enumValues, List.of());
    }

    private static FieldSpec enumString(String canonicalPath, String description, Object defaultValue, List<String> enumValues, List<String> aliases) {
        return new FieldSpec(canonicalPath, ValueType.STRING, description, defaultValue, enumValues, aliases);
    }

    private static SectionSpec section(String canonicalPath, boolean strict, String... aliases) {
        return new SectionSpec(canonicalPath, List.of(aliases), strict);
    }
}

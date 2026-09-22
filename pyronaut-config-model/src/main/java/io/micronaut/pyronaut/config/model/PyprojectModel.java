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
import java.util.Map;

/**
 * Immutable model for {@code pyproject.toml}.
 *
 * @param project project table
 * @param buildSystem build-system table
 * @param pyronaut tool.pyronaut table
 */
public record PyprojectModel(Project project,
                             BuildSystem buildSystem,
                             Pyronaut pyronaut) {

    /**
     * Project table.
     *
     * @param name project name
     * @param version project version
     * @param dynamic dynamic project fields
     */
    public record Project(String name,
                          String version,
                          List<String> dynamic) {
    }

    /**
     * Build-system table.
     *
     * @param requires build requirements
     * @param buildBackend build backend value
     */
    public record BuildSystem(List<String> requires,
                              String buildBackend) {
    }

    /**
     * tool.pyronaut table.
     *
     * @param coreVersion Micronaut Core BOM version
     * @param platformVersion Micronaut Platform BOM version
     * @param repositories configured repositories
     * @param packaging packaging defaults
     * @param dependencies dependency scopes
     * @param run runtime execution settings
     * @param controlPanel control panel settings
     * @param build build defaults/settings
     * @param processor processor execution settings
     * @param test test execution settings
     * @param sources project source/resource directory settings
     * @param toolchain toolchain resolution settings
     * @param ideStubs IDE stub generation settings
     * @param validation validation settings
     * @param testResources test resources settings
     * @param controlPanelConfigured whether the control-panel section was explicitly configured
     */
    public record Pyronaut(String coreVersion,
                           String platformVersion,
                           List<String> repositories,
                           Packaging packaging,
                           Dependencies dependencies,
                           Run run,
                           ControlPanel controlPanel,
                           Build build,
                           Processor processor,
                           Test test,
                           Sources sources,
                           Toolchain toolchain,
                           IdeStubs ideStubs,
                           Validation validation,
                           TestResources testResources,
                           boolean controlPanelConfigured) {
        public Pyronaut {
            packaging = packaging == null ? new Packaging(null) : packaging;
            if (build != null && !packaging.format().legacyMode().equals(build.mode())) {
                build = new Build(
                    packaging.format().legacyMode(),
                    build.pythonBytecodeEnabled(),
                    build.nativeBase(),
                    build.metadata(),
                    build.docker()
                );
            }
        }

        /**
         * Compatibility constructor for callers compiled against the model before packaging formats were introduced.
         *
         * @param coreVersion Micronaut Core BOM version
         * @param platformVersion Micronaut Platform BOM version
         * @param repositories configured repositories
         * @param dependencies dependency scopes
         * @param run runtime execution settings
         * @param controlPanel control panel settings
         * @param build build defaults/settings
         * @param processor processor execution settings
         * @param test test execution settings
         * @param sources project source/resource directory settings
         * @param toolchain toolchain resolution settings
         * @param ideStubs IDE stub generation settings
         * @param validation validation settings
         * @param testResources test resources settings
         * @param controlPanelConfigured whether the control-panel section was explicitly configured
        */
        @Deprecated(forRemoval = true)
        @SuppressWarnings("checkstyle:ParameterNumber")
        public Pyronaut(String coreVersion,
                        String platformVersion,
                        List<String> repositories,
                        Dependencies dependencies,
                        Run run,
                        ControlPanel controlPanel,
                        Build build,
                        Processor processor,
                        Test test,
                        Sources sources,
                        Toolchain toolchain,
                        IdeStubs ideStubs,
                        Validation validation,
                        TestResources testResources,
                        boolean controlPanelConfigured) {
            this(coreVersion, platformVersion, repositories, packagingFromLegacyBuild(build), dependencies, run,
                controlPanel, build, processor, test, sources, toolchain, ideStubs, validation, testResources,
                controlPanelConfigured);
        }

        private static Packaging packagingFromLegacyBuild(Build build) {
            return new Packaging(build != null && "native".equalsIgnoreCase(build.mode())
                ? PackagingFormat.WHEEL_NATIVE
                : PackagingFormat.WHEEL_JVM);
        }
    }

    /**
     * tool.pyronaut.packaging table.
     *
     * @param format default packaging format
     */
    public record Packaging(PackagingFormat format) {
        public Packaging {
            format = format == null ? PackagingFormat.WHEEL_JVM : format;
        }
    }

    /**
     * Supported production packaging formats.
     */
    public enum PackagingFormat {
        FAT_JAR("fat-jar", "jvm"),
        WHEEL_JVM("wheel-jvm", "jvm"),
        WHEEL_NATIVE("wheel-native", "native"),
        WHEEL_CREMA("wheel-crema", "native"),
        DOCKER_JVM("docker-jvm", "jvm"),
        DOCKER_NATIVE("docker-native", "native"),
        DOCKER_CREMA("docker-crema", "native");

        private final String value;
        private final String legacyMode;

        PackagingFormat(String value, String legacyMode) {
            this.value = value;
            this.legacyMode = legacyMode;
        }

        /**
         * @return the {@code pyproject.toml} value
         */
        public String value() {
            return value;
        }

        /**
         * @return the legacy JVM/native build mode represented by this format
         */
        public String legacyMode() {
            return legacyMode;
        }

        /**
         * Resolves a configuration value.
         *
         * @param value configuration value
         * @return resolved format
         * @throws IllegalArgumentException when the value is unsupported
         */
        public static PackagingFormat fromValue(String value) {
            for (PackagingFormat format : values()) {
                if (format.value.equals(value)) {
                    return format;
                }
            }
            throw new IllegalArgumentException("Unsupported packaging format: " + value);
        }
    }

    /**
     * tool.pyronaut.processor table.
     *
     * @param mode processor execution mode (for example jvm or native)
     * @param incremental whether incremental compilation is enabled
     * @param pythonIncrementalMode handling of dynamic or unresolved Python relationships
     * @param daemon whether the compiler daemon is enabled
     * @param typeCheck the tool.pyronaut.processor.type-check table
     * @param staticCompilation the tool.pyronaut.processor.static-compilation table
     */
    public record Processor(String mode,
                            Boolean incremental,
                            String pythonIncrementalMode,
                            Boolean daemon,
                            TypeCheck typeCheck,
                            StaticCompilation staticCompilation) {
        /**
         * @param mode processor execution mode
         * @param incremental whether incremental compilation is enabled
         * @param pythonIncrementalMode handling of dynamic or unresolved Python relationships
         * @param daemon whether the compiler daemon is enabled
         */
        public Processor(String mode, Boolean incremental, String pythonIncrementalMode, Boolean daemon) {
            this(mode, incremental, pythonIncrementalMode, daemon, TypeCheck.DEFAULT, StaticCompilation.DEFAULT);
        }

        /**
         * @param mode processor execution mode
         */
        public Processor(String mode) {
            this(mode, Boolean.FALSE, "conservative", Boolean.FALSE);
        }

        /**
         * @param mode processor execution mode
         * @param incremental whether incremental compilation is enabled
         */
        public Processor(String mode, Boolean incremental) {
            this(mode, incremental, "conservative", Boolean.FALSE);
        }

        /**
         * @param mode processor execution mode
         * @param incremental whether incremental compilation is enabled
         * @param pythonIncrementalMode handling of dynamic or unresolved Python relationships
         */
        public Processor(String mode, Boolean incremental, String pythonIncrementalMode) {
            this(mode, incremental, pythonIncrementalMode, Boolean.FALSE);
        }
    }

    /**
     * tool.pyronaut.processor.type-check table.
     *
     * @param mode how Python sources are checked against the Java types they use: off, warn or error
     */
    public record TypeCheck(String mode) {
        /**
         * The defaults: no checking.
         */
        public static final TypeCheck DEFAULT = new TypeCheck("off");
    }

    /**
     * tool.pyronaut.processor.static-compilation table.
     *
     * @param mode which Python method bodies are compiled to Java: off, annotated or all
     * @param report the directory the report is written to, relative to the project
     * @param strict whether an explicit CompileStatic that cannot be honoured fails the build
     */
    public record StaticCompilation(String mode, String report, Boolean strict) {
        /**
         * The defaults: no compilation, the report under the project's pyronaut directory.
         */
        public static final StaticCompilation DEFAULT = new StaticCompilation("off", "__pyronaut__/reports/static-compilation", Boolean.FALSE);
    }

    /**
     * tool.pyronaut.test table.
     *
     * @param mode test execution mode (for example jvm or native)
     * @param engine test engine selection
     * @param verbose whether application logs and test framework output stream to the console instead of the report
     */
    public record Test(String mode, TestEngine engine, Boolean verbose) {
        /**
         * Compatibility constructor using the default test engine.
         *
         * @param mode test execution mode
         */
        public Test(String mode) {
            this(mode, TestEngine.BOTH);
        }

        /**
         * Compatibility constructor with captured (non-verbose) test output.
         *
         * @param mode test execution mode
         * @param engine test engine selection
         */
        public Test(String mode, TestEngine engine) {
            this(mode, engine, null);
        }

        /**
         * @return whether test execution streams application logs and test
         * framework output to the console instead of the report
         */
        public boolean verboseEnabled() {
            return Boolean.TRUE.equals(verbose);
        }
    }

    /**
     * Test engines supported by Pyronaut project test execution.
     */
    public enum TestEngine {
        JUNIT,
        PYTEST,
        BOTH
    }

    /**
     * tool.pyronaut.run table.
     *
     * @param bannerEnabled whether the Micronaut banner is printed when running the application
     */
    public record Run(Boolean bannerEnabled) {
    }

    /**
     * tool.pyronaut.control-panel table.
     *
     * @param enabled whether development control panel support is enabled
     * @param path URL path where the control panel is served
     * @param productionEnabled whether control panel support is included in production runtime artifacts
     */
    public record ControlPanel(Boolean enabled,
                               String path,
                               Boolean productionEnabled) {
    }

    /**
     * tool.pyronaut.sources table.
     *
     * @param python Python application sources directory
     * @param pythonTest Python test sources directory
     * @param java Java application sources directory
     * @param javaTest Java test sources directory
     * @param resources application resources directory
     * @param testResources test resources directory
     * @param additionalResources additional application resources directories
     * @param additionalTestResources additional test resources directories
     */
    public record Sources(String python,
                          String pythonTest,
                          String java,
                          String javaTest,
                          String resources,
                          String testResources,
                          List<String> additionalResources,
                          List<String> additionalTestResources) {
        public Sources {
            additionalResources = additionalResources == null ? List.of() : List.copyOf(additionalResources);
            additionalTestResources = additionalTestResources == null ? List.of() : List.copyOf(additionalTestResources);
        }

        public Sources(String python,
                       String pythonTest,
                       String java,
                       String javaTest,
                       String resources,
                       String testResources) {
            this(python, pythonTest, java, javaTest, resources, testResources, List.of(), List.of());
        }
    }

    /**
     * tool.pyronaut.toolchain table.
     *
     * @param distribution desired GraalVM distribution/channel (for example ce, ee, or dev)
     * @param type CLI execution toolchain (jvm or native)
     * @param version desired GraalVM version string
     * @param javaVersion minimum required Java major version
     * @param releaseTag release tag used to resolve dev builds
     * @param downloadUrl explicit archive URL override
     */
    public record Toolchain(String distribution,
                            String type,
                            String version,
                            Integer javaVersion,
                            String releaseTag,
                            String downloadUrl) {
    }

    /**
     * tool.pyronaut.ide-stubs table.
     *
     * @param enabled whether IDE stub generation is enabled
     * @param ide target IDE to configure for the generated stubs
     * @param packages Java packages to render into Python stubs
     * @param excludePatterns fully qualified Java type-name patterns excluded from stub generation
     * @param destinationDir destination directory for generated stubs
     */
    public record IdeStubs(Boolean enabled,
                           String ide,
                           List<String> packages,
                           List<String> excludePatterns,
                           String destinationDir) {
    }

    /**
     * tool.pyronaut.dependencies table.
     *
     * @param runtime runtime dependencies
     * @param developmentRuntime development runtime dependencies
     * @param build build dependencies
     * @param test test dependencies
     * @param boms user-declared Maven BOMs
     * @param exclusions global transitive exclusions
     * @param artifactExclusions per-direct-dependency transitive exclusions
     */
    public record Dependencies(List<String> runtime,
                               List<String> developmentRuntime,
                               List<String> build,
                               List<String> test,
                               List<String> boms,
                               List<String> exclusions,
                               Map<String, List<String>> artifactExclusions) {
        public Dependencies {
            runtime = runtime == null ? List.of() : List.copyOf(runtime);
            developmentRuntime = developmentRuntime == null ? List.of() : List.copyOf(developmentRuntime);
            build = build == null ? List.of() : List.copyOf(build);
            test = test == null ? List.of() : List.copyOf(test);
            boms = boms == null ? List.of() : List.copyOf(boms);
            exclusions = exclusions == null ? List.of() : List.copyOf(exclusions);
            if (artifactExclusions == null || artifactExclusions.isEmpty()) {
                artifactExclusions = Map.of();
            } else {
                var normalized = new java.util.LinkedHashMap<String, List<String>>();
                artifactExclusions.forEach((key, value) -> normalized.put(key, value == null ? List.of() : List.copyOf(value)));
                artifactExclusions = Map.copyOf(normalized);
            }
        }

        public Dependencies(List<String> runtime, List<String> developmentRuntime, List<String> build, List<String> test) {
            this(runtime, developmentRuntime, build, test, List.of(), List.of(), Map.of());
        }
    }

    /**
     * tool.pyronaut.build table.
     *
     * @param mode legacy default build mode, derived from {@link Packaging#format()}
     * @param pythonBytecodeEnabled whether generated Python resources include bytecode caches
     * @param nativeBase local reusable native runtime image path or URL
     * @param metadata native image metadata settings
     * @param docker container image build settings
     */
    public record Build(String mode,
                        Boolean pythonBytecodeEnabled,
                        String nativeBase,
                        Metadata metadata,
                        Docker docker) {
        public Build(String mode, Boolean pythonBytecodeEnabled, Metadata metadata, Docker docker) {
            this(mode, pythonBytecodeEnabled, null, metadata, docker);
        }

        public Build(String mode, Metadata metadata, Docker docker) {
            this(mode, false, null, metadata, docker);
        }

        /**
         * @return the legacy JVM/native build mode
         * @deprecated Use {@link Pyronaut#packaging()} and {@link Packaging#format()}.
         */
        @Deprecated(forRemoval = true)
        @Override
        public String mode() {
            return mode;
        }
    }

    /**
     * tool.pyronaut.build.metadata table.
     *
     * @param enabled whether metadata lookup is enabled
     * @param version metadata repository version override
     * @param repositoryUrl explicit metadata repository URL override
     * @param excludedModules modules excluded from metadata resolution
     */
    public record Metadata(Boolean enabled,
                           String version,
                           String repositoryUrl,
                           List<String> excludedModules) {
    }

    /**
     * tool.pyronaut.build.docker table.
     *
     * @param imageName image name/tag repository prefix
     * @param dockerfile custom Dockerfile for JVM container builds
     * @param dockerfileNative custom Dockerfile for native container builds
     * @param jvmBaseImage JVM runtime base image
     * @param nativeBuilderImage native builder image
     * @param nativeBaseImage native runtime base image for non-static builds
     * @param staticNativeBuilderImage native builder image for static builds
     * @param staticNativeBaseImage native runtime base image for static builds
     * @param baseImage reusable Pyronaut native runtime image
     */
    public record Docker(String imageName,
                         String dockerfile,
                         String dockerfileNative,
                         String jvmBaseImage,
                         String nativeBuilderImage,
                         String nativeBaseImage,
                         String staticNativeBuilderImage,
                         String staticNativeBaseImage,
                         String baseImage) {
        public Docker(String imageName,
                      String dockerfile,
                      String dockerfileNative,
                      String jvmBaseImage,
                      String nativeBuilderImage,
                      String nativeBaseImage,
                      String staticNativeBuilderImage,
                      String staticNativeBaseImage) {
            this(imageName, dockerfile, dockerfileNative, jvmBaseImage, nativeBuilderImage, nativeBaseImage,
                staticNativeBuilderImage, staticNativeBaseImage, null);
        }
    }

    /**
     * tool.pyronaut.validation table.
     *
     * @param enabled whether validation is enabled globally
     * @param failOnNotPresent fail when expected config/classpath entries are missing
     * @param deduceEnvironments infer environments from runtime context
     * @param validateDependencyInjection enable dependency-injection validation
     * @param dependencyInjectionValidationStrategy dependency-injection validation strategy
     * @param format report format (json/html/both)
     * @param suppressions warning/error suppression patterns
     * @param suppressInjectErrors dependency-injection error suppressions
     * @param projectBaseDir project base directory for path normalization
     * @param resourcesDirs default resources directories for validation
     * @param run run scenario validation settings
     * @param test test scenario validation settings
     * @param production production scenario validation settings
     */
    public record Validation(Boolean enabled,
                             Boolean failOnNotPresent,
                             Boolean deduceEnvironments,
                             Boolean validateDependencyInjection,
                             String dependencyInjectionValidationStrategy,
                             String format,
                             List<String> suppressions,
                             List<String> suppressInjectErrors,
                             String projectBaseDir,
                             List<String> resourcesDirs,
                             ValidationScenario run,
                             ValidationScenario test,
                             ValidationScenario production) {
    }

    /**
     * Validation scenario table (run/test/production).
     *
     * @param enabled whether this scenario is enabled
     * @param environments environments applied for this scenario
     * @param includeDefaultEnvironment include Micronaut default environment
     * @param overrideClasspath use explicit classpath instead of inferred classpath
     * @param classpath explicit scenario classpath
     * @param additionalClasspath additional classpath entries appended after base classpath
     * @param resourcesDirs scenario resources directories
     * @param outputDir scenario-specific output directory override
     */
    public record ValidationScenario(Boolean enabled,
                                     List<String> environments,
                                     Boolean includeDefaultEnvironment,
                                     Boolean overrideClasspath,
                                     List<String> classpath,
                                     List<String> additionalClasspath,
                                     List<String> resourcesDirs,
                                     String outputDir) {
    }

    /**
     * tool.pyronaut.testResources table.
     *
     * @param configured whether the testResources table is explicitly present in pyproject.toml
     * @param enabled whether standalone test resources server support is enabled
     * @param version requested test-resources tooling version
     * @param explicitPort explicit server port (otherwise ephemeral)
     * @param inferClasspath infer launcher classpath from runtime
     * @param additionalModules additional test resources modules
     * @param clientTimeout client timeout in seconds
     * @param sharedServer use shared server settings path under user home
     * @param sharedServerNamespace namespace suffix for shared server settings directory
     * @param logsDir directory for test resources server logs
     * @param serverIdleTimeoutMinutes idle timeout before server self-shutdown
     * @param serverSystemProperties JVM system properties passed to server process
     * @param serverEnvironment environment variables passed to server process
     * @param debugServer enable server debug mode
     * @param javaExecutable explicit Java executable for server process
     * @param startupOptimization startup optimization mode (none/auto/leyden/cds)
     * @param leydenJvmArgs Leyden JVM args used for best-effort startup optimization
     */
    public record TestResources(boolean configured,
                                Boolean enabled,
                                String version,
                                Integer explicitPort,
                                Boolean inferClasspath,
                                List<String> additionalModules,
                                Integer clientTimeout,
                                Boolean sharedServer,
                                String sharedServerNamespace,
                                String logsDir,
                                Integer serverIdleTimeoutMinutes,
                                Map<String, String> serverSystemProperties,
                                Map<String, String> serverEnvironment,
                                Boolean debugServer,
                                String javaExecutable,
                                String startupOptimization,
                                List<String> leydenJvmArgs) {
    }
}

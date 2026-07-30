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
     */
    public record Pyronaut(String coreVersion,
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
                           TestResources testResources) {
    }

    /**
     * tool.pyronaut.processor table.
     *
     * @param mode processor execution mode (for example jvm or native)
     */
    public record Processor(String mode) {
    }

    /**
     * tool.pyronaut.test table.
     *
     * @param mode test execution mode (for example jvm or native)
     */
    public record Test(String mode) {
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
     */
    public record Dependencies(List<String> runtime,
                               List<String> developmentRuntime,
                               List<String> build,
                               List<String> test) {
    }

    /**
     * tool.pyronaut.build table.
     *
     * @param mode default build mode (for example jvm or native)
     * @param pythonBytecodeEnabled whether generated Python resources include bytecode caches
     * @param baseImage local reusable native runtime image path
     * @param metadata native image metadata settings
     * @param docker container image build settings
     */
    public record Build(String mode,
                        Boolean pythonBytecodeEnabled,
                        String baseImage,
                        Metadata metadata,
                        Docker docker) {
        public Build(String mode, Boolean pythonBytecodeEnabled, Metadata metadata, Docker docker) {
            this(mode, pythonBytecodeEnabled, null, metadata, docker);
        }

        public Build(String mode, Metadata metadata, Docker docker) {
            this(mode, false, null, metadata, docker);
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

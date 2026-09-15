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
package io.micronaut.pyronaut.validateconfig;

import io.micronaut.pyronaut.config.model.PyprojectModel;
import io.micronaut.pyronaut.config.model.PyprojectModelException;
import io.micronaut.pyronaut.config.model.PyprojectModelReader;
import io.micronaut.pyronaut.config.model.ExternalProjectLayout;
import io.micronaut.pyronaut.config.terminal.LiveRegion;
import io.micronaut.pyronaut.config.terminal.Terminal;
import io.micronaut.pyronaut.logback.PyronautLauncherLogging;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Command line entry point for validating Micronaut configuration in Pyronaut projects.
 */
@CommandLine.Command(name = "pyronaut-validate-config", mixinStandardHelpOptions = true, description = "Validate Micronaut configuration and generate reports")
public final class PyronautValidateConfigMain implements Callable<Integer> {
    private static final int SUCCESS = 0;
    private static final int VALIDATION_ERROR = 1;
    private static final int PRECONDITION_FAILED = 8;
    private static final int INTERNAL_ERROR = 10;
    private static final List<String> DEFAULT_CACHE_IGNORE = List.of("META-INF/*", "logback.xml", "logback-test.xml");
    private static final String TEST_RESOURCES_CLIENT_ARTIFACT = "micronaut-test-resources-client";
    private static final String RUNTIME_DEPENDENCIES_MANIFEST = "__pyronaut__/resolved-runtime-dependencies";
    private static final String TEST_DEPENDENCIES_MANIFEST = "__pyronaut__/resolved-test-dependencies";

    private static final String DEFAULT_SCENARIO = "production";
    private static final List<String> EXTERNAL_DEFAULT_SUPPRESSIONS = List.of("micronaut.config", "micronaut.graalvm", "micronaut.openapi", "micronaut.processing");
    private static final PyprojectModel.ValidationScenario EMPTY_SCENARIO_CONFIG =
        new PyprojectModel.ValidationScenario(null, List.of(), null, null, List.of(), List.of(), List.of(), null);
    private static final PyprojectModel.Validation EMPTY_VALIDATION_CONFIG = new PyprojectModel.Validation(
        null, null, null, null, null, null, List.of(), List.of(), null, List.of(),
        EMPTY_SCENARIO_CONFIG, EMPTY_SCENARIO_CONFIG, EMPTY_SCENARIO_CONFIG
    );
    private static final String MICRONAUT_SECURITY_GROUP_PATH = "/io/micronaut/security/";
    private static final String MICRONAUT_SECURITY_ARTIFACT_PREFIX = "micronaut-security";

    @CommandLine.Option(names = "--project-dir", defaultValue = ".", description = "Project directory")
    Path projectDir = Path.of(".");

    @CommandLine.Option(names = "--scenario", defaultValue = DEFAULT_SCENARIO, description = "Validation scenario: dev|test|production")
    String scenario = DEFAULT_SCENARIO;

    @CommandLine.Option(names = "--classpath", split = "[,;]", description = "Classpath entries")
    List<String> classpath = new ArrayList<>();

    @CommandLine.Option(names = "--env", split = ",", description = "Repeatable environment")
    List<String> env = new ArrayList<>();

    @CommandLine.Option(names = "--environments", split = ",", description = "Comma-separated environments")
    List<String> environments = new ArrayList<>();

    @CommandLine.Option(names = "--suppress", split = ",", description = "Repeatable suppression")
    List<String> suppress = new ArrayList<>();

    @CommandLine.Option(names = "--suppressions", split = ",", description = "Suppression list")
    List<String> suppressions = new ArrayList<>();

    @CommandLine.Option(names = "--suppress-inject-errors", split = ",", description = "Suppressed DI error identifiers")
    List<String> suppressInjectErrors = new ArrayList<>();

    @CommandLine.Option(names = "--fail-on-not-present", negatable = true, description = "Fail when expected classpath/resource entries are missing")
    Boolean failOnNotPresent;

    @CommandLine.Option(names = "--deduce-environments", negatable = true, description = "Deduce environments from classpath")
    Boolean deduceEnvironments;

    @CommandLine.Option(names = "--validate-dependency-injection", negatable = true, description = "Enable dependency-injection validation")
    Boolean validateDependencyInjection;

    @CommandLine.Option(names = "--dependency-injection-validation-strategy", description = "DI strategy: reachable|application-beans|all-beans")
    String dependencyInjectionValidationStrategy;

    @CommandLine.Option(names = "--out", description = "Output report directory")
    Path out;

    @CommandLine.Option(names = "--format", description = "Report format: json|html|both")
    String format;

    @CommandLine.Option(names = "--project-base-dir", description = "Base directory for relative project paths")
    Path projectBaseDir;

    @CommandLine.Option(names = "--resources-dirs", split = "[,;]", description = "Resource directories")
    List<String> resourcesDirs = new ArrayList<>();

    @CommandLine.Option(names = "--no-cache", description = "Disable configuration validation cache")
    boolean noCache;

    private final PyprojectModelReader modelReader;
    private final ConfigurationValidatorExecutor validatorExecutor;

    public PyronautValidateConfigMain() {
        this(new PyprojectModelReader(), new MicronautConfigurationValidatorExecutor());
    }

    PyronautValidateConfigMain(PyprojectModelReader modelReader,
                               ConfigurationValidatorExecutor validatorExecutor) {
        this.modelReader = modelReader;
        this.validatorExecutor = validatorExecutor;
    }

    public static void main(String[] args) {
        PyronautLauncherLogging.initialize();
        int exit = new CommandLine(new PyronautValidateConfigMain()).execute(args);
        if (exit != 0) {
            System.exit(exit);
        }
    }

    static void initializeJavaHomeIfMissing(Supplier<String> javaHomeSupplier) {
        String currentJavaHome = System.getProperty("java.home");
        if (currentJavaHome != null && !currentJavaHome.isBlank()) {
            return;
        }
        String javaHome = javaHomeSupplier.get();
        if (javaHome != null && !javaHome.isBlank()) {
            System.setProperty("java.home", javaHome);
        }
    }

    @Override
    public Integer call() {
        initializeJavaHomeIfMissing(() -> System.getenv("JAVA_HOME"));
        Path root = projectDir.toAbsolutePath().normalize();
        try {
            String normalizedScenario = normalizeScenario(scenario);
            PyprojectModel model = ExternalProjectLayout.isExternal(root)
                ? (Files.isRegularFile(root.resolve("project.toml"))
                    ? modelReader.readProjectToml(root.resolve("project.toml")) : null)
                : modelReader.readProjectDirectory(root);
            ValidationSettings settings = ExternalProjectLayout.isExternal(root)
                ? resolveExternalSettings(root, normalizedScenario, model) : resolveSettings(root, model, normalizedScenario);
            if (model != null) {
                validateProductionControlPanelSecurity(model, settings);
            }
            if (!settings.enabled()) {
                System.out.println("Configuration validation disabled for scenario '" + normalizedScenario + "'.");
                return SUCCESS;
            }

            Files.createDirectories(settings.outputDir());
            Path cacheFile = (ExternalProjectLayout.isExternal(root) ? ExternalProjectLayout.outputDirectory(root) : root.resolve("__pyronaut__"))
                .resolve(".config-validation-cache.properties");
            String classpathFingerprint = ConfigurationValidationCache.fingerprintClasspath(settings.classpathElements());
            String resourcesFingerprint = ConfigurationValidationCache.fingerprintResources(settings.resourcesDirs(), DEFAULT_CACHE_IGNORE);
            String inputsFingerprint = settings.inputsFingerprint(classpathFingerprint);

            if (!noCache) {
                ConfigurationValidationCache.CacheEntry cache = ConfigurationValidationCache.readIfUpToDate(cacheFile, inputsFingerprint, resourcesFingerprint);
                if (cache != null) {
                    if (cache.lastResult() == ConfigurationValidationCache.LastResult.FAILURE) {
                        System.err.println("Configuration validation failed (cached). Report directory: " + settings.outputDir());
                        return VALIDATION_ERROR;
                    }
                    passed(null, "(cached)");
                    return SUCCESS;
                }
            }

            cleanupStaleReports(settings.outputDir(), settings.format());
            long started = System.nanoTime();
            ValidationExecutionResult result;
            try (ValidationProgress progress = ValidationProgress.start()) {
                result = validatorExecutor.validate(settings);
            }
            if (!noCache) {
                ConfigurationValidationCache.write(
                    cacheFile,
                    inputsFingerprint,
                    resourcesFingerprint,
                    result.hasErrors() ? ConfigurationValidationCache.LastResult.FAILURE : ConfigurationValidationCache.LastResult.SUCCESS
                );
            }

            if (result.hasErrors()) {
                failed("Configuration validation failed. See reports in " + settings.outputDir(), System.nanoTime() - started);
                return VALIDATION_ERROR;
            }
            passed(System.nanoTime() - started, null);
            return SUCCESS;
        } catch (PyprojectModelException e) {
            System.err.println(e.getMessage());
            return PRECONDITION_FAILED;
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return CommandLine.ExitCode.USAGE;
        } catch (Exception e) {
            if (isTraceEnabled()) {
                e.printStackTrace(System.err);
            }
            System.err.println("validate-config failed: " + e.getMessage());
            return INTERNAL_ERROR;
        }
    }

    /**
     * Report success: a stamped line on the terminal, or the plain message on
     * standard output for scripts and logs.
     */
    private static void passed(Long tookNanos, String suffix) {
        if (Terminal.isInteractive()) {
            LiveRegion region = ValidationProgress.region(null);
            region.output().println(region.stamp(LiveRegion.GREEN, region.glyphs().check(), "Configuration validation passed",
                suffix != null ? suffix : tookNanos == null ? null : "(" + Terminal.formatDuration(tookNanos) + ")"));
            region.close();
            return;
        }
        System.out.println("Configuration validation passed.");
    }

    private static void failed(String message, long tookNanos) {
        if (Terminal.isInteractive()) {
            LiveRegion region = ValidationProgress.region(null);
            region.output().println(region.stamp(LiveRegion.RED, region.glyphs().cross(), message, tookNanos));
            region.close();
            return;
        }
        System.err.println(message);
    }

    private ValidationSettings resolveExternalSettings(Path root, String normalizedScenario, PyprojectModel model) throws IOException {
        ExternalProjectLayout layout = ExternalProjectLayout.read(root);
        Path output = ExternalProjectLayout.outputDirectory(root);
        LinkedHashSet<String> classpath = new LinkedHashSet<>();
        if ("test".equals(normalizedScenario)) {
            layout.testClasspath().forEach(path -> classpath.add(path.toString()));
            classpath.add(output.resolve("test-classes").toString());
        } else {
            ("dev".equals(normalizedScenario) ? layout.developmentRuntimeClasspath() : layout.runtimeClasspath())
                .forEach(path -> classpath.add(path.toString()));
        }
        classpath.add(output.resolve("classes").toString());
        List<Path> resources = new ArrayList<>(layout.mainResources());
        if ("dev".equals(normalizedScenario) || "test".equals(normalizedScenario)) {
            resources.addAll(layout.testResources());
        }
        if (!resourcesDirs.isEmpty()) {
            resources = new ArrayList<>();
            for (String entry : resourcesDirs) {
                resources.add(root.resolve(entry).normalize());
            }
        }
        // External build layouts keep resources separate from compiled classes. Include
        // them on the validator classpath so application.properties/yaml are actually
        // loaded during configuration validation.
        resources.forEach(path -> classpath.add(path.toString()));

        // Apply the same CLI-over-configuration precedence as pyproject projects, using an
        // empty configuration when the external project has no project.toml.
        PyprojectModel.Validation validation = model == null ? EMPTY_VALIDATION_CONFIG : model.pyronaut().validation();
        PyprojectModel.ValidationScenario scenarioConfig = scenarioConfig(validation, normalizedScenario);
        EffectiveOptions options = resolveEffectiveOptions(validation, scenarioConfig, model == null);
        List<String> effectiveEnvironments = model == null
            ? mergeEnvironments("dev".equals(normalizedScenario) ? List.of("dev") : "test".equals(normalizedScenario) ? List.of("test") : List.of())
            : resolveEnvironments(scenarioConfig, normalizedScenario);
        LinkedHashSet<String> effectiveSuppressions = new LinkedHashSet<>(
            model == null ? EXTERNAL_DEFAULT_SUPPRESSIONS : mergeSuppressions(validation)
        );
        addNonBlank(effectiveSuppressions, suppressions);
        addNonBlank(effectiveSuppressions, suppress);
        Path outputDir = out != null
            ? root.resolve(out).normalize()
            : output.resolve("reports/config-validation").resolve(normalizedScenario);
        return new ValidationSettings(options.enabled(), options.failOnNotPresent(),
            options.deduceEnvironments(), options.validateDependencyInjection(),
            options.dependencyInjectionValidationStrategy(),
            options.format(),
            outputDir, resolveProjectBaseDir(root, validation),
            effectiveEnvironments,
            List.copyOf(classpath), List.copyOf(resources),
            List.copyOf(effectiveSuppressions),
            mergeSuppressInjectErrors(validation), normalizedScenario);
    }

    private static PyprojectModel.ValidationScenario scenarioConfig(PyprojectModel.Validation validation, String normalizedScenario) {
        return switch (normalizedScenario) {
            case "dev", "run" -> validation.run();
            case "test" -> validation.test();
            default -> validation.production();
        };
    }

    private EffectiveOptions resolveEffectiveOptions(PyprojectModel.Validation validation,
                                                     PyprojectModel.ValidationScenario scenarioConfig,
                                                     boolean defaultValidateDependencyInjection) {
        boolean enabled = valueOrDefault(validation.enabled(), true) && valueOrDefault(scenarioConfig.enabled(), true);
        boolean effectiveFailOnNotPresent = failOnNotPresent != null ? failOnNotPresent : valueOrDefault(validation.failOnNotPresent(), true);
        boolean effectiveDeduceEnvironments = deduceEnvironments != null ? deduceEnvironments : valueOrDefault(validation.deduceEnvironments(), false);
        boolean effectiveValidateDi = validateDependencyInjection != null
            ? validateDependencyInjection
            : valueOrDefault(validation.validateDependencyInjection(), defaultValidateDependencyInjection);

        String strategyRaw = dependencyInjectionValidationStrategy != null
            ? dependencyInjectionValidationStrategy
            : validation.dependencyInjectionValidationStrategy();
        String effectiveDiStrategy = normalizeDiStrategy(strategyRaw);
        String formatRaw = format != null ? format : validation.format();
        ReportFormat effectiveFormat = normalizeFormat(formatRaw);
        return new EffectiveOptions(
            enabled,
            effectiveFailOnNotPresent,
            effectiveDeduceEnvironments,
            effectiveValidateDi,
            effectiveDiStrategy,
            effectiveFormat
        );
    }

    private Path resolveProjectBaseDir(Path root, PyprojectModel.Validation validation) {
        if (projectBaseDir != null) {
            return root.resolve(projectBaseDir).normalize();
        }
        return validation.projectBaseDir() != null ? root.resolve(validation.projectBaseDir()).normalize() : root;
    }

    private static boolean isTraceEnabled() {
        String value = System.getenv("PYRONAUT_VALIDATE_CONFIG_TRACE");
        return value != null && !value.isBlank() && !"false".equalsIgnoreCase(value);
    }

    private ValidationSettings resolveSettings(Path root,
                                               PyprojectModel model,
                                               String normalizedScenario) throws IOException {
        PyprojectModel.Validation validation = model.pyronaut().validation();
        PyprojectModel.ValidationScenario scenarioConfig = scenarioConfig(validation, normalizedScenario);
        EffectiveOptions options = resolveEffectiveOptions(validation, scenarioConfig, false);

        List<String> effectiveEnvironments = resolveEnvironments(scenarioConfig, normalizedScenario);
        List<String> effectiveClasspath = resolveClasspath(root, normalizedScenario, scenarioConfig);
        List<Path> effectiveResources = resolveResourcesDirs(root, validation, scenarioConfig);
        Path effectiveProjectBaseDir = resolveProjectBaseDir(root, validation);
        Path outputDir = resolveOutputDir(root, scenarioConfig, normalizedScenario);

        return new ValidationSettings(
            options.enabled(),
            options.failOnNotPresent(),
            options.deduceEnvironments(),
            options.validateDependencyInjection(),
            options.dependencyInjectionValidationStrategy(),
            options.format(),
            outputDir,
            effectiveProjectBaseDir,
            effectiveEnvironments,
            effectiveClasspath,
            effectiveResources,
            mergeSuppressions(validation),
            mergeSuppressInjectErrors(validation),
            normalizedScenario
        );
    }

    private List<String> resolveEnvironments(PyprojectModel.ValidationScenario scenarioConfig, String scenario) {
        return mergeEnvironments("run".equals(scenario) ? List.of() : scenarioConfig.environments());
    }

    private List<String> mergeEnvironments(List<String> configured) {
        Set<String> resolved = new LinkedHashSet<>(configured);
        resolved.addAll(env);
        resolved.addAll(environments);
        resolved.removeIf(String::isBlank);
        return List.copyOf(resolved);
    }

    private List<String> resolveClasspath(Path root,
                                          String normalizedScenario,
                                          PyprojectModel.ValidationScenario scenarioConfig) throws IOException {
        Set<String> resolved = new LinkedHashSet<>();
        if (!classpath.isEmpty()) {
            for (String entry : classpath) {
                resolved.add(root.resolve(entry).normalize().toString());
            }
            return List.copyOf(resolved);
        }
        if (valueOrDefault(scenarioConfig.overrideClasspath(), false) && !scenarioConfig.classpath().isEmpty()) {
            for (String entry : scenarioConfig.classpath()) {
                resolved.add(root.resolve(entry).normalize().toString());
            }
        } else if ("test".equals(normalizedScenario)) {
            // The test manifest is the complete test classpath (including the
            // application runtime graph). Never substitute the development
            // graph, which may contain launcher-only Control Panel jars.
            resolved.addAll(readClasspathManifest(root.resolve(TEST_DEPENDENCIES_MANIFEST)));
            resolved.addAll(readClasspathManifest(root.resolve(RUNTIME_DEPENDENCIES_MANIFEST)));
            resolved.addAll(readClasspathManifest(root.resolve("__pyronaut__/resolved-build-dependencies")));
            Path testClasses = root.resolve("__pyronaut__/test-classes");
            resolved.add((Files.isDirectory(testClasses) ? testClasses : root.resolve("__pyronaut__/classes")).toString());
        } else if ("run".equals(normalizedScenario)) {
            // Do not use resolved-development-runtime-dependencies here.
            // That manifest contains optional launcher-only jars (notably
            // Control Panel) which must not affect application validation.
            resolved.addAll(readClasspathManifest(root.resolve(RUNTIME_DEPENDENCIES_MANIFEST)));
            resolved.add(root.resolve("__pyronaut__/classes").toString());
        } else {
            resolved.addAll(readClasspathManifest(root.resolve(RUNTIME_DEPENDENCIES_MANIFEST)));
            resolved.add(root.resolve("__pyronaut__/classes").toString());
        }
        for (String additional : scenarioConfig.additionalClasspath()) {
            resolved.add(root.resolve(additional).normalize().toString());
        }
        return List.copyOf(resolved);
    }

    private List<Path> resolveResourcesDirs(Path root,
                                            PyprojectModel.Validation validation,
                                            PyprojectModel.ValidationScenario scenarioConfig) {
        List<String> configured = new ArrayList<>();
        if (!resourcesDirs.isEmpty()) {
            configured.addAll(resourcesDirs);
        } else if (!scenarioConfig.resourcesDirs().isEmpty()) {
            configured.addAll(scenarioConfig.resourcesDirs());
        } else {
            configured.addAll(validation.resourcesDirs());
        }
        List<Path> resolved = new ArrayList<>(configured.size());
        for (String entry : configured) {
            resolved.add(root.resolve(entry).normalize());
        }
        return List.copyOf(resolved);
    }

    private Path resolveOutputDir(Path root,
                                  PyprojectModel.ValidationScenario scenarioConfig,
                                  String normalizedScenario) {
        if (out != null) {
            return root.resolve(out).normalize();
        }
        if (scenarioConfig.outputDir() != null && !scenarioConfig.outputDir().isBlank()) {
            return root.resolve(scenarioConfig.outputDir()).normalize();
        }
        return root.resolve("__pyronaut__/reports/config-validation").resolve(normalizedScenario).normalize();
    }

    private List<String> mergeSuppressions(PyprojectModel.Validation validation) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        merged.add("micronaut.config");
        merged.add("micronaut.graalvm");
        merged.add("micronaut.openapi");
        merged.add("micronaut.processing");
        merged.add("endpoints.*");
        merged.add("logger.levels.*");
        addNonBlank(merged, validation.suppressions());
        addNonBlank(merged, suppressions);
        addNonBlank(merged, suppress);
        return List.copyOf(merged);
    }

    private static void addNonBlank(Set<String> target, List<String> values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                target.add(value);
            }
        }
    }

    private List<String> mergeSuppressInjectErrors(PyprojectModel.Validation validation) {
        List<String> merged = new ArrayList<>();
        merged.addAll(validation.suppressInjectErrors());
        merged.addAll(suppressInjectErrors);
        return List.copyOf(merged);
    }

    private static List<String> readClasspathManifest(Path manifest) throws IOException {
        if (!Files.exists(manifest)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String line : Files.readAllLines(manifest)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !isForbiddenClasspathEntry(trimmed)) {
                values.add(Path.of(trimmed).toAbsolutePath().normalize().toString());
            }
        }
        return List.copyOf(values);
    }

    private static boolean isForbiddenClasspathEntry(String entry) {
        String normalized = entry.replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.contains("/" + TEST_RESOURCES_CLIENT_ARTIFACT + "-")
            || normalized.endsWith("/" + TEST_RESOURCES_CLIENT_ARTIFACT + ".jar")
            || normalized.equals(TEST_RESOURCES_CLIENT_ARTIFACT)
            || normalized.contains(":" + TEST_RESOURCES_CLIENT_ARTIFACT + ":");
    }

    private static void cleanupStaleReports(Path outputDir, ReportFormat format) throws IOException {
        Path html = outputDir.resolve("configuration-errors.html");
        Path json = outputDir.resolve("configuration-errors.json");
        if (format != ReportFormat.HTML && format != ReportFormat.BOTH) {
            Files.deleteIfExists(html);
        }
        if (format != ReportFormat.JSON && format != ReportFormat.BOTH) {
            Files.deleteIfExists(json);
        }
    }

    private static String normalizeScenario(String raw) {
        String normalized = raw == null ? DEFAULT_SCENARIO : raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("dev") || normalized.equals("run") || normalized.equals("test") || normalized.equals("production")) {
            return normalized;
        }
        throw new IllegalArgumentException("Invalid value for --scenario. Use dev|run|test|production");
    }

    private static ReportFormat normalizeFormat(String raw) {
        String normalized = raw == null ? "both" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "json" -> ReportFormat.JSON;
            case "html" -> ReportFormat.HTML;
            case "both" -> ReportFormat.BOTH;
            default -> throw new IllegalArgumentException("Invalid value for --format. Use json|html|both");
        };
    }

    private static String normalizeDiStrategy(String raw) {
        String normalized = raw == null ? "reachable" : raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("reachable") || normalized.equals("application-beans") || normalized.equals("all-beans")) {
            return normalized;
        }
        throw new IllegalArgumentException("Invalid value for --dependency-injection-validation-strategy. Use reachable|application-beans|all-beans");
    }

    private static boolean valueOrDefault(Boolean value, boolean fallback) {
        return value == null ? fallback : value;
    }

    private static void validateProductionControlPanelSecurity(PyprojectModel model,
                                                               ValidationSettings settings) {
        if (!"production".equals(settings.scenario()) || model.pyronaut() == null || model.pyronaut().controlPanel() == null) {
            return;
        }
        if (!Boolean.TRUE.equals(model.pyronaut().controlPanel().productionEnabled())) {
            return;
        }
        if (settings.classpathElements().stream().noneMatch(PyronautValidateConfigMain::isMicronautSecurityClasspathEntry)) {
            throw new PyprojectModelException(
                "tool.pyronaut.control-panel.production-enabled requires a Micronaut Security runtime dependency"
            );
        }
    }

    private static boolean isMicronautSecurityClasspathEntry(String entry) {
        String normalized = entry.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalized.contains(MICRONAUT_SECURITY_GROUP_PATH)
            && normalized.contains("/" + MICRONAUT_SECURITY_ARTIFACT_PREFIX)) {
            return true;
        }
        return normalized.contains("io.micronaut.security:")
            && normalized.contains(":" + MICRONAUT_SECURITY_ARTIFACT_PREFIX);
    }

    interface ConfigurationValidatorExecutor {
        ValidationExecutionResult validate(ValidationSettings settings) throws Exception;
    }

    enum ReportFormat {
        JSON,
        HTML,
        BOTH
    }

    record ValidationSettings(boolean enabled,
                              boolean failOnNotPresent,
                              boolean deduceEnvironments,
                              boolean validateDependencyInjection,
                              String dependencyInjectionValidationStrategy,
                              ReportFormat format,
                              Path outputDir,
                              Path projectBaseDir,
                              List<String> environments,
                              List<String> classpathElements,
                              List<Path> resourcesDirs,
                              List<String> suppressions,
                              List<String> suppressedInjectErrors,
                              String scenario) {
        String classpath() {
            return String.join(java.io.File.pathSeparator, classpathElements);
        }

        String inputsFingerprint(String classpathFingerprint) {
            return String.join("|",
                scenario,
                environments.toString(),
                suppressions.toString(),
                suppressedInjectErrors.toString(),
                Boolean.toString(failOnNotPresent),
                Boolean.toString(deduceEnvironments),
                Boolean.toString(validateDependencyInjection),
                dependencyInjectionValidationStrategy,
                format.name(),
                outputDir.toString(),
                projectBaseDir.toString(),
                resourcesDirs.toString(),
                classpath(),
                classpathFingerprint
            );
        }
    }

    private record EffectiveOptions(boolean enabled,
                                    boolean failOnNotPresent,
                                    boolean deduceEnvironments,
                                    boolean validateDependencyInjection,
                                    String dependencyInjectionValidationStrategy,
                                    ReportFormat format) {
    }

    record ValidationExecutionResult(boolean hasErrors) {
    }

    /**
     * Spinner shown while the Micronaut configuration validator runs.
     */
    private static final class ValidationProgress implements AutoCloseable {
        private final LiveRegion region;
        private final long startedNanos = System.nanoTime();

        private ValidationProgress(LiveRegion region) {
            this.region = region;
        }

        static ValidationProgress start() {
            if (!Terminal.isInteractive()) {
                return new ValidationProgress(null);
            }
            ValidationProgress[] holder = new ValidationProgress[1];
            LiveRegion region = region((liveRegion, frame, width) -> List.of(
                liveRegion.headerRow(frame, "Validating configuration", null, System.nanoTime() - holder[0].startedNanos, width)));
            holder[0] = new ValidationProgress(region);
            region.refresh();
            return holder[0];
        }

        static LiveRegion region(LiveRegion.Frame frame) {
            boolean tty = Terminal.isInteractive();
            boolean unicode = tty && Terminal.unicodeSupported();
            PrintStream output = unicode ? new PrintStream(System.err, true, StandardCharsets.UTF_8) : System.err;
            return new LiveRegion(output, tty && frame != null, Terminal.colorEnabled("auto", tty), unicode,
                frame == null ? (liveRegion, spinnerFrame, width) -> List.of() : frame);
        }

        @Override
        public void close() {
            if (region != null) {
                region.close();
            }
        }
    }
}

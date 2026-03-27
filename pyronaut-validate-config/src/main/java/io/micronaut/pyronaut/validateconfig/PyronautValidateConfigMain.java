/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package io.micronaut.pyronaut.validateconfig;

import io.micronaut.pyronaut.config.model.PyprojectModel;
import io.micronaut.pyronaut.config.model.PyprojectModelException;
import io.micronaut.pyronaut.config.model.PyprojectModelReader;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "pyronaut-validate-config", mixinStandardHelpOptions = true, description = "Validate Micronaut configuration and generate reports")
public final class PyronautValidateConfigMain implements Callable<Integer> {
    private static final int SUCCESS = 0;
    private static final int VALIDATION_ERROR = 1;
    private static final int PRECONDITION_FAILED = 8;
    private static final int INTERNAL_ERROR = 10;
    private static final List<String> DEFAULT_CACHE_IGNORE = List.of("META-INF/*", "logback.xml", "logback-test.xml");

    private static final String DEFAULT_SCENARIO = "production";

    @CommandLine.Option(names = "--project-dir", defaultValue = ".", description = "Project directory")
    Path projectDir = Path.of(".");

    @CommandLine.Option(names = "--scenario", defaultValue = DEFAULT_SCENARIO, description = "Validation scenario: run|test|production")
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
        int exit = new CommandLine(new PyronautValidateConfigMain()).execute(args);
        if (exit != 0) {
            System.exit(exit);
        }
    }

    @Override
    public Integer call() {
        Path root = projectDir.toAbsolutePath().normalize();
        try {
            String normalizedScenario = normalizeScenario(scenario);
            PyprojectModel model = modelReader.readProjectDirectory(root);
            ValidationSettings settings = resolveSettings(root, model, normalizedScenario);
            if (!settings.enabled()) {
                System.out.println("Configuration validation disabled for scenario '" + normalizedScenario + "'.");
                return SUCCESS;
            }

            Files.createDirectories(settings.outputDir());
            Path cacheFile = root.resolve("__pyronaut__").resolve(".config-validation-cache.properties");
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
                    System.out.println("Configuration validation up to date (cache hit). Reports in " + settings.outputDir());
                    return SUCCESS;
                }
            }

            cleanupStaleReports(settings.outputDir(), settings.format());
            ValidationExecutionResult result = validatorExecutor.validate(settings);
            if (!noCache) {
                ConfigurationValidationCache.write(
                    cacheFile,
                    inputsFingerprint,
                    resourcesFingerprint,
                    result.hasErrors() ? ConfigurationValidationCache.LastResult.FAILURE : ConfigurationValidationCache.LastResult.SUCCESS
                );
            }

            if (result.hasErrors()) {
                System.err.println("Configuration validation failed. See reports in " + settings.outputDir());
                return VALIDATION_ERROR;
            }
            System.out.println("Configuration validation passed. Reports written to " + settings.outputDir());
            return SUCCESS;
        } catch (PyprojectModelException e) {
            System.err.println(e.getMessage());
            return PRECONDITION_FAILED;
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            return CommandLine.ExitCode.USAGE;
        } catch (Exception e) {
            System.err.println("validate-config failed: " + e.getMessage());
            return INTERNAL_ERROR;
        }
    }

    private ValidationSettings resolveSettings(Path root,
                                               PyprojectModel model,
                                               String normalizedScenario) throws IOException {
        PyprojectModel.Validation validation = model.pyronaut().validation();
        PyprojectModel.ValidationScenario scenarioConfig = switch (normalizedScenario) {
            case "run" -> validation.run();
            case "test" -> validation.test();
            default -> validation.production();
        };

        boolean enabled = valueOrDefault(validation.enabled(), true) && valueOrDefault(scenarioConfig.enabled(), true);
        boolean effectiveFailOnNotPresent = failOnNotPresent != null ? failOnNotPresent : valueOrDefault(validation.failOnNotPresent(), true);
        boolean effectiveDeduceEnvironments = deduceEnvironments != null ? deduceEnvironments : valueOrDefault(validation.deduceEnvironments(), false);
        boolean effectiveValidateDi = validateDependencyInjection != null
            ? validateDependencyInjection
            : valueOrDefault(validation.validateDependencyInjection(), false);

        String strategyRaw = dependencyInjectionValidationStrategy != null
            ? dependencyInjectionValidationStrategy
            : validation.dependencyInjectionValidationStrategy();
        String effectiveDiStrategy = normalizeDiStrategy(strategyRaw);
        String formatRaw = format != null ? format : validation.format();
        ReportFormat effectiveFormat = normalizeFormat(formatRaw);

        List<String> effectiveEnvironments = resolveEnvironments(scenarioConfig);
        List<String> effectiveClasspath = resolveClasspath(root, normalizedScenario, scenarioConfig);
        List<Path> effectiveResources = resolveResourcesDirs(root, validation, scenarioConfig);
        Path effectiveProjectBaseDir = projectBaseDir != null
            ? root.resolve(projectBaseDir).normalize()
            : (validation.projectBaseDir() != null ? root.resolve(validation.projectBaseDir()).normalize() : root);
        Path outputDir = resolveOutputDir(root, scenarioConfig, normalizedScenario);

        return new ValidationSettings(
            enabled,
            effectiveFailOnNotPresent,
            effectiveDeduceEnvironments,
            effectiveValidateDi,
            effectiveDiStrategy,
            effectiveFormat,
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

    private List<String> resolveEnvironments(PyprojectModel.ValidationScenario scenarioConfig) {
        Set<String> resolved = new LinkedHashSet<>();
        resolved.addAll(scenarioConfig.environments());
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
            resolved.addAll(readClasspathManifest(root.resolve("__pyronaut__/resolved-test-dependencies")));
            resolved.addAll(readClasspathManifest(root.resolve("__pyronaut__/resolved-runtime-dependencies")));
            resolved.addAll(readClasspathManifest(root.resolve("__pyronaut__/resolved-build-dependencies")));
            Path testClasses = root.resolve("__pyronaut__/test-classes");
            resolved.add((Files.isDirectory(testClasses) ? testClasses : root.resolve("__pyronaut__/classes")).toString());
        } else {
            resolved.addAll(readClasspathManifest(root.resolve("__pyronaut__/resolved-runtime-dependencies")));
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
            if (!trimmed.isEmpty()) {
                values.add(Path.of(trimmed).toAbsolutePath().normalize().toString());
            }
        }
        return List.copyOf(values);
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
        if (normalized.equals("run") || normalized.equals("test") || normalized.equals("production")) {
            return normalized;
        }
        throw new IllegalArgumentException("Invalid value for --scenario. Use run|test|production");
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
                classpath(),
                classpathFingerprint
            );
        }
    }

    record ValidationExecutionResult(boolean hasErrors) {
    }
}

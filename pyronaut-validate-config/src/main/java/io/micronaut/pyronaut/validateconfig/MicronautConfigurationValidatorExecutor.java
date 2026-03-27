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

import io.micronaut.json.JsonMapper;
import io.micronaut.jsonschema.configuration.validator.ConfigurationError;
import io.micronaut.jsonschema.configuration.validator.ConfigurationJsonSchemaValidator;
import io.micronaut.jsonschema.configuration.validator.DependencyInjectionError;
import io.micronaut.jsonschema.configuration.validator.DependencyInjectionValidationStrategy;
import io.micronaut.jsonschema.configuration.validator.cli.DependencyInjectionConfigurationValidator;
import io.micronaut.jsonschema.configuration.validator.cli.JsonSchemaConfigurationValidator;
import io.micronaut.jsonschema.configuration.validator.report.HtmlConfigurationErrorReporter;
import io.micronaut.jsonschema.configuration.validator.report.JsonConfigurationErrorReporter;
import io.micronaut.jsonschema.configuration.validator.report.SystemErrConfigurationErrorReporter;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

final class MicronautConfigurationValidatorExecutor implements PyronautValidateConfigMain.ConfigurationValidatorExecutor {

    @Override
    public PyronautValidateConfigMain.ValidationExecutionResult validate(PyronautValidateConfigMain.ValidationSettings settings) throws Exception {
        Files.createDirectories(settings.outputDir());

        ConfigurationJsonSchemaValidator validator = new ConfigurationJsonSchemaValidator();
        validator.setFailOnNotPresent(settings.failOnNotPresent());
        if (!settings.suppressions().isEmpty()) {
            validator.setSuppressionPatterns(settings.suppressions());
        }

        JsonSchemaConfigurationValidator facade = JsonSchemaConfigurationValidator.forClasspath(
            settings.classpath(),
            settings.environments(),
            settings.deduceEnvironments(),
            validator
        );

        Set<ConfigurationError> errors = facade.validate();
        Set<DependencyInjectionError> dependencyInjectionErrors = Set.of();

        if (settings.validateDependencyInjection()) {
            dependencyInjectionErrors = DependencyInjectionConfigurationValidator.forClasspath(
                settings.classpath(),
                settings.environments(),
                settings.deduceEnvironments(),
                settings.suppressedInjectErrors(),
                strategy(settings.dependencyInjectionValidationStrategy())
            ).validate();
        }

        Path jsonFile = null;
        Path htmlFile = null;
        if (settings.format() == PyronautValidateConfigMain.ReportFormat.JSON || settings.format() == PyronautValidateConfigMain.ReportFormat.BOTH) {
            jsonFile = settings.outputDir().resolve("configuration-errors.json");
            try (OutputStream os = Files.newOutputStream(jsonFile)) {
                new JsonConfigurationErrorReporter(JsonMapper.createDefault(), os).report(errors, dependencyInjectionErrors);
            }
        }
        if (settings.format() == PyronautValidateConfigMain.ReportFormat.HTML || settings.format() == PyronautValidateConfigMain.ReportFormat.BOTH) {
            htmlFile = settings.outputDir().resolve("configuration-errors.html");
            try (OutputStream os = Files.newOutputStream(htmlFile)) {
                new HtmlConfigurationErrorReporter(os).report(errors, dependencyInjectionErrors);
            }
        }

        new SystemErrConfigurationErrorReporter(System.err, htmlFile, jsonFile, settings.projectBaseDir(), settings.resourcesDirs())
            .report(errors, dependencyInjectionErrors);

        boolean hasErrors = errors.stream().anyMatch(e -> e.type() == ConfigurationError.Type.ERROR) || !dependencyInjectionErrors.isEmpty();
        return new PyronautValidateConfigMain.ValidationExecutionResult(hasErrors);
    }

    private static DependencyInjectionValidationStrategy strategy(String value) {
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "reachable" -> DependencyInjectionValidationStrategy.REACHABLE;
            case "application-beans" -> DependencyInjectionValidationStrategy.APPLICATION_BEANS;
            case "all-beans" -> DependencyInjectionValidationStrategy.ALL_BEANS;
            default -> throw new IllegalArgumentException("Invalid value for --dependency-injection-validation-strategy. Use reachable|application-beans|all-beans");
        };
    }
}

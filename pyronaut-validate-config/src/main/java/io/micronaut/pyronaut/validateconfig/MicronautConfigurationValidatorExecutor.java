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
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class MicronautConfigurationValidatorExecutor implements PyronautValidateConfigMain.ConfigurationValidatorExecutor {
    private static final String DEFAULT_ENVIRONMENT_LOGGER = "org.slf4j.simpleLogger.log.io.micronaut.context.env.DefaultEnvironment";

    @Override
    public PyronautValidateConfigMain.ValidationExecutionResult validate(PyronautValidateConfigMain.ValidationSettings settings) throws Exception {
        Files.createDirectories(settings.outputDir());
        suppressDefaultEnvironmentLogging();
        System.out.println(validationMessage(settings));

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

        printSuppressionSnippet(System.err, suppressionPatterns(errors));

        boolean hasErrors = errors.stream().anyMatch(e -> e.type() == ConfigurationError.Type.ERROR) || !dependencyInjectionErrors.isEmpty();
        return new PyronautValidateConfigMain.ValidationExecutionResult(hasErrors);
    }

    private static void suppressDefaultEnvironmentLogging() {
        System.setProperty(DEFAULT_ENVIRONMENT_LOGGER, "error");
    }

    private static String validationMessage(PyronautValidateConfigMain.ValidationSettings settings) {
        if (!settings.environments().isEmpty()) {
            return "Validating configuration for environments: " + settings.environments();
        }
        if (settings.deduceEnvironments()) {
            return "Validating configuration with deduced environments.";
        }
        return "Validating configuration with no explicit environments.";
    }

    private static List<String> suppressionPatterns(Set<ConfigurationError> errors) {
        return errors.stream()
            .filter(error -> error.type() == ConfigurationError.Type.ERROR)
            .map(ConfigurationError::property)
            .filter(property -> property != null && !property.isBlank())
            .map(MicronautConfigurationValidatorExecutor::toSuppressionPattern)
            .sorted(Comparator.naturalOrder())
            .toList();
    }

    private static void printSuppressionSnippet(PrintStream err, List<String> suppressionPatterns) {
        if (suppressionPatterns.isEmpty()) {
            return;
        }

        LinkedHashSet<String> deduplicatedPatterns = new LinkedHashSet<>(suppressionPatterns);
        err.println();
        err.println("Add the following to pyproject.toml to suppress these validation errors:");
        err.println();
        err.println("[tool.pyronaut.validation]");
        err.println("suppressions = [");
        for (String pattern : deduplicatedPatterns) {
            err.println("  \"" + pattern + "\",");
        }
        err.println("]");
        err.println();
    }

    private static String toSuppressionPattern(String property) {
        return property.replaceAll("\\.[0-9]+(?=\\.|$)", ".*");
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

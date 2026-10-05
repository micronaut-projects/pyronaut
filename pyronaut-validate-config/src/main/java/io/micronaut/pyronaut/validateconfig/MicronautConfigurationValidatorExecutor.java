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

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.ApplicationContextConfiguration;
import io.micronaut.context.ConfigurableApplicationContext;
import io.micronaut.context.env.Environment;
import io.micronaut.context.env.EnvironmentPropertySource;
import io.micronaut.core.beans.BeanIntrospectionProviders;
import io.micronaut.core.beans.BeanIntrospectionsProvider;
import io.micronaut.json.JsonMapper;
import io.micronaut.jsonschema.configuration.validator.ConfigurationError;
import io.micronaut.jsonschema.configuration.validator.ConfigurationJsonSchemaValidator;
import io.micronaut.jsonschema.configuration.validator.DependencyInjectionError;
import io.micronaut.jsonschema.configuration.validator.DependencyInjectionValidationStrategy;
import io.micronaut.jsonschema.configuration.validator.DefaultDependencyInjectionValidator;
import io.micronaut.jsonschema.configuration.validator.report.HtmlConfigurationErrorReporter;
import io.micronaut.jsonschema.configuration.validator.report.JsonConfigurationErrorReporter;
import io.micronaut.jsonschema.configuration.validator.report.SystemErrConfigurationErrorReporter;
import io.micronaut.pyronaut.config.classloader.ContextClassLoaderBeanIntrospectionsProvider;
import io.micronaut.pyronaut.config.model.NativeProvidedJarResolver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class MicronautConfigurationValidatorExecutor implements PyronautValidateConfigMain.ConfigurationValidatorExecutor {
    private static final String DEFAULT_ENVIRONMENT_LOGGER = "org.slf4j.simpleLogger.log.io.micronaut.context.env.DefaultEnvironment";
    private static final String MICRONAUT_INTROSPECTIONS_USE_CONTEXT_CLASSLOADER = "micronaut.introspections.use.context.classloader";
    private static final String PROPERTY_NOT_PRESENT = "Property not present in schema";
    // Validation never resolves values through Test Resources. Left active, the client, which
    // pyronaut-dev bundles beside the validator, falls back to settings files such as
    // ~/.micronaut/test-resources/test-resources.properties, which may name a server that has
    // long since exited or that belongs to another project. Both switches stop the client and the
    // pyronaut-dev bridge to it for as long as validation runs, including a client cached by an
    // earlier application in the same process (micronaut-test-resources#1236). The client's own
    // service registrations are also hidden from the validation classloader: the disabled client
    // still announces itself on stderr each time one of them asks it for a value.
    private static final List<String> TEST_RESOURCES_SWITCHES = List.of(
        "micronaut.test.resources.enabled",
        "pyronaut.dev.test.resources.bridge.enabled"
    );
    private static final String TEST_RESOURCES_PACKAGE = "io.micronaut.testresources.";
    private static final String SERVICES_PREFIX = "META-INF/services/";

    @Override
    public PyronautValidateConfigMain.ValidationExecutionResult validate(PyronautValidateConfigMain.ValidationSettings settings) throws Exception {
        List<URL> validationClasspath = validationClasspath(settings.classpath(), settings.resourcesDirs());
        ClassLoader previousClassLoader = Thread.currentThread().getContextClassLoader();
        String previousIntrospectionProperty = System.getProperty(MICRONAUT_INTROSPECTIONS_USE_CONTEXT_CLASSLOADER);
        BeanIntrospectionsProvider previousProvider = BeanIntrospectionProviders.set(new ContextClassLoaderBeanIntrospectionsProvider());
        Map<String, String> previousTestResourcesSwitches = disableTestResources();
        try (URLClassLoader validationClassLoader = new URLClassLoader(validationClasspath.toArray(URL[]::new), previousClassLoader)) {
            System.setProperty(MICRONAUT_INTROSPECTIONS_USE_CONTEXT_CLASSLOADER, "true");
            Thread.currentThread().setContextClassLoader(validationClassLoader);
            return validate(settings, validationClasspath);
        } finally {
            Thread.currentThread().setContextClassLoader(previousClassLoader);
            BeanIntrospectionProviders.set(previousProvider);
            restoreProperty(MICRONAUT_INTROSPECTIONS_USE_CONTEXT_CLASSLOADER, previousIntrospectionProperty);
            previousTestResourcesSwitches.forEach(MicronautConfigurationValidatorExecutor::restoreProperty);
        }
    }

    private static Map<String, String> disableTestResources() {
        Map<String, String> previous = new HashMap<>();
        for (String name : TEST_RESOURCES_SWITCHES) {
            previous.put(name, System.getProperty(name));
            System.setProperty(name, "false");
        }
        return previous;
    }

    private static URLClassLoader environmentClassLoader(List<URL> validationClasspath) {
        return new URLClassLoader(
            validationClasspath.toArray(URL[]::new),
            new TestResourcesServicesHidingClassLoader(MicronautConfigurationValidatorExecutor.class.getClassLoader())
        );
    }

    private static ApplicationContextBuilder contextBuilder(PyronautValidateConfigMain.ValidationSettings settings, ClassLoader classLoader) {
        return ApplicationContext.builder(settings.environments().toArray(String[]::new))
            .classLoader(classLoader)
            .deduceEnvironment(settings.deduceEnvironments());
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private PyronautValidateConfigMain.ValidationExecutionResult validate(
        PyronautValidateConfigMain.ValidationSettings settings,
        List<URL> validationClasspath
    ) throws Exception {
        Files.createDirectories(settings.outputDir());
        suppressDefaultEnvironmentLogging();

        ConfigurationJsonSchemaValidator validator = new ConfigurationJsonSchemaValidator();
        validator.setFailOnNotPresent(settings.failOnNotPresent());
        List<String> suppressions = new ArrayList<>(settings.suppressions());
        suppressions.add(MICRONAUT_INTROSPECTIONS_USE_CONTEXT_CLASSLOADER);
        suppressions.add("micronaut.introspections");
        validator.setSuppressionPatterns(suppressions);

        // Mirrors JsonSchemaConfigurationValidator and DependencyInjectionConfigurationValidator,
        // which build the same classloader and context, but under a parent that hides the Test
        // Resources client bundled beside the validator.
        Set<ConfigurationError> errors;
        try (URLClassLoader classLoader = environmentClassLoader(validationClasspath);
             Environment environment = Environment.create((ApplicationContextConfiguration) contextBuilder(settings, classLoader)).start()) {
            try {
                errors = withoutAmbiguousEnvironmentKeys(validator.validate(classLoader, environment));
            } finally {
                environment.stop();
            }
        }
        Set<DependencyInjectionError> dependencyInjectionErrors = Set.of();

        if (settings.validateDependencyInjection()) {
            DefaultDependencyInjectionValidator injectionValidator = new DefaultDependencyInjectionValidator(
                settings.suppressedInjectErrors(),
                strategy(settings.dependencyInjectionValidationStrategy())
            );
            try (URLClassLoader classLoader = environmentClassLoader(validationClasspath);
                 ApplicationContext context = contextBuilder(settings, classLoader).build()) {
                ConfigurableApplicationContext configurableContext = (ConfigurableApplicationContext) context;
                try (Environment ignored = configurableContext.getEnvironment().start()) {
                    configurableContext.configure();
                    dependencyInjectionErrors = injectionValidator.validate(configurableContext);
                }
            } catch (Exception e) {
                throw new IllegalStateException("Dependency injection validation failed", e);
            }
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

        boolean hasErrors = errors.stream().anyMatch(e -> e.type() == ConfigurationError.Type.ERROR) || !dependencyInjectionErrors.isEmpty();
        if (hasErrors) {
            System.err.println(validationMessage(settings));
            new SystemErrConfigurationErrorReporter(System.err, htmlFile, jsonFile, settings.projectBaseDir(), settings.resourcesDirs())
                .report(errors, dependencyInjectionErrors);
            printSuppressionSnippet(System.err, suppressionPatterns(errors));
        }
        return new PyronautValidateConfigMain.ValidationExecutionResult(hasErrors);
    }

    /**
     * Drops "not present in schema" errors for keys derived from environment variables.
     * Micronaut maps a variable such as {@code MICRONAUT_SERVER_PORT} to every candidate
     * name ({@code micronaut.server.port}, {@code micronaut.server-port}, ...), so all
     * but the one the application binds are reported as unknown. Value errors from
     * environment variables are kept. Remove once
     * https://github.com/micronaut-projects/micronaut-json-schema/issues/419 is fixed.
     */
    static Set<ConfigurationError> withoutAmbiguousEnvironmentKeys(Set<ConfigurationError> errors) {
        Set<ConfigurationError> retained = new LinkedHashSet<>(errors.size());
        for (ConfigurationError error : errors) {
            boolean fromEnvironment = EnvironmentPropertySource.ORIGIN.location().equals(error.originLocation());
            boolean unknownKey = error.message() != null && error.message().startsWith(PROPERTY_NOT_PRESENT);
            if (!(fromEnvironment && unknownKey)) {
                retained.add(error);
            }
        }
        return retained;
    }

    static List<URL> validationClasspath(String classpath, List<Path> resourcesDirs) throws java.net.MalformedURLException {
        LinkedHashSet<Path> entries = new LinkedHashSet<>();
        for (String entry : classpath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (!entry.isBlank()) {
                entries.add(Path.of(entry).toAbsolutePath().normalize());
            }
        }
        // Resources directories (config/ by default) are not copied into the processed
        // classes directory; the launchers add them to the application classpath instead.
        // Do the same so application.toml/yml/properties and their environment-specific
        // variants are loaded by the same property source loaders the application uses.
        for (Path dir : resourcesDirs) {
            if (Files.isDirectory(dir)) {
                entries.add(dir.toAbsolutePath().normalize());
            }
        }
        new NativeProvidedJarResolver().resolve().stream()
            .map(NativeProvidedJarResolver.JarPair::binary)
            .forEach(entries::add);
        List<URL> urls = new ArrayList<>(entries.size());
        for (Path entry : entries) {
            urls.add(entry.toUri().toURL());
        }
        return List.copyOf(urls);
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

    /**
     * Hides the service registrations of the Test Resources client, so that validation never
     * loads its property source loader or expression resolver.
     */
    static final class TestResourcesServicesHidingClassLoader extends ClassLoader {
        static {
            registerAsParallelCapable();
        }

        TestResourcesServicesHidingClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        public URL getResource(String name) {
            URL resource = super.getResource(name);
            if (resource != null && name.startsWith(SERVICES_PREFIX) && registersOnlyTestResources(resource)) {
                Enumeration<URL> resources = getResources(name);
                return resources.hasMoreElements() ? resources.nextElement() : null;
            }
            return resource;
        }

        @Override
        public Enumeration<URL> getResources(String name) {
            Enumeration<URL> resources;
            try {
                resources = super.getResources(name);
            } catch (IOException e) {
                return Collections.emptyEnumeration();
            }
            if (!name.startsWith(SERVICES_PREFIX)) {
                return resources;
            }
            List<URL> visible = new ArrayList<>();
            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                if (!registersOnlyTestResources(resource)) {
                    visible.add(resource);
                }
            }
            return Collections.enumeration(visible);
        }

        private static boolean registersOnlyTestResources(URL resource) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.openStream(), StandardCharsets.UTF_8))) {
                List<String> providers = reader.lines()
                    .map(line -> line.replaceFirst("#.*", "").trim())
                    .filter(line -> !line.isEmpty())
                    .toList();
                return !providers.isEmpty() && providers.stream().allMatch(provider -> provider.startsWith(TEST_RESOURCES_PACKAGE));
            } catch (IOException e) {
                return false;
            }
        }
    }
}

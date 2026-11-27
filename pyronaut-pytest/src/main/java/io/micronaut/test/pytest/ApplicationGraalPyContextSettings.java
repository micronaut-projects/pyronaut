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
package io.micronaut.test.pytest;

import io.micronaut.context.env.ActiveEnvironment;
import io.micronaut.context.env.Environment;
import io.micronaut.context.env.EnvironmentPropertySource;
import io.micronaut.context.env.PropertiesPropertySourceLoader;
import io.micronaut.context.env.PropertySourceLoader;
import io.micronaut.context.env.PropertySourcePropertyResolver;
import io.micronaut.context.env.SystemPropertiesPropertySource;
import io.micronaut.context.env.yaml.YamlPropertySourceLoader;
import io.micronaut.context.python.GraalPyContextCustomizer;
import io.micronaut.context.python.GraalPyContextFactory;
import io.micronaut.context.python.PythonContextRuntime;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.io.ResourceLoader;
import io.micronaut.core.io.scan.ClassPathResourceLoader;
import io.micronaut.core.io.service.SoftServiceLoader;
import io.micronaut.core.naming.conventions.StringConvention;
import io.micronaut.core.type.Argument;
import io.micronaut.core.value.PropertyResolver;
import io.micronaut.core.util.StringUtils;
import org.graalvm.polyglot.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Applies the application's {@code graalpy.context.*} settings to the reusable GraalPy context that
 * {@code pyronaut test} and the dev-mode test runner bootstrap before any application context
 * exists.
 * <p>
 * Micronaut Core binds {@code GraalPyContextConfiguration} only inside an application context, so
 * {@link GraalPyContextFactory#bootstrapReusableContext(ClassLoader, Map, String)} builds the test
 * context with default settings: native access stays off and {@code host-class-lookup} is ignored,
 * although the application honours both. This class reads the same properties (the
 * {@code application} and {@code application-test} configuration files, system properties and
 * environment variables) without an application context, so no bean or context configurer runs,
 * and applies them through the {@link GraalPyContextCustomizer} hook, which Core runs for every
 * context it builds.
 * <p>
 * Applied settings: the boolean {@code Context.Builder} flags ({@code allow-native-access},
 * {@code allow-all-access}, ...), {@code options.*}, {@code environment.*} and
 * {@code host-class-lookup}. Builder settings Core overrides after binding the configuration
 * (experimental options, process creation, value sharing, IO through the virtual file system) are
 * left as Core sets them, as in the application.
 */
@Internal
public final class ApplicationGraalPyContextSettings implements GraalPyContextCustomizer {

    static final String PREFIX = "graalpy.context";
    private static final Logger LOG = LoggerFactory.getLogger(ApplicationGraalPyContextSettings.class);
    /** The boolean builder flags the application can set, by property name (no reflection, for native launchers). */
    static final Map<String, BiConsumer<Context.Builder, Boolean>> FLAGS = Map.of(
        "allow-all-access", Context.Builder::allowAllAccess,
        "allow-native-access", Context.Builder::allowNativeAccess,
        "allow-create-thread", Context.Builder::allowCreateThread,
        "allow-host-class-loading", Context.Builder::allowHostClassLoading,
        "allow-inner-context-options", Context.Builder::allowInnerContextOptions,
        "use-system-exit", Context.Builder::useSystemExit
    );
    /** Package prefixes Core always keeps visible when host-class-lookup is set. */
    private static final List<String> FRAMEWORK_PACKAGES = List.of("java.", "jakarta.", "io.micronaut.");
    private static final Set<String> PRIMITIVE_TYPES = Set.of("boolean", "byte", "char", "short", "int", "long", "float", "double", "void");
    private static final String PRIMITIVE_DESCRIPTORS = "ZBCSIJFD";
    private static final ThreadLocal<Settings> CURRENT = new ThreadLocal<>();

    /**
     * Bootstrap the reusable GraalPy context with the application's {@code graalpy.context.*}
     * settings. Explicit {@code options} override configured options of the same name.
     *
     * @param classLoader The application class loader, which also resolves the configuration files
     * @param options Additional GraalPy context options
     * @param applicationMain The Python source resource to evaluate after the generated launcher
     * @return The initialized GraalPy context
     * @throws IOException If the context cannot load application resources
     */
    public static Context bootstrapReusableContext(ClassLoader classLoader,
                                                   Map<String, String> options,
                                                   String applicationMain) throws IOException {
        if (PythonContextRuntime.isInitialized() && PythonContextRuntime.isReuseContext()) {
            return PythonContextRuntime.getContext();
        }
        Settings settings = read(classLoader);
        Map<String, String> mergedOptions = new LinkedHashMap<>(settings.options());
        mergedOptions.putAll(options);
        CURRENT.set(settings);
        try {
            return GraalPyContextFactory.bootstrapReusableContext(classLoader, mergedOptions, applicationMain);
        } finally {
            CURRENT.remove();
        }
    }

    @Override
    public void customize(Context.Builder builder) {
        Settings settings = CURRENT.get();
        if (settings != null) {
            settings.applyTo(builder);
        }
    }

    @Override
    public int getOrder() {
        // run first, so application customizers registered through META-INF/services still win
        return Integer.MIN_VALUE;
    }

    static Settings read(ClassLoader classLoader) {
        try {
            return Settings.from(configuration(classLoader));
        } catch (RuntimeException e) {
            LOG.warn("Could not read the {}.* settings for the test GraalPy context; using the defaults: {}", PREFIX, e.getMessage());
            LOG.debug("Configuration read failure", e);
            return Settings.EMPTY;
        }
    }

    /**
     * The configuration files, system properties and environment variables an application
     * started in the {@code test} environment reads, without starting one. The Test Resources
     * client's loader is skipped: it would ask the Test Resources server to resolve properties
     * that have nothing to do with the GraalPy context.
     */
    private static PropertyResolver configuration(ClassLoader classLoader) {
        PropertySourcePropertyResolver resolver = new PropertySourcePropertyResolver();
        ResourceLoader resources = ClassPathResourceLoader.defaultLoader(classLoader);
        ActiveEnvironment test = ActiveEnvironment.of(Environment.TEST, 0);
        // the loaders Micronaut's environment uses: YAML and properties built in, TOML and others as services
        List<PropertySourceLoader> loaders = new ArrayList<>(List.of(
            new YamlPropertySourceLoader(false),
            new PropertiesPropertySourceLoader(false)
        ));
        loaders.addAll(SoftServiceLoader.load(PropertySourceLoader.class, classLoader).collectAll());
        for (PropertySourceLoader loader : loaders) {
            if (!loader.isEnabled() || loader.getClass().getName().startsWith("io.micronaut.testresources.")) {
                continue;
            }
            loader.load(Environment.DEFAULT_NAME, resources).ifPresent(resolver::addPropertySource);
            loader.loadEnv(Environment.DEFAULT_NAME, resources, test).ifPresent(resolver::addPropertySource);
        }
        resolver.addPropertySource(new SystemPropertiesPropertySource());
        resolver.addPropertySource(new EnvironmentPropertySource());
        return resolver;
    }

    /**
     * The settings read from the application configuration.
     *
     * @param flags The configured builder flags, by property name below the prefix
     * @param options The configured context options
     * @param environmentVariables The configured {@code environment.*} variables of the context
     * @param hostClassLookup The configured host class lookup package prefixes
     */
    record Settings(Map<String, Boolean> flags,
                    Map<String, String> options,
                    Map<String, String> environmentVariables,
                    List<String> hostClassLookup) {
        static final Settings EMPTY = new Settings(Map.of(), Map.of(), Map.of(), List.of());

        static Settings from(PropertyResolver environment) {
            Map<String, Boolean> flags = new LinkedHashMap<>();
            FLAGS.keySet().stream().sorted().forEach(name ->
                environment.getProperty(PREFIX + "." + name, Boolean.class).ifPresent(value -> flags.put(name, value)));
            Map<String, String> options = rawMap(environment, PREFIX + ".options");
            Map<String, String> environmentVariables = rawMap(environment, PREFIX + ".environment");
            List<String> hostClassLookup = environment
                .getProperty(PREFIX + ".host-class-lookup", Argument.listOf(String.class))
                .orElse(List.of())
                .stream()
                .filter(StringUtils::isNotEmpty)
                .toList();
            return new Settings(flags, options, environmentVariables, hostClassLookup);
        }

        private static Map<String, String> rawMap(PropertyResolver environment, String prefix) {
            Map<String, String> values = new LinkedHashMap<>();
            environment.getProperties(prefix, StringConvention.RAW).forEach((key, value) -> {
                if (value != null) {
                    values.put(key, value.toString());
                }
            });
            return values;
        }

        void applyTo(Context.Builder builder) {
            flags.forEach((name, value) -> FLAGS.get(name).accept(builder, value));
            if (!environmentVariables.isEmpty()) {
                builder.environment(environmentVariables);
            }
            if (!hostClassLookup.isEmpty()) {
                builder.allowHostClassLookup(hostClassFilter(hostClassLookup));
            }
        }
    }

    /**
     * The host class filter Core builds for {@code graalpy.context.host-class-lookup}: the JDK,
     * Jakarta and framework packages, primitive types and the configured packages or classes,
     * matching an array by its element type.
     *
     * @param hostClassLookup The configured package or class names
     * @return The filter
     */
    static Predicate<String> hostClassFilter(List<String> hostClassLookup) {
        List<String> names = hostClassLookup.stream()
            .map(name -> name.endsWith(".") ? name.substring(0, name.length() - 1) : name)
            .toList();
        Predicate<String> classFilter = className -> FRAMEWORK_PACKAGES.stream().anyMatch(className::startsWith)
            || names.stream().anyMatch(name -> className.equals(name) || className.startsWith(name + '.') || className.startsWith(name + '$'));
        return className -> isVisible(className, classFilter);
    }

    private static boolean isVisible(String className, Predicate<String> classFilter) {
        String name = className;
        while (name.endsWith("[]")) {
            name = name.substring(0, name.length() - 2);
        }
        if (name.startsWith("[")) {
            String element = name.substring(name.lastIndexOf('[') + 1);
            if (element.length() == 1) {
                return PRIMITIVE_DESCRIPTORS.indexOf(element.charAt(0)) >= 0;
            }
            if (element.length() < 3 || element.charAt(0) != 'L' || !element.endsWith(";")) {
                return false;
            }
            name = element.substring(1, element.length() - 1);
        }
        return PRIMITIVE_TYPES.contains(name) || classFilter.test(name);
    }
}

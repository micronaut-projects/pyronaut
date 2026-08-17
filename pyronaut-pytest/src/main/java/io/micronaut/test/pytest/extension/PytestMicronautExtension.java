/*
 * Copyright 2017-2025 original authors
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
package io.micronaut.test.pytest.extension;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.DefaultApplicationContextBuilder;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.python.PythonContextRuntime;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.beans.BeanIntrospectionProviders;
import io.micronaut.core.beans.BeanIntrospectionsProvider;
import io.micronaut.core.convert.TypeConverterRegistrar;
import io.micronaut.core.io.ResourceLoader;
import io.micronaut.core.io.scan.ClassClassPathResourceLoader;
import io.micronaut.core.io.scan.ClassPathResourceLoader;
import io.micronaut.core.io.scan.CombinedClassPathResourceLoader;
import io.micronaut.core.io.service.SoftServiceLoader;
import io.micronaut.data.connection.ConnectionDefinition;
import io.micronaut.data.connection.ConnectionOperations;
import io.micronaut.data.connection.ConnectionOperationsRegistry;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.pyronaut.config.classloader.ContextClassLoaderBeanDefinitionsProvider;
import io.micronaut.pyronaut.config.classloader.ContextClassLoaderBeanIntrospectionsProvider;
import io.micronaut.test.annotation.MicronautTestValue;
import io.micronaut.test.annotation.Sql;
import io.micronaut.test.annotation.TransactionMode;
import io.micronaut.test.extensions.AbstractMicronautExtension;
import io.micronaut.test.pytest.FailureDiagnostics;
import io.micronaut.test.pytest.PythonAssertionError;
import io.micronaut.test.support.sql.SqlHandler;
import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.AnnotatedElement;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import javax.sql.DataSource;

/**
 * Micronaut Test extension for Pytest.
 */
@SuppressWarnings("checkstyle:InnerTypeLast")
public final class PytestMicronautExtension extends AbstractMicronautExtension<Value> {

    public static final String ID = "_micronaut_test_extension";
    private static final Logger LOG = LoggerFactory.getLogger(PytestMicronautExtension.class);
    private final List<SqlConfig> sqlConfigs;
    @Nullable
    private BeanIntrospectionsProvider previousBeanIntrospectionsProvider;

    public PytestMicronautExtension(Map<String, Object> pytestProperties, Value node) {
        this(pytestProperties, node, List.of());
    }

    public PytestMicronautExtension(Map<String, Object> pytestProperties, Value node, @Nullable List<Map<String, Object>> sqlConfigs) {
        if (pytestProperties != null) {
            this.testProperties.putAll(pytestProperties);
        }
        this.sqlConfigs = parseSqlConfigs(sqlConfigs);
        node.putMember(ID, this);
    }

    /**
     * Bootstraps a pytest Micronaut fixture without leaking Java exceptions through GraalPy.
     *
     * @param pytestProperties The pytest properties.
     * @param node The pytest node.
     * @param environments The environments.
     * @param packages The packages.
     * @param propertySources The property sources.
     * @param sqlConfigs The SQL test-resource configurations.
     * @param rollback Whether rollback is enabled.
     * @param transactional Whether transactional tests are enabled.
     * @param rebuildContext Whether the context should be rebuilt between tests.
     * @param startApplication Whether the embedded application should start.
     * @param resolveParameters Whether test parameters should be resolved.
     * @return The bootstrap result.
     */
    public static FixtureBootstrapResult bootstrapFixture(
        @Nullable Map<String, Object> pytestProperties,
        Value node,
        @Nullable String[] environments,
        @Nullable String[] packages,
        @Nullable String[] propertySources,
        @Nullable List<Map<String, Object>> sqlConfigs,
        boolean rollback,
        boolean transactional,
        boolean rebuildContext,
        boolean startApplication,
        boolean resolveParameters
    ) {
        try {
            PytestMicronautExtension extension = new PytestMicronautExtension(pytestProperties, node, sqlConfigs);
            String error = extension.start(
                node,
                createMicronautTestValue(
                    environments,
                    packages,
                    propertySources,
                    rollback,
                    transactional,
                    rebuildContext,
                    startApplication,
                    resolveParameters
                )
            );
            return new FixtureBootstrapResult(extension.getContext(), error);
        } catch (Throwable e) {
            LOG.error("Error bootstrapping Micronaut pytest fixture:\n{}", FailureDiagnostics.render(e));
            return new FixtureBootstrapResult(null, buildFailureMessage(e));
        }
    }

    /**
     * Invokes a pytest test function through Java so Java exceptions are converted before pytest wrappers see them.
     *
     * @param invocation A zero-argument Python callable that invokes the test function with pytest fixtures.
     * @return The invocation result.
     */
    public static TestFunctionResult invokeTestFunction(Value invocation) {
        try {
            Value result = invocation.execute();
            if (result == null || result.isNull()) {
                return new TestFunctionResult(null, null);
            }
            return new TestFunctionResult(renderResult(result), null);
        } catch (Throwable e) {
            LOG.error("Error invoking pytest function:\n{}", FailureDiagnostics.render(e));
            return new TestFunctionResult(null, buildFailureMessage(e));
        }
    }

    /**
     * Builds a Micronaut test value from GraalPy-friendly inputs.
     *
     * @param environments The environments.
     * @param packages The packages.
     * @param propertySources The property sources.
     * @param rollback Whether rollback is enabled.
     * @param transactional Whether transactional tests are enabled.
     * @param rebuildContext Whether the context should be rebuilt between tests.
     * @param startApplication Whether the embedded application should start.
     * @param resolveParameters Whether test parameters should be resolved.
     * @return The Micronaut test value.
     */
    public static MicronautTestValue createMicronautTestValue(
        @Nullable String[] environments,
        @Nullable String[] packages,
        @Nullable String[] propertySources,
        boolean rollback,
        boolean transactional,
        boolean rebuildContext,
        boolean startApplication,
        boolean resolveParameters
    ) {
        @SuppressWarnings("unchecked")
        Class<? extends ApplicationContextBuilder>[] contextBuilders =
            (Class<? extends ApplicationContextBuilder>[]) new Class<?>[] { PytestApplicationContextBuilder.class };
        return new MicronautTestValue(
            void.class,
            environments == null ? new String[0] : environments,
            packages == null ? new String[0] : packages,
            propertySources == null ? new String[0] : propertySources,
            rollback,
            transactional,
            rebuildContext,
            contextBuilders,
            TransactionMode.SEPARATE_TRANSACTIONS,
            startApplication,
            resolveParameters,
            true
        );
    }

    /**
     * Starts Micronaut test support for a pytest node.
     *
     * @param node The pytest node.
     * @param testAnnotationValue The Micronaut test value.
     * @return Null when startup succeeds, otherwise a rendered failure message.
     */
    @Nullable
    public String start(Value node, MicronautTestValue testAnnotationValue) {
        try {
            beforeClass(node, PytestMicronautExtension.class, testAnnotationValue);
            runSql(Sql.Phase.BEFORE_ALL);
            return null;
        } catch (Throwable e) {
            if (!(e instanceof PythonAssertionError)) {
                LOG.error("Error PytestMicronautExtension start:\n{}", FailureDiagnostics.render(e));
            }
            return buildFailureMessage(e);
        }
    }

    @Override
    protected void resolveTestProperties(Value context, MicronautTestValue testAnnotationValue, Map<String, Object> testProperties) {
    }

    @Override
    protected void alignMocks(Value context, Object instance) {
        // TODO
    }

    @Override
    public void beforeClass(Value context, Class<?> testClass, @Nullable MicronautTestValue testAnnotationValue) {
        try {
            installNativeBeanIntrospectionsProvider();
            super.beforeClass(context, testClass, testAnnotationValue);
        } catch (RuntimeException e) {
            restoreNativeBeanIntrospectionsProvider();
            LOG.error("Error PytestMicronautExtension beforeClass:\n{}", FailureDiagnostics.render(e));
            throw new PythonAssertionError(buildFailureMessage(e), e);
        }
    }

    @Override
    protected void postProcessBuilder(ApplicationContextBuilder builder) {
        configureApplicationClassLoader(builder);
    }

    static void configureApplicationClassLoader(ApplicationContextBuilder builder) {
        ClassLoader contextClassLoader = resolveApplicationClassLoader();
        if (contextClassLoader != null) {
            builder.classLoader(contextClassLoader);
            builder.resourceResolver(CombinedClassPathResourceLoader.of(
                ClassPathResourceLoader.defaultLoader(contextClassLoader),
                new ClassClassPathResourceLoader(PytestMicronautExtension.class)
            ));
            registerProjectTypeConverterRegistrars(builder, contextClassLoader);
        }
        if (ImageInfo.inImageRuntimeCode()) {
            builder.beanDefinitionsProvider(new ContextClassLoaderBeanDefinitionsProvider());
        }
    }

    static ClassLoader resolveApplicationClassLoader() {
        ClassLoader contextClassLoader = PythonContextRuntime.getContextClassLoader();
        if (contextClassLoader != null) {
            return contextClassLoader;
        }
        return Thread.currentThread().getContextClassLoader();
    }

    static void registerProjectTypeConverterRegistrars(ApplicationContextBuilder builder, ClassLoader classLoader) {
        List<TypeConverterRegistrar> registrars = new ArrayList<>();
        SoftServiceLoader.load(TypeConverterRegistrar.class, classLoader)
            .disableFork()
            .collectAll(registrars);
        if (!registrars.isEmpty()) {
            builder.singletons(registrars.toArray());
        }
    }

    @Override
    public void afterClass(Value context) {
        try {
            runSql(Sql.Phase.AFTER_ALL);
            super.afterClass(context);
        } catch (RuntimeException e) {
            LOG.error("Error PytestMicronautExtension afterClass:\n{}", FailureDiagnostics.render(e));
            throw e;
        } finally {
            restoreNativeBeanIntrospectionsProvider();
        }
    }

    private void installNativeBeanIntrospectionsProvider() {
        if (ImageInfo.inImageRuntimeCode() && previousBeanIntrospectionsProvider == null) {
            previousBeanIntrospectionsProvider = BeanIntrospectionProviders.set(new ContextClassLoaderBeanIntrospectionsProvider());
        }
    }

    private void restoreNativeBeanIntrospectionsProvider() {
        BeanIntrospectionsProvider previous = previousBeanIntrospectionsProvider;
        if (previous != null) {
            BeanIntrospectionProviders.set(previous);
            previousBeanIntrospectionsProvider = null;
        }
    }

    @Override
    public void beforeEach(Value context, @Nullable Object testInstance, @Nullable AnnotatedElement method, List<Property> propertyAnnotations) {
        super.beforeEach(context, testInstance, method, propertyAnnotations);
        runSql(Sql.Phase.BEFORE_EACH);
    }

    @Override
    public void afterEach(Value context) throws Exception {
        runSql(Sql.Phase.AFTER_EACH);
        super.afterEach(context);
    }

    public ApplicationContext getContext() {
        return this.applicationContext;
    }

    private void runSql(Sql.Phase phase) {
        if (applicationContext == null || !applicationContext.isRunning()) {
            return;
        }
        List<SqlConfig> phaseConfigs = sqlConfigs.stream()
            .filter(config -> config.phase() == phase)
            .toList();
        if (phaseConfigs.isEmpty()) {
            return;
        }
        ResourceLoader resourceLoader = applicationContext.getBean(ResourceLoader.class);
        for (SqlConfig config : phaseConfigs) {
            Consumer<String> processor = sqlProcessor(config);
            for (String script : config.scripts()) {
                handleScript(resourceLoader, script, processor, phase);
            }
        }
    }

    private Consumer<String> sqlProcessor(SqlConfig config) {
        Object resource = applicationContext.getBean(config.resourceType(), Qualifiers.byName(config.dataSourceName()));
        @SuppressWarnings({"rawtypes", "unchecked"})
        SqlHandler<Object> handler = applicationContext.getBean(SqlHandler.class, Qualifiers.byTypeArguments(config.resourceType()));
        if (resource instanceof DataSource) {
            ConnectionOperations<?> operations = applicationContext.getBean(ConnectionOperationsRegistry.class)
                .provideSynchronous(ConnectionOperations.class, config.dataSourceName());
            return script -> operations.execute(ConnectionDefinition.REQUIRES_NEW, ignored -> {
                handler.handle(resource, script);
                return null;
            });
        }
        return script -> handler.handle(resource, script);
    }

    private static void handleScript(ResourceLoader loader, String script, Consumer<String> processor, Sql.Phase phase) {
        Optional<URL> resource = loader.getResource(script);
        if (resource.isEmpty()) {
            LOG.warn("Could not find SQL script: {}", script);
            return;
        }
        try (InputStream in = resource.get().openStream()) {
            processor.accept(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Error processing " + phase + " SQL script: " + script, e);
        }
    }

    private static List<SqlConfig> parseSqlConfigs(@Nullable List<Map<String, Object>> configs) {
        if (configs == null || configs.isEmpty()) {
            return List.of();
        }
        List<SqlConfig> parsed = new ArrayList<>(configs.size());
        for (Map<String, Object> config : configs) {
            List<String> scripts = stringList(config.get("scripts"));
            if (scripts.isEmpty()) {
                continue;
            }
            Sql.Phase phase = parsePhase(config.get("phase"));
            String dataSourceName = stringValue(config.get("dataSourceName"), "default");
            Class<?> resourceType = resolveClass(stringValue(config.get("resourceType"), DataSource.class.getName()));
            parsed.add(new SqlConfig(scripts, phase, dataSourceName, resourceType));
        }
        return List.copyOf(parsed);
    }

    private static Sql.Phase parsePhase(@Nullable Object value) {
        if (value == null) {
            return Sql.Phase.BEFORE_ALL;
        }
        return Sql.Phase.valueOf(value.toString());
    }

    private static String stringValue(@Nullable Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = value.toString();
        return text.isBlank() ? fallback : text;
    }

    private static List<String> stringList(@Nullable Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof String string) {
            return string.isBlank() ? List.of() : List.of(string);
        }
        if (value instanceof String[] strings) {
            return Arrays.stream(strings).filter(s -> !s.isBlank()).toList();
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                .map(Object::toString)
                .filter(s -> !s.isBlank())
                .toList();
        }
        return List.of(value.toString());
    }

    private static Class<?> resolveClass(String className) {
        try {
            return Class.forName(className, false, resolveApplicationClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Cannot resolve SQL resource type: " + className, e);
        }
    }

    private record SqlConfig(
        @NonNull List<String> scripts,
        @NonNull Sql.Phase phase,
        @NonNull String dataSourceName,
        @NonNull Class<?> resourceType
    ) {
    }

    /**
     * Application context builder that installs the Pyronaut application class loader before
     * Micronaut Test loads property sources and service-backed test resources.
     */
    public static final class PytestApplicationContextBuilder extends DefaultApplicationContextBuilder {
        public PytestApplicationContextBuilder() {
            configureApplicationClassLoader(this);
        }
    }

    static String buildFailureMessage(Throwable e) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> messages = new LinkedHashSet<>();
        collectFailureMessages(e, visited, messages);
        return String.join(System.lineSeparator(), messages);
    }

    private static void collectFailureMessages(Throwable throwable, Set<Throwable> visited, Set<String> messages) {
        if (throwable == null || !visited.add(throwable)) {
            return;
        }
        Throwable rendered = unwrapPolyglotHostException(throwable);
        if (rendered != throwable && !visited.add(rendered)) {
            return;
        }
        messages.add(renderThrowable(rendered));
        if (rendered instanceof ExceptionInInitializerError initializerError) {
            collectFailureMessages(initializerError.getException(), visited, messages);
        }
        for (Throwable suppressed : rendered.getSuppressed()) {
            collectFailureMessages(suppressed, visited, messages);
        }
        collectFailureMessages(rendered.getCause(), visited, messages);
    }

    private static Throwable unwrapPolyglotHostException(Throwable throwable) {
        if (throwable instanceof PolyglotException polyglotException && polyglotException.isHostException()) {
            return polyglotException.asHostException();
        }
        return throwable;
    }

    private static String renderThrowable(Throwable throwable) {
        throwable = unwrapPolyglotHostException(throwable);
        String message = throwable.getMessage();
        if (throwable instanceof PythonAssertionError) {
            return message == null || message.isBlank() ? throwable.getClass().getName() : message;
        }
        if (message == null || message.isBlank()) {
            return throwable.getClass().getName();
        }
        return throwable.getClass().getName() + ": " + message;
    }

    private static String renderResult(Value result) {
        try {
            if (result.isString()) {
                return result.asString();
            }
            return result.toString();
        } catch (Throwable e) {
            return result.getClass().getName();
        }
    }

    /**
     * Result wrapper for pytest fixture bootstrap.
     */
    public static final class FixtureBootstrapResult {
        private final ApplicationContext context;
        private final String error;

        FixtureBootstrapResult(@Nullable ApplicationContext context, @Nullable String error) {
            this.context = context;
            this.error = error;
        }

        @Nullable
        public ApplicationContext getContext() {
            return context;
        }

        @Nullable
        public String getError() {
            return error;
        }
    }

    /**
     * Result wrapper for pytest function invocation.
     */
    public static final class TestFunctionResult {
        private final String returnValue;
        private final String error;

        TestFunctionResult(@Nullable String returnValue, @Nullable String error) {
            this.returnValue = returnValue;
            this.error = error;
        }

        @Nullable
        public String getReturnValue() {
            return returnValue;
        }

        @Nullable
        public String getError() {
            return error;
        }
    }
}

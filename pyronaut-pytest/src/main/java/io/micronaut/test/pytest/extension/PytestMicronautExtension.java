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
import io.micronaut.context.python.ContextHolder;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.convert.TypeConverterRegistrar;
import io.micronaut.core.io.scan.ClassPathResourceLoader;
import io.micronaut.core.io.service.SoftServiceLoader;
import io.micronaut.test.pytest.FailureDiagnostics;
import io.micronaut.test.pytest.PythonAssertionError;
import io.micronaut.test.annotation.MicronautTestValue;
import io.micronaut.test.annotation.TransactionMode;
import io.micronaut.test.extensions.AbstractMicronautExtension;
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Micronaut Test extension for Pytest.
 */
public final class PytestMicronautExtension extends AbstractMicronautExtension<Value> {

    public static final String ID = "_micronaut_test_extension";
    private static final Logger LOG = LoggerFactory.getLogger(PytestMicronautExtension.class);

    public PytestMicronautExtension(Map<String, Object> pytestProperties, Value node) {
        if (pytestProperties != null) {
            this.testProperties.putAll(pytestProperties);
        }
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
        boolean rollback,
        boolean transactional,
        boolean rebuildContext,
        boolean startApplication,
        boolean resolveParameters
    ) {
        try {
            PytestMicronautExtension extension = new PytestMicronautExtension(pytestProperties, node);
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
            LOG.error("Error bootstrapping Micronaut pytest fixture: {}", e.getMessage(), e);
            return new FixtureBootstrapResult(null, buildFailureMessage(e));
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
            return null;
        } catch (Throwable e) {
            LOG.error("Error PytestMicronautExtension start: {}", e.getMessage(), e);
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
            super.beforeClass(context, testClass, testAnnotationValue);
        } catch (RuntimeException e) {
            LOG.error("Error PytestMicronautExtension beforeClass: {}", e.getMessage(), e);
            throw new PythonAssertionError(e.getMessage(), e);
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
            builder.resourceResolver(ClassPathResourceLoader.defaultLoader(contextClassLoader));
            registerProjectTypeConverterRegistrars(builder, contextClassLoader);
        }
    }

    static ClassLoader resolveApplicationClassLoader() {
        ClassLoader contextClassLoader = ContextHolder.getContextClassLoader();
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
            super.afterClass(context);
        } catch (RuntimeException e) {
            LOG.error("Error PytestMicronautExtension afterClass: " + e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public void beforeEach(Value context, @Nullable Object testInstance, @Nullable AnnotatedElement method, List<Property> propertyAnnotations) {
        super.beforeEach(context, testInstance, method, propertyAnnotations);
    }

    @Override
    public void afterEach(Value context) throws Exception {
        super.afterEach(context);
    }

    public ApplicationContext getContext() {
        return this.applicationContext;
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
        String message = FailureDiagnostics.render(e);
        if (message == null || message.isBlank()) {
            return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getName() : e.getMessage();
        }
        return message;
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
}

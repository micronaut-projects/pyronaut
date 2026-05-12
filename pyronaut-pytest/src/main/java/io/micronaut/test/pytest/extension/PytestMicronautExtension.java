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
import io.micronaut.context.BeanDefinitionsProvider;
import io.micronaut.context.DefaultBeanDefinitionsProvider;
import io.micronaut.context.annotation.Property;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.inject.BeanDefinitionReference;
import io.micronaut.test.pytest.PythonAssertionError;
import io.micronaut.test.annotation.MicronautTestValue;
import io.micronaut.test.annotation.TransactionMode;
import io.micronaut.test.extensions.AbstractMicronautExtension;
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Micronaut Test extension for Pytest.
 */
public final class PytestMicronautExtension extends AbstractMicronautExtension<Value> {

    public static final String ID = "_micronaut_test_extension";
    private static final String PROCESSED_CLASSES_DIR_PROPERTY = "pyronaut.test.processed.classes.dir";
    private static final String BEAN_DEFINITION_REFERENCES_PATH = "META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference";
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
            (Class<? extends ApplicationContextBuilder>[]) new Class<?>[0];
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
            LOG.error("Error PytestMicronautExtension beforeClass: {}", e.getMessage());
            throw new PythonAssertionError(e.getMessage());
        }
    }

    @Override
    protected void postProcessBuilder(ApplicationContextBuilder builder) {
        // propagate context classloader
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        if (contextClassLoader != null) {
            builder.classLoader(contextClassLoader);
        }
        String processedClassesDir = System.getProperty(PROCESSED_CLASSES_DIR_PROPERTY);
        if (processedClassesDir != null && !processedClassesDir.isBlank()) {
            builder.beanDefinitionsProvider(new ProcessedClassesBeanDefinitionsProvider(Path.of(processedClassesDir)));
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

    private static String buildFailureMessage(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getName();
        }
        Throwable cause = e.getCause();
        if (cause == null || cause == e) {
            return message;
        }
        String causeMessage = cause.getMessage();
        if (causeMessage == null || causeMessage.isBlank()) {
            causeMessage = cause.getClass().getName();
        }
        if (message.equals(causeMessage)) {
            return message;
        }
        return message + System.lineSeparator() + causeMessage;
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

    private static final class ProcessedClassesBeanDefinitionsProvider implements BeanDefinitionsProvider {
        private final BeanDefinitionsProvider delegate = new DefaultBeanDefinitionsProvider();
        private final Path classesDirectory;

        private ProcessedClassesBeanDefinitionsProvider(Path classesDirectory) {
            this.classesDirectory = classesDirectory;
        }

        @Override
        public List<BeanDefinitionReference<?>> provide(ClassLoader classLoader) {
            Map<String, BeanDefinitionReference<?>> references = new LinkedHashMap<>();
            for (BeanDefinitionReference<?> reference : loadProcessedBeanDefinitionReferences(classesDirectory, classLoader)) {
                references.put(reference.getClass().getName(), reference);
            }
            for (BeanDefinitionReference<?> reference : delegate.provide(classLoader)) {
                references.putIfAbsent(reference.getClass().getName(), reference);
            }
            return new ArrayList<>(references.values());
        }
    }

    static List<BeanDefinitionReference<?>> loadProcessedBeanDefinitionReferences(Path classesDirectory, ClassLoader classLoader) {
        Path referencesDirectory = classesDirectory.resolve(BEAN_DEFINITION_REFERENCES_PATH);
        if (!Files.isDirectory(referencesDirectory)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(referencesDirectory)) {
            return stream
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .filter(name -> !name.isBlank())
                .sorted()
                .<BeanDefinitionReference<?>>map(name -> loadBeanDefinitionReference(name, classLoader))
                .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read processed bean definition references from " + referencesDirectory, e);
        }
    }

    private static BeanDefinitionReference<?> loadBeanDefinitionReference(String className, ClassLoader classLoader) {
        try {
            Class<?> loadedClass = Class.forName(className, false, classLoader);
            if (!BeanDefinitionReference.class.isAssignableFrom(loadedClass)) {
                throw new IllegalStateException("Processed bean definition reference is not a BeanDefinitionReference: " + className);
            }
            Constructor<?> constructor = loadedClass.getDeclaredConstructor();
            if (!constructor.canAccess(null)) {
                constructor.setAccessible(true);
            }
            return (BeanDefinitionReference<?>) constructor.newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("Failed to load processed bean definition reference: " + className, e);
        }
    }
}

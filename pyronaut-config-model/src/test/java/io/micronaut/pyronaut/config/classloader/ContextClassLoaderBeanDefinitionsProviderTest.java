/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.micronaut.pyronaut.config.classloader;

import io.micronaut.context.BeanContext;
import io.micronaut.context.BeanResolutionContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.BeanDefinitionReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.annotation.Annotation;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextClassLoaderBeanDefinitionsProviderTest {
    private static final String SERVICE_PATH = "META-INF/micronaut/" + BeanDefinitionReference.class.getName();

    @TempDir
    Path tempDir;

    @Test
    void discoversReferencesFromClasspathWhenLoaderDoesNotExposeResources() throws Exception {
        Path serviceDirectory = tempDir.resolve(SERVICE_PATH);
        Files.createDirectories(serviceDirectory);
        Files.writeString(
            serviceDirectory.resolve(TestBeanDefinitionReference.class.getName()),
            "",
            StandardCharsets.UTF_8
        );
        String previousClasspath = System.getProperty("java.class.path");
        try (URLClassLoader classLoader = new URLClassLoader(new URL[] {tempDir.toUri().toURL()}, getClass().getClassLoader())) {
            System.setProperty("java.class.path", tempDir.toString());
            ClassLoader hiddenResourcesLoader = new URLClassLoader(new URL[] {tempDir.toUri().toURL()}, classLoader) {
                @Override
                public Enumeration<URL> getResources(String name) {
                    if (SERVICE_PATH.equals(name)) {
                        return Collections.emptyEnumeration();
                    }
                    try {
                        return super.getResources(name);
                    } catch (Exception e) {
                        return Collections.emptyEnumeration();
                    }
                }
            };

            List<BeanDefinitionReference<?>> references =
                new ContextClassLoaderBeanDefinitionsProvider().provide(hiddenResourcesLoader);

            assertTrue(references.stream().anyMatch(reference ->
                TestBean.class.getName().equals(reference.getBeanDefinitionName())));
        } finally {
            if (previousClasspath == null) {
                System.clearProperty("java.class.path");
            } else {
                System.setProperty("java.class.path", previousClasspath);
            }
        }
    }

    @Test
    void dropsLauncherReferencesWhoseMissingClassIsOnTheRuntimeClasspath() throws Exception {
        ClassLoader previousContextClassLoader = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader runtimeClassLoader = new URLClassLoader(new URL[0], getClass().getClassLoader())) {
            Thread.currentThread().setContextClassLoader(runtimeClassLoader);

            List<String> names = new ContextClassLoaderBeanDefinitionsProvider().provide(runtimeClassLoader).stream()
                .map(BeanDefinitionReference::getBeanDefinitionName)
                .toList();

            assertFalse(names.contains(RuntimeReplacedReference.NAME));
            assertTrue(names.contains(StillMissingReference.NAME));
        } finally {
            Thread.currentThread().setContextClassLoader(previousContextClassLoader);
        }
    }

    /**
     * Stands in for the image's default {@code WatchServiceFactory}, which requires a class that only
     * {@code micronaut-runtime-osx} on the runtime classpath provides.
     */
    public static final class RuntimeReplacedReference extends MissingClassReference {
        static final String NAME = "test.RuntimeReplaced";

        public RuntimeReplacedReference() {
            super(NAME, TestBean.class.getName());
        }
    }

    public static final class StillMissingReference extends MissingClassReference {
        static final String NAME = "test.StillMissing";

        public StillMissingReference() {
            super(NAME, "io.micronaut.pyronaut.test.DoesNotExist");
        }
    }

    abstract static class MissingClassReference extends TestBeanDefinitionReference {
        private final String name;
        private final AnnotationMetadata metadata;

        MissingClassReference(String name, String missingClass) {
            this.name = name;
            AnnotationValue<Requires> requirement = AnnotationValue.builder(Requires.class)
                .member("missingClasses", missingClass)
                .build();
            this.metadata = new AnnotationMetadata() {
                @Override
                @SuppressWarnings("unchecked")
                public <T extends Annotation> List<AnnotationValue<T>> getAnnotationValuesByType(Class<T> annotationType) {
                    return annotationType == Requires.class ? List.of((AnnotationValue<T>) requirement) : List.of();
                }
            };
        }

        @Override
        public String getBeanDefinitionName() {
            return name;
        }

        @Override
        public AnnotationMetadata getAnnotationMetadata() {
            return metadata;
        }
    }

    public static class TestBeanDefinitionReference implements BeanDefinitionReference<TestBean> {
        @Override
        public String getBeanDefinitionName() {
            return TestBean.class.getName();
        }

        @Override
        public BeanDefinition<TestBean> load() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isPresent() {
            return true;
        }

        @Override
        public boolean isEnabled(BeanContext context, BeanResolutionContext resolutionContext) {
            return true;
        }

        @Override
        public Class<TestBean> getBeanType() {
            return TestBean.class;
        }
    }

    public static final class TestBean {
    }
}

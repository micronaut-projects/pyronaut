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
package io.micronaut.pyronaut.runtime;

import io.micronaut.context.env.Environment;
import io.micronaut.context.env.PropertyExpressionResolver;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.value.PropertyResolver;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Native-image bridge for test-resources expression resolution from the application classpath.
 */
@Internal
public final class PyronautTestResourcesPropertyExpressionResolver implements PropertyExpressionResolver, AutoCloseable {
    private static final String DELEGATE_CLASS = "io.micronaut.testresources.client.TestResourcesClientPropertyExpressionResolver";
    private static final String TEST_RESOURCES_PREFIX = "auto.test.resources.";

    private final ConcurrentMap<ClassLoader, Optional<PropertyExpressionResolver>> delegates = new ConcurrentHashMap<>();

    @Override
    public <T> Optional<T> resolve(PropertyResolver propertyResolver,
                                   ConversionService conversionService,
                                   String expression,
                                   Class<T> requiredType) {
        if (!expression.startsWith(TEST_RESOURCES_PREFIX)) {
            return Optional.empty();
        }
        return resolveDelegate(propertyResolver)
            .flatMap(delegate -> delegate.resolve(propertyResolver, conversionService, expression, requiredType));
    }

    @Override
    public void close() throws Exception {
        Exception failure = null;
        for (Optional<PropertyExpressionResolver> delegate : delegates.values()) {
            if (delegate.isPresent() && delegate.get() instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
        }
        delegates.clear();
        if (failure != null) {
            throw failure;
        }
    }

    private Optional<PropertyExpressionResolver> resolveDelegate(PropertyResolver propertyResolver) {
        ClassLoader classLoader = resolveClassLoader(propertyResolver);
        return delegates.computeIfAbsent(classLoader, PyronautTestResourcesPropertyExpressionResolver::loadDelegate);
    }

    private static ClassLoader resolveClassLoader(PropertyResolver propertyResolver) {
        if (propertyResolver instanceof Environment environment) {
            return environment.getClassLoader();
        }
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        return contextClassLoader != null ? contextClassLoader : PyronautTestResourcesPropertyExpressionResolver.class.getClassLoader();
    }

    private static Optional<PropertyExpressionResolver> loadDelegate(ClassLoader classLoader) {
        try {
            Class<?> delegateClass = Class.forName(DELEGATE_CLASS, true, classLoader);
            if (!PropertyExpressionResolver.class.isAssignableFrom(delegateClass)) {
                return Optional.empty();
            }
            return Optional.of((PropertyExpressionResolver) delegateClass.getDeclaredConstructor().newInstance());
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("Failed to load test resources property expression resolver from application classpath", e);
        }
    }
}

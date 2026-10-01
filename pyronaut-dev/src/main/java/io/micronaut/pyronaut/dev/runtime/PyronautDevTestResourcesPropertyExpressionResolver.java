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
package io.micronaut.pyronaut.dev.runtime;

import io.micronaut.context.env.Environment;
import io.micronaut.context.env.PropertyExpressionResolver;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.core.naming.conventions.StringConvention;
import io.micronaut.core.value.PropertyResolver;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Native-image bridge for test-resources expression resolution from the application classpath.
 */
@Internal
public final class PyronautDevTestResourcesPropertyExpressionResolver implements PropertyExpressionResolver, AutoCloseable {
    private static final String DELEGATE_CLASS = "io.micronaut.testresources.client.TestResourcesClientPropertyExpressionResolver";
    private static final String TEST_RESOURCES_PREFIX = "auto.test.resources.";

    // Properties whose test-resources expression is being resolved on this thread.
    private static final ThreadLocal<Set<String>> IN_PROGRESS = ThreadLocal.withInitial(HashSet::new);

    private final ConcurrentMap<ClassLoader, Optional<PropertyExpressionResolver>> delegates = new ConcurrentHashMap<>();

    @Override
    public <T> Optional<T> resolve(PropertyResolver propertyResolver,
                                   ConversionService conversionService,
                                   String expression,
                                   Class<T> requiredType) {
        if (!expression.startsWith(TEST_RESOURCES_PREFIX)) {
            return Optional.empty();
        }
        if (!enabled()) {
            return Optional.empty();
        }
        Optional<PropertyExpressionResolver> delegate = resolveDelegate(propertyResolver);
        if (delegate.isEmpty()) {
            return Optional.empty();
        }
        Set<String> inProgress = IN_PROGRESS.get();
        String property = expression.substring(TEST_RESOURCES_PREFIX.length());
        boolean outermost = inProgress.add(property);
        try {
            return delegate.get().resolve(new CycleGuardPropertyResolver(propertyResolver), conversionService, expression, requiredType);
        } finally {
            if (outermost) {
                inProgress.remove(property);
            }
        }
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
        return delegates.computeIfAbsent(classLoader, PyronautDevTestResourcesPropertyExpressionResolver::loadDelegate);
    }

    private static ClassLoader resolveClassLoader(PropertyResolver propertyResolver) {
        ClassLoader fallback;
        if (propertyResolver instanceof Environment environment) {
            fallback = environment.getClassLoader();
        } else {
            ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
            fallback = contextClassLoader != null ? contextClassLoader : PyronautDevTestResourcesPropertyExpressionResolver.class.getClassLoader();
        }
        return PyronautDevTestResourcesClassLoader.resolve(fallback);
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

    private static boolean enabled() {
        return PyronautDevTestResourcesPropertySourceLoader.enabled();
    }

    /**
     * Hides properties whose expression is already being resolved on this thread.
     *
     * <p>Before resolving an expression, the client reads the properties the server says it
     * requires, and each of those can be a test-resources placeholder too. When two of them
     * require each other (Mailpit's {@code javamail.properties.mail.smtp.host} and
     * {@code ...smtp.port} do), reading them recurses until the stack overflows. Reading a property
     * that is already being resolved as absent breaks the cycle: the inner expression is resolved
     * without it and the outer one then gets its value.
     */
    private static final class CycleGuardPropertyResolver implements PropertyResolver {
        private final PropertyResolver delegate;

        private CycleGuardPropertyResolver(PropertyResolver delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean containsProperty(String name) {
            return !IN_PROGRESS.get().contains(name) && delegate.containsProperty(name);
        }

        @Override
        public boolean containsProperties(String name) {
            return delegate.containsProperties(name);
        }

        @Override
        public <T> Optional<T> getProperty(String name, ArgumentConversionContext<T> conversionContext) {
            if (IN_PROGRESS.get().contains(name)) {
                return Optional.empty();
            }
            return delegate.getProperty(name, conversionContext);
        }

        @Override
        public Collection<String> getPropertyEntries(String name) {
            return delegate.getPropertyEntries(name);
        }

        @Override
        public Map<String, Object> getProperties(@Nullable String name, @Nullable StringConvention keyFormat) {
            return delegate.getProperties(name, keyFormat);
        }

        @Override
        public Collection<List<String>> getPropertyPathMatches(String pathPattern) {
            return delegate.getPropertyPathMatches(pathPattern);
        }
    }
}

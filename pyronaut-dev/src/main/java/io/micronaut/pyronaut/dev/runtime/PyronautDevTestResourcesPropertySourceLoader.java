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

import io.micronaut.context.env.ActiveEnvironment;
import io.micronaut.context.env.Environment;
import io.micronaut.context.env.PropertySource;
import io.micronaut.context.env.PropertySourceLoader;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Native-image bridge for test-resources property source loading from the application classpath.
 */
@Internal
public final class PyronautDevTestResourcesPropertySourceLoader implements PropertySourceLoader {
    private static final String DELEGATE_CLASS = "io.micronaut.testresources.client.TestResourcesClientPropertySourceLoader";

    private final ConcurrentMap<ClassLoader, Optional<PropertySourceLoader>> delegates = new ConcurrentHashMap<>();

    @Override
    public Optional<PropertySource> load(Environment environment) {
        return resolveDelegate(environment).flatMap(delegate -> delegate.load(environment));
    }

    @Override
    public Optional<PropertySource> load(String resourceName, ResourceLoader resourceLoader) {
        return resolveDelegate(resourceLoader)
            .flatMap(delegate -> delegate.load(resourceName, resourceLoader));
    }

    @Override
    public Optional<PropertySource> loadEnv(String resourceName,
                                            ResourceLoader resourceLoader,
                                            ActiveEnvironment activeEnvironment) {
        return resolveDelegate(resourceLoader)
            .flatMap(delegate -> delegate.loadEnv(resourceName, resourceLoader, activeEnvironment));
    }

    @Override
    public Map<String, Object> read(String name, InputStream input) throws IOException {
        return Map.of();
    }

    @Override
    public Set<String> getExtensions() {
        return Set.of();
    }

    private Optional<PropertySourceLoader> resolveDelegate(ResourceLoader resourceLoader) {
        ClassLoader classLoader = resolveClassLoader(resourceLoader);
        return delegates.computeIfAbsent(classLoader, PyronautDevTestResourcesPropertySourceLoader::loadDelegate);
    }

    private static ClassLoader resolveClassLoader(ResourceLoader resourceLoader) {
        ClassLoader fallback;
        if (resourceLoader instanceof Environment environment) {
            fallback = environment.getClassLoader();
        } else {
            ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
            fallback = contextClassLoader != null ? contextClassLoader : PyronautDevTestResourcesPropertySourceLoader.class.getClassLoader();
        }
        return PyronautDevTestResourcesClassLoader.resolve(fallback);
    }

    private static Optional<PropertySourceLoader> loadDelegate(ClassLoader classLoader) {
        try {
            Class<?> delegateClass = Class.forName(DELEGATE_CLASS, true, classLoader);
            if (!PropertySourceLoader.class.isAssignableFrom(delegateClass)) {
                return Optional.empty();
            }
            return Optional.of((PropertySourceLoader) delegateClass.getDeclaredConstructor().newInstance());
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("Failed to load test resources property source loader from application classpath", e);
        }
    }
}

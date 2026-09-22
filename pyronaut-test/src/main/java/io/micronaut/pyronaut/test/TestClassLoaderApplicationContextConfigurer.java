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
package io.micronaut.pyronaut.test;

import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.ApplicationContextConfigurer;
import io.micronaut.core.io.scan.ClassPathResourceLoader;

/**
 * Builds test application contexts against the project classloader.
 *
 * <p>{@link PyronautTestMain} assembles a classloader over the processed classes and every
 * configured resource directory, and makes it the thread context classloader before launching.
 * The pytest integration picks that up by overriding Micronaut Test's {@code postProcessBuilder}
 * hook. Micronaut Test's JUnit extension has the same hook and does not override it, so a context
 * built from a JUnit test keeps the default classloader and cannot see any project resource
 * directory — not the configured {@code additional-resources}, and not {@code config} either.
 * Every {@code classpath:} lookup then fails in a JUnit test while the identical pytest resolves
 * it.</p>
 *
 * <p>Applying the classloader here covers both engines, because an
 * {@link ApplicationContextConfigurer} reaches the builder whoever created it. The pytest
 * extension still refines the resource resolver afterwards, and {@code postProcessBuilder} runs
 * later, so this changes nothing for pytest.</p>
 */
public final class TestClassLoaderApplicationContextConfigurer implements ApplicationContextConfigurer {

    @Override
    public void configure(ApplicationContextBuilder builder) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            return;
        }
        builder.classLoader(classLoader);
        builder.resourceResolver(ClassPathResourceLoader.defaultLoader(classLoader));
    }
}

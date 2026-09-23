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

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.core.io.ResourceResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestClassLoaderApplicationContextConfigurerTest {

    @Test
    void appliesTheContextClassLoaderToTheBuilder(@TempDir Path tempDir) throws IOException {
        Path resources = Files.createDirectories(tempDir.resolve("resources"));
        Files.writeString(resources.resolve("probe.txt"), "found", StandardCharsets.UTF_8);

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader projectLoader = new URLClassLoader(
            new URL[]{resources.toUri().toURL()}, previous)) {

            // Without the context classloader applied, a resource that only exists in the
            // project's resource directory is invisible to the builder's resolver.
            assertTrue(resolveProbe(ApplicationContext.builder()).isEmpty());

            Thread.currentThread().setContextClassLoader(projectLoader);
            ApplicationContextBuilder builder = ApplicationContext.builder();
            new TestClassLoaderApplicationContextConfigurer().configure(builder);

            Optional<URL> resolved = resolveProbe(builder);
            assertTrue(resolved.isPresent(), "probe.txt should resolve from the project classloader");
            assertEquals("found", readAll(resolved.get()));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void leavesTheBuilderAloneWhenThereIsNoContextClassLoader() {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(null);
            ApplicationContextBuilder builder = ApplicationContext.builder();
            new TestClassLoaderApplicationContextConfigurer().configure(builder);
            assertTrue(resolveProbe(builder).isEmpty());
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static Optional<URL> resolveProbe(ApplicationContextBuilder builder) {
        try (ApplicationContext context = builder.start()) {
            return context.getBean(ResourceResolver.class).getResource("classpath:probe.txt");
        }
    }

    private static String readAll(URL url) throws IOException {
        try (var stream = url.openStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }
}

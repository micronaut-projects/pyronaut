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
package io.micronaut.pyronaut.config.classloader;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.ApplicationContextConfigurer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContextClassLoaderApplicationContextConfigurersTest {

    @TempDir
    Path tempDir;

    @Test
    void discoversMicronautMetadataConfigurersFromRuntimeClassloader() throws Exception {
        Path serviceDir = tempDir
            .resolve("META-INF/micronaut")
            .resolve(ApplicationContextConfigurer.class.getName());
        Files.createDirectories(serviceDir);
        Files.writeString(
            serviceDir.resolve(MetadataConfigurer.class.getName()),
            "",
            StandardCharsets.UTF_8
        );

        try (URLClassLoader classLoader = new URLClassLoader(new java.net.URL[] { tempDir.toUri().toURL() }, getClass().getClassLoader())) {
            ApplicationContextBuilder builder = ApplicationContext.builder();

            ContextClassLoaderApplicationContextConfigurers.configure(builder, classLoader);

            try (ApplicationContext context = builder.start()) {
                assertEquals("metadata", context.getProperty("test.configurer.source", String.class).orElse(null));
            }
        }
    }

    @Test
    void discoversStandardServiceConfigurersFromRuntimeClassloader() throws Exception {
        Path serviceFile = tempDir
            .resolve("META-INF/services")
            .resolve(ApplicationContextConfigurer.class.getName());
        Files.createDirectories(serviceFile.getParent());
        Files.writeString(
            serviceFile,
            StandardServiceConfigurer.class.getName() + System.lineSeparator(),
            StandardCharsets.UTF_8
        );

        try (URLClassLoader classLoader = new URLClassLoader(new java.net.URL[] { tempDir.toUri().toURL() }, getClass().getClassLoader())) {
            ApplicationContextBuilder builder = ApplicationContext.builder();

            ContextClassLoaderApplicationContextConfigurers.configure(builder, classLoader);

            try (ApplicationContext context = builder.start()) {
                assertEquals("standard", context.getProperty("test.configurer.source", String.class).orElse(null));
            }
        }
    }

    public static final class MetadataConfigurer implements ApplicationContextConfigurer {
        @Override
        public void configure(ApplicationContextBuilder builder) {
            builder.properties(java.util.Map.of("test.configurer.source", "metadata"));
        }
    }

    public static final class StandardServiceConfigurer implements ApplicationContextConfigurer {
        @Override
        public void configure(ApplicationContextBuilder builder) {
            builder.properties(java.util.Map.of("test.configurer.source", "standard"));
        }
    }
}

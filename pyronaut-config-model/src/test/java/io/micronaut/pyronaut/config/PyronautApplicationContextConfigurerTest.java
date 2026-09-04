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
package io.micronaut.pyronaut.config;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.ApplicationContextConfigurer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PyronautApplicationContextConfigurerTest {

    @TempDir
    Path tempDir;

    @Test
    void serviceDiscoveryAppliesExecutorDefaults() {
        try (ApplicationContext context = ApplicationContext.builder().start()) {
            assertDefaultExecutorProperties(context);
        }
    }

    @Test
    void applicationConfigurationOverridesExecutorDefaults() throws IOException {
        Files.writeString(tempDir.resolve("application.properties"), """
            micronaut.executors.io.type=fixed
            micronaut.executors.io.virtual=true
            micronaut.executors.blocking.type=scheduled
            micronaut.executors.blocking.virtual=true
            """);

        try (ApplicationContext context = ApplicationContext.builder()
            .overrideConfigLocations(tempDir.toUri().toString())
            .start()) {
            assertEquals("fixed", context.getProperty("micronaut.executors.io.type", String.class).orElse(null));
            assertTrue(context.getProperty("micronaut.executors.io.virtual", Boolean.class).orElse(false));
            assertEquals("scheduled", context.getProperty("micronaut.executors.blocking.type", String.class).orElse(null));
            assertTrue(context.getProperty("micronaut.executors.blocking.virtual", Boolean.class).orElse(false));
        }
    }

    @Test
    void partialApplicationConfigurationPreservesUnspecifiedDefaults() throws IOException {
        Files.writeString(tempDir.resolve("application.properties"), """
            micronaut.executors.io.virtual=true
            micronaut.executors.blocking.type=fixed
            """);

        try (ApplicationContext context = ApplicationContext.builder()
            .overrideConfigLocations(tempDir.toUri().toString())
            .start()) {
            assertEquals("cached", context.getProperty("micronaut.executors.io.type", String.class).orElse(null));
            assertTrue(context.getProperty("micronaut.executors.io.virtual", Boolean.class).orElse(false));
            assertEquals("fixed", context.getProperty("micronaut.executors.blocking.type", String.class).orElse(null));
            assertFalse(context.getProperty("micronaut.executors.blocking.virtual", Boolean.class).orElse(true));
        }
    }

    @Test
    void serviceDescriptorRegistersConfigurer() {
        assertTrue(ServiceLoader.load(ApplicationContextConfigurer.class, getClass().getClassLoader())
            .stream()
            .map(ServiceLoader.Provider::type)
            .anyMatch(PyronautApplicationContextConfigurer.class::equals));
    }

    private static void assertDefaultExecutorProperties(ApplicationContext context) {
        assertEquals("cached", context.getProperty("micronaut.executors.io.type", String.class).orElse(null));
        assertFalse(context.getProperty("micronaut.executors.io.virtual", Boolean.class).orElse(true));
        assertEquals("cached", context.getProperty("micronaut.executors.blocking.type", String.class).orElse(null));
        assertFalse(context.getProperty("micronaut.executors.blocking.virtual", Boolean.class).orElse(true));
    }
}

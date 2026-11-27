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
package io.micronaut.test.pytest;

import io.micronaut.context.python.PythonContextRuntime;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.testkit.engine.EngineTestKit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reusable test context honours the application's {@code graalpy.context.*} settings (#332).
 */
class ApplicationGraalPyContextSettingsTest {

    private static final List<String> PROPERTIES = List.of(
        "graalpy.context.allow-native-access",
        "graalpy.context.host-class-lookup",
        "graalpy.context.options.python.WarnExperimentalFeatures"
    );

    @TempDir
    Path tempDir;

    @BeforeEach
    @AfterEach
    void resetRuntime() {
        PROPERTIES.forEach(System::clearProperty);
        if (PythonContextRuntime.isInitialized()) {
            Context context = PythonContextRuntime.getContext();
            PythonContextRuntime.setReuseContext(false);
            PythonContextRuntime.resetContext();
            try {
                context.close(true);
            } catch (RuntimeException ignored) {
                // already closed by its owner
            }
        } else {
            PythonContextRuntime.setReuseContext(false);
            PythonContextRuntime.resetContext();
        }
    }

    @Test
    void readsBuilderFlagsOptionsAndHostClassLookupFromTheApplicationConfiguration() {
        System.setProperty("graalpy.context.allow-native-access", "true");
        System.setProperty("graalpy.context.host-class-lookup", "com.example,org.acme.Widget");
        System.setProperty("graalpy.context.options.python.WarnExperimentalFeatures", "false");

        var settings = ApplicationGraalPyContextSettings.read(getClass().getClassLoader());

        assertEquals(Map.of("allow-native-access", true), settings.flags());
        assertEquals(List.of("com.example", "org.acme.Widget"), settings.hostClassLookup());
        assertEquals("false", settings.options().get("python.WarnExperimentalFeatures"));
    }

    @Test
    void readsTheApplicationAndTestEnvironmentConfigurationFiles() throws Exception {
        Path config = Files.createDirectories(tempDir.resolve("config"));
        Files.writeString(config.resolve("application.properties"), """
            graalpy.context.allow-native-access=true
            graalpy.context.allow-create-thread=true
            """);
        Files.writeString(config.resolve("application-test.properties"), "graalpy.context.allow-create-thread=false\n");

        try (var classLoader = new java.net.URLClassLoader(new java.net.URL[]{config.toUri().toURL()}, getClass().getClassLoader())) {
            var settings = ApplicationGraalPyContextSettings.read(classLoader);

            assertEquals(Map.of("allow-native-access", true, "allow-create-thread", false), settings.flags());
        }
    }

    @Test
    void defaultsLeaveTheContextUnchanged() {
        var settings = ApplicationGraalPyContextSettings.read(getClass().getClassLoader());

        assertTrue(settings.flags().isEmpty());
        assertTrue(settings.hostClassLookup().isEmpty());
    }

    @Test
    void hostClassFilterMatchesCore() {
        Predicate<String> filter = ApplicationGraalPyContextSettings.hostClassFilter(List.of("com.example.", "org.acme.Widget"));

        assertTrue(filter.test("java.util.ArrayList"));
        assertTrue(filter.test("io.micronaut.core.annotation.Introspected"));
        assertTrue(filter.test("com.example.sub.Thing"));
        assertTrue(filter.test("org.acme.Widget$Part"));
        assertTrue(filter.test("byte[]"));
        assertTrue(filter.test("[Lcom.example.Thing;"));
        assertFalse(filter.test("org.acme.Other"));
        assertFalse(filter.test("org.junit.platform.engine.TestEngine[]"));
    }

    @Test
    void pytestCollectionContextAppliesTheConfiguredHostClassLookup() throws Exception {
        System.setProperty("graalpy.context.host-class-lookup", "com.example");
        Path tests = Files.createDirectories(tempDir.resolve("tests"));
        Files.writeString(tests.resolve("test_context_settings.py"), """
            import java
            import pytest

            def test_framework_classes_stay_visible():
                assert java.type("java.util.ArrayList") is not None

            def test_unlisted_classes_are_rejected():
                with pytest.raises(Exception):
                    java.type("org.junit.platform.engine.TestEngine")
            """);

        var execution = EngineTestKit
            .engine(PytestTestEngine.ENGINE_ID)
            .selectors(DiscoverySelectors.selectDirectory(tests.toString()))
            .execute();
        execution.allEvents().debug();
        var results = execution.testEvents();
        assertEquals(2, results.succeeded().count());
        assertEquals(0, results.failed().count());
    }
}

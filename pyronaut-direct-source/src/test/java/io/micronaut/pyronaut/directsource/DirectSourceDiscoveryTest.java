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
package io.micronaut.pyronaut.directsource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DirectSourceDiscoveryTest {
    @Test
    void discoversRepeatableJavaDeclarations(@TempDir Path sourceDirectory) throws Exception {
        Files.writeString(sourceDirectory.resolve("App.java"), """
            import pyronaut.build.AppConfig;
            import pyronaut.build.Dependency;
            import pyronaut.build.MavenRepository;

            @Dependency(group = "example", module = "runtime", version = "1")
            @Dependency(group = "example", module = "build", version = "2", scope = Dependency.Scope.BUILD)
            @MavenRepository("https://repo1.example")
            @MavenRepository("https://repo2.example")
            @AppConfig(name = "runtime.name", value = "runtime.value")
            @AppConfig(name = "build.name", value = "build.value", scope = AppConfig.Scope.BUILD)
            class App {
            }
            """);

        DirectSourceDeclarations declarations = new DirectSourceDiscovery().discover(
            DirectSourceDiscovery.Language.JAVA,
            sourceDirectory,
            classpath()
        );

        assertEquals(List.of(
            new DirectSourceDeclarations.Dependency("example:runtime:1", false),
            new DirectSourceDeclarations.Dependency("example:build:2", true)
        ), declarations.dependencies());
        assertEquals(List.of("https://repo1.example", "https://repo2.example"), declarations.repositories());
        assertEquals("runtime.value", declarations.runtimeProperties().get("runtime.name"));
        assertEquals("build.value", declarations.buildProperties().get("build.name"));
    }

    @Test
    void discoversPythonDeclarationsAcrossMultipleSources(@TempDir Path sourceDirectory) throws Exception {
        Files.writeString(sourceDirectory.resolve("app.py"), """
            from pyronaut.build import AppConfig, Dependency, MavenRepository

            Dependency(group="example", module="runtime", version="1")
            MavenRepository("https://repo1.example")
            AppConfig(name="runtime.name", value="runtime.value")
            """);
        Files.writeString(sourceDirectory.resolve("helpers.py"), """
            from pyronaut.build import AppConfig, Dependency, MavenRepository

            Dependency(group="example", module="build", version="2", scope=Dependency.Scope.BUILD)
            MavenRepository(value="https://repo2.example")
            AppConfig(name="build.name", value="build.value", scope=AppConfig.Scope.BUILD)
            """);

        DirectSourceDeclarations declarations = new DirectSourceDiscovery().discover(
            DirectSourceDiscovery.Language.PYTHON,
            sourceDirectory,
            classpath()
        );

        assertEquals(List.of(
            new DirectSourceDeclarations.Dependency("example:runtime:1", false),
            new DirectSourceDeclarations.Dependency("example:build:2", true)
        ), declarations.dependencies());
        assertEquals(List.of("https://repo1.example", "https://repo2.example"), declarations.repositories());
        assertEquals("runtime.value", declarations.runtimeProperties().get("runtime.name"));
        assertEquals("build.value", declarations.buildProperties().get("build.name"));
    }

    private static List<Path> classpath() {
        return Arrays.stream(System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator)))
            .filter(value -> !value.isBlank())
            .map(Path::of)
            .toList();
    }
}

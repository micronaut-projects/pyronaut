/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.micronaut.pyronaut.config.model;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalProjectLayoutTest {
    @Test
    void buildDescriptorsTakePrecedenceOverPyproject() throws Exception {
        var root = Files.createTempDirectory("pyronaut-layout");
        Files.writeString(root.resolve("pyproject.toml"), "[tool.pyronaut]");
        Files.writeString(root.resolve("build.gradle.kts"), "plugins { java }");
        assertEquals(ExternalProjectLayout.ProjectKind.GRADLE, ExternalProjectLayout.detect(root));
    }

    @Test
    void mavenIsDetectedBeforeGradle() throws Exception {
        var root = Files.createTempDirectory("pyronaut-layout");
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }");
        assertEquals(ExternalProjectLayout.ProjectKind.MAVEN, ExternalProjectLayout.detect(root));
    }

    @Test
    void externalOutputUsesBuildToolDirectory() throws Exception {
        var gradle = Files.createTempDirectory("pyronaut-gradle");
        Files.writeString(gradle.resolve("build.gradle"), "plugins { id 'java' }");
        assertEquals(gradle.resolve("build/pyronaut"), ExternalProjectLayout.outputDirectory(gradle));

        var maven = Files.createTempDirectory("pyronaut-maven");
        Files.writeString(maven.resolve("pom.xml"), "<project/>");
        assertEquals(maven.resolve("target/pyronaut"), ExternalProjectLayout.outputDirectory(maven));
    }

    @Test
    void persistsResolvedSourceAndResourceLayout() throws Exception {
        var root = Files.createTempDirectory("pyronaut-layout");
        var source = Files.createDirectories(root.resolve("custom/java"));
        var resource = Files.createDirectories(root.resolve("custom/resources"));
        var layout = new ExternalProjectLayout(ExternalProjectLayout.ProjectKind.GRADLE,
            java.util.List.of(source), java.util.List.of(), java.util.List.of(resource), java.util.List.of(),
            java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of());
        layout.write(root);
        assertEquals(layout, ExternalProjectLayout.read(root));
    }

    @Test
    void persistsTestResourcesState() throws Exception {
        var root = Files.createTempDirectory("pyronaut-layout");
        var testResourcesJar = Files.createFile(root.resolve("test-resources.jar"));
        var layout = new ExternalProjectLayout(ExternalProjectLayout.ProjectKind.GRADLE,
            java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(),
            java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of(),
            true, java.util.List.of(testResourcesJar));
        layout.write(root);
        var restored = ExternalProjectLayout.read(root);
        assertTrue(restored.testResourcesEnabled());
        assertEquals(java.util.List.of(testResourcesJar), restored.testResourcesClasspath());
    }
}

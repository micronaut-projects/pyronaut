/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.micronaut.pyronaut.install;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExternalBuildResolverTest {
    @Test
    void readsConfiguredMavenResourceDirectories() throws Exception {
        var root = Files.createTempDirectory("pyronaut-maven-resources");
        var resources = Files.createDirectories(root.resolve("custom/main-resources"));
        Files.writeString(root.resolve("pom.xml"), """
            <project><build><resources><resource><directory>custom/main-resources</directory></resource></resources></build></project>
            """);
        assertEquals(java.util.List.of(resources.toAbsolutePath()), ExternalBuildResolver.mavenResources(root, "main", "src/main/resources"));
    }

    @Test
    void readsConfiguredMavenSourceDirectory() throws Exception {
        var root = Files.createTempDirectory("pyronaut-maven-sources");
        var sources = Files.createDirectories(root.resolve("custom/main-java"));
        Files.writeString(root.resolve("pom.xml"), "<project><build><sourceDirectory>custom/main-java</sourceDirectory></build></project>");
        assertEquals(java.util.List.of(sources.toAbsolutePath()), ExternalBuildResolver.mavenSources(root, "sourceDirectory", "src/main/java"));
    }

    @Test
    void resolvesProjectBasedirMavenPathsRelativeToProjectRoot() throws Exception {
        var root = Files.createTempDirectory("pyronaut-maven-basedir");
        var sources = Files.createDirectories(root.resolve("custom/main-java"));
        Files.writeString(root.resolve("pom.xml"), "<project><build><sourceDirectory>${project.basedir}/custom/main-java</sourceDirectory></build></project>");
        assertEquals(java.util.List.of(sources.toAbsolutePath()), ExternalBuildResolver.mavenSources(root, "sourceDirectory", "src/main/java"));
    }

    @Test
    void externalInstallHashChangesWhenBuildDescriptorChanges() throws Exception {
        var root = Files.createTempDirectory("pyronaut-cache");
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }");
        var repository = root.resolve("repo");
        String before = ResolutionCache.externalInstallHash(root, repository);
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'java-library' }");
        org.junit.jupiter.api.Assertions.assertNotEquals(before, ResolutionCache.externalInstallHash(root, repository));
    }

}

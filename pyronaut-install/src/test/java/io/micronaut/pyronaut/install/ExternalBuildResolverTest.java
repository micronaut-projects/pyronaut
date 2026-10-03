/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.micronaut.pyronaut.install;

import io.micronaut.pyronaut.config.model.ExternalProjectLayout.ProjectKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalBuildResolverTest {
    @Test
    void selectsPlatformBuildToolAndPrefersMatchingProjectWrapper(@TempDir Path directory) throws Exception {
        for (ProjectKind kind : List.of(ProjectKind.MAVEN, ProjectKind.GRADLE)) {
            String tool = kind == ProjectKind.MAVEN ? "mvn" : "gradle";
            String windowsSuffix = kind == ProjectKind.MAVEN ? ".cmd" : ".bat";
            for (boolean windows : List.of(false, true)) {
                Path root = Files.createDirectories(directory.resolve(kind + " with spaces " + windows));
                String suffix = windows ? windowsSuffix : "";
                assertEquals(tool + suffix, ExternalBuildResolver.buildToolCommand(root, kind, windows));
                Files.writeString(root.resolve(tool + "w" + (windows ? "" : windowsSuffix)), "wrong platform");
                assertEquals(tool + suffix, ExternalBuildResolver.buildToolCommand(root, kind, windows));
                Path wrapper = Files.writeString(root.resolve(tool + "w" + suffix), "matching wrapper");
                assertEquals(wrapper.toAbsolutePath().toString(), ExternalBuildResolver.buildToolCommand(root, kind, windows));
            }
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void startsWindowsBuildToolWrappersWithSpaces(@TempDir Path directory) throws Exception {
        Path root = Files.createDirectories(directory.resolve("project with spaces"));
        for (ProjectKind kind : List.of(ProjectKind.MAVEN, ProjectKind.GRADLE)) {
            String wrapper = kind == ProjectKind.MAVEN ? "mvnw.cmd" : "gradlew.bat";
            Files.writeString(root.resolve(wrapper), "@echo off\r\nif not \"%~1\"==\"argument with spaces\" exit /b 17\r\nexit /b 0\r\n");
            Process process = new ProcessBuilder(ExternalBuildResolver.buildToolCommand(root, kind, true), "argument with spaces")
                .directory(root.toFile()).inheritIO().start();
            assertEquals(0, process.waitFor());
        }
    }

    @Test
    void externalInstallHashChangesWhenWindowsWrapperChanges(@TempDir Path root) throws Exception {
        for (String name : List.of("mvnw.cmd", "gradlew.bat")) {
            Path wrapper = root.resolve(name);
            Files.writeString(wrapper, "first version");
            String before = ResolutionCache.externalInstallHash(root, root.resolve("repo"));
            Files.writeString(wrapper, "updated version");
            assertNotEquals(before, ResolutionCache.externalInstallHash(root, root.resolve("repo")));
        }
    }

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

    @Test
    void keepsDifferentGradleArtifactsWithTheSameVersionWhenMergingClasspaths() {
        var version = "4.2.16.Final";
        var handler = java.nio.file.Path.of(
            "/gradle/io.netty/netty-handler/" + version + "/hash/netty-handler-" + version + ".jar");
        var buffer = java.nio.file.Path.of(
            "/gradle/io.netty/netty-buffer/" + version + "/hash/netty-buffer-" + version + ".jar");

        assertEquals(java.util.List.of(handler, buffer), ExternalBuildResolver.mergeClasspath(
            java.util.List.of(handler, buffer), java.util.List.of()));
    }

    @Test
    void doesNotInjectManagedDevelopmentSupportUnlessControlPanelIsEnabled() throws Exception {
        MavenClasspathResolver resolver = new MavenClasspathResolver(
            new ProxyConfigurationLoader(), name -> null);

        assertTrue(resolver.resolveManagedDevelopmentSupport(
            Files.createTempDirectory("pyronaut-managed-development"), true, java.util.List.of()).isEmpty());
    }

    @Test
    void detectsGradleTestResourcesPlugin() throws Exception {
        var root = gradleProject("pyronaut-gradle-test-resources");
        Files.writeString(root.resolve("build.gradle.kts"), "plugins { id(\"io.micronaut.test-resources\") version \"5.0.0\" }");

        assertTrue(ExternalBuildResolver.testResourcesEnabled(root, ProjectKind.GRADLE, null));
    }

    @Test
    void doesNotEnableGradleTestResourcesWhenPluginIsAbsent() throws Exception {
        var root = gradleProject("pyronaut-gradle-no-test-resources");
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }");

        assertFalse(ExternalBuildResolver.testResourcesEnabled(root, ProjectKind.GRADLE, null));
    }

    @Test
    void doesNotEnableGradleTestResourcesForAnUnappliedPluginReference() throws Exception {
        var root = gradleProject("pyronaut-gradle-unapplied-test-resources");
        Files.writeString(root.resolve("build.gradle.kts"), "// id(\"io.micronaut.test-resources\") version \"5.0.0\"");

        assertFalse(ExternalBuildResolver.testResourcesEnabled(root, ProjectKind.GRADLE, null));
    }

    @Test
    void doesNotEnableGradleTestResourcesWhenThePluginIsAppliedFalse() throws Exception {
        var root = gradleProject("pyronaut-gradle-test-resources-apply-false");
        Files.writeString(root.resolve("build.gradle.kts"), "plugins { id(\"io.micronaut.test-resources\") version \"5.0.0\" apply false }");

        assertFalse(ExternalBuildResolver.testResourcesEnabled(root, ProjectKind.GRADLE, null));
    }

    @Test
    void detectsMavenTestResourcesWhenEnabledInTheMavenModel() throws Exception {
        var root = Files.createTempDirectory("pyronaut-maven-test-resources");
        var pom = root.resolve("pom.xml");
        Files.writeString(pom, "<project><properties><micronaut.test.resources.enabled>true</micronaut.test.resources.enabled></properties></project>");

        assertTrue(ExternalBuildResolver.testResourcesEnabled(root, ProjectKind.MAVEN, pom));
    }

    @Test
    void doesNotEnableMavenTestResourcesWhenDisabledInTheMavenModel() throws Exception {
        var root = Files.createTempDirectory("pyronaut-maven-no-test-resources");
        var pom = root.resolve("pom.xml");
        Files.writeString(pom, "<project><properties><micronaut.test.resources.enabled>false</micronaut.test.resources.enabled></properties><dependencies><dependency><groupId>io.micronaut.testresources</groupId><artifactId>micronaut-test-resources-client</artifactId></dependency></dependencies></project>");

        assertFalse(ExternalBuildResolver.testResourcesEnabled(root, ProjectKind.MAVEN, pom));
    }

    @Test
    void preparesCompleteMavenServerClasspathWithoutApplicationRuntime() throws Exception {
        var root = Files.createTempDirectory("pyronaut-maven-test-resources-classpath");
        var effectivePom = root.resolve("effective-pom.xml");
        Files.writeString(effectivePom, """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>example</groupId><artifactId>application</artifactId><version>1.0</version>
              <dependencies>
                <dependency><groupId>com.mysql</groupId><artifactId>mysql-connector-j</artifactId><version>9.0.0</version></dependency>
                <dependency><groupId>io.micronaut.data</groupId><artifactId>micronaut-data-jdbc</artifactId><version>4.0.0</version></dependency>
                <dependency><groupId>com.example</groupId><artifactId>application-only</artifactId><version>1.0</version></dependency>
                <dependency><groupId>io.micronaut.testresources</groupId><artifactId>micronaut-test-resources-server</artifactId><version>4.1.0</version></dependency>
                <dependency><groupId>io.micronaut.testresources</groupId><artifactId>micronaut-test-resources-control-panel</artifactId><version>4.1.0</version></dependency>
              </dependencies>
            </project>
            """);

        ExternalBuildResolver.prepareMavenTestResourcesPom(effectivePom);

        String generated = Files.readString(effectivePom);
        assertFalse(generated.contains("application-only"));
        assertTrue(generated.contains("micronaut-http-server"));
        assertTrue(generated.contains("micronaut-test-resources-jdbc-mysql"));
        assertTrue(generated.contains("micronaut-test-resources-server"));
        assertTrue(generated.contains("4.1.0"));
        assertTrue(generated.contains("nashorn-core"));
    }

    /**
     * Creates a Gradle project that runs this repository's Gradle wrapper, so the
     * result does not depend on whichever Gradle version is installed on the PATH.
     */
    private static Path gradleProject(String prefix) throws Exception {
        Path repositoryRoot = Path.of("").toAbsolutePath();
        while (repositoryRoot != null && !Files.isRegularFile(repositoryRoot.resolve("gradlew"))) {
            repositoryRoot = repositoryRoot.getParent();
        }
        assertTrue(repositoryRoot != null, "Could not locate the repository Gradle wrapper");
        Path root = Files.createTempDirectory(prefix);
        for (String script : List.of("gradlew", "gradlew.bat")) {
            Files.copy(repositoryRoot.resolve(script), root.resolve(script), StandardCopyOption.COPY_ATTRIBUTES);
        }
        Path wrapper = Files.createDirectories(root.resolve("gradle/wrapper"));
        for (String file : new String[] {"gradle-wrapper.jar", "gradle-wrapper.properties"}) {
            Files.copy(repositoryRoot.resolve("gradle/wrapper").resolve(file), wrapper.resolve(file));
        }
        return root;
    }
}

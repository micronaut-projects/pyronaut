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
package io.micronaut.pyronaut.install;

import io.micronaut.pyronaut.config.model.PyprojectModel;
import io.micronaut.pyronaut.config.model.PyprojectModelException;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenResolutionPolicyTest {
    @TempDir
    Path tempDir;
    private String previousPyronautHome;

    @BeforeEach
    void rememberPyronautHome() {
        previousPyronautHome = System.getProperty("pyronaut.home");
    }

    @AfterEach
    void restorePyronautHome() {
        if (previousPyronautHome == null) {
            System.clearProperty("pyronaut.home");
        } else {
            System.setProperty("pyronaut.home", previousPyronautHome);
        }
    }

    @Test
    void versionOverridesWinAfterCoreAndPlatformManagementAndCoverTransitiveVariants() throws Exception {
        Path repository = Files.createDirectories(tempDir.resolve("repository"));
        writePom(repository, "io.micronaut", "micronaut-core-bom", "1.0", """
            <dependencyManagement><dependencies><dependency>
              <groupId>org.graalvm.python</groupId><artifactId>graalpy</artifactId><version>25.4.4.1.1</version>
            </dependency></dependencies></dependencyManagement>
            """);
        writePom(repository, "io.micronaut.platform", "micronaut-platform", "1.0", """
            <dependencyManagement><dependencies><dependency>
              <groupId>org.graalvm.python</groupId><artifactId>graalpy</artifactId><version>25.5.5</version>
            </dependency></dependencies></dependencyManagement>
            """);
        writeArtifact(repository, "org.graalvm.python", "graalpy", "25.4.4.1.1", "old");
        writeArtifact(repository, "org.graalvm.python", "graalpy", "25.5.5", "new");
        writeArtifact(repository, "example", "old-child", "1.0", "old child");
        writeArtifact(repository, "example", "new-child", "2.0", "new child");
        writePom(repository, "org.graalvm.python", "graalpy", "25.4.4.1.1", """
            <dependencies><dependency><groupId>example</groupId><artifactId>old-child</artifactId>
            <version>1.0</version></dependency></dependencies>
            """);
        writePom(repository, "org.graalvm.python", "graalpy", "25.5.5", """
            <dependencies><dependency><groupId>example</groupId><artifactId>new-child</artifactId>
            <version>2.0</version></dependency></dependencies>
            """);
        writeVariant(repository, "example", "variant", "1.0", "old-variant");
        writeVariant(repository, "example", "variant", "2.0", "new-variant");
        writeArtifact(repository, "example", "root", "1.0", "root");
        writePom(repository, "example", "root", "1.0", """
            <dependencies><dependency>
              <groupId>org.graalvm.python</groupId><artifactId>graalpy</artifactId><version>25.4.4.1.1</version>
            </dependency><dependency>
              <groupId>example</groupId><artifactId>variant</artifactId><type>zip</type><classifier>tests</classifier><version>1.0</version>
            </dependency></dependencies>
            """);
        writeSettings("""
            [maven.version-overrides]
            "org.graalvm.python:graalpy" = "25.5.5"
            "org.graalvm.sdk:collections" = "25.5.5"
            "example:variant" = "2.0"
            """);
        RepositorySystem system = fileOnlyRepositorySystem();
        try {
            MavenClasspathResolver resolver = new MavenClasspathResolver(
                system,
                new ProxyConfigurationLoader(Map.of(), tempDir.resolve("missing-proxy.toml"), tempDir.resolve("missing-maven-settings.xml"))
            );
            String policy = Files.readString(tempDir.resolve("home/settings.toml"));
            writeSettings("[maven]\n");
            assertTrue(resolver.resolveScopeDetails(model(repository, "example:root:1.0", "1.0", "1.0"),
                InstallScope.RUNTIME, tempDir.resolve("local"), false).classpath().stream()
                .anyMatch(path -> path.endsWith("graalpy-25.4.4.1.1.jar")));
            writeSettings(policy);
            List<Path> classpath = resolver.resolveScopeDetails(
                model(repository, "example:root:1.0", "1.0", "1.0"),
                InstallScope.RUNTIME,
                tempDir.resolve("local"),
                false
            ).classpath();
            assertEquals(List.of(tempDir.resolve("local/example/new-child/2.0/new-child-2.0.jar").toAbsolutePath().normalize(),
                tempDir.resolve("local/example/root/1.0/root-1.0.jar").toAbsolutePath().normalize(),
                tempDir.resolve("local/example/variant/2.0/variant-2.0-tests.zip").toAbsolutePath().normalize(),
                tempDir.resolve("local/org/graalvm/python/graalpy/25.5.5/graalpy-25.5.5.jar").toAbsolutePath().normalize()), classpath);
            assertEquals(List.of("new-child-2.0.jar", "graalpy-25.5.5.jar"),
                resolver.resolveScopeDetails(model(repository, "org.graalvm.python:graalpy", "1.0", "1.0"),
                    InstallScope.RUNTIME, tempDir.resolve("local"), false).classpath().stream()
                    .map(path -> path.getFileName().toString()).toList());
            // Tool descriptors remain exact even when application versions are forced.
            assertTrue(resolver.resolveToolArtifacts(model(repository, "example:root:1.0", null, null),
                List.of(new DefaultArtifact("org.graalvm.python:graalpy:25.4.4.1.1")), tempDir.resolve("local"), false, false)
                .getFirst().endsWith("graalpy-25.4.4.1.1.jar"));
        } finally {
            system.shutdown();
        }
    }

    @Test
    void explicitRootsKeepExtensionAndClassifierWhileForcingTheModuleVersion() throws Exception {
        Path repository = Files.createDirectories(tempDir.resolve("repository"));
        writeVariant(repository, "example", "variant", "2.0", "new variant");
        writeArtifact(repository, "example", "child", "2.0", "new child");
        writePom(repository, "example", "aggregate", "2.0", """
            <packaging>pom</packaging><dependencies><dependency><groupId>example</groupId>
            <artifactId>child</artifactId><version>2.0</version></dependency></dependencies>
            """);
        writeSettings("""
            [maven.version-overrides]
            "example:variant" = "2.0"
            "example:aggregate" = "2.0"
            """);
        RepositorySystem system = fileOnlyRepositorySystem();
        try {
            MavenClasspathResolver resolver = new MavenClasspathResolver(system,
                new ProxyConfigurationLoader(Map.of(), tempDir.resolve("no-proxy"), tempDir.resolve("no-maven")));
            assertEquals(List.of("variant-2.0-tests.zip"), resolver.resolveScopeDetails(
                model(repository, "example:variant:zip:tests:1.0", null, null), InstallScope.RUNTIME,
                tempDir.resolve("local"), false).classpath().stream().map(path -> path.getFileName().toString()).toList());
            assertEquals(List.of("child-2.0.jar"), resolver.resolveScopeDetails(
                model(repository, "example:aggregate:pom:1.0", null, null), InstallScope.RUNTIME,
                tempDir.resolve("local"), false).classpath().stream().map(path -> path.getFileName().toString()).toList());
        } finally {
            system.shutdown();
        }
    }

    @Test
    void retryCountRequiresAnExactNonNegativeInteger() throws Exception {
        Path settings = tempDir.resolve("settings.toml");
        Files.writeString(settings, """
            [maven]
            retry-count = 4

            [maven.version-overrides]
            "org.graalvm.python:graalpy" = "25.5.5"
            "org.graalvm.sdk:collections" = "25.5.5"
            """, StandardCharsets.UTF_8);
        MavenRepositoryConfiguration configuration = MavenRepositoryConfiguration.load(settings);
        assertEquals(4, configuration.retryCount());
        assertEquals("25.5.5", configuration.versionOverrides().get("org.graalvm.python:graalpy"));
        assertEquals("25.5.5", configuration.versionOverrides().get("org.graalvm.sdk:collections"));

        for (String value : List.of("-1", "1.0", "\"four\"")) {
            Files.writeString(settings, "[maven]\nretry-count = " + value + "\n", StandardCharsets.UTF_8);
            assertThrows(PyprojectModelException.class, () -> MavenRepositoryConfiguration.load(settings));
        }
        for (String invalid : List.of(
            "[maven.version-overrides]\n\"org.example:bad/module\" = \"1.0\"\n",
            "[maven.version-overrides]\n\"org.example:bad\" = \" 1.0\"\n",
            "[maven.version-overrides]\n\"org.example:bad\" = \"1/0\"\n")) {
            Files.writeString(settings, invalid, StandardCharsets.UTF_8);
            assertThrows(PyprojectModelException.class, () -> MavenRepositoryConfiguration.load(settings));
        }
    }

    private void writeSettings(String contents) throws Exception {
        Files.createDirectories(tempDir.resolve("home"));
        System.setProperty("pyronaut.home", tempDir.resolve("home").toString());
        Files.writeString(tempDir.resolve("home/settings.toml"), contents, StandardCharsets.UTF_8);
    }

    private static RepositorySystem fileOnlyRepositorySystem() {
        return new RepositorySystemSupplier() {
            @Override
            protected Map<String, TransporterFactory> createTransporterFactories() {
                return Map.of(FileTransporterFactory.NAME, new FileTransporterFactory());
            }
        }.get();
    }

    private static void writeArtifact(Path repository, String group, String artifact, String version, String contents) throws Exception {
        Path directory = repository.resolve(group.replace('.', '/')).resolve(artifact).resolve(version);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(artifact + "-" + version + ".jar"), contents, StandardCharsets.UTF_8);
        writePom(repository, group, artifact, version, "");
    }

    private static void writeVariant(Path repository, String group, String artifact, String version, String contents) throws Exception {
        Path directory = repository.resolve(group.replace('.', '/')).resolve(artifact).resolve(version);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(artifact + "-" + version + "-tests.zip"), contents, StandardCharsets.UTF_8);
        writePom(repository, group, artifact, version, "");
    }

    private static void writePom(Path repository, String group, String artifact, String version, String body) throws Exception {
        Path directory = repository.resolve(group.replace('.', '/')).resolve(artifact).resolve(version);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(artifact + "-" + version + ".pom"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion><groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version>%s
            </project>
            """.formatted(group, artifact, version, body), StandardCharsets.UTF_8);
    }

    private static PyprojectModel model(Path repository, String coordinate, String coreVersion, String platformVersion) {
        return new PyprojectModel(null, null, new PyprojectModel.Pyronaut(
            coreVersion, platformVersion, List.of(repository.toUri().toString()), null,
            new PyprojectModel.Dependencies(List.of(coordinate), List.of(), List.of(), List.of()),
            null, null, null, null, null, null, null, null, null, null, false
        ));
    }
}

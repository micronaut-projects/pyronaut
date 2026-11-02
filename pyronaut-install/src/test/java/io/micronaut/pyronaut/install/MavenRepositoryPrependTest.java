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
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenRepositoryPrependTest {
    @TempDir
    Path tempDir;

    private String previousPyronautHome;

    @BeforeEach
    void useIsolatedPyronautHome() throws IOException {
        previousPyronautHome = System.getProperty("pyronaut.home");
        Files.createDirectories(tempDir.resolve("home"));
        System.setProperty("pyronaut.home", tempDir.resolve("home").toString());
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
    void prependedRepositoryWinsWhileExplicitFixtureRetainsRepoZeroProvenance() throws Exception {
        Path sourceRepository = Files.createDirectories(tempDir.resolve("source-repository"));
        Path fixtureRepository = Files.createDirectories(tempDir.resolve("fixture-repository"));
        writeArtifact(sourceRepository, "example", "shared", "1.0", "source");
        writeArtifact(fixtureRepository, "example", "shared", "1.0", "fixture");
        writeArtifact(fixtureRepository, "example", "fixture-only", "1.0", "fixture-only");
        writeSettings("""
            [maven]
            prepend-repositories = ["%s"]
            """.formatted(sourceRepository.toUri()));

        RepositorySystem system = fileOnlyRepositorySystem();
        try {
            Path local = tempDir.resolve("local");
            MavenClasspathResolver resolver = resolver(system);
            PyprojectModel project = model(
                List.of(fixtureRepository.toUri().toString(), "mavenCentral"),
                List.of("example:shared:1.0", "example:fixture-only:1.0")
            );
            resolver.resolveScopeDetails(
                project,
                InstallScope.RUNTIME,
                local,
                false
            );

            assertEquals("source", Files.readString(local.resolve("example/shared/1.0/shared-1.0.jar")));
            String sourceMarker = Files.readString(local.resolve("example/shared/1.0/_remote.repositories"));
            assertTrue(sourceMarker.contains("shared-1.0.jar>prepend-0"), sourceMarker);
            Path fixtureMarker = local.resolve("example/fixture-only/1.0/_remote.repositories");
            assertTrue(Files.readString(fixtureMarker).contains("fixture-only-1.0.jar>repo-0"));
            // Gradle's fixture setup writes the legacy ID-only tracking format.
            Files.writeString(fixtureMarker, "fixture-only-1.0.jar>repo-0=\nfixture-only-1.0.pom>repo-0=\n");
            assertEquals(2, resolver.resolveScopeDetails(project, InstallScope.RUNTIME, local, true).classpath().size());
        } finally {
            system.shutdown();
        }
    }

    @Test
    void prependedRepositoryIsRetainedWhenGlobalRepositoriesProvideTheDefaults() throws Exception {
        Path sourceRepository = Files.createDirectories(tempDir.resolve("source-repository"));
        Path defaultRepository = Files.createDirectories(tempDir.resolve("default-repository"));
        writeArtifact(sourceRepository, "example", "source-only", "1.0", "source");
        writeArtifact(defaultRepository, "example", "default-only", "1.0", "default");
        writeSettings("""
            [maven]
            prepend-repositories = ["%s"]
            repositories = ["%s"]
            """.formatted(sourceRepository.toUri(), defaultRepository.toUri()));

        RepositorySystem system = fileOnlyRepositorySystem();
        try {
            Path local = tempDir.resolve("local");
            MavenClasspathResolver resolver = resolver(system);
            resolver.resolveScopeDetails(
                model(List.of(), List.of("example:source-only:1.0", "example:default-only:1.0")),
                InstallScope.RUNTIME,
                local,
                false
            );

            assertEquals("source", Files.readString(local.resolve("example/source-only/1.0/source-only-1.0.jar")));
            assertEquals("default", Files.readString(local.resolve("example/default-only/1.0/default-only-1.0.jar")));
        } finally {
            system.shutdown();
        }
    }

    @Test
    void resolvesPrependPathsRelativeToSettingsAndValidatesItsShape() throws Exception {
        Path settingsDirectory = tempDir.resolve("home");
        Path repository = Files.createDirectories(settingsDirectory.resolve("repository"));
        Path settings = settingsDirectory.resolve("settings.toml");
        Files.writeString(settings, "[maven]\nprepend-repositories = [\"repository\"]\n", StandardCharsets.UTF_8);

        MavenRepositoryConfiguration configuration = MavenRepositoryConfiguration.load(settings);
        assertEquals(List.of(repository.toRealPath().toString()), configuration.prependRepositories());

        Files.writeString(settings, "[maven]\nprepend-repositories = []\n", StandardCharsets.UTF_8);
        assertEquals(List.of(), MavenRepositoryConfiguration.load(settings).prependRepositories());

        Files.writeString(settings, "[maven]\nprepend-repositories = \"repository\"\n", StandardCharsets.UTF_8);
        PyprojectModelException nonArray = assertThrows(
            PyprojectModelException.class,
            () -> MavenRepositoryConfiguration.load(settings)
        );
        assertTrue(nonArray.getMessage().contains("prepend-repositories"), nonArray.getMessage());

        Files.writeString(settings, "[maven]\nprepend-repositories = [\"\"]\n", StandardCharsets.UTF_8);
        PyprojectModelException blankEntry = assertThrows(
            PyprojectModelException.class,
            () -> MavenRepositoryConfiguration.load(settings)
        );
        assertTrue(blankEntry.getMessage().contains("prepend-repositories"), blankEntry.getMessage());
    }

    private Path settingsPath() {
        return tempDir.resolve("home/settings.toml");
    }

    private void writeSettings(String contents) throws IOException {
        Files.writeString(settingsPath(), contents, StandardCharsets.UTF_8);
    }

    private MavenClasspathResolver resolver(RepositorySystem system) {
        return new MavenClasspathResolver(
            system,
            new ProxyConfigurationLoader(
                Map.of(),
                tempDir.resolve("missing-proxy.toml"),
                tempDir.resolve("missing-maven-settings.xml")
            )
        );
    }

    private static PyprojectModel model(List<String> repositories, List<String> runtime) {
        return new PyprojectModel(null, null, new PyprojectModel.Pyronaut(
            null,
            null,
            repositories,
            null,
            new PyprojectModel.Dependencies(runtime, List.of(), List.of(), List.of()),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false
        ));
    }

    private static RepositorySystem fileOnlyRepositorySystem() {
        return new RepositorySystemSupplier() {
            @Override
            protected Map<String, TransporterFactory> createTransporterFactories() {
                return Map.of(FileTransporterFactory.NAME, new FileTransporterFactory());
            }
        }.get();
    }

    private static void writeArtifact(Path repository,
                                      String group,
                                      String artifact,
                                      String version,
                                      String contents) throws IOException {
        Path directory = repository.resolve(group.replace('.', '/')).resolve(artifact).resolve(version);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(artifact + "-" + version + ".jar"), contents, StandardCharsets.UTF_8);
        Files.writeString(
            directory.resolve(artifact + "-" + version + ".pom"),
            emptyPom(group, artifact, version),
            StandardCharsets.UTF_8
        );
    }

    private static String emptyPom(String group, String artifact, String version) {
        return """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>%s</groupId>
              <artifactId>%s</artifactId>
              <version>%s</version>
            </project>
            """.formatted(group, artifact, version);
    }
}

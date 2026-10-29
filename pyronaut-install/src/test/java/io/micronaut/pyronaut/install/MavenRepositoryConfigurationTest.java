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
import org.eclipse.aether.artifact.DefaultArtifact;
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
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenRepositoryConfigurationTest {
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
    void loadsRelativeRepositoriesAndRejectsInvalidSettings() throws Exception {
        Path settings = settingsPath();
        String validSettings = """
            [maven]
            repositories = ["repository"]

            [[maven.mirrors]]
            id = "internal"
            url = "https://repo.example.test/maven/"
            mirror-of = "external:*"
            """;
        Files.writeString(settings, validSettings, StandardCharsets.UTF_8);

        MavenRepositoryConfiguration configuration = MavenRepositoryConfiguration.load(settings);

        assertEquals(List.of(settings.getParent().resolve("repository").toAbsolutePath().normalize().toString()),
            configuration.repositories());
        assertEquals("internal", configuration.mirrors().getFirst().id());
        assertEquals("external:*", configuration.mirrors().getFirst().mirrorOf());
        Path secondSettings = tempDir.resolve("other/settings.toml");
        Files.createDirectories(secondSettings.getParent());
        Files.writeString(secondSettings, validSettings, StandardCharsets.UTF_8);
        assertNotEquals(configuration.fingerprint(), MavenRepositoryConfiguration.load(secondSettings).fingerprint());

        Files.writeString(settings, "[maven]\nrepositories = []\n", StandardCharsets.UTF_8);
        PyprojectModelException failure = assertThrows(
            PyprojectModelException.class,
            () -> MavenRepositoryConfiguration.load(settings)
        );
        assertTrue(failure.getMessage().contains("repositories"), failure.getMessage());

        for (String[] invalid : new String[][]{
            {"maven = \"not-a-table\"\n", "[maven]"},
            {"[maven]\nrepositories = \"not-an-array\"\n", "repositories"},
            {"[maven]\nrepositories = [\"\"]\n", "repositories"},
            {"[maven]\nmirrors = \"not-an-array\"\n", "mirrors"},
            {"[maven]\n[[maven.mirrors]]\nid = \"internal\"\n", "mirror"},
            {"[maven]\n[[maven.mirrors]]\nid = \"internal\"\nurl = \"relative\"\nmirror-of = \"*\"\n", "URL"},
            {"""
                [maven]
                [[maven.mirrors]]
                id = "internal"
                url = "https://one.example"
                mirror-of = "*"
                [[maven.mirrors]]
                id = "internal"
                url = "https://two.example"
                mirror-of = "*"
                """, "Duplicate"},
            {"[maven]\n[[maven.mirrors]]\nid = \"pyronaut-lock\"\nurl = \"https://repo.example\"\nmirror-of = \"*\"\n", "reserved"}
        }) {
            Files.writeString(settings, invalid[0], StandardCharsets.UTF_8);
            PyprojectModelException invalidFailure = assertThrows(
                PyprojectModelException.class,
                () -> MavenRepositoryConfiguration.load(settings)
            );
            assertTrue(invalidFailure.getMessage().toLowerCase().contains(invalid[1].toLowerCase()),
                invalidFailure.getMessage());
        }
    }

    @Test
    void mirrorsRootAndPomRepositoriesWithoutFallingBackToHttp() throws Exception {
        Path rootRepository = Files.createDirectories(tempDir.resolve("root-repository"));
        Path transitiveRepository = Files.createDirectories(tempDir.resolve("transitive-repository"));
        writeArtifact(rootRepository, "example", "root", "1.0", """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>example</groupId>
              <artifactId>root</artifactId>
              <version>1.0</version>
              <repositories>
                <repository>
                  <id>external-repository</id>
                  <url>https://external.invalid/maven/</url>
                </repository>
              </repositories>
              <dependencies>
                <dependency>
                  <groupId>example</groupId>
                  <artifactId>transitive</artifactId>
                  <version>1.0</version>
                </dependency>
              </dependencies>
            </project>
            """);
        writeArtifact(transitiveRepository, "example", "transitive", "1.0", emptyPom("example", "transitive", "1.0"));
        writeSettings("""
            [maven]
            [[maven.mirrors]]
            id = "internal-root"
            url = "%s"
            mirror-of = "mavenCentral"

            [[maven.mirrors]]
            id = "internal-transitive"
            url = "%s"
            mirror-of = "external-repository"
            """.formatted(rootRepository.toUri(), transitiveRepository.toUri()));

        RepositorySystem system = fileOnlyRepositorySystem();
        try {
            Path local = tempDir.resolve("local");
            MavenClasspathResolver resolver = resolver(system);
            MavenClasspathResolver.ResolvedScopeDetails result = resolver.resolveScopeDetails(
                model(List.of("mavenCentral"), List.of("example:root:1.0")),
                InstallScope.RUNTIME,
                local,
                false
            );

            assertEquals(Set.of(
                local.resolve("example/root/1.0/root-1.0.jar").toAbsolutePath().normalize(),
                local.resolve("example/transitive/1.0/transitive-1.0.jar").toAbsolutePath().normalize()
            ), Set.copyOf(result.classpath()));
        } finally {
            system.shutdown();
        }
    }

    @Test
    void preservesAnExplicitFileRepositoryAgainstGlobalDefaultsAndMirrors() throws Exception {
        Path projectRepository = Files.createDirectories(tempDir.resolve("project-repository"));
        writeArtifact(projectRepository, "example", "local", "1.0", emptyPom("example", "local", "1.0"));
        Path unusedMirror = Files.createDirectories(tempDir.resolve("unused-mirror"));
        writeSettings("""
            [maven]
            repositories = ["mavenCentral"]

            [[maven.mirrors]]
            id = "internal"
            url = "%s"
            mirror-of = "external:*"
            """.formatted(unusedMirror.toUri()));

        RepositorySystem system = fileOnlyRepositorySystem();
        try {
            Path local = tempDir.resolve("local");
            MavenClasspathResolver.ResolvedScopeDetails result = resolver(system).resolveScopeDetails(
                model(List.of(projectRepository.toUri().toString()), List.of("example:local:1.0")),
                InstallScope.RUNTIME,
                local,
                false
            );

            assertEquals(List.of(local.resolve("example/local/1.0/local-1.0.jar").toAbsolutePath().normalize()),
                result.classpath());
        } finally {
            system.shutdown();
        }
    }

    @Test
    void usesTheSameGlobalDefaultsForScopesSdkToolsAndNativeClasspaths() throws Exception {
        Path repository = Files.createDirectories(tempDir.resolve("repository"));
        writeArtifact(repository, "example", "scope", "1.0", emptyPom("example", "scope", "1.0"));
        writeArtifact(repository, "example", "tool", "1.0", emptyPom("example", "tool", "1.0"));
        writeArtifact(repository, "example", "native", "1.0", emptyPom("example", "native", "1.0"));
        writeSettings("[maven]\nrepositories = [\"%s\"]\n".formatted(repository.toUri()));

        RepositorySystem system = fileOnlyRepositorySystem();
        try {
            MavenClasspathResolver resolver = resolver(system);
            MavenClasspathResolver.ResolvedScopeDetails scope = resolver.resolveScopeDetails(
                model(List.of(), List.of("example:scope:1.0")),
                InstallScope.RUNTIME,
                tempDir.resolve("scope-local"),
                false
            );
            assertTrue(scope.classpath().getFirst().toString().endsWith("example/scope/1.0/scope-1.0.jar"));

            Path sdkLocal = tempDir.resolve("sdk-local");
            List<Path> sdk = resolver.resolveToolArtifacts(
                ToolClasspathInstaller.defaultModel(),
                List.of(new DefaultArtifact("example", "tool", "jar", "1.0")),
                sdkLocal,
                false,
                false
            );
            assertEquals(List.of(sdkLocal.resolve("example/tool/1.0/tool-1.0.jar").toAbsolutePath().normalize()), sdk);

            Path descriptors = Files.createDirectories(tempDir.resolve("descriptors"));
            for (String image : NativeClasspathInstaller.IMAGE_NAMES) {
                Files.writeString(descriptors.resolve(image + ".tsv"),
                    "maven\texample\tnative\t1.0\tjar\t\tnative-1.0.jar\n", StandardCharsets.UTF_8);
            }
            Path nativeLocal = tempDir.resolve("native-local");
            new NativeClasspathInstaller(resolver).install(
                ToolClasspathInstaller.defaultModel(), descriptors, nativeLocal, false, false, null
            );
            Path nativeArtifact = nativeLocal.resolve("example/native/1.0/native-1.0.jar").toAbsolutePath().normalize();
            for (String image : NativeClasspathInstaller.IMAGE_NAMES) {
                assertEquals(List.of(nativeArtifact.toString()),
                    Files.readAllLines(descriptors.resolve("resolved").resolve(image + ".txt")));
            }
        } finally {
            system.shutdown();
        }
    }

    @Test
    void retainsMirroredRepositoryProvenanceForOfflineResolution() throws Exception {
        Path mirror = Files.createDirectories(tempDir.resolve("mirror"));
        writeArtifact(mirror, "example", "offline", "1.0", emptyPom("example", "offline", "1.0"));
        writeSettings("""
            [maven]
            [[maven.mirrors]]
            id = "internal"
            url = "%s"
            mirror-of = "*"
            """.formatted(mirror.toUri()));

        RepositorySystem system = fileOnlyRepositorySystem();
        try {
            Path local = tempDir.resolve("local");
            MavenClasspathResolver resolver = resolver(system);
            PyprojectModel project = model(List.of("mavenCentral"), List.of("example:offline:1.0"));
            resolver.resolveScopeDetails(project, InstallScope.RUNTIME, local, false);
            Path marker = local.resolve("example/offline/1.0/_remote.repositories");
            assertTrue(Files.readAllLines(marker).stream().anyMatch(line -> line.contains("internal")),
                Files.readString(marker));
            assertEquals(
                List.of(local.resolve("example/offline/1.0/offline-1.0.jar").toAbsolutePath().normalize()),
                resolver.resolveScopeDetails(project, InstallScope.RUNTIME, local, true).classpath()
            );
        } finally {
            system.shutdown();
        }
    }

    @Test
    void settingsChangesInvalidateAllResolverCachesAndToolMetadata() throws Exception {
        Path settings = settingsPath();
        Files.writeString(settings, "[maven]\nrepositories = [\"mavenCentral\"]\n", StandardCharsets.UTF_8);
        Path pyproject = tempDir.resolve("pyproject.toml");
        Files.writeString(pyproject, "[tool.pyronaut]\n", StandardCharsets.UTF_8);
        Path external = Files.createDirectories(tempDir.resolve("external"));
        Files.writeString(external.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        Path local = tempDir.resolve("local");

        String directBefore = DirectSourceDependencyResolver.fingerprint(List.of(), List.of(), List.of());
        String installBefore = ResolutionCache.installHash(pyproject, local);
        String externalBefore = ResolutionCache.externalInstallHash(external, local);
        String settingsBefore = MavenRepositoryConfiguration.load().fingerprint();

        Path repository = Files.createDirectories(tempDir.resolve("repository"));
        writeArtifact(repository, "example", "tool", "1.0", emptyPom("example", "tool", "1.0"));
        writeSettings("[maven]\nrepositories = [\"%s\"]\n".formatted(repository.toUri()));

        assertNotEquals(settingsBefore, MavenRepositoryConfiguration.load().fingerprint());
        assertNotEquals(directBefore, DirectSourceDependencyResolver.fingerprint(List.of(), List.of(), List.of()));
        assertNotEquals(installBefore, ResolutionCache.installHash(pyproject, local));
        assertNotEquals(externalBefore, ResolutionCache.externalInstallHash(external, local));

        Path packaged = Files.createDirectories(tempDir.resolve("packaged/pyronaut-dev/bin"));
        Files.writeString(packaged.resolve("pyronaut-dev"), "launcher\n", StandardCharsets.UTF_8);
        Files.writeString(packaged.getParent().getParent().resolve(ToolClasspathInstaller.RUNTIME_PROPERTIES_FILE),
            "sdk.version=1.0\n", StandardCharsets.UTF_8);
        Files.writeString(packaged.resolve(ToolClasspathInstaller.DESCRIPTOR_FILE),
            "maven\texample\ttool\t1.0\tjar\t\ttool-1.0.jar\n", StandardCharsets.UTF_8);
        RepositorySystem toolSystem = fileOnlyRepositorySystem();
        try {
            ToolClasspathInstaller installer = new ToolClasspathInstaller(
                resolver(toolSystem), packaged.getParent().getParent(), tempDir.resolve("tool-cache")
            );
            Path first = installer.install(null, tempDir.resolve("tool-local"), false, false);
            String firstFingerprint = readToolSettingsFingerprint(first);

            writeSettings("[maven]\nrepositories = [\"%s\"]\n# changed\n".formatted(repository.toUri()));
            Path second = installer.install(null, tempDir.resolve("tool-local"), false, false);
            assertNotEquals(firstFingerprint, readToolSettingsFingerprint(second));
        } finally {
            toolSystem.shutdown();
        }
    }

    private Path settingsPath() throws IOException {
        Path settings = tempDir.resolve("home/settings.toml");
        Files.createDirectories(settings.getParent());
        return settings;
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
                                      String pom) throws IOException {
        Path directory = repository.resolve(group.replace('.', '/')).resolve(artifact).resolve(version);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(artifact + "-" + version + ".jar"), artifact, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(artifact + "-" + version + ".pom"), pom, StandardCharsets.UTF_8);
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

    private static String readToolSettingsFingerprint(Path current) throws IOException {
        Properties properties = new Properties();
        try (var input = Files.newInputStream(current.resolve(ToolClasspathInstaller.RUNTIME_PROPERTIES_FILE))) {
            properties.load(input);
        }
        return properties.getProperty("maven.settings.sha256", "");
    }
}

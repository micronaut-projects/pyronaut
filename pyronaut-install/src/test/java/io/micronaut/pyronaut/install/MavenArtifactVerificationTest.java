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
import io.micronaut.pyronaut.config.model.PyronautManagedVersions;
import io.micronaut.pyronaut.config.model.PyprojectModelReader;
import io.micronaut.json.JsonMapper;
import io.micronaut.json.tree.JsonNode;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MavenArtifactVerificationTest {
    private static final String MODULE = "org.graalvm.example:language";
    @TempDir
    Path tempDir;
    private String previousHome;
    private Path settings;
    private Path bundleLock;
    private Path local;

    @BeforeEach
    void isolate() throws IOException {
        previousHome = System.getProperty("pyronaut.home");
        Path home = Files.createDirectories(tempDir.resolve("home"));
        System.setProperty("pyronaut.home", home.toString());
        settings = home.resolve("settings.toml");
        bundleLock = home.resolve("bundle.lock");
        local = tempDir.resolve("local");
    }

    @AfterEach
    void restore() {
        if (previousHome == null) {
            System.clearProperty("pyronaut.home");
        } else {
            System.setProperty("pyronaut.home", previousHome);
        }
    }

    @Test
    void acceptsLongGroupSelectorsWithoutRecursiveRegexMatching() throws Exception {
        configure(List.of());
        String group = "segment.".repeat(10_000) + "leaf";
        Files.writeString(settings, Files.readString(settings).replace("org.graalvm.*", group + ".*"));
        MavenArtifactVerification verification = MavenRepositoryConfiguration.load().verification();
        assertTrue(verification.active());
    }

    @Test
    void acceptsMatchingInstalledArtifactsAndRejectsChangedBytesOnEveryInvocation() throws Exception {
        Artifact jar = artifact("jar");
        configure(List.of(entry(jar)));
        MavenClasspathResolver resolver = resolver();
        assertEquals(List.of(jar.getPath()), resolver.resolveToolArtifacts(model(), List.of(new DefaultArtifact(jar.toString())), local, true, false));
        assertEquals(List.of(jar.getPath()), resolver.resolveToolArtifacts(model(), List.of(new DefaultArtifact(jar.toString())), local, true, false));

        Files.writeString(jar.getPath(), "different release with the same coordinate");
        PyprojectModelException failure = assertThrows(PyprojectModelException.class,
            () -> resolver.resolveToolArtifacts(model(), List.of(new DefaultArtifact(jar.toString())), local, true, false));
        assertTrue(failure.getMessage().contains("expected SHA-256"), failure.getMessage());
        assertTrue(Files.isRegularFile(jar.getPath()), "Verification must not delete the cached artifact");
    }

    @Test
    void verifiesCachedPomsAsWellAsJarsAndRejectsMissingBundleEntries() throws Exception {
        Artifact jar = artifact("jar");
        Artifact pom = artifact("pom");
        configure(List.of(entry(jar), entry(pom)));
        MavenClasspathResolver resolver = resolver();
        resolver.resolveScopeDetails(model(), InstallScope.RUNTIME, local, true);
        Files.writeString(pom.getPath(), Files.readString(pom.getPath()).replace("</project>", "<name>other build</name></project>"));
        PyprojectModelException failure = assertThrows(PyprojectModelException.class,
            () -> resolver.resolveScopeDetails(model(), InstallScope.RUNTIME, local, true));
        assertTrue(failure.getMessage().contains(":pom:1.0"), failure.getMessage());

        configure(List.of(entry(pom)));
        failure = assertThrows(PyprojectModelException.class,
            () -> resolver.resolveToolArtifacts(model(), List.of(new DefaultArtifact(jar.toString())), local, true, false));
        assertTrue(failure.getMessage().contains("missing from the trusted bundle lock"), failure.getMessage());
    }

    @Test
    void ignoresBinaryEventsUntilTheFinalClasspathIsVerified() throws Exception {
        Artifact jar = artifact("jar");
        configure(List.of());
        MavenArtifactVerification verification = MavenRepositoryConfiguration.load().verification();
        verification.verifyPom(new DefaultArtifact("other:library:1.0").setPath(jar.getPath()));
        verification.verifyPom(new DefaultArtifact("org.graalvm.example:language:jar:sources:1.0").setPath(jar.getPath()));
        verification.verifyPom(jar);
        verification.check();
        verification.verifyArtifact(jar);
        assertThrows(PyprojectModelException.class, verification::check);
    }

    @Test
    void refreshVerifiesTheReplacementJarAndStillRejectsAnUntrustedDownload() throws Exception {
        Artifact jar = artifact("jar");
        Artifact pom = artifact("pom");
        Path remote = tempDir.resolve("remote");
        Path remoteJar = remote.resolve(DependencyLock.layoutPath(jar));
        Path remotePom = remote.resolve(DependencyLock.layoutPath(pom));
        Files.createDirectories(remoteJar.getParent());
        Files.copy(jar.getPath(), remoteJar);
        Files.copy(pom.getPath(), remotePom);
        Files.delete(jar.getPath());
        Files.delete(pom.getPath());
        String repositories = "[maven]\nrepositories = [\"" + remote.toUri() + "\"]\n";
        Files.writeString(settings, repositories);
        MavenClasspathResolver resolver = resolver();
        resolver.resolveScopeDetails(model(), InstallScope.RUNTIME, local, false);

        Files.writeString(remoteJar, "new trusted source-built bundle");
        configure(List.of(entry(jar.setPath(remoteJar)), entry(pom.setPath(remotePom))));
        Files.writeString(settings, repositories + Files.readString(settings));
        resolver.resolveScopeDetails(model(), InstallScope.RUNTIME, local, false, true);
        assertEquals("new trusted source-built bundle", Files.readString(jar.getPath()));

        Files.writeString(remoteJar, "untrusted remote release");
        assertThrows(PyprojectModelException.class,
            () -> resolver.resolveScopeDetails(model(), InstallScope.RUNTIME, local, false, true));
    }

    @Test
    void verifiesNormalizedSnapshotFilesAgainstAnUnambiguousTrustedTimestamp() throws Exception {
        Artifact normalized = artifact("jar").setVersion("1.0-SNAPSHOT");
        Artifact timestamped = normalized.setVersion("1.0-20261030.120000-1");
        configure(List.of(entry(timestamped)));
        MavenArtifactVerification verification = MavenRepositoryConfiguration.load().verification();
        verification.verifyArtifact(normalized);
        verification.verifyArtifact(timestamped);
        verification.check();

        configure(List.of(entry(timestamped), entry(timestamped.setVersion("1.0-20261030.130000-2"))));
        verification = MavenRepositoryConfiguration.load().verification();
        verification.verifyArtifact(normalized);
        assertThrows(PyprojectModelException.class, verification::check);
    }

    @Test
    void anExplicitSourcesClassifierCannotBypassVerification() throws Exception {
        Artifact source = new DefaultArtifact(MODULE + ":jar:sources:1.0");
        Path path = local.resolve(DependencyLock.layoutPath(source));
        Files.createDirectories(path.getParent());
        Files.writeString(path, "executable content under a sources classifier");
        configure(List.of());
        assertThrows(PyprojectModelException.class,
            () -> resolver().resolveToolArtifacts(model(), List.of(source), local, true, false));
    }

    @Test
    void manifestChangesInvalidateFingerprintsAndApplicationCachesCannotBypassVerification() throws Exception {
        Artifact jar = artifact("jar");
        configure(List.of(entry(jar)));
        String before = MavenRepositoryConfiguration.load().fingerprint();
        Path cache = tempDir.resolve("cache");
        ResolutionCache.write(cache, "hash", Map.of(InstallScope.RUNTIME, List.of(jar.getPath().toString())));
        assertFalse(ResolutionCache.cacheHit(cache, "hash", List.of(InstallScope.RUNTIME)));
        Files.writeString(bundleLock, Files.readString(bundleLock) + "\n# new trusted bundle\n");
        assertNotEquals(before, MavenRepositoryConfiguration.load().fingerprint());
        Files.delete(settings);
        assertTrue(ResolutionCache.cacheHit(cache, "hash", List.of(InstallScope.RUNTIME)));
    }

    @Test
    void completeToolLayoutsAreVerifiedAgain() throws Exception {
        Artifact jar = artifact("jar");
        configure(List.of(entry(jar)));
        Path packaged = Files.createDirectories(tempDir.resolve("packaged/pyronaut-run/bin"));
        Files.writeString(packaged.resolve("pyronaut-run"), "launcher");
        Files.writeString(packaged.resolve(ToolClasspathInstaller.DESCRIPTOR_FILE),
            "maven\torg.graalvm.example\tlanguage\t1.0\tjar\t\tlanguage-1.0.jar\n");
        Path tools = packaged.getParent().getParent();
        Files.writeString(tools.resolve(ToolClasspathInstaller.RUNTIME_PROPERTIES_FILE), "sdk.version=1.0\n");
        ToolClasspathInstaller installer = new ToolClasspathInstaller(resolver(), tools, tempDir.resolve("tools"));
        Path current = installer.install(model(), local, true, false);
        assertEquals(current, installer.install(model(), local, true, false));
        Files.writeString(jar.getPath(), "changed");
        assertThrows(PyprojectModelException.class, () -> installer.install(model(), local, true, false));
    }

    @Test
    void directSourceManifestsCannotBypassVerification() throws Exception {
        Artifact jar = artifact("jar");
        Artifact pom = artifact("pom");
        configure(List.of(entry(jar), entry(pom)));
        String platformVersion = PyronautManagedVersions.micronautPlatformVersion();
        Path platform = local.resolve("io/micronaut/platform/micronaut-platform/" + platformVersion);
        Files.createDirectories(platform);
        Files.writeString(platform.resolve("micronaut-platform-" + platformVersion + ".pom"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>io.micronaut.platform</groupId>
            <artifactId>micronaut-platform</artifactId><version>%s</version><packaging>pom</packaging></project>
            """.formatted(platformVersion));
        DirectSourceDependencyResolver resolver = new DirectSourceDependencyResolver(resolver());
        Path cache = tempDir.resolve("direct-source");
        assertFalse(resolver.resolveDetailed(cache, List.of(), List.of(MODULE + ":1.0"), List.of(), local, true, false, List.of()).cacheHit());
        assertReportContainsPomAndJar(cache, jar, pom);
        assertFalse(resolver.resolveDetailed(cache, List.of(), List.of(MODULE + ":1.0"), List.of(), local, true, false, List.of()).cacheHit());
        Files.writeString(jar.getPath(), "changed");
        assertThrows(PyprojectModelException.class,
            () -> resolver.resolveDetailed(cache, List.of(), List.of(MODULE + ":1.0"), List.of(), local, true, false, List.of()));
    }

    @Test
    void projectInstallationRecordsArtifactIdentitiesAndUsedPoms() throws Exception {
        Artifact jar = artifact("jar");
        Artifact pom = artifact("pom");
        configure(List.of(entry(jar), entry(pom)));
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Files.writeString(project.resolve("pyproject.toml"), """
            [project]
            name = "verification-report"
            [tool.pyronaut.dependencies]
            runtime = ["%s:1.0"]
            build = []
            test = []
            [tool.pyronaut.test-resources]
            enabled = false
            """.formatted(MODULE));
        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), resolver(),
            new PyprojectEditorSupport(), new PythonEditorSupport(), new ExternalBuildResolver(),
            (model, repository, offline, refresh, listener) -> null);
        command.projectDir = project;
        command.localRepository = local;
        command.offline = true;
        command.scope = "runtime";
        command.progress = "off";
        command.setEnvReader(name -> null);
        assertEquals(InstallExitCode.SUCCESS.code(), command.call());
        assertReportContainsPomAndJar(project.resolve("__pyronaut__"), jar, pom);
    }

    private void assertReportContainsPomAndJar(Path cache, Artifact jar, Artifact pom) throws IOException {
        JsonNode report;
        try (var input = Files.newInputStream(cache.resolve(MavenArtifactVerification.REPORT_FILE))) {
            report = JsonMapper.createDefault().readValue(input, JsonNode.class);
        }
        assertEquals(1, report.get("version").getIntValue());
        assertEquals(MavenRepositoryConfiguration.load().fingerprint(), report.get("mavenSettingsSha256").getStringValue());
        for (Artifact artifact : List.of(jar, pom)) {
            boolean present = false;
            for (JsonNode recorded : report.get("artifacts").values()) {
                if (recorded.get("path").getStringValue().equals(DependencyLock.layoutPath(artifact))) {
                    assertEquals(artifact.toString(), recorded.get("coordinates").getStringValue());
                    assertEquals(artifact.getGroupId(), recorded.get("groupId").getStringValue());
                    assertEquals(artifact.getPath().toAbsolutePath().normalize().toString(), recorded.get("file").getStringValue());
                    present = true;
                }
            }
            assertTrue(present, artifact.toString());
        }
    }

    @Test
    void validatesPolicyAndTrustedManifest() throws Exception {
        Artifact jar = artifact("jar");
        DependencyLock.Entry entry = entry(jar);
        configure(List.of(entry, entry));
        assertTrue(assertThrows(PyprojectModelException.class, MavenRepositoryConfiguration::load)
            .getMessage().contains("Duplicate"));
        configure(List.of(new DependencyLock.Entry("other:library:jar:1.0", entry.path(), "", "", entry.sha256())));
        assertTrue(assertThrows(PyprojectModelException.class, MavenRepositoryConfiguration::load)
            .getMessage().contains("coordinates do not match"));
        configure(List.of(entry));
        Files.writeString(bundleLock, Files.readString(bundleLock).replace("version = 1", "version = 1.5"));
        assertThrows(PyprojectModelException.class, MavenRepositoryConfiguration::load);
        for (String policy : List.of("groups=[]\nlock-file='bundle.lock'", "groups=['org.*.invalid']\nlock-file='bundle.lock'",
            "groups=['org.graalvm.*']", "lock-file='bundle.lock'", "groups=[1]\nlock-file='bundle.lock'")) {
            Files.writeString(settings, "[maven.verification]\n" + policy);
            assertThrows(PyprojectModelException.class, MavenRepositoryConfiguration::load);
        }
        configure(List.of(entry));
        Files.delete(bundleLock);
        assertTrue(assertThrows(PyprojectModelException.class, MavenRepositoryConfiguration::load)
            .getMessage().contains(bundleLock.toString()));
    }

    private Artifact artifact(String extension) throws IOException {
        Artifact artifact = new DefaultArtifact(MODULE + ":" + extension + ":1.0");
        Path path = local.resolve(DependencyLock.layoutPath(artifact));
        Files.createDirectories(path.getParent());
        Files.writeString(path, extension.equals("pom") ? """
            <project><modelVersion>4.0.0</modelVersion><groupId>org.graalvm.example</groupId>
            <artifactId>language</artifactId><version>1.0</version></project>
            """ : "source-built bundle");
        return artifact.setPath(path);
    }

    private static DependencyLock.Entry entry(Artifact artifact) throws IOException {
        return new DependencyLock.Entry(artifact.toString(), DependencyLock.layoutPath(artifact), "local", "", DependencyLock.sha256(artifact.getPath()));
    }

    private void configure(List<DependencyLock.Entry> entries) throws IOException {
        Files.writeString(bundleLock, DependencyLock.render(entries));
        Files.writeString(settings, """
            [maven.verification]
            lock-file = "bundle.lock"
            groups = ["org.graalvm.*"]
            """);
    }

    private MavenClasspathResolver resolver() {
        return new MavenClasspathResolver(new ProxyConfigurationLoader(Map.of(), tempDir.resolve("no-proxy"), tempDir.resolve("no-maven")));
    }

    private static PyprojectModel model() {
        return new PyprojectModel(null, null, new PyprojectModel.Pyronaut(null, null, List.of(), null,
            new PyprojectModel.Dependencies(List.of(MODULE + ":1.0"), List.of(), List.of(), List.of()),
            null, null, null, null, null, null, null, null, null, null, false));
    }
}

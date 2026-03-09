package io.micronaut.pyronaut.install;

import io.micronaut.pyronaut.config.model.PyprojectModelReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PyronautInstallMainTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesAndWritesScopedManifests() throws Exception {
        Path repository = tempDir.resolve("repo");
        writeArtifact(repository, "com.example", "runtime-dep", "1.0.0");
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        Path cacheDir = project.resolve(".pytest_cache");
        Path buildManifest = cacheDir.resolve("resolved-build-dependencies");
        Path runtimeManifest = cacheDir.resolve("resolved-runtime-dependencies");
        Path testManifest = cacheDir.resolve("resolved-test-dependencies");
        assertTrue(Files.exists(buildManifest));
        assertTrue(Files.exists(runtimeManifest));
        assertTrue(Files.exists(testManifest));

        List<String> buildEntries = Files.readAllLines(buildManifest, StandardCharsets.UTF_8);
        List<String> runtimeEntries = Files.readAllLines(runtimeManifest, StandardCharsets.UTF_8);
        List<String> testEntries = Files.readAllLines(testManifest, StandardCharsets.UTF_8);
        assertEquals(1, buildEntries.size());
        assertEquals(1, runtimeEntries.size());
        assertEquals(2, testEntries.size());
        assertTrue(buildEntries.getFirst().contains("build-dep"));
        assertTrue(runtimeEntries.getFirst().contains("runtime-dep"));
        assertTrue(testEntries.stream().anyMatch(entry -> entry.contains("runtime-dep")));
        assertTrue(testEntries.stream().anyMatch(entry -> entry.contains("test-dep")));
    }

    @Test
    void unchangedPyprojectUsesCache() throws Exception {
        Path repository = tempDir.resolve("repo-cache");
        writeArtifact(repository, "com.example", "runtime-dep", "1.0.0");
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-cache");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;
        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        Path runtimeManifest = project.resolve(".pytest_cache").resolve("resolved-runtime-dependencies");
        long firstModified = Files.getLastModifiedTime(runtimeManifest).toMillis();
        Thread.sleep(25);

        PyronautInstallMain secondRun = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        secondRun.projectDir = project;
        assertEquals(InstallExitCode.SUCCESS.code(), secondRun.call());
        long secondModified = Files.getLastModifiedTime(runtimeManifest).toMillis();

        assertEquals(firstModified, secondModified);
    }

    @Test
    void unresolvedDependencyReturnsResolutionError() throws Exception {
        Path project = tempDir.resolve("project-failure");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), """
            [project]
            name = "broken"

            [tool.pyronaut]
            repositories = ["%s"]

            [tool.pyronaut.dependencies]
            runtime = ["com.example:missing:1.0.0"]
            build = []
            test = []
            """.formatted(project.resolve("empty-repo").toUri()));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.RESOLUTION_ERROR.code(), command.call());
        assertFalse(Files.exists(project.resolve(".pytest_cache").resolve("resolved-runtime-dependencies")));
    }

    private static String pyproject(Path repository) {
        return """
            [project]
            name = "install-test"
            version = "1.0.0"

            [tool.pyronaut]
            repositories = ["%s"]

            [tool.pyronaut.dependencies]
            runtime = ["com.example:runtime-dep:1.0.0"]
            build = ["com.example:build-dep:1.0.0"]
            test = ["com.example:test-dep:1.0.0"]
            """.formatted(repository.toUri());
    }

    private static void writeArtifact(Path repository, String groupId, String artifactId, String version) throws IOException {
        Path artifactDir = repository
            .resolve(groupId.replace('.', '/'))
            .resolve(artifactId)
            .resolve(version);
        Files.createDirectories(artifactDir);

        Path pomFile = artifactDir.resolve(artifactId + "-" + version + ".pom");
        Files.writeString(pomFile, """
            <project xmlns=\"http://maven.apache.org/POM/4.0.0\"
                     xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"
                     xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd\">
              <modelVersion>4.0.0</modelVersion>
              <groupId>%s</groupId>
              <artifactId>%s</artifactId>
              <version>%s</version>
            </project>
            """.formatted(groupId, artifactId, version));

        Path jarFile = artifactDir.resolve(artifactId + "-" + version + ".jar");
        Files.write(jarFile, new byte[]{0});
    }
}

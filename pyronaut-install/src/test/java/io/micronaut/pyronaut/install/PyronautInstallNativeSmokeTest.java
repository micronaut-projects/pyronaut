package io.micronaut.pyronaut.install;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "pyronaut.install.native.binary", matches = ".+")
class PyronautInstallNativeSmokeTest {

    @TempDir
    Path tempDir;

    @Test
    void nativeBinaryResolvesAndWritesManifests() throws Exception {
        String binaryPath = System.getProperty("pyronaut.install.native.binary");
        Path binary = Path.of(binaryPath);

        Path project = tempDir.resolve("project");
        Path repository = tempDir.resolve("repo");
        Files.createDirectories(project);
        writeArtifact(repository, "com.example", "runtime-dep", "1.0.0");
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        Process process = new ProcessBuilder(binary.toString(), "--project-dir", project.toString())
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();

        assertEquals(InstallExitCode.SUCCESS.code(), exitCode, output);
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
        assertTrue(buildEntries.stream().anyMatch(entry -> entry.contains("build-dep")), output);
        assertTrue(runtimeEntries.stream().anyMatch(entry -> entry.contains("runtime-dep")), output);
        assertTrue(testEntries.stream().anyMatch(entry -> entry.contains("runtime-dep")), output);
        assertTrue(testEntries.stream().anyMatch(entry -> entry.contains("test-dep")), output);
    }

    private static String pyproject(Path repository) {
        return """
            [project]
            name = "native-smoke"
            version = "1.0.0"

            [tool.pyronaut]
            repositories = ["%s"]

            [tool.pyronaut.dependencies]
            runtime = ["com.example:runtime-dep:1.0.0"]
            build = ["com.example:build-dep:1.0.0"]
            test = ["com.example:test-dep:1.0.0"]
            """.formatted(repository.toUri());
    }

    private static void writeArtifact(Path repository, String groupId, String artifactId, String version) throws Exception {
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

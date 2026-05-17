package io.micronaut.pyronaut.testresources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "pyronaut.testresources.native.binary", matches = ".+")
class PyronautTestResourcesServerNativeSmokeTest {

    @TempDir
    Path tempDir;

    @Test
    void nativeBinaryStartsReportsStatusAndStops() throws Exception {
        Path project = prepareProject();
        Path binary = Path.of(System.getProperty("pyronaut.testresources.native.binary"));

        RunResult start = run(binary, project, "start");
        try {
            assertEquals(0, start.exitCode(), start.output());
            assertTrue(start.output().contains("running uri=http://localhost:"), start.output());

            Properties settings = loadSettings(project);
            String serverUri = settings.getProperty("server.uri");
            String token = settings.getProperty("server.access.token");
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> health = client.send(
                HttpRequest.newBuilder(URI.create(serverUri + "/api/test-resources/health"))
                    .header("Access-Token", token)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            assertEquals(200, health.statusCode(), health.body());
            assertTrue(health.body().contains("\"status\":\"UP\""), health.body());

            HttpResponse<String> containers = client.send(
                HttpRequest.newBuilder(URI.create(serverUri + "/api/test-resources/containers"))
                    .header("Access-Token", token)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            assertEquals(200, containers.statusCode(), containers.body());
            assertTrue(containers.body().contains("\"containers\""), containers.body());

            RunResult status = run(binary, project, "status");
            assertEquals(0, status.exitCode(), status.output());
            assertTrue(status.output().contains("running uri=http://localhost:"), status.output());
        } finally {
            RunResult stop = run(binary, project, "stop");
            assertEquals(0, stop.exitCode(), stop.output());
        }

        RunResult stoppedStatus = run(binary, project, "status");
        assertEquals(0, stoppedStatus.exitCode(), stoppedStatus.output());
        assertTrue(stoppedStatus.output().contains("stopped"), stoppedStatus.output());
    }

    private Path prepareProject() throws Exception {
        Path project = tempDir.resolve("app");
        Path pyronautDir = project.resolve("__pyronaut__");
        Files.createDirectories(pyronautDir);
        Files.writeString(project.resolve("pyproject.toml"), """
            [project]
            name = "test-resources-native-smoke"
            version = "1.0.0"

            [tool.pyronaut.core]
            version = "5.0.0-SNAPSHOT"

            [tool.pyronaut.testResources]
            startupOptimization = "none"
            """, StandardCharsets.UTF_8);
        Files.writeString(
            pyronautDir.resolve("resolved-test-resources-server-dependencies"),
            installLibManifest(),
            StandardCharsets.UTF_8
        );
        return project;
    }

    private static Properties loadSettings(Path project) throws Exception {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(
            project.resolve(".micronaut/test-resources/test-resources.properties"),
            StandardCharsets.UTF_8
        )) {
            properties.load(reader);
        }
        return properties;
    }

    private static RunResult run(Path binary, Path project, String action) throws Exception {
        Process process = new ProcessBuilder(
            binary.toString(),
            action,
            "--project-dir",
            project.toString()
        )
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        return new RunResult(exitCode, output);
    }

    private static String installLibManifest() throws Exception {
        String libDirProperty = System.getProperty("pyronaut.testresources.install.lib.dir", "");
        if (libDirProperty.isBlank()) {
            throw new IllegalStateException("Missing pyronaut.testresources.install.lib.dir system property");
        }
        Path libDir = Path.of(libDirProperty).toAbsolutePath().normalize();
        try (Stream<Path> stream = Files.list(libDir)) {
            return stream
                .filter(path -> path.getFileName().toString().endsWith(".jar"))
                .sorted()
                .map(path -> path.toAbsolutePath().normalize().toString())
                .reduce("", (left, right) -> left + right + System.lineSeparator());
        }
    }

    private record RunResult(int exitCode, String output) {
    }
}

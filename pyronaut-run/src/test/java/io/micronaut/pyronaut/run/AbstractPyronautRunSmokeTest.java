package io.micronaut.pyronaut.run;

import io.micronaut.pyronaut.processor.PyronautProcessorMain;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

abstract class AbstractPyronautRunSmokeTest {

    @TempDir
    Path tempDir;

    protected void assertHelloWorldApplicationServesHttpResponse() throws Exception {
        Path project = tempDir.resolve("app");
        int port = reservePort();
        prepareProject(project, port);

        int processExit = new picocli.CommandLine(new PyronautProcessorMain()).execute(
            "--project-dir", project.toString(),
            "--no-cache"
        );
        assertEquals(0, processExit);
        assertProcessedMainArtifacts(project);

        RunResult result = runPyronautRun(project);
        assertTrue(result.responseBody().contains("Hello World"), result.output());
    }

    protected abstract RunResult runPyronautRun(Path project) throws Exception;

    protected static RunResult runJvm(Path project) throws Exception {
        List<String> classpathEntries = new ArrayList<>();
        classpathEntries.addAll(readManifestEntries(project.resolve("__pyronaut__/resolved-runtime-dependencies")));
        classpathEntries.add(project.resolve("__pyronaut__/classes").toAbsolutePath().normalize().toString());
        classpathEntries.addAll(currentRuntimeClasspathEntries());
        return runCommand(
            project,
            List.of(
                javaExecutable().toString(),
                "--sun-misc-unsafe-memory-access=allow",
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                String.join(File.pathSeparator, classpathEntries),
                PyronautRunMain.class.getName(),
                "--project-dir",
                project.toString()
            ),
            Map.of()
        );
    }

    protected static RunResult runNative(Path binary, Path project) throws Exception {
        return runCommand(
            project,
            List.of(
                binary.toString(),
                "--project-dir",
                project.toString()
            ),
            Map.of()
        );
    }

    private static RunResult runCommand(Path project, List<String> command, Map<String, String> additionalEnvironment) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder(command)
            .redirectErrorStream(true);
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            processBuilder.environment().put("JAVA_HOME", javaHome);
        }
        processBuilder.environment().put("MICRONAUT_SERVER_PORT", Integer.toString(configuredPort(project)));
        processBuilder.environment().putAll(additionalEnvironment);
        Process process = processBuilder.start();
        ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
        Thread outputReader = new Thread(() -> copyOutput(process, outputBuffer), "pyronaut-run-smoke-output");
        outputReader.start();
        try {
            String response = waitForHelloResponse(project, process, outputBuffer);
            return new RunResult(response, snapshot(outputBuffer));
        } finally {
            stopProcess(process);
            outputReader.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    private static void copyOutput(Process process, ByteArrayOutputStream outputBuffer) {
        try (var input = process.getInputStream()) {
            input.transferTo(outputBuffer);
        } catch (IOException ignored) {
        }
    }

    private static void stopProcess(Process process) throws InterruptedException {
        if (!process.isAlive()) {
            return;
        }
        process.destroy();
        if (process.waitFor(10, TimeUnit.SECONDS)) {
            return;
        }
        process.destroyForcibly();
        process.waitFor(10, TimeUnit.SECONDS);
    }

    private static String waitForHelloResponse(Path project,
                                               Process process,
                                               ByteArrayOutputStream outputBuffer) throws Exception {
        int port = configuredPort(project);
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/hello"))
            .timeout(Duration.ofSeconds(2))
            .GET()
            .build();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        Exception lastFailure = null;
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                fail("pyronaut-run exited before serving requests:" + System.lineSeparator() + snapshot(outputBuffer));
            }
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() == 200) {
                    return response.body();
                }
                lastFailure = new IllegalStateException("Unexpected status " + response.statusCode());
            } catch (Exception e) {
                lastFailure = e;
            }
            Thread.sleep(200);
        }
        String message = "Timed out waiting for pyronaut-run to serve /hello:" + System.lineSeparator() + snapshot(outputBuffer);
        if (lastFailure != null) {
            throw new IllegalStateException(message, lastFailure);
        }
        throw new IllegalStateException(message);
    }

    private static String snapshot(ByteArrayOutputStream outputBuffer) {
        return outputBuffer.toString(StandardCharsets.UTF_8);
    }

    private static int configuredPort(Path project) throws IOException {
        for (String line : Files.readAllLines(project.resolve("config/application.properties"), StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("micronaut.server.port=")) {
                return Integer.parseInt(trimmed.substring("micronaut.server.port=".length()));
            }
        }
        throw new IllegalStateException("Missing micronaut.server.port in config/application.properties");
    }

    private static List<String> readManifestEntries(Path manifest) throws IOException {
        if (!Files.exists(manifest)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    private void prepareProject(Path project, int port) throws Exception {
        Path pyronautDir = project.resolve("__pyronaut__");
        Path configDir = project.resolve("config");
        Files.createDirectories(pyronautDir);
        Files.createDirectories(configDir);
        writeProjectFile(project, "pyproject.toml", minimalPyproject());
        writeProjectFile(project, "src/main.py", "from app import HelloController\n");
        writeProjectFile(
            project,
            "src/app.py",
            """
                from micronaut.context.annotation import EachProperty
                from micronaut.http.annotation import Controller, Get

                @EachProperty("engines")
                class EngineConfiguration:
                    cylinders: int
                    enabled: bool = True

                @Controller("/hello")
                class HelloController:
                    @Get(produces="text/plain")
                    def index(self) -> str:
                        return "Hello World"
                """
        );
        writeProjectFile(
            project,
            "config/application.properties",
            "micronaut.server.port=" + port + System.lineSeparator()
        );
        String manifest = currentRuntimeClasspathManifest();
        Files.writeString(pyronautDir.resolve("resolved-build-dependencies"), manifest, StandardCharsets.UTF_8);
        Files.writeString(pyronautDir.resolve("resolved-runtime-dependencies"), manifest, StandardCharsets.UTF_8);
        Files.writeString(pyronautDir.resolve("resolved-test-dependencies"), manifest, StandardCharsets.UTF_8);
    }

    private static void assertProcessedMainArtifacts(Path project) throws IOException {
        Path classesDir = project.resolve("__pyronaut__/classes");
        assertTrue(Files.isDirectory(classesDir), "Missing classes dir: " + classesDir);
        assertTrue(Files.exists(classesDir.resolve("pyronaut_application/PyronautMain.class")));
        Path beanRefs = classesDir.resolve("META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference");
        assertTrue(Files.isDirectory(beanRefs), "Missing bean definitions dir: " + beanRefs);
        List<String> fileNames;
        try (var stream = Files.walk(beanRefs)) {
            fileNames = stream
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .toList();
        }
        assertFalse(fileNames.isEmpty(), "No bean definitions generated under " + beanRefs);
    }

    private static void writeProjectFile(Path project, String relativePath, String contents) throws IOException {
        Path target = project.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, contents, StandardCharsets.UTF_8);
    }

    private static String currentRuntimeClasspathManifest() {
        return String.join(System.lineSeparator(), currentRuntimeClasspathEntries()) + System.lineSeparator();
    }

    private static LinkedHashSet<String> currentRuntimeClasspathEntries() {
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        String rawClasspath = System.getProperty("java.class.path", "");
        if (rawClasspath.isBlank()) {
            return entries;
        }
        for (String entry : rawClasspath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Path path = Path.of(trimmed).toAbsolutePath().normalize();
            if (Files.exists(path)) {
                entries.add(path.toString());
            }
        }
        return entries;
    }

    private static Path javaExecutable() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isBlank()) {
            Path java = Path.of(javaHome).resolve("bin").resolve("java");
            if (Files.isExecutable(java)) {
                return java;
            }
        }
        return Path.of("java");
    }

    private static int reservePort() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
            return serverSocket.getLocalPort();
        }
    }

    private static String minimalPyproject() {
        return """
            [project]
            name = "pyronaut-run-smoke"
            version = "1.0.0"

            [tool.pyronaut.core]
            version = "5.1.0-SNAPSHOT"
            """;
    }

    protected record RunResult(String responseBody, String output) {
    }
}

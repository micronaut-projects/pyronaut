/*
 * Copyright 2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.pyronaut.dev;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FastApiTutorialSmokeTest {

    @Test
    void executesDocumentedApplicationAndTests(@TempDir Path tempDir) throws Exception {
        Path repositoryRoot = Path.of("").toAbsolutePath().normalize();
        while (repositoryRoot != null && !Files.isRegularFile(repositoryRoot.resolve("settings.gradle.kts"))) {
            repositoryRoot = repositoryRoot.getParent();
        }
        assertTrue(repositoryRoot != null, "Could not locate the repository root");
        Path fixture = repositoryRoot.resolve("src/main/docs/examples/gettingStarted/fastapi/upgraded");
        Path documentedSource = fixture.resolve("main.py");
        Path documentedTest = fixture.resolve("test_main.py");
        assertTrue(Files.isRegularFile(documentedSource), documentedSource.toString());
        assertTrue(Files.isRegularFile(documentedTest), documentedTest.toString());
        Path source = tempDir.resolve("main.py");
        Path test = tempDir.resolve("test_main.py");
        Files.copy(documentedSource, source);
        Files.copy(documentedTest, test);

        Path developmentDirectory = Files.createDirectory(tempDir.resolve("development"));
        Path developmentSource = developmentDirectory.resolve("main.py");
        Files.copy(documentedSource, developmentSource);
        verifyDevelopmentDocumentation(developmentDirectory, developmentSource);

        Path outputFile = tempDir.resolve("smoke-output.txt");
        List<String> command = smokeCommand(tempDir);
        command.addAll(List.of("test", source.toString(), "--", test.toString()));

        Process process = new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(outputFile.toFile())
            .start();
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        String testOutput = Files.readString(outputFile);
        assertTrue(finished, testOutput);
        assertEquals(0, process.exitValue(), testOutput);
        assertTrue(testOutput.contains("4 tests successful"), testOutput);
        assertTrue(testOutput.contains("0 tests skipped"), testOutput);
        assertTrue(testOutput.contains("0 tests failed"), testOutput);
    }

    private static void verifyDevelopmentDocumentation(Path projectDirectory, Path source) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Path outputFile = projectDirectory.resolve("dev-output.txt");
        List<String> command = smokeCommand(projectDirectory);
        command.addAll(List.of("--port", Integer.toString(port), source.toString()));
        Process process = new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(outputFile.toFile())
            .start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> root = awaitResponse(client, process, outputFile, port, "/");
            assertEquals(200, root.statusCode(), Files.readString(outputFile));
            assertTrue(root.body().contains("\"Hello\":\"World\""), root.body());
            assertResponseContains(client, process, outputFile, port, "/swagger/service-1.0.0.yml", "/items/{item_id}");
            assertResponseContains(client, process, outputFile, port, "/swagger-ui/index.html", "SwaggerUIBundle");
            assertResponseContains(client, process, outputFile, port, "/redoc/index.html", "redoc");
        } finally {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        }
    }

    private static HttpResponse<String> awaitResponse(
        HttpClient client,
        Process process,
        Path outputFile,
        int port,
        String path
    ) throws Exception {
        URI uri = URI.create("http://localhost:" + port + path);
        Exception lastFailure = null;
        for (int attempt = 0; attempt < 120; attempt++) {
            if (!process.isAlive()) {
                throw new IllegalStateException("Development server exited early:\n" + Files.readString(outputFile));
            }
            try {
                return client.send(
                    HttpRequest.newBuilder(uri).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
                );
            } catch (IOException e) {
                lastFailure = e;
                Thread.sleep(250);
            }
        }
        throw new IllegalStateException("Server did not start at " + uri, lastFailure);
    }

    private static void assertResponseContains(
        HttpClient client,
        Process process,
        Path outputFile,
        int port,
        String path,
        String expected
    ) throws Exception {
        HttpResponse<String> response = awaitResponse(client, process, outputFile, port, path);
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains(expected), response.body());
    }

    private static List<String> smokeCommand(Path projectDirectory) throws URISyntaxException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Dpyronaut.dev.project.dir=" + projectDirectory);
        String compilerClasspath = System.getProperty("pyronaut.dev.compiler.class.path");
        if (compilerClasspath != null) {
            command.add("-Dpyronaut.dev.compiler.class.path=" + compilerClasspath);
        }
        command.add("-classpath");
        command.add(testProcessClasspath());
        command.add(SmokeLauncher.class.getName());
        return command;
    }

    private static String testProcessClasspath() throws URISyntaxException {
        Set<String> entries = new LinkedHashSet<>(
            Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator))
        );
        for (ClassLoader loader = FastApiTutorialSmokeTest.class.getClassLoader();
             loader != null;
             loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urlClassLoader) {
                for (URL url : urlClassLoader.getURLs()) {
                    if ("file".equals(url.getProtocol())) {
                        entries.add(Path.of(url.toURI()).toString());
                    }
                }
            }
        }
        return String.join(File.pathSeparator, entries);
    }

    public static final class SmokeLauncher {
        private SmokeLauncher() {
        }

        public static void main(String[] args) {
            System.exit(PyronautDevMain.execute(args));
        }
    }
}

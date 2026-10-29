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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micronaut.pyronaut.config.model.PyprojectModel;
import io.micronaut.pyronaut.config.model.PyprojectModelException;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenRepositoryCredentialsTest {
    private static final String USERNAME = "repo-user";
    private static final String PASSWORD = "repo-password";

    @TempDir
    Path tempDir;

    private String previousPyronautHome;
    private MavenHttpRepository repository;

    @BeforeEach
    void useIsolatedPyronautHome() throws IOException {
        previousPyronautHome = System.getProperty("pyronaut.home");
        Files.createDirectories(tempDir.resolve("home"));
        System.setProperty("pyronaut.home", tempDir.resolve("home").toString());
    }

    @AfterEach
    void restorePyronautHome() {
        if (repository != null) {
            repository.close();
        }
        if (previousPyronautHome == null) {
            System.clearProperty("pyronaut.home");
        } else {
            System.setProperty("pyronaut.home", previousPyronautHome);
        }
    }

    @Test
    void resolvesScopesAndToolsWithPathScopedCredentials() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("root"));
        Path privateRepository = Files.createDirectories(tempDir.resolve("private"));
        Path unrelated = Files.createDirectories(tempDir.resolve("unrelated"));
        writeArtifact(root, "example", "root", "1.0", """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>example</groupId>
              <artifactId>root</artifactId>
              <version>1.0</version>
              <repositories>
                <repository>
                  <id>private</id>
                  <url>%s</url>
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
            """.formatted("PRIVATE_URL_PLACEHOLDER"));
        writeArtifact(root, "example", "tool", "1.0", emptyPom("example", "tool", "1.0"));
        writeArtifact(privateRepository, "example", "transitive", "1.0", emptyPom("example", "transitive", "1.0"));
        writeArtifact(unrelated, "example", "unrelated", "1.0", emptyPom("example", "unrelated", "1.0"));

        repository = MavenHttpRepository.start(Map.of(
            "/root/", root,
            "/private/", privateRepository,
            "/root-other/", unrelated
        ), Map.of(
            "/root/", basicAuth(USERNAME, PASSWORD),
            "/private/", basicAuth(" private-user ", "")
        ));
        String rootUrl = repository.url("/root/");
        String privateUrl = repository.url("/private/");
        String unrelatedUrl = repository.url("/root-other/");
        replaceInFiles(root, "PRIVATE_URL_PLACEHOLDER", privateUrl);
        writeSettings("""
            [maven]
            [[maven.credentials]]
            url = "%s"
            username-env = "REPOSITORY_USER"
            password-env = "REPOSITORY_PASSWORD"

            [[maven.credentials]]
            url = "%s"
            username = " private-user "
            password = ""
            """.formatted(rootUrl, privateUrl));

        Map<String, String> environment = Map.of(
            "REPOSITORY_USER", USERNAME,
            "REPOSITORY_PASSWORD", PASSWORD
        );
        MavenClasspathResolver resolver = resolver(environment);
        PyprojectModel model = model(List.of(rootUrl, unrelatedUrl), List.of(
            "example:root:1.0",
            "example:unrelated:1.0"
        ));

        MavenClasspathResolver.ResolvedScopeDetails scope = resolver.resolveScopeDetails(
            model,
            InstallScope.RUNTIME,
            tempDir.resolve("scope-local"),
            false
        );
        assertTrue(scope.classpath().stream().anyMatch(path -> path.endsWith("root-1.0.jar")));
        assertTrue(scope.classpath().stream().anyMatch(path -> path.endsWith("transitive-1.0.jar")));
        assertTrue(scope.classpath().stream().anyMatch(path -> path.endsWith("unrelated-1.0.jar")));

        List<Path> tools = resolver.resolveToolArtifacts(
            model,
            List.of(new DefaultArtifact("example", "tool", "jar", "1.0")),
            tempDir.resolve("tool-local"),
            false,
            false
        );
        assertTrue(tools.getFirst().endsWith("tool-1.0.jar"));
        assertTrue(repository.authorizations("/root/").contains(basicAuth(USERNAME, PASSWORD)));
        assertTrue(repository.authorizations("/private/").contains(basicAuth(" private-user ", "")));
        assertTrue(repository.authorizations("/root-other/").stream().allMatch(value -> value == null));
    }

    @Test
    void resolvesCredentialsForAnAuthenticatedMirror() throws Exception {
        Path mirror = Files.createDirectories(tempDir.resolve("mirror"));
        writeArtifact(mirror, "example", "mirrored", "1.0", emptyPom("example", "mirrored", "1.0"));
        repository = MavenHttpRepository.start(
            Map.of("/mirror/", mirror),
            Map.of("/mirror/", basicAuth(USERNAME, PASSWORD))
        );
        String mirrorUrl = repository.url("/mirror/");
        writeSettings("""
            [maven]
            [[maven.mirrors]]
            id = "protected-mirror"
            url = "%s"
            mirror-of = "mavenCentral"

            [[maven.credentials]]
            url = "%s"
            username-env = "REPOSITORY_USER"
            password-env = "REPOSITORY_PASSWORD"
            """.formatted(mirrorUrl, mirrorUrl));

        List<Path> artifacts = resolver(Map.of(
            "REPOSITORY_USER", USERNAME,
            "REPOSITORY_PASSWORD", PASSWORD
        )).resolveToolArtifacts(
            model(List.of("mavenCentral"), List.of()),
            List.of(new DefaultArtifact("example", "mirrored", "jar", "1.0")),
            tempDir.resolve("mirror-local"),
            false,
            false
        );
        assertTrue(artifacts.getFirst().endsWith("mirrored-1.0.jar"));
        assertTrue(repository.authorizations("/mirror/").contains(basicAuth(USERNAME, PASSWORD)));
    }

    @Test
    void missingAndWrongCredentialsFailWithoutLeakingSecrets() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("root"));
        writeArtifact(root, "example", "tool", "1.0", emptyPom("example", "tool", "1.0"));
        repository = MavenHttpRepository.start(
            Map.of("/root/", root),
            Map.of("/root/", basicAuth(USERNAME, PASSWORD))
        );
        String rootUrl = repository.url("/root/");
        writeSettings("""
            [maven]
            [[maven.credentials]]
            url = "%s"
            username-env = "MISSING_USER"
            password-env = "MISSING_PASSWORD"
            """.formatted(rootUrl));

        PyprojectModel model = model(List.of(rootUrl), List.of());
        PyprojectModelException missing = assertThrows(PyprojectModelException.class, () -> resolver(Map.of())
            .resolveToolArtifacts(model, List.of(new DefaultArtifact("example", "tool", "jar", "1.0")),
                tempDir.resolve("missing-local"), false, false));
        assertTrue(missing.getMessage().contains("MISSING_USER"));
        assertFalse(missing.toString().contains(PASSWORD));

        writeSettings("""
            [maven]
            [[maven.credentials]]
            url = "%s"
            username = "%s"
            password = "wrong-secret"
            """.formatted(rootUrl, USERNAME));
        PyprojectModelException wrong = assertThrows(PyprojectModelException.class, () -> resolver(Map.of())
            .resolveToolArtifacts(model, List.of(new DefaultArtifact("example", "tool", "jar", "1.0")),
                tempDir.resolve("wrong-local"), false, false));
        assertFalse(wrong.toString().contains("wrong-secret"));
    }

    @Test
    void offlineResolutionUsesCachedArtifactWithoutResolvingEnvironmentCredentials() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("root"));
        writeArtifact(root, "example", "cached", "1.0", emptyPom("example", "cached", "1.0"));
        repository = MavenHttpRepository.start(
            Map.of("/root/", root),
            Map.of("/root/", basicAuth(USERNAME, PASSWORD))
        );
        String rootUrl = repository.url("/root/");
        writeSettings("""
            [maven]
            [[maven.credentials]]
            url = "%s"
            username-env = "REPOSITORY_USER"
            password-env = "REPOSITORY_PASSWORD"
            """.formatted(rootUrl));
        PyprojectModel model = model(List.of(rootUrl), List.of());
        Path local = tempDir.resolve("local");

        List<Path> online = resolver(Map.of(
            "REPOSITORY_USER", USERNAME,
            "REPOSITORY_PASSWORD", PASSWORD
        )).resolveToolArtifacts(
            model,
            List.of(new DefaultArtifact("example", "cached", "jar", "1.0")),
            local,
            false,
            false
        );
        assertTrue(online.getFirst().endsWith("cached-1.0.jar"));
        repository.close();
        repository = null;

        List<Path> offline = resolver(Map.of()).resolveToolArtifacts(
            model,
            List.of(new DefaultArtifact("example", "cached", "jar", "1.0")),
            local,
            true,
            false
        );
        assertTrue(offline.getFirst().endsWith("cached-1.0.jar"));
    }

    @Test
    void validatesCredentialTablesAndRedactsSecrets() throws Exception {
        writeSettings("""
            [maven]
            [[maven.credentials]]
            url = "https://repo.example/repository/"
            username = "literal-user"
            password = "literal-secret"
            """);
        MavenRepositoryConfiguration valid = MavenRepositoryConfiguration.load(settingsPath());
        assertFalse(valid.toString().contains("literal-user"));
        assertFalse(valid.toString().contains("literal-secret"));

        List<String> invalidSettings = List.of(
            """
                [maven]
                [[maven.credentials]]
                url = "ftp://repo.example/repository/"
                username = "u"
                password = "p"
                """,
            """
                [maven]
                [[maven.credentials]]
                url = "https://user@repo.example/repository/"
                username = "u"
                password = "p"
                """,
            """
                [maven]
                [[maven.credentials]]
                url = "https://repo.example/repository/?query=true"
                username = "u"
                password = "p"
                """,
            """
                [maven]
                [[maven.credentials]]
                url = "https://repo.example/repository/#fragment"
                username = "u"
                password = "p"
                """,
            """
                [maven]
                [[maven.credentials]]
                url = "https://repo.example/repository/"
                username = "u"
                username-env = "U"
                password = "p"
                """,
            """
                [maven]
                [[maven.credentials]]
                url = "https://repo.example/repository/"
                username = "u"
                password = "p"
                password-env = "P"
                """,
            """
                [maven]
                [[maven.credentials]]
                url = "https://repo.example/repository/"
                password = "p"
                """,
            """
                [maven]
                [[maven.credentials]]
                url = "https://repo.example/repository/"
                username = "u"
                """,
            """
                [maven]
                [[maven.credentials]]
                url = 42
                username = "u"
                password = "p"
                """,
            """
                [maven]
                [[maven.credentials]]
                url = "https://repo.example/repository/"
                username = 42
                password = "p"
                """,
            """
                [maven]
                [[maven.credentials]]
                url = "https://repo.example/repository/"
                username = "u"
                password = 42
                """,
            """
                [maven]
                [[maven.credentials]]
                url = "https://repo.example/repository/"
                username = "u"
                password = "p"
                [[maven.credentials]]
                url = "https://repo.example/repository"
                username = "u"
                password = "p"
                """
        );
        for (String settings : invalidSettings) {
            writeSettings(settings);
            assertThrows(PyprojectModelException.class, () -> MavenRepositoryConfiguration.load(settingsPath()));
        }
    }

    private MavenClasspathResolver resolver(Map<String, String> environment) {
        return new MavenClasspathResolver(
            new ProxyConfigurationLoader(
                Map.of(),
                tempDir.resolve("missing-proxy.toml"),
                tempDir.resolve("missing-maven-settings.xml")
            ),
            environment::get
        );
    }

    private Path settingsPath() throws IOException {
        Path settings = tempDir.resolve("home/settings.toml");
        Files.createDirectories(settings.getParent());
        return settings;
    }

    private void writeSettings(String contents) throws IOException {
        Files.writeString(settingsPath(), contents, StandardCharsets.UTF_8);
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

    private static void replaceInFiles(Path root, String oldValue, String newValue) throws IOException {
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Files.writeString(file, Files.readString(file).replace(oldValue, newValue), StandardCharsets.UTF_8);
            }
        }
    }

    private static String basicAuth(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static final class MavenHttpRepository implements AutoCloseable {
        private final HttpServer server;
        private final Map<String, String> expectedAuthorization;
        private final Map<String, List<String>> receivedAuthorization = new ConcurrentHashMap<>();

        private MavenHttpRepository(HttpServer server, Map<String, String> expectedAuthorization) {
            this.server = server;
            this.expectedAuthorization = expectedAuthorization;
        }

        static MavenHttpRepository start(Map<String, Path> roots,
                                         Map<String, String> expectedAuthorization) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            MavenHttpRepository repository = new MavenHttpRepository(server, expectedAuthorization);
            for (Map.Entry<String, Path> entry : roots.entrySet()) {
                String prefix = entry.getKey();
                Path root = entry.getValue().toAbsolutePath().normalize();
                server.createContext(prefix, exchange -> repository.serve(prefix, root, exchange));
            }
            server.start();
            return repository;
        }

        String url(String prefix) {
            return "http://127.0.0.1:" + server.getAddress().getPort() + prefix;
        }

        List<String> authorizations(String prefix) {
            return receivedAuthorization.getOrDefault(prefix, List.of());
        }

        private void serve(String prefix, Path root, HttpExchange exchange) throws IOException {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            receivedAuthorization.computeIfAbsent(prefix, ignored -> new CopyOnWriteArrayList<>()).add(authorization);
            Path relative = Path.of(exchange.getRequestURI().getPath().substring(prefix.length())).normalize();
            Path file = root.resolve(relative).normalize();
            String expected = expectedAuthorization.get(prefix);
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            if (expected != null && !expected.equals(authorization)) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=repository");
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            byte[] bytes = Files.readAllBytes(file);
            exchange.sendResponseHeaders(200, bytes.length);
            try {
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}

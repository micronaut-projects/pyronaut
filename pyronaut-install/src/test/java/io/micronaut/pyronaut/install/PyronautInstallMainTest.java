package io.micronaut.pyronaut.install;

import io.micronaut.pyronaut.config.model.PyprojectModelReader;
import io.micronaut.pyronaut.config.model.PyprojectJsonSchemaGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

class PyronautInstallMainTest {

    @TempDir
    Path tempDir;

    private String previousIdeStubsCacheDir;

    @BeforeEach
    void setSharedIdeStubsCacheDir() {
        previousIdeStubsCacheDir = System.getProperty("pyronaut.ide-stubs.cache-dir");
        System.setProperty("pyronaut.ide-stubs.cache-dir", tempDir.resolve("shared-ide-stubs-cache").toString());
    }

    @AfterEach
    void restoreSharedIdeStubsCacheDir() {
        restoreSystemProperty("pyronaut.ide-stubs.cache-dir", previousIdeStubsCacheDir);
    }

    @Test
    void initializesJavaHomeFromEnvironmentWhenMissing() {
        String previousJavaHome = System.getProperty("java.home");
        try {
            System.clearProperty("java.home");

            PyronautInstallMain.initializeJavaHomeIfMissing(() -> "/tmp/graalvm-home");

            assertEquals("/tmp/graalvm-home", System.getProperty("java.home"));
        } finally {
            restoreJavaHome(previousJavaHome);
        }
    }

    @Test
    void leavesJavaHomeUnsetWhenEnvironmentMissing() {
        String previousJavaHome = System.getProperty("java.home");
        try {
            System.clearProperty("java.home");

            PyronautInstallMain.initializeJavaHomeIfMissing(() -> null);

            assertNull(System.getProperty("java.home"));
        } finally {
            restoreJavaHome(previousJavaHome);
        }
    }

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

        Path cacheDir = project.resolve("__pyronaut__");
        Path buildManifest = cacheDir.resolve("resolved-build-dependencies");
        Path runtimeManifest = cacheDir.resolve("resolved-runtime-dependencies");
        Path developmentRuntimeManifest = cacheDir.resolve("resolved-development-runtime-dependencies");
        Path testManifest = cacheDir.resolve("resolved-test-dependencies");
        Path testResourcesServerManifest = cacheDir.resolve("resolved-test-resources-server-dependencies");
        assertTrue(Files.exists(buildManifest));
        assertTrue(Files.exists(runtimeManifest));
        assertTrue(Files.exists(developmentRuntimeManifest));
        assertTrue(Files.exists(testManifest));
        assertTrue(Files.exists(testResourcesServerManifest));

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
    void injectsMicronautManagementOnlyIntoDevelopmentRuntimeManifest() throws Exception {
        Path repository = tempDir.resolve("repo-management-default");
        writeArtifact(repository, "com.example", "runtime-dep", "1.0.0");
        writeArtifact(repository, "io.micronaut", "micronaut-management", "1.2.3");
        writeBom(repository, "io.micronaut", "micronaut-core-bom", "1.0.0", List.of());
        writeBom(
            repository,
            "io.micronaut.platform",
            "micronaut-platform",
            "1.0.0",
            List.of(new ManagedDependency("io.micronaut", "micronaut-management", "1.2.3"))
        );

        Path project = tempDir.resolve("project-management-default");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), """
            [project]
            name = "install-test"
            version = "1.0.0"

            [tool.pyronaut]
            repositories = ["%s"]

            [tool.pyronaut.core]
            version = "1.0.0"

            [tool.pyronaut.platform]
            version = "1.0.0"

            [tool.pyronaut.dependencies]
            runtime = ["com.example:runtime-dep:1.0.0"]
            build = []
            test = []

            [tool.pyronaut.test-resources]
            enabled = false
            """.formatted(repository.toUri()));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        Path cacheDir = project.resolve("__pyronaut__");
        List<String> runtimeEntries = Files.readAllLines(cacheDir.resolve("resolved-runtime-dependencies"), StandardCharsets.UTF_8);
        List<String> developmentEntries = Files.readAllLines(cacheDir.resolve("resolved-development-runtime-dependencies"), StandardCharsets.UTF_8);
        assertTrue(runtimeEntries.stream().anyMatch(entry -> entry.contains("runtime-dep")));
        assertTrue(runtimeEntries.stream().noneMatch(entry -> entry.contains("micronaut-management")));
        assertTrue(developmentEntries.stream().anyMatch(entry -> entry.contains("runtime-dep")));
        assertTrue(developmentEntries.stream().anyMatch(entry -> entry.contains("micronaut-management")));
    }

    @Test
    void injectsMicronautTomlByDefaultWhenManagedByPlatform() throws Exception {
        Path repository = tempDir.resolve("repo-toml-default");
        writeArtifact(repository, "com.example", "runtime-dep", "1.0.0");
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");
        writeArtifact(repository, "io.micronaut.toml", "micronaut-toml", "1.2.3");
        writeBom(repository, "io.micronaut", "micronaut-core-bom", "1.0.0", List.of());
        writeBom(
            repository,
            "io.micronaut.platform",
            "micronaut-platform",
            "1.0.0",
            List.of(new ManagedDependency("io.micronaut.toml", "micronaut-toml", "1.2.3"))
        );

        Path project = tempDir.resolve("project-toml-default");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), """
            [project]
            name = "install-test"
            version = "1.0.0"

            [tool.pyronaut]
            repositories = ["%s"]

            [tool.pyronaut.core]
            version = "1.0.0"

            [tool.pyronaut.platform]
            version = "1.0.0"

            [tool.pyronaut.dependencies]
            runtime = ["com.example:runtime-dep:1.0.0"]
            build = ["com.example:build-dep:1.0.0"]
            test = ["com.example:test-dep:1.0.0"]

            [tool.pyronaut.test-resources]
            enabled = false
            """.formatted(repository.toUri()));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        Path cacheDir = project.resolve("__pyronaut__");
        List<String> runtimeEntries = Files.readAllLines(cacheDir.resolve("resolved-runtime-dependencies"), StandardCharsets.UTF_8);
        List<String> testEntries = Files.readAllLines(cacheDir.resolve("resolved-test-dependencies"), StandardCharsets.UTF_8);
        assertTrue(runtimeEntries.stream().anyMatch(entry -> entry.contains("runtime-dep")));
        assertTrue(runtimeEntries.stream().anyMatch(entry -> entry.contains("micronaut-toml")));
        assertTrue(testEntries.stream().anyMatch(entry -> entry.contains("test-dep")));
        assertTrue(testEntries.stream().anyMatch(entry -> entry.contains("micronaut-toml")));
    }

    @Test
    void resolvesVersionlessRuntimeDependencyManagedByPlatform() throws Exception {
        Path repository = tempDir.resolve("repo-platform-managed-runtime");
        writeArtifact(repository, "io.micrometer", "context-propagation", "1.2.1");
        writeBom(repository, "io.micronaut", "micronaut-core-bom", "1.0.0", List.of());
        writeBom(
            repository,
            "io.micronaut.platform",
            "micronaut-platform",
            "1.0.0",
            List.of(new ManagedDependency("io.micrometer", "context-propagation", "1.2.1"))
        );

        Path project = tempDir.resolve("project-platform-managed-runtime");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), """
            [project]
            name = "install-test"
            version = "1.0.0"

            [tool.pyronaut]
            repositories = ["%s"]

            [tool.pyronaut.core]
            version = "1.0.0"

            [tool.pyronaut.platform]
            version = "1.0.0"

            [tool.pyronaut.dependencies]
            runtime = ["io.micrometer:context-propagation"]
            build = []
            test = []

            [tool.pyronaut.test-resources]
            enabled = false
            """.formatted(repository.toUri()));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        Path cacheDir = project.resolve("__pyronaut__");
        List<String> runtimeEntries = Files.readAllLines(cacheDir.resolve("resolved-runtime-dependencies"), StandardCharsets.UTF_8);
        List<String> testEntries = Files.readAllLines(cacheDir.resolve("resolved-test-dependencies"), StandardCharsets.UTF_8);
        assertTrue(runtimeEntries.stream().anyMatch(entry -> entry.contains("context-propagation-1.2.1.jar")));
        assertTrue(testEntries.stream().anyMatch(entry -> entry.contains("context-propagation-1.2.1.jar")));
    }

    @Test
    void resolvesVersionlessPyronautModulesFromPyronautBom() throws Exception {
        Path repository = tempDir.resolve("repo-pyronaut-bom");
        writeArtifact(repository, "io.micronaut.pyronaut", "micronaut-pyronaut-logback", "0.0.99");
        writeBom(repository, "io.micronaut", "micronaut-core-bom", "1.0.0", List.of());
        writeBom(repository, "io.micronaut.platform", "micronaut-platform", "1.0.0", List.of());
        writeBom(
            repository,
            "io.micronaut.pyronaut",
            "micronaut-pyronaut-bom",
            "9.9.9",
            List.of(new ManagedDependency("io.micronaut.pyronaut", "micronaut-pyronaut-logback", "0.0.99"))
        );

        Path project = tempDir.resolve("project-pyronaut-bom");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), """
            [project]
            name = "install-test"
            version = "1.0.0"

            [tool.pyronaut]
            repositories = ["%s"]

            [tool.pyronaut.core]
            version = "1.0.0"

            [tool.pyronaut.platform]
            version = "1.0.0"

            [tool.pyronaut.dependencies]
            runtime = ["io.micronaut.pyronaut:micronaut-pyronaut-logback"]
            build = []
            test = []

            [tool.pyronaut.test-resources]
            enabled = false
            """.formatted(repository.toUri()));

        MavenClasspathResolver resolver = new MavenClasspathResolver(
            new ProxyConfigurationLoader(),
            System::getenv,
            () -> "9.9.9"
        );
        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), resolver);
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        List<String> runtimeEntries = Files.readAllLines(
            project.resolve("__pyronaut__").resolve("resolved-runtime-dependencies"),
            StandardCharsets.UTF_8
        );
        assertEquals(1, runtimeEntries.size());
        assertTrue(runtimeEntries.getFirst().contains("micronaut-pyronaut-logback"));
    }

    @Test
    void injectsTestResourcesClientOnlyForRunAndTestWhenConfiguredAndEnabled() throws Exception {
        Path repository = tempDir.resolve("repo-test-resources-enabled");
        writeArtifact(repository, "com.example", "runtime-dep", "1.0.0");
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");
        writeArtifact(repository, "io.micronaut.testresources", "micronaut-test-resources-client", "2.9.0");
        writeArtifact(repository, "io.micronaut.testresources", "micronaut-test-resources-server", "2.9.0");
        writeArtifact(repository, "io.micronaut.testresources", "micronaut-test-resources-testcontainers", "2.9.0");
        writeArtifact(repository, "io.micronaut.testresources", "micronaut-test-resources-control-panel", "2.9.0");

        Path project = tempDir.resolve("project-test-resources-enabled");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyprojectWithTestResources(repository, true));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        Path cacheDir = project.resolve("__pyronaut__");
        List<String> buildEntries = Files.readAllLines(cacheDir.resolve("resolved-build-dependencies"), StandardCharsets.UTF_8);
        List<String> runtimeEntries = Files.readAllLines(cacheDir.resolve("resolved-runtime-dependencies"), StandardCharsets.UTF_8);
        List<String> testEntries = Files.readAllLines(cacheDir.resolve("resolved-test-dependencies"), StandardCharsets.UTF_8);
        List<String> serverEntries = Files.readAllLines(cacheDir.resolve("resolved-test-resources-server-dependencies"), StandardCharsets.UTF_8);

        assertTrue(buildEntries.stream().noneMatch(entry -> entry.contains("micronaut-test-resources-client")));
        assertTrue(runtimeEntries.stream().anyMatch(entry -> entry.contains("runtime-dep")));
        assertTrue(runtimeEntries.stream().anyMatch(entry -> entry.contains("micronaut-test-resources-client")));
        assertTrue(testEntries.stream().anyMatch(entry -> entry.contains("runtime-dep")));
        assertTrue(testEntries.stream().anyMatch(entry -> entry.contains("test-dep")));
        assertTrue(testEntries.stream().anyMatch(entry -> entry.contains("micronaut-test-resources-client")));
        assertTrue(serverEntries.stream().anyMatch(entry -> entry.contains("micronaut-test-resources-server")));
        assertTrue(serverEntries.stream().anyMatch(entry -> entry.contains("micronaut-test-resources-testcontainers")));
    }

    @Test
    void testResourcesServerManifestIncludesInferredAndAdditionalModulesWithFiltering() throws Exception {
        Path repository = tempDir.resolve("repo-test-resources-server-manifest");
        writeArtifact(repository, "mysql", "mysql-connector-j", "8.3.0");
        writeArtifact(repository, "io.micronaut.testresources", "micronaut-test-resources-server", "2.9.0");
        writeArtifact(repository, "io.micronaut.testresources", "micronaut-test-resources-testcontainers", "2.9.0");
        writeArtifact(repository, "io.micronaut.testresources", "micronaut-test-resources-control-panel", "2.9.0");
        writeArtifact(repository, "io.micronaut.testresources", "micronaut-test-resources-jdbc-mysql", "2.9.0");
        writeArtifact(repository, "io.micronaut.testresources", "micronaut-test-resources-jdbc-postgresql", "2.9.0");

        Path project = tempDir.resolve("project-test-resources-server-manifest");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), """
            [project]
            name = "install-test"
            version = "1.0.0"

            [tool.pyronaut]
            repositories = ["%s"]

            [tool.pyronaut.dependencies]
            runtime = ["mysql:mysql-connector-j:8.3.0"]
            build = []
            test = []

            [tool.pyronaut.testResources]
            enabled = true
            version = "2.9.0"
            additionalModules = [
              "jdbc-postgresql",
              "io.micronaut.testresources:micronaut-test-resources-build-tools"
            ]
            """.formatted(repository.toUri()));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;
        command.scope = "test-resources-server";

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        List<String> serverEntries = Files.readAllLines(
            project.resolve("__pyronaut__").resolve("resolved-test-resources-server-dependencies"),
            StandardCharsets.UTF_8
        );
        assertTrue(serverEntries.stream().anyMatch(entry -> entry.contains("micronaut-test-resources-server")));
        assertTrue(serverEntries.stream().anyMatch(entry -> entry.contains("micronaut-test-resources-testcontainers")));
        assertTrue(serverEntries.stream().anyMatch(entry -> entry.contains("micronaut-test-resources-jdbc-mysql")));
        assertTrue(serverEntries.stream().anyMatch(entry -> entry.contains("micronaut-test-resources-jdbc-postgresql")));
        assertTrue(serverEntries.stream().anyMatch(entry -> entry.contains("mysql-connector-j")));
        assertTrue(serverEntries.stream().noneMatch(entry -> entry.contains("micronaut-test-resources-build-tools")));
    }

    @Test
    void doesNotInjectTestResourcesClientWhenConfiguredButDisabled() throws Exception {
        Path repository = tempDir.resolve("repo-test-resources-disabled");
        writeArtifact(repository, "com.example", "runtime-dep", "1.0.0");
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");
        writeArtifact(repository, "io.micronaut.testresources", "micronaut-test-resources-client", "2.9.0");

        Path project = tempDir.resolve("project-test-resources-disabled");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyprojectWithTestResources(repository, false));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        Path cacheDir = project.resolve("__pyronaut__");
        List<String> runtimeEntries = Files.readAllLines(cacheDir.resolve("resolved-runtime-dependencies"), StandardCharsets.UTF_8);
        List<String> testEntries = Files.readAllLines(cacheDir.resolve("resolved-test-dependencies"), StandardCharsets.UTF_8);
        assertTrue(runtimeEntries.stream().noneMatch(entry -> entry.contains("micronaut-test-resources-client")));
        assertTrue(testEntries.stream().noneMatch(entry -> entry.contains("micronaut-test-resources-client")));
    }

    @Test
    void envKillSwitchDisablesTestResourcesClientInjection() throws Exception {
        Path repository = tempDir.resolve("repo-test-resources-env-disabled");
        writeArtifact(repository, "com.example", "runtime-dep", "1.0.0");
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");
        writeArtifact(repository, "io.micronaut.testresources", "micronaut-test-resources-client", "2.9.0");

        Path project = tempDir.resolve("project-test-resources-env-disabled");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyprojectWithTestResources(repository, true));

        MavenClasspathResolver resolver = new MavenClasspathResolver(
            new ProxyConfigurationLoader(),
            name -> "PYRONAUT_TEST_RESOURCES_DISABLED".equals(name) ? "true" : null
        );
        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), resolver);
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        Path cacheDir = project.resolve("__pyronaut__");
        List<String> runtimeEntries = Files.readAllLines(cacheDir.resolve("resolved-runtime-dependencies"), StandardCharsets.UTF_8);
        List<String> testEntries = Files.readAllLines(cacheDir.resolve("resolved-test-dependencies"), StandardCharsets.UTF_8);
        assertTrue(runtimeEntries.stream().noneMatch(entry -> entry.contains("micronaut-test-resources-client")));
        assertTrue(testEntries.stream().noneMatch(entry -> entry.contains("micronaut-test-resources-client")));
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

        Path runtimeManifest = project.resolve("__pyronaut__").resolve("resolved-runtime-dependencies");
        long firstModified = Files.getLastModifiedTime(runtimeManifest).toMillis();
        Thread.sleep(25);

        PyronautInstallMain secondRun = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        secondRun.projectDir = project;
        assertEquals(InstallExitCode.SUCCESS.code(), secondRun.call());
        long secondModified = Files.getLastModifiedTime(runtimeManifest).toMillis();

        assertEquals(firstModified, secondModified);
    }

    @Test
    void installWritesPyprojectSchemaAndDirective() throws Exception {
        Path repository = tempDir.resolve("repo-editor-support");
        writeArtifact(repository, "com.example", "runtime-dep", "1.0.0");
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-editor-support");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        Path schemaFile = project.resolve("__pyronaut__")
            .resolve("schemas")
            .resolve(PyprojectJsonSchemaGenerator.SCHEMA_FILE_NAME);
        String pyproject = Files.readString(project.resolve("pyproject.toml"), StandardCharsets.UTF_8);

        assertTrue(Files.exists(schemaFile));
        assertTrue(Files.readString(schemaFile, StandardCharsets.UTF_8).contains("\"tool\""));
        assertTrue(pyproject.startsWith(PyprojectEditorSupport.managedDirectiveBlock()));
    }

    @Test
    void installWritesApplicationSchemaAndDirective() throws Exception {
        Path repository = tempDir.resolve("repo-app-schema");
        writeArtifactWithEntries(repository, "com.example", "runtime-dep", "1.0.0", Map.of(
            "META-INF/micronaut-configuration-schemas/example.HttpServerConfiguration.json", """
                {
                  "$schema": "https://json-schema.org/draft/2020-12/schema",
                  "$id": "urn:test:http-server",
                  "title": "HttpServerConfiguration",
                  "type": "object",
                  "x-micronaut": {
                    "prefix": "micronaut.server",
                    "kind": "configuration-properties"
                  },
                  "properties": {
                    "port": {
                      "type": "integer"
                    }
                  }
                }
                """,
            "META-INF/micronaut-configuration-schemas/example.NettyHttpServerConfiguration.json", """
                {
                  "$schema": "https://json-schema.org/draft/2020-12/schema",
                  "$id": "urn:test:netty-http-server",
                  "title": "NettyHttpServerConfiguration",
                  "type": "object",
                  "x-micronaut": {
                    "prefix": "micronaut.server.netty",
                    "kind": "configuration-properties"
                  },
                  "properties": {
                    "log-level": {
                      "type": "string"
                    }
                  }
                }
                """
        ));
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-app-schema");
        Files.createDirectories(project.resolve("config"));
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));
        Files.writeString(project.resolve("config/application.toml"), "micronaut.server.port = 8080\n");

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        Path schemaFile = project.resolve("__pyronaut__")
            .resolve("schemas")
            .resolve(MicronautApplicationJsonSchemaBundler.SCHEMA_FILE_NAME);
        Path stateFile = project.resolve("__pyronaut__")
            .resolve("schemas")
            .resolve(MicronautApplicationJsonSchemaBundler.STATE_FILE_NAME);
        String applicationToml = Files.readString(project.resolve("config/application.toml"), StandardCharsets.UTF_8);
        String schema = Files.readString(schemaFile, StandardCharsets.UTF_8);

        assertTrue(Files.exists(schemaFile));
        assertTrue(Files.exists(stateFile));
        assertTrue(applicationToml.startsWith(PyprojectEditorSupport.applicationManagedDirectiveBlock()));
        assertTrue(schema.contains("\"micronaut\""));
        assertTrue(schema.contains("\"server\""));
        assertTrue(schema.contains("\"port\""));
        assertTrue(schema.contains("\"netty\""));
        assertTrue(schema.contains("\"log-level\""));
    }

    @Test
    void installUsesConfiguredResourcesDirectoryForApplicationSchemaDirective() throws Exception {
        Path repository = tempDir.resolve("repo-custom-app-schema");
        writeArtifactWithEntries(repository, "com.example", "runtime-dep", "1.0.0", Map.of(
            "META-INF/micronaut-configuration-schemas/example.ApplicationConfiguration.json", """
                {
                  "$schema": "https://json-schema.org/draft/2020-12/schema",
                  "$id": "urn:test:application",
                  "title": "ApplicationConfiguration",
                  "type": "object",
                  "x-micronaut": {
                    "prefix": "micronaut.application",
                    "kind": "configuration-properties"
                  },
                  "properties": {
                    "name": {
                      "type": "string"
                    }
                  }
                }
                """
        ));
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-custom-app-schema");
        Files.createDirectories(project.resolve("app-config"));
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository) + """

            [tool.pyronaut.sources]
            resources = "app-config"
            """);
        Files.writeString(project.resolve("app-config/application.toml"), "micronaut.application.name = \"demo\"\n");

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());
        String applicationToml = Files.readString(project.resolve("app-config/application.toml"), StandardCharsets.UTF_8);
        assertTrue(applicationToml.startsWith("#:schema ../__pyronaut__/schemas/" + MicronautApplicationJsonSchemaBundler.SCHEMA_FILE_NAME));
    }

    @Test
    void installMergesApplicationEachPropertySchemasByPrefix() throws Exception {
        Path repository = tempDir.resolve("repo-app-schema-merge");
        writeArtifactWithEntries(repository, "com.example", "runtime-a", "1.0.0", Map.of(
            "META-INF/micronaut-configuration-schemas/example.DataJdbcConfiguration.json", """
                {
                  "$schema": "https://json-schema.org/draft/2020-12/schema",
                  "$id": "urn:test:data-jdbc",
                  "title": "DataJdbcConfiguration",
                  "type": "object",
                  "x-micronaut": {
                    "prefix": "datasources",
                    "kind": "each-property",
                    "container": "map"
                  },
                  "additionalProperties": {
                    "$ref": "#/$defs/Entry"
                  },
                  "$defs": {
                    "Entry": {
                      "type": "object",
                      "properties": {
                        "dialect": {
                          "type": "string"
                        }
                      }
                    }
                  }
                }
                """
        ));
        writeArtifactWithEntries(repository, "com.example", "runtime-b", "1.0.0", Map.of(
            "META-INF/micronaut-configuration-schemas/example.HikariConfiguration.json", """
                {
                  "$schema": "https://json-schema.org/draft/2020-12/schema",
                  "$id": "urn:test:hikari",
                  "title": "DatasourceConfiguration",
                  "type": "object",
                  "x-micronaut": {
                    "prefix": "datasources",
                    "kind": "each-property",
                    "container": "map"
                  },
                  "additionalProperties": {
                    "$ref": "#/$defs/Entry"
                  },
                  "$defs": {
                    "Entry": {
                      "type": "object",
                      "properties": {
                        "url": {
                          "type": "string"
                        }
                      }
                    }
                  }
                }
                """
        ));
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-app-schema-merge");
        Files.createDirectories(project.resolve("config"));
        Files.writeString(project.resolve("pyproject.toml"), """
            [project]
            name = "install-test"
            version = "1.0.0"

            [tool.pyronaut]
            repositories = ["%s"]

            [tool.pyronaut.core]
            version = ""

            [tool.pyronaut.platform]
            version = ""

            [tool.pyronaut.dependencies]
            runtime = ["com.example:runtime-a:1.0.0", "com.example:runtime-b:1.0.0"]
            build = ["com.example:build-dep:1.0.0"]
            test = ["com.example:test-dep:1.0.0"]

            [tool.pyronaut.test-resources]
            enabled = false
            """.formatted(repository.toUri()));
        Files.writeString(project.resolve("config/application.toml"), "[datasources.default]\n");

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        String schema = Files.readString(
            project.resolve("__pyronaut__").resolve("schemas").resolve(MicronautApplicationJsonSchemaBundler.SCHEMA_FILE_NAME),
            StandardCharsets.UTF_8
        );
        assertTrue(schema.contains("\"datasources\""));
        assertTrue(schema.contains("\"dialect\""));
        assertTrue(schema.contains("\"url\""));
    }

    @Test
    void installPreservesUserManagedTaploConfig() throws Exception {
        Path repository = tempDir.resolve("repo-user-taplo");
        writeArtifact(repository, "com.example", "runtime-dep", "1.0.0");
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-user-taplo");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));
        Files.writeString(project.resolve(PyprojectEditorSupport.TAPLO_FILE_NAME), "[[rule]]\ninclude = [\"*.toml\"]\n", StandardCharsets.UTF_8);

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());
        assertEquals("[[rule]]\ninclude = [\"*.toml\"]\n", Files.readString(project.resolve(PyprojectEditorSupport.TAPLO_FILE_NAME), StandardCharsets.UTF_8));
    }

    @Test
    void installRemovesGeneratedTaploConfig() throws Exception {
        Path repository = tempDir.resolve("repo-generated-taplo");
        writeArtifact(repository, "com.example", "runtime-dep", "1.0.0");
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-generated-taplo");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));
        Files.writeString(project.resolve(PyprojectEditorSupport.TAPLO_FILE_NAME),
            "# Generated by pyronaut-install for Pyronaut TOML schema support.\n[[rule]]\ninclude = [\"pyproject.toml\"]\n",
            StandardCharsets.UTF_8);

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());
        assertFalse(Files.exists(project.resolve(PyprojectEditorSupport.TAPLO_FILE_NAME)));
    }

    @Test
    void cacheHitRegeneratesMissingSchemaAndDirective() throws Exception {
        Path repository = tempDir.resolve("repo-cache-editor-support");
        writeArtifact(repository, "com.example", "runtime-dep", "1.0.0");
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-cache-editor-support");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain firstRun = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        firstRun.projectDir = project;
        assertEquals(InstallExitCode.SUCCESS.code(), firstRun.call());

        Path schemaFile = project.resolve("__pyronaut__")
            .resolve("schemas")
            .resolve(PyprojectJsonSchemaGenerator.SCHEMA_FILE_NAME);
        Files.deleteIfExists(schemaFile);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository), StandardCharsets.UTF_8);

        PyronautInstallMain secondRun = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        secondRun.projectDir = project;
        assertEquals(InstallExitCode.SUCCESS.code(), secondRun.call());

        assertTrue(Files.exists(schemaFile));
        assertTrue(Files.readString(project.resolve("pyproject.toml"), StandardCharsets.UTF_8)
            .startsWith(PyprojectEditorSupport.managedDirectiveBlock()));
    }

    @Test
    void cacheHitRegeneratesMissingApplicationSchemaAndDirective() throws Exception {
        Path repository = tempDir.resolve("repo-cache-app-editor-support");
        writeArtifactWithEntries(repository, "com.example", "runtime-dep", "1.0.0", Map.of(
            "META-INF/micronaut-configuration-schemas/example.ApplicationConfiguration.json", """
                {
                  "$schema": "https://json-schema.org/draft/2020-12/schema",
                  "$id": "urn:test:application",
                  "title": "ApplicationConfiguration",
                  "type": "object",
                  "x-micronaut": {
                    "prefix": "micronaut.application",
                    "kind": "configuration-properties"
                  },
                  "properties": {
                    "name": {
                      "type": "string"
                    }
                  }
                }
                """
        ));
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-cache-app-editor-support");
        Files.createDirectories(project.resolve("config"));
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));
        Files.writeString(project.resolve("config/application.toml"), "micronaut.application.name = \"demo\"\n");

        PyronautInstallMain firstRun = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        firstRun.projectDir = project;
        assertEquals(InstallExitCode.SUCCESS.code(), firstRun.call());

        Path schemaFile = project.resolve("__pyronaut__")
            .resolve("schemas")
            .resolve(MicronautApplicationJsonSchemaBundler.SCHEMA_FILE_NAME);
        Files.deleteIfExists(schemaFile);
        Files.writeString(project.resolve("config/application.toml"), "micronaut.application.name = \"demo\"\n", StandardCharsets.UTF_8);

        PyronautInstallMain secondRun = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        secondRun.projectDir = project;
        assertEquals(InstallExitCode.SUCCESS.code(), secondRun.call());

        assertTrue(Files.exists(schemaFile));
        assertTrue(Files.readString(project.resolve("config/application.toml"), StandardCharsets.UTF_8)
            .startsWith(PyprojectEditorSupport.applicationManagedDirectiveBlock()));
    }

    @Test
    void refreshForcesManifestRewrite() throws Exception {
        Path repository = tempDir.resolve("repo-refresh");
        writeArtifact(repository, "com.example", "runtime-dep", "1.0.0");
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-refresh");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain firstRun = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        firstRun.projectDir = project;
        assertEquals(InstallExitCode.SUCCESS.code(), firstRun.call());

        Path runtimeManifest = project.resolve("__pyronaut__").resolve("resolved-runtime-dependencies");
        long firstModified = Files.getLastModifiedTime(runtimeManifest).toMillis();
        Thread.sleep(25L);

        PyronautInstallMain refreshRun = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        refreshRun.projectDir = project;
        refreshRun.refresh = true;
        assertEquals(InstallExitCode.SUCCESS.code(), refreshRun.call());

        long refreshedModified = Files.getLastModifiedTime(runtimeManifest).toMillis();
        assertTrue(refreshedModified > firstModified);
    }

    @Test
    void refreshPreservesLocalRepositoryContents() throws Exception {
        Path repository = tempDir.resolve("repo-refresh-local-repo");
        writeArtifact(repository, "com.example", "runtime-dep", "1.0.0", new byte[]{1});
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-refresh-local-repo");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain firstRun = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        firstRun.projectDir = project;
        assertEquals(InstallExitCode.SUCCESS.code(), firstRun.call());

        Path cachedRuntimeJar = project.resolve("__pyronaut__")
            .resolve("m2-repository")
            .resolve("com/example/runtime-dep/1.0.0/runtime-dep-1.0.0.jar");
        assertTrue(Files.exists(cachedRuntimeJar));
        assertEquals((byte) 1, Files.readAllBytes(cachedRuntimeJar)[0]);

        Path sentinel = project.resolve("__pyronaut__").resolve("m2-repository").resolve("sentinel.txt");
        Files.writeString(sentinel, "keep");

        PyronautInstallMain refreshRun = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        refreshRun.projectDir = project;
        refreshRun.refresh = true;
        assertEquals(InstallExitCode.SUCCESS.code(), refreshRun.call());

        assertTrue(Files.exists(cachedRuntimeJar));
        assertTrue(Files.exists(sentinel));
        assertEquals((byte) 1, Files.readAllBytes(cachedRuntimeJar)[0]);
    }

    @Test
    void noCacheClearsLocalRepositoryAndRehydratesSameVersionArtifacts() throws Exception {
        Path repository = tempDir.resolve("repo-no-cache-local-repo");
        writeArtifact(repository, "com.example", "runtime-dep", "1.0.0", new byte[]{1});
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-no-cache-local-repo");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain firstRun = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        firstRun.projectDir = project;
        assertEquals(InstallExitCode.SUCCESS.code(), firstRun.call());

        Path cachedRuntimeJar = project.resolve("__pyronaut__")
            .resolve("m2-repository")
            .resolve("com/example/runtime-dep/1.0.0/runtime-dep-1.0.0.jar");
        assertTrue(Files.exists(cachedRuntimeJar));
        assertEquals((byte) 1, Files.readAllBytes(cachedRuntimeJar)[0]);

        Path sentinel = project.resolve("__pyronaut__").resolve("m2-repository").resolve("sentinel.txt");
        Files.writeString(sentinel, "delete");
        writeArtifact(repository, "com.example", "runtime-dep", "1.0.0", new byte[]{2});

        PyronautInstallMain noCacheRun = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        noCacheRun.projectDir = project;
        noCacheRun.noCache = true;
        assertEquals(InstallExitCode.SUCCESS.code(), noCacheRun.call());

        assertFalse(Files.exists(sentinel));
        assertEquals((byte) 2, Files.readAllBytes(cachedRuntimeJar)[0]);
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
        assertFalse(Files.exists(project.resolve("__pyronaut__").resolve("resolved-runtime-dependencies")));
    }

    @Test
    void malformedTomlReturnsConfigError() throws Exception {
        Path project = tempDir.resolve("project-malformed-toml");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), """
            [project
            name = "broken"
            """);

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.CONFIG_ERROR.code(), command.call());
        assertFalse(Files.exists(project.resolve("__pyronaut__").resolve("resolved-runtime-dependencies")));
    }

    @Test
    void invalidDependencyCoordinateReturnsConfigError() throws Exception {
        Path project = tempDir.resolve("project-invalid-coordinate");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), """
            [project]
            name = "invalid-coordinate"

            [tool.pyronaut]
            repositories = ["mavenLocal"]

            [tool.pyronaut.dependencies]
            runtime = ["invalid-coordinate"]
            build = []
            test = []
            """);

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.CONFIG_ERROR.code(), command.call());
        assertFalse(Files.exists(project.resolve("__pyronaut__").resolve("resolved-runtime-dependencies")));
    }

    @Test
    void mavenLocalAliasUsesConfiguredMavenRepoLocal() throws Exception {
        Path repository = tempDir.resolve("custom-m2-repository");
        writeArtifact(repository, "com.example", "runtime-dep", "1.0.0");

        Path project = tempDir.resolve("project-custom-m2");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), """
            [project]
            name = "custom-m2"

            [tool.pyronaut]
            repositories = ["mavenLocal"]

            [tool.pyronaut.core]
            version = ""

            [tool.pyronaut.platform]
            version = ""

            [tool.pyronaut.dependencies]
            runtime = ["com.example:runtime-dep:1.0.0"]
            build = []
            test = []

            [tool.pyronaut.test-resources]
            enabled = false
            """);

        String previousMavenRepoLocal = System.getProperty("maven.repo.local");
        try {
            System.setProperty("maven.repo.local", repository.toString());
            PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
            command.projectDir = project;

            assertEquals(InstallExitCode.SUCCESS.code(), command.call());

            Path cacheDir = project.resolve("__pyronaut__");
            List<String> runtimeEntries = Files.readAllLines(cacheDir.resolve("resolved-runtime-dependencies"), StandardCharsets.UTF_8);
            assertTrue(runtimeEntries.stream().anyMatch(entry -> entry.contains("runtime-dep")));
        } finally {
            restoreSystemProperty("maven.repo.local", previousMavenRepoLocal);
        }
    }

    @Test
    void resolvesVersionlessDependenciesUsingManagedBoms() throws Exception {
        Path repository = tempDir.resolve("repo-managed");
        writeBom(
            repository,
            "io.micronaut",
            "micronaut-core-bom",
            "5.0.0",
            List.of(
                new ManagedDependency("io.micronaut", "micronaut-context-python", "5.0.0"),
                new ManagedDependency("io.micronaut", "micronaut-inject-python", "5.0.0")
            )
        );
        writeBom(
            repository,
            "io.micronaut.platform",
            "micronaut-platform",
            "5.0.0",
            List.of(
                new ManagedDependency("io.micronaut.test", "micronaut-test-junit5", "5.0.0"),
                new ManagedDependency("org.junit.platform", "junit-platform-launcher", "1.12.2"),
                new ManagedDependency("org.junit.jupiter", "junit-jupiter-engine", "5.12.2"),
                new ManagedDependency("io.micronaut.testresources", "micronaut-test-resources-client", "2.9.0"),
                new ManagedDependency("io.micronaut.testresources", "micronaut-test-resources-server", "2.9.0")
            )
        );

        writeArtifact(repository, "io.micronaut", "micronaut-context-python", "5.0.0");
        writeArtifact(repository, "io.micronaut", "micronaut-inject-python", "5.0.0");
        writeArtifact(repository, "io.micronaut.test", "micronaut-test-junit5", "5.0.0");
        writeArtifact(repository, "org.junit.platform", "junit-platform-launcher", "1.12.2");
        writeArtifact(repository, "org.junit.jupiter", "junit-jupiter-engine", "5.12.2");
        writeArtifact(repository, "io.micronaut.testresources", "micronaut-test-resources-client", "2.9.0");
        writeArtifact(repository, "io.micronaut.testresources", "micronaut-test-resources-server", "2.9.0");
        writeArtifact(repository, "io.micronaut.testresources", "micronaut-test-resources-testcontainers", "2.9.0");
        writeArtifact(repository, "io.micronaut.testresources", "micronaut-test-resources-control-panel", "2.9.0");

        Path project = tempDir.resolve("project-managed");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), """
            [project]
            name = "managed-test"

            [tool.pyronaut]
            repositories = ["%s"]

            [tool.pyronaut.core]
            version = "5.0.0"

            [tool.pyronaut.platform]
            version = "5.0.0"

            [tool.pyronaut.dependencies]
            runtime = ["io.micronaut:micronaut-inject-python"]
            build = ["io.micronaut:micronaut-context-python"]
            test = ["io.micronaut.test:micronaut-test-junit5"]
            """.formatted(repository.toUri()));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        Path cacheDir = project.resolve("__pyronaut__");
        List<String> buildEntries = Files.readAllLines(cacheDir.resolve("resolved-build-dependencies"), StandardCharsets.UTF_8);
        List<String> runtimeEntries = Files.readAllLines(cacheDir.resolve("resolved-runtime-dependencies"), StandardCharsets.UTF_8);
        List<String> testEntries = Files.readAllLines(cacheDir.resolve("resolved-test-dependencies"), StandardCharsets.UTF_8);

        assertEquals(2, buildEntries.size());
        assertTrue(buildEntries.stream().anyMatch(entry -> entry.contains("micronaut-inject-python")));
        assertTrue(buildEntries.stream().anyMatch(entry -> entry.contains("micronaut-context-python")));

        assertEquals(3, runtimeEntries.size());
        assertTrue(runtimeEntries.stream().anyMatch(entry -> entry.contains("micronaut-context-python")));
        assertTrue(runtimeEntries.stream().anyMatch(entry -> entry.contains("micronaut-inject-python")));
        assertTrue(runtimeEntries.stream().anyMatch(entry -> entry.contains("micronaut-test-resources-client")));

        assertEquals(6, testEntries.size());
        assertTrue(testEntries.stream().anyMatch(entry -> entry.contains("micronaut-test-junit5")));
        assertTrue(testEntries.stream().anyMatch(entry -> entry.contains("micronaut-test-junit5") && entry.contains("5.0.0")));
        assertTrue(testEntries.stream().anyMatch(entry -> entry.contains("micronaut-inject-python")));
        assertTrue(testEntries.stream().anyMatch(entry -> entry.contains("micronaut-context-python")));
        assertTrue(testEntries.stream().anyMatch(entry -> entry.contains("junit-platform-launcher")));
        assertTrue(testEntries.stream().anyMatch(entry -> entry.contains("junit-jupiter-engine")));
        assertTrue(testEntries.stream().anyMatch(entry -> entry.contains("micronaut-test-resources-client")));
    }

    @Test
    void addsDefaultRuntimeBuildAndTestDependenciesWhenOmittedFromPyproject() throws Exception {
        Path repository = tempDir.resolve("repo-default-dependencies");
        writeBom(
            repository,
            "io.micronaut",
            "micronaut-core-bom",
            "5.0.0",
            List.of(
                new ManagedDependency("io.micronaut", "micronaut-context-python", "5.0.0"),
                new ManagedDependency("io.micronaut", "micronaut-inject-python", "5.0.0"),
                new ManagedDependency("io.micronaut.toml", "micronaut-toml", "5.0.0")
            )
        );
        writeBom(
            repository,
            "io.micronaut.platform",
            "micronaut-platform",
            "5.0.0",
            List.of(
                new ManagedDependency("org.junit.platform", "junit-platform-launcher", "1.12.2"),
                new ManagedDependency("org.junit.jupiter", "junit-jupiter-engine", "5.12.2")
            )
        );

        writeArtifact(repository, "io.micronaut", "micronaut-context-python", "5.0.0");
        writeArtifact(repository, "io.micronaut", "micronaut-inject-python", "5.0.0");
        writeArtifact(repository, "io.micronaut.toml", "micronaut-toml", "5.0.0");
        writeArtifact(repository, "org.junit.platform", "junit-platform-launcher", "1.12.2");
        writeArtifact(repository, "org.junit.jupiter", "junit-jupiter-engine", "5.12.2");

        Path project = tempDir.resolve("project-default-dependencies");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), """
            [project]
            name = "default-dependencies-test"

            [tool.pyronaut]
            repositories = ["%s"]

            [tool.pyronaut.core]
            version = "5.0.0"

            [tool.pyronaut.platform]
            version = "5.0.0"

            [tool.pyronaut.dependencies]
            runtime = []
            build = []
            test = []

            [tool.pyronaut.testResources]
            enabled = false
            """.formatted(repository.toUri()));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        Path cacheDir = project.resolve("__pyronaut__");
        List<String> buildEntries = Files.readAllLines(cacheDir.resolve("resolved-build-dependencies"), StandardCharsets.UTF_8);
        List<String> runtimeEntries = Files.readAllLines(cacheDir.resolve("resolved-runtime-dependencies"), StandardCharsets.UTF_8);
        List<String> testEntries = Files.readAllLines(cacheDir.resolve("resolved-test-dependencies"), StandardCharsets.UTF_8);

        assertEquals(2, buildEntries.size());
        assertTrue(buildEntries.stream().anyMatch(entry -> entry.contains("micronaut-inject-python")));
        assertTrue(buildEntries.stream().anyMatch(entry -> entry.contains("micronaut-context-python")));

        assertEquals(2, runtimeEntries.size());
        assertTrue(runtimeEntries.stream().anyMatch(entry -> entry.contains("micronaut-context-python")));
        assertTrue(runtimeEntries.stream().anyMatch(entry -> entry.contains("micronaut-toml")));

        assertEquals(4, testEntries.size());
        assertTrue(testEntries.stream().anyMatch(entry -> entry.contains("micronaut-context-python")));
        assertTrue(testEntries.stream().anyMatch(entry -> entry.contains("junit-platform-launcher")));
        assertTrue(testEntries.stream().anyMatch(entry -> entry.contains("junit-jupiter-engine")));
        assertTrue(testEntries.stream().anyMatch(entry -> entry.contains("micronaut-toml")));
    }

    @Test
    void usesPyronautSnapshotVersionForPlatformManagedDependencies() throws Exception {
        Path repository = tempDir.resolve("repo-managed-snapshot");
        writeBom(
            repository,
            "io.micronaut",
            "micronaut-core-bom",
            "5.0.0-SNAPSHOT",
            List.of(
                new ManagedDependency("io.micronaut", "micronaut-inject-python", "5.0.0-SNAPSHOT"),
                new ManagedDependency("io.micronaut", "micronaut-context-python", "5.0.0-SNAPSHOT")
            )
        );
        writeBom(
            repository,
            "io.micronaut.platform",
            "micronaut-platform",
            "5.0.0-SNAPSHOT",
            List.of(
                new ManagedDependency("io.micronaut.test", "micronaut-test-junit5", "5.0.0-SNAPSHOT"),
                new ManagedDependency("org.junit.platform", "junit-platform-launcher", "1.12.2"),
                new ManagedDependency("org.junit.jupiter", "junit-jupiter-engine", "5.12.2")
            )
        );

        writeArtifact(repository, "io.micronaut", "micronaut-inject-python", "5.0.0-SNAPSHOT");
        writeArtifact(repository, "io.micronaut", "micronaut-context-python", "5.0.0-SNAPSHOT");
        writeArtifact(repository, "io.micronaut.test", "micronaut-test-junit5", "5.0.0-SNAPSHOT");
        writeArtifact(repository, "org.junit.platform", "junit-platform-launcher", "1.12.2");
        writeArtifact(repository, "org.junit.jupiter", "junit-jupiter-engine", "5.12.2");
        writeArtifact(repository, "io.micronaut.testresources", "micronaut-test-resources-client", "2.9.0");
        writeArtifact(repository, "io.micronaut.testresources", "micronaut-test-resources-server", "2.9.0");
        writeArtifact(repository, "io.micronaut.testresources", "micronaut-test-resources-testcontainers", "2.9.0");
        writeArtifact(repository, "io.micronaut.testresources", "micronaut-test-resources-control-panel", "2.9.0");

        Path project = tempDir.resolve("project-managed-snapshot");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), """
            [project]
            name = "managed-snapshot-test"

            [tool.pyronaut]
            repositories = ["%s"]

            [tool.pyronaut.core]
            version = "5.0.0-SNAPSHOT"

            [tool.pyronaut.platform]
            version = "5.0.0-SNAPSHOT"

            [tool.pyronaut.dependencies]
            runtime = ["io.micronaut:micronaut-inject-python"]
            build = []
            test = ["io.micronaut.test:micronaut-test-junit5"]

            [tool.pyronaut.test-resources]
            enabled = false
            """.formatted(repository.toUri()));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        Path cacheDir = project.resolve("__pyronaut__");
        List<String> runtimeEntries = Files.readAllLines(cacheDir.resolve("resolved-runtime-dependencies"), StandardCharsets.UTF_8);
        List<String> testEntries = Files.readAllLines(cacheDir.resolve("resolved-test-dependencies"), StandardCharsets.UTF_8);

        assertTrue(runtimeEntries.stream().anyMatch(entry -> entry.contains("micronaut-inject-python") && entry.contains("5.0.0-SNAPSHOT")));
        assertTrue(runtimeEntries.stream().anyMatch(entry -> entry.contains("micronaut-context-python") && entry.contains("5.0.0-SNAPSHOT")));
        assertTrue(testEntries.stream().anyMatch(entry -> entry.contains("micronaut-test-junit5") && entry.contains("5.0.0-SNAPSHOT")));
    }

    @Test
    void usesCoreVersionForMicronautArtifactsAndPlatformVersionForEcosystem() throws Exception {
        Path repository = tempDir.resolve("repo-split-core-platform");
        writeBom(
            repository,
            "io.micronaut",
            "micronaut-core-bom",
            "5.0.0-SNAPSHOT",
            List.of(
                new ManagedDependency("io.micronaut", "micronaut-inject-python", "5.0.0-SNAPSHOT"),
                new ManagedDependency("io.micronaut", "micronaut-context-python", "5.0.0-SNAPSHOT")
            )
        );
        writeBom(
            repository,
            "io.micronaut.platform",
            "micronaut-platform",
            "5.0.0-RC1",
            List.of(
                new ManagedDependency("io.micronaut", "micronaut-context-python", "5.0.0-RC2"),
                new ManagedDependency("io.micronaut.test", "micronaut-test-junit5", "5.0.0-RC1"),
                new ManagedDependency("io.micronaut.testresources", "micronaut-test-resources-client", "4.0.0-RC1"),
                new ManagedDependency("io.micronaut.testresources", "micronaut-test-resources-server", "4.0.0-RC1"),
                new ManagedDependency("io.micronaut.toml", "micronaut-toml", "3.0.0-RC1"),
                new ManagedDependency("org.junit.platform", "junit-platform-launcher", "1.12.2"),
                new ManagedDependency("org.junit.jupiter", "junit-jupiter-engine", "5.12.2")
            )
        );

        writeArtifact(repository, "io.micronaut", "micronaut-inject-python", "5.0.0-SNAPSHOT");
        writeArtifact(repository, "io.micronaut", "micronaut-context-python", "5.0.0-SNAPSHOT");
        writeArtifact(repository, "io.micronaut", "micronaut-context-python", "5.0.0-RC2");
        writeArtifact(repository, "io.micronaut.test", "micronaut-test-junit5", "5.0.0-RC1");
        writeArtifact(repository, "io.micronaut.testresources", "micronaut-test-resources-client", "2.9.0");
        writeArtifact(repository, "io.micronaut.testresources", "micronaut-test-resources-client", "4.0.0-RC1");
        writeArtifact(repository, "io.micronaut.testresources", "micronaut-test-resources-server", "4.0.0-RC1");
        writeArtifact(repository, "io.micronaut.testresources", "micronaut-test-resources-testcontainers", "4.0.0-RC1");
        writeArtifact(repository, "io.micronaut.testresources", "micronaut-test-resources-control-panel", "4.0.0-RC1");
        writeArtifact(repository, "io.micronaut.toml", "micronaut-toml", "3.0.0-RC1");
        writeArtifact(repository, "org.junit.platform", "junit-platform-launcher", "1.12.2");
        writeArtifact(repository, "org.junit.jupiter", "junit-jupiter-engine", "5.12.2");

        Path project = tempDir.resolve("project-split-core-platform");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), """
            [project]
            name = "split-core-platform-test"

            [tool.pyronaut]
            repositories = ["%s"]

            [tool.pyronaut.core]
            version = "5.0.0-SNAPSHOT"

            [tool.pyronaut.platform]
            version = "5.0.0-RC1"

            [tool.pyronaut.test-resources]
            enabled = true

            [tool.pyronaut.dependencies]
            runtime = ["io.micronaut:micronaut-inject-python"]
            build = []
            test = ["io.micronaut.test:micronaut-test-junit5"]
            """.formatted(repository.toUri()));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        Path cacheDir = project.resolve("__pyronaut__");
        List<String> runtimeEntries = Files.readAllLines(cacheDir.resolve("resolved-runtime-dependencies"), StandardCharsets.UTF_8);
        List<String> testEntries = Files.readAllLines(cacheDir.resolve("resolved-test-dependencies"), StandardCharsets.UTF_8);
        List<String> serverEntries = Files.readAllLines(cacheDir.resolve("resolved-test-resources-server-dependencies"), StandardCharsets.UTF_8);

        assertTrue(runtimeEntries.stream().anyMatch(entry -> entry.contains("micronaut-inject-python") && entry.contains("5.0.0-SNAPSHOT")));
        assertTrue(runtimeEntries.stream().anyMatch(entry -> entry.contains("micronaut-context-python") && entry.contains("5.0.0-SNAPSHOT")));
        assertFalse(runtimeEntries.stream().anyMatch(entry -> entry.contains("micronaut-context-python") && entry.contains("5.0.0-RC2")));
        assertTrue(runtimeEntries.stream().anyMatch(entry -> entry.contains("micronaut-toml") && entry.contains("3.0.0-RC1")));
        assertTrue(runtimeEntries.stream().anyMatch(entry -> entry.contains("micronaut-test-resources-client") && entry.contains("4.0.0-RC1")));
        assertFalse(runtimeEntries.stream().anyMatch(entry -> entry.contains("micronaut-test-resources-client") && entry.contains("2.9.0")));
        assertTrue(testEntries.stream().anyMatch(entry -> entry.contains("micronaut-test-junit5") && entry.contains("5.0.0-RC1")));
        assertTrue(testEntries.stream().anyMatch(entry -> entry.contains("micronaut-test-resources-client") && entry.contains("4.0.0-RC1")));
        assertTrue(serverEntries.stream().anyMatch(entry -> entry.contains("micronaut-test-resources-server") && entry.contains("4.0.0-RC1")));
    }

    @Test
    void prefersPlatformManagedTestResourcesVersionOverBuildToolsFallback() throws Exception {
        Path repository = tempDir.resolve("repo-managed-test-resources-version");
        writeBom(
            repository,
            "io.micronaut",
            "micronaut-core-bom",
            "5.0.0",
            List.of(
                new ManagedDependency("io.micronaut", "micronaut-inject-python", "5.0.0")
            )
        );
        writeBom(
            repository,
            "io.micronaut.platform",
            "micronaut-platform",
            "5.0.0",
            List.of(
                new ManagedDependency("io.micronaut.testresources", "micronaut-test-resources-client", "4.0.0-M1")
            )
        );

        writeArtifact(repository, "io.micronaut", "micronaut-inject-python", "5.0.0");
        writeArtifact(repository, "io.micronaut.testresources", "micronaut-test-resources-client", "2.9.0");
        writeArtifact(repository, "io.micronaut.testresources", "micronaut-test-resources-client", "4.0.0-M1");
        writeArtifact(repository, "io.micronaut.testresources", "micronaut-test-resources-server", "4.0.0-M1");
        writeArtifact(repository, "io.micronaut.testresources", "micronaut-test-resources-testcontainers", "4.0.0-M1");
        writeArtifact(repository, "io.micronaut.testresources", "micronaut-test-resources-control-panel", "4.0.0-M1");

        Path project = tempDir.resolve("project-managed-test-resources-version");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), """
            [project]
            name = "managed-test-resources-version"

            [tool.pyronaut]
            repositories = ["%s"]

            [tool.pyronaut.core]
            version = "5.0.0"

            [tool.pyronaut.platform]
            version = "5.0.0"

            [tool.pyronaut.dependencies]
            runtime = ["io.micronaut:micronaut-inject-python"]
            build = []
            test = []
            """.formatted(repository.toUri()));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        Path cacheDir = project.resolve("__pyronaut__");
        List<String> runtimeEntries = Files.readAllLines(cacheDir.resolve("resolved-runtime-dependencies"), StandardCharsets.UTF_8);
        List<String> testEntries = Files.readAllLines(cacheDir.resolve("resolved-test-dependencies"), StandardCharsets.UTF_8);

        assertTrue(runtimeEntries.stream().anyMatch(entry -> entry.contains("micronaut-test-resources-client") && entry.contains("4.0.0-M1")));
        assertFalse(runtimeEntries.stream().anyMatch(entry -> entry.contains("micronaut-test-resources-client") && entry.contains("2.9.0")));
        assertTrue(testEntries.stream().anyMatch(entry -> entry.contains("micronaut-test-resources-client") && entry.contains("4.0.0-M1")));
        assertFalse(testEntries.stream().anyMatch(entry -> entry.contains("micronaut-test-resources-client") && entry.contains("2.9.0")));
    }

    @Test
    void dependenciesModeDefaultsToRuntimeScope() throws Exception {
        Path repository = tempDir.resolve("repo-runtime-default");
        writeArtifact(repository, "com.example", "runtime-dep", "1.0.0");

        Path project = tempDir.resolve("project-runtime-default");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;
        command.dependencies = true;
        command.progress = "off";
        command.color = "never";

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
        try {
            assertEquals(InstallExitCode.SUCCESS.code(), command.call());
        } finally {
            System.setOut(originalOut);
        }

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Dependency tree (runtime):"));
        assertFalse(output.contains("Dependency tree (build):"));
        assertFalse(output.contains("Dependency tree (test):"));

        Path cacheDir = project.resolve("__pyronaut__");
        assertFalse(Files.exists(cacheDir.resolve("resolved-build-dependencies")));
        assertFalse(Files.exists(cacheDir.resolve("resolved-runtime-dependencies")));
        assertFalse(Files.exists(cacheDir.resolve("resolved-test-dependencies")));
        assertFalse(Files.exists(cacheDir.resolve("resolved-test-resources-server-dependencies")));
    }

    @Test
    void scopeAllResolvesAllScopes() throws Exception {
        Path repository = tempDir.resolve("repo-all-scope");
        writeArtifact(repository, "com.example", "runtime-dep", "1.0.0");
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-all-scope");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;
        command.scope = "all";

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        Path cacheDir = project.resolve("__pyronaut__");
        assertTrue(Files.exists(cacheDir.resolve("resolved-build-dependencies")));
        assertTrue(Files.exists(cacheDir.resolve("resolved-runtime-dependencies")));
        assertTrue(Files.exists(cacheDir.resolve("resolved-test-dependencies")));
        assertTrue(Files.exists(cacheDir.resolve("resolved-test-resources-server-dependencies")));
    }

    @Test
    void invalidScopeReturnsConfigError() {
        Path project = tempDir.resolve("project-invalid-scope");

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;
        command.scope = "invalid";

        assertEquals(InstallExitCode.CONFIG_ERROR.code(), command.call());
    }

    @Test
    void invalidProgressModeReturnsConfigError() {
        Path project = tempDir.resolve("project-invalid-progress");

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;
        command.progress = "invalid";

        assertEquals(InstallExitCode.CONFIG_ERROR.code(), command.call());
    }

    @Test
    void invalidColorModeReturnsConfigError() {
        Path project = tempDir.resolve("project-invalid-color");

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;
        command.color = "invalid";

        assertEquals(InstallExitCode.CONFIG_ERROR.code(), command.call());
    }

    @Test
    void dependenciesModePrintsTreeWithoutWritingManifests() throws Exception {
        Path repository = tempDir.resolve("repo-dependencies-tree");
        writeArtifact(repository, "com.example", "runtime-dep", "1.0.0");

        Path project = tempDir.resolve("project-dependencies-tree");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;
        command.dependencies = true;
        command.progress = "off";
        command.color = "never";

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
        try {
            assertEquals(InstallExitCode.SUCCESS.code(), command.call());
        } finally {
            System.setOut(originalOut);
        }

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Dependency tree (runtime):"));
        assertTrue(output.contains("com.example:runtime-dep:1.0.0"));

        Path cacheDir = project.resolve("__pyronaut__");
        assertFalse(Files.exists(cacheDir.resolve("resolved-build-dependencies")));
        assertFalse(Files.exists(cacheDir.resolve("resolved-runtime-dependencies")));
        assertFalse(Files.exists(cacheDir.resolve("resolved-test-dependencies")));
        assertFalse(Files.exists(cacheDir.resolve("resolved-test-resources-server-dependencies")));
    }

    @Test
    void dependenciesModePrintsResolutionErrorAndReturnsResolutionCode() throws Exception {
        Path repository = tempDir.resolve("repo-tree-failure");
        writeArtifactWithDependencies(
            repository,
            "com.example",
            "runtime-root",
            "1.0.0",
            List.of(new DependencyCoordinate("com.example", "missing-child", "1.0.0"))
        );

        Path project = tempDir.resolve("project-tree-failure");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), """
            [project]
            name = "broken-tree"

            [tool.pyronaut]
            repositories = ["%s"]

            [tool.pyronaut.dependencies]
            runtime = ["com.example:runtime-root:1.0.0"]
            build = []
            test = []
            """.formatted(repository.toUri()));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;
        command.dependencies = true;
        command.progress = "off";
        command.color = "never";

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
        try {
            assertEquals(InstallExitCode.RESOLUTION_ERROR.code(), command.call());
        } finally {
            System.setOut(originalOut);
        }

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Dependency tree (runtime):"));
        assertTrue(output.contains("ERROR: Dependency resolution failed for scope 'runtime'"));
        assertTrue(output.contains("com.example:missing-child:1.0.0 [runtime] [ERROR]"));
        assertTrue(output.contains("ERROR path: com.example:runtime-root:1.0.0 -> com.example:missing-child:1.0.0"));
    }

    @Test
    void resolutionErrorIncludesProxySourceSummaryWhenProxyConfigured() throws Exception {
        Path project = tempDir.resolve("project-proxy-failure");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), """
            [project]
            name = "broken-proxy-tree"

            [tool.pyronaut]
            repositories = ["%s"]

            [tool.pyronaut.dependencies]
            runtime = ["com.example:missing:1.0.0"]
            build = []
            test = []
            """.formatted(project.resolve("empty-repo").toUri()));

        ProxyConfigurationLoader proxyConfigurationLoader = new ProxyConfigurationLoader(
            Map.of("HTTPS_PROXY", "http://proxy.example:3128"),
            tempDir.resolve("missing-pyronaut-settings.toml"),
            tempDir.resolve("missing-m2-settings.xml")
        );
        MavenClasspathResolver resolver = new MavenClasspathResolver(proxyConfigurationLoader);
        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), resolver);
        command.projectDir = project;
        command.dependencies = true;
        command.progress = "off";
        command.color = "never";

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
        try {
            assertEquals(InstallExitCode.RESOLUTION_ERROR.code(), command.call());
        } finally {
            System.setOut(originalOut);
        }

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("proxy environment -> http://proxy.example:3128"));
    }

    @Test
    void installSucceedsWithProxyConfigurationPresent() throws Exception {
        Path repository = tempDir.resolve("repo-proxy-success");
        writeArtifact(repository, "com.example", "runtime-dep", "1.0.0");
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-proxy-success");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        ProxyConfigurationLoader proxyConfigurationLoader = new ProxyConfigurationLoader(
            Map.of("HTTPS_PROXY", "http://proxy.example:3128"),
            tempDir.resolve("missing-pyronaut-settings.toml"),
            tempDir.resolve("missing-m2-settings.xml")
        );
        MavenClasspathResolver resolver = new MavenClasspathResolver(proxyConfigurationLoader);
        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), resolver);
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());
        Path cacheDir = project.resolve("__pyronaut__");
        assertTrue(Files.exists(cacheDir.resolve("resolved-build-dependencies")));
        assertTrue(Files.exists(cacheDir.resolve("resolved-runtime-dependencies")));
        assertTrue(Files.exists(cacheDir.resolve("resolved-test-dependencies")));
        assertTrue(Files.exists(cacheDir.resolve("resolved-test-resources-server-dependencies")));
    }

    @Test
    void filtersChecksumStackTraceButKeepsWarningLine() {
        boolean[] suppressChecksumTrace = new boolean[1];

        String warning = ChecksumWarningFilter.filterLine(
            "[main] WARN org.eclipse.aether.internal.impl.WarnChecksumPolicy - Could not validate integrity of download from file:///tmp/repo/example.pom",
            suppressChecksumTrace
        );
        String exception = ChecksumWarningFilter.filterLine(
            "org.eclipse.aether.transfer.ChecksumFailureException: Checksum validation failed, no checksums available",
            suppressChecksumTrace
        );
        String stackFrame = ChecksumWarningFilter.filterLine(
            "\tat org.eclipse.aether.internal.impl.AbstractChecksumPolicy.onNoMoreChecksums(AbstractChecksumPolicy.java:63)",
            suppressChecksumTrace
        );
        String unrelatedWarning = ChecksumWarningFilter.filterLine(
            "[main] WARN org.eclipse.aether.internal.impl.DefaultArtifactResolver - Continuing resolution",
            suppressChecksumTrace
        );

        assertTrue(suppressChecksumTrace[0] == false);
        assertTrue(warning.contains("Could not validate integrity of download"));
        assertEquals(null, exception);
        assertEquals(null, stackFrame);
        assertTrue(unrelatedWarning.contains("Continuing resolution"));
    }

    @Test
    void installGeneratesPythonIdeStubsAndVsCodeSettings() throws Exception {
        Path repository = tempDir.resolve("repo-python-ide-stubs");
        writeCompiledArtifact(repository, "com.example", "runtime-dep", "1.0.0", Map.of(
            "io.micronaut.http.HttpResponse", """
                package io.micronaut.http;

                /**
                 * Represents an HTTP response exposed to Python code.
                 */
                public class HttpResponse {
                    public static HttpResponse ok(Object body) {
                        return new HttpResponse();
                    }

                    public int code() {
                        return 200;
                    }
                }
                """,
            "io.micronaut.http.HttpRequest", """
                package io.micronaut.http;

                public class HttpRequest {
                }
                """,
            "io.micronaut.http.client.HttpClient", """
                package io.micronaut.http.client;

                import io.micronaut.http.HttpRequest;
                import io.micronaut.http.HttpResponse;

                public class HttpClient {
                    /**
                     * Exchanges a request for a response.
                     */
                    public HttpResponse exchange(HttpRequest request) {
                        return new HttpResponse();
                    }
                }
                """,
            "io.micronaut.http.client.HttpClientBuilder", """
                package io.micronaut.http.client;

                import io.micronaut.http.HttpRequest;

                public class HttpClientBuilder {
                    /**
                     * Creates an empty builder.
                     */
                    public HttpClientBuilder() {
                    }

                    /**
                     * Creates a builder preloaded with a request.
                     */
                    public HttpClientBuilder(HttpRequest request) {
                    }

                    /**
                     * Builds a client without a preset request.
                     */
                    public HttpClient create() {
                        return new HttpClient();
                    }

                    /**
                     * Builds a client for the given request.
                     */
                    public HttpClient create(HttpRequest request) {
                        return new HttpClient();
                    }
                }
                """,
            "io.micronaut.http.annotation.Get", """
                package io.micronaut.http.annotation;

                /**
                 * Handles HTTP GET requests.
                 */
                public @interface Get {
                    String value() default "";
                    String[] produces() default {};
                }
                """,
            "io.micronaut.http.annotation.Post", """
                package io.micronaut.http.annotation;

                public @interface Post {
                    String value() default "";
                    String[] consumes() default {};
                }
                """,
            "io.micronaut.http.annotation.Body", """
                package io.micronaut.http.annotation;

                public @interface Body {
                }
                """
        ));
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeCompiledArtifact(repository, "com.example", "test-dep", "1.0.0", Map.of(
            "jakarta.inject.Inject", """
                package jakarta.inject;

                /**
                 * Injects a dependency from the Micronaut context.
                 */
                public @interface Inject {
                }
                """,
            "jakarta.inject.Singleton", """
                package jakarta.inject;

                public @interface Singleton {
                }
                """
        ));

        Path project = tempDir.resolve("project-python-ide-stubs");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        Path stubsRoot = project.resolve("__pyronaut__").resolve("ide-stubs");
        String editorManifest = Files.readString(project.resolve("__pyronaut__").resolve("resolved-editor-artifacts.json"), StandardCharsets.UTF_8);
        String annotationStub = Files.readString(stubsRoot.resolve("micronaut/http/annotation/__init__.pyi"), StandardCharsets.UTF_8);
        String httpStub = Files.readString(stubsRoot.resolve("micronaut/http/__init__.pyi"), StandardCharsets.UTF_8);
        String httpClientStub = Files.readString(stubsRoot.resolve("micronaut/http/client/__init__.pyi"), StandardCharsets.UTF_8);
        String injectStub = Files.readString(stubsRoot.resolve("jakarta/inject/__init__.pyi"), StandardCharsets.UTF_8);
        String settings = Files.readString(project.resolve(".vscode/settings.json"), StandardCharsets.UTF_8);

        assertTrue(editorManifest.contains("\"scope\":\"runtime\""));
        assertTrue(editorManifest.contains("\"sourceJar\""));
        assertTrue(annotationStub.contains("@overload\ndef Get(target: _T, /) -> _T: ..."));
        assertTrue(annotationStub.contains("@overload\ndef Get(value: str = ..., *, produces: str | list[str] = ...) -> Callable[[_T], _T]: ..."));
        assertTrue(annotationStub.contains("def Get(*args: Any, **kwargs: Any) -> Callable[[_T], _T] | _T:\n    \"\"\""));
        assertTrue(annotationStub.contains("    Handles HTTP GET requests."));
        assertTrue(annotationStub.contains("@overload\ndef Post(value: str = ..., *, consumes: str | list[str] = ...) -> Callable[[_T], _T]: ..."));
        assertTrue(annotationStub.contains("def Post(*args: Any, **kwargs: Any) -> Callable[[_T], _T] | _T:"));
        assertTrue(annotationStub.contains("def Body(*args: Any, **kwargs: Any) -> Callable[[_T], _T] | _T:"));
        assertTrue(httpStub.contains("class HttpResponse:"));
        assertTrue(httpStub.contains("Represents an HTTP response exposed to Python code"));
        assertTrue(httpStub.contains("def ok(body: Any) -> HttpResponse: ..."));
        assertTrue(httpClientStub.contains("from micronaut.http import HttpRequest, HttpResponse"));
        assertTrue(httpClientStub.contains("        Exchanges a request for a response."));
        assertTrue(httpClientStub.contains("def exchange(self, request: HttpRequest) -> HttpResponse:\n        \"\"\""));
        assertTrue(httpClientStub.contains("class HttpClientBuilder:"));
        assertTrue(httpClientStub.contains("@overload\n    def __init__(self) -> None: ..."));
        assertTrue(httpClientStub.contains("def __init__(self, *args: Any, **kwargs: Any) -> None:\n        \"\"\""));
        assertTrue(httpClientStub.contains("        Creates an empty builder."));
        assertTrue(httpClientStub.contains("@overload\n    def __init__(self, request: HttpRequest) -> None: ..."));
        assertTrue(httpClientStub.contains("@overload\n    def create(self) -> HttpClient: ..."));
        assertTrue(httpClientStub.contains("def create(self, *args: Any, **kwargs: Any) -> HttpClient:\n        \"\"\""));
        assertTrue(httpClientStub.contains("        Builds a client without a preset request."));
        assertTrue(httpClientStub.contains("@overload\n    def create(self, request: HttpRequest) -> HttpClient: ..."));
        assertTrue(injectStub.contains("@overload\ndef Inject(target: _T, /) -> _T: ..."));
        assertTrue(injectStub.contains("@overload\ndef Singleton() -> Callable[[_T], _T]: ..."));
        assertTrue(injectStub.contains("@overload\ndef Singleton(target: _T, /) -> _T: ..."));
        assertTrue(injectStub.contains("def Inject(*args: Any, **kwargs: Any) -> Callable[[_T], _T] | _T:\n    \"\"\""));
        assertTrue(injectStub.contains("    Injects a dependency from the Micronaut context."));
        assertTrue(settings.contains("__pyronaut__/ide-stubs"));
    }

    @Test
    void installCopiesPythonIdeStubsFromSharedCacheForEquivalentArtifacts() throws Exception {
        Path repository = tempDir.resolve("repo-python-ide-shared-cache");
        writeCompiledArtifact(repository, "com.example", "runtime-dep", "1.0.0", Map.of(
            "io.micronaut.http.HttpResponse", """
                package io.micronaut.http;

                public class HttpResponse {
                    public static HttpResponse ok() {
                        return new HttpResponse();
                    }
                }
                """
        ));
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path firstProject = tempDir.resolve("project-python-ide-shared-cache-a");
        Path secondProject = tempDir.resolve("project-python-ide-shared-cache-b");
        Files.createDirectories(firstProject);
        Files.createDirectories(secondProject);
        Files.writeString(firstProject.resolve("pyproject.toml"), pyproject(repository));
        Files.writeString(secondProject.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain firstCommand = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        firstCommand.projectDir = firstProject;
        assertEquals(InstallExitCode.SUCCESS.code(), firstCommand.call());

        PyronautInstallMain secondCommand = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        secondCommand.projectDir = secondProject;
        assertEquals(InstallExitCode.SUCCESS.code(), secondCommand.call());

        Path firstState = firstProject.resolve("__pyronaut__/ide-stubs").resolve(PythonIdeStubGenerator.STATE_FILE_NAME);
        Path secondState = secondProject.resolve("__pyronaut__/ide-stubs").resolve(PythonIdeStubGenerator.STATE_FILE_NAME);
        assertEquals(Files.readString(firstState), Files.readString(secondState));
        assertTrue(Files.exists(secondProject.resolve("__pyronaut__/ide-stubs/micronaut/http/__init__.pyi")));
        try (Stream<Path> cacheFiles = Files.walk(tempDir.resolve("shared-ide-stubs-cache"))) {
            assertTrue(cacheFiles.anyMatch(path -> path.getFileName().toString().equals(".generated")));
        }
    }

    @Test
    void installGeneratesPyronautTestStubsFromPackagedPythonVfs() throws Exception {
        Path repository = tempDir.resolve("repo-python-ide-pyronaut-test-vfs");
        writeArtifact(repository, "com.example", "runtime-dep", "1.0.0");
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifactWithEntries(repository, "io.micronaut.pyronaut", "micronaut-pyronaut-pytest", "1.0.0", Map.of(
            "META-INF/GRAALPY-VFS/micronaut-application/src/pyronaut/__init__.py", "",
            "META-INF/GRAALPY-VFS/micronaut-application/src/pyronaut/test/__init__.py", "from .test import MicronautTest, micronaut_test_fixture\n",
            "META-INF/GRAALPY-VFS/micronaut-application/src/pyronaut/test/test.py", "class MicronautTest:\n    pass\n"
        ));

        Path project = tempDir.resolve("project-python-ide-pyronaut-test-vfs");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), """
            [project]
            name = "install-test"
            version = "1.0.0"

            [tool.pyronaut]
            repositories = ["%s"]

            [tool.pyronaut.core]
            version = ""

            [tool.pyronaut.platform]
            version = ""

            [tool.pyronaut.dependencies]
            runtime = ["com.example:runtime-dep:1.0.0"]
            build = ["com.example:build-dep:1.0.0"]
            test = ["io.micronaut.pyronaut:micronaut-pyronaut-pytest:1.0.0"]

            [tool.pyronaut.test-resources]
            enabled = false
            """.formatted(repository.toUri()));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());
        List<String> testEntries = Files.readAllLines(
            project.resolve("__pyronaut__/resolved-test-dependencies"),
            StandardCharsets.UTF_8
        );
        assertTrue(testEntries.stream().noneMatch(entry -> entry.contains("micronaut-pyronaut-pytest")));
        String pyronautTestStub = Files.readString(
            project.resolve("__pyronaut__/ide-stubs").resolve("pyronaut/test/__init__.pyi"),
            StandardCharsets.UTF_8
        );
        assertTrue(pyronautTestStub.contains("class MicronautTest:"));
        assertTrue(pyronautTestStub.contains("from micronaut.context import ApplicationContext"));
        assertTrue(pyronautTestStub.contains("class ApplicationContextWrapper(ApplicationContext):"));
        assertTrue(pyronautTestStub.contains("def micronaut_test_fixture(request: Any, micronaut_test: MicronautTest | None = ...) -> ApplicationContextWrapper: ..."));
    }

    @Test
    void installCopiesPackagedPythonVfsSourcesIntoIdeStubs() throws Exception {
        Path repository = tempDir.resolve("repo-python-ide-vfs-sources");
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifactWithEntries(repository, "io.micronaut.pyronaut", "micronaut-pyronaut-logback", "1.0.0", Map.of(
            "META-INF/GRAALPY-VFS/micronaut-application/src/logback/__init__.py", "from .config import dictConfig\n",
            "META-INF/GRAALPY-VFS/micronaut-application/src/logback/config.py", "def dictConfig(config):\n    return config\n"
        ));

        Path project = tempDir.resolve("project-python-ide-vfs-sources");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), """
            [project]
            name = "install-test"
            version = "1.0.0"

            [tool.pyronaut]
            repositories = ["%s"]

            [tool.pyronaut.core]
            version = ""

            [tool.pyronaut.platform]
            version = ""

            [tool.pyronaut.dependencies]
            runtime = ["io.micronaut.pyronaut:micronaut-pyronaut-logback:1.0.0"]
            build = ["com.example:build-dep:1.0.0"]
            test = []

            [tool.pyronaut.test-resources]
            enabled = false
            """.formatted(repository.toUri()));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());
        Path stubsRoot = project.resolve("__pyronaut__/ide-stubs");
        String logbackInit = Files.readString(stubsRoot.resolve("logback/__init__.py"), StandardCharsets.UTF_8);
        String logbackConfig = Files.readString(stubsRoot.resolve("logback/config.py"), StandardCharsets.UTF_8);
        assertTrue(logbackInit.contains("from .config import dictConfig"));
        assertTrue(logbackConfig.contains("def dictConfig(config):"));
    }

    @Test
    void vscodeSettingsIncludePythonInterpreterAndSitePackagesWhenKnown() throws Exception {
        Path repository = tempDir.resolve("repo-python-ide-vscode-python");
        writeCompiledArtifact(repository, "com.example", "runtime-dep", "1.0.0", Map.of(
            "io.micronaut.http.HttpResponse", """
                package io.micronaut.http;

                public class HttpResponse {
                }
                """
        ));
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");
        Path pythonExecutable = tempDir.resolve("venv/bin/python");
        Path sitePackages = tempDir.resolve("venv/lib/python/site-packages");
        Files.createDirectories(pythonExecutable.getParent());
        Files.createDirectories(sitePackages);
        Files.writeString(pythonExecutable, "#!/usr/bin/env python\n", StandardCharsets.UTF_8);
        String previousExecutable = System.getProperty("pyronaut.python.executable");
        String previousSitePackages = System.getProperty("pyronaut.python.site-packages");
        try {
            System.setProperty("pyronaut.python.executable", pythonExecutable.toString());
            System.setProperty("pyronaut.python.site-packages", sitePackages.toString());

            Path project = tempDir.resolve("project-python-ide-vscode-python");
            Files.createDirectories(project);
            Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

            PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
            command.projectDir = project;

            assertEquals(InstallExitCode.SUCCESS.code(), command.call());
            String settings = Files.readString(project.resolve(".vscode/settings.json"), StandardCharsets.UTF_8);
            assertTrue(settings.contains("\"python.defaultInterpreterPath\":\"" + pythonExecutable.toAbsolutePath().normalize()));
            assertTrue(settings.contains(sitePackages.toAbsolutePath().normalize().toString()));
        } finally {
            restoreSystemProperty("pyronaut.python.executable", previousExecutable);
            restoreSystemProperty("pyronaut.python.site-packages", previousSitePackages);
        }
    }

    @Test
    void charSequenceEnumsRetainEnumTypeInMethodParameters() throws Exception {
        Path repository = tempDir.resolve("repo-python-ide-charsequence-enum");
        writeCompiledArtifact(repository, "com.example", "runtime-dep", "1.0.0", Map.of(
            "io.micronaut.http.HttpStatus", """
                package io.micronaut.http;

                public enum HttpStatus implements CharSequence {
                    OK,
                    NOT_FOUND;

                    @Override
                    public int length() {
                        return name().length();
                    }

                    @Override
                    public char charAt(int index) {
                        return name().charAt(index);
                    }

                    @Override
                    public CharSequence subSequence(int start, int end) {
                        return name().subSequence(start, end);
                    }
                }
                """,
            "io.micronaut.http.HttpResponse", """
                package io.micronaut.http;

                public class HttpResponse {
                    public static HttpResponse status(HttpStatus status) {
                        return new HttpResponse();
                    }
                }
                """
        ));
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-python-ide-charsequence-enum");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());
        String httpStub = Files.readString(
            project.resolve("__pyronaut__/ide-stubs").resolve("micronaut/http/__init__.pyi"),
            StandardCharsets.UTF_8
        );
        assertTrue(httpStub.contains("class HttpStatus(Enum):"));
        assertTrue(httpStub.contains("def status(status: HttpStatus) -> HttpResponse: ..."));
        assertFalse(httpStub.contains("def status(status: str) -> HttpResponse: ..."));
    }

    @Test
    void annotationStubsRenderValidKeywordAndAnnotationMemberTypes() throws Exception {
        Path repository = tempDir.resolve("repo-python-ide-annotation-signatures");
        writeCompiledArtifact(repository, "com.example", "runtime-dep", "1.0.0", Map.of(
            "io.micronaut.http.annotation.Header", """
                package io.micronaut.http.annotation;

                public @interface Header {
                    String value() default "";
                    String name() default "";
                    String defaultValue() default "";
                }
                """,
            "io.micronaut.http.annotation.Headers", """
                package io.micronaut.http.annotation;

                public @interface Headers {
                    Header[] value() default {};
                }
                """,
            "io.micronaut.http.annotation.Error", """
                package io.micronaut.http.annotation;

                public @interface Error {
                    Class<?> value() default Object.class;
                    Class<?> exception() default Throwable.class;
                    boolean global() default false;
                    String status() default "";
                }
                """
        ));
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-python-ide-annotation-signatures");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        String annotationStub = Files.readString(
            project.resolve("__pyronaut__/ide-stubs").resolve("micronaut/http/annotation/__init__.pyi"),
            StandardCharsets.UTF_8
        );

        assertTrue(annotationStub.contains("@overload\ndef Header(value: str = ..., *, defaultValue: str = ..., name: str = ...) -> Callable[[_T], _T]: ..."));
        assertTrue(annotationStub.contains("@overload\ndef Headers(value: Callable[..., Any] | list[Callable[..., Any]] = ...) -> Callable[[_T], _T]: ..."));
        assertTrue(annotationStub.contains("@overload\ndef Error(value: type[Any] = ..., *, exception: type[Any] = ..., global_: bool = ..., status: str = ...) -> Callable[[_T], _T]: ..."));
        assertFalse(annotationStub.contains("type[Any][Any]"));
        assertFalse(annotationStub.contains(" global: "));
    }

    @Test
    void installSkipsVsCodeSettingsWhenPyrightConfigExists() throws Exception {
        Path repository = tempDir.resolve("repo-python-ide-pyright");
        writeCompiledArtifact(repository, "com.example", "runtime-dep", "1.0.0", Map.of(
            "io.micronaut.http.annotation.Get", """
                package io.micronaut.http.annotation;

                public @interface Get {
                }
                """
        ));
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-python-ide-pyright");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));
        Files.writeString(project.resolve("pyrightconfig.json"), "{\"extraPaths\":[\"src\"]}", StandardCharsets.UTF_8);

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());
        assertFalse(Files.exists(project.resolve(".vscode/settings.json")));
        assertTrue(Files.exists(project.resolve("__pyronaut__/ide-stubs").resolve("micronaut/http/annotation/__init__.pyi")));
    }

    @Test
    void installGeneratesPythonIdeStubsAndPyCharmModule() throws Exception {
        Path repository = tempDir.resolve("repo-python-ide-pycharm");
        writeCompiledArtifact(repository, "com.example", "runtime-dep", "1.0.0", Map.of(
            "io.micronaut.http.annotation.Get", """
                package io.micronaut.http.annotation;

                public @interface Get {
                }
                """
        ));
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeCompiledArtifact(repository, "com.example", "test-dep", "1.0.0", Map.of(
            "jakarta.inject.Inject", """
                package jakarta.inject;

                public @interface Inject {
                }
                """
        ));

        Path project = tempDir.resolve("project-python-ide-pycharm");
        Files.createDirectories(project.resolve(".idea"));
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository) + """

            [tool.pyronaut.ide-stubs]
            ide = "pycharm"
            destination-dir = "__pyronaut__/custom-stubs"
            """);
        Files.writeString(project.resolve(".idea/modules.xml"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <project version="4">
              <component name="ProjectModuleManager">
                <modules>
                  <module fileurl="file://$PROJECT_DIR$/.idea/existing.iml" filepath="$PROJECT_DIR$/.idea/existing.iml" />
                </modules>
              </component>
            </project>
            """, StandardCharsets.UTF_8);

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        assertTrue(Files.exists(project.resolve("__pyronaut__/custom-stubs").resolve("micronaut/http/annotation/__init__.pyi")));
        assertFalse(Files.exists(project.resolve(".vscode/settings.json")));

        String modulesXml = Files.readString(project.resolve(".idea/modules.xml"), StandardCharsets.UTF_8);
        String generatedModule = Files.readString(
            project.resolve(".idea").resolve(PyCharmSettingsWriter.GENERATED_MODULE_FILE),
            StandardCharsets.UTF_8
        );
        assertTrue(modulesXml.contains("existing.iml"));
        assertTrue(modulesXml.contains(PyCharmSettingsWriter.GENERATED_MODULE_FILE));
        assertTrue(generatedModule.contains("type=\"PYTHON_MODULE\""));
        assertTrue(generatedModule.contains("file://$MODULE_DIR$/../__pyronaut__/custom-stubs"));
        assertTrue(generatedModule.contains("file://$MODULE_DIR$/.."));
    }

    @Test
    void installHonorsConfiguredIdeStubDestinationAndPackages() throws Exception {
        Path repository = tempDir.resolve("repo-python-ide-configured");
        writeCompiledArtifact(repository, "com.example", "runtime-dep", "1.0.0", Map.of(
            "io.micronaut.http.annotation.Get", """
                package io.micronaut.http.annotation;

                public @interface Get {
                }
                """,
            "io.micronaut.context.BeanContext", """
                package io.micronaut.context;

                public class BeanContext {
                }
                """
        ));
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeCompiledArtifact(repository, "com.example", "test-dep", "1.0.0", Map.of(
            "jakarta.inject.Inject", """
                package jakarta.inject;

                public @interface Inject {
                }
                """
        ));

        Path project = tempDir.resolve("project-python-ide-configured");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository) + """

            [tool.pyronaut.ide-stubs]
            packages = ["io.micronaut.http.annotation"]
            destination-dir = "__pyronaut__/custom-stubs"
            """);

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());
        assertTrue(Files.exists(project.resolve("__pyronaut__/custom-stubs").resolve("micronaut/http/annotation/__init__.pyi")));
        assertFalse(Files.exists(project.resolve("__pyronaut__/custom-stubs").resolve("micronaut/context/__init__.pyi")));
        String settings = Files.readString(project.resolve(".vscode/settings.json"), StandardCharsets.UTF_8);
        assertTrue(settings.contains("__pyronaut__/custom-stubs"));
    }

    @Test
    void sourceMetadataSuppliesParameterNamesWhenBytecodeOmitsThem() throws Exception {
        Path repository = tempDir.resolve("repo-python-ide-source-params");
        writeCompiledArtifactWithoutParameterMetadata(repository, "com.example", "runtime-dep", "1.0.0", Map.of(
            "io.micronaut.http.HttpRequest", """
                package io.micronaut.http;

                public class HttpRequest {
                }
                """,
            "io.micronaut.http.client.ParameterNameClient", """
                package io.micronaut.http.client;

                import io.micronaut.http.HttpRequest;

                public class ParameterNameClient {
                    public ParameterNameClient(HttpRequest requestTemplate) {
                    }

                    public String send(HttpRequest requestTemplate, String routeName) {
                        return routeName;
                    }
                }
                """
        ));
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-python-ide-source-params");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        String httpClientStub = Files.readString(
            project.resolve("__pyronaut__/ide-stubs").resolve("micronaut/http/client/__init__.pyi"),
            StandardCharsets.UTF_8
        );
        assertTrue(httpClientStub.contains("def __init__(self, requestTemplate: HttpRequest) -> None: ..."));
        assertTrue(httpClientStub.contains("def send(self, requestTemplate: HttpRequest, routeName: str) -> str: ..."));
        assertFalse(httpClientStub.contains("arg0"));
        assertFalse(httpClientStub.contains("arg1"));
    }

    @Test
    void sourceMetadataDisambiguatesSameArityOverloads() throws Exception {
        Path repository = tempDir.resolve("repo-python-ide-overload-signatures");
        writeCompiledArtifactWithoutParameterMetadata(repository, "com.example", "runtime-dep", "1.0.0", Map.of(
            "io.micronaut.http.HttpRequest", """
                package io.micronaut.http;

                public class HttpRequest {
                }
                """,
            "io.micronaut.http.client.OverloadDocClient", """
                package io.micronaut.http.client;

                import io.micronaut.http.HttpRequest;

                public class OverloadDocClient {
                    /**
                     * Sends the template request.
                     */
                    public String send(HttpRequest requestTemplate) {
                        return "request";
                    }

                    /**
                     * Sends the route by name.
                     */
                    public String send(String routeName) {
                        return routeName;
                    }
                }
                """
        ));
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-python-ide-overload-signatures");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        String httpClientStub = Files.readString(
            project.resolve("__pyronaut__/ide-stubs").resolve("micronaut/http/client/__init__.pyi"),
            StandardCharsets.UTF_8
        );
        assertTrue(httpClientStub.contains("        Sends the template request."));
        assertTrue(httpClientStub.contains("@overload\n    def send(self, requestTemplate: HttpRequest) -> str: ..."));
        assertTrue(httpClientStub.contains("@overload\n    def send(self, routeName: str) -> str: ..."));
        assertTrue(httpClientStub.contains("def send(self, *args: Any, **kwargs: Any) -> str:\n        \"\"\""));
    }

    @Test
    void overloadedStaticMethodsRetainVisibleImplementationDocstrings() throws Exception {
        Path repository = tempDir.resolve("repo-python-ide-overloaded-static");
        writeCompiledArtifact(repository, "com.example", "runtime-dep", "1.0.0", Map.of(
            "io.micronaut.http.HttpResponse", """
                package io.micronaut.http;

                public class HttpResponse {
                    /**
                     * Creates a response without a body.
                     */
                    public static HttpResponse ok() {
                        return new HttpResponse();
                    }

                    /**
                     * Creates a response with a body.
                     */
                    public static HttpResponse ok(Object body) {
                        return new HttpResponse();
                    }
                }
                """
        ));
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-python-ide-overloaded-static");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());
        String httpStub = Files.readString(
            project.resolve("__pyronaut__/ide-stubs").resolve("micronaut/http/__init__.pyi"),
            StandardCharsets.UTF_8
        );
        assertTrue(httpStub.contains("@overload\n    @staticmethod\n    def ok() -> HttpResponse: ..."));
        assertTrue(httpStub.contains("@overload\n    @staticmethod\n    def ok(body: Any) -> HttpResponse: ..."));
        assertTrue(httpStub.contains("@staticmethod\n    def ok(*args: Any, **kwargs: Any) -> HttpResponse:\n        \"\"\""));
        assertTrue(httpStub.contains("        Overloads for `ok`:"));
        assertTrue(httpStub.contains("        Creates a response without a body."));
        assertTrue(httpStub.contains("        Creates a response with a body."));
    }

    @Test
    void realisticHttpResponseInterfaceRetainsClassAndStaticMethodDocstrings() throws Exception {
        Path repository = tempDir.resolve("repo-python-ide-http-response-interface");
        writeCompiledArtifact(repository, "com.example", "runtime-dep", "1.0.0", Map.of(
            "io.micronaut.http.HttpMessage", """
                package io.micronaut.http;

                import java.util.Optional;

                public interface HttpMessage<B> {
                    /**
                     * @return The request body
                     */
                    default Optional<B> getBody() {
                        return Optional.empty();
                    }
                }
                """,
            "io.micronaut.http.MutableHttpResponse", """
                package io.micronaut.http;

                public interface MutableHttpResponse<B> extends HttpResponse<B> {
                }
                """,
            "io.micronaut.http.HttpResponseFactory", """
                package io.micronaut.http;

                public interface HttpResponseFactory {
                    HttpResponseFactory INSTANCE = null;

                    <T> MutableHttpResponse<T> ok();

                    <T> MutableHttpResponse<T> ok(T body);
                }
                """,
            "io.micronaut.http.HttpResponse", """
                package io.micronaut.http;

                import org.jspecify.annotations.Nullable;

                import java.util.Optional;

                /**
                 * <p>Common interface for HTTP response implementations.</p>
                 *
                 * @param <B> The Http body type
                 */
                public interface HttpResponse<B> extends HttpMessage<B> {
                    /**
                     * @return The response status code
                     */
                    int code();

                    /**
                     * @return The HTTP status reason phrase
                     */
                    String reason();

                    /**
                     * Return an {@link io.micronaut.http.HttpStatus#OK} response with an empty body.
                     *
                     * @param <T> The response type
                     * @return The ok response
                     */
                    static <T> MutableHttpResponse<T> ok() {
                        return HttpResponseFactory.INSTANCE.ok();
                    }

                    /**
                     * Return an {@link io.micronaut.http.HttpStatus#OK} response with a body.
                     *
                     * @param body The response body
                     * @param <T>  The body type
                     * @return The ok response
                     */
                    static <T> MutableHttpResponse<T> ok(T body) {
                        return HttpResponseFactory.INSTANCE.ok(body);
                    }
                }
                """
        ));
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-python-ide-http-response-interface");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());
        String httpStub = Files.readString(
            project.resolve("__pyronaut__/ide-stubs").resolve("micronaut/http/__init__.pyi"),
            StandardCharsets.UTF_8
        );
        assertTrue(httpStub.contains("class HttpResponse(Protocol[_HttpResponse_B], HttpMessage[_HttpResponse_B]):\n    \"\"\""));
        assertTrue(httpStub.contains("Common interface for HTTP response implementations."));
        assertTrue(httpStub.contains("@staticmethod\n    def ok(*args: Any, **kwargs: Any) -> MutableHttpResponse[_HttpResponse_ok_T]:\n        \"\"\""));
        assertTrue(httpStub.contains("Overloads for `ok`:"));
        assertTrue(httpStub.contains("Return an `HttpStatus.OK` response with an empty body."));
        assertTrue(httpStub.contains("Return an `HttpStatus.OK` response with a body."));
    }

    @Test
    void sourceMetadataIncludesPublicFieldsAndConstants() throws Exception {
        Path repository = tempDir.resolve("repo-python-ide-fields");
        writeCompiledArtifact(repository, "com.example", "runtime-dep", "1.0.0", Map.of(
            "io.micronaut.http.HttpStatus", """
                package io.micronaut.http;

                public class HttpStatus {
                    /**
                     * Successful response status.
                     */
                    public static final int OK = 200;

                    /**
                     * Human readable reason.
                     */
                    public String reason;
                }
                """
        ));
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-python-ide-fields");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        String httpStub = Files.readString(
            project.resolve("__pyronaut__/ide-stubs").resolve("micronaut/http/__init__.pyi"),
            StandardCharsets.UTF_8
        );
        assertTrue(httpStub.contains("# Successful response status."));
        assertTrue(httpStub.contains("OK: ClassVar[int]"));
        assertTrue(httpStub.contains("# Human readable reason."));
        assertTrue(httpStub.contains("reason: str"));
    }

    @Test
    void installSkipsMicronautInternalTypesWhenAnnotated() throws Exception {
        Path repository = tempDir.resolve("repo-python-ide-internal");
        writeCompiledArtifact(repository, "com.example", "runtime-dep", "1.0.0", Map.of(
            "io.micronaut.core.annotation.Internal", """
                package io.micronaut.core.annotation;

                public @interface Internal {
                }
                """,
            "io.micronaut.http.PublicThing", """
                package io.micronaut.http;

                public class PublicThing {
                    public String value() {
                        return "ok";
                    }
                }
                """,
            "io.micronaut.http.InternalThing", """
                package io.micronaut.http;

                import io.micronaut.core.annotation.Internal;

                @Internal
                public class InternalThing {
                    public String hidden() {
                        return "hidden";
                    }
                }
                """
        ));
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-python-ide-internal");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        String httpStub = Files.readString(
            project.resolve("__pyronaut__/ide-stubs").resolve("micronaut/http/__init__.pyi"),
            StandardCharsets.UTF_8
        );
        assertTrue(httpStub.contains("class PublicThing:"));
        assertFalse(httpStub.contains("class InternalThing:"));
    }

    @Test
    void genericsUseUsefulBoundsInsteadOfFallingBackToAny() throws Exception {
        Path repository = tempDir.resolve("repo-python-ide-generics");
        writeCompiledArtifact(repository, "com.example", "runtime-dep", "1.0.0", Map.of(
            "io.micronaut.http.HttpRequest", """
                package io.micronaut.http;

                public class HttpRequest {
                }
                """,
            "io.micronaut.http.client.GenericClient", """
                package io.micronaut.http.client;

                import io.micronaut.http.HttpRequest;
                import java.util.List;

                public class GenericClient {
                    public <T extends HttpRequest> List<T> echoAll(List<? extends T> requests) {
                        return java.util.List.of();
                    }

                    public <T extends HttpRequest> T[] copyArray(T[] requests) {
                        return requests;
                    }
                }
                """
        ));
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-python-ide-generics");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        String clientStub = Files.readString(
            project.resolve("__pyronaut__/ide-stubs").resolve("micronaut/http/client/__init__.pyi"),
            StandardCharsets.UTF_8
        );
        assertTrue(clientStub.contains("_GenericClient_echoAll_T = TypeVar(\"_GenericClient_echoAll_T\", bound=HttpRequest)"));
        assertTrue(clientStub.contains("_GenericClient_copyArray_T = TypeVar(\"_GenericClient_copyArray_T\", bound=HttpRequest)"));
        assertTrue(clientStub.contains("def echoAll(self, requests: list[_GenericClient_echoAll_T]) -> list[_GenericClient_echoAll_T]: ..."));
        assertTrue(clientStub.contains("def copyArray(self, requests: list[_GenericClient_copyArray_T]) -> list[_GenericClient_copyArray_T]: ..."));
        assertFalse(clientStub.contains("list[Any]"));
    }

    @Test
    void classGenericsArePreservedWithGenericBase() throws Exception {
        Path repository = tempDir.resolve("repo-python-ide-class-generics");
        writeCompiledArtifact(repository, "com.example", "runtime-dep", "1.0.0", Map.of(
            "io.micronaut.http.HttpRequest", """
                package io.micronaut.http;

                public class HttpRequest {
                }
                """,
            "io.micronaut.http.client.GenericHolder", """
                package io.micronaut.http.client;

                import io.micronaut.http.HttpRequest;
                import java.util.List;

                public class GenericHolder<T extends HttpRequest> {
                    public T current;

                    public GenericHolder(T current) {
                        this.current = current;
                    }

                    public List<T> all() {
                        return java.util.List.of();
                    }
                }
                """
        ));
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-python-ide-class-generics");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        String clientStub = Files.readString(
            project.resolve("__pyronaut__/ide-stubs").resolve("micronaut/http/client/__init__.pyi"),
            StandardCharsets.UTF_8
        );
        assertTrue(clientStub.contains("_GenericHolder_T = TypeVar(\"_GenericHolder_T\", bound=HttpRequest)"));
        assertTrue(clientStub.contains("class GenericHolder(Generic[_GenericHolder_T]):"));
        assertTrue(clientStub.contains("current: _GenericHolder_T"));
        assertTrue(clientStub.contains("def __init__(self, current: _GenericHolder_T) -> None: ..."));
        assertTrue(clientStub.contains("def all(self) -> list[_GenericHolder_T]: ..."));
    }

    @Test
    void selfReferentialGenericBoundsDoNotOverflow() throws Exception {
        Path repository = tempDir.resolve("repo-python-ide-lifecycle");
        writeCompiledArtifact(repository, "com.example", "runtime-dep", "1.0.0", Map.of(
            "io.micronaut.context.LifeCycle", """
                package io.micronaut.context;

                import java.io.Closeable;

                public interface LifeCycle<T extends LifeCycle<T>> extends Closeable, AutoCloseable {
                    boolean isRunning();

                    default T start() {
                        return (T) this;
                    }

                    default T stop() {
                        return (T) this;
                    }

                    @Override
                    default void close() {
                        stop();
                    }
                }
                """
        ));
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-python-ide-lifecycle");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        String contextStub = Files.readString(
            project.resolve("__pyronaut__/ide-stubs").resolve("micronaut/context/__init__.pyi"),
            StandardCharsets.UTF_8
        );
        String warningReport = Files.readString(
            project.resolve("__pyronaut__/reports/editor-stubs/stub-generation-report.txt"),
            StandardCharsets.UTF_8
        );
        assertFalse(warningReport.contains("StackOverflowError"));
        assertTrue(contextStub.contains("_LifeCycle_T = TypeVar(\"_LifeCycle_T\""));
        assertTrue(contextStub.contains("class LifeCycle("));
        assertTrue(contextStub.contains("def start(self) -> _LifeCycle_T:"));
        assertTrue(contextStub.contains("def stop(self) -> _LifeCycle_T:"));
    }

    @Test
    void annotationsWithNestedEnumMembersRenderWithoutSkippingTheType() throws Exception {
        Path repository = tempDir.resolve("repo-python-ide-nested-annotation");
        writeCompiledArtifact(repository, "com.example", "runtime-dep", "1.0.0", Map.of(
            "io.micronaut.test.annotation.Sql", """
                package io.micronaut.test.annotation;

                import java.lang.annotation.Documented;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Inherited;
                import java.lang.annotation.Repeatable;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Target({ElementType.TYPE})
                @Retention(RetentionPolicy.RUNTIME)
                @Documented
                @Inherited
                @Repeatable(Sql.Sqls.class)
                public @interface Sql {
                    String[] value() default {};

                    Phase phase() default Phase.BEFORE_ALL;

                    @Target({ElementType.TYPE})
                    @Retention(RetentionPolicy.RUNTIME)
                    @Documented
                    @Inherited
                    @interface Sqls {
                        Sql[] value();
                    }

                    enum Phase {
                        BEFORE_ALL,
                        AFTER_ALL
                    }
                }
                """
        ));
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-python-ide-nested-annotation");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        String annotationStub = Files.readString(
            project.resolve("__pyronaut__/ide-stubs").resolve("micronaut/test/annotation/__init__.pyi"),
            StandardCharsets.UTF_8
        );
        assertTrue(annotationStub.contains("@overload\ndef Sql(target: _T, /) -> _T: ..."));
        assertTrue(annotationStub.contains("def Sql(*args: Any, **kwargs: Any) -> Callable[[_T], _T] | _T:"));
    }

    @Test
    void defaultExcludePatternsSkipModuleInfoTypesWithoutWarnings() throws Exception {
        Path repository = tempDir.resolve("repo-python-ide-module-info");
        writeCompiledArtifact(repository, "com.example", "runtime-dep", "1.0.0", Map.of(
            "io.micronaut.data.info.MicronautDataModelModuleInfo", """
                package io.micronaut.data.info;

                public class MicronautDataModelModuleInfo {
                }
                """,
            "io.micronaut.http.HttpResponse", """
                package io.micronaut.http;

                public class HttpResponse {
                }
                """
        ));
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-python-ide-module-info");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        String warningReport = Files.readString(
            project.resolve("__pyronaut__/reports/editor-stubs/stub-generation-report.txt"),
            StandardCharsets.UTF_8
        );
        String httpStub = Files.readString(
            project.resolve("__pyronaut__/ide-stubs").resolve("micronaut/http/__init__.pyi"),
            StandardCharsets.UTF_8
        );
        assertTrue(httpStub.contains("class HttpResponse:"));
        assertFalse(Files.exists(project.resolve("__pyronaut__/ide-stubs").resolve("micronaut/data/info/__init__.pyi")));
        assertFalse(warningReport.contains("MicronautDataModelModuleInfo"));
    }

    @Test
    void configuredExcludePatternsSkipMatchingTypes() throws Exception {
        Path repository = tempDir.resolve("repo-python-ide-custom-excludes");
        writeCompiledArtifact(repository, "com.example", "runtime-dep", "1.0.0", Map.of(
            "io.micronaut.http.HttpResponse", """
                package io.micronaut.http;

                public class HttpResponse {
                }
                """,
            "io.micronaut.http.internal.HiddenClient", """
                package io.micronaut.http.internal;

                public class HiddenClient {
                }
                """
        ));
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-python-ide-custom-excludes");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository) + """

            [tool.pyronaut.ide-stubs]
            exclude-patterns = ["*ModuleInfo", "io.micronaut.http.internal.*"]
            """);

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        String httpStub = Files.readString(
            project.resolve("__pyronaut__/ide-stubs").resolve("micronaut/http/__init__.pyi"),
            StandardCharsets.UTF_8
        );
        assertTrue(httpStub.contains("class HttpResponse:"));
        assertFalse(httpStub.contains("class HiddenClient:"));
    }

    @Test
    void kotlinLinkageFailuresAreSilentlySkipped() throws Exception {
        Path repository = tempDir.resolve("repo-python-ide-kotlin-linkage");
        writeCompiledArtifact(repository, "com.example", "compile-only-dep", "1.0.0", Map.of(
            "kotlinx.pyronaut.missing.Continuation", """
                package kotlinx.pyronaut.missing;

                public interface Continuation<T> {
                }
                """
        ));
        Path compileOnlyJar = repository.resolve("com/example/compile-only-dep/1.0.0/compile-only-dep-1.0.0.jar");
        writeCompiledArtifact(
            repository,
            "com.example",
            "runtime-dep",
            "1.0.0",
            Map.of(
                "io.micronaut.http.HttpResponse", """
                    package io.micronaut.http;

                    public class HttpResponse {
                    }
                    """,
                "io.micronaut.http.CoroutineBridge", """
                    package io.micronaut.http;

                    public class CoroutineBridge {
                        public kotlinx.pyronaut.missing.Continuation<?> continuation;
                    }
                    """
            ),
            true,
            List.of(compileOnlyJar)
        );
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-python-ide-kotlin-linkage");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        String warningReport = Files.readString(
            project.resolve("__pyronaut__/reports/editor-stubs/stub-generation-report.txt"),
            StandardCharsets.UTF_8
        );
        String httpStub = Files.readString(
            project.resolve("__pyronaut__/ide-stubs").resolve("micronaut/http/__init__.pyi"),
            StandardCharsets.UTF_8
        );
        assertTrue(httpStub.contains("class HttpResponse:"));
        assertFalse(httpStub.contains("class CoroutineBridge:"));
        assertFalse(warningReport.contains("CoroutineBridge"));
        assertFalse(warningReport.contains("kotlinx.pyronaut.missing.Continuation"));
    }

    @Test
    void classHeadersPreserveMappedSuperclassesAndInterfaces() throws Exception {
        Path repository = tempDir.resolve("repo-python-ide-inheritance");
        writeCompiledArtifact(repository, "com.example", "runtime-dep", "1.0.0", Map.of(
            "io.micronaut.http.HttpRequest", """
                package io.micronaut.http;

                public class HttpRequest {
                }
                """,
            "io.micronaut.http.client.HttpOperations", """
                package io.micronaut.http.client;

                public interface HttpOperations {
                    String route();
                }
                """,
            "io.micronaut.http.client.BaseClient", """
                package io.micronaut.http.client;

                import io.micronaut.http.HttpRequest;

                public class BaseClient<T extends HttpRequest> {
                    public T request;
                }
                """,
            "io.micronaut.http.client.AdvancedClient", """
                package io.micronaut.http.client;

                import io.micronaut.http.HttpRequest;

                public class AdvancedClient extends BaseClient<HttpRequest> implements HttpOperations {
                    public String route() {
                        return "/advanced";
                    }
                }
                """
        ));
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-python-ide-inheritance");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        String clientStub = Files.readString(
            project.resolve("__pyronaut__/ide-stubs").resolve("micronaut/http/client/__init__.pyi"),
            StandardCharsets.UTF_8
        );
        assertTrue(clientStub.contains("class HttpOperations(Protocol):"));
        assertTrue(clientStub.contains("class BaseClient(Generic[_BaseClient_T]):"));
        assertTrue(clientStub.contains("class AdvancedClient(BaseClient[HttpRequest], HttpOperations):"));
    }

    @Test
    void enumsRenderAsPythonEnums() throws Exception {
        Path repository = tempDir.resolve("repo-python-ide-enums");
        writeCompiledArtifact(repository, "com.example", "runtime-dep", "1.0.0", Map.of(
            "io.micronaut.http.HttpMethod", """
                package io.micronaut.http;

                /**
                 * Supported HTTP methods.
                 */
                public enum HttpMethod {
                    GET,
                    POST
                }
                """
        ));
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-python-ide-enums");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());

        String httpStub = Files.readString(
            project.resolve("__pyronaut__/ide-stubs").resolve("micronaut/http/__init__.pyi"),
            StandardCharsets.UTF_8
        );
        assertTrue(httpStub.contains("from enum import Enum"));
        assertTrue(httpStub.contains("class HttpMethod(Enum):"));
        assertTrue(httpStub.contains("Supported HTTP methods."));
        assertTrue(httpStub.contains("GET = ..."));
        assertTrue(httpStub.contains("POST = ..."));
    }

    @Test
    void stubGenerationIsBestEffortForInvalidArtifacts() throws Exception {
        Path repository = tempDir.resolve("repo-python-ide-invalid");
        writeArtifact(repository, "com.example", "runtime-dep", "1.0.0", new byte[]{1, 2, 3});
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeCompiledArtifact(repository, "com.example", "test-dep", "1.0.0", Map.of(
            "jakarta.inject.Inject", """
                package jakarta.inject;

                public @interface Inject {
                }
                """
        ));

        Path project = tempDir.resolve("project-python-ide-invalid");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());
        assertTrue(Files.exists(project.resolve("__pyronaut__/ide-stubs").resolve("jakarta/inject/__init__.pyi")));
        assertTrue(Files.exists(project.resolve("__pyronaut__/reports/editor-stubs/stub-generation-report.txt")));
    }

    @Test
    void stubGenerationIsBestEffortWhenTypeRenderingHitsMissingTransitiveClass() throws Exception {
        Path repository = tempDir.resolve("repo-python-ide-missing-transitive");
        writeCompiledArtifact(repository, "com.example", "compile-only-dep", "1.0.0", Map.of(
            "io.micronaut.http.MissingDependency", """
                package io.micronaut.http;

                public enum MissingDependency {
                    VALUE
                }
                """
        ));
        Path compileOnlyJar = repository.resolve("com/example/compile-only-dep/1.0.0/compile-only-dep-1.0.0.jar");
        writeCompiledArtifact(
            repository,
            "com.example",
            "runtime-dep",
            "1.0.0",
            Map.of(
                "io.micronaut.http.HttpResponse", """
                    package io.micronaut.http;

                    public class HttpResponse {
                    }
                    """,
                "io.micronaut.http.BrokenType", """
                    package io.micronaut.http;

                    public class BrokenType {
                        public MissingDependency missing;
                    }
                    """
            ),
            true,
            List.of(compileOnlyJar)
        );
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeCompiledArtifact(repository, "com.example", "test-dep", "1.0.0", Map.of(
            "jakarta.inject.Inject", """
                package jakarta.inject;

                public @interface Inject {
                }
                """
        ));

        Path project = tempDir.resolve("project-python-ide-missing-transitive");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());
        String httpStub = Files.readString(
            project.resolve("__pyronaut__/ide-stubs").resolve("micronaut/http/__init__.pyi"),
            StandardCharsets.UTF_8
        );
        assertTrue(httpStub.contains("class HttpResponse:"));
        assertFalse(httpStub.contains("class BrokenType:"));
    }

    @Test
    void stubGenerationUsesSlf4jSupportJarFromInstallClasspath() throws Exception {
        Path repository = tempDir.resolve("repo-python-ide-slf4j-support");
        Path slf4jApiJar = currentClasspathArtifact("org.slf4j.Logger");
        writeCompiledArtifact(
            repository,
            "com.example",
            "runtime-dep",
            "1.0.0",
            Map.of(
                "io.micronaut.http.HttpResponse", """
                    package io.micronaut.http;

                    public class HttpResponse {
                    }
                    """,
                "io.micronaut.http.LoggingSupport", """
                    package io.micronaut.http;

                    public class LoggingSupport {
                        public org.slf4j.Logger logger;
                    }
                    """
            ),
            true,
            List.of(slf4jApiJar)
        );
        writeArtifact(repository, "com.example", "build-dep", "1.0.0");
        writeArtifact(repository, "com.example", "test-dep", "1.0.0");

        Path project = tempDir.resolve("project-python-ide-slf4j-support");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), pyproject(repository));

        PyronautInstallMain command = new PyronautInstallMain(new PyprojectModelReader(), new MavenClasspathResolver());
        command.projectDir = project;

        assertEquals(InstallExitCode.SUCCESS.code(), command.call());
        String httpStub = Files.readString(
            project.resolve("__pyronaut__/ide-stubs").resolve("micronaut/http/__init__.pyi"),
            StandardCharsets.UTF_8
        );
        assertTrue(httpStub.contains("class HttpResponse:"));
        assertTrue(httpStub.contains("class LoggingSupport:"));
        assertTrue(httpStub.contains("logger: Any"));
        Path warningReport = project.resolve("__pyronaut__/reports/editor-stubs/stub-generation-report.txt");
        if (Files.exists(warningReport)) {
            String report = Files.readString(warningReport, StandardCharsets.UTF_8);
            assertFalse(report.contains("LoggingSupport"));
            assertFalse(report.contains("org/slf4j/Logger"));
        }
    }

    private static String pyproject(Path repository) {
        return pyprojectBase(repository) + """

            [tool.pyronaut.test-resources]
            enabled = false
            """;
    }

    private static String pyprojectBase(Path repository) {
        return """
            [project]
            name = "install-test"
            version = "1.0.0"

            [tool.pyronaut]
            repositories = ["%s"]

            [tool.pyronaut.core]
            version = ""

            [tool.pyronaut.platform]
            version = ""

            [tool.pyronaut.dependencies]
            runtime = ["com.example:runtime-dep:1.0.0"]
            build = ["com.example:build-dep:1.0.0"]
            test = ["com.example:test-dep:1.0.0"]
            """.formatted(repository.toUri());
    }

    private static void restoreJavaHome(String previousJavaHome) {
        if (previousJavaHome == null) {
            System.clearProperty("java.home");
        } else {
            System.setProperty("java.home", previousJavaHome);
        }
    }

    private static void restoreSystemProperty(String key, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previousValue);
        }
    }

    private static Path currentClasspathArtifact(String className) throws IOException {
        try {
            Class<?> type = Class.forName(className);
            if (type.getProtectionDomain() == null
                || type.getProtectionDomain().getCodeSource() == null
                || type.getProtectionDomain().getCodeSource().getLocation() == null) {
                throw new IOException("Could not determine classpath artifact for " + className);
            }
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            throw new IOException("Could not determine classpath artifact for " + className, e);
        }
    }

    private static String pyprojectWithTestResources(Path repository, boolean enabled) {
        return pyprojectBase(repository) + "\n" + "[tool.pyronaut.test-resources]\n"
            + "enabled = " + enabled + "\n"
            + "version = \"2.9.0\"\n";
    }

    private static void writeArtifact(Path repository, String groupId, String artifactId, String version) throws IOException {
        writeArtifact(repository, groupId, artifactId, version, new byte[]{0});
    }

    private static void writeArtifact(Path repository, String groupId, String artifactId, String version, byte[] jarBytes) throws IOException {
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
        Files.write(jarFile, jarBytes);
    }

    private static void writeArtifactWithEntries(Path repository,
                                                 String groupId,
                                                 String artifactId,
                                                 String version,
                                                 Map<String, String> entries) throws IOException {
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
        try (ZipOutputStream outputStream = new ZipOutputStream(Files.newOutputStream(jarFile))) {
            Map<String, String> ordered = new LinkedHashMap<>(entries);
            for (Map.Entry<String, String> entry : ordered.entrySet()) {
                outputStream.putNextEntry(new ZipEntry(entry.getKey()));
                outputStream.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                outputStream.closeEntry();
            }
        }
    }

    private static void writeArtifactWithDependencies(Path repository,
                                                      String groupId,
                                                      String artifactId,
                                                      String version,
                                                      List<DependencyCoordinate> dependencies) throws IOException {
        Path artifactDir = repository
            .resolve(groupId.replace('.', '/'))
            .resolve(artifactId)
            .resolve(version);
        Files.createDirectories(artifactDir);

        StringBuilder dependencyBlock = new StringBuilder();
        for (DependencyCoordinate dependency : dependencies) {
            dependencyBlock
                .append("    <dependency>\n")
                .append("      <groupId>").append(dependency.groupId()).append("</groupId>\n")
                .append("      <artifactId>").append(dependency.artifactId()).append("</artifactId>\n")
                .append("      <version>").append(dependency.version()).append("</version>\n")
                .append("    </dependency>\n");
        }

        Files.writeString(artifactDir.resolve(artifactId + "-" + version + ".pom"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd">
              <modelVersion>4.0.0</modelVersion>
              <groupId>%s</groupId>
              <artifactId>%s</artifactId>
              <version>%s</version>
              <dependencies>
            %s  </dependencies>
            </project>
            """.formatted(groupId, artifactId, version, dependencyBlock), StandardCharsets.UTF_8);

        Path jarFile = artifactDir.resolve(artifactId + "-" + version + ".jar");
        Files.write(jarFile, new byte[]{0});
    }

    private static void writeCompiledArtifact(Path repository,
                                              String groupId,
                                              String artifactId,
                                              String version,
                                              Map<String, String> sources) throws IOException {
        writeCompiledArtifact(repository, groupId, artifactId, version, sources, true);
    }

    private static void writeCompiledArtifactWithoutParameterMetadata(Path repository,
                                                                      String groupId,
                                                                      String artifactId,
                                                                      String version,
                                                                      Map<String, String> sources) throws IOException {
        writeCompiledArtifact(repository, groupId, artifactId, version, sources, false);
    }

    private static void writeCompiledArtifact(Path repository,
                                              String groupId,
                                              String artifactId,
                                              String version,
                                              Map<String, String> sources,
                                              boolean includeParameterMetadata) throws IOException {
        writeCompiledArtifact(repository, groupId, artifactId, version, sources, includeParameterMetadata, List.of());
    }

    private static void writeCompiledArtifact(Path repository,
                                              String groupId,
                                              String artifactId,
                                              String version,
                                              Map<String, String> sources,
                                              boolean includeParameterMetadata,
                                              List<Path> compileClasspath) throws IOException {
        Path artifactDir = repository
            .resolve(groupId.replace('.', '/'))
            .resolve(artifactId)
            .resolve(version);
        Files.createDirectories(artifactDir);

        Files.writeString(artifactDir.resolve(artifactId + "-" + version + ".pom"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd">
              <modelVersion>4.0.0</modelVersion>
              <groupId>%s</groupId>
              <artifactId>%s</artifactId>
              <version>%s</version>
            </project>
            """.formatted(groupId, artifactId, version), StandardCharsets.UTF_8);

        Path sourceDir = Files.createTempDirectory("pyronaut-install-sources");
        Path classesDir = Files.createTempDirectory("pyronaut-install-classes");
        List<Path> sourceFiles = new ArrayList<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            Path sourceFile = sourceDir.resolve(entry.getKey().replace('.', '/') + ".java");
            Files.createDirectories(sourceFile.getParent());
            Files.writeString(sourceFile, entry.getValue(), StandardCharsets.UTF_8);
            sourceFiles.add(sourceFile);
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("No system Java compiler is available");
        }
        StringWriter compilerOutput = new StringWriter();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            List<String> compilerOptions = new ArrayList<>();
            if (includeParameterMetadata) {
                compilerOptions.add("-parameters");
            }
            if (compileClasspath != null && !compileClasspath.isEmpty()) {
                compilerOptions.add("-classpath");
                compilerOptions.add(compileClasspath.stream()
                    .map(Path::toString)
                    .reduce((left, right) -> left + java.io.File.pathSeparator + right)
                    .orElse(""));
            }
            compilerOptions.add("-d");
            compilerOptions.add(classesDir.toString());
            boolean success = compiler.getTask(
                compilerOutput,
                fileManager,
                null,
                compilerOptions,
                null,
                fileManager.getJavaFileObjectsFromFiles(sourceFiles.stream().map(Path::toFile).toList())
            ).call();
            if (!success) {
                throw new IOException("Compilation failed: " + compilerOutput);
            }
        }

        Path jarFile = artifactDir.resolve(artifactId + "-" + version + ".jar");
        try (ZipOutputStream outputStream = new ZipOutputStream(Files.newOutputStream(jarFile));
             Stream<Path> walk = Files.walk(classesDir)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String entryName = classesDir.relativize(file).toString().replace('\\', '/');
                outputStream.putNextEntry(new ZipEntry(entryName));
                outputStream.write(Files.readAllBytes(file));
                outputStream.closeEntry();
            }
        }

        Path sourceJarFile = artifactDir.resolve(artifactId + "-" + version + "-sources.jar");
        try (ZipOutputStream outputStream = new ZipOutputStream(Files.newOutputStream(sourceJarFile));
             Stream<Path> walk = Files.walk(sourceDir)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String entryName = sourceDir.relativize(file).toString().replace('\\', '/');
                outputStream.putNextEntry(new ZipEntry(entryName));
                outputStream.write(Files.readAllBytes(file));
                outputStream.closeEntry();
            }
        }
    }

    private static void writeBom(Path repository,
                                 String groupId,
                                 String artifactId,
                                 String version,
                                 List<ManagedDependency> managedDependencies) throws IOException {
        Path artifactDir = repository
            .resolve(groupId.replace('.', '/'))
            .resolve(artifactId)
            .resolve(version);
        Files.createDirectories(artifactDir);

        StringBuilder dependencyManagement = new StringBuilder();
        for (ManagedDependency dependency : managedDependencies) {
            dependencyManagement
                .append("      <dependency>\n")
                .append("        <groupId>").append(dependency.groupId()).append("</groupId>\n")
                .append("        <artifactId>").append(dependency.artifactId()).append("</artifactId>\n")
                .append("        <version>").append(dependency.version()).append("</version>\n")
                .append("      </dependency>\n");
        }

        Files.writeString(
            artifactDir.resolve(artifactId + "-" + version + ".pom"),
            """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <packaging>pom</packaging>
                  <dependencyManagement>
                    <dependencies>
            %s        </dependencies>
                  </dependencyManagement>
                </project>
                """.formatted(groupId, artifactId, version, dependencyManagement),
            StandardCharsets.UTF_8
        );
    }

    private record ManagedDependency(String groupId, String artifactId, String version) {
    }

    private record DependencyCoordinate(String groupId, String artifactId, String version) {
    }
}

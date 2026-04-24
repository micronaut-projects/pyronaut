package io.micronaut.pyronaut.install;

import io.micronaut.pyronaut.config.model.PyprojectModelReader;
import io.micronaut.pyronaut.config.model.PyprojectJsonSchemaGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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

        Path cacheDir = project.resolve("__pyronaut__");
        Path buildManifest = cacheDir.resolve("resolved-build-dependencies");
        Path runtimeManifest = cacheDir.resolve("resolved-runtime-dependencies");
        Path testManifest = cacheDir.resolve("resolved-test-dependencies");
        Path testResourcesServerManifest = cacheDir.resolve("resolved-test-resources-server-dependencies");
        assertTrue(Files.exists(buildManifest));
        assertTrue(Files.exists(runtimeManifest));
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
            version = "1.0.0"
            repositories = ["%s"]

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
            version = "1.0.0"
            repositories = ["%s"]

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

            [tool.pyronaut.dependencies]
            runtime = ["com.example:runtime-a:1.0.0", "com.example:runtime-b:1.0.0"]
            build = ["com.example:build-dep:1.0.0"]
            test = ["com.example:test-dep:1.0.0"]
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
                new ManagedDependency("org.junit.jupiter", "junit-jupiter-engine", "5.12.2")
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
            version = "5.0.0"
            repositories = ["%s"]

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
            version = "5.0.0"
            repositories = ["%s"]

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
            version = "5.0.0-SNAPSHOT"
            repositories = ["%s"]

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

        assertTrue(runtimeEntries.stream().anyMatch(entry -> entry.contains("micronaut-inject-python") && entry.contains("5.0.0-SNAPSHOT")));
        assertTrue(runtimeEntries.stream().anyMatch(entry -> entry.contains("micronaut-context-python") && entry.contains("5.0.0-SNAPSHOT")));
        assertTrue(testEntries.stream().anyMatch(entry -> entry.contains("micronaut-test-junit5") && entry.contains("5.0.0-SNAPSHOT")));
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
            version = "5.0.0"
            repositories = ["%s"]

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

    private static String pyprojectWithTestResources(Path repository, boolean enabled) {
        return pyproject(repository) + "\n" + "[tool.pyronaut.testResources]\n"
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

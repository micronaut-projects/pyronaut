package io.micronaut.pyronaut.config.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PyprojectModelReaderTest {

    @TempDir
    Path tempDir;

    private final PyprojectModelReader reader = new PyprojectModelReader();

    @Test
    void parseCanonicalPyproject() throws URISyntaxException {
        var fixture = PyprojectModelReaderTest.class.getResource("/fixtures/pyproject.toml");
        assertNotNull(fixture);
        PyprojectModel model = reader.readFile(Path.of(fixture.toURI()));

        assertEquals("pyronaut-demo", model.project().name());
        assertEquals("1.0.0", model.project().version());
        assertEquals("setuptools.build_meta", model.buildSystem().buildBackend());
        assertEquals("5.0.0-SNAPSHOT", model.pyronaut().version());
        assertEquals(3, model.pyronaut().repositories().size());
        assertEquals(8, model.pyronaut().dependencies().runtime().size());
        assertEquals(2, model.pyronaut().dependencies().build().size());
        assertEquals(4, model.pyronaut().dependencies().test().size());
        assertEquals("jvm", model.pyronaut().build().mode());
        assertNotNull(model.pyronaut().build().metadata());
        assertEquals(List.of(), model.pyronaut().build().metadata().excludedModules());
        assertNotNull(model.pyronaut().validation());
        assertEquals(Boolean.TRUE, model.pyronaut().validation().enabled());
        assertEquals(Boolean.TRUE, model.pyronaut().validation().failOnNotPresent());
        assertEquals("both", model.pyronaut().validation().format());
        assertEquals(List.of("dev"), model.pyronaut().validation().run().environments());
        assertEquals(List.of("test"), model.pyronaut().validation().test().environments());
        assertEquals(List.of(), model.pyronaut().validation().production().environments());
        assertNotNull(model.pyronaut().testResources());
        assertEquals(Boolean.TRUE, model.pyronaut().testResources().enabled());
        assertEquals(Boolean.TRUE, model.pyronaut().testResources().inferClasspath());
        assertEquals(Integer.valueOf(60), model.pyronaut().testResources().clientTimeout());
        assertEquals(Boolean.FALSE, model.pyronaut().testResources().sharedServer());
        assertEquals("auto", model.pyronaut().testResources().startupOptimization());
    }

    @Test
    void rejectInvalidFilename() throws IOException {
        Path file = tempDir.resolve("pyroject.toml");
        Files.writeString(file, "[project]\nname='x'\n");

        PyprojectModelException exception = assertThrows(PyprojectModelException.class, () -> reader.readFile(file));
        assertEquals("Invalid config filename 'pyroject.toml'. Expected 'pyproject.toml'", exception.getMessage());
    }

    @Test
    void rejectMissingFile() {
        Path file = tempDir.resolve("pyproject.toml");

        PyprojectModelException exception = assertThrows(PyprojectModelException.class, () -> reader.readFile(file));
        assertEquals("Missing required file 'pyproject.toml' at: " + file, exception.getMessage());
    }

    @Test
    void parseLegacyDependencyKeys() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut]
            version = "5.0.0-SNAPSHOT"
            annotationProcessor = ["io.micronaut:micronaut-context-python"]

            [tool.pyronaut.dependencies]
            compile = ["io.micronaut:micronaut-inject-python"]
            test = ["org.junit.jupiter:junit-jupiter-engine"]
            """);

        PyprojectModel model = reader.readFile(file);
        assertEquals(1, model.pyronaut().dependencies().runtime().size());
        assertEquals("io.micronaut:micronaut-inject-python", model.pyronaut().dependencies().runtime().getFirst());
        assertEquals(1, model.pyronaut().dependencies().build().size());
        assertEquals("io.micronaut:micronaut-context-python", model.pyronaut().dependencies().build().getFirst());
    }

    @Test
    void rejectInvalidToml() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, "[project\nname = \"broken\"\n");

        PyprojectModelException exception = assertThrows(PyprojectModelException.class, () -> reader.readFile(file));
        assertTrue(exception.getMessage().startsWith("Invalid TOML in "));
    }

    @Test
    void rejectInvalidRepositoriesType() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut]
            repositories = "mavenCentral"
            """);

        PyprojectModelException exception = assertThrows(PyprojectModelException.class, () -> reader.readFile(file));
        assertEquals("Invalid type for 'tool.pyronaut.repositories': expected array", exception.getMessage());
    }

    @Test
    void parseBuildModeDefaultsToJvmWhenMissing() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut]
            version = "5.0.0-SNAPSHOT"
            """);

        PyprojectModel model = reader.readFile(file);
        assertEquals("jvm", model.pyronaut().build().mode());
    }

    @Test
    void rejectInvalidBuildModeType() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut]
            version = "5.0.0-SNAPSHOT"

            [tool.pyronaut.build]
            mode = ["native"]
            """);

        PyprojectModelException exception = assertThrows(PyprojectModelException.class, () -> reader.readFile(file));
        assertEquals("Invalid type for 'tool.pyronaut.build.mode': expected string", exception.getMessage());
    }

    @Test
    void rejectInvalidBuildModeValue() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut]
            version = "5.0.0-SNAPSHOT"

            [tool.pyronaut.build]
            mode = "fast"
            """);

        PyprojectModelException exception = assertThrows(PyprojectModelException.class, () -> reader.readFile(file));
        assertEquals("Invalid value for 'tool.pyronaut.build.mode': expected 'jvm' or 'native'", exception.getMessage());
    }

    @Test
    void parseBuildMetadataConfiguration() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut]
            version = "5.0.0-SNAPSHOT"

            [tool.pyronaut.build]
            mode = "native"

            [tool.pyronaut.build.metadata]
            enabled = false
            version = "0.9.0"
            repositoryUrl = "https://example.test/metadata.zip"
            excludedModules = ["org.slf4j:slf4j-api", "ch.qos.logback:logback-classic"]
            """);

        PyprojectModel model = reader.readFile(file);
        assertEquals("native", model.pyronaut().build().mode());
        assertEquals(Boolean.FALSE, model.pyronaut().build().metadata().enabled());
        assertEquals("0.9.0", model.pyronaut().build().metadata().version());
        assertEquals("https://example.test/metadata.zip", model.pyronaut().build().metadata().repositoryUrl());
        assertEquals(List.of("org.slf4j:slf4j-api", "ch.qos.logback:logback-classic"), model.pyronaut().build().metadata().excludedModules());
    }

    @Test
    void rejectInvalidBuildMetadataEnabledType() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut]
            version = "5.0.0-SNAPSHOT"

            [tool.pyronaut.build.metadata]
            enabled = "yes"
            """);

        PyprojectModelException exception = assertThrows(PyprojectModelException.class, () -> reader.readFile(file));
        assertEquals("Invalid type for 'tool.pyronaut.build.metadata.enabled': expected boolean", exception.getMessage());
    }

    @Test
    void parseValidationConfiguration() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut]
            version = "5.0.0-SNAPSHOT"

            [tool.pyronaut.validation]
            enabled = true
            failOnNotPresent = false
            deduceEnvironments = true
            validateDependencyInjection = true
            dependencyInjectionValidationStrategy = "all-beans"
            format = "json"
            suppressions = ["micronaut.config.deprecated"]
            suppressInjectErrors = ["missing.bean"]
            projectBaseDir = "src"
            resourcesDirs = ["src/main/resources"]

            [tool.pyronaut.validation.run]
            environments = ["dev", "cloud"]
            includeDefaultEnvironment = false
            overrideClasspath = true
            classpath = ["/tmp/run-cp"]
            additionalClasspath = ["/tmp/additional"]
            resourcesDirs = ["src/run/resources"]
            outputDir = "build/reports/run"
            """);

        PyprojectModel model = reader.readFile(file);
        assertEquals(Boolean.TRUE, model.pyronaut().validation().enabled());
        assertEquals(Boolean.FALSE, model.pyronaut().validation().failOnNotPresent());
        assertEquals(Boolean.TRUE, model.pyronaut().validation().validateDependencyInjection());
        assertEquals("all-beans", model.pyronaut().validation().dependencyInjectionValidationStrategy());
        assertEquals("json", model.pyronaut().validation().format());
        assertEquals(List.of("micronaut.config.deprecated"), model.pyronaut().validation().suppressions());
        assertEquals(List.of("missing.bean"), model.pyronaut().validation().suppressInjectErrors());
        assertEquals(List.of("dev", "cloud"), model.pyronaut().validation().run().environments());
        assertEquals(Boolean.TRUE, model.pyronaut().validation().run().overrideClasspath());
        assertEquals(List.of("/tmp/run-cp"), model.pyronaut().validation().run().classpath());
    }

    @Test
    void rejectInvalidValidationFailOnNotPresentType() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut]
            version = "5.0.0-SNAPSHOT"

            [tool.pyronaut.validation]
            failOnNotPresent = "yes"
            """);

        PyprojectModelException exception = assertThrows(PyprojectModelException.class, () -> reader.readFile(file));
        assertEquals("Invalid type for 'tool.pyronaut.validation.failOnNotPresent': expected boolean", exception.getMessage());
    }

    @Test
    void parseTestResourcesConfiguration() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut]
            version = "5.0.0-SNAPSHOT"

            [tool.pyronaut.testResources]
            enabled = true
            version = "2.9.0"
            explicitPort = 18081
            inferClasspath = false
            additionalModules = ["jdbc-postgresql"]
            clientTimeout = 90
            sharedServer = true
            sharedServerNamespace = "demo"
            serverIdleTimeoutMinutes = 15
            debugServer = true
            javaExecutable = "/opt/jdk/bin/java"
            startupOptimization = "leyden"
            leydenJvmArgs = ["--enable-preview", "-XX:+UseFastUnorderedTimeStamps"]

            [tool.pyronaut.testResources.serverSystemProperties]
            "micronaut.server.host" = "127.0.0.1"

            [tool.pyronaut.testResources.serverEnvironment]
            "TEST_RESOURCES_MODE" = "standalone"
            """);

        PyprojectModel model = reader.readFile(file);
        PyprojectModel.TestResources testResources = model.pyronaut().testResources();
        assertEquals(Boolean.TRUE, testResources.enabled());
        assertEquals("2.9.0", testResources.version());
        assertEquals(Integer.valueOf(18081), testResources.explicitPort());
        assertEquals(Boolean.FALSE, testResources.inferClasspath());
        assertEquals(List.of("jdbc-postgresql"), testResources.additionalModules());
        assertEquals(Integer.valueOf(90), testResources.clientTimeout());
        assertEquals(Boolean.TRUE, testResources.sharedServer());
        assertEquals("demo", testResources.sharedServerNamespace());
        assertEquals(Integer.valueOf(15), testResources.serverIdleTimeoutMinutes());
        assertEquals(Map.of("micronaut.server.host", "127.0.0.1"), testResources.serverSystemProperties());
        assertEquals(Map.of("TEST_RESOURCES_MODE", "standalone"), testResources.serverEnvironment());
        assertEquals(Boolean.TRUE, testResources.debugServer());
        assertEquals("/opt/jdk/bin/java", testResources.javaExecutable());
        assertEquals("leyden", testResources.startupOptimization());
        assertEquals(List.of("--enable-preview", "-XX:+UseFastUnorderedTimeStamps"), testResources.leydenJvmArgs());
    }

    @Test
    void rejectInvalidTestResourcesClientTimeoutType() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut]
            version = "5.0.0-SNAPSHOT"

            [tool.pyronaut.testResources]
            clientTimeout = "fast"
            """);

        PyprojectModelException exception = assertThrows(PyprojectModelException.class, () -> reader.readFile(file));
        assertEquals("Invalid type for 'tool.pyronaut.testResources.clientTimeout': expected integer", exception.getMessage());
    }
}

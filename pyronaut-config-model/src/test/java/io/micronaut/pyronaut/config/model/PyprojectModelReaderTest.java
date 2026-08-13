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
        assertEquals(PyronautManagedVersions.micronautCoreVersion(), model.pyronaut().coreVersion());
        assertEquals(PyronautManagedVersions.micronautPlatformVersion(), model.pyronaut().platformVersion());
        assertEquals(2, model.pyronaut().repositories().size());
        assertEquals(8, model.pyronaut().dependencies().runtime().size());
        assertEquals(1, model.pyronaut().dependencies().developmentRuntime().size());
        assertEquals("io.micronaut.controlpanel:micronaut-control-panel-management", model.pyronaut().dependencies().developmentRuntime().getFirst());
        assertEquals(2, model.pyronaut().dependencies().build().size());
        assertEquals(4, model.pyronaut().dependencies().test().size());
        assertEquals(true, model.pyronaut().run().bannerEnabled());
        assertEquals(Boolean.FALSE, model.pyronaut().controlPanel().enabled());
        assertEquals("/control-panel", model.pyronaut().controlPanel().path());
        assertEquals(Boolean.FALSE, model.pyronaut().controlPanel().productionEnabled());
        assertEquals("jvm", model.pyronaut().build().mode());
        assertEquals(Boolean.TRUE, model.pyronaut().build().pythonBytecodeEnabled());
        assertEquals("jvm", model.pyronaut().processor().mode());
        assertEquals(Boolean.FALSE, model.pyronaut().processor().incremental());
        assertEquals("conservative", model.pyronaut().processor().pythonIncrementalMode());
        assertEquals(Boolean.FALSE, model.pyronaut().processor().daemon());
        assertEquals("src", model.pyronaut().sources().python());
        assertEquals("tests", model.pyronaut().sources().pythonTest());
        assertEquals("src-java", model.pyronaut().sources().java());
        assertEquals("test-java", model.pyronaut().sources().javaTest());
        assertEquals("config", model.pyronaut().sources().resources());
        assertEquals("tests-config", model.pyronaut().sources().testResources());
        assertEquals(List.of(), model.pyronaut().sources().additionalResources());
        assertEquals(List.of(), model.pyronaut().sources().additionalTestResources());
        assertEquals("ee", model.pyronaut().toolchain().distribution());
        assertEquals("jvm", model.pyronaut().toolchain().type());
        assertEquals(Integer.valueOf(25), model.pyronaut().toolchain().javaVersion());
        assertNotNull(model.pyronaut().ideStubs());
        assertEquals(Boolean.TRUE, model.pyronaut().ideStubs().enabled());
        assertEquals("vscode", model.pyronaut().ideStubs().ide());
        assertEquals(List.of("io.micronaut", "jakarta"), model.pyronaut().ideStubs().packages());
        assertEquals(List.of("*ModuleInfo"), model.pyronaut().ideStubs().excludePatterns());
        assertEquals("__pyronaut__/ide-stubs", model.pyronaut().ideStubs().destinationDir());
        assertNotNull(model.pyronaut().build().metadata());
        assertEquals(List.of(), model.pyronaut().build().metadata().excludedModules());
        assertNotNull(model.pyronaut().build().docker());
        assertNotNull(model.pyronaut().validation());
        assertEquals(Boolean.TRUE, model.pyronaut().validation().enabled());
        assertEquals(Boolean.TRUE, model.pyronaut().validation().failOnNotPresent());
        assertEquals("both", model.pyronaut().validation().format());
        assertEquals(List.of("dev"), model.pyronaut().validation().run().environments());
        assertEquals(List.of("test"), model.pyronaut().validation().test().environments());
        assertEquals(List.of(), model.pyronaut().validation().production().environments());
        assertNotNull(model.pyronaut().testResources());
        assertEquals(false, model.pyronaut().testResources().configured());
        assertEquals(Boolean.TRUE, model.pyronaut().testResources().enabled());
        assertEquals(Boolean.TRUE, model.pyronaut().testResources().inferClasspath());
        assertEquals(Integer.valueOf(60), model.pyronaut().testResources().clientTimeout());
        assertEquals(Boolean.FALSE, model.pyronaut().testResources().sharedServer());
        assertEquals("none", model.pyronaut().testResources().startupOptimization());
    }

    @Test
    void parsesPythonBytecodeConfiguration() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut.build.python-bytecode]
            enabled = true
            """);

        PyprojectModel model = reader.readFile(file);

        assertEquals(Boolean.TRUE, model.pyronaut().build().pythonBytecodeEnabled());
    }

    @Test
    void pythonBytecodeConfigurationDefaultsToEnabled() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"
            """);

        PyprojectModel model = reader.readFile(file);

        assertEquals(Boolean.TRUE, model.pyronaut().build().pythonBytecodeEnabled());
    }

    @Test
    void parseCustomSourcesConfiguration() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut]

            [tool.pyronaut.sources]
            python = "app"
            python-test = "spec"
            java = "java-src"
            java-test = "java-test"
            resources = "app-config"
            test-resources = "test-config"
            additional-resources = ["assets", "views"]
            additional-test-resources = ["test-assets", "test-fixtures"]
            """);

        PyprojectModel model = reader.readFile(file);
        assertEquals("app", model.pyronaut().sources().python());
        assertEquals("spec", model.pyronaut().sources().pythonTest());
        assertEquals("java-src", model.pyronaut().sources().java());
        assertEquals("java-test", model.pyronaut().sources().javaTest());
        assertEquals("app-config", model.pyronaut().sources().resources());
        assertEquals("test-config", model.pyronaut().sources().testResources());
        assertEquals(List.of("assets", "views"), model.pyronaut().sources().additionalResources());
        assertEquals(List.of("test-assets", "test-fixtures"), model.pyronaut().sources().additionalTestResources());
        assertEquals(List.of("app-config", "assets", "views", "src/main/resources"), model.pyronaut().validation().run().resourcesDirs());
        assertEquals(List.of("app-config", "assets", "views", "src/main/resources", "test-config", "test-assets", "test-fixtures"), model.pyronaut().validation().test().resourcesDirs());
    }

    @Test
    void parseRunConfiguration() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut]

            [tool.pyronaut.run]
            banner-enabled = false
            """);

        PyprojectModel model = reader.readFile(file);
        assertEquals(false, model.pyronaut().run().bannerEnabled());
    }

    @Test
    void parseControlPanelConfiguration() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut]

            [tool.pyronaut.control-panel]
            enabled = false
            path = "/admin/control-panel"
            production-enabled = true
            """);

        PyprojectModel model = reader.readFile(file);
        assertEquals(Boolean.FALSE, model.pyronaut().controlPanel().enabled());
        assertEquals("/admin/control-panel", model.pyronaut().controlPanel().path());
        assertEquals(Boolean.TRUE, model.pyronaut().controlPanel().productionEnabled());
    }

    @Test
    void rejectRelativeControlPanelPath() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut]

            [tool.pyronaut.control-panel]
            path = "control-panel"
            """);

        PyprojectModelException exception = assertThrows(PyprojectModelException.class, () -> reader.readFile(file));
        assertEquals(
            "Invalid value for 'tool.pyronaut.control-panel.path': expected an absolute URL path starting with '/'",
            exception.getMessage()
        );
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
    void parsesBomsAndDependencyExclusions() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"
            [tool.pyronaut.dependencies]
            boms = ["io.netty:netty-bom:4.2.17.Final"]
            exclusions = ["commons:commons-lang"]
            [tool.pyronaut.dependencies.artifact-exclusions]
            "io.netty:netty-handler" = ["commons:commons-lang"]
            """);
        PyprojectModel.Dependencies dependencies = reader.readFile(file).pyronaut().dependencies();
        assertEquals(List.of("io.netty:netty-bom:4.2.17.Final"), dependencies.boms());
        assertEquals(List.of("commons:commons-lang"), dependencies.exclusions());
        assertEquals(List.of("commons:commons-lang"), dependencies.artifactExclusions().get("io.netty:netty-handler"));
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

            [tool.pyronaut.build]
            mode = "fast"
            """);

        PyprojectModelException exception = assertThrows(PyprojectModelException.class, () -> reader.readFile(file));
        assertEquals("Invalid value for 'tool.pyronaut.build.mode': expected one of [jvm, native]", exception.getMessage());
    }

    @Test
    void parseProcessorModeDefaultsToJvmWhenMissing() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut]
            """);

        PyprojectModel model = reader.readFile(file);
        assertEquals("jvm", model.pyronaut().processor().mode());
        assertEquals(Boolean.FALSE, model.pyronaut().processor().incremental());
        assertEquals("conservative", model.pyronaut().processor().pythonIncrementalMode());
        assertEquals(Boolean.FALSE, model.pyronaut().processor().daemon());
    }

    @Test
    void parseProcessorModeWhenConfigured() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut]

            [tool.pyronaut.processor]
            mode = "native"
            """);

        PyprojectModel model = reader.readFile(file);
        assertEquals("native", model.pyronaut().processor().mode());
    }

    @Test
    void parseIncrementalProcessorCompilationWhenConfigured() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut.processor]
            incremental = true
            """);

        PyprojectModel model = reader.readFile(file);
        assertEquals(Boolean.TRUE, model.pyronaut().processor().incremental());
    }

    @Test
    void parsePythonIncrementalModeWhenConfigured() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut.processor]
            python-incremental-mode = "optimistic"
            """);

        PyprojectModel model = reader.readFile(file);
        assertEquals("optimistic", model.pyronaut().processor().pythonIncrementalMode());
    }

    @Test
    void parseProcessorDaemonWhenConfigured() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut.processor]
            daemon = true
            """);

        PyprojectModel model = reader.readFile(file);
        assertEquals(Boolean.TRUE, model.pyronaut().processor().daemon());
    }

    @Test
    void rejectInvalidPythonIncrementalMode() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut.processor]
            python-incremental-mode = "fast"
            """);

        PyprojectModelException exception = assertThrows(
            PyprojectModelException.class,
            () -> reader.readFile(file)
        );
        assertEquals(
            "Invalid value for 'tool.pyronaut.processor.python-incremental-mode': "
                + "expected one of [conservative, optimistic]",
            exception.getMessage()
        );
    }

    @Test
    void rejectInvalidIncrementalProcessorType() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut.processor]
            incremental = "yes"
            """);

        PyprojectModelException exception = assertThrows(
            PyprojectModelException.class,
            () -> reader.readFile(file)
        );
        assertEquals(
            "Invalid type for 'tool.pyronaut.processor.incremental': expected boolean",
            exception.getMessage()
        );
    }

    @Test
    void rejectInvalidProcessorModeType() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut]

            [tool.pyronaut.processor]
            mode = ["native"]
            """);

        PyprojectModelException exception = assertThrows(PyprojectModelException.class, () -> reader.readFile(file));
        assertEquals("Invalid type for 'tool.pyronaut.processor.mode': expected string", exception.getMessage());
    }

    @Test
    void rejectInvalidProcessorModeValue() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut]

            [tool.pyronaut.processor]
            mode = "fast"
            """);

        PyprojectModelException exception = assertThrows(PyprojectModelException.class, () -> reader.readFile(file));
        assertEquals("Invalid value for 'tool.pyronaut.processor.mode': expected one of [jvm, native]", exception.getMessage());
    }

    @Test
    void parseTestModeDefaultsToJvmWhenMissing() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut]
            """);

        PyprojectModel model = reader.readFile(file);
        assertEquals("jvm", model.pyronaut().test().mode());
    }

    @Test
    void parseTestModeWhenConfigured() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut]

            [tool.pyronaut.test]
            mode = "native"
            """);

        PyprojectModel model = reader.readFile(file);
        assertEquals("native", model.pyronaut().test().mode());
    }

    @Test
    void parseToolchainConfiguration() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [build-system]
            requires = ["setuptools"]
            build-backend = "setuptools.build_meta"

            [tool.pyronaut]

            [tool.pyronaut.toolchain]
            distribution = "dev"
            type = "native"
            version = "25.1.0-dev+10.1"
            java-version = 25
            release-tag = "jdk-25.1.0-dev-20260429_0111"
            download-url = "https://example.invalid/graalvm-dev.tar.gz"
            """);

        PyprojectModel model = reader.readFile(file);
        assertEquals("dev", model.pyronaut().toolchain().distribution());
        assertEquals("native", model.pyronaut().toolchain().type());
        assertEquals("25.1.0-dev+10.1", model.pyronaut().toolchain().version());
        assertEquals(Integer.valueOf(25), model.pyronaut().toolchain().javaVersion());
        assertEquals("jdk-25.1.0-dev-20260429_0111", model.pyronaut().toolchain().releaseTag());
        assertEquals("https://example.invalid/graalvm-dev.tar.gz", model.pyronaut().toolchain().downloadUrl());
    }

    @Test
    void parseToolchainCamelCaseAliases() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [build-system]
            requires = ["setuptools"]
            build-backend = "setuptools.build_meta"

            [tool.pyronaut]

            [tool.pyronaut.toolchain]
            distribution = "ee"
            javaVersion = 25
            releaseTag = "jdk-25.1.0-dev-20260429_0111"
            downloadUrl = "https://example.invalid/graalvm-ee.tar.gz"
            """);

        PyprojectModel model = reader.readFile(file);
        assertEquals("ee", model.pyronaut().toolchain().distribution());
        assertEquals("jvm", model.pyronaut().toolchain().type());
        assertEquals(Integer.valueOf(25), model.pyronaut().toolchain().javaVersion());
        assertEquals("jdk-25.1.0-dev-20260429_0111", model.pyronaut().toolchain().releaseTag());
        assertEquals("https://example.invalid/graalvm-ee.tar.gz", model.pyronaut().toolchain().downloadUrl());
    }

    @Test
    void rejectInvalidToolchainTypeValue() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut.toolchain]
            type = "jit"
            """);

        PyprojectModelException exception = assertThrows(PyprojectModelException.class, () -> reader.readFile(file));
        assertEquals("Invalid value for 'tool.pyronaut.toolchain.type': expected one of [jvm, native]", exception.getMessage());
    }

    @Test
    void rejectInvalidTestModeType() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut]

            [tool.pyronaut.test]
            mode = ["native"]
            """);

        PyprojectModelException exception = assertThrows(PyprojectModelException.class, () -> reader.readFile(file));
        assertEquals("Invalid type for 'tool.pyronaut.test.mode': expected string", exception.getMessage());
    }

    @Test
    void rejectInvalidTestModeValue() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut]

            [tool.pyronaut.test]
            mode = "fast"
            """);

        PyprojectModelException exception = assertThrows(PyprojectModelException.class, () -> reader.readFile(file));
        assertEquals("Invalid value for 'tool.pyronaut.test.mode': expected one of [jvm, native]", exception.getMessage());
    }

    @Test
    void parseBuildMetadataConfiguration() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut]

            [tool.pyronaut.build]
            mode = "native"

            [tool.pyronaut.build.metadata]
            enabled = false
            version = "0.9.0"
            repository-url = "https://example.test/metadata.zip"
            excluded-modules = ["org.slf4j:slf4j-api", "ch.qos.logback:logback-classic"]
            """);

        PyprojectModel model = reader.readFile(file);
        assertEquals("native", model.pyronaut().build().mode());
        assertEquals(Boolean.FALSE, model.pyronaut().build().metadata().enabled());
        assertEquals("0.9.0", model.pyronaut().build().metadata().version());
        assertEquals("https://example.test/metadata.zip", model.pyronaut().build().metadata().repositoryUrl());
        assertEquals(List.of("org.slf4j:slf4j-api", "ch.qos.logback:logback-classic"), model.pyronaut().build().metadata().excludedModules());
    }

    @Test
    void parseBuildDockerConfiguration() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut]

            [tool.pyronaut.build]
            base-image = "build/pyronaut-base/pyronaut-run"

            [tool.pyronaut.build.docker]
            image-name = "example/demo"
            dockerfile = "docker/Dockerfile.jvm"
            dockerfile-native = "docker/Dockerfile.native"
            jvm-base-image = "container-registry.oracle.com/graalvm/jdk:25"
            native-builder-image = "container-registry.oracle.com/graalvm/native-image:25"
            native-base-image = "gcr.io/distroless/base"
            static-native-builder-image = "container-registry.oracle.com/graalvm/native-image:25-muslib"
            static-native-base-image = "scratch"
            base-image = "registry.example.com/acme/pyronaut-runtime:1"
            """);

        PyprojectModel model = reader.readFile(file);
        assertEquals("build/pyronaut-base/pyronaut-run", model.pyronaut().build().baseImage());
        assertEquals("example/demo", model.pyronaut().build().docker().imageName());
        assertEquals("docker/Dockerfile.jvm", model.pyronaut().build().docker().dockerfile());
        assertEquals("docker/Dockerfile.native", model.pyronaut().build().docker().dockerfileNative());
        assertEquals("container-registry.oracle.com/graalvm/jdk:25", model.pyronaut().build().docker().jvmBaseImage());
        assertEquals("container-registry.oracle.com/graalvm/native-image:25", model.pyronaut().build().docker().nativeBuilderImage());
        assertEquals("gcr.io/distroless/base", model.pyronaut().build().docker().nativeBaseImage());
        assertEquals("container-registry.oracle.com/graalvm/native-image:25-muslib", model.pyronaut().build().docker().staticNativeBuilderImage());
        assertEquals("scratch", model.pyronaut().build().docker().staticNativeBaseImage());
        assertEquals("registry.example.com/acme/pyronaut-runtime:1", model.pyronaut().build().docker().baseImage());
    }

    @Test
    void parseBuildDockerCamelCaseAliases() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut]

            [tool.pyronaut.build]
            [tool.pyronaut.build.docker]
            imageName = "example/demo"
            dockerfileNative = "DockerfileNative"
            jvmBaseImage = "example/jvm"
            nativeBuilderImage = "example/builder"
            nativeBaseImage = "example/native"
            staticNativeBuilderImage = "example/static-builder"
            staticNativeBaseImage = "example/static-native"
            baseImage = "example/runtime"
            """);

        PyprojectModel model = reader.readFile(file);
        assertEquals(null, model.pyronaut().build().baseImage());
        assertEquals("example/demo", model.pyronaut().build().docker().imageName());
        assertEquals("DockerfileNative", model.pyronaut().build().docker().dockerfileNative());
        assertEquals("example/jvm", model.pyronaut().build().docker().jvmBaseImage());
        assertEquals("example/builder", model.pyronaut().build().docker().nativeBuilderImage());
        assertEquals("example/native", model.pyronaut().build().docker().nativeBaseImage());
        assertEquals("example/static-builder", model.pyronaut().build().docker().staticNativeBuilderImage());
        assertEquals("example/static-native", model.pyronaut().build().docker().staticNativeBaseImage());
        assertEquals("example/runtime", model.pyronaut().build().docker().baseImage());
    }

    @Test
    void parseIdeStubConfiguration() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut]

            [tool.pyronaut.ide-stubs]
            enabled = false
            ide = "pycharm"
            packages = ["io.micronaut.http", "jakarta.inject"]
            exclude-patterns = ["*ModuleInfo", "io.micronaut.http.internal.*"]
            destination-dir = "__pyronaut__/custom-stubs"
            """);

        PyprojectModel model = reader.readFile(file);
        assertEquals(Boolean.FALSE, model.pyronaut().ideStubs().enabled());
        assertEquals("pycharm", model.pyronaut().ideStubs().ide());
        assertEquals(List.of("io.micronaut.http", "jakarta.inject"), model.pyronaut().ideStubs().packages());
        assertEquals(List.of("*ModuleInfo", "io.micronaut.http.internal.*"), model.pyronaut().ideStubs().excludePatterns());
        assertEquals("__pyronaut__/custom-stubs", model.pyronaut().ideStubs().destinationDir());
    }

    @Test
    void rejectInvalidBuildMetadataEnabledType() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut]

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

            [tool.pyronaut.validation]
            enabled = true
            fail-on-not-present = false
            deduce-environments = true
            validate-dependency-injection = true
            dependency-injection-validation-strategy = "all-beans"
            format = "json"
            suppressions = ["micronaut.config.deprecated"]
            suppress-inject-errors = ["missing.bean"]
            project-base-dir = "src"
            resources-dirs = ["src/main/resources"]

            [tool.pyronaut.validation.run]
            environments = ["dev", "cloud"]
            include-default-environment = false
            override-classpath = true
            classpath = ["/tmp/run-cp"]
            additional-classpath = ["/tmp/additional"]
            resources-dirs = ["src/run/resources"]
            output-dir = "build/reports/run"
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

            [tool.pyronaut.validation]
            fail-on-not-present = "yes"
            """);

        PyprojectModelException exception = assertThrows(PyprojectModelException.class, () -> reader.readFile(file));
        assertEquals("Invalid type for 'tool.pyronaut.validation.fail-on-not-present': expected boolean", exception.getMessage());
    }

    @Test
    void parseTestResourcesConfiguration() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut]

            [tool.pyronaut.test-resources]
            enabled = true
            version = "2.9.0"
            explicit-port = 18081
            infer-classpath = false
            additional-modules = ["jdbc-postgresql"]
            client-timeout = 90
            shared-server = true
            shared-server-namespace = "demo"
            logs-dir = "var/test-resources-logs"
            server-idle-timeout-minutes = 15
            debug-server = true
            java-executable = "/opt/jdk/bin/java"
            startup-optimization = "leyden"
            leyden-jvm-args = ["--enable-preview", "-XX:+UseFastUnorderedTimeStamps"]

            [tool.pyronaut.test-resources.server-system-properties]
            "micronaut.server.host" = "127.0.0.1"

            [tool.pyronaut.test-resources.server-environment]
            "TEST_RESOURCES_MODE" = "standalone"
            """);

        PyprojectModel model = reader.readFile(file);
        PyprojectModel.TestResources testResources = model.pyronaut().testResources();
        assertEquals(true, testResources.configured());
        assertEquals(Boolean.TRUE, testResources.enabled());
        assertEquals("2.9.0", testResources.version());
        assertEquals(Integer.valueOf(18081), testResources.explicitPort());
        assertEquals(Boolean.FALSE, testResources.inferClasspath());
        assertEquals(List.of("jdbc-postgresql"), testResources.additionalModules());
        assertEquals(Integer.valueOf(90), testResources.clientTimeout());
        assertEquals(Boolean.TRUE, testResources.sharedServer());
        assertEquals("demo", testResources.sharedServerNamespace());
        assertEquals("var/test-resources-logs", testResources.logsDir());
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

            [tool.pyronaut.test-resources]
            client-timeout = "fast"
            """);

        PyprojectModelException exception = assertThrows(PyprojectModelException.class, () -> reader.readFile(file));
        assertEquals("Invalid type for 'tool.pyronaut.test-resources.client-timeout': expected integer", exception.getMessage());
    }

    @Test
    void parseCamelCaseAliasesForCompatibility() throws IOException {
        Path file = tempDir.resolve("pyproject.toml");
        Files.writeString(file, """
            [project]
            name = "demo"

            [tool.pyronaut]

            [tool.pyronaut.build.metadata]
            repositoryUrl = "https://example.test/metadata.zip"
            excludedModules = ["org.slf4j:slf4j-api"]

            [tool.pyronaut.validation]
            failOnNotPresent = false
            validateDependencyInjection = true
            dependencyInjectionValidationStrategy = "all-beans"

            [tool.pyronaut.testResources]
            explicitPort = 18081
            inferClasspath = false
            clientTimeout = 90
            sharedServer = true
            sharedServerNamespace = "demo"
            logsDir = "logs"
            serverIdleTimeoutMinutes = 15
            debugServer = true
            javaExecutable = "/opt/jdk/bin/java"
            startupOptimization = "leyden"
            leydenJvmArgs = ["--enable-preview"]

            [tool.pyronaut.testResources.serverSystemProperties]
            "micronaut.server.host" = "127.0.0.1"
            """);

        PyprojectModel model = reader.readFile(file);
        assertEquals("https://example.test/metadata.zip", model.pyronaut().build().metadata().repositoryUrl());
        assertEquals(List.of("org.slf4j:slf4j-api"), model.pyronaut().build().metadata().excludedModules());
        assertEquals(Boolean.FALSE, model.pyronaut().validation().failOnNotPresent());
        assertEquals(Boolean.TRUE, model.pyronaut().validation().validateDependencyInjection());
        assertEquals("all-beans", model.pyronaut().validation().dependencyInjectionValidationStrategy());
        assertEquals(Boolean.TRUE, model.pyronaut().testResources().configured());
        assertEquals(Integer.valueOf(18081), model.pyronaut().testResources().explicitPort());
        assertEquals(Boolean.FALSE, model.pyronaut().testResources().inferClasspath());
        assertEquals(Integer.valueOf(90), model.pyronaut().testResources().clientTimeout());
        assertEquals(Boolean.TRUE, model.pyronaut().testResources().sharedServer());
        assertEquals("demo", model.pyronaut().testResources().sharedServerNamespace());
        assertEquals("logs", model.pyronaut().testResources().logsDir());
        assertEquals(Integer.valueOf(15), model.pyronaut().testResources().serverIdleTimeoutMinutes());
        assertEquals(Boolean.TRUE, model.pyronaut().testResources().debugServer());
        assertEquals("/opt/jdk/bin/java", model.pyronaut().testResources().javaExecutable());
        assertEquals("leyden", model.pyronaut().testResources().startupOptimization());
        assertEquals(List.of("--enable-preview"), model.pyronaut().testResources().leydenJvmArgs());
        assertEquals(Map.of("micronaut.server.host", "127.0.0.1"), model.pyronaut().testResources().serverSystemProperties());
    }
}

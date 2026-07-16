package io.micronaut.pyronaut.test;

import io.micronaut.pyronaut.config.model.PyprojectModelReader;
import io.micronaut.pyronaut.config.model.ExternalProjectLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PyronautTestMainTest {

    @TempDir
    Path tempDir;

    private PyronautTestMain newCommand() {
        return new PyronautTestMain(new PyprojectModelReader(), (classLoader, applicationMain) -> { });
    }

    @Test
    void launcherRuntimeCanResolveContextPythonHttpHelpers() {
        assertDoesNotThrow(() ->
            Class.forName("io.micronaut.http.HttpResponse", false, PyronautTestMain.class.getClassLoader())
        );
    }

    @Test
    void launcherRuntimeCanResolveReactorMicrometerContextAccessor() {
        assertDoesNotThrow(() ->
            Class.forName("reactor.util.context.ReactorContextAccessor", false, PyronautTestMain.class.getClassLoader())
        );
    }

    @Test
    void executesPassingSelectedClass() throws Exception {
        Path project = setupProject();
        PyronautTestMain command = newCommand();
        command.projectDir = project;
        command.selectClasses = java.util.List.of(PassingTest.class.getName());

        assertEquals(0, command.call());
    }

    @Test
    void enablesContextClassLoaderIntrospectionsDuringTestExecution() throws Exception {
        String property = "micronaut.introspections.use.context.classloader";
        String previous = System.getProperty(property);
        System.clearProperty(property);
        try {
            Path project = setupProject();
            AtomicReference<String> propertyDuringBootstrap = new AtomicReference<>();
            PyronautTestMain command = new PyronautTestMain(
                new PyprojectModelReader(),
                (classLoader, applicationMain) -> propertyDuringBootstrap.set(System.getProperty(property))
            );
            command.projectDir = project;
            command.selectClasses = java.util.List.of(PassingTest.class.getName());

            assertEquals(0, command.call());
            assertEquals("true", propertyDuringBootstrap.get());
            assertFalse(System.getProperties().containsKey(property));
        } finally {
            restoreProperty(property, previous);
        }
    }

    @Test
    void defaultsTestServerToRandomPortDuringTestExecution() throws Exception {
        String property = "micronaut.server.port";
        String previous = System.getProperty(property);
        System.clearProperty(property);
        try {
            Path project = setupProject();
            AtomicReference<String> propertyDuringBootstrap = new AtomicReference<>();
            PyronautTestMain command = new PyronautTestMain(
                new PyprojectModelReader(),
                (classLoader, applicationMain) -> propertyDuringBootstrap.set(System.getProperty(property))
            );
            command.projectDir = project;
            command.selectClasses = java.util.List.of(PassingTest.class.getName());

            assertEquals(0, command.call());
            assertEquals("0", propertyDuringBootstrap.get());
            assertFalse(System.getProperties().containsKey(property));
        } finally {
            restoreProperty(property, previous);
        }
    }

    @Test
    void preservesExplicitTestServerPortDuringTestExecution() throws Exception {
        String property = "micronaut.server.port";
        String previous = System.getProperty(property);
        System.setProperty(property, "9090");
        try {
            Path project = setupProject();
            AtomicReference<String> propertyDuringBootstrap = new AtomicReference<>();
            PyronautTestMain command = new PyronautTestMain(
                new PyprojectModelReader(),
                (classLoader, applicationMain) -> propertyDuringBootstrap.set(System.getProperty(property))
            );
            command.projectDir = project;
            command.selectClasses = java.util.List.of(PassingTest.class.getName());

            assertEquals(0, command.call());
            assertEquals("9090", propertyDuringBootstrap.get());
            assertEquals("9090", System.getProperty(property));
        } finally {
            restoreProperty(property, previous);
        }
    }

    @Test
    void defaultsLoggerConfigDuringTestExecution() throws Exception {
        String property = "logger.config";
        String previous = System.getProperty(property);
        String previousLogbackConfigurationFile = System.getProperty("logback.configurationFile");
        System.clearProperty(property);
        System.clearProperty("logback.configurationFile");
        try {
            Path project = setupProject();
            AtomicReference<String> propertyDuringBootstrap = new AtomicReference<>();
            PyronautTestMain command = new PyronautTestMain(
                new PyprojectModelReader(),
                (classLoader, applicationMain) -> propertyDuringBootstrap.set(System.getProperty(property))
            );
            command.projectDir = project;
            command.selectClasses = java.util.List.of(PassingTest.class.getName());

            assertEquals(0, command.call());
            assertEquals("pyronaut-default-logback.xml", propertyDuringBootstrap.get());
            assertFalse(System.getProperties().containsKey(property));
        } finally {
            restoreProperty(property, previous);
            restoreProperty("logback.configurationFile", previousLogbackConfigurationFile);
        }
    }

    @Test
    void preservesExplicitLoggerConfigDuringTestExecution() throws Exception {
        String property = "logger.config";
        String previous = System.getProperty(property);
        System.setProperty(property, "custom-logback.xml");
        try {
            Path project = setupProject();
            AtomicReference<String> propertyDuringBootstrap = new AtomicReference<>();
            PyronautTestMain command = new PyronautTestMain(
                new PyprojectModelReader(),
                (classLoader, applicationMain) -> propertyDuringBootstrap.set(System.getProperty(property))
            );
            command.projectDir = project;
            command.selectClasses = java.util.List.of(PassingTest.class.getName());

            assertEquals(0, command.call());
            assertEquals("custom-logback.xml", propertyDuringBootstrap.get());
            assertEquals("custom-logback.xml", System.getProperty(property));
        } finally {
            restoreProperty(property, previous);
        }
    }

    @Test
    void preservesExplicitLogbackConfigurationFileDuringTestExecution() throws Exception {
        String loggerConfig = "logger.config";
        String logbackConfigurationFile = "logback.configurationFile";
        String previousLoggerConfig = System.getProperty(loggerConfig);
        String previousLogbackConfigurationFile = System.getProperty(logbackConfigurationFile);
        System.clearProperty(loggerConfig);
        System.setProperty(logbackConfigurationFile, "custom-file-logback.xml");
        try {
            Path project = setupProject();
            AtomicReference<String> loggerConfigDuringBootstrap = new AtomicReference<>();
            PyronautTestMain command = new PyronautTestMain(
                new PyprojectModelReader(),
                (classLoader, applicationMain) -> loggerConfigDuringBootstrap.set(System.getProperty(loggerConfig))
            );
            command.projectDir = project;
            command.selectClasses = java.util.List.of(PassingTest.class.getName());

            assertEquals(0, command.call());
            assertNull(loggerConfigDuringBootstrap.get());
            assertFalse(System.getProperties().containsKey(loggerConfig));
            assertEquals("custom-file-logback.xml", System.getProperty(logbackConfigurationFile));
        } finally {
            restoreProperty(loggerConfig, previousLoggerConfig);
            restoreProperty(logbackConfigurationFile, previousLogbackConfigurationFile);
        }
    }

    @Test
    void returnsFailureCodeForFailingClass() throws Exception {
        Path project = setupProject();
        PyronautTestMain command = newCommand();
        command.projectDir = project;
        command.selectClasses = java.util.List.of("io.micronaut.pyronaut.test.DoesNotExist");

        assertEquals(7, command.call());
    }

    @Test
    void failsWhenProcessedClassesDirectoryMissing() throws Exception {
        Path project = tempDir.resolve("project-missing-classes");
        Files.createDirectories(project.resolve("__pyronaut__/classes"));
        Files.delete(project.resolve("__pyronaut__/classes"));
        writeMinimalPyproject(project);
        PyronautTestMain command = newCommand();
        command.projectDir = project;
        command.selectClasses = java.util.List.of(PassingTest.class.getName());

        assertEquals(8, command.call());
    }

    @Test
    void returnsFailureWhenSelectedClassIsNotOnProcessClasspathEvenIfGeneratedUnderTestClasses() throws Exception {
        Path project = setupProject();
        Path testClasses = project.resolve("__pyronaut__/test-classes");
        Files.createDirectories(testClasses);
        compileGeneratedTestClass(testClasses);

        PyronautTestMain command = newCommand();
        command.projectDir = project;
        command.selectClasses = java.util.List.of("generated.GeneratedPassingTest");

        assertEquals(0, command.call());
    }

    @Test
    void returnsFailureWhenSelectedClassIsNotOnProcessClasspathEvenIfGeneratedUnderClasses() throws Exception {
        Path project = setupProject();
        compileGeneratedTestClass(project.resolve("__pyronaut__/classes"));

        PyronautTestMain command = newCommand();
        command.projectDir = project;
        command.selectClasses = java.util.List.of("generated.GeneratedPassingTest");

        assertEquals(0, command.call());
    }

    @Test
    void excludesClassesDirectoryWhenTestClassesDirectoryIsPresent() throws Exception {
        Path project = setupProject();
        compileGeneratedTestClass(project.resolve("__pyronaut__/classes"));
        Files.createDirectories(project.resolve("__pyronaut__/test-classes"));

        PyronautTestMain command = newCommand();
        command.projectDir = project;
        command.selectClasses = java.util.List.of("generated.GeneratedPassingTest");

        assertEquals(7, command.call());
    }

    @Test
    void acceptsDebugVmFlag() {
        assertDoesNotThrow(() -> new picocli.CommandLine(new PyronautTestMain()).execute("--debug-vm", "--help"));
    }

    @Test
    void acceptsRepeatableTestsSelectors() {
        PyronautTestMain main = new PyronautTestMain();
        var value = PyronautTestMain.buildPytestTestsParameter(java.util.List.of("tests/test_math.py::test_add", "*test_*"));
        assertEquals(java.util.Optional.of("tests/test_math.py::test_add|*test_*"), value);
    }

    @Test
    void rejectsBlankTestsSelectorWithDeterministicMessage() {
        IllegalStateException e = assertThrows(
            IllegalStateException.class,
            () -> PyronautTestMain.buildPytestTestsParameter(java.util.List.of(" "))
        );
        assertEquals("Invalid --tests selector: blank value", e.getMessage());
    }

    @Test
    void resolvesPlainTestsSelectorToFileInTestsDirectory() throws Exception {
        Path project = tempDir.resolve("selector-project");
        Path testsDir = project.resolve("tests");
        Files.createDirectories(testsDir);
        Path selected = testsDir.resolve("test_mycontroller.py");
        Files.writeString(selected, "def test_ok():\n  assert True\n", StandardCharsets.UTF_8);

        List<Path> files = PyronautTestMain.resolveDirectTestFileSelectors(project, testsDir, List.of("test_mycontroller"));
        assertEquals(List.of(selected), files);
    }

    @Test
    void resolvesNodeIdSelectorToFilePath() throws Exception {
        Path project = tempDir.resolve("selector-nodeid-project");
        Path testsDir = project.resolve("tests");
        Files.createDirectories(testsDir);
        Path selected = testsDir.resolve("test_mycontroller.py");
        Files.writeString(selected, "def test_ok():\n  assert True\n", StandardCharsets.UTF_8);

        List<Path> files = PyronautTestMain.resolveDirectTestFileSelectors(project, testsDir, List.of("tests/test_mycontroller.py::test_ok"));
        assertEquals(List.of(selected), files);
    }

    @Test
    void wildcardTestsSelectorDoesNotForceFileSelection() throws Exception {
        Path project = tempDir.resolve("selector-wildcard-project");
        Path testsDir = project.resolve("tests");
        Files.createDirectories(testsDir);
        Files.writeString(testsDir.resolve("test_mycontroller.py"), "def test_ok():\n  assert True\n", StandardCharsets.UTF_8);

        List<Path> files = PyronautTestMain.resolveDirectTestFileSelectors(project, testsDir, List.of("*mycontroller*"));
        assertTrue(files.isEmpty());
    }

    @Test
    void mixedSelectorsAreNotTreatedAsOnlyDirectFiles() {
        assertTrue(PyronautTestMain.hasOnlyDirectFileSelectors(List.of("test_mycontroller", "tests/test_a.py::test_x")));
        assertFalse(PyronautTestMain.hasOnlyDirectFileSelectors(List.of("test_mycontroller", "*integration*")));
    }

    @Test
    void selectsTestsBootstrapWhenRootTestsScriptExists() throws Exception {
        Path testsDir = tempDir.resolve("tests-bootstrap");
        Files.createDirectories(testsDir);
        Files.writeString(testsDir.resolve("tests.py"), "print('test bootstrap')\n", StandardCharsets.UTF_8);

        assertEquals("tests.py", PyronautTestMain.selectApplicationMain(testsDir));
    }

    @Test
    void selectsApplicationMainWhenRootTestsScriptDoesNotExist() throws Exception {
        Path testsDir = tempDir.resolve("default-bootstrap");
        Files.createDirectories(testsDir);

        assertEquals("main.py", PyronautTestMain.selectApplicationMain(testsDir));
    }

    @Test
    void prefersProcessedTestSourcesForPytestExecution() throws Exception {
        Path project = tempDir.resolve("processed-test-source-project");
        Path originalTests = project.resolve("tests");
        Path processedTests = project.resolve("__pyronaut__/test-sources");
        Files.createDirectories(originalTests);
        Files.createDirectories(processedTests);

        assertEquals(
            processedTests.toAbsolutePath().normalize(),
            PyronautTestMain.resolvePytestSourceDir(project, originalTests)
        );
    }

    @Test
    void mapsDirectTestFileSelectorsToProcessedTestSourcesWhenAvailable() throws Exception {
        Path project = tempDir.resolve("processed-test-selector-project");
        Path originalTests = project.resolve("tests");
        Path processedTests = project.resolve("__pyronaut__/test-sources");
        Path originalFile = originalTests.resolve("example/micronaut/test_fruit.py");
        Path processedFile = processedTests.resolve("example/micronaut/test_fruit.py");
        Files.createDirectories(originalFile.getParent());
        Files.createDirectories(processedFile.getParent());
        Files.writeString(originalFile, "def test_original():\n  assert True\n", StandardCharsets.UTF_8);
        Files.writeString(processedFile, "def test_processed():\n  assert True\n", StandardCharsets.UTF_8);

        List<Path> mapped = PyronautTestMain.mapToPytestSourceFiles(originalTests, processedTests, List.of(originalFile));

        assertEquals(List.of(processedFile), mapped);
    }

    @Test
    void clearsLegacyReportAliasesBeforeExecution() throws Exception {
        Path project = tempDir.resolve("legacy-report-clean-project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("junit.xml"), "<testsuite/>", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("index.html"), "<html></html>", StandardCharsets.UTF_8);
        Files.writeString(project.resolve(".pyronaut-last-nodeid.txt"), "tests/test_a.py::test_ok\n", StandardCharsets.UTF_8);

        PyronautTestMain.clearLegacyReportAliases(project);

        assertFalse(Files.exists(project.resolve("junit.xml")));
        assertFalse(Files.exists(project.resolve("index.html")));
        assertFalse(Files.exists(project.resolve(".pyronaut-last-nodeid.txt")));
    }

    @Test
    void reportLocationsOutputMentionsReportsDirectoryAndHtmlOnly() throws Exception {
        Path project = tempDir.resolve("report-links-project");
        Files.createDirectories(project.resolve("__pyronaut__/reports/tests"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            PyronautTestMain.publishReportLocations(project);
        } finally {
            System.setOut(original);
        }

        String output = out.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Test reports directory:"));
        assertTrue(output.contains("HTML report:"));
        assertTrue(output.contains("\u001b]8;;file:"));
        assertTrue(output.contains("\u001b\\"));
        assertFalse(output.contains("junit.xml"));
    }

    @Test
    void appliesTestResourcesPropertiesFromEnvironment() {
        String previousUri = System.getProperty("micronaut.test.resources.server.uri");
        String previousToken = System.getProperty("micronaut.test.resources.server.access.token");
        String previousTimeout = System.getProperty("micronaut.test.resources.server.client.read.timeout");
        System.clearProperty("micronaut.test.resources.server.uri");
        System.clearProperty("micronaut.test.resources.server.access.token");
        System.clearProperty("micronaut.test.resources.server.client.read.timeout");
        try {
            PyronautTestMain.applyTestResourcesProperties(Map.of(
                "MICRONAUT_TEST_RESOURCES_SERVER_URI", "http://localhost:18080",
                "MICRONAUT_TEST_RESOURCES_SERVER_ACCESS_TOKEN", "token-123",
                "MICRONAUT_TEST_RESOURCES_SERVER_CLIENT_READ_TIMEOUT", "60"
            ));

            assertEquals("http://localhost:18080", System.getProperty("micronaut.test.resources.server.uri"));
            assertEquals("token-123", System.getProperty("micronaut.test.resources.server.access.token"));
            assertEquals("60", System.getProperty("micronaut.test.resources.server.client.read.timeout"));
        } finally {
            restoreProperty("micronaut.test.resources.server.uri", previousUri);
            restoreProperty("micronaut.test.resources.server.access.token", previousToken);
            restoreProperty("micronaut.test.resources.server.client.read.timeout", previousTimeout);
        }
    }

    private Path setupProject() throws Exception {
        Path project = tempDir.resolve("project");
        Path classes = project.resolve("__pyronaut__/classes");
        Files.createDirectories(classes);
        writeMinimalPyproject(project);
        return project;
    }

    @Test
    void resolveProjectLayoutIncludesConfiguredTestResourcesDirectory() throws Exception {
        Path project = tempDir.resolve("project-layout");
        Path classesDir = project.resolve("__pyronaut__/classes");
        Path configDir = project.resolve("app-config");
        Path viewsDir = project.resolve("views");
        Path testResourcesDir = project.resolve("src/integration/resources");
        Path testFixturesDir = project.resolve("src/integration/fixtures");
        String fixtureVersion = "fixture-version";
        Path bundledPytestJar = launcherProvidedArtifact(project, "micronaut-pyronaut-pytest");
        Path bundledLogbackJar = launcherProvidedArtifact(project, "micronaut-pyronaut-logback");
        Path contextJar = mavenArtifact(project, "io/micronaut", "micronaut-context", fixtureVersion);
        Path bundledHttpNettyJar = launcherProvidedArtifact(project, "micronaut-http-netty");
        Path bundledNettyJar = project.resolve("__pyronaut__/launcher-provided/netty-transport-fixture.jar");
        Path testHttpClientJar = mavenArtifact(project, "io/micronaut", "micronaut-http-client", fixtureVersion);
        Files.createDirectories(classesDir);
        Files.createDirectories(configDir);
        Files.createDirectories(viewsDir);
        Files.createDirectories(testResourcesDir);
        Files.createDirectories(testFixturesDir);
        Files.createDirectories(project.resolve("__pyronaut__"));
        Files.createDirectories(bundledPytestJar.getParent());
        Files.createDirectories(bundledLogbackJar.getParent());
        Files.createDirectories(contextJar.getParent());
        Files.createDirectories(bundledHttpNettyJar.getParent());
        Files.createDirectories(bundledNettyJar.getParent());
        Files.createDirectories(testHttpClientJar.getParent());
        Files.writeString(bundledPytestJar, "", StandardCharsets.UTF_8);
        Files.writeString(bundledLogbackJar, "", StandardCharsets.UTF_8);
        Files.writeString(contextJar, "", StandardCharsets.UTF_8);
        Files.writeString(bundledHttpNettyJar, "", StandardCharsets.UTF_8);
        Files.writeString(bundledNettyJar, "", StandardCharsets.UTF_8);
        Files.writeString(testHttpClientJar, "", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("__pyronaut__/resolved-test-dependencies"), "/tmp/test.jar\n" + bundledPytestJar + "\n" + testHttpClientJar + "\n", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("__pyronaut__/resolved-runtime-dependencies"), "/tmp/runtime.jar\n" + bundledLogbackJar + "\n" + contextJar + "\n" + bundledHttpNettyJar + "\n" + bundledNettyJar + "\n", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("__pyronaut__/resolved-build-dependencies"), "/tmp/build.jar\n", StandardCharsets.UTF_8);

        PyronautTestMain.ResolvedProjectLayout layout = PyronautTestMain.resolveProjectLayout(
            project,
            Path.of("__pyronaut__/classes"),
            Path.of("__pyronaut__/test-classes"),
            configDir,
            testResourcesDir,
            List.of(viewsDir),
            List.of(testFixturesDir)
        );

        List<String> urls = layout.classpathUrls().stream().map(Object::toString).toList();
        assertTrue(urls.stream().anyMatch(url -> url.contains("app-config")));
        assertTrue(urls.stream().anyMatch(url -> url.contains("views")));
        assertTrue(urls.stream().anyMatch(url -> url.contains("src/integration/resources")));
        assertTrue(urls.stream().anyMatch(url -> url.contains("src/integration/fixtures")));
        assertTrue(urls.stream().anyMatch(url -> url.contains("micronaut-context")));
        assertTrue(urls.stream().noneMatch(url -> url.contains("micronaut-pyronaut-pytest")));
        assertTrue(urls.stream().noneMatch(url -> url.contains("micronaut-pyronaut-logback")));
        assertTrue(urls.stream().noneMatch(url -> url.contains("micronaut-http-netty")));
        assertTrue(urls.stream().anyMatch(url -> url.contains("netty-transport")));
        assertTrue(urls.stream().anyMatch(url -> url.contains("micronaut-http-client")));
    }

    @Test
    void externalLayoutAddsMainAndTestResources() throws Exception {
        Path project = tempDir.resolve("external-test-layout");
        Files.createDirectories(project.resolve("__pyronaut__/classes"));
        Files.createDirectories(project.resolve("__pyronaut__/test-classes"));
        Path mainResources = Files.createDirectories(project.resolve("src/main/resources"));
        Path testResources = Files.createDirectories(project.resolve("src/test/resources"));
        Files.writeString(project.resolve("build.gradle"), "plugins { id 'java' }");
        ExternalProjectLayout external = new ExternalProjectLayout(
            ExternalProjectLayout.ProjectKind.GRADLE,
            List.of(), List.of(), List.of(mainResources), List.of(testResources),
            List.of(), List.of(), List.of(), List.of(), List.of()
        );
        external.write(project);
        List<String> urls = PyronautTestMain.resolveExternalProjectLayout(
            project, Path.of("__pyronaut__/classes"), Path.of("__pyronaut__/test-classes"), external
        ).classpathUrls().stream().map(Object::toString).toList();
        assertTrue(urls.stream().anyMatch(url -> url.contains("__pyronaut__/classes")));
        assertTrue(urls.stream().anyMatch(url -> url.contains("__pyronaut__/test-classes")));
        assertTrue(urls.stream().anyMatch(url -> url.contains("main/resources")));
        assertTrue(urls.stream().anyMatch(url -> url.contains("test/resources")));
    }

    @Test
    void nativeRuntimeUsesSystemClassLoaderWhenJavaClassPathIsSupplied() {
        String previousNativeImageCode = System.getProperty("org.graalvm.nativeimage.imagecode");
        String previousClasspath = System.getProperty("java.class.path");
        try {
            System.setProperty("org.graalvm.nativeimage.imagecode", "runtime");
            System.setProperty("java.class.path", tempDir.toString());
            PyronautTestMain.ResolvedProjectLayout layout = new PyronautTestMain.ResolvedProjectLayout(tempDir, List.of());

            assertEquals(ClassLoader.getSystemClassLoader(), layout.applicationClassLoader());
        } finally {
            restoreProperty("org.graalvm.nativeimage.imagecode", previousNativeImageCode);
            restoreProperty("java.class.path", previousClasspath);
        }
    }

    @Test
    void uberCliJvmRuntimeUsesSystemClassLoaderWhenJavaClassPathIsSupplied() {
        String previousUseSystemApplicationClassLoader = System.getProperty("pyronaut.use.system.application.classloader");
        String previousClasspath = System.getProperty("java.class.path");
        try {
            System.setProperty("pyronaut.use.system.application.classloader", "true");
            System.setProperty("java.class.path", tempDir.toString());
            PyronautTestMain.ResolvedProjectLayout layout = new PyronautTestMain.ResolvedProjectLayout(tempDir, List.of());

            assertEquals(ClassLoader.getSystemClassLoader(), layout.applicationClassLoader());
        } finally {
            restoreProperty("pyronaut.use.system.application.classloader", previousUseSystemApplicationClassLoader);
            restoreProperty("java.class.path", previousClasspath);
        }
    }

    @Test
    void resolveProjectLayoutSkipsDependenciesAlreadyVisibleToParentClassLoader() throws Exception {
        Path project = tempDir.resolve("project-layout-deduplicated");
        Path classesDir = project.resolve("__pyronaut__/classes");
        Path configDir = project.resolve("config");
        Files.createDirectories(classesDir);
        Files.createDirectories(configDir);
        Path duplicateJar = project.resolve("duplicate.jar");
        Path uniqueJar = project.resolve("unique.jar");
        Files.writeString(duplicateJar, "", StandardCharsets.UTF_8);
        Files.writeString(uniqueJar, "", StandardCharsets.UTF_8);
        Files.writeString(
            project.resolve("__pyronaut__/resolved-runtime-dependencies"),
            duplicateJar.toAbsolutePath().normalize() + "\n" + uniqueJar.toAbsolutePath().normalize() + "\n",
            StandardCharsets.UTF_8
        );

        String previousClasspath = System.getProperty("java.class.path");
        try {
            System.setProperty("java.class.path", duplicateJar.toAbsolutePath().normalize().toString());
            PyronautTestMain.ResolvedProjectLayout layout = PyronautTestMain.resolveProjectLayout(
                project,
                Path.of("__pyronaut__/classes"),
                Path.of("__pyronaut__/test-classes"),
                configDir,
                project.resolve("tests-config")
            );

            List<String> urls = layout.classpathUrls().stream().map(Object::toString).toList();
            assertFalse(urls.stream().anyMatch(url -> url.contains("duplicate.jar")));
            assertTrue(urls.stream().anyMatch(url -> url.contains("unique.jar")));
        } finally {
            restoreProperty("java.class.path", previousClasspath);
        }
    }

    private void compileGeneratedTestClass(Path outputDir) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler available");
        }
        Path sourceDir = tempDir.resolve("generated-src");
        Files.createDirectories(sourceDir);
        Path sourceFile = sourceDir.resolve("GeneratedPassingTest.java");
        Files.writeString(
            sourceFile,
            "package generated;\n"
                + "import org.junit.jupiter.api.Test;\n"
                + "public class GeneratedPassingTest {\n"
                + "  @Test void pass() {}\n"
                + "}\n",
            StandardCharsets.UTF_8
        );
        int exit = compiler.run(
            null,
            null,
            null,
            "-classpath",
            System.getProperty("java.class.path", ""),
            "-d",
            outputDir.toString(),
            sourceFile.toString()
        );
        assertEquals(0, exit);
    }

    private static void writeMinimalPyproject(Path project) throws Exception {
        Files.createDirectories(project);
        Files.writeString(
            project.resolve("pyproject.toml"),
            """
                [project]
                name = "demo"
                version = "1.0.0"

                [tool.pyronaut]
                repositories = ["mavenCentral"]

                [tool.pyronaut.dependencies]
                runtime = []
                build = []
                test = []
                """,
            StandardCharsets.UTF_8
        );
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private static Path launcherProvidedArtifact(Path project, String artifactId) {
        return project.resolve("__pyronaut__/launcher-provided")
            .resolve(artifactId)
            .resolve(artifactId + ".jar");
    }

    private static Path mavenArtifact(Path project, String groupPath, String artifactId, String version) {
        return project.resolve("__pyronaut__/m2-repository")
            .resolve(groupPath)
            .resolve(artifactId)
            .resolve(version)
            .resolve(artifactId + "-" + version + ".jar");
    }

    public static final class PassingTest {
        @Test
        void pass() {
        }
    }

}

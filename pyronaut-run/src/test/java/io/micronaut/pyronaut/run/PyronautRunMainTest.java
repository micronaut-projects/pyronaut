package io.micronaut.pyronaut.run;

import io.micronaut.pyronaut.config.model.PyprojectModelReader;
import io.micronaut.pyronaut.config.model.ExternalProjectLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class PyronautRunMainTest {

    @TempDir
    Path tempDir;

    @Test
    void enablesContextClassLoaderIntrospectionsWhileApplicationRuns() throws Exception {
        String property = "micronaut.introspections.use.context.classloader";
        String previous = System.getProperty(property);
        System.clearProperty(property);
        try {
            Path project = tempDir.resolve("project-introspections");
            Files.createDirectories(project.resolve("__pyronaut__/classes"));
            writeMinimalPyproject(project);

            AtomicReference<String> propertyDuringStart = new AtomicReference<>();
            PyronautRunMain runMain = new PyronautRunMain(
                new PyprojectModelReader(),
                classLoader -> { },
                (loadedClass, appArgs) -> {
                    propertyDuringStart.set(System.getProperty(property));
                    return false;
                }
            );
            runMain.projectDir = project;

            assertEquals(0, runMain.call());
            assertEquals("true", propertyDuringStart.get());
            assertFalse(System.getProperties().containsKey(property));
        } finally {
            restoreProperty(property, previous);
        }
    }

    @Test
    void failsWhenProcessedClassesDirectoryMissing() throws Exception {
        Path project = tempDir.resolve("project-missing-classes");
        Files.createDirectories(project.resolve("__pyronaut__/classes"));
        Files.delete(project.resolve("__pyronaut__/classes"));
        writeMinimalPyproject(project);

        PyronautRunMain runMain = new PyronautRunMain();
        runMain.projectDir = project;

        assertEquals(8, runMain.call());
    }

    @Test
    void externalLayoutAddsResolvedRuntimeAndMainResources() throws Exception {
        Path project = tempDir.resolve("external-layout");
        Path classes = Files.createDirectories(project.resolve("__pyronaut__/classes"));
        Path resources = Files.createDirectories(project.resolve("src/main/resources"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        ExternalProjectLayout layout = new ExternalProjectLayout(
            ExternalProjectLayout.ProjectKind.MAVEN,
            List.of(), List.of(), List.of(resources), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of()
        );
        layout.write(project);
        List<String> urls = PyronautRunMain.resolveExternalProjectLayout(project, Path.of("__pyronaut__/classes"), layout)
            .classpathUrls().stream().map(Object::toString).toList();
        assertTrue(urls.stream().anyMatch(url -> url.contains("main/resources")));
        assertTrue(urls.stream().anyMatch(url -> url.contains(classes.getFileName().toString())));
    }

    @Test
    void acceptsDebugVmFlag() {
        assertDoesNotThrow(() -> new picocli.CommandLine(new PyronautRunMain()).execute("--debug-vm", "--help"));
    }

    @Test
    void initializesApplicationLoggingDefaultsWhenPythonMainIsAbsent() throws Exception {
        Path project = tempDir.resolve("project-without-python-main");
        Files.createDirectories(project.resolve("__pyronaut__/classes"));
        writeMinimalPyproject(project);

        String previousLoggerConfig = System.getProperty("logger.config");
        String previousLogbackConfigurationFile = System.getProperty("logback.configurationFile");
        System.clearProperty("logger.config");
        System.clearProperty("logback.configurationFile");
        AtomicInteger loggingInitializations = new AtomicInteger();
        AtomicReference<String> loggerConfigDuringStart = new AtomicReference<>();
        try {
            PyronautRunMain runMain = new PyronautRunMain(
                new PyprojectModelReader(),
                classLoader -> { },
                (loadedClass, appArgs) -> {
                    loggerConfigDuringStart.set(System.getProperty("logger.config"));
                    return false;
                },
                loggingInitializations::incrementAndGet
            );
            runMain.projectDir = project;

            assertEquals(0, runMain.call());
            assertEquals(1, loggingInitializations.get());
            assertNull(loggerConfigDuringStart.get());
            assertFalse(System.getProperties().containsKey("logger.config"));
        } finally {
            restoreProperty("logger.config", previousLoggerConfig);
            restoreProperty("logback.configurationFile", previousLogbackConfigurationFile);
        }
    }

    @Test
    void leavesApplicationLoggingToPythonMainWhenPresent() throws Exception {
        Path project = tempDir.resolve("project-with-python-main");
        Path classes = project.resolve("__pyronaut__/classes");
        Files.createDirectories(classes.resolve("META-INF/GRAALPY-VFS/micronaut-application/src"));
        Files.writeString(
            classes.resolve("META-INF/GRAALPY-VFS/micronaut-application/src/main.py"),
            "from logback.config import dictConfig\n",
            StandardCharsets.UTF_8
        );
        writeMinimalPyproject(project);

        String previousLoggerConfig = System.getProperty("logger.config");
        String previousLogbackConfigurationFile = System.getProperty("logback.configurationFile");
        System.clearProperty("logger.config");
        System.clearProperty("logback.configurationFile");
        AtomicInteger loggingInitializations = new AtomicInteger();
        AtomicReference<String> loggerConfigDuringStart = new AtomicReference<>();
        try {
            PyronautRunMain runMain = new PyronautRunMain(
                new PyprojectModelReader(),
                classLoader -> { },
                (loadedClass, appArgs) -> {
                    loggerConfigDuringStart.set(System.getProperty("logger.config"));
                    return false;
                },
                loggingInitializations::incrementAndGet
            );
            runMain.projectDir = project;

            assertEquals(0, runMain.call());
            assertEquals(1, loggingInitializations.get());
            assertNull(loggerConfigDuringStart.get());
            assertFalse(System.getProperties().containsKey("logger.config"));
        } finally {
            restoreProperty("logger.config", previousLoggerConfig);
            restoreProperty("logback.configurationFile", previousLogbackConfigurationFile);
        }
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
            PyronautRunMain.applyTestResourcesProperties(Map.of(
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

    @Test
    void resolveProjectLayoutUsesResolvedRuntimeDependencies() throws Exception {
        Path project = tempDir.resolve("project-layout");
        Path pyronautDir = project.resolve("__pyronaut__");
        Path classesDir = pyronautDir.resolve("classes");
        Path configDir = project.resolve("config");
        Path runtimeJar = tempDir.resolve("runtime.jar");
        Files.createDirectories(classesDir);
        Files.createDirectories(configDir);
        Files.writeString(runtimeJar, "", StandardCharsets.UTF_8);
        Files.createDirectories(pyronautDir);
        Files.writeString(pyronautDir.resolve("resolved-runtime-dependencies"), runtimeJar + "\n", StandardCharsets.UTF_8);

        PyronautRunMain.ResolvedProjectLayout layout = PyronautRunMain.resolveProjectLayout(project, Path.of("__pyronaut__/classes"), Path.of("config"));
        List<String> urls = layout.classpathUrls().stream().map(URL::toString).toList();
        assertEquals(classesDir.toAbsolutePath().normalize(), layout.processedClassesRoot());
        assertTrue(urls.stream().anyMatch(url -> url.contains(runtimeJar.getFileName().toString())));
        assertTrue(urls.stream().anyMatch(url -> url.contains("__pyronaut__/classes/")));
        assertTrue(urls.stream().anyMatch(url -> url.contains("config/")) || urls.stream().anyMatch(url -> url.endsWith("/config")));
    }

    @Test
    void nativeRuntimeUsesApplicationUrlClassLoaderWhenJavaClassPathIsSupplied() throws Exception {
        String previousNativeImageCode = System.getProperty("org.graalvm.nativeimage.imagecode");
        String previousClasspath = System.getProperty("java.class.path");
        try {
            System.setProperty("org.graalvm.nativeimage.imagecode", "runtime");
            System.setProperty("java.class.path", tempDir.toString());
            PyronautRunMain.ResolvedProjectLayout layout = new PyronautRunMain.ResolvedProjectLayout(tempDir, List.of());

            assertTrue(layout.applicationClassLoader() instanceof java.net.URLClassLoader);
            assertEquals(ClassLoader.getSystemClassLoader(), layout.applicationClassLoader().getParent());
        } finally {
            restoreProperty("org.graalvm.nativeimage.imagecode", previousNativeImageCode);
            restoreProperty("java.class.path", previousClasspath);
        }
    }

    @Test
    void resolveProjectLayoutPrefersDevelopmentRuntimeDependencies() throws Exception {
        Path project = tempDir.resolve("project-development-layout");
        Path pyronautDir = project.resolve("__pyronaut__");
        Path classesDir = pyronautDir.resolve("classes");
        Path configDir = project.resolve("config");
        Path runtimeJar = tempDir.resolve("runtime.jar");
        Path developmentRuntimeJar = tempDir.resolve("runtime-dev.jar");
        Files.createDirectories(classesDir);
        Files.createDirectories(configDir);
        Files.writeString(runtimeJar, "", StandardCharsets.UTF_8);
        Files.writeString(developmentRuntimeJar, "", StandardCharsets.UTF_8);
        Files.createDirectories(pyronautDir);
        Files.writeString(pyronautDir.resolve("resolved-runtime-dependencies"), runtimeJar + "\n", StandardCharsets.UTF_8);
        Files.writeString(pyronautDir.resolve("resolved-development-runtime-dependencies"), developmentRuntimeJar + "\n", StandardCharsets.UTF_8);

        PyronautRunMain.ResolvedProjectLayout layout = PyronautRunMain.resolveProjectLayout(project, Path.of("__pyronaut__/classes"), Path.of("config"));
        List<String> urls = layout.classpathUrls().stream()
            .map(URL::toString)
            .toList();
        assertTrue(urls.stream().anyMatch(url -> url.contains(developmentRuntimeJar.getFileName().toString())));
        assertFalse(urls.stream().anyMatch(url -> url.contains(runtimeJar.getFileName().toString())));
    }

    @Test
    void usesConfiguredResourcesDirectoryWhenConfigDirNotOverridden() throws Exception {
        Path project = tempDir.resolve("project-custom-config");
        Path classes = project.resolve("__pyronaut__/classes");
        Files.createDirectories(classes);
        Files.createDirectories(project.resolve("app-config"));
        Files.createDirectories(project.resolve("views"));
        Files.createDirectories(project.resolve("assets"));
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

                [tool.pyronaut.sources]
                resources = "app-config"
                additional-resources = ["views", "assets"]
                """,
            StandardCharsets.UTF_8
        );

        AtomicReference<java.net.URLClassLoader> applicationClassLoader = new AtomicReference<>();
        PyronautRunMain runMain = new PyronautRunMain(
            new PyprojectModelReader(),
            classLoader -> applicationClassLoader.set((java.net.URLClassLoader) classLoader),
            (loadedClass, appArgs) -> false
        );
        runMain.projectDir = project;

        assertEquals(0, runMain.call());
        assertTrue(
            java.util.Arrays.stream(applicationClassLoader.get().getURLs())
                .anyMatch(url -> url.toString().contains("app-config"))
        );
        assertTrue(
            java.util.Arrays.stream(applicationClassLoader.get().getURLs())
                .anyMatch(url -> url.toString().contains("views"))
        );
        assertTrue(
            java.util.Arrays.stream(applicationClassLoader.get().getURLs())
                .anyMatch(url -> url.toString().contains("assets"))
        );
    }

    @Test
    void passesConfiguredBannerSettingToApplicationStarter() throws Exception {
        Path project = tempDir.resolve("project-no-banner");
        Files.createDirectories(project.resolve("__pyronaut__/classes"));
        Files.writeString(
            project.resolve("pyproject.toml"),
            """
                [project]
                name = "demo"
                version = "1.0.0"

                [tool.pyronaut]
                repositories = ["mavenCentral"]

                [tool.pyronaut.run]
                banner-enabled = false

                [tool.pyronaut.dependencies]
                runtime = []
                build = []
                test = []
                """,
            StandardCharsets.UTF_8
        );

        AtomicReference<Boolean> bannerEnabled = new AtomicReference<>();
        PyronautRunMain runMain = new PyronautRunMain(
            new PyprojectModelReader(),
            classLoader -> { },
            (loadedClass, applicationArgs) -> {
                bannerEnabled.set(applicationArgs.bannerEnabled());
                return false;
            }
        );
        runMain.projectDir = project;

        assertEquals(0, runMain.call());
        assertEquals(false, bannerEnabled.get());
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

    public static final class SampleApp {
        public static void main(String[] args) throws Exception {
            Path target = Path.of(args[0]);
            Files.writeString(target, "started", StandardCharsets.UTF_8);
        }
    }
}

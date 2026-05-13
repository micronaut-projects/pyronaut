package io.micronaut.pyronaut.run;

import io.micronaut.context.BeanContext;
import io.micronaut.context.BeanResolutionContext;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.BeanDefinitionReference;
import io.micronaut.pyronaut.config.model.PyprojectModelReader;
import io.micronaut.pyronaut.runtime.PyronautBeanDefinitionReferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class PyronautRunMainTest {

    @TempDir
    Path tempDir;

    @Test
    void startsApplicationWithConfiguredMainClass() throws Exception {
        Path project = tempDir.resolve("project");
        Path classes = project.resolve("__pyronaut__/classes");
        Files.createDirectories(classes);
        writeMinimalPyproject(project);

        AtomicReference<Class<?>> resolvedMainClass = new AtomicReference<>();
        PyronautRunMain runMain = new PyronautRunMain(
            new PyprojectModelReader(),
            (className, classLoader) -> {
                if (SampleApp.class.getName().equals(className)) {
                    return SampleApp.class;
                }
                return Class.forName(className, true, classLoader);
            },
            classLoader -> { },
            (loadedClass, resolvedClassesDir, appArgs) -> {
                resolvedMainClass.set(loadedClass);
                return false;
            }
        );
        runMain.projectDir = project;
        runMain.mainClass = SampleApp.class.getName();

        assertEquals(0, runMain.call());
        assertEquals(SampleApp.class, resolvedMainClass.get());
    }

    @Test
    void failsWhenProcessedClassesDirectoryMissing() throws Exception {
        Path project = tempDir.resolve("project-missing-classes");
        Files.createDirectories(project.resolve("__pyronaut__/classes"));
        Files.delete(project.resolve("__pyronaut__/classes"));
        writeMinimalPyproject(project);

        PyronautRunMain runMain = new PyronautRunMain();
        runMain.projectDir = project;
        runMain.mainClass = SampleApp.class.getName();

        assertEquals(8, runMain.call());
    }

    @Test
    void acceptsDebugVmFlag() {
        assertDoesNotThrow(() -> new picocli.CommandLine(new PyronautRunMain()).execute("--debug-vm", "--help"));
    }

    @Test
    void missingDefaultMainClassStartsApplicationWithoutStacktraceNoise() throws Exception {
        Path project = tempDir.resolve("project-missing-default-main");
        Path classes = project.resolve("__pyronaut__/classes");
        Files.createDirectories(classes);
        writeMinimalPyproject(project);

        AtomicReference<Path> startedClassesDir = new AtomicReference<>();
        PyronautRunMain runMain = new PyronautRunMain(
            new PyprojectModelReader(),
            (className, classLoader) -> {
                throw new ClassNotFoundException(className);
            },
            classLoader -> { },
            (loadedClass, resolvedClassesDir, appArgs) -> {
                startedClassesDir.set(resolvedClassesDir);
                return false;
            }
        );
        runMain.projectDir = project;
        runMain.mainClass = "pyronaut_application.PyronautMain";

        PrintStream originalErr = System.err;
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        try (PrintStream errStream = new PrintStream(errBuffer, true, StandardCharsets.UTF_8)) {
            System.setErr(errStream);
            assertEquals(0, runMain.call());
        } finally {
            System.setErr(originalErr);
        }

        String stderr = errBuffer.toString();
        assertEquals(project.resolve("__pyronaut__/classes").toAbsolutePath().normalize(), startedClassesDir.get());
        assertFalse(stderr.contains("ClassNotFoundException"));
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
        try (var classLoader = layout.applicationClassLoader()) {
            assertEquals(classesDir.toAbsolutePath().normalize(), layout.processedClassesRoot());
            Path archivedClasses = pyronautDir.resolve("run-classes.jar");
            assertTrue(Files.exists(archivedClasses));
            assertTrue(
                java.util.Arrays.stream(classLoader.getURLs()).anyMatch(url -> url.toString().contains(runtimeJar.getFileName().toString()))
            );
            assertTrue(
                java.util.Arrays.stream(classLoader.getURLs()).anyMatch(url -> url.toString().contains("run-classes.jar"))
            );
            assertTrue(
                java.util.Arrays.stream(classLoader.getURLs()).anyMatch(url -> url.toString().contains("config/"))
                    || java.util.Arrays.stream(classLoader.getURLs()).anyMatch(url -> url.toString().endsWith("/config"))
            );
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
        try (var classLoader = layout.applicationClassLoader()) {
            List<String> urls = Arrays.stream(classLoader.getURLs())
                .map(URL::toString)
                .toList();
            assertTrue(urls.stream().anyMatch(url -> url.contains(developmentRuntimeJar.getFileName().toString())));
            assertFalse(urls.stream().anyMatch(url -> url.contains(runtimeJar.getFileName().toString())));
        }
    }

    @Test
    void discoversProcessedBeanDefinitionReferences() throws Exception {
        Path classesDir = tempDir.resolve("processed-classes");
        Path referencesDir = classesDir.resolve("META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference");
        Files.createDirectories(referencesDir);
        Files.writeString(referencesDir.resolve(SampleBeanDefinitionReference.class.getName()), "", StandardCharsets.UTF_8);

        List<BeanDefinitionReference<?>> references = PyronautBeanDefinitionReferences.loadProcessed(
            classesDir,
            PyronautRunMainTest.class.getClassLoader()
        );

        assertEquals(1, references.size());
        assertEquals(SampleBeanDefinitionReference.class, references.get(0).getClass());
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

        AtomicReference<Path> startedClassesDir = new AtomicReference<>();
        AtomicReference<java.net.URLClassLoader> applicationClassLoader = new AtomicReference<>();
        PyronautRunMain runMain = new PyronautRunMain(
            new PyprojectModelReader(),
            (className, classLoader) -> null,
            classLoader -> applicationClassLoader.set((java.net.URLClassLoader) classLoader),
            (loadedClass, resolvedClassesDir, appArgs) -> {
                startedClassesDir.set(resolvedClassesDir);
                return false;
            }
        );
        runMain.projectDir = project;

        assertEquals(0, runMain.call());
        assertEquals(classes.toAbsolutePath().normalize(), startedClassesDir.get());
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

    public static final class SampleBeanDefinitionReference implements BeanDefinitionReference<SampleApp> {

        @Override
        public String getBeanDefinitionName() {
            return SampleApp.class.getName() + "$Definition";
        }

        @Override
        public BeanDefinition<SampleApp> load() {
            return null;
        }

        @Override
        public boolean isPresent() {
            return true;
        }

        @Override
        public Class<SampleApp> getBeanType() {
            return SampleApp.class;
        }

        @Override
        public AnnotationMetadata getAnnotationMetadata() {
            return AnnotationMetadata.EMPTY_METADATA;
        }

        @Override
        public boolean isEnabled(BeanContext context, BeanResolutionContext resolutionContext) {
            return true;
        }
    }
}

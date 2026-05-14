package io.micronaut.pyronaut.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void resolvesProcessedClassesRoot() throws Exception {
        Path project = tempDir.resolve("project-layout");
        Path classesDir = project.resolve("__pyronaut__/classes");
        Files.createDirectories(classesDir);

        assertEquals(classesDir.toAbsolutePath().normalize(), PyronautRunMain.resolveProcessedClassesRoot(project, Path.of("__pyronaut__/classes")));
    }

    @Test
    void startsWithCurrentContextClassLoader() throws Exception {
        Path project = tempDir.resolve("project-custom-config");
        Path classes = project.resolve("__pyronaut__/classes");
        Files.createDirectories(classes);
        writeMinimalPyproject(project);

        AtomicReference<Path> startedClassesDir = new AtomicReference<>();
        AtomicReference<ClassLoader> applicationClassLoader = new AtomicReference<>();
        PyronautRunMain runMain = new PyronautRunMain(
            (className, classLoader) -> null,
            applicationClassLoader::set,
            (loadedClass, resolvedClassesDir, appArgs) -> {
                startedClassesDir.set(resolvedClassesDir);
                return false;
            }
        );
        runMain.projectDir = project;

        assertEquals(0, runMain.call());
        assertEquals(classes.toAbsolutePath().normalize(), startedClassesDir.get());
        assertEquals(Thread.currentThread().getContextClassLoader(), applicationClassLoader.get());
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

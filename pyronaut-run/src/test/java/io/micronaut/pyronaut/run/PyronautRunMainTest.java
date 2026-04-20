package io.micronaut.pyronaut.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

        AtomicReference<Class<?>> resolvedMainClass = new AtomicReference<>();
        PyronautRunMain runMain = new PyronautRunMain(
            className -> {
                if (SampleApp.class.getName().equals(className)) {
                    return SampleApp.class;
                }
                return Class.forName(className);
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

        AtomicReference<Path> startedClassesDir = new AtomicReference<>();
        PyronautRunMain runMain = new PyronautRunMain(
            className -> {
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

    public static final class SampleApp {
        public static void main(String[] args) throws Exception {
            Path target = Path.of(args[0]);
            Files.writeString(target, "started", StandardCharsets.UTF_8);
        }
    }
}

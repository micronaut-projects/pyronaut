package io.micronaut.pyronaut.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class PyronautRunMainTest {

    @TempDir
    Path tempDir;

    @Test
    void invokesConfiguredMainClass() throws Exception {
        Path project = tempDir.resolve("project");
        Path classes = project.resolve("__pyronaut__/classes");
        Files.createDirectories(classes);

        Path output = project.resolve("invocation.txt");
        PyronautRunMain runMain = new PyronautRunMain(
            className -> {
                if (SampleApp.class.getName().equals(className)) {
                    return SampleApp.class;
                }
                return Class.forName(className);
            },
            (loadedClass, resolvedClassesDir, appArgs) -> false
        );
        runMain.projectDir = project;
        runMain.mainClass = SampleApp.class.getName();
        runMain.appArgs = java.util.List.of(output.toString());

        assertEquals(0, runMain.call());
        assertTrue(Files.exists(output));
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
    void missingDefaultMainClassWithoutFallbackReturnsActionableErrorWithoutStacktraceNoise() throws Exception {
        Path project = tempDir.resolve("project-missing-default-main");
        Path classes = project.resolve("__pyronaut__/classes");
        Files.createDirectories(classes);

        PyronautRunMain runMain = new PyronautRunMain(
            className -> {
                throw new ClassNotFoundException(className);
            },
            (loadedClass, resolvedClassesDir, appArgs) -> false
        );
        runMain.projectDir = project;
        runMain.mainClass = "pyronaut_application.PyronautMain";

        PrintStream originalErr = System.err;
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        try (PrintStream errStream = new PrintStream(errBuffer, true, StandardCharsets.UTF_8)) {
            System.setErr(errStream);
            int exitCode = runMain.call();
            assertEquals(6, exitCode);
        } finally {
            System.setErr(originalErr);
        }

        String stderr = errBuffer.toString();
        assertTrue(stderr.contains("Missing generated main class"));
        assertTrue(stderr.contains("reflective Micronaut startup is unavailable"));
        assertFalse(stderr.contains("ClassNotFoundException"));
    }

    @Test
    void runReachabilityMetadataExistsAndDeclaresMicronautReflectionEntries() throws Exception {
        Path metadata = Path.of("src/main/resources/META-INF/native-image/io.micronaut/micronaut-pyronaut-run/reachability-metadata.json");
        assertTrue(Files.exists(metadata));
        String content = Files.readString(metadata, StandardCharsets.UTF_8);
        assertNotNull(content);
        assertTrue(content.contains("io.micronaut.runtime.Micronaut"));
        assertTrue(content.contains("build"));
        assertTrue(content.contains("start"));
    }

    public static final class SampleApp {
        public static void main(String[] args) throws Exception {
            Path target = Path.of(args[0]);
            Files.writeString(target, "started", StandardCharsets.UTF_8);
        }
    }
}

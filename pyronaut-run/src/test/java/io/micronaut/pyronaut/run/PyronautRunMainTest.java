package io.micronaut.pyronaut.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

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
        Path cache = project.resolve("__pyronaut__");
        Files.createDirectories(classes);
        Files.createDirectories(cache);
        String classpath = System.getProperty("java.class.path", "");
        Files.write(
            cache.resolve("resolved-runtime-dependencies"),
            Arrays.stream(classpath.split(System.getProperty("path.separator"))).toList(),
            StandardCharsets.UTF_8
        );

        Path output = project.resolve("invocation.txt");
        PyronautRunMain runMain = new PyronautRunMain();
        runMain.projectDir = project;
        runMain.mainClass = SampleApp.class.getName();
        runMain.appArgs = java.util.List.of(output.toString());

        assertEquals(0, runMain.call());
        assertTrue(Files.exists(output));
    }

    @Test
    void failsWhenRuntimeManifestMissing() throws Exception {
        Path project = tempDir.resolve("project-missing-manifest");
        Files.createDirectories(project.resolve("__pyronaut__/classes"));

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
        Path cache = project.resolve("__pyronaut__");
        Files.createDirectories(classes);
        Files.createDirectories(cache);

        Path fakeRuntimePath = tempDir.resolve("fake-runtime");
        Files.createDirectories(fakeRuntimePath);
        Files.writeString(
            cache.resolve("resolved-runtime-dependencies"),
            fakeRuntimePath.toString() + System.lineSeparator(),
            StandardCharsets.UTF_8
        );

        PyronautRunMain runMain = new PyronautRunMain();
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

        String stderr = errBuffer.toString(StandardCharsets.UTF_8);
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

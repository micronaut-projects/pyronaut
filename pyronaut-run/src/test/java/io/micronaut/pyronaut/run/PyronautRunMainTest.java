package io.micronaut.pyronaut.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PyronautRunMainTest {

    @TempDir
    Path tempDir;

    @Test
    void invokesConfiguredMainClass() throws Exception {
        Path project = tempDir.resolve("project");
        Path classes = project.resolve("__pyronaut__/classes");
        Path cache = project.resolve(".pytest_cache");
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

    public static final class SampleApp {
        public static void main(String[] args) throws Exception {
            Path target = Path.of(args[0]);
            Files.writeString(target, "started", StandardCharsets.UTF_8);
        }
    }
}

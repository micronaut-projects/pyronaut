package io.micronaut.pyronaut.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PyronautTestMainTest {

    @TempDir
    Path tempDir;

    @Test
    void executesPassingSelectedClass() throws Exception {
        Path project = setupProject();
        PyronautTestMain command = new PyronautTestMain();
        command.projectDir = project;
        command.selectClasses = java.util.List.of(PassingTest.class.getName());

        assertEquals(0, command.call());
    }

    @Test
    void returnsFailureCodeForFailingClass() throws Exception {
        Path project = setupProject();
        PyronautTestMain command = new PyronautTestMain();
        command.projectDir = project;
        command.selectClasses = java.util.List.of("io.micronaut.pyronaut.test.DoesNotExist");

        assertEquals(7, command.call());
    }

    @Test
    void failsWhenTestManifestMissing() throws Exception {
        Path project = tempDir.resolve("project-missing-manifest");
        Files.createDirectories(project.resolve("__pyronaut__/classes"));
        PyronautTestMain command = new PyronautTestMain();
        command.projectDir = project;
        command.selectClasses = java.util.List.of(PassingTest.class.getName());

        assertEquals(8, command.call());
    }

    private Path setupProject() throws Exception {
        Path project = tempDir.resolve("project");
        Path classes = project.resolve("__pyronaut__/classes");
        Path cache = project.resolve("__pyronaut__");
        Files.createDirectories(classes);
        Files.createDirectories(cache);
        String classpath = System.getProperty("java.class.path", "");
        Files.write(
            cache.resolve("resolved-test-dependencies"),
            Arrays.stream(classpath.split(System.getProperty("path.separator"))).toList(),
            StandardCharsets.UTF_8
        );
        return project;
    }

    public static final class PassingTest {
        @Test
        void pass() {
        }
    }

}

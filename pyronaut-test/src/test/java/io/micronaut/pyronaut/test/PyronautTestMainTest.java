package io.micronaut.pyronaut.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

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

    @Test
    void executesSelectedClassFromTestClassesDirectoryWhenPresent() throws Exception {
        Path project = setupProject();
        Path testClasses = project.resolve("__pyronaut__/test-classes");
        Files.createDirectories(testClasses);
        compileGeneratedTestClass(testClasses);

        PyronautTestMain command = new PyronautTestMain();
        command.projectDir = project;
        command.selectClasses = java.util.List.of("generated.GeneratedPassingTest");

        assertEquals(0, command.call());
    }

    @Test
    void executesSelectedClassFromClassesDirectoryWhenTestClassesDirectoryIsMissing() throws Exception {
        Path project = setupProject();
        compileGeneratedTestClass(project.resolve("__pyronaut__/classes"));

        PyronautTestMain command = new PyronautTestMain();
        command.projectDir = project;
        command.selectClasses = java.util.List.of("generated.GeneratedPassingTest");

        assertEquals(0, command.call());
    }

    @Test
    void excludesClassesDirectoryWhenTestClassesDirectoryIsPresent() throws Exception {
        Path project = setupProject();
        compileGeneratedTestClass(project.resolve("__pyronaut__/classes"));
        Files.createDirectories(project.resolve("__pyronaut__/test-classes"));

        PyronautTestMain command = new PyronautTestMain();
        command.projectDir = project;
        command.selectClasses = java.util.List.of("generated.GeneratedPassingTest");

        assertEquals(7, command.call());
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

    public static final class PassingTest {
        @Test
        void pass() {
        }
    }

}

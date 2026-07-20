package io.micronaut.pyronaut.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PyronautProcessorCompilationTest {

    @TempDir
    Path tempDir;

    @Test
    void processesMicronautPythonControllerAndGeneratesClasses() throws Exception {
        Path project = tempDir.resolve("project");
        Path srcDir = project.resolve("src");
        Path testDir = project.resolve("tests");
        Path srcJavaDir = project.resolve("src-java");
        Path testJavaDir = project.resolve("test-java");
        Path cacheDir = project.resolve("__pyronaut__");
        Files.createDirectories(srcDir);
        Files.createDirectories(testDir);
        Files.createDirectories(srcJavaDir);
        Files.createDirectories(testJavaDir);
        Files.createDirectories(cacheDir);

        Files.writeString(project.resolve("pyproject.toml"), minimalPyproject());
        Files.copy(resolveFixture("/fixtures/python/controller.py"), srcDir.resolve("controller.py"));
        Files.writeString(
            srcJavaDir.resolve("BaseType.java"),
            "package example; public class BaseType {}",
            StandardCharsets.UTF_8
        );
        Files.writeString(
            testJavaDir.resolve("TestType.java"),
            "package example; public class TestType extends BaseType {}",
            StandardCharsets.UTF_8
        );

        List<String> classpathEntries = Arrays.stream(System.getProperty("java.class.path", "").split(System.getProperty("path.separator")))
            .map(String::trim)
            .filter(entry -> !entry.isEmpty())
            .toList();
        Files.write(cacheDir.resolve("resolved-build-dependencies"), classpathEntries, StandardCharsets.UTF_8);
        Files.write(cacheDir.resolve("resolved-runtime-dependencies"), classpathEntries, StandardCharsets.UTF_8);
        Files.write(cacheDir.resolve("resolved-test-dependencies"), classpathEntries, StandardCharsets.UTF_8);

        PyronautProcessorMain command = new PyronautProcessorMain();
        command.projectDir = project;

        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), command.call());

        Path classesDir = project.resolve("__pyronaut__/classes");
        assertTrue(Files.isDirectory(classesDir));
        assertTrue(hasClassContaining(classesDir, "MyController"));
        assertTrue(hasClassContaining(classesDir, "BaseType"));

        Path testClassesDir = project.resolve("__pyronaut__/test-classes");
        assertTrue(Files.isDirectory(testClassesDir));
        assertTrue(hasClassContaining(testClassesDir, "TestType"));
        assertTrue(Files.exists(cacheDir.resolve(ProcessorSourceCache.MAIN_HASH_FILE)));
        assertTrue(Files.exists(cacheDir.resolve(ProcessorSourceCache.TEST_HASH_FILE)));
    }

    @Test
    void emitsPythonBytecodeWhenConfigured() throws Exception {
        Path project = tempDir.resolve("project-bytecode");
        Files.createDirectories(project.resolve("src"));
        Files.createDirectories(project.resolve("tests"));
        Files.createDirectories(project.resolve("src-java"));
        Files.createDirectories(project.resolve("test-java"));
        Path cacheDir = project.resolve("__pyronaut__");
        Files.createDirectories(cacheDir);
        Files.writeString(project.resolve("pyproject.toml"), minimalPyproject() + "\n[tool.pyronaut.build.python-bytecode]\nenabled = true\n");
        Files.writeString(project.resolve("src/main.py"), "answer = 42\n");
        List<String> classpathEntries = Arrays.stream(System.getProperty("java.class.path", "").split(System.getProperty("path.separator")))
            .filter(entry -> !entry.isBlank())
            .toList();
        Files.write(cacheDir.resolve("resolved-build-dependencies"), classpathEntries, StandardCharsets.UTF_8);
        Files.write(cacheDir.resolve("resolved-runtime-dependencies"), classpathEntries, StandardCharsets.UTF_8);
        Files.write(cacheDir.resolve("resolved-test-dependencies"), classpathEntries, StandardCharsets.UTF_8);

        PyronautProcessorMain command = new PyronautProcessorMain();
        command.projectDir = project;
        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), command.call());
        Path filesList = project.resolve("__pyronaut__/classes/META-INF/GRAALPY-VFS/micronaut-application/fileslist.txt");
        assertTrue(Files.readString(filesList).contains("__pycache__"));
        assertTrue(Files.readString(filesList).contains(".pyc"));
    }

    @Test
    void compilesMainSourcesIntoTestClassesWhenNoTestSourcesPresent() throws Exception {
        Path project = tempDir.resolve("project-no-test-sources");
        Path srcDir = project.resolve("src");
        Path srcJavaDir = project.resolve("src-java");
        Path cacheDir = project.resolve("__pyronaut__");
        Files.createDirectories(srcDir);
        Files.createDirectories(srcJavaDir);
        Files.createDirectories(cacheDir);

        Files.writeString(project.resolve("pyproject.toml"), minimalPyproject());
        Files.copy(resolveFixture("/fixtures/python/controller.py"), srcDir.resolve("controller.py"));
        Files.writeString(
            srcJavaDir.resolve("BaseType.java"),
            "package example; public class BaseType {}",
            StandardCharsets.UTF_8
        );

        List<String> classpathEntries = Arrays.stream(System.getProperty("java.class.path", "").split(System.getProperty("path.separator")))
            .map(String::trim)
            .filter(entry -> !entry.isEmpty())
            .toList();
        Files.write(cacheDir.resolve("resolved-build-dependencies"), classpathEntries, StandardCharsets.UTF_8);
        Files.write(cacheDir.resolve("resolved-runtime-dependencies"), classpathEntries, StandardCharsets.UTF_8);
        Files.write(cacheDir.resolve("resolved-test-dependencies"), classpathEntries, StandardCharsets.UTF_8);

        PyronautProcessorMain command = new PyronautProcessorMain();
        command.projectDir = project;

        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), command.call());

        Path classesDir = project.resolve("__pyronaut__/classes");
        assertTrue(Files.isDirectory(classesDir));
        assertTrue(hasClassContaining(classesDir, "MyController"));

        Path testClassesDir = project.resolve("__pyronaut__/test-classes");
        assertTrue(Files.isDirectory(testClassesDir));
        assertTrue(hasClassContaining(testClassesDir, "MyController"));
        assertTrue(Files.exists(cacheDir.resolve(ProcessorSourceCache.MAIN_HASH_FILE)));
        assertTrue(Files.exists(cacheDir.resolve(ProcessorSourceCache.TEST_HASH_FILE)));
    }

    @Test
    void removesGeneratedControllerArtifactsWhenSourceIsDeleted() throws Exception {
        Path project = tempDir.resolve("project-delete-controller");
        Path srcDir = project.resolve("src");
        Path cacheDir = project.resolve("__pyronaut__");
        Files.createDirectories(srcDir);
        Files.createDirectories(cacheDir);

        Files.writeString(project.resolve("pyproject.toml"), minimalPyproject());
        Path controller = srcDir.resolve("controller.py");
        Files.copy(resolveFixture("/fixtures/python/controller.py"), controller);
        writeClasspathCaches(cacheDir);

        PyronautProcessorMain command = new PyronautProcessorMain();
        command.projectDir = project;

        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), command.call());

        Path classesDir = project.resolve("__pyronaut__/classes");
        assertTrue(hasClassContaining(classesDir, "MyController"));
        assertTrue(hasFileContaining(classesDir, "MyController"));

        Files.delete(controller);

        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), command.call());
        assertFalse(hasClassContaining(classesDir, "MyController"));
        assertFalse(hasFileContaining(classesDir, "MyController"));
    }

    private static boolean hasClassContaining(Path root, String token) throws Exception {
        try (var files = Files.walk(root)) {
            return files
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .anyMatch(name -> name.endsWith(".class") && name.contains(token));
        }
    }

    private static boolean hasFileContaining(Path root, String token) throws Exception {
        try (var files = Files.walk(root)) {
            return files
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .anyMatch(name -> name.contains(token));
        }
    }

    private static void writeClasspathCaches(Path cacheDir) throws Exception {
        List<String> classpathEntries = Arrays.stream(System.getProperty("java.class.path", "").split(System.getProperty("path.separator")))
            .map(String::trim)
            .filter(entry -> !entry.isEmpty())
            .toList();
        Files.write(cacheDir.resolve("resolved-build-dependencies"), classpathEntries, StandardCharsets.UTF_8);
        Files.write(cacheDir.resolve("resolved-runtime-dependencies"), classpathEntries, StandardCharsets.UTF_8);
        Files.write(cacheDir.resolve("resolved-test-dependencies"), classpathEntries, StandardCharsets.UTF_8);
    }

    private static Path resolveFixture(String resourcePath) throws URISyntaxException {
        var url = PyronautProcessorCompilationTest.class.getResource(resourcePath);
        assertNotNull(url);
        return Path.of(url.toURI());
    }

    private static String minimalPyproject() {
        return """
            [project]
            name = "processor-compilation-test"
            version = "1.0.0"

            [tool.pyronaut]
            repositories = ["mavenCentral"]

            [tool.pyronaut.dependencies]
            runtime = []
            build = []
            test = []
            """;
    }
}

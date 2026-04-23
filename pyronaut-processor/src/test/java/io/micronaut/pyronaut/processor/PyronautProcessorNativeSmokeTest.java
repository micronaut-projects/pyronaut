package io.micronaut.pyronaut.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "pyronaut.processor.native.binary", matches = ".+")
class PyronautProcessorNativeSmokeTest {

    @TempDir
    Path tempDir;

    @Test
    void nativeBinaryProcessesDecoratedHelloWorldSources() throws Exception {
        String binaryPath = System.getProperty("pyronaut.processor.native.binary");
        Path project = tempDir.resolve("project");
        Path src = project.resolve("src");
        Path testSrc = project.resolve("tests");
        Path cache = project.resolve("__pyronaut__");
        Files.createDirectories(src);
        Files.createDirectories(testSrc);
        Files.createDirectories(cache);
        Files.writeString(project.resolve("pyproject.toml"), minimalPyproject());
        Files.writeString(src.resolve("main.py"), "from app import HelloController\n", StandardCharsets.UTF_8);
        Files.writeString(src.resolve("app.py"), decoratedApplication(), StandardCharsets.UTF_8);
        Files.writeString(testSrc.resolve("test_controller.py"), "def test_hello():\n    assert True\n", StandardCharsets.UTF_8);

        List<String> classpathEntries = Arrays.stream(System.getProperty("java.class.path", "").split(System.getProperty("path.separator")))
            .map(String::trim)
            .filter(entry -> !entry.isEmpty())
            .toList();
        Files.write(cache.resolve("resolved-build-dependencies"), classpathEntries, StandardCharsets.UTF_8);
        Files.write(cache.resolve("resolved-runtime-dependencies"), classpathEntries, StandardCharsets.UTF_8);
        Files.write(cache.resolve("resolved-test-dependencies"), classpathEntries, StandardCharsets.UTF_8);

        Process process = new ProcessBuilder(binaryPath, "--project-dir", project.toString())
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();

        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), exitCode, output);
        Path classesDir = project.resolve("__pyronaut__/classes");
        assertMainArtifacts(classesDir, output);

        Path testClassesDir = project.resolve("__pyronaut__/test-classes");
        assertMainArtifacts(testClassesDir, output);
        assertExists(testClassesDir, "META-INF/GRAALPY-VFS/micronaut-application/src/test_controller.py", output);
    }

    private static void assertMainArtifacts(Path outputDir, String output) throws IOException {
        assertTrue(Files.isDirectory(outputDir), output);
        assertTrue(containsClassFile(outputDir), output);
        assertExists(outputDir, "META-INF/GRAALPY-VFS/micronaut-application/fileslist.txt", output);
        assertExists(outputDir, "META-INF/GRAALPY-VFS/micronaut-application/src/main.py", output);
        assertExists(outputDir, "META-INF/GRAALPY-VFS/micronaut-application/src/app.py", output);

        assertContainsPath(outputDir, "META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference", "HelloController$Definition", output);
        assertContainsPath(outputDir, "META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference", "EngineConfiguration$Definition", output);
        assertContainsPath(outputDir, "META-INF/micronaut/io.micronaut.core.beans.BeanIntrospectionReference", "Person$Introspection", output);

        assertContainsPath(outputDir, "python", "HelloController$Definition.class", output);
        assertContainsPath(outputDir, "python", "HelloController$Definition$Exec.class", output);
        assertContainsPath(outputDir, "python", "EngineConfiguration$Definition.class", output);
        assertContainsPath(outputDir, "python", "Person$Introspection.class", output);
        assertContainsPath(outputDir, "python", "HelloController.class", output);
        assertContainsPath(outputDir, "python", "Person.class", output);
        assertContainsPath(outputDir, "python", "EngineConfiguration.class", output);
        assertExists(outputDir, "pyronaut_application/PyronautMain.class", output);
    }

    private static String minimalPyproject() {
        return """
            [project]
            name = "processor-native-smoke"
            version = "1.0.0"

            [tool.pyronaut]
            repositories = ["mavenCentral"]

            [tool.pyronaut.dependencies]
            runtime = []
            build = []
            test = []
            """;
    }

    private static boolean containsClassFile(Path dir) throws IOException {
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.anyMatch(path -> path.getFileName().toString().endsWith(".class"));
        }
    }

    private static void assertExists(Path root, String relativePath, String output) {
        assertTrue(Files.exists(root.resolve(relativePath)), () -> relativePath + System.lineSeparator() + output);
    }

    private static void assertContainsPath(Path root, String relativeDirectory, String fileToken, String output) throws IOException {
        Path directory = root.resolve(relativeDirectory);
        assertTrue(
            Files.isDirectory(directory),
            () -> relativeDirectory + System.lineSeparator() + safeListTree(root) + output
        );
        String listing;
        try (Stream<Path> stream = Files.walk(directory)) {
            listing = stream
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .sorted()
                .reduce("", (left, right) -> left + right + System.lineSeparator());
        }
        try (Stream<Path> stream = Files.walk(directory)) {
            assertTrue(
                stream
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .anyMatch(name -> name.contains(fileToken)),
                () -> relativeDirectory + " :: " + fileToken + System.lineSeparator() + listing + output
            );
        }
    }

    private static String listTree(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                .filter(path -> !path.equals(root))
                .map(path -> root.relativize(path).toString())
                .sorted()
                .reduce("", (left, right) -> left + right + System.lineSeparator());
        }
    }

    private static String safeListTree(Path root) {
        try {
            return listTree(root);
        } catch (IOException e) {
            return "Failed to list tree for " + root + ": " + e.getMessage() + System.lineSeparator();
        }
    }

    private static String decoratedApplication() {
        return """
            from dataclasses import dataclass
            from micronaut.context.annotation import EachProperty
            from micronaut.core.annotation import Introspected
            from micronaut.http.annotation import Controller, Get

            @Introspected
            @dataclass
            class Person:
                name: str
                age: int

            @EachProperty("engines")
            class EngineConfiguration:
                cylinders: int
                enabled: bool = True

            @Controller("/hello")
            class HelloController:
                @Get(produces="application/json")
                def index(self) -> Person:
                    return Person("Hello World", 1)
            """;
    }
}

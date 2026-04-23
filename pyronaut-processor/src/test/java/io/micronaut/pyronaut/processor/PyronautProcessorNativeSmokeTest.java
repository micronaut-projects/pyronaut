package io.micronaut.pyronaut.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        ProcessResult result = runProcessor(binaryPath, tempDir.resolve("project"), helloWorldProjectFiles());
        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), result.exitCode(), result.output());
        Path classesDir = result.project().resolve("__pyronaut__/classes");
        assertMainArtifacts(classesDir, result.output());

        Path testClassesDir = result.project().resolve("__pyronaut__/test-classes");
        assertMainArtifacts(testClassesDir, result.output());
        assertExists(testClassesDir, "META-INF/GRAALPY-VFS/micronaut-application/src/test_controller.py", result.output());
    }

    @Test
    void nativeBinaryProcessesMicronautDataRepositorySources() throws Exception {
        String binaryPath = System.getProperty("pyronaut.processor.native.binary");
        ProcessResult result = runProcessor(binaryPath, tempDir.resolve("data-project"), micronautDataProjectFiles());

        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), result.exitCode(), result.output());
        Path classesDir = result.project().resolve("__pyronaut__/classes");
        assertMicronautDataArtifacts(classesDir, result.output());

        Path testClassesDir = result.project().resolve("__pyronaut__/test-classes");
        assertMicronautDataArtifacts(testClassesDir, result.output());
        assertExists(testClassesDir, "META-INF/GRAALPY-VFS/micronaut-application/src/test_repository.py", result.output());
    }

    private ProcessResult runProcessor(String binaryPath, Path project, Map<String, String> projectFiles) throws Exception {
        Path src = project.resolve("src");
        Path testSrc = project.resolve("tests");
        Path cache = project.resolve("__pyronaut__");
        Files.createDirectories(src);
        Files.createDirectories(testSrc);
        Files.createDirectories(cache);
        Files.writeString(project.resolve("pyproject.toml"), minimalPyproject());
        for (Map.Entry<String, String> entry : projectFiles.entrySet()) {
            Path target = project.resolve(entry.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
        }

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
        return new ProcessResult(project, output, exitCode);
    }

    private static Map<String, String> helloWorldProjectFiles() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("src/main.py", "from app import HelloController\n");
        files.put("src/app.py", decoratedApplication());
        files.put("tests/test_controller.py", "def test_hello():\n    assert True\n");
        return files;
    }

    private static Map<String, String> micronautDataProjectFiles() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("src/main.py", """
            from Book import Book
            from BookRepository import BookRepository
            """);
        files.put("src/Book.py", """
            from dataclasses import dataclass
            from micronaut.data.annotation import GeneratedValue, Id, MappedEntity
            from typing import Annotated

            @dataclass
            @MappedEntity
            class Book:
                id: Annotated[int, Id, GeneratedValue]
                title: str
            """);
        files.put("src/BookRepository.py", """
            from abc import ABC, abstractmethod
            from jakarta.data.repository import Save
            from typing import List

            from micronaut.data.jdbc.annotation import JdbcRepository

            from Book import Book

            @JdbcRepository(dialect = "MYSQL")
            class BookRepository(ABC):

                @Save
                @abstractmethod
                def saveBook(self, book: Book) -> None:
                    pass

                @abstractmethod
                def findAll(self) -> List[Book]:
                    pass

                @abstractmethod
                def findById(self, id: int) -> Book:
                    pass

                @abstractmethod
                def findByTitle(self, title: str) -> Book:
                    pass
            """);
        files.put("tests/test_repository.py", "def test_repository_fixture():\n    assert True\n");
        return files;
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

    private static void assertMicronautDataArtifacts(Path outputDir, String output) throws IOException {
        assertTrue(Files.isDirectory(outputDir), output);
        assertTrue(containsClassFile(outputDir), output);
        assertExists(outputDir, "META-INF/GRAALPY-VFS/micronaut-application/fileslist.txt", output);
        assertExists(outputDir, "META-INF/GRAALPY-VFS/micronaut-application/src/main.py", output);
        assertExists(outputDir, "META-INF/GRAALPY-VFS/micronaut-application/src/Book.py", output);
        assertExists(outputDir, "META-INF/GRAALPY-VFS/micronaut-application/src/BookRepository.py", output);

        assertContainsPath(outputDir, "META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference", "BookRepository", output);
        assertContainsPath(outputDir, "META-INF/micronaut/io.micronaut.core.beans.BeanIntrospectionReference", "Book$Introspection", output);
        assertContainsPath(outputDir, "python", "Book.class", output);
        assertContainsPath(outputDir, "python", "Book$Introspection.class", output);
        assertContainsPath(outputDir, "python", "BookRepository", output);
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

    private record ProcessResult(Path project, String output, int exitCode) {
    }
}

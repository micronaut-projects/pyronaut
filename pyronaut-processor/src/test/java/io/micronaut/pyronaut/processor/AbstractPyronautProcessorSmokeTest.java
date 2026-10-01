package io.micronaut.pyronaut.processor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

abstract class AbstractPyronautProcessorSmokeTest {

    private static final String NON_DETERMINISTIC_LAUNCHER_CLASS = "pyronaut_application/PyronautMain.class";

    protected ProcessResult runJvmProcessor(Path project, Map<String, String> projectFiles) throws Exception {
        return runJvmProcessor(project, projectFiles, minimalPyproject("processor-jvm-smoke"));
    }

    protected ProcessResult runJvmProcessor(Path project, Map<String, String> projectFiles, String pyproject) throws Exception {
        return runJvmProcessor(project, projectFiles, pyproject, List.of());
    }

    protected ProcessResult runJvmProcessor(Path project,
                                            Map<String, String> projectFiles,
                                            String pyproject,
                                            List<String> jvmOptions) throws Exception {
        return runJvmProcessor(project, projectFiles, pyproject, jvmOptions, "off");
    }

    protected ProcessResult runJvmProcessor(Path project,
                                            Map<String, String> projectFiles,
                                            String pyproject,
                                            List<String> jvmOptions,
                                            String progress) throws Exception {
        prepareProject(project, projectFiles, pyproject);
        List<String> command = new java.util.ArrayList<>();
        command.add(javaExecutable().toString());
        command.addAll(jvmOptions);
        command.add("-cp");
        command.add(System.getProperty("java.class.path", ""));
        command.add(PyronautProcessorMain.class.getName());
        command.add("--project-dir");
        command.add(project.toString());
        command.add("--progress");
        command.add(progress);
        Process process = new ProcessBuilder(command)
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        return new ProcessResult(project, output, exitCode);
    }

    protected ProcessResult runNativeProcessor(String binaryPath, Path project, Map<String, String> projectFiles) throws Exception {
        return runNativeProcessor(binaryPath, project, projectFiles, minimalPyproject("processor-native-smoke"));
    }

    protected ProcessResult runNativeProcessor(String binaryPath, Path project, Map<String, String> projectFiles, String pyproject) throws Exception {
        prepareProject(project, projectFiles, pyproject);
        Process process = new ProcessBuilder(
            binaryPath,
            "--project-dir",
            project.toString(),
            "--progress",
            "off"
        )
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        return new ProcessResult(project, output, exitCode);
    }

    protected static Map<String, String> helloWorldProjectFiles() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("src/main.py", "from app import HelloController\n");
        files.put("src/app.py", decoratedApplication());
        files.put("tests/test_controller.py", "def test_hello():\n    assert True\n");
        return files;
    }

    protected static Map<String, String> micronautDataProjectFiles() {
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

    protected static String bytecodeEnabledPyproject(String name) {
        return minimalPyproject(name) + "\n[tool.pyronaut.build.python-bytecode]\nenabled = true\n";
    }

    protected static String incrementalPyproject(String name) {
        return minimalPyproject(name) + "\n[tool.pyronaut.processor]\nincremental = true\n";
    }

    protected static String daemonIncrementalPyproject(String name) {
        return minimalPyproject(name)
            + "\n[tool.pyronaut.processor]\nincremental = true\ndaemon = true\n";
    }

    protected static long daemonPid(Path project, String output) throws Exception {
        Path daemonDirectory = project.resolve("__pyronaut__/daemon");
        Path metadata = daemonDirectory.resolve("daemon.properties");
        if (!Files.isRegularFile(metadata)) {
            Path log = daemonDirectory.resolve("daemon.log");
            fail("No running compiler daemon. Processor output:\n" + output
                + "\nDaemon log:\n" + (Files.isRegularFile(log) ? Files.readString(log) : "<none>"));
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(metadata)) {
            properties.load(input);
        }
        return Long.parseLong(properties.getProperty("pid"));
    }

    protected static void assertMainArtifacts(Path outputDir, String output) throws IOException {
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

    protected static void assertMicronautDataArtifacts(Path outputDir, String output) throws IOException {
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

    protected static Map<String, String> snapshotOutput(Path outputDir) throws IOException {
        try (Stream<Path> stream = Files.walk(outputDir)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(path -> !NON_DETERMINISTIC_LAUNCHER_CLASS.equals(outputDir.relativize(path).toString()))
                .sorted()
                .collect(
                    LinkedHashMap::new,
                    (snapshot, path) -> snapshot.put(outputDir.relativize(path).toString(), fileDigest(path)),
                    LinkedHashMap::putAll
                );
        }
    }

    protected static void assertExists(Path root, String relativePath, String output) {
        assertTrue(Files.exists(root.resolve(relativePath)), () -> relativePath + System.lineSeparator() + output);
    }

    protected static void assertContainsPath(Path root, String relativeDirectory, String fileToken, String output) throws IOException {
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

    protected static boolean containsClassFile(Path dir) throws IOException {
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.anyMatch(path -> path.getFileName().toString().endsWith(".class"));
        }
    }

    protected static String safeListTree(Path root) {
        try {
            return listTree(root);
        } catch (IOException e) {
            return "Failed to list tree for " + root + ": " + e.getMessage() + System.lineSeparator();
        }
    }

    private void prepareProject(Path project, Map<String, String> projectFiles, String pyproject) throws IOException {
        Path src = project.resolve("src");
        Path testSrc = project.resolve("tests");
        Path cache = project.resolve("__pyronaut__");
        Files.createDirectories(src);
        Files.createDirectories(testSrc);
        Files.createDirectories(cache);
        Files.writeString(project.resolve("pyproject.toml"), pyproject);
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
    }

    private static Path javaExecutable() {
        String executable = System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }

    private static String minimalPyproject(String name) {
        return """
            [project]
            name = "%s"
            version = "1.0.0"

            [tool.pyronaut]
            repositories = ["mavenCentral"]

            [tool.pyronaut.dependencies]
            runtime = []
            build = []
            test = []
            """.formatted(name);
    }

    private static String fileDigest(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(path));
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Unable to hash " + path, e);
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

    protected record ProcessResult(Path project, String output, int exitCode) {
    }
}

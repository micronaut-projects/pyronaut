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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "pyronaut.processor.native.binary", matches = ".+")
class PyronautProcessorNativeSmokeTest {

    @TempDir
    Path tempDir;

    @Test
    void nativeBinaryProcessesPythonSources() throws Exception {
        String binaryPath = System.getProperty("pyronaut.processor.native.binary");
        Path project = tempDir.resolve("project");
        Path src = project.resolve("src");
        Path testSrc = project.resolve("tests");
        Path cache = project.resolve("__pyronaut__");
        Files.createDirectories(src);
        Files.createDirectories(testSrc);
        Files.createDirectories(cache);
        Files.writeString(project.resolve("pyproject.toml"), minimalPyproject());
        Files.writeString(src.resolve("app.py"), "def hello():\n    return 'hello'\n", StandardCharsets.UTF_8);
        Files.writeString(testSrc.resolve("test_app.py"), "def test_hello():\n    assert True\n", StandardCharsets.UTF_8);

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
        Path targetDir = project.resolve("__pyronaut__/classes");
        assertTrue(Files.isDirectory(targetDir), output);
        assertTrue(containsClassFile(targetDir), output);
        Path testTargetDir = project.resolve("__pyronaut__/test-classes");
        assertTrue(Files.isDirectory(testTargetDir), output);
        assertTrue(containsClassFile(testTargetDir), output);
    }

    private static boolean containsClassFile(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            return stream.anyMatch(path -> path.getFileName().toString().endsWith(".class"));
        }
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
}

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PyronautProcessorCompilationTest {

    @TempDir
    Path tempDir;

    @Test
    void processesMicronautPythonControllerAndGeneratesBeanDefinitionMetadata() throws Exception {
        Path project = tempDir.resolve("project");
        Path srcDir = project.resolve("src");
        Path cacheDir = project.resolve("__pyronaut__");
        Files.createDirectories(srcDir);
        Files.createDirectories(cacheDir);

        Files.writeString(project.resolve("pyproject.toml"), minimalPyproject());
        Files.copy(resolveFixture("/fixtures/python/controller.py"), srcDir.resolve("controller.py"));

        List<String> classpathEntries = Arrays.stream(System.getProperty("java.class.path", "").split(System.getProperty("path.separator")))
            .map(String::trim)
            .filter(entry -> !entry.isEmpty())
            .toList();
        Files.write(cacheDir.resolve("resolved-build-dependencies"), classpathEntries, StandardCharsets.UTF_8);
        Files.write(cacheDir.resolve("resolved-runtime-dependencies"), classpathEntries, StandardCharsets.UTF_8);

        PyronautProcessorMain command = new PyronautProcessorMain();
        command.projectDir = project;

        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), command.call());

        Path classesDir = project.resolve("__pyronaut__/classes");
        assertTrue(Files.isDirectory(classesDir));
        assertTrue(hasClassContaining(classesDir, "MyController"));

        Path beanRefDir = classesDir.resolve("META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference");
        assertTrue(Files.isDirectory(beanRefDir));
        assertTrue(containsFileName(beanRefDir, "MyController"));
    }

    private static boolean hasClassContaining(Path root, String token) throws Exception {
        try (var files = Files.walk(root)) {
            return files
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .anyMatch(name -> name.endsWith(".class") && name.contains(token));
        }
    }

    private static boolean containsFileName(Path root, String token) throws Exception {
        try (var files = Files.walk(root)) {
            return files
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .anyMatch(name -> name.contains(token));
        }
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

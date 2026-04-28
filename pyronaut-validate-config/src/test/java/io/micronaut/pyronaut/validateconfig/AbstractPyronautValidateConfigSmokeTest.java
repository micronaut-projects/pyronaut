package io.micronaut.pyronaut.validateconfig;

import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

abstract class AbstractPyronautValidateConfigSmokeTest {

    @TempDir
    Path tempDir;

    protected void assertValidationFindsConfigurationAndDependencyInjectionErrors() throws Exception {
        Path project = tempDir.resolve("app");
        Path classesDir = project.resolve("__pyronaut__/classes");
        Files.createDirectories(project.resolve("__pyronaut__"));
        Files.createDirectories(classesDir);
        Files.writeString(project.resolve("pyproject.toml"), minimalPyproject(), StandardCharsets.UTF_8);
        Files.writeString(classesDir.resolve("application.properties"), """
            test.config.enabled=not-a-bool
            """, StandardCharsets.UTF_8);
        writeConfigurationSchema(classesDir);
        compileApplication(classesDir);
        assertBeanMetadataGenerated(classesDir);
        Files.writeString(
            project.resolve("__pyronaut__/resolved-runtime-dependencies"),
            runtimeClasspathManifest(),
            StandardCharsets.UTF_8
        );

        RunResult result = runValidation(project);

        assertEquals(1, result.exitCode(), result.output());
        Path reportDir = project.resolve("__pyronaut__/reports/config-validation/run");
        Path jsonReport = reportDir.resolve("configuration-errors.json");
        Path htmlReport = reportDir.resolve("configuration-errors.html");
        assertTrue(Files.exists(jsonReport), result.output());
        assertTrue(Files.exists(htmlReport), result.output());

        String json = Files.readString(jsonReport, StandardCharsets.UTF_8);
        assertTrue(json.contains("test.config.enabled"), json);
        assertTrue(json.contains("MissingDependencyConsumer") || json.contains("MissingCollaborator"), json);
    }

    protected abstract RunResult runValidation(Path project) throws Exception;

    protected static RunResult runJvmValidation(Path project) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        int exitCode;
        try {
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
            exitCode = new CommandLine(new PyronautValidateConfigMain()).execute(
                "--project-dir", project.toString(),
                "--scenario", "run",
                "--validate-dependency-injection",
                "--dependency-injection-validation-strategy", "all-beans",
                "--no-cache"
            );
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new RunResult(
            exitCode,
            stdout.toString(StandardCharsets.UTF_8) + stderr.toString(StandardCharsets.UTF_8)
        );
    }

    protected static void compileApplication(Path outputDir) throws Exception {
        Path sourceDir = Files.createTempDirectory("pyronaut-validate-config-smoke-src");
        Path sourceFile = sourceDir.resolve("example/MissingDependencyConsumer.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, """
            package example;

            import jakarta.inject.Singleton;

            @Singleton
            public class MissingDependencyConsumer {
                public MissingDependencyConsumer(MissingCollaborator collaborator) {
                }
            }

            class MissingCollaborator {
            }
            """, StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JDK compiler is required for smoke test");
        }
        String classpath = currentRuntimeClasspath();
        StringWriter compilerOutput = new StringWriter();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            List<String> options = List.of(
                "-parameters",
                "-classpath", classpath,
                "-processorpath", classpath,
                "-d", outputDir.toString()
            );
            boolean success = compiler.getTask(
                compilerOutput,
                fileManager,
                null,
                options,
                null,
                fileManager.getJavaFileObjects(sourceFile.toFile())
            ).call();
            if (!success) {
                throw new IllegalStateException("Compilation failed: " + compilerOutput);
            }
        }
    }

    protected static void assertBeanMetadataGenerated(Path outputDir) throws Exception {
        try (var walk = Files.walk(outputDir)) {
            boolean found = walk
                .filter(Files::isRegularFile)
                .map(path -> outputDir.relativize(path).toString().replace('\\', '/'))
                .anyMatch(name -> name.contains("MissingDependencyConsumer$Definition"));
            assertTrue(found, () -> "Expected generated bean definition under " + outputDir);
        }
    }

    protected static void writeConfigurationSchema(Path outputDir) throws Exception {
        Path schemaFile = outputDir.resolve("META-INF/micronaut-configuration-schemas/example.TestConfig.json");
        Files.createDirectories(schemaFile.getParent());
        Files.writeString(schemaFile, """
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "title": "TestConfig",
              "type": "object",
              "x-micronaut": {
                "prefix": "test.config"
              },
              "properties": {
                "enabled": {
                  "type": "boolean",
                  "x-micronaut-javaType": "java.lang.Boolean",
                  "x-micronaut-path": "test.config.enabled"
                }
              }
            }
            """, StandardCharsets.UTF_8);
    }

    protected static String runtimeClasspathManifest() {
        return currentRuntimeClasspathEntries().stream()
            .collect(Collectors.joining(System.lineSeparator(), "", System.lineSeparator()));
    }

    protected static String currentRuntimeClasspath() {
        return String.join(File.pathSeparator, currentRuntimeClasspathEntries());
    }

    protected static LinkedHashSet<String> currentRuntimeClasspathEntries() {
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        String rawClasspath = System.getProperty("java.class.path", "");
        if (rawClasspath.isBlank()) {
            return entries;
        }
        for (String entry : rawClasspath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Path path = Path.of(trimmed).toAbsolutePath().normalize();
            if (Files.exists(path)) {
                entries.add(path.toString());
            }
        }
        return entries;
    }

    protected static String minimalPyproject() {
        return """
            [project]
            name = "validate-config-smoke"
            version = "1.0.0"

            [tool.pyronaut]
            version = "5.0.0-SNAPSHOT"
            """;
    }

    protected record RunResult(int exitCode, String output) {
    }
}

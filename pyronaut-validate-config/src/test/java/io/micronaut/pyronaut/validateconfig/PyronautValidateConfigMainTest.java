package io.micronaut.pyronaut.validateconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PyronautValidateConfigMainTest {

    @TempDir
    Path tempDir;

    @Test
    void validateConfigWritesJsonAndHtmlReportsForProductionScenario() throws Exception {
        Path project = prepareProject();
        Path runtimeJar = project.resolve("runtime.jar");
        Files.writeString(runtimeJar, "stub");
        Files.writeString(project.resolve("__pyronaut__/resolved-runtime-dependencies"), runtimeJar.toString() + "\n");

        PyronautValidateConfigMain command = new PyronautValidateConfigMain();
        int exit = new CommandLine(command).execute("--project-dir", project.toString());

        assertEquals(0, exit);
        Path reportDir = project.resolve("__pyronaut__/reports/config-validation/production");
        assertTrue(Files.exists(reportDir.resolve("configuration-errors.json")));
        assertTrue(Files.exists(reportDir.resolve("configuration-errors.html")));
    }

    @Test
    void validateConfigReturnsValidationErrorWhenDiErrorsPresentAndEnabled() throws Exception {
        Path project = prepareProject();
        PyronautValidateConfigMain command = new PyronautValidateConfigMain(
            new io.micronaut.pyronaut.config.model.PyprojectModelReader(),
            settings -> new PyronautValidateConfigMain.ValidationExecutionResult(true)
        );
        int exit = new CommandLine(command).execute(
            "--project-dir", project.toString(),
            "--validate-dependency-injection"
        );

        assertEquals(1, exit);
    }

    @Test
    void validateConfigUsesCacheOnSecondRunWithSameInputs() throws Exception {
        Path project = prepareProject();
        AtomicInteger calls = new AtomicInteger();
        PyronautValidateConfigMain command = new PyronautValidateConfigMain(
            new io.micronaut.pyronaut.config.model.PyprojectModelReader(),
            settings -> {
                calls.incrementAndGet();
                Path reportDir = settings.outputDir();
                Files.createDirectories(reportDir);
                Files.writeString(reportDir.resolve("configuration-errors.json"), "{}\n");
                Files.writeString(reportDir.resolve("configuration-errors.html"), "<html></html>\n");
                return new PyronautValidateConfigMain.ValidationExecutionResult(false);
            }
        );

        int first = new CommandLine(command).execute("--project-dir", project.toString());
        int second = new CommandLine(command).execute("--project-dir", project.toString());

        assertEquals(0, first);
        assertEquals(0, second);
        assertEquals(1, calls.get());
    }

    @Test
    void validateConfigNoCacheForcesExecutionOnEachRun() throws Exception {
        Path project = prepareProject();
        AtomicInteger calls = new AtomicInteger();
        PyronautValidateConfigMain command = new PyronautValidateConfigMain(
            new io.micronaut.pyronaut.config.model.PyprojectModelReader(),
            settings -> {
                calls.incrementAndGet();
                Path reportDir = settings.outputDir();
                Files.createDirectories(reportDir);
                Files.writeString(reportDir.resolve("configuration-errors.json"), "{}\n");
                Files.writeString(reportDir.resolve("configuration-errors.html"), "<html></html>\n");
                return new PyronautValidateConfigMain.ValidationExecutionResult(false);
            }
        );

        int first = new CommandLine(command).execute("--project-dir", project.toString(), "--no-cache");
        int second = new CommandLine(command).execute("--project-dir", project.toString(), "--no-cache");

        assertEquals(0, first);
        assertEquals(0, second);
        assertEquals(2, calls.get());
    }

    @Test
    void validateConfigMergesSuppressionsWithoutBlanksOrDuplicates() throws Exception {
        Path project = prepareProject("""
            [project]
            name = "demo"

            [tool.pyronaut]
            version = "5.0.0-SNAPSHOT"

            [tool.pyronaut.validation]
            suppressions = ["datasources.*.db-type", "", "datasources.*.db-type"]
            """);

        AtomicReference<List<String>> effectiveSuppressions = new AtomicReference<>(List.of());
        PyronautValidateConfigMain command = new PyronautValidateConfigMain(
            new io.micronaut.pyronaut.config.model.PyprojectModelReader(),
            settings -> {
                effectiveSuppressions.set(settings.suppressions());
                Path reportDir = settings.outputDir();
                Files.createDirectories(reportDir);
                Files.writeString(reportDir.resolve("configuration-errors.json"), "{}\n");
                Files.writeString(reportDir.resolve("configuration-errors.html"), "<html></html>\n");
                return new PyronautValidateConfigMain.ValidationExecutionResult(false);
            }
        );

        int exit = new CommandLine(command).execute(
            "--project-dir", project.toString(),
            "--no-cache",
            "--suppressions", "datasources.*.x-protocol-url,datasources.*.db-type",
            "--suppress", "micronaut.http.*"
        );

        assertEquals(0, exit);
        assertEquals(List.of("datasources.*.db-type", "datasources.*.x-protocol-url", "micronaut.http.*"), effectiveSuppressions.get());
    }

    @Test
    void executorPrintsCopyPasteablePyprojectSuppressionsForConfigurationErrors() throws Exception {
        var err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(err));
        try {
            Class<?> executorType = Class.forName("io.micronaut.pyronaut.validateconfig.MicronautConfigurationValidatorExecutor");
            var method = executorType.getDeclaredMethod("printSuppressionSnippet", PrintStream.class, List.class);
            method.setAccessible(true);
            method.invoke(null, System.err, List.of("datasources.*.db-type", "datasources.*.x-protocol-url"));
        } finally {
            System.setErr(originalErr);
        }

        String output = err.toString();
        assertTrue(output.contains("Add the following to pyproject.toml to suppress these validation errors:"));
        assertTrue(output.contains("[tool.pyronaut.validation]"));
        assertTrue(output.contains("\"datasources.*.db-type\""));
        assertTrue(output.contains("\"datasources.*.x-protocol-url\""));
    }

    @Test
    void executorPrintsIndexedPropertiesAsWildcardSuppressions() {
        String suppressionPattern = invokeSuppressionPattern("micronaut.server.netty.listeners.0.port");

        assertEquals("micronaut.server.netty.listeners.*.port", suppressionPattern);
    }

    @Test
    void executorDoesNotPrintSuppressionSnippetWhenThereAreNoConfigurationErrors() throws Exception {
        var err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(err));
        try {
            Class<?> executorType = Class.forName("io.micronaut.pyronaut.validateconfig.MicronautConfigurationValidatorExecutor");
            var method = executorType.getDeclaredMethod("printSuppressionSnippet", PrintStream.class, List.class);
            method.setAccessible(true);
            method.invoke(null, System.err, List.of());
        } finally {
            System.setErr(originalErr);
        }

        assertFalse(err.toString().contains("[tool.pyronaut.validation]"));
    }

    private static String invokeSuppressionPattern(String property) {
        try {
            Class<?> executorType = Class.forName("io.micronaut.pyronaut.validateconfig.MicronautConfigurationValidatorExecutor");
            var method = executorType.getDeclaredMethod("toSuppressionPattern", String.class);
            method.setAccessible(true);
            return (String) method.invoke(null, property);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private Path prepareProject() throws Exception {
        return prepareProject("""
            [project]
            name = "demo"

            [tool.pyronaut]
            version = "5.0.0-SNAPSHOT"
            """);
    }

    private Path prepareProject(String pyprojectToml) throws Exception {
        Path project = tempDir.resolve("app");
        Files.createDirectories(project.resolve("__pyronaut__"));
        Files.createDirectories(project.resolve("__pyronaut__/classes"));
        Files.createDirectories(project.resolve("src/main/resources"));
        Files.writeString(project.resolve("pyproject.toml"), pyprojectToml);
        return project;
    }
}

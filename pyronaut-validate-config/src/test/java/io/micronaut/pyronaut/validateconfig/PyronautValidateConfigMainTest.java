package io.micronaut.pyronaut.validateconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

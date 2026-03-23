package io.micronaut.pyronaut.validateconfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

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

    private Path prepareProject() throws Exception {
        Path project = tempDir.resolve("app");
        Files.createDirectories(project.resolve("__pyronaut__"));
        Files.createDirectories(project.resolve("__pyronaut__/classes"));
        Files.createDirectories(project.resolve("src/main/resources"));
        Files.writeString(project.resolve("pyproject.toml"), """
            [project]
            name = "demo"

            [tool.pyronaut]
            version = "5.0.0-SNAPSHOT"
            """);
        return project;
    }
}

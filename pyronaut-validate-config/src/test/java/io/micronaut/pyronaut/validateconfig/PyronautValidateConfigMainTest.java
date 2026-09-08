package io.micronaut.pyronaut.validateconfig;

import io.micronaut.core.beans.BeanIntrospectionProviders;
import io.micronaut.core.beans.BeanIntrospectionsProvider;
import io.micronaut.jsonschema.configuration.validator.report.JsonConfigurationErrorReporter;
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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PyronautValidateConfigMainTest {

    @TempDir
    Path tempDir;

    @Test
    void initializesJavaHomeFromEnvironmentWhenMissing() {
        String previousJavaHome = System.getProperty("java.home");
        try {
            System.clearProperty("java.home");

            PyronautValidateConfigMain.initializeJavaHomeIfMissing(() -> "/tmp/graalvm-home");

            assertEquals("/tmp/graalvm-home", System.getProperty("java.home"));
        } finally {
            restoreJavaHome(previousJavaHome);
        }
    }

    @Test
    void leavesJavaHomeUnsetWhenEnvironmentMissing() {
        String previousJavaHome = System.getProperty("java.home");
        try {
            System.clearProperty("java.home");

            PyronautValidateConfigMain.initializeJavaHomeIfMissing(() -> null);

            assertNull(System.getProperty("java.home"));
        } finally {
            restoreJavaHome(previousJavaHome);
        }
    }

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
    void validateConfigPrintsOnlyPassedMessageWhenValidationSucceeds() throws Exception {
        Path project = prepareProject();
        PyronautValidateConfigMain command = new PyronautValidateConfigMain(
            new io.micronaut.pyronaut.config.model.PyprojectModelReader(),
            settings -> {
                Path reportDir = settings.outputDir();
                Files.createDirectories(reportDir);
                Files.writeString(reportDir.resolve("configuration-errors.json"), "{}\n");
                Files.writeString(reportDir.resolve("configuration-errors.html"), "<html></html>\n");
                return new PyronautValidateConfigMain.ValidationExecutionResult(false);
            }
        );
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        int exit;
        try {
            System.setOut(new PrintStream(out, true, UTF_8));
            System.setErr(new PrintStream(err, true, UTF_8));
            exit = new CommandLine(command).execute("--project-dir", project.toString(), "--no-cache");
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        assertEquals(0, exit);
        assertEquals("Configuration validation passed." + System.lineSeparator(), out.toString(UTF_8));
        assertEquals("", err.toString(UTF_8));
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
    void executorDoesNotPrintReportDiagnosticsWhenValidationSucceeds() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        PyronautValidateConfigMain.ValidationExecutionResult result;
        try {
            System.setOut(new PrintStream(out, true, UTF_8));
            System.setErr(new PrintStream(err, true, UTF_8));
            result = new MicronautConfigurationValidatorExecutor().validate(new PyronautValidateConfigMain.ValidationSettings(
                true,
                false,
                false,
                false,
                "reachable",
                PyronautValidateConfigMain.ReportFormat.BOTH,
                tempDir.resolve("reports"),
                tempDir,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "production"
            ));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        assertFalse(result.hasErrors());
        assertEquals("", out.toString(UTF_8));
        assertEquals("", err.toString(UTF_8));
    }

    @Test
    void validationClasspathIncludesNativeProvidedJarWithoutChangingProjectClasspath() throws Exception {
        Path projectJar = Files.createFile(tempDir.resolve("project.jar"));
        Path nativeJar = Files.createFile(tempDir.resolve("native-metadata-1.0.0.jar"));
        String previousArtifacts = System.getProperty("pyronaut.dev.native.provided.artifacts");
        String previousJars = System.getProperty("pyronaut.dev.native.provided.jars");
        try {
            System.setProperty("pyronaut.dev.native.provided.artifacts", "example:native-metadata");
            System.setProperty("pyronaut.dev.native.provided.jars", nativeJar.toString());
            var method = MicronautConfigurationValidatorExecutor.class.getDeclaredMethod("validationClasspath", String.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<java.net.URL> urls = (List<java.net.URL>) method.invoke(null, projectJar.toString());

            assertEquals(List.of(projectJar.toUri().toURL(), nativeJar.toUri().toURL()), urls);
        } finally {
            restoreProperty("pyronaut.dev.native.provided.artifacts", previousArtifacts);
            restoreProperty("pyronaut.dev.native.provided.jars", previousJars);
        }
    }

    @Test
    void jsonReportUsesIntrospectionsFromValidationClasspath() throws Exception {
        Path project = prepareProject();
        Path runtimeJar = Files.createFile(project.resolve("runtime.jar"));
        Files.writeString(project.resolve("__pyronaut__/resolved-runtime-dependencies"), runtimeJar + "\n");
        Path validatorJar = Path.of(JsonConfigurationErrorReporter.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String previousArtifacts = System.getProperty("pyronaut.dev.native.provided.artifacts");
        String previousJars = System.getProperty("pyronaut.dev.native.provided.jars");
        BeanIntrospectionsProvider previousProvider = BeanIntrospectionProviders.set(classLoader -> List.of());
        try {
            System.setProperty("pyronaut.dev.native.provided.artifacts", "io.micronaut.jsonschema:micronaut-json-schema-configuration-validator");
            System.setProperty("pyronaut.dev.native.provided.jars", validatorJar.toString());
            new MicronautConfigurationValidatorExecutor().validate(
                new PyronautValidateConfigMain.ValidationSettings(
                    true,
                    false,
                    false,
                    false,
                    "reachable",
                    PyronautValidateConfigMain.ReportFormat.JSON,
                    project.resolve("__pyronaut__/reports"),
                    project,
                    List.of(),
                    List.of(runtimeJar.toString()),
                    List.of(),
                    List.of(),
                    List.of(),
                    "production"
                )
            );
            assertTrue(Files.size(project.resolve("__pyronaut__/reports/configuration-errors.json")) > 0);
        } finally {
            BeanIntrospectionProviders.set(previousProvider);
            restoreProperty("pyronaut.dev.native.provided.artifacts", previousArtifacts);
            restoreProperty("pyronaut.dev.native.provided.jars", previousJars);
        }
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
    void validateConfigCacheIsInvalidatedWhenOutputDirectoryChanges() throws Exception {
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

        int first = new CommandLine(command).execute("--project-dir", project.toString(), "--out", "reports-a");
        int second = new CommandLine(command).execute("--project-dir", project.toString(), "--out", "reports-b");

        assertEquals(0, first);
        assertEquals(0, second);
        assertEquals(2, calls.get());
        assertTrue(Files.isRegularFile(project.resolve("reports-a/configuration-errors.json")));
        assertTrue(Files.isRegularFile(project.resolve("reports-b/configuration-errors.json")));
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
        assertEquals(List.of("micronaut.config", "micronaut.graalvm", "micronaut.openapi", "micronaut.processing", "endpoints.*", "logger.levels.*", "datasources.*.db-type", "datasources.*.x-protocol-url", "micronaut.http.*"), effectiveSuppressions.get());
    }

    @Test
    void validateConfigFiltersTestResourcesClientFromManifestClasspath() throws Exception {
        Path project = prepareProject();
        Path runtimeJar = project.resolve("libs/runtime.jar");
        Path buildJar = project.resolve("libs/build.jar");
        Path testJar = project.resolve("libs/test.jar");
        Path testResourcesClientJar = project.resolve("libs/micronaut-test-resources-client-2.9.0.jar");
        Files.createDirectories(runtimeJar.getParent());
        Files.writeString(runtimeJar, "runtime");
        Files.writeString(buildJar, "build");
        Files.writeString(testJar, "test");
        Files.writeString(testResourcesClientJar, "client");
        Files.writeString(project.resolve("__pyronaut__/resolved-runtime-dependencies"), runtimeJar + "\n" + testResourcesClientJar + "\n");
        Files.writeString(project.resolve("__pyronaut__/resolved-build-dependencies"), buildJar + "\n");
        Files.writeString(project.resolve("__pyronaut__/resolved-test-dependencies"), testJar + "\n" + testResourcesClientJar + "\n");

        AtomicReference<List<String>> productionClasspath = new AtomicReference<>(List.of());
        PyronautValidateConfigMain productionCommand = new PyronautValidateConfigMain(
            new io.micronaut.pyronaut.config.model.PyprojectModelReader(),
            settings -> {
                productionClasspath.set(settings.classpathElements());
                Path reportDir = settings.outputDir();
                Files.createDirectories(reportDir);
                Files.writeString(reportDir.resolve("configuration-errors.json"), "{}\n");
                Files.writeString(reportDir.resolve("configuration-errors.html"), "<html></html>\n");
                return new PyronautValidateConfigMain.ValidationExecutionResult(false);
            }
        );

        AtomicReference<List<String>> testClasspath = new AtomicReference<>(List.of());
        PyronautValidateConfigMain testCommand = new PyronautValidateConfigMain(
            new io.micronaut.pyronaut.config.model.PyprojectModelReader(),
            settings -> {
                testClasspath.set(settings.classpathElements());
                Path reportDir = settings.outputDir();
                Files.createDirectories(reportDir);
                Files.writeString(reportDir.resolve("configuration-errors.json"), "{}\n");
                Files.writeString(reportDir.resolve("configuration-errors.html"), "<html></html>\n");
                return new PyronautValidateConfigMain.ValidationExecutionResult(false);
            }
        );

        int productionExit = new CommandLine(productionCommand).execute("--project-dir", project.toString(), "--no-cache");
        int testExit = new CommandLine(testCommand).execute("--project-dir", project.toString(), "--scenario", "test", "--no-cache");

        assertEquals(0, productionExit);
        assertEquals(0, testExit);
        assertFalse(productionClasspath.get().stream().anyMatch(entry -> entry.contains("micronaut-test-resources-client")));
        assertFalse(testClasspath.get().stream().anyMatch(entry -> entry.contains("micronaut-test-resources-client")));
        assertTrue(productionClasspath.get().stream().anyMatch(entry -> entry.endsWith("runtime.jar")));
        assertTrue(testClasspath.get().stream().anyMatch(entry -> entry.endsWith("runtime.jar")));
        assertTrue(testClasspath.get().stream().anyMatch(entry -> entry.endsWith("test.jar")));
        assertTrue(testClasspath.get().stream().anyMatch(entry -> entry.endsWith("build.jar")));
    }

    @Test
    void runScenarioUsesRuntimeManifestWhenDevelopmentManifestIsPresent() throws Exception {
        Path project = prepareProject();
        Path runtimeJar = project.resolve("libs/runtime.jar");
        Path developmentRuntimeJar = project.resolve("libs/development.jar");
        Files.createDirectories(runtimeJar.getParent());
        Files.writeString(runtimeJar, "runtime");
        Files.writeString(developmentRuntimeJar, "development");
        Files.writeString(project.resolve("__pyronaut__/resolved-runtime-dependencies"), runtimeJar + "\n");
        Files.writeString(project.resolve("__pyronaut__/resolved-development-runtime-dependencies"), developmentRuntimeJar + "\n");

        AtomicReference<List<String>> runClasspath = new AtomicReference<>(List.of());
        PyronautValidateConfigMain runCommand = new PyronautValidateConfigMain(
            new io.micronaut.pyronaut.config.model.PyprojectModelReader(),
            settings -> {
                runClasspath.set(settings.classpathElements());
                Path reportDir = settings.outputDir();
                Files.createDirectories(reportDir);
                Files.writeString(reportDir.resolve("configuration-errors.json"), "{}\n");
                Files.writeString(reportDir.resolve("configuration-errors.html"), "<html></html>\n");
                return new PyronautValidateConfigMain.ValidationExecutionResult(false);
            }
        );

        AtomicReference<List<String>> productionClasspath = new AtomicReference<>(List.of());
        PyronautValidateConfigMain productionCommand = new PyronautValidateConfigMain(
            new io.micronaut.pyronaut.config.model.PyprojectModelReader(),
            settings -> {
                productionClasspath.set(settings.classpathElements());
                Path reportDir = settings.outputDir();
                Files.createDirectories(reportDir);
                Files.writeString(reportDir.resolve("configuration-errors.json"), "{}\n");
                Files.writeString(reportDir.resolve("configuration-errors.html"), "<html></html>\n");
                return new PyronautValidateConfigMain.ValidationExecutionResult(false);
            }
        );

        int runExit = new CommandLine(runCommand).execute("--project-dir", project.toString(), "--scenario", "run", "--no-cache");
        int productionExit = new CommandLine(productionCommand).execute("--project-dir", project.toString(), "--no-cache");

        assertEquals(0, runExit);
        assertEquals(0, productionExit);
        assertTrue(runClasspath.get().stream().anyMatch(entry -> entry.endsWith("runtime.jar")));
        assertFalse(runClasspath.get().stream().anyMatch(entry -> entry.endsWith("development.jar")));
        assertTrue(productionClasspath.get().stream().anyMatch(entry -> entry.endsWith("runtime.jar")));
        assertFalse(productionClasspath.get().stream().anyMatch(entry -> entry.endsWith("development.jar")));
    }

    @Test
    void validateConfigRejectsProductionControlPanelWithoutSecurity() throws Exception {
        Path project = prepareProject("""
            [project]
            name = "demo"

            [tool.pyronaut]

            [tool.pyronaut.control-panel]
            production-enabled = true
            """);
        Path runtimeJar = project.resolve("libs/runtime.jar");
        Files.createDirectories(runtimeJar.getParent());
        Files.writeString(runtimeJar, "runtime");
        Files.writeString(project.resolve("__pyronaut__/resolved-runtime-dependencies"), runtimeJar + "\n");

        AtomicInteger calls = new AtomicInteger();
        PyronautValidateConfigMain command = new PyronautValidateConfigMain(
            new io.micronaut.pyronaut.config.model.PyprojectModelReader(),
            settings -> {
                calls.incrementAndGet();
                return new PyronautValidateConfigMain.ValidationExecutionResult(false);
            }
        );

        int exit = new CommandLine(command).execute("--project-dir", project.toString(), "--no-cache");

        assertEquals(8, exit);
        assertEquals(0, calls.get());
    }

    @Test
    void validateConfigAcceptsProductionControlPanelWithSecurity() throws Exception {
        Path project = prepareProject("""
            [project]
            name = "demo"

            [tool.pyronaut]

            [tool.pyronaut.control-panel]
            production-enabled = true
            """);
        Path runtimeJar = project.resolve("libs/runtime.jar");
        Path securityJar = project.resolve("m2/io/micronaut/security/micronaut-security/4.0.0/micronaut-security-4.0.0.jar");
        Files.createDirectories(runtimeJar.getParent());
        Files.createDirectories(securityJar.getParent());
        Files.writeString(runtimeJar, "runtime");
        Files.writeString(securityJar, "security");
        Files.writeString(project.resolve("__pyronaut__/resolved-runtime-dependencies"), runtimeJar + "\n" + securityJar + "\n");

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

        int exit = new CommandLine(command).execute("--project-dir", project.toString(), "--no-cache");

        assertEquals(0, exit);
        assertEquals(1, calls.get());
    }

    @Test
    void validateConfigIncludesConfigDirectoryInDefaultResourceDirs() throws Exception {
        Path project = prepareProject();
        Files.createDirectories(project.resolve("config"));
        Files.writeString(project.resolve("config/application.toml"), "[micronaut.server]\nport = \"junk\"\n");

        AtomicReference<List<Path>> runResources = new AtomicReference<>(List.of());
        PyronautValidateConfigMain runCommand = new PyronautValidateConfigMain(
            new io.micronaut.pyronaut.config.model.PyprojectModelReader(),
            settings -> {
                runResources.set(settings.resourcesDirs());
                Path reportDir = settings.outputDir();
                Files.createDirectories(reportDir);
                Files.writeString(reportDir.resolve("configuration-errors.json"), "{}\n");
                Files.writeString(reportDir.resolve("configuration-errors.html"), "<html></html>\n");
                return new PyronautValidateConfigMain.ValidationExecutionResult(false);
            }
        );

        AtomicReference<List<Path>> testResources = new AtomicReference<>(List.of());
        PyronautValidateConfigMain testCommand = new PyronautValidateConfigMain(
            new io.micronaut.pyronaut.config.model.PyprojectModelReader(),
            settings -> {
                testResources.set(settings.resourcesDirs());
                Path reportDir = settings.outputDir();
                Files.createDirectories(reportDir);
                Files.writeString(reportDir.resolve("configuration-errors.json"), "{}\n");
                Files.writeString(reportDir.resolve("configuration-errors.html"), "<html></html>\n");
                return new PyronautValidateConfigMain.ValidationExecutionResult(false);
            }
        );

        int runExit = new CommandLine(runCommand).execute("--project-dir", project.toString(), "--scenario", "run", "--no-cache");
        int testExit = new CommandLine(testCommand).execute("--project-dir", project.toString(), "--scenario", "test", "--no-cache");

        assertEquals(0, runExit);
        assertEquals(0, testExit);
        assertEquals(
            List.of(project.resolve("config"), project.resolve("src/main/resources")),
            runResources.get()
        );
        assertEquals(
            List.of(project.resolve("config"), project.resolve("src/main/resources"), project.resolve("tests-config")),
            testResources.get()
        );
    }

    @Test
    void executorPrintsCopyPasteablePyprojectSuppressionsForConfigurationErrors() throws Exception {
        var err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(err, true, UTF_8));
        try {
            Class<?> executorType = Class.forName("io.micronaut.pyronaut.validateconfig.MicronautConfigurationValidatorExecutor");
            var method = executorType.getDeclaredMethod("printSuppressionSnippet", PrintStream.class, List.class);
            method.setAccessible(true);
            method.invoke(null, System.err, List.of("datasources.*.db-type", "datasources.*.x-protocol-url"));
        } finally {
            System.setErr(originalErr);
        }

        String output = err.toString(UTF_8);
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
        System.setErr(new PrintStream(err, true, UTF_8));
        try {
            Class<?> executorType = Class.forName("io.micronaut.pyronaut.validateconfig.MicronautConfigurationValidatorExecutor");
            var method = executorType.getDeclaredMethod("printSuppressionSnippet", PrintStream.class, List.class);
            method.setAccessible(true);
            method.invoke(null, System.err, List.of());
        } finally {
            System.setErr(originalErr);
        }

        assertFalse(err.toString(UTF_8).contains("[tool.pyronaut.validation]"));
    }

    @Test
    void executorPrintsExplicitValidationMessageForEnvironments() throws Exception {
        Class<?> executorType = Class.forName("io.micronaut.pyronaut.validateconfig.MicronautConfigurationValidatorExecutor");
        var method = executorType.getDeclaredMethod("validationMessage", PyronautValidateConfigMain.ValidationSettings.class);
        method.setAccessible(true);

        PyronautValidateConfigMain.ValidationSettings settings = new PyronautValidateConfigMain.ValidationSettings(
            true,
            true,
            false,
            false,
            "reachable",
            PyronautValidateConfigMain.ReportFormat.HTML,
            tempDir.resolve("reports"),
            tempDir,
            List.of("dev"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "run"
        );

        String message = (String) method.invoke(null, settings);
        assertEquals("Validating configuration for environments: [dev]", message);
    }

    @Test
    void executorSuppressesDefaultEnvironmentLogger() throws Exception {
        String property = "org.slf4j.simpleLogger.log.io.micronaut.context.env.DefaultEnvironment";
        String previous = System.getProperty(property);
        try {
            System.clearProperty(property);
            Class<?> executorType = Class.forName("io.micronaut.pyronaut.validateconfig.MicronautConfigurationValidatorExecutor");
            var method = executorType.getDeclaredMethod("suppressDefaultEnvironmentLogging");
            method.setAccessible(true);
            method.invoke(null);

            assertEquals("error", System.getProperty(property));
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
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

            [tool.pyronaut.validation]
            suppressions = ["micronaut.home"]
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

    private static void restoreJavaHome(String previousJavaHome) {
        if (previousJavaHome == null) {
            System.clearProperty("java.home");
        } else {
            System.setProperty("java.home", previousJavaHome);
        }
    }

    private static void restoreProperty(String property, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, previousValue);
        }
    }
}

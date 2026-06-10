/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.pyronaut.createapp;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PyronautCreateAppMainTest {
    @TempDir
    Path tempDir;

    @Test
    void helpUsesCreateCommandName() {
        try (ApplicationContext context = ApplicationContext.run()) {
            CommandLine commandLine = new CommandLine(context.getBean(PyronautCreateAppMain.class));
            assertEquals("pyronaut-create", commandLine.getCommandName());
        }
    }

    @Test
    void createAppWritesIntoNamedDirectory() throws Exception {
        Execution execution = execute("demo", "--output", tempDir.toString());

        Path project = tempDir.resolve("demo");
        assertEquals(0, execution.exitCode());
        assertTrue(Files.exists(project.resolve("pyproject.toml")));
        assertTrue(Files.exists(project.resolve("src/demo/controller.py")));
        assertTrue(Files.exists(project.resolve("tests/test_demo.py")));
        assertTrue(Files.exists(project.resolve(".agents/skills/pyronaut-project/SKILL.md")));
        assertTrue(Files.exists(project.resolve(".agents/skills/pyronaut-cli/SKILL.md")));
        assertTrue(Files.exists(project.resolve(".agents/skills/pyronaut-coding/SKILL.md")));
        String pyproject = Files.readString(project.resolve("pyproject.toml"), StandardCharsets.UTF_8);
        String application = Files.readString(project.resolve("config/application.toml"), StandardCharsets.UTF_8);
        String testApplication = Files.readString(project.resolve("tests-config/application-test.toml"), StandardCharsets.UTF_8);
        assertTrue(pyproject.contains("[tool.pyronaut.test-resources]"));
        assertTrue(pyproject.contains("enabled = false"));
        assertTrue(pyproject.contains("runtime = [\n"));
        assertFalse(pyproject.contains("runtime = ['"));
        assertTrue(application.contains("[micronaut.application]\nname = 'demo'"));
        assertFalse(application.contains("micronaut.application.name = 'demo'"));
        assertTrue(testApplication.contains("[micronaut.server]\nport = -1"));
        assertFalse(testApplication.contains("micronaut.server.port = -1"));
    }

    @Test
    void inplaceWritesIntoSelectedOutputDirectory() throws Exception {
        Path project = tempDir.resolve("existing");
        Files.createDirectories(project);

        Execution execution = execute("demo", "--inplace", "--output", project.toString());

        assertEquals(0, execution.exitCode());
        assertTrue(Files.exists(project.resolve("pyproject.toml")));
        assertTrue(Files.exists(project.resolve("src/demo/controller.py")));
        assertFalse(Files.exists(project.resolve("demo")));
    }

    @Test
    void outputUsesNameSubdirectoryByDefault() throws Exception {
        Execution execution = execute("demo", "--output", tempDir.resolve("apps").toString());

        assertEquals(0, execution.exitCode());
        assertTrue(Files.exists(tempDir.resolve("apps/demo/pyproject.toml")));
    }

    @Test
    void selectedFeaturesContributeDependenciesAndConfig() throws Exception {
        Execution execution = execute(
            "demo",
            "--output",
            tempDir.toString(),
            "--features",
            "data-jdbc,mysql,json-schema,test-resources"
        );

        String pyproject = Files.readString(tempDir.resolve("demo/pyproject.toml"), StandardCharsets.UTF_8);
        String application = Files.readString(tempDir.resolve("demo/config/application.toml"), StandardCharsets.UTF_8);
        assertEquals(0, execution.exitCode());
        assertTrue(pyproject.contains("io.micronaut.data:micronaut-data-jdbc"));
        assertTrue(pyproject.contains("com.mysql:mysql-connector-j"));
        assertTrue(pyproject.contains("[tool.pyronaut.test-resources]"));
        assertTrue(pyproject.contains("enabled = true"));
        assertFalse(pyproject.contains("enabled = false"));
        assertTrue(application.contains("[datasources.default]"));
    }

    @Test
    void packageOptionSupportsNestedPythonPackage() throws Exception {
        Execution execution = execute("demo", "--output", tempDir.toString(), "--package", "example.service");

        Path project = tempDir.resolve("demo");
        assertEquals(0, execution.exitCode());
        assertFalse(Files.exists(project.resolve("src/example/__init__.py")));
        assertFalse(Files.exists(project.resolve("src/example/service/__init__.py")));
        assertTrue(Files.exists(project.resolve("src/example/service/controller.py")));
        assertTrue(Files.exists(project.resolve("tests/test_example_service.py")));
    }

    @Test
    void listFeaturesPrintsVisibleFeatureNames() {
        Execution execution = execute("--list-features");

        assertEquals(0, execution.exitCode());
        assertTrue(execution.out().contains("http-server-netty"));
        assertTrue(execution.out().contains("pyronaut-pytest"));
        assertFalse(execution.out().contains("gradle -"));
    }

    @Test
    void invalidFeatureFailsWithUsageError() {
        Execution execution = execute("demo", "--output", tempDir.toString(), "--features", "missing-feature");

        assertEquals(CommandLine.ExitCode.USAGE, execution.exitCode());
        assertTrue(execution.err().contains("The requested feature does not exist"));
    }

    @Test
    void unsupportedFeatureFailsWithClearMessage() {
        Execution execution = execute("demo", "--output", tempDir.toString(), "--features", "gradle");

        assertEquals(CommandLine.ExitCode.USAGE, execution.exitCode());
        assertTrue(execution.err().contains("not supported for Pyronaut/Python projects"));
    }

    @Test
    void reflectionDependentFeatureFailsWithClearMessage() {
        Execution execution = execute("demo", "--output", tempDir.toString(), "--features", "jackson-databind");

        assertEquals(CommandLine.ExitCode.USAGE, execution.exitCode());
        assertTrue(execution.err().contains("not supported for Pyronaut/Python projects"));
    }

    @Test
    void existingTargetDirectoryFailsForNonInplaceGeneration() throws Exception {
        Files.createDirectories(tempDir.resolve("demo"));

        Execution execution = execute("demo", "--output", tempDir.toString());

        assertEquals(CommandLine.ExitCode.USAGE, execution.exitCode());
        assertTrue(execution.err().contains("Target directory already exists"));
    }

    @Test
    void projectNameCannotBeAPath() {
        Execution execution = execute("../demo", "--output", tempDir.toString());

        assertEquals(CommandLine.ExitCode.USAGE, execution.exitCode());
        assertTrue(execution.err().contains("Invalid project NAME"));
    }

    @Test
    void inplaceFailsOnConflictingGeneratedFiles() throws Exception {
        Path project = tempDir.resolve("demo");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), "existing", StandardCharsets.UTF_8);

        Execution execution = execute("demo", "--inplace", "--output", project.toString());

        assertEquals(CommandLine.ExitCode.USAGE, execution.exitCode());
        assertTrue(execution.err().contains("conflicting generated files"));
    }

    private Execution execute(String... args) {
        try (ApplicationContext context = ApplicationContext.run()) {
            CommandLine commandLine = new CommandLine(context.getBean(PyronautCreateAppMain.class));
            StringWriter out = new StringWriter();
            StringWriter err = new StringWriter();
            commandLine.setOut(new PrintWriter(out));
            commandLine.setErr(new PrintWriter(err));
            int exit = commandLine.execute(args);
            return new Execution(exit, out.toString(), err.toString());
        }
    }

    private record Execution(int exitCode, String out, String err) {
    }
}

package io.micronaut.pyronaut.processor;

import io.micronaut.pyronaut.config.model.PyprojectModelReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PyronautProcessorMainTest {

    @TempDir
    Path tempDir;

    @Test
    void usesCachedDefaultPaths() throws Exception {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project.resolve("__pyronaut__"));
        Files.createDirectories(project.resolve("src"));
        Files.createDirectories(project.resolve("tests"));
        Files.writeString(project.resolve("tests").resolve("sample_test.py"), "def test_example():\n    assert True\n", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("pyproject.toml"), minimalPyproject());
        Files.write(project.resolve("__pyronaut__").resolve("resolved-build-dependencies"), List.of("/tmp/build-a.jar"), StandardCharsets.UTF_8);
        Files.write(project.resolve("__pyronaut__").resolve("resolved-runtime-dependencies"), List.of("/tmp/runtime-a.jar"), StandardCharsets.UTF_8);
        Files.write(project.resolve("__pyronaut__").resolve("resolved-test-dependencies"), List.of("/tmp/test-a.jar"), StandardCharsets.UTF_8);

        CapturingExecutor executor = new CapturingExecutor();
        PyronautProcessorMain command = new PyronautProcessorMain(new PyprojectModelReader(), executor);
        command.projectDir = project;

        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), command.call());
        assertEquals(2, executor.requests.size());

        PyronautCompilerExecutor.CompileRequest mainRequest = executor.requests.get(0);
        assertEquals(project.resolve("src").toAbsolutePath().normalize(), mainRequest.pythonSrc());
        assertEquals(project.resolve("src-java").toAbsolutePath().normalize(), mainRequest.javaSrc());
        assertEquals(project.resolve("__pyronaut__/classes").toAbsolutePath().normalize(), mainRequest.targetDir());
        assertEquals(Path.of("/tmp/build-a.jar"), mainRequest.annotationProcessorPath().getFirst());
        assertEquals(Path.of("/tmp/runtime-a.jar"), mainRequest.classpath().getFirst());

        PyronautCompilerExecutor.CompileRequest testRequest = executor.requests.get(1);
        assertEquals("python", testRequest.pythonSrc().getFileName().toString());
        assertEquals("java", testRequest.javaSrc().getFileName().toString());
        assertEquals(testRequest.pythonSrc().getParent(), testRequest.javaSrc().getParent());
        assertEquals(project.resolve("__pyronaut__/test-classes").toAbsolutePath().normalize(), testRequest.targetDir());
        assertEquals(Path.of("/tmp/build-a.jar"), testRequest.annotationProcessorPath().getFirst());
        assertEquals(Path.of("/tmp/test-a.jar"), testRequest.classpath().getFirst());
        assertFalse(testRequest.classpath().contains(project.resolve("__pyronaut__/classes").toAbsolutePath().normalize()));
    }

    @Test
    void failsWhenBuildCacheMissing() throws Exception {
        Path project = tempDir.resolve("project-missing-cache");
        Files.createDirectories(project.resolve("__pyronaut__"));
        Files.writeString(project.resolve("pyproject.toml"), minimalPyproject());

        PyronautProcessorMain command = new PyronautProcessorMain(new PyprojectModelReader(), new CapturingExecutor());
        command.projectDir = project;

        assertEquals(PyronautProcessorExitCode.PRECONDITION_FAILED.code(), command.call());
    }

    @Test
    void allowsExplicitClasspathOverrides() throws Exception {
        Path project = tempDir.resolve("project-overrides");
        Files.createDirectories(project);
        Files.createDirectories(project.resolve("src"));
        Files.createDirectories(project.resolve("tests"));
        Files.writeString(project.resolve("tests").resolve("sample_test.py"), "def test_example():\n    assert True\n", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("pyproject.toml"), minimalPyproject());

        CapturingExecutor executor = new CapturingExecutor();
        PyronautProcessorMain command = new PyronautProcessorMain(new PyprojectModelReader(), executor);
        command.projectDir = project;
        command.annotationProcessorPath = List.of(Path.of("/tmp/processor-override.jar"));
        command.classpath = List.of(Path.of("/tmp/runtime-override.jar"));
        command.testClasspath = List.of(Path.of("/tmp/test-override.jar"));
        command.options = List.of("-parameters");

        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), command.call());
        assertEquals(2, executor.requests.size());
        PyronautCompilerExecutor.CompileRequest mainRequest = executor.requests.get(0);
        assertEquals(Path.of("/tmp/processor-override.jar"), mainRequest.annotationProcessorPath().getFirst());
        assertEquals(Path.of("/tmp/runtime-override.jar"), mainRequest.classpath().getFirst());
        assertEquals(List.of("-parameters"), mainRequest.options());

        PyronautCompilerExecutor.CompileRequest testRequest = executor.requests.get(1);
        assertEquals(Path.of("/tmp/processor-override.jar"), testRequest.annotationProcessorPath().getFirst());
        assertEquals(Path.of("/tmp/test-override.jar"), testRequest.classpath().getFirst());
        assertFalse(testRequest.classpath().contains(project.resolve("__pyronaut__/classes").toAbsolutePath().normalize()));
        assertEquals(List.of("-parameters"), testRequest.options());
    }

    @Test
    void failsWhenTestCacheMissing() throws Exception {
        Path project = tempDir.resolve("project-missing-test-cache");
        Files.createDirectories(project.resolve("__pyronaut__"));
        Files.writeString(project.resolve("pyproject.toml"), minimalPyproject());
        Files.write(project.resolve("__pyronaut__").resolve("resolved-build-dependencies"), List.of("/tmp/build-a.jar"), StandardCharsets.UTF_8);
        Files.write(project.resolve("__pyronaut__").resolve("resolved-runtime-dependencies"), List.of("/tmp/runtime-a.jar"), StandardCharsets.UTF_8);

        PyronautProcessorMain command = new PyronautProcessorMain(new PyprojectModelReader(), new CapturingExecutor());
        command.projectDir = project;

        assertEquals(PyronautProcessorExitCode.PRECONDITION_FAILED.code(), command.call());
    }

    @Test
    void compilesMergedSourcesIntoTestTargetWhenNoDedicatedTestSourcesExist() throws Exception {
        Path project = tempDir.resolve("project-without-tests");
        Files.createDirectories(project.resolve("__pyronaut__"));
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("src").resolve("sample.py"), "VALUE = 42\n", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("pyproject.toml"), minimalPyproject());
        Files.write(project.resolve("__pyronaut__").resolve("resolved-build-dependencies"), List.of("/tmp/build-a.jar"), StandardCharsets.UTF_8);
        Files.write(project.resolve("__pyronaut__").resolve("resolved-runtime-dependencies"), List.of("/tmp/runtime-a.jar"), StandardCharsets.UTF_8);
        Files.write(project.resolve("__pyronaut__").resolve("resolved-test-dependencies"), List.of("/tmp/test-a.jar"), StandardCharsets.UTF_8);

        CapturingExecutor executor = new CapturingExecutor();
        PyronautProcessorMain command = new PyronautProcessorMain(new PyprojectModelReader(), executor);
        command.projectDir = project;

        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), command.call());
        assertEquals(2, executor.requests.size());
        PyronautCompilerExecutor.CompileRequest testRequest = executor.requests.get(1);
        assertEquals("python", testRequest.pythonSrc().getFileName().toString());
        assertEquals("java", testRequest.javaSrc().getFileName().toString());
        assertEquals(testRequest.pythonSrc().getParent(), testRequest.javaSrc().getParent());
        assertEquals(project.resolve("__pyronaut__/test-classes").toAbsolutePath().normalize(), testRequest.targetDir());
    }

    @Test
    void runtimeFailureIncludesVerboseHintWithoutStacktrace() throws Exception {
        Path project = tempDir.resolve("project-runtime-failure");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), minimalPyproject());

        PyronautProcessorMain command = new PyronautProcessorMain(
            new PyprojectModelReader(),
            new FailingExecutor(new RuntimeException("boom"))
        );
        command.projectDir = project;
        command.annotationProcessorPath = List.of(Path.of("/tmp/processor-override.jar"));
        command.classpath = List.of(Path.of("/tmp/runtime-override.jar"));
        command.testClasspath = List.of(Path.of("/tmp/test-override.jar"));

        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
        try {
            assertEquals(PyronautProcessorExitCode.PROCESSING_ERROR.code(), command.call());
        } finally {
            System.setErr(originalErr);
        }

        String output = stderr.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Processing failed: boom"));
        assertTrue(output.contains("Re-run with --verbose for full diagnostics."));
        assertFalse(output.contains("java.lang.RuntimeException: boom"));
        assertFalse(output.contains("\tat "));
    }

    @Test
    void verboseRuntimeFailurePrintsStacktrace() throws Exception {
        Path project = tempDir.resolve("project-verbose-runtime-failure");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), minimalPyproject());

        PyronautProcessorMain command = new PyronautProcessorMain(
            new PyprojectModelReader(),
            new FailingExecutor(new RuntimeException("boom"))
        );
        command.projectDir = project;
        command.verbose = true;
        command.annotationProcessorPath = List.of(Path.of("/tmp/processor-override.jar"));
        command.classpath = List.of(Path.of("/tmp/runtime-override.jar"));
        command.testClasspath = List.of(Path.of("/tmp/test-override.jar"));

        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
        try {
            assertEquals(PyronautProcessorExitCode.PROCESSING_ERROR.code(), command.call());
        } finally {
            System.setErr(originalErr);
        }

        String output = stderr.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Processing failed: boom"));
        assertTrue(output.contains("java.lang.RuntimeException: boom"));
        assertTrue(output.contains("\tat "));
        assertFalse(output.contains("Re-run with --verbose for full diagnostics."));
    }

    @Test
    void skipsCompilationOnCacheHitForUnchangedSources() throws Exception {
        Path project = tempDir.resolve("project-cache-hit");
        setupProjectWithSourcesAndCaches(project);

        CapturingExecutor executor = new CapturingExecutor();
        PyronautProcessorMain command = new PyronautProcessorMain(new PyprojectModelReader(), executor);
        command.projectDir = project;
        command.progress = "off";

        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), command.call());
        assertEquals(2, executor.requests.size());
        assertTrue(Files.exists(project.resolve("__pyronaut__").resolve(ProcessorSourceCache.MAIN_HASH_FILE)));
        assertTrue(Files.exists(project.resolve("__pyronaut__").resolve(ProcessorSourceCache.TEST_HASH_FILE)));

        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), command.call());
        assertEquals(2, executor.requests.size());
    }

    @Test
    void recompilesOnlyAffectedPassesWhenSourcesChange() throws Exception {
        Path project = tempDir.resolve("project-cache-invalidation");
        setupProjectWithSourcesAndCaches(project);

        CapturingExecutor executor = new CapturingExecutor();
        PyronautProcessorMain command = new PyronautProcessorMain(new PyprojectModelReader(), executor);
        command.projectDir = project;
        command.progress = "off";

        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), command.call());
        assertEquals(2, executor.requests.size());

        Files.writeString(project.resolve("tests").resolve("sample_test.py"), "def test_example():\n    assert 2 == 2\n", StandardCharsets.UTF_8);
        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), command.call());
        assertEquals(3, executor.requests.size());
        assertEquals(project.resolve("__pyronaut__/test-classes").toAbsolutePath().normalize(), executor.requests.get(2).targetDir());

        Files.writeString(project.resolve("src").resolve("sample.py"), "VALUE = 99\n", StandardCharsets.UTF_8);
        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), command.call());
        assertEquals(5, executor.requests.size());
        assertEquals(project.resolve("__pyronaut__/classes").toAbsolutePath().normalize(), executor.requests.get(3).targetDir());
        assertEquals(project.resolve("__pyronaut__/test-classes").toAbsolutePath().normalize(), executor.requests.get(4).targetDir());
    }

    @Test
    void changingCompilerOptionsInvalidatesBothPasses() throws Exception {
        Path project = tempDir.resolve("project-options-invalidation");
        setupProjectWithSourcesAndCaches(project);

        CapturingExecutor executor = new CapturingExecutor();
        PyronautProcessorMain command = new PyronautProcessorMain(new PyprojectModelReader(), executor);
        command.projectDir = project;
        command.progress = "off";

        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), command.call());
        assertEquals(2, executor.requests.size());

        command.options = List.of("-parameters");
        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), command.call());
        assertEquals(4, executor.requests.size());
    }

    @Test
    void invalidProgressModeReturnsUsageError() throws Exception {
        Path project = tempDir.resolve("project-invalid-progress");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), minimalPyproject());

        PyronautProcessorMain command = new PyronautProcessorMain(new PyprojectModelReader(), new CapturingExecutor());
        command.projectDir = project;
        command.progress = "loud";

        assertEquals(PyronautProcessorExitCode.USAGE_ERROR.code(), command.call());
    }

    private static void setupProjectWithSourcesAndCaches(Path project) throws Exception {
        Files.createDirectories(project.resolve("__pyronaut__"));
        Files.createDirectories(project.resolve("src"));
        Files.createDirectories(project.resolve("tests"));
        Files.writeString(project.resolve("src").resolve("sample.py"), "VALUE = 42\n", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("tests").resolve("sample_test.py"), "def test_example():\n    assert True\n", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("pyproject.toml"), minimalPyproject());
        Files.write(project.resolve("__pyronaut__").resolve("resolved-build-dependencies"), List.of("/tmp/build-a.jar"), StandardCharsets.UTF_8);
        Files.write(project.resolve("__pyronaut__").resolve("resolved-runtime-dependencies"), List.of("/tmp/runtime-a.jar"), StandardCharsets.UTF_8);
        Files.write(project.resolve("__pyronaut__").resolve("resolved-test-dependencies"), List.of("/tmp/test-a.jar"), StandardCharsets.UTF_8);
    }

    private static String minimalPyproject() {
        return """
            [project]
            name = "processor-test"
            version = "1.0.0"

            [tool.pyronaut]
            repositories = ["mavenCentral"]

            [tool.pyronaut.dependencies]
            runtime = []
            build = []
            test = []
            """;
    }

    private static final class CapturingExecutor implements PyronautCompilerExecutor {
        private final List<CompileRequest> requests = new ArrayList<>();

        @Override
        public void compile(CompileRequest request) {
            requests.add(request);
            try {
                Files.createDirectories(request.targetDir());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private record FailingExecutor(RuntimeException error) implements PyronautCompilerExecutor {
        @Override
        public void compile(CompileRequest request) {
            throw error;
        }
    }
}

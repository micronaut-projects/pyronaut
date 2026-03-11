package io.micronaut.pyronaut.processor;

import io.micronaut.pyronaut.config.model.PyprojectModelReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PyronautProcessorMainTest {

    @TempDir
    Path tempDir;

    @Test
    void usesCachedDefaultPaths() throws Exception {
        Path project = tempDir.resolve("project");
        Files.createDirectories(project.resolve("__pyronaut__"));
        Files.writeString(project.resolve("pyproject.toml"), minimalPyproject());
        Files.write(project.resolve("__pyronaut__").resolve("resolved-build-dependencies"), List.of("/tmp/build-a.jar"), StandardCharsets.UTF_8);
        Files.write(project.resolve("__pyronaut__").resolve("resolved-runtime-dependencies"), List.of("/tmp/runtime-a.jar"), StandardCharsets.UTF_8);

        CapturingExecutor executor = new CapturingExecutor();
        PyronautProcessorMain command = new PyronautProcessorMain(new PyprojectModelReader(), executor);
        command.projectDir = project;

        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), command.call());
        PyronautCompilerExecutor.CompileRequest request = executor.requests.getFirst();
        assertEquals(project.resolve("src").toAbsolutePath().normalize(), request.pythonSrc());
        assertEquals(project.resolve("src-java").toAbsolutePath().normalize(), request.javaSrc());
        assertEquals(project.resolve("__pyronaut__/classes").toAbsolutePath().normalize(), request.targetDir());
        assertEquals(Path.of("/tmp/build-a.jar"), request.annotationProcessorPath().getFirst());
        assertEquals(Path.of("/tmp/runtime-a.jar"), request.classpath().getFirst());
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
        Files.writeString(project.resolve("pyproject.toml"), minimalPyproject());

        CapturingExecutor executor = new CapturingExecutor();
        PyronautProcessorMain command = new PyronautProcessorMain(new PyprojectModelReader(), executor);
        command.projectDir = project;
        command.annotationProcessorPath = List.of(Path.of("/tmp/processor-override.jar"));
        command.classpath = List.of(Path.of("/tmp/runtime-override.jar"));
        command.options = List.of("-parameters");

        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), command.call());
        PyronautCompilerExecutor.CompileRequest request = executor.requests.getFirst();
        assertEquals(Path.of("/tmp/processor-override.jar"), request.annotationProcessorPath().getFirst());
        assertEquals(Path.of("/tmp/runtime-override.jar"), request.classpath().getFirst());
        assertEquals(List.of("-parameters"), request.options());
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
        }
    }
}

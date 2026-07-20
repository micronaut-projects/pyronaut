package io.micronaut.pyronaut.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PyronautProcessorJvmSmokeTest extends AbstractPyronautProcessorSmokeTest {

    @TempDir
    Path tempDir;

    @Test
    void jvmProcessProcessesDecoratedHelloWorldSources() throws Exception {
        ProcessResult result = runJvmProcessor(tempDir.resolve("project"), helloWorldProjectFiles());
        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), result.exitCode(), result.output());
        Path classesDir = result.project().resolve("__pyronaut__/classes");
        assertMainArtifacts(classesDir, result.output());

        Path testClassesDir = result.project().resolve("__pyronaut__/test-classes");
        assertMainArtifacts(testClassesDir, result.output());
        assertExists(testClassesDir, "META-INF/GRAALPY-VFS/micronaut-application/src/test_controller.py", result.output());
    }

    @Test
    void jvmProcessProcessesMicronautDataRepositorySources() throws Exception {
        ProcessResult result = runJvmProcessor(tempDir.resolve("data-project"), micronautDataProjectFiles());

        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), result.exitCode(), result.output());
        Path classesDir = result.project().resolve("__pyronaut__/classes");
        assertMicronautDataArtifacts(classesDir, result.output());

        Path testClassesDir = result.project().resolve("__pyronaut__/test-classes");
        assertMicronautDataArtifacts(testClassesDir, result.output());
        assertExists(testClassesDir, "META-INF/GRAALPY-VFS/micronaut-application/src/test_repository.py", result.output());
    }

    @Test
    void jvmProcessEmitsPythonBytecodeWhenConfigured() throws Exception {
        ProcessResult result = runJvmProcessor(
            tempDir.resolve("bytecode-project"),
            helloWorldProjectFiles(),
            bytecodeEnabledPyproject("processor-jvm-bytecode")
        );
        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), result.exitCode(), result.output());
        Path filesList = result.project().resolve("__pyronaut__/classes/META-INF/GRAALPY-VFS/micronaut-application/fileslist.txt");
        String content = Files.readString(filesList);
        org.junit.jupiter.api.Assertions.assertTrue(content.contains("__pycache__"), content);
        org.junit.jupiter.api.Assertions.assertTrue(content.contains(".pyc"), content);
    }
}

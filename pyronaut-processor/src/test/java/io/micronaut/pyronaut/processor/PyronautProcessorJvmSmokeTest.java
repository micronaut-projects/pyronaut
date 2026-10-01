package io.micronaut.pyronaut.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

    @Test
    void jvmProcessReusesIncrementalStateAcrossRuns() throws Exception {
        Path project = tempDir.resolve("incremental-project");
        ProcessResult first = runJvmProcessor(
            project,
            helloWorldProjectFiles(),
            incrementalPyproject("processor-jvm-incremental")
        );
        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), first.exitCode(), first.output());

        var changedFiles = helloWorldProjectFiles();
        changedFiles.put("src/main.py", "from app import HelloController\nVALUE = 2\n");
        ProcessResult second = runJvmProcessor(
            project,
            changedFiles,
            incrementalPyproject("processor-jvm-incremental"),
            List.of(),
            "auto"
        );

        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), second.exitCode(), second.output());
        assertMainArtifacts(project.resolve("__pyronaut__/classes"), second.output());
        assertExists(project, "__pyronaut__/incremental/main/state.properties", second.output());
        org.junit.jupiter.api.Assertions.assertTrue(
            second.output().contains("Incrementally processing main sources"),
            second.output()
        );
        org.junit.jupiter.api.Assertions.assertTrue(
            second.output().contains("src/main.py"),
            second.output()
        );
    }

    @Test
    void jvmProcessReusesCompilerDaemonAcrossRunsAndShutsDownWhenIdle() throws Exception {
        Path project = tempDir.resolve("daemon-project");
        String pyproject = daemonIncrementalPyproject("processor-jvm-daemon");
        List<String> jvmOptions = List.of(
            "-Dpyronaut.processor.daemon.idle-timeout-seconds=3",
            // The daemon is slow to start on the 2-CPU GitHub-hosted runners.
            "-Dpyronaut.processor.daemon.start-timeout-seconds=120"
        );

        ProcessResult first = runJvmProcessor(
            project,
            helloWorldProjectFiles(),
            pyproject,
            jvmOptions
        );
        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), first.exitCode(), first.output());
        long daemonPid = daemonPid(project, first.output());
        org.junit.jupiter.api.Assertions.assertTrue(ProcessHandle.of(daemonPid).orElseThrow().isAlive());

        var changedFiles = helloWorldProjectFiles();
        changedFiles.put("src/main.py", "from app import HelloController\nVALUE = 3\n");
        ProcessResult second = runJvmProcessor(project, changedFiles, pyproject, jvmOptions);

        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), second.exitCode(), second.output());
        assertEquals(daemonPid, daemonPid(project, second.output()));
        assertMainArtifacts(project.resolve("__pyronaut__/classes"), second.output());

        var invalidFiles = helloWorldProjectFiles();
        invalidFiles.put("src/app.py", "class Broken(\n");
        ProcessResult invalid = runJvmProcessor(project, invalidFiles, pyproject, jvmOptions);
        assertEquals(PyronautProcessorExitCode.PROCESSING_ERROR.code(), invalid.exitCode(), invalid.output());
        org.junit.jupiter.api.Assertions.assertFalse(
            invalid.output().isBlank(),
            "Daemon compiler diagnostics were not forwarded to the client"
        );

        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(8).toNanos();
        while (ProcessHandle.of(daemonPid).map(ProcessHandle::isAlive).orElse(false)
            && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        org.junit.jupiter.api.Assertions.assertFalse(
            ProcessHandle.of(daemonPid).map(ProcessHandle::isAlive).orElse(false),
            "Compiler daemon did not stop after its idle timeout"
        );
    }
}

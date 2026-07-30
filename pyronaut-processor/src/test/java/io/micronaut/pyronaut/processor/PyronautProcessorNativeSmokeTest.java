package io.micronaut.pyronaut.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "pyronaut.processor.native.binary", matches = ".+")
class PyronautProcessorNativeSmokeTest extends AbstractPyronautProcessorSmokeTest {

    @TempDir
    Path tempDir;

    @Test
    void nativeBinaryProcessesDecoratedHelloWorldSources() throws Exception {
        String binaryPath = System.getProperty("pyronaut.processor.native.binary");
        ProcessResult result = runNativeProcessor(binaryPath, tempDir.resolve("project"), helloWorldProjectFiles());
        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), result.exitCode(), result.output());
        Path classesDir = result.project().resolve("__pyronaut__/classes");
        assertMainArtifacts(classesDir, result.output());

        Path testClassesDir = result.project().resolve("__pyronaut__/test-classes");
        assertMainArtifacts(testClassesDir, result.output());
        assertExists(testClassesDir, "META-INF/GRAALPY-VFS/micronaut-application/src/test_controller.py", result.output());
    }

    @Test
    void nativeBinaryProcessesMicronautDataRepositorySources() throws Exception {
        String binaryPath = System.getProperty("pyronaut.processor.native.binary");
        ProcessResult result = runNativeProcessor(binaryPath, tempDir.resolve("data-project"), micronautDataProjectFiles());

        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), result.exitCode(), result.output());
        Path classesDir = result.project().resolve("__pyronaut__/classes");
        assertMicronautDataArtifacts(classesDir, result.output());

        Path testClassesDir = result.project().resolve("__pyronaut__/test-classes");
        assertMicronautDataArtifacts(testClassesDir, result.output());
        assertExists(testClassesDir, "META-INF/GRAALPY-VFS/micronaut-application/src/test_repository.py", result.output());
    }

    @Test
    void nativeBinaryEmitsPythonBytecodeWhenConfigured() throws Exception {
        String binaryPath = System.getProperty("pyronaut.processor.native.binary");
        ProcessResult result = runNativeProcessor(
            binaryPath,
            tempDir.resolve("bytecode-project"),
            helloWorldProjectFiles(),
            bytecodeEnabledPyproject("processor-native-bytecode")
        );
        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), result.exitCode(), result.output());
        Path filesList = result.project().resolve("__pyronaut__/classes/META-INF/GRAALPY-VFS/micronaut-application/fileslist.txt");
        String content = java.nio.file.Files.readString(filesList);
        assertTrue(content.contains("__pycache__"), content);
        assertTrue(content.contains(".pyc"), content);
    }

    @Test
    void nativeBinaryMatchesJvmOutputForDecoratedHelloWorldSources() throws Exception {
        String binaryPath = System.getProperty("pyronaut.processor.native.binary");
        ProcessResult nativeResult = runNativeProcessor(binaryPath, tempDir.resolve("compare-project-native"), helloWorldProjectFiles());
        ProcessResult jvmResult = runJvmProcessor(tempDir.resolve("compare-project-jvm"), helloWorldProjectFiles());

        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), nativeResult.exitCode(), nativeResult.output());
        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), jvmResult.exitCode(), jvmResult.output());
        assertEquals(
            snapshotOutput(jvmResult.project().resolve("__pyronaut__/classes")),
            snapshotOutput(nativeResult.project().resolve("__pyronaut__/classes"))
        );
        assertEquals(
            snapshotOutput(jvmResult.project().resolve("__pyronaut__/test-classes")),
            snapshotOutput(nativeResult.project().resolve("__pyronaut__/test-classes"))
        );
    }

    @Test
    void nativeBinaryMatchesJvmOutputForMicronautDataRepositorySources() throws Exception {
        String binaryPath = System.getProperty("pyronaut.processor.native.binary");
        ProcessResult nativeResult = runNativeProcessor(binaryPath, tempDir.resolve("compare-data-project-native"), micronautDataProjectFiles());
        ProcessResult jvmResult = runJvmProcessor(tempDir.resolve("compare-data-project-jvm"), micronautDataProjectFiles());

        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), nativeResult.exitCode(), nativeResult.output());
        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), jvmResult.exitCode(), jvmResult.output());
        assertEquals(
            snapshotOutput(jvmResult.project().resolve("__pyronaut__/classes")),
            snapshotOutput(nativeResult.project().resolve("__pyronaut__/classes"))
        );
        assertEquals(
            snapshotOutput(jvmResult.project().resolve("__pyronaut__/test-classes")),
            snapshotOutput(nativeResult.project().resolve("__pyronaut__/test-classes"))
        );
    }

    @Test
    void nativeBinaryReusesIncrementalStateAcrossRuns() throws Exception {
        String binaryPath = System.getProperty("pyronaut.processor.native.binary");
        Path project = tempDir.resolve("incremental-project");
        ProcessResult first = runNativeProcessor(
            binaryPath,
            project,
            helloWorldProjectFiles(),
            incrementalPyproject("processor-native-incremental")
        );
        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), first.exitCode(), first.output());

        var changedFiles = helloWorldProjectFiles();
        changedFiles.put("src/main.py", "from app import HelloController\nVALUE = 2\n");
        ProcessResult second = runNativeProcessor(
            binaryPath,
            project,
            changedFiles,
            incrementalPyproject("processor-native-incremental")
        );

        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), second.exitCode(), second.output());
        assertMainArtifacts(project.resolve("__pyronaut__/classes"), second.output());
        assertTrue(
            Files.isRegularFile(project.resolve("__pyronaut__/incremental/main/state.properties")),
            second.output()
        );
    }
}

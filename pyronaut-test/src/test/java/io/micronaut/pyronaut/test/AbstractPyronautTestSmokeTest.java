package io.micronaut.pyronaut.test;

import io.micronaut.pyronaut.processor.PyronautProcessorMain;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

abstract class AbstractPyronautTestSmokeTest {

    @TempDir
    Path tempDir;

    protected void assertSelectedClassRunsUsingProjectClasspath() throws Exception {
        Path project = tempDir.resolve("app");
        Path pyronautDir = project.resolve("__pyronaut__");
        Path testClassesDir = pyronautDir.resolve("test-classes");
        Files.createDirectories(testClassesDir);
        Files.writeString(project.resolve("pyproject.toml"), minimalPyproject(), StandardCharsets.UTF_8);

        Path supportJar = buildSupportJar(tempDir.resolve("support"));
        compileGeneratedTestClass(testClassesDir, supportJar);
        Files.writeString(
            pyronautDir.resolve("resolved-test-dependencies"),
            testClasspathManifest(supportJar),
            StandardCharsets.UTF_8
        );

        RunResult result = runPyronautTest(project);
        assertEquals(0, result.exitCode(), result.output());
    }

    protected void assertPythonLogbackModuleLoadsUsingProjectClasspath() throws Exception {
        Path project = tempDir.resolve("app");
        Path pyronautDir = project.resolve("__pyronaut__");
        Path testClassesDir = pyronautDir.resolve("test-classes");
        Path testsDir = project.resolve("tests");
        Files.createDirectories(testClassesDir);
        Files.createDirectories(testsDir);
        Files.writeString(project.resolve("pyproject.toml"), minimalPyproject(), StandardCharsets.UTF_8);
        Files.writeString(
            testsDir.resolve("test_logback.py"),
            """
                from logback.config import dictConfig


                def test_imports_logback_config():
                    assert dictConfig is not None
                """,
            StandardCharsets.UTF_8
        );
        Files.writeString(
            pyronautDir.resolve("resolved-test-dependencies"),
            testClasspathManifest(null),
            StandardCharsets.UTF_8
        );
        Files.writeString(
            pyronautDir.resolve("resolved-runtime-dependencies"),
            testClasspathManifest(null),
            StandardCharsets.UTF_8
        );

        RunResult result = runDefaultPytest(project);
        assertEquals(0, result.exitCode(), result.output());
    }

    protected void assertProcessedPythonBeanLookupWorks() throws Exception {
        Path project = tempDir.resolve("app");
        Path pyronautDir = project.resolve("__pyronaut__");
        Path sourceDir = project.resolve("src/helloworld");
        Path testsDir = project.resolve("tests");
        Files.createDirectories(sourceDir);
        Files.createDirectories(testsDir);
        Files.createDirectories(pyronautDir);
        Files.writeString(project.resolve("pyproject.toml"), minimalPyproject(), StandardCharsets.UTF_8);
        Files.writeString(
            sourceDir.resolve("services.py"),
            """
                from jakarta.inject import Singleton


                @Singleton
                class MessageService:
                    def say_hello(self, name: str) -> str:
                        return f"Hello {name}!!!!!!"
                """,
            StandardCharsets.UTF_8
        );
        Files.writeString(
            testsDir.resolve("test_processed_lookup.py"),
            """
                import pytest
                from pyronaut.test import *


                @pytest.fixture
                def my_context(request):
                    fixture = micronaut_test_fixture(
                        request,
                        MicronautTest(
                            environments=["foo"],
                            transactional=False,
                            properties={"custom.property": "test_value"},
                        ),
                    )
                    yield fixture
                    fixture.stop()


                @pytest.fixture
                def my_service(my_context):
                    return my_context["helloworld.MessageService"]


                def test_processed_python_service_lookup(my_service):
                    assert my_service is not None
                    assert my_service.say_hello("John") == "Hello John!!!!!!"
                """,
            StandardCharsets.UTF_8
        );
        Files.writeString(
            pyronautDir.resolve("resolved-build-dependencies"),
            testClasspathManifest(null),
            StandardCharsets.UTF_8
        );
        Files.writeString(
            pyronautDir.resolve("resolved-test-dependencies"),
            testClasspathManifest(null),
            StandardCharsets.UTF_8
        );
        Files.writeString(
            pyronautDir.resolve("resolved-runtime-dependencies"),
            testClasspathManifest(null),
            StandardCharsets.UTF_8
        );
        int processExit = new picocli.CommandLine(new PyronautProcessorMain()).execute(
            "--project-dir", project.toString(),
            "--no-cache"
        );
        assertEquals(0, processExit);

        RunResult result = runDefaultPytest(project);
        assertEquals(0, result.exitCode(), result.output());
    }

    protected abstract RunResult runPyronautTest(Path project) throws Exception;
    protected abstract RunResult runDefaultPytest(Path project) throws Exception;

    protected static RunResult runJvmTest(Path project) throws Exception {
        return runJvmTest(project, "smoke.GeneratedPassingTest");
    }

    protected static RunResult runJvmTest(Path project, String className) throws Exception {
        List<String> classpathEntries = new ArrayList<>(nativeTestClasspathEntries(project));
        classpathEntries.addAll(currentRuntimeClasspathEntries());
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("--sun-misc-unsafe-memory-access=allow");
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("-cp");
        command.add(String.join(File.pathSeparator, dedupeExistingClasspathEntries(classpathEntries)));
        command.add(PyronautTestMain.class.getName());
        command.add("--project-dir");
        command.add(project.toString());
        if (className != null) {
            command.add("--select-class");
            command.add(className);
        }
        ProcessBuilder processBuilder = new ProcessBuilder(command)
            .redirectErrorStream(true);
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            processBuilder.environment().put("JAVA_HOME", javaHome);
        }
        Process process = processBuilder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        process.getInputStream().transferTo(output);
        int exitCode = process.waitFor();
        return new RunResult(exitCode, output.toString(StandardCharsets.UTF_8));
    }

    protected static List<String> nativeTestClasspathEntries(Path project) throws Exception {
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        addManifestEntries(entries, project.resolve("__pyronaut__/resolved-test-dependencies"));
        addManifestEntries(entries, project.resolve("__pyronaut__/resolved-runtime-dependencies"));
        addManifestEntries(entries, project.resolve("__pyronaut__/resolved-build-dependencies"));
        addDirectory(entries, project.resolve("__pyronaut__/test-classes"));
        if (!Files.isDirectory(project.resolve("__pyronaut__/test-classes"))) {
            addDirectory(entries, project.resolve("__pyronaut__/classes"));
        }
        addDirectory(entries, project.resolve("tests-config"));
        addDirectory(entries, project.resolve("config"));
        return List.copyOf(entries);
    }

    private static void addManifestEntries(LinkedHashSet<String> entries, Path manifest) throws Exception {
        if (!Files.exists(manifest)) {
            return;
        }
        for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Path path = Path.of(trimmed).toAbsolutePath().normalize();
            if (Files.exists(path)) {
                entries.add(path.toString());
            }
        }
    }

    private static void addDirectory(LinkedHashSet<String> entries, Path directory) {
        Path normalized = directory.toAbsolutePath().normalize();
        if (Files.isDirectory(normalized)) {
            entries.add(normalized.toString());
        }
    }

    private static List<String> dedupeExistingClasspathEntries(List<String> rawEntries) {
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        for (String entry : rawEntries) {
            String trimmed = entry == null ? "" : entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Path path = Path.of(trimmed).toAbsolutePath().normalize();
            if (Files.exists(path)) {
                entries.add(path.toString());
            }
        }
        return List.copyOf(entries);
    }

    private static Path javaExecutable() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isBlank()) {
            Path java = Path.of(javaHome, "bin", isWindows() ? "java.exe" : "java");
            if (Files.isExecutable(java)) {
                return java;
            }
        }
        return Path.of("java");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static Path buildSupportJar(Path workDir) throws Exception {
        Path sourceDir = workDir.resolve("src");
        Path classesDir = workDir.resolve("classes");
        Files.createDirectories(sourceDir);
        Files.createDirectories(classesDir);
        compileJava(
            sourceDir.resolve("smoke/Support.java"),
            """
                package smoke;

                public final class Support {
                    private Support() {
                    }

                    public static String message() {
                        return "support-ok";
                    }
                }
                """,
            classesDir,
            null
        );
        Path jarPath = workDir.resolve("support.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
            try (Stream<Path> stream = Files.walk(classesDir)) {
                for (Path file : stream.filter(Files::isRegularFile).toList()) {
                    String entryName = classesDir.relativize(file).toString().replace('\\', '/');
                    jar.putNextEntry(new JarEntry(entryName));
                    jar.write(Files.readAllBytes(file));
                    jar.closeEntry();
                }
            }
        }
        return jarPath;
    }

    private static void compileGeneratedTestClass(Path outputDir, Path supportJar) throws Exception {
        Path sourceDir = Files.createTempDirectory("pyronaut-test-smoke-src");
        compileJava(
            sourceDir.resolve("smoke/GeneratedPassingTest.java"),
            """
                package smoke;

                import org.junit.jupiter.api.Test;

                import static org.junit.jupiter.api.Assertions.assertEquals;

                public class GeneratedPassingTest {
                    @Test
                    void pass() {
                        assertEquals("support-ok", Support.message());
                    }
                }
                """,
            outputDir,
            supportJar.toAbsolutePath().normalize() + File.pathSeparator + currentRuntimeClasspath()
        );
    }

    private static void compileJava(Path sourceFile, String source, Path outputDir, String classpath) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JDK compiler is required for smoke test");
        }
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
        Files.createDirectories(outputDir);
        StringWriter compilerOutput = new StringWriter();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            List<String> options = classpath == null
                ? List.of("-d", outputDir.toString())
                : List.of("-classpath", classpath, "-d", outputDir.toString());
            boolean success = compiler.getTask(
                compilerOutput,
                fileManager,
                null,
                options,
                null,
                fileManager.getJavaFileObjects(sourceFile.toFile())
            ).call();
            if (!success) {
                throw new IllegalStateException("Compilation failed: " + compilerOutput);
            }
        }
    }

    private static String testClasspathManifest(Path supportJar) {
        LinkedHashSet<String> entries = currentRuntimeClasspathEntries();
        if (supportJar != null) {
            entries.add(supportJar.toAbsolutePath().normalize().toString());
        }
        return entries.stream().collect(Collectors.joining(System.lineSeparator(), "", System.lineSeparator()));
    }

    private static String currentRuntimeClasspath() {
        return String.join(File.pathSeparator, currentRuntimeClasspathEntries());
    }

    private static LinkedHashSet<String> currentRuntimeClasspathEntries() {
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        String rawClasspath = System.getProperty("java.class.path", "");
        if (rawClasspath.isBlank()) {
            return entries;
        }
        for (String entry : rawClasspath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Path path = Path.of(trimmed).toAbsolutePath().normalize();
            if (Files.exists(path)) {
                entries.add(path.toString());
            }
        }
        return entries;
    }

    private static String minimalPyproject() {
        return """
            [project]
            name = "pyronaut-test-smoke"
            version = "1.0.0"

            [tool.pyronaut]
            version = "5.0.0-SNAPSHOT"
            """;
    }

    protected record RunResult(int exitCode, String output) {
    }
}

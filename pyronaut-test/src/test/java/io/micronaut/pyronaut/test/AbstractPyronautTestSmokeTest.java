package io.micronaut.pyronaut.test;

import io.micronaut.pyronaut.processor.PyronautProcessorMain;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    protected void assertPytestUsesProcessedRuntimeSourcesForKeywordAliases() throws Exception {
        Path project = tempDir.resolve("keyword-alias-app");
        Path pyronautDir = project.resolve("__pyronaut__");
        Path sourceDir = project.resolve("src/example");
        Path testsDir = project.resolve("tests/example");
        Files.createDirectories(sourceDir);
        Files.createDirectories(testsDir);
        Files.createDirectories(pyronautDir);
        Files.writeString(project.resolve("pyproject.toml"), minimalPyproject(), StandardCharsets.UTF_8);
        Files.writeString(
            sourceDir.resolve("marker.py"),
            """
                from jakarta.inject import Singleton


                @Singleton
                class Marker:
                    pass
                """,
            StandardCharsets.UTF_8
        );
        Files.writeString(
            testsDir.resolve("test_keyword_alias.py"),
            """
                from reactor.core.publisher import Flux


                def test_keyword_safe_java_method_alias():
                    assert Flux.from_(Flux.just("ok")).blockFirst() == "ok"
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
        String processedTest = Files.readString(project.resolve("__pyronaut__/test-sources/example/test_keyword_alias.py"));
        if (!processedTest.contains("getattr(Flux, 'from')")) {
            throw new AssertionError(processedTest);
        }

        RunResult result = runDefaultPytest(project);
        assertEquals(0, result.exitCode(), result.output());
    }

    protected void assertPytestLoadsProjectTypeConverterRegistrars() throws Exception {
        Path project = tempDir.resolve("app");
        Path pyronautDir = project.resolve("__pyronaut__");
        Path classesDir = pyronautDir.resolve("classes");
        Path testsDir = project.resolve("tests");
        Files.createDirectories(classesDir);
        Files.createDirectories(testsDir);
        Files.writeString(project.resolve("pyproject.toml"), minimalPyproject(), StandardCharsets.UTF_8);
        Files.writeString(
            testsDir.resolve("test_project_type_converter.py"),
            """
                import java
                import pytest
                from pyronaut.test import MicronautTest, micronaut_test_fixture

                CustomValue = java.type("smoke.CustomValue")


                @pytest.fixture
                def my_context(request):
                    fixture = micronaut_test_fixture(
                        request,
                        MicronautTest(
                            properties={"custom.value": "project-converter-ok"},
                        ),
                    )
                    yield fixture
                    fixture.stop()


                def test_project_type_converter_registrar(my_context):
                    env = my_context["io.micronaut.context.env.Environment"]
                    converted = env.getProperty("custom.value", CustomValue)

                    assert converted.isPresent()
                    assert converted.get().value() == "project-converter-ok"
                """,
            StandardCharsets.UTF_8
        );

        Path converterJar = buildTypeConverterRegistrarJar(tempDir.resolve("converter"));
        Files.writeString(
            pyronautDir.resolve("resolved-test-dependencies"),
            testClasspathManifest(converterJar),
            StandardCharsets.UTF_8
        );

        RunResult result = runDefaultPytest(project);
        assertEquals(0, result.exitCode(), result.output());
    }

    protected abstract RunResult runPyronautTest(Path project) throws Exception;
    protected abstract RunResult runDefaultPytest(Path project) throws Exception;

    protected static RunResult runJvmTest(Path project) throws Exception {
        return runJvmTest(project, "smoke.GeneratedPassingTest");
    }

    protected static RunResult runJvmTest(Path project, String className) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        int exitCode;
        try {
            System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
            java.util.List<String> args = new java.util.ArrayList<>();
            args.add("--project-dir");
            args.add(project.toString());
            if (className != null) {
                args.add("--select-class");
                args.add(className);
            }
            exitCode = new picocli.CommandLine(new PyronautTestMain()).execute(args.toArray(String[]::new));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new RunResult(exitCode, stdout.toString(StandardCharsets.UTF_8) + stderr.toString(StandardCharsets.UTF_8));
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

    private static Path buildTypeConverterRegistrarJar(Path workDir) throws Exception {
        Path sourceDir = workDir.resolve("src");
        Path classesDir = workDir.resolve("classes");
        Files.createDirectories(sourceDir);
        Files.createDirectories(classesDir);
        compileJava(
            sourceDir.resolve("smoke/CustomValue.java"),
            """
                package smoke;

                public final class CustomValue {
                    private final String value;

                    public CustomValue(String value) {
                        this.value = value;
                    }

                    public String value() {
                        return value;
                    }
                }
                """,
            classesDir,
            currentRuntimeClasspath()
        );
        compileJava(
            sourceDir.resolve("smoke/CustomValueConverterRegistrar.java"),
            """
                package smoke;

                import io.micronaut.core.convert.MutableConversionService;
                import io.micronaut.core.convert.TypeConverterRegistrar;

                public final class CustomValueConverterRegistrar implements TypeConverterRegistrar {
                    @Override
                    public void register(MutableConversionService conversionService) {
                        conversionService.addConverter(String.class, CustomValue.class, CustomValue::new);
                    }
                }
                """,
            classesDir,
            classesDir.toAbsolutePath().normalize() + File.pathSeparator + currentRuntimeClasspath()
        );
        Path jarPath = workDir.resolve("converter.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
            try (Stream<Path> stream = Files.walk(classesDir)) {
                for (Path file : stream.filter(Files::isRegularFile).toList()) {
                    String entryName = classesDir.relativize(file).toString().replace('\\', '/');
                    jar.putNextEntry(new JarEntry(entryName));
                    jar.write(Files.readAllBytes(file));
                    jar.closeEntry();
                }
            }
            jar.putNextEntry(new JarEntry("META-INF/services/io.micronaut.core.convert.TypeConverterRegistrar"));
            jar.write("smoke.CustomValueConverterRegistrar\n".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
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

            [tool.pyronaut.core]
            version = "5.1.0-SNAPSHOT"
            """;
    }

    protected record RunResult(int exitCode, String output) {
    }
}

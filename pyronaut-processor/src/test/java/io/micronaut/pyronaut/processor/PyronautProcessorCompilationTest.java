package io.micronaut.pyronaut.processor;

import io.micronaut.pyronaut.config.model.PyprojectModelReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PyronautProcessorCompilationTest {
    private static final FileTime UNCHANGED_MARKER = FileTime.fromMillis(1_000);

    @TempDir
    Path tempDir;

    @Test
    void processesMicronautPythonControllerAndGeneratesClasses() throws Exception {
        Path project = tempDir.resolve("project");
        Path srcDir = project.resolve("src");
        Path testDir = project.resolve("tests");
        Path srcJavaDir = project.resolve("src-java");
        Path testJavaDir = project.resolve("test-java");
        Path cacheDir = project.resolve("__pyronaut__");
        Files.createDirectories(srcDir);
        Files.createDirectories(testDir);
        Files.createDirectories(srcJavaDir);
        Files.createDirectories(testJavaDir);
        Files.createDirectories(cacheDir);

        Files.writeString(project.resolve("pyproject.toml"), minimalPyproject());
        Files.copy(resolveFixture("/fixtures/python/controller.py"), srcDir.resolve("controller.py"));
        Files.writeString(
            srcJavaDir.resolve("BaseType.java"),
            "package example; public class BaseType {}",
            StandardCharsets.UTF_8
        );
        Files.writeString(
            testJavaDir.resolve("TestType.java"),
            "package example; public class TestType extends BaseType {}",
            StandardCharsets.UTF_8
        );

        List<String> classpathEntries = Arrays.stream(System.getProperty("java.class.path", "").split(System.getProperty("path.separator")))
            .map(String::trim)
            .filter(entry -> !entry.isEmpty())
            .toList();
        Files.write(cacheDir.resolve("resolved-build-dependencies"), classpathEntries, StandardCharsets.UTF_8);
        Files.write(cacheDir.resolve("resolved-runtime-dependencies"), classpathEntries, StandardCharsets.UTF_8);
        Files.write(cacheDir.resolve("resolved-test-dependencies"), classpathEntries, StandardCharsets.UTF_8);

        PyronautProcessorMain command = new PyronautProcessorMain();
        command.projectDir = project;

        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), command.call());

        Path classesDir = project.resolve("__pyronaut__/classes");
        assertTrue(Files.isDirectory(classesDir));
        assertTrue(hasClassContaining(classesDir, "MyController"));
        assertTrue(hasClassContaining(classesDir, "BaseType"));

        Path testClassesDir = project.resolve("__pyronaut__/test-classes");
        assertTrue(Files.isDirectory(testClassesDir));
        assertTrue(hasClassContaining(testClassesDir, "TestType"));
        assertTrue(Files.exists(cacheDir.resolve(ProcessorSourceCache.MAIN_HASH_FILE)));
        assertTrue(Files.exists(cacheDir.resolve(ProcessorSourceCache.TEST_HASH_FILE)));
    }

    @Test
    void emitsPythonBytecodeWhenConfigured() throws Exception {
        Path project = tempDir.resolve("project-bytecode");
        Files.createDirectories(project.resolve("src"));
        Files.createDirectories(project.resolve("tests"));
        Files.createDirectories(project.resolve("src-java"));
        Files.createDirectories(project.resolve("test-java"));
        Path cacheDir = project.resolve("__pyronaut__");
        Files.createDirectories(cacheDir);
        Files.writeString(project.resolve("pyproject.toml"), minimalPyproject() + "\n[tool.pyronaut.build.python-bytecode]\nenabled = true\n");
        Files.writeString(project.resolve("src/main.py"), "answer = 42\n");
        List<String> classpathEntries = Arrays.stream(System.getProperty("java.class.path", "").split(System.getProperty("path.separator")))
            .filter(entry -> !entry.isBlank())
            .toList();
        Files.write(cacheDir.resolve("resolved-build-dependencies"), classpathEntries, StandardCharsets.UTF_8);
        Files.write(cacheDir.resolve("resolved-runtime-dependencies"), classpathEntries, StandardCharsets.UTF_8);
        Files.write(cacheDir.resolve("resolved-test-dependencies"), classpathEntries, StandardCharsets.UTF_8);

        PyronautProcessorMain command = new PyronautProcessorMain();
        command.projectDir = project;
        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), command.call());
        Path filesList = project.resolve("__pyronaut__/classes/META-INF/GRAALPY-VFS/micronaut-application/fileslist.txt");
        assertTrue(Files.readString(filesList).contains("__pycache__"));
        assertTrue(Files.readString(filesList).contains(".pyc"));
    }

    @Test
    void incrementallyCompilesIndependentJavaAndPythonSources() throws Exception {
        Path project = tempDir.resolve("project-incremental");
        Path srcDir = Files.createDirectories(project.resolve("src"));
        Path javaDir = Files.createDirectories(project.resolve("src-java"));
        Path cacheDir = Files.createDirectories(project.resolve("__pyronaut__"));
        Path alphaPython = srcDir.resolve("alpha.py");
        Path alphaJava = javaDir.resolve("Alpha.java");
        Files.writeString(alphaPython, "class AlphaPython:\n    value: int = 1\n");
        Files.writeString(srcDir.resolve("beta.py"), "class BetaPython:\n    value: int = 2\n");
        Files.writeString(alphaJava, "public class Alpha { int value() { return 1; } }\n");
        Files.writeString(javaDir.resolve("Beta.java"), "public class Beta {}\n");
        Files.writeString(
            project.resolve("pyproject.toml"),
            minimalPyproject() + "\n[tool.pyronaut.processor]\nincremental = true\n"
        );
        writeClasspathCaches(cacheDir);

        PyronautProcessorMain command = new PyronautProcessorMain();
        command.projectDir = project;
        command.pass = "main";
        command.progress = "off";
        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), command.call());

        Path output = project.resolve("__pyronaut__/classes");
        Path betaClass = output.resolve("Beta.class");
        Path betaPython = output.resolve(
            "META-INF/GRAALPY-VFS/micronaut-application/src/beta.py"
        );
        Path filesList = output.resolve(
            "META-INF/GRAALPY-VFS/micronaut-application/fileslist.txt"
        );
        Files.setLastModifiedTime(betaClass, UNCHANGED_MARKER);
        Files.setLastModifiedTime(betaPython, UNCHANGED_MARKER);

        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), command.call());
        assertEquals(UNCHANGED_MARKER, Files.getLastModifiedTime(betaClass));
        assertEquals(UNCHANGED_MARKER, Files.getLastModifiedTime(betaPython));

        Files.delete(betaClass);
        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), command.call());
        assertTrue(Files.isRegularFile(betaClass));
        Files.setLastModifiedTime(betaClass, UNCHANGED_MARKER);
        Files.setLastModifiedTime(betaPython, UNCHANGED_MARKER);
        Files.setLastModifiedTime(filesList, UNCHANGED_MARKER);

        Files.writeString(alphaPython, "class AlphaPython:\n    value: int = 3\n");
        Files.writeString(alphaJava, "public class Alpha { int value() { return 2; } }\n");
        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), command.call());

        assertEquals(UNCHANGED_MARKER, Files.getLastModifiedTime(betaClass));
        assertEquals(UNCHANGED_MARKER, Files.getLastModifiedTime(betaPython));
        assertNotEquals(UNCHANGED_MARKER, Files.getLastModifiedTime(filesList));
        assertTrue(Files.isRegularFile(cacheDir.resolve("incremental/main/state.properties")));

        Files.delete(alphaPython);
        Files.delete(alphaJava);
        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), command.call());
        assertFalse(Files.exists(output.resolve("Alpha.class")));
        assertFalse(Files.exists(
            output.resolve("META-INF/GRAALPY-VFS/micronaut-application/src/alpha.py")
        ));
        assertFalse(Files.readString(filesList).contains("/src/alpha.py"));
        assertTrue(Files.isRegularFile(betaClass));
        assertTrue(Files.isRegularFile(betaPython));

        Files.setLastModifiedTime(betaClass, UNCHANGED_MARKER);
        Files.setLastModifiedTime(betaPython, UNCHANGED_MARKER);
        Files.writeString(srcDir.resolve("gamma.py"), "class GammaPython:\n    value: int = 4\n");
        Files.writeString(javaDir.resolve("Gamma.java"), "public class Gamma {}\n");
        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), command.call());
        assertTrue(Files.isRegularFile(output.resolve("Gamma.class")));
        assertTrue(Files.isRegularFile(
            output.resolve("META-INF/GRAALPY-VFS/micronaut-application/src/gamma.py")
        ));
        assertEquals(UNCHANGED_MARKER, Files.getLastModifiedTime(betaClass));
        assertEquals(UNCHANGED_MARKER, Files.getLastModifiedTime(betaPython));
    }

    @Test
    void compilesMainSourcesIntoTestClassesWhenNoTestSourcesPresent() throws Exception {
        Path project = tempDir.resolve("project-no-test-sources");
        Path srcDir = project.resolve("src");
        Path srcJavaDir = project.resolve("src-java");
        Path cacheDir = project.resolve("__pyronaut__");
        Files.createDirectories(srcDir);
        Files.createDirectories(srcJavaDir);
        Files.createDirectories(cacheDir);

        Files.writeString(project.resolve("pyproject.toml"), minimalPyproject());
        Files.copy(resolveFixture("/fixtures/python/controller.py"), srcDir.resolve("controller.py"));
        Files.writeString(
            srcJavaDir.resolve("BaseType.java"),
            "package example; public class BaseType {}",
            StandardCharsets.UTF_8
        );

        List<String> classpathEntries = Arrays.stream(System.getProperty("java.class.path", "").split(System.getProperty("path.separator")))
            .map(String::trim)
            .filter(entry -> !entry.isEmpty())
            .toList();
        Files.write(cacheDir.resolve("resolved-build-dependencies"), classpathEntries, StandardCharsets.UTF_8);
        Files.write(cacheDir.resolve("resolved-runtime-dependencies"), classpathEntries, StandardCharsets.UTF_8);
        Files.write(cacheDir.resolve("resolved-test-dependencies"), classpathEntries, StandardCharsets.UTF_8);

        PyronautProcessorMain command = new PyronautProcessorMain();
        command.projectDir = project;

        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), command.call());

        Path classesDir = project.resolve("__pyronaut__/classes");
        assertTrue(Files.isDirectory(classesDir));
        assertTrue(hasClassContaining(classesDir, "MyController"));

        Path testClassesDir = project.resolve("__pyronaut__/test-classes");
        assertTrue(Files.isDirectory(testClassesDir));
        assertTrue(hasClassContaining(testClassesDir, "MyController"));
        assertTrue(Files.exists(cacheDir.resolve(ProcessorSourceCache.MAIN_HASH_FILE)));
        assertTrue(Files.exists(cacheDir.resolve(ProcessorSourceCache.TEST_HASH_FILE)));
    }

    @Test
    void removesGeneratedControllerArtifactsWhenSourceIsDeleted() throws Exception {
        Path project = tempDir.resolve("project-delete-controller");
        Path srcDir = project.resolve("src");
        Path cacheDir = project.resolve("__pyronaut__");
        Files.createDirectories(srcDir);
        Files.createDirectories(cacheDir);

        Files.writeString(project.resolve("pyproject.toml"), minimalPyproject());
        Path controller = srcDir.resolve("controller.py");
        Files.copy(resolveFixture("/fixtures/python/controller.py"), controller);
        writeClasspathCaches(cacheDir);

        PyronautProcessorMain command = new PyronautProcessorMain();
        command.projectDir = project;

        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), command.call());

        Path classesDir = project.resolve("__pyronaut__/classes");
        assertTrue(hasClassContaining(classesDir, "MyController"));
        assertTrue(hasFileContaining(classesDir, "MyController"));

        Files.delete(controller);

        assertEquals(PyronautProcessorExitCode.SUCCESS.code(), command.call());
        assertFalse(hasClassContaining(classesDir, "MyController"));
        assertFalse(hasFileContaining(classesDir, "MyController"));
    }

    private static boolean hasClassContaining(Path root, String token) throws Exception {
        try (var files = Files.walk(root)) {
            return files
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .anyMatch(name -> name.endsWith(".class") && name.contains(token));
        }
    }

    private static boolean hasFileContaining(Path root, String token) throws Exception {
        try (var files = Files.walk(root)) {
            return files
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .anyMatch(name -> name.contains(token));
        }
    }

    @Test
    void filesListOmitsTheReplacedJavaImportsManifestAfterDeletingAndAddingModules() throws Exception {
        Path project = tempDir.resolve("project-java-imports");
        Path srcDir = Files.createDirectories(project.resolve("src"));
        Path cacheDir = Files.createDirectories(project.resolve("__pyronaut__"));
        Files.writeString(
            project.resolve("pyproject.toml"),
            minimalPyproject() + """

                [tool.pyronaut.processor]
                incremental = true
                daemon = true
                python-incremental-mode = "optimistic"
                """
        );
        writeClasspathCaches(cacheDir);
        for (String name : List.of("alpha", "beta", "gamma", "delta")) {
            Files.writeString(srcDir.resolve(name + ".py"), "class " + name.toUpperCase() + ":\n    value: int = 1\n");
        }
        Path removed = srcDir.resolve("removed.py");
        Files.writeString(removed, "from java.util import ArrayList\nclass Removed:\n    value: int = 1\n");
        Path vfs = project.resolve("__pyronaut__/classes/META-INF/GRAALPY-VFS/micronaut-application");

        // the executor of the compiler daemon, which keeps the Python processing session between runs
        try (CompilerDaemon.SessionCompilerExecutor executor = new CompilerDaemon.SessionCompilerExecutor()) {
            PyronautProcessorMain command = new PyronautProcessorMain(new PyprojectModelReader(), executor, true);
            command.projectDir = project;
            command.pass = "main";
            command.progress = "off";
            assertEquals(PyronautProcessorExitCode.SUCCESS.code(), command.call());
            List<String> initialManifests = javaImportsManifests(vfs);
            assertEquals(1, initialManifests.size());
            assertFilesListMatchesTheVfs(vfs);

            Files.delete(removed);
            Files.writeString(srcDir.resolve("added.py"), "from java.util import HashMap\nclass Added:\n    value: int = 1\n");
            assertEquals(PyronautProcessorExitCode.SUCCESS.code(), command.call());

            List<String> manifests = javaImportsManifests(vfs);
            assertEquals(1, manifests.size());
            assertNotEquals(initialManifests, manifests);
            assertFilesListMatchesTheVfs(vfs);

            // processing again without changes keeps the list consistent
            assertEquals(PyronautProcessorExitCode.SUCCESS.code(), command.call());
            assertFilesListMatchesTheVfs(vfs);
        }
    }

    private static List<String> javaImportsManifests(Path vfs) throws Exception {
        try (var files = Files.list(vfs.resolve("src"))) {
            return files.map(file -> file.getFileName().toString())
                .filter(name -> name.startsWith("__micronaut_java_imports_"))
                .sorted()
                .toList();
        }
    }

    private static void assertFilesListMatchesTheVfs(Path vfs) throws Exception {
        Path filesList = vfs.resolve("fileslist.txt");
        Path classes = vfs.getParent().getParent().getParent();
        List<String> listed = Files.readAllLines(filesList, StandardCharsets.UTF_8).stream().sorted().toList();
        List<String> present;
        try (var files = Files.walk(vfs)) {
            present = files.filter(Files::isRegularFile)
                .filter(file -> !file.equals(filesList))
                .map(file -> "/" + classes.relativize(file).toString().replace(java.io.File.separatorChar, '/'))
                .sorted()
                .toList();
        }
        assertEquals(present, listed);
    }

    private static void writeClasspathCaches(Path cacheDir) throws Exception {
        List<String> classpathEntries = Arrays.stream(System.getProperty("java.class.path", "").split(System.getProperty("path.separator")))
            .map(String::trim)
            .filter(entry -> !entry.isEmpty())
            .toList();
        Files.write(cacheDir.resolve("resolved-build-dependencies"), classpathEntries, StandardCharsets.UTF_8);
        Files.write(cacheDir.resolve("resolved-runtime-dependencies"), classpathEntries, StandardCharsets.UTF_8);
        Files.write(cacheDir.resolve("resolved-test-dependencies"), classpathEntries, StandardCharsets.UTF_8);
    }

    private static Path resolveFixture(String resourcePath) throws URISyntaxException {
        var url = PyronautProcessorCompilationTest.class.getResource(resourcePath);
        assertNotNull(url);
        return Path.of(url.toURI());
    }

    private static String minimalPyproject() {
        return """
            [project]
            name = "processor-compilation-test"
            version = "1.0.0"

            [tool.pyronaut]
            repositories = ["mavenCentral"]

            [tool.pyronaut.dependencies]
            runtime = []
            build = []
            test = []
            """;
    }
}

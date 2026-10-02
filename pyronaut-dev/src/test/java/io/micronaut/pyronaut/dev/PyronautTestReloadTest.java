package io.micronaut.pyronaut.dev;

import io.micronaut.dev.compile.SourceKind;
import io.micronaut.dev.manifest.DevManifest;
import io.micronaut.dev.manifest.DevMode;
import io.micronaut.dev.manifest.TestSettings;
import io.micronaut.pyronaut.config.model.PyprojectModel;
import io.micronaut.pyronaut.config.model.PyprojectModelReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PyronautTestReloadTest {

    @TempDir
    Path project;

    private PyronautTestReload.Layout layout(String extra) throws Exception {
        Files.writeString(project.resolve("pyproject.toml"), """
            [project]
            name = "demo"
            version = "1.0.0"

            [tool.pyronaut.sources]
            python = "src"
            python-test = "tests"
            resources = "config"
            test-resources = "tests-config"
            """ + extra);
        for (String directory : List.of("src", "tests", "config", "tests-config", "__pyronaut__/classes")) {
            Files.createDirectories(project.resolve(directory));
        }
        PyprojectModel model = new PyprojectModelReader().readFile(project.resolve("pyproject.toml"));
        return PyronautTestReload.Layout.of(project.toAbsolutePath().normalize(), model);
    }

    @Test
    void theManifestRunsThePythonTestsInTestModeAgainstTheProcessedClasses() throws Exception {
        PyronautTestReload.Layout layout = layout("");
        Path pyronaut = project.resolve("__pyronaut__").toAbsolutePath().normalize();
        Path devDir = Files.createDirectories(pyronaut.resolve("micronaut-dev/test"));
        Path runtimeJar = project.resolve("lib/micronaut-context.jar");
        Path testJar = project.resolve("lib/micronaut-pyronaut-pytest.jar");
        Path processor = project.resolve("lib/micronaut-inject-python.jar");

        Path file = PyronautTestReload.writeManifest(layout, devDir, List.of(runtimeJar), List.of(runtimeJar, testJar), List.of(processor),
            List.of("-Amicronaut.openapi.enabled=true"), List.of("-Amicronaut.openapi.enabled=false"), List.of("test_app.py"));
        DevManifest manifest = DevManifest.load(file);

        assertEquals(DevMode.TEST, manifest.mode());
        Path classes = pyronaut.resolve("classes");
        Path testClasses = pyronaut.resolve("test-classes");
        // the tests load ahead of the classes under test, from one generation
        assertEquals(List.of(testClasses, classes), manifest.reloadableRoots());
        assertEquals(project.resolve("src").toAbsolutePath().normalize(), manifest.sourceRoots(SourceKind.PYTHON).getFirst().path());
        assertEquals(project.resolve("tests").toAbsolutePath().normalize(), manifest.testSourceRoots().getFirst().path());
        assertEquals(SourceKind.PYTHON, manifest.testSourceRoots().getFirst().kind());
        assertEquals(testClasses, manifest.testClassOutput(SourceKind.PYTHON));
        assertEquals(pyronaut.resolve("micronaut-dev/test/generations"), manifest.generations());
        assertEquals(List.of("-Amicronaut.openapi.enabled=true"), manifest.compileOptions(SourceKind.PYTHON));
        assertTrue(manifest.resourceRoots().stream().anyMatch(root -> root.path().equals(project.resolve("tests-config").toAbsolutePath().normalize())));
        assertTrue(manifest.resourceRoots().stream().anyMatch(root -> root.path().equals(project.resolve("config").toAbsolutePath().normalize())));

        // the tests compile with the test scope and the test options, against the processed classes
        DevManifest tests = manifest.testView();
        assertEquals(List.of(runtimeJar.toAbsolutePath().normalize(), testJar.toAbsolutePath().normalize(), classes), tests.compileClasspath());
        assertEquals(List.of(processor.toAbsolutePath().normalize()), tests.processorPath());
        assertEquals(List.of("-Amicronaut.openapi.enabled=false"), tests.compileOptions(SourceKind.PYTHON));
        assertEquals(testClasses, tests.classOutput(SourceKind.PYTHON));

        TestSettings settings = manifest.testSettings();
        assertEquals(PyronautTestRunner.ID, settings.runner());
        Path reports = pyronaut.resolve("reports/tests");
        assertEquals(reports.resolve("junit"), settings.reports());
        assertEquals(reports, settings.htmlReport());
        assertEquals("/tests/", settings.htmlReportPath());
        assertEquals(List.of("test_app.py"), settings.patterns());
        // the pytest engine writes its own JUnit report and last node id as for the test command; the events and the
        // HTML report are test mode's, so the engine writes its own where they are not read
        assertEquals(Map.of(
            "pytest.report.dir", reports.toString(),
            "pytest.report.junit", reports.resolve("junit.xml").toString(),
            "pytest.report.nodeid", reports.resolve(".pyronaut-last-nodeid.txt").toString(),
            "pytest.report.html", devDir.resolve("pytest/index.html").toString(),
            "pytest.report.events", devDir.resolve("pytest/events.ndjson").toString()), settings.parameters());
    }

    @Test
    void theLiveReportIsServedAtTheConfiguredPath() throws Exception {
        PyronautTestReload.Layout layout = layout("""

            [tool.pyronaut.test]
            report-path = "/pyronaut/tests"
            """);
        Path devDir = Files.createDirectories(project.resolve("__pyronaut__/micronaut-dev/test"));
        Path file = PyronautTestReload.writeManifest(layout, devDir, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        assertEquals("/pyronaut/tests/", DevManifest.load(file).testSettings().htmlReportPath());
    }

    @Test
    void thePythonBytecodeSettingIsAddedUnlessTheOptionsHoldOne() {
        assertEquals(List.of("-Aa=b", "-Amicronaut.python.bytecode=false"), DevReloadFiles.withPythonBytecode(List.of("-Aa=b"), false));
        assertEquals(List.of("-Amicronaut.python.bytecode=false"), DevReloadFiles.withPythonBytecode(List.of("-Amicronaut.python.bytecode=false"), true));
    }

    @Test
    void preparingTakesTheProcessedTestsOverFromThePyronautProcessor() throws Exception {
        PyronautTestReload.Layout layout = layout("");
        Path pyronaut = project.resolve("__pyronaut__");
        // what pyronaut process --pass all leaves: the test pass compiles the application with the tests
        Path stale = pyronaut.resolve("test-classes/META-INF/GRAALPY-VFS/micronaut-application/src/demo/hello.py");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "def hello(): return 'old'\n");
        Files.createDirectories(pyronaut.resolve("test-sources"));
        Files.createDirectories(pyronaut.resolve("incremental/test"));
        Files.createDirectories(pyronaut.resolve("incremental/main"));
        Files.writeString(pyronaut.resolve("processor-test.sha256"), "x");
        Files.writeString(pyronaut.resolve("processor-main.sha256"), "x");
        Files.writeString(pyronaut.resolve("resolved-processor-options"), "-Amain=true\n");
        Files.writeString(pyronaut.resolve("resolved-test-processor-options"), "-Atest=true\n");

        Path file = PyronautTestReload.prepare(layout, List.of());

        assertFalse(Files.exists(pyronaut.resolve("test-classes")));
        assertFalse(Files.exists(pyronaut.resolve("test-sources")));
        assertFalse(Files.exists(pyronaut.resolve("processor-test.sha256")));
        assertFalse(Files.exists(pyronaut.resolve("processor-main.sha256")));
        assertFalse(Files.exists(pyronaut.resolve("incremental/test")));
        assertFalse(Files.exists(pyronaut.resolve("incremental/main")));
        assertTrue(Files.isDirectory(pyronaut.resolve("reports/tests")));
        DevManifest manifest = DevManifest.load(file);
        // the application compiles with the bytecode setting pyronaut process compiled it with, so that both write the same files
        assertEquals(List.of("-Amain=true", "-Amicronaut.python.bytecode=true"), manifest.compileOptions(SourceKind.PYTHON));
        assertEquals(List.of("-Atest=true"), manifest.testView().compileOptions(SourceKind.PYTHON));
    }

    @Test
    void pytestSelectorsMatchByFileAndOtherSelectorsAsPatterns() {
        assertEquals(List.of("*/tests/test_app.py.test_hello", "*/test_app.py", "*/test_other.py.test", "com.example.*Test",
                "*/tests/test_*.py", "/work/tests/test_app.py"),
            PyronautTestReload.filterPatterns(List.of(
                "tests/test_app.py::test_hello",
                "./test_app.py",
                "test_other::test",
                "com.example.*Test",
                "tests/test_*.py",
                "/work/tests/test_app.py",
                " ")));
    }

    @Test
    void theArgumentsOfTheTestCommandThatTestModeSupports() {
        PyronautTestReload.Arguments arguments = PyronautTestReload.Arguments.parse(new String[] {
            "--project-dir", "demo", "--tests", "test_app.py", "--tests=com.example.*", "--verbose", "--debug-vm"});
        assertEquals(Path.of("demo"), arguments.projectDir());
        assertEquals(List.of("test_app.py", "com.example.*"), arguments.tests());
        assertEquals("", arguments.verbose());
        assertEquals("io.micronaut.http,demo", PyronautTestReload.Arguments.parse(new String[] {"--verbose=io.micronaut.http,demo"}).verbose());
        assertEquals(null, PyronautTestReload.Arguments.parse(new String[0]).verbose());
        IllegalArgumentException unsupported = assertThrows(IllegalArgumentException.class,
            () -> PyronautTestReload.Arguments.parse(new String[] {"--select-class", "com.example.AppTest"}));
        assertTrue(unsupported.getMessage().contains("tool.pyronaut.test.continuous"));
    }

    @Test
    void theEnginesTheProjectSelects() {
        // the engines of the test command, not any other on the classpath
        assertEquals(List.of("junit-jupiter", "pyronaut-pytest"), PyronautTestReload.engines(PyprojectModel.TestEngine.BOTH));
        assertEquals(List.of("junit-jupiter"), PyronautTestReload.engines(PyprojectModel.TestEngine.JUNIT));
        assertEquals(List.of("pyronaut-pytest"), PyronautTestReload.engines(PyprojectModel.TestEngine.PYTEST));
    }
}

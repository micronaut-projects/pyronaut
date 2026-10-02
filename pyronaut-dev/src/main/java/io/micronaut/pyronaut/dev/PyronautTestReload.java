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
package io.micronaut.pyronaut.dev;

import io.micronaut.dev.MicronautDevMain;
import io.micronaut.dev.compile.SourceCompiler;
import io.micronaut.dev.compile.SourceKind;
import io.micronaut.dev.manifest.DevManifest;
import io.micronaut.pyronaut.config.model.PyprojectModel;
import io.micronaut.pyronaut.config.model.PyprojectModelException;
import io.micronaut.pyronaut.config.model.PyprojectModelReader;
import io.micronaut.pyronaut.config.model.PyronautRuntimeProperties;
import io.micronaut.pyronaut.config.terminal.Terminal;
import io.micronaut.pyronaut.logback.PyronautLauncherLogging;
import io.micronaut.pyronaut.test.PyronautTestMain;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Continuous testing on the JVM toolchain, {@code pyronaut test -t}: the tests run in the test mode of the
 * development runtime of {@code micronaut-dev}, in one JVM. It watches the Python and Java sources, the tests and
 * the configuration, compiles a change in process with the Pyronaut compiler, and runs the tests the change can
 * affect on a new class loader generation, so the dependencies, the compilers, the JIT and the GraalPy runtime stay
 * loaded from one run to the next.
 *
 * <p>The project's dependencies are the parent tier, on the launch classpath as the test command has them; the
 * processed classes and the tests' are the reloadable tier, and the configuration directories are read live. The
 * tests compile with the Python compiler of the development runtime into {@code __pyronaut__/test-classes}, against
 * the processed classes, so that a change of the application is not hidden by a copy of it there. The runs report
 * as JUnit XML, as a live HTML page, served by the LiveReload server, and as the {@code events.ndjson} of the test
 * command, which the editor integrations read.</p>
 *
 * <p>On a terminal, keys ask for runs: space runs the last tests again, {@code a} every test, {@code f} the failures,
 * {@code w} turns watching on or off and {@code q} exits, with the status of the last run.</p>
 */
public final class PyronautTestReload extends MicronautDevMain {

    /**
     * Where the {@link PyronautTestEventsListener} writes the events of the runs.
     */
    static final String EVENTS_PROPERTY = "pyronaut.dev.test.events";
    /**
     * The project directory, for the test identifiers of the events.
     */
    static final String PROJECT_DIR_PROPERTY = "pyronaut.dev.test.project-dir";
    /**
     * The Python tests directory, relative to the project, for the test identifiers of the events.
     */
    static final String TESTS_ROOT_PROPERTY = "pyronaut.dev.test.tests-root";
    /**
     * The Python source the GraalPy context of a run evaluates after the generated launcher.
     */
    static final String APPLICATION_MAIN_PROPERTY = "pyronaut.dev.test.application-main";
    /**
     * The test engines that run, by identifier, separated by commas: {@code tool.pyronaut.test.engine}.
     */
    static final String ENGINES_PROPERTY = "pyronaut.dev.test.engines";
    /**
     * Set by the CLI on the native image's test command to run it in test mode, in process.
     */
    static final String TEST_RELOAD_PROPERTY = "pyronaut.test.reload";
    /**
     * The application's Python source root, whose modules the test runner patches rather than imports again.
     */
    static final String PYTHON_SOURCES_PROPERTY = "pyronaut.dev.test.python-sources";

    private static final String TEST_DIR = "test";
    private static final String CLASSES_DIR = "classes";
    private static final String TEST_CLASSES_DIR = "test-classes";
    private static final String TEST_SOURCES_DIR = "test-sources";
    private static final String REPORTS_DIR = "reports/tests";
    private static final String RUNTIME_DEPENDENCIES_MANIFEST = "resolved-runtime-dependencies";
    private static final String TEST_DEPENDENCIES_MANIFEST = "resolved-test-dependencies";
    private static final String TEST_PROCESSOR_OPTIONS = "resolved-test-processor-options";
    private static final String PROCESSOR_HASHES = "processor-%s.sha256";
    private static final String PROCESSOR_INCREMENTAL = "incremental/%s";
    private static final int TESTS_FAILED = 7;
    private static final int PRECONDITION_FAILED = 8;

    /**
     * Runs the tests continuously.
     *
     * @param args {@code --project-dir <dir>} and any number of {@code --tests <pattern>}
     * @throws Exception if the development runtime fails to start
     */
    public static void main(String[] args) throws Exception {
        Terminal.notifyLaunched();
        PyronautRuntimeProperties.disableGraalVmImageSingletons();
        PyronautLauncherLogging.initialize();
        System.exit(runTests(args));
    }

    static int runTests(String[] args) throws Exception {
        Arguments arguments;
        PyprojectModel model;
        Path root;
        try {
            arguments = Arguments.parse(args);
            root = arguments.projectDir().toAbsolutePath().normalize();
            model = new PyprojectModelReader().readFile(root.resolve(PyprojectModelReader.FILE_NAME));
        } catch (IllegalArgumentException | PyprojectModelException e) {
            System.err.println(e.getMessage());
            return PRECONDITION_FAILED;
        }
        PyronautTestMain.prepareTestJvm(System.getenv());
        // as the test command does: application logging, and with --verbose trace logging, of every logger or of those named
        if (PyronautLauncherLogging.shouldInitializeApplicationDefaults(PyronautTestReload.class.getClassLoader())) {
            PyronautLauncherLogging.initializeApplicationDefaults((String) null);
        }
        if (arguments.verbose() != null) {
            PyronautLauncherLogging.initializeApplicationDefaults(arguments.verbose());
        }
        Layout layout = Layout.of(root, model);
        Path manifest = prepare(layout, arguments.tests());
        System.setProperty(EVENTS_PROPERTY, layout.reports().resolve("events.ndjson").toString());
        System.setProperty(PROJECT_DIR_PROPERTY, root.toString());
        System.setProperty(PYTHON_SOURCES_PROPERTY, layout.python().toString());
        System.setProperty(TESTS_ROOT_PROPERTY, relative(root, layout.pythonTests()));
        System.setProperty(APPLICATION_MAIN_PROPERTY, PyronautTestMain.applicationMain(layout.pythonTests()));
        System.setProperty(ENGINES_PROPERTY, String.join(",", engines(model.pyronaut().test().engine())));
        int status = new PyronautTestReload().runForStatus(new String[] {MANIFEST_OPTION, manifest.toString()});
        if (status == RELAUNCH) {
            // the generation budget is spent: the marker tells the CLI that the runtime, not a test, asks for a new process
            Path marker = manifest.resolveSibling(PyronautDevReload.RELAUNCH_MARKER);
            try {
                Files.writeString(marker, "generation budget spent\n", StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.err.println("Cannot mark the relaunch in " + marker + ": " + e.getMessage());
                return TESTS_FAILED;
            }
            return RELAUNCH;
        }
        return status == 0 ? 0 : TESTS_FAILED;
    }

    @Override
    protected Map<SourceKind, SourceCompiler> createCompilers(DevManifest manifest) {
        return ImageProcessorState.releasingAfterCompilation(super.createCompilers(manifest));
    }

    /**
     * Whether the CLI asked the native image's test command for test mode, {@code -Dpyronaut.test.reload=true}.
     *
     * @return True when the tests run continuously in this process
     */
    static boolean isRequested() {
        return Boolean.getBoolean(TEST_RELOAD_PROPERTY);
    }

    /**
     * Runs {@link #runTests(String[])} for the native image's test command, which reports a failure as a status.
     *
     * @param args The arguments
     * @return The status
     */
    static int runTestsReportingFailures(String[] args) {
        try {
            return runTests(args);
        } catch (Exception e) {
            System.err.println("pyronaut test -t failed: " + e.getMessage());
            e.printStackTrace(System.err);
            return TESTS_FAILED;
        }
    }

    /**
     * Takes the processed outputs over from {@code pyronaut process} and writes the manifest of test mode.
     *
     * @param layout The project's layout
     * @param tests The {@code --tests} selectors
     * @return The manifest
     * @throws IOException if the manifest cannot be written
     */
    static Path prepare(Layout layout, List<String> tests) throws IOException {
        Path pyronautDir = layout.root().resolve(DevReloadFiles.PYRONAUT_DIR);
        // from here on the development runtime writes the processed classes and the tests': the next pyronaut process
        // must not take them for its own output, by its source fingerprint or its incremental state
        for (String pass : List.of("main", TEST_DIR)) {
            Files.deleteIfExists(pyronautDir.resolve(PROCESSOR_HASHES.formatted(pass)));
            DevReloadFiles.deleteRecursively(pyronautDir.resolve(PROCESSOR_INCREMENTAL.formatted(pass)));
        }
        // the test pass compiles the application with the tests, into the tests' output, where the copy would hide a
        // change of the application from the tests: the development runtime compiles the tests alone, against the
        // processed classes, when it starts
        DevReloadFiles.deleteRecursively(layout.testClasses());
        DevReloadFiles.deleteRecursively(pyronautDir.resolve(TEST_SOURCES_DIR));
        Files.createDirectories(layout.reports());
        Path devDir = Files.createDirectories(pyronautDir.resolve(DevReloadFiles.DEV_DIR).resolve(TEST_DIR));
        Files.deleteIfExists(devDir.resolve(PyronautDevReload.RELAUNCH_MARKER));
        List<Path> buildDependencies = DevReloadFiles.readLines(pyronautDir.resolve(DevReloadFiles.BUILD_DEPENDENCIES_MANIFEST));
        return writeManifest(layout, devDir,
            DevReloadFiles.readLines(pyronautDir.resolve(RUNTIME_DEPENDENCIES_MANIFEST)),
            DevReloadFiles.readLines(pyronautDir.resolve(TEST_DEPENDENCIES_MANIFEST)),
            // in a native image the processors the image holds run from it, as pyronaut process runs them there
            DevReloadFiles.withoutNativeProvidedArtifacts(buildDependencies),
            DevReloadFiles.withOpenApiAdoc(
                DevReloadFiles.withPythonBytecode(DevReloadFiles.readStrings(pyronautDir.resolve(DevReloadFiles.PROCESSOR_OPTIONS)), layout.pythonBytecode()),
                buildDependencies),
            options(pyronautDir),
            filterPatterns(tests));
    }

    private static List<String> options(Path pyronautDir) {
        Path testOptions = pyronautDir.resolve(TEST_PROCESSOR_OPTIONS);
        // a project processed before the test options were recorded compiles its tests with the application's
        return DevReloadFiles.readStrings(Files.isRegularFile(testOptions) ? testOptions : pyronautDir.resolve(DevReloadFiles.PROCESSOR_OPTIONS));
    }

    /**
     * Writes the manifest of test mode, with its classpaths and compiler options in argument files beside it.
     */
    static Path writeManifest(Layout layout,
                              Path devDir,
                              List<Path> compileClasspath,
                              List<Path> testCompileClasspath,
                              List<Path> processorPath,
                              List<String> options,
                              List<String> testOptions,
                              List<String> filter) throws IOException {
        DevReloadFiles.writeLines(devDir.resolve("compile.argfile"), strings(compileClasspath));
        DevReloadFiles.writeLines(devDir.resolve("test-compile.argfile"), strings(testCompileClasspath));
        DevReloadFiles.writeLines(devDir.resolve("processor.argfile"), strings(processorPath));
        DevReloadFiles.writeLines(devDir.resolve("options.argfile"), options);
        DevReloadFiles.writeLines(devDir.resolve("test-options.argfile"), testOptions);
        Properties properties = new Properties();
        String prefix = "micronaut.dev.";
        properties.setProperty(prefix + "mode", "test");
        properties.setProperty(prefix + "project-dir", layout.root().toString());
        properties.setProperty(prefix + "strategy", "restart");
        properties.setProperty(prefix + "reloadable", layout.classes().toString());
        properties.setProperty(prefix + "compile-classpath", "@compile.argfile");
        properties.setProperty(prefix + "processor-path", "@processor.argfile");
        properties.setProperty(prefix + "generations", devDir.resolve("generations").toString());
        sources(properties, prefix, layout.python(), layout.java(), layout.classes(), "@options.argfile");
        // the tests compile into an output of their own, as the test pass does, against the processed classes
        sources(properties, prefix + "test.", layout.pythonTests(), layout.javaTests(), layout.testClasses(), "@test-options.argfile");
        properties.setProperty(prefix + "test.compile-classpath", "@test-compile.argfile");
        roots(properties, prefix + "resources.config", layout.config());
        roots(properties, prefix + "resources.other", layout.additional());
        roots(properties, prefix + "test.resources.config", layout.testResources());
        properties.setProperty(prefix + "test.runner", PyronautTestRunner.ID);
        properties.setProperty(prefix + "test.reports", layout.reports().resolve("junit").toString());
        properties.setProperty(prefix + "test.html-report", layout.reports().toString());
        properties.setProperty(prefix + "test.html-report-path", layout.reportPath());
        if (!filter.isEmpty()) {
            properties.setProperty(prefix + "test.filter", String.join(",", filter));
        }
        // the pytest engine's own reports, as the test command asks for them; the HTML report and the events are the
        // runtime's, which cover every engine and keep the results of the runs that came before, so the engine writes
        // its own beside the manifest, where they are not read
        String parameters = prefix + "test.parameters.";
        Path pytest = devDir.resolve("pytest");
        properties.setProperty(parameters + "pytest.report.dir", layout.reports().toString());
        properties.setProperty(parameters + "pytest.report.junit", layout.reports().resolve("junit.xml").toString());
        properties.setProperty(parameters + "pytest.report.nodeid", layout.reports().resolve(".pyronaut-last-nodeid.txt").toString());
        properties.setProperty(parameters + "pytest.report.html", pytest.resolve("index.html").toString());
        properties.setProperty(parameters + "pytest.report.events", pytest.resolve("events.ndjson").toString());
        Path manifest = devDir.resolve("test.properties");
        try (OutputStream out = Files.newOutputStream(manifest)) {
            properties.store(out, "Written by pyronaut test -t for the test mode of the micronaut-dev runtime");
        }
        return manifest;
    }

    private static void sources(Properties properties, String prefix, Path python, Path java, Path output, String options) {
        if (Files.isDirectory(python)) {
            properties.setProperty(prefix + "sources.python", python.toString());
            properties.setProperty(prefix + "compile.python.output", output.toString());
            properties.setProperty(prefix + "compile.python.options", options);
        }
        if (Files.isDirectory(java)) {
            // compiled with the Python sources, into the same output
            properties.setProperty(prefix + "sources.java", java.toString());
            properties.setProperty(prefix + "compile.java.output", output.toString());
            properties.setProperty(prefix + "compile.java.options", options);
        }
    }

    private static void roots(Properties properties, String key, List<Path> roots) {
        List<String> existing = roots.stream().filter(Files::isDirectory).map(Path::toString).toList();
        if (!existing.isEmpty()) {
            properties.setProperty(key, String.join(",", existing));
        }
    }

    /**
     * Turns the {@code --tests} selectors of the test command into the patterns of test mode, which match a test by
     * its class and method joined by a dot. The pytest engine names a test's class by its file's absolute path, so a
     * pytest selector, {@code tests/test_app.py::test_hello} or {@code test_app.py}, matches the end of that path, and
     * the test's name after a dot; any other selector, such as {@code com.example.*Test}, is a pattern as it is.
     *
     * @param selectors The selectors
     * @return The patterns
     */
    static List<String> filterPatterns(List<String> selectors) {
        Set<String> patterns = new LinkedHashSet<>();
        for (String selector : selectors) {
            String value = selector.strip();
            if (value.isEmpty()) {
                continue;
            }
            int node = value.indexOf("::");
            String file = node >= 0 ? value.substring(0, node) : value;
            if (node >= 0 || file.endsWith(".py")) {
                String path = file.replace('\\', '/');
                while (path.startsWith("./")) {
                    path = path.substring(2);
                }
                if (!path.endsWith(".py")) {
                    path = path + ".py";
                }
                // the engine names the file by its absolute path, with the platform's separator
                String pattern = (Path.of(path).isAbsolute() ? path : "*/" + path).replace('/', java.io.File.separatorChar);
                patterns.add(node >= 0 ? pattern + "." + value.substring(node + 2) : pattern);
            } else {
                patterns.add(value);
            }
        }
        return List.copyOf(patterns);
    }

    static List<String> engines(PyprojectModel.TestEngine engine) {
        return switch (engine) {
            case JUNIT -> List.of("junit-jupiter");
            case PYTEST -> List.of("pyronaut-pytest");
            case BOTH -> List.of("junit-jupiter", "pyronaut-pytest");
        };
    }

    private static String relative(Path root, Path path) {
        return path.startsWith(root) ? root.relativize(path).toString().replace('\\', '/') : path.toString();
    }

    private static List<String> strings(List<Path> paths) {
        return paths.stream().map(Path::toString).toList();
    }

    /**
     * Where the project's sources, outputs and reports are.
     *
     * @param root The project directory
     * @param classes The processed classes
     * @param testClasses The tests' classes
     * @param python The Python sources
     * @param java The Java sources
     * @param pythonTests The Python tests
     * @param javaTests The Java tests
     * @param config The configuration directories
     * @param additional The additional resource directories
     * @param testResources The test resource directories
     * @param reports The test reports
     * @param reportPath The path the LiveReload server serves the live HTML report at
     * @param pythonBytecode Whether the build compiles Python modules to bytecode
     */
    record Layout(Path root,
                  Path classes,
                  Path testClasses,
                  Path python,
                  Path java,
                  Path pythonTests,
                  Path javaTests,
                  List<Path> config,
                  List<Path> additional,
                  List<Path> testResources,
                  Path reports,
                  String reportPath,
                  boolean pythonBytecode) {

        static Layout of(Path root, PyprojectModel model) {
            PyprojectModel.Sources sources = model.pyronaut().sources();
            Path pyronautDir = root.resolve(DevReloadFiles.PYRONAUT_DIR);
            List<Path> additional = new ArrayList<>();
            for (String resource : sources.additionalResources()) {
                additional.add(DevReloadFiles.resolve(root, resource));
            }
            List<Path> testResources = new ArrayList<>();
            testResources.add(DevReloadFiles.resolve(root, sources.testResources()));
            for (String resource : sources.additionalTestResources()) {
                testResources.add(DevReloadFiles.resolve(root, resource));
            }
            return new Layout(root,
                pyronautDir.resolve(CLASSES_DIR),
                pyronautDir.resolve(TEST_CLASSES_DIR),
                DevReloadFiles.resolve(root, sources.python()),
                DevReloadFiles.resolve(root, sources.java()),
                DevReloadFiles.resolve(root, sources.pythonTest()),
                DevReloadFiles.resolve(root, sources.javaTest()),
                List.of(DevReloadFiles.resolve(root, sources.resources())),
                List.copyOf(additional),
                List.copyOf(testResources),
                pyronautDir.resolve(REPORTS_DIR),
                model.pyronaut().test().reportPathOrDefault(),
                Boolean.TRUE.equals(model.pyronaut().build().pythonBytecodeEnabled()));
        }
    }

    /**
     * The arguments the CLI passes: those of the test command that test mode supports.
     *
     * @param projectDir The project directory
     * @param tests The {@code --tests} selectors
     * @param verbose The loggers {@code --verbose} names, empty for every logger, null without {@code --verbose}
     */
    record Arguments(Path projectDir, List<String> tests, String verbose) {

        static Arguments parse(String[] args) {
            Path projectDir = Path.of(".");
            List<String> tests = new ArrayList<>();
            String verbose = null;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg.equals("--project-dir") || arg.equals("--tests")) {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("Missing the value of " + arg);
                    }
                    String value = args[++i];
                    if (arg.equals("--tests")) {
                        tests.add(value);
                    } else {
                        projectDir = Path.of(value);
                    }
                } else if (arg.startsWith("--project-dir=")) {
                    projectDir = Path.of(arg.substring("--project-dir=".length()));
                } else if (arg.startsWith("--tests=")) {
                    tests.add(arg.substring("--tests=".length()));
                } else if (arg.equals("--verbose")) {
                    verbose = "";
                } else if (arg.startsWith("--verbose=")) {
                    verbose = arg.substring("--verbose=".length());
                } else if (!arg.equals("--debug-vm")) {
                    throw new IllegalArgumentException("pyronaut test -t does not support " + arg
                        + " on the JVM toolchain. Set tool.pyronaut.test.continuous = \"process\" to run it in a new process for every run.");
                }
            }
            return new Arguments(projectDir, List.copyOf(tests), verbose);
        }
    }
}

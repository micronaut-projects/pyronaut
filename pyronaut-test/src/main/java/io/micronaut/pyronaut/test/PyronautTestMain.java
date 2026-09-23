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
package io.micronaut.pyronaut.test;

import io.micronaut.context.python.GraalPyContextFactory;
import io.micronaut.pyronaut.config.terminal.Terminal;
import io.micronaut.test.pytest.execution.ConsoleCapture;
import io.micronaut.core.beans.BeanIntrospectionProviders;
import io.micronaut.core.beans.BeanIntrospectionsProvider;
import io.micronaut.pyronaut.config.classloader.ContextClassLoaderBeanIntrospectionsProvider;
import io.micronaut.pyronaut.config.model.PyprojectModel;
import io.micronaut.pyronaut.config.model.PyprojectModelReader;
import io.micronaut.pyronaut.config.model.ExternalProjectLayout;
import io.micronaut.pyronaut.config.model.PyronautRuntimeProperties;
import io.micronaut.pyronaut.config.testresources.TestResourcesLogMirror;
import io.micronaut.pyronaut.logback.PyronautLauncherLogging;
import io.micronaut.test.pytest.PytestTestEngine;
import io.micronaut.test.pytest.execution.JUnitReportWriter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.EngineFilter;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.engine.reporting.ReportEntry;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import picocli.CommandLine;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Entry point for {@code pyronaut-test}.
 */
@SuppressWarnings("checkstyle:InnerTypeLast")
@CommandLine.Command(name = "pyronaut-test", mixinStandardHelpOptions = true, description = "Run tests for a processed Pyronaut application")
public final class PyronautTestMain implements Callable<Integer> {
    static {
        if (System.getProperty("org.slf4j.simpleLogger.defaultLogLevel") == null) {
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        }
    }

    private static final String DEFAULT_PYRONAUT_DIR = "__pyronaut__";
    private static final String DEFAULT_CLASSES_DIR = "__pyronaut__/classes";
    private static final String DEFAULT_TEST_CLASSES_DIR = "__pyronaut__/test-classes";
    private static final String DEFAULT_TEST_SOURCES_DIR = "__pyronaut__/test-sources";
    private static final String PYTHON_ENABLED_MARKER = "META-INF/pyronaut/python-enabled";
    private static final String MICRONAUT_PYTHON_ENABLED = "micronaut.python.enabled";
    private static final String DEFAULT_CONFIG_DIR = "config";
    private static final String DEFAULT_TESTS_DIR = "tests";
    private static final String TEST_APPLICATION_MAIN = "tests.py";
    private static final String DEFAULT_REPORTS_DIR = "__pyronaut__/reports/tests";
    private static final String DEFAULT_JUNIT_XML_REPORT = "junit.xml";
    private static final String DEFAULT_HTML_REPORT = "index.html";
    private static final String DEFAULT_NODEID_REPORT = ".pyronaut-last-nodeid.txt";
    private static final String DEFAULT_EVENTS_REPORT = "events.ndjson";
    private static final String DEFAULT_CONSOLE_LOG = "console.log";
    private static final String MICRONAUT_INTROSPECTIONS_USE_CONTEXT_CLASSLOADER = "micronaut.introspections.use.context.classloader";
    private static final String MICRONAUT_SERVER_PORT = "micronaut.server.port";
    private static final String CONFIGURATION_VALIDATOR_FAIL_ON_NOT_PRESENT = "micronaut.jsonschema.configuration.validator.fail-on-not-present";
    private static final String CONFIGURATION_VALIDATOR_SUPPRESSIONS = "micronaut.jsonschema.configuration.validator.suppressions";
    private static final String LOGGER_CONFIG_PROPERTY = "logger.config";
    private static final String PYRONAUT_USE_SYSTEM_APPLICATION_CLASSLOADER = "pyronaut.use.system.application.classloader";
    private static final String DEFAULT_TEST_SERVER_PORT = "0";
    private static final List<String> LAUNCHER_PROVIDED_ARTIFACT_IDS = List.of(
        "micronaut-aop",
        "micronaut-buffer-netty",
        "micronaut-context-propagation",
        "micronaut-context-python",
        "micronaut-core",
        "micronaut-core-reactive",
        "micronaut-discovery-core",
        "micronaut-http",
        "micronaut-http-netty",
        "micronaut-http-server",
        "micronaut-http-server-netty",
        "micronaut-inject",
        "micronaut-jackson-core",
        "micronaut-json-core",
        "micronaut-pyronaut-logback",
        "micronaut-pyronaut-pytest",
        "micronaut-retry",
        "micronaut-router",
        "micronaut-runtime"
    );
    private static final List<String> LAUNCHER_PROVIDED_ARTIFACT_PREFIXES = List.of();
    private static final String PYTEST_SOURCE_DIR = "pytest.src.dir";

    private static final String PYTEST_TESTS = "pytest.tests";
    private static final String PYTEST_REPORT_DIR = "pytest.report.dir";
    private static final String PYTEST_JUNIT_XML_REPORT = "pytest.report.junit";
    private static final String PYTEST_HTML_REPORT = "pytest.report.html";
    private static final String PYTEST_LAST_NODEID_REPORT = "pytest.report.nodeid";
    private static final String PYTEST_EVENTS_REPORT = "pytest.report.events";
    private static final String PYTEST_PRECONDITION_EXCEPTION = "io.micronaut.test.pytest.execution.PytestPreconditionException";
    private static final List<TestResourcesProperty> TEST_RESOURCES_PROPERTIES = List.of(
        new TestResourcesProperty("MICRONAUT_TEST_RESOURCES_SERVER_URI", "micronaut.test.resources.server.uri"),
        new TestResourcesProperty("MICRONAUT_TEST_RESOURCES_SERVER_ACCESS_TOKEN", "micronaut.test.resources.server.access.token"),
        new TestResourcesProperty("MICRONAUT_TEST_RESOURCES_SERVER_CLIENT_READ_TIMEOUT", "micronaut.test.resources.server.client.read.timeout")
    );

    @CommandLine.Option(names = "--project-dir", defaultValue = ".", description = "Project directory")
    Path projectDir = Path.of(".");

    @CommandLine.Option(names = "--classes-dir", defaultValue = DEFAULT_CLASSES_DIR, description = "Processed classes directory")
    Path classesDir = Path.of(DEFAULT_CLASSES_DIR);

    @CommandLine.Option(names = "--test-classes-dir", defaultValue = DEFAULT_TEST_CLASSES_DIR, description = "Processed test classes directory")
    Path testClassesDir = Path.of(DEFAULT_TEST_CLASSES_DIR);

    @CommandLine.Option(names = "--config-dir", defaultValue = DEFAULT_CONFIG_DIR, description = "Configuration directory")
    Path configDir = Path.of(DEFAULT_CONFIG_DIR);

    @CommandLine.Option(names = "--tests-dir", defaultValue = DEFAULT_TESTS_DIR, description = "Python tests directory")
    Path testsDir = Path.of(DEFAULT_TESTS_DIR);

    @CommandLine.Option(names = "--select-class", description = "Select class to execute")
    List<String> selectClasses = List.of();

    @CommandLine.Option(names = "--tests", description = "Select tests (Gradle-like). Repeatable.")
    List<String> tests = List.of();

    @CommandLine.Option(
        names = "--debug-vm",
        description = "Enable JVM JDWP debugging on port 5005 (flag is accepted for orchestrator forwarding)"
    )
    boolean debugVm;

    @CommandLine.Option(
        names = "--verbose",
        arity = "0..1",
        fallbackValue = "",
        description = "Enable verbose output, optionally scoped to comma-separated logger names"
    )
    String verboseLogger;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec commandSpec;

    private final ContextBootstrapper contextBootstrapper;
    private final PyprojectModelReader modelReader;

    public PyronautTestMain() {
        this(new PyprojectModelReader(), (classLoader, applicationMain) ->
            GraalPyContextFactory.bootstrapReusableContext(classLoader, Map.of(), applicationMain)
        );
    }

    PyronautTestMain(PyprojectModelReader modelReader, ContextBootstrapper contextBootstrapper) {
        this.modelReader = modelReader;
        this.contextBootstrapper = contextBootstrapper;
    }

    @Override
    public Integer call() {
        initializeJavaHomeIfMissing(() -> System.getenv("JAVA_HOME"));
        Path root = projectDir.toAbsolutePath().normalize();
        PyprojectModel model;
        ResolvedProjectLayout layout;
        Path resolvedTestsDir;
        try {
            applyTestResourcesProperties(System.getenv());
            if (ExternalProjectLayout.isExternal(root)) {
                ExternalProjectLayout external = ExternalProjectLayout.read(root);
                layout = resolveExternalProjectLayout(root, classesDir, testClassesDir, external);
                Path projectToml = root.resolve("project.toml");
                model = Files.isRegularFile(projectToml) ? modelReader.readProjectToml(projectToml) : null;
                resolvedTestsDir = root.resolve(testsDir).normalize();
            } else {
                model = modelReader.readFile(root.resolve(PyprojectModelReader.FILE_NAME));
                Path resolvedConfigDir = resolveConfiguredPath(root, configDir, DEFAULT_CONFIG_DIR, model.pyronaut().sources().resources(), "--config-dir");
                Path resolvedTestResourcesDir = root.resolve(model.pyronaut().sources().testResources()).normalize();
                resolvedTestsDir = resolveConfiguredPath(root, testsDir, DEFAULT_TESTS_DIR, model.pyronaut().sources().pythonTest(), "--tests-dir");
                layout = resolveProjectLayout(root, classesDir, testClassesDir, resolvedConfigDir, resolvedTestResourcesDir,
                    resolveConfiguredPaths(root, model.pyronaut().sources().additionalResources()),
                    resolveConfiguredPaths(root, model.pyronaut().sources().additionalTestResources()));
            }
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
            return 8;
        } catch (Exception e) {
            System.err.println("Test execution failed: " + e.getMessage());
            return 7;
        }
        PyprojectModel.TestEngine testEngine = model == null
            ? PyprojectModel.TestEngine.BOTH
            : model.pyronaut().test().engine();
        boolean junitEnabled = testEngine != PyprojectModel.TestEngine.PYTEST;
        boolean pytestEnabled = testEngine != PyprojectModel.TestEngine.JUNIT;

        // Application logs, GraalPy diagnostics and pytest's own terminal output
        // are captured into the test reports unless the user asked to stream
        // them; the reporter always writes to the real console.
        boolean streamOutput = verboseLogger != null || (model != null && model.pyronaut().test().verboseEnabled());
        PrintStream console = System.err;
        ClassLoader previousContextClassLoader = Thread.currentThread().getContextClassLoader();
        String previousIntrospectionClassLoaderProperty = System.getProperty(MICRONAUT_INTROSPECTIONS_USE_CONTEXT_CLASSLOADER);
        String previousPythonEnabledProperty = System.getProperty(MICRONAUT_PYTHON_ENABLED);
        Path resolvedPytestSourceDir = resolvePytestSourceDir(root, resolvedTestsDir);
        String previousServerPortProperty = System.getProperty(MICRONAUT_SERVER_PORT);
        String previousFailOnNotPresentProperty = System.getProperty(CONFIGURATION_VALIDATOR_FAIL_ON_NOT_PRESENT);
        String previousConfigurationSuppressionsProperty = System.getProperty(CONFIGURATION_VALIDATOR_SUPPRESSIONS);
        String previousLoggerConfigProperty = System.getProperty(LOGGER_CONFIG_PROPERTY);
        BeanIntrospectionsProvider previousBeanIntrospectionsProvider = null;
        ConsoleCapture capture = streamOutput ? null : ConsoleCapture.install();
        // A test that needs a container waits on the Test Resources server,
        // which logs to its own file in its own process. This launcher owns the
        // terminal, so it is the one that can report that wait without tearing
        // the live region.
        try (layout;
             TestProgressReporter reporter = TestProgressReporter.create(console, streamOutput);
             TestResourcesLogMirror testResources = TestResourcesLogMirror.start(entry -> {
                 if (entry.error()) {
                     reporter.error(entry.message());
                 } else {
                     reporter.note(entry.message());
                 }
             })) {
            ClassLoader applicationClassLoader = layout.applicationClassLoader();
            if (ExternalProjectLayout.isExternal(root)) {
                applyExternalPythonDefault(applicationClassLoader, System.getenv());
            }
            if (PyronautLauncherLogging.shouldInitializeApplicationDefaults(applicationClassLoader)) {
                PyronautLauncherLogging.initializeApplicationDefaults((String) null);
            }
            if (verboseLogger != null) {
                PyronautLauncherLogging.initializeApplicationDefaults(verboseLogger);
            }
            setDefaultProperty(CONFIGURATION_VALIDATOR_FAIL_ON_NOT_PRESENT, "false");
            setDefaultProperty(CONFIGURATION_VALIDATOR_SUPPRESSIONS, "logger.levels.*");
            enableContextClassLoaderIntrospections();
            previousBeanIntrospectionsProvider = BeanIntrospectionProviders.set(new ContextClassLoaderBeanIntrospectionsProvider());
            defaultTestServerPort();
            Thread.currentThread().setContextClassLoader(applicationClassLoader);
            try {
                if (!ExternalProjectLayout.isExternal(root) || shouldBootstrapPythonContext(applicationClassLoader)) {
                    contextBootstrapper.bootstrap(applicationClassLoader, selectApplicationMain(resolvedPytestSourceDir));
                }
                // Bootstrapping the reusable Python context may change the
                // thread context classloader. Restore the application loader
                // before JUnit/Micronaut discovers test bean definitions;
                // external Java tests keep their generated definitions in
                // __pyronaut__/test-classes.
                Thread.currentThread().setContextClassLoader(applicationClassLoader);
                LauncherDiscoveryRequestBuilder requestBuilder = LauncherDiscoveryRequestBuilder.request();
                Path reportsDir = ExternalProjectLayout.isExternal(root)
                    ? ExternalProjectLayout.outputDirectory(root).resolve("reports/tests").normalize()
                    : root.resolve(DEFAULT_REPORTS_DIR).normalize();
                Path junitReport = reportsDir.resolve(DEFAULT_JUNIT_XML_REPORT);
                Path htmlReport = reportsDir.resolve(DEFAULT_HTML_REPORT);
                Path nodeIdReport = reportsDir.resolve(DEFAULT_NODEID_REPORT);
                Path eventsReport = reportsDir.resolve(DEFAULT_EVENTS_REPORT);
                Files.createDirectories(reportsDir);
                clearLegacyReportAliases(root);
                if (pytestEnabled) {
                    requestBuilder.configurationParameter(PYTEST_REPORT_DIR, reportsDir.toString());
                    requestBuilder.configurationParameter(PYTEST_JUNIT_XML_REPORT, junitReport.toString());
                    requestBuilder.configurationParameter(PYTEST_HTML_REPORT, htmlReport.toString());
                    requestBuilder.configurationParameter(PYTEST_LAST_NODEID_REPORT, nodeIdReport.toString());
                    requestBuilder.configurationParameter(PYTEST_EVENTS_REPORT, eventsReport.toString());
                }
                requestBuilder.filters(EngineFilter.includeEngines(engineIds(testEngine).toArray(String[]::new)));
                boolean publishReports = true;
                if (selectClasses == null || selectClasses.isEmpty()) {
                    java.util.LinkedHashSet<Path> classpathRoots = new java.util.LinkedHashSet<>();
                    if (junitEnabled) {
                        classpathRoots.add(layout.processedClassesRoot());
                    }
                    if (junitEnabled && ExternalProjectLayout.isExternal(root)) {
                        Path mainClasses = root.resolve(classesDir).normalize();
                        if (Files.isDirectory(mainClasses)) {
                            classpathRoots.add(mainClasses);
                        }
                        for (Class<?> testClass : externalTestClasses(root.resolve(testClassesDir).normalize(), applicationClassLoader)) {
                            requestBuilder.selectors(DiscoverySelectors.selectClass(testClass));
                        }
                    }
                    if (junitEnabled && !classpathRoots.isEmpty()) {
                        requestBuilder.selectors(DiscoverySelectors.selectClasspathRoots(classpathRoots));
                    }
                    if (pytestEnabled) {
                        Optional<String> pytestTests = buildPytestTestsParameter(normalizePytestTestSelectors(root, resolvedTestsDir, tests));
                        if (Files.isDirectory(resolvedPytestSourceDir)) {
                            List<Path> explicitFiles = resolveDirectTestFileSelectors(root, resolvedTestsDir, tests);
                            List<Path> selectedFiles = mapToPytestSourceFiles(resolvedTestsDir, resolvedPytestSourceDir, explicitFiles);
                            boolean onlyDirectSelectors = hasOnlyDirectFileSelectors(tests);
                            if (explicitFiles.isEmpty() || !onlyDirectSelectors) {
                                requestBuilder.selectors(DiscoverySelectors.selectDirectory(resolvedPytestSourceDir.toString()));
                                requestBuilder.configurationParameter(PYTEST_SOURCE_DIR, resolvedPytestSourceDir.toString());
                            } else {
                                for (Path file : selectedFiles) {
                                    requestBuilder.selectors(DiscoverySelectors.selectFile(file.toString()));
                                }
                            }
                        }
                        pytestTests.ifPresent(value -> requestBuilder.configurationParameter(PYTEST_TESTS, value));
                    }
                } else {
                    for (String className : selectClasses) {
                        try {
                            Class<?> testClass = Class.forName(className, false, applicationClassLoader);
                            requestBuilder.selectors(DiscoverySelectors.selectClass(testClass));
                        } catch (ClassNotFoundException e) {
                            console.println("Unable to load selected test class: " + className);
                            return 7;
                        }
                    }
                }
                LauncherDiscoveryRequest request = requestBuilder.build();
                Launcher launcher = LauncherFactory.create();
                SummaryGeneratingListener listener = new SummaryGeneratingListener();
                launcher.registerTestExecutionListeners(listener);
                List<JUnitReportWriter.TestResult> testResults = new CopyOnWriteArrayList<>();
                launcher.registerTestExecutionListeners(reporter);
                if (ExternalProjectLayout.isExternal(root) || junitEnabled) {
                    launcher.registerTestExecutionListeners(new TestExecutionListener() {
                        // The pytest engine publishes each test's captured
                        // stdout/stderr/log as report entries keyed by stream.
                        private final Map<TestIdentifier, Map<String, StringBuilder>> published = new ConcurrentHashMap<>();

                        @Override
                        public void reportingEntryPublished(TestIdentifier identifier, ReportEntry entry) {
                            Map<String, StringBuilder> streams = published.computeIfAbsent(identifier, ignored -> new ConcurrentHashMap<>());
                            entry.getKeyValuePairs().forEach((stream, text) ->
                                streams.computeIfAbsent(stream, ignored -> new StringBuilder()).append(text));
                        }

                        @Override
                        public void executionFinished(TestIdentifier identifier,
                                                       org.junit.platform.engine.TestExecutionResult result) {
                            if (!identifier.isTest()) {
                                return;
                            }
                            if (streamOutput) {
                                result.getThrowable().ifPresent(Throwable::printStackTrace);
                            }
                            JUnitReportWriter.Status reportStatus = switch (result.getStatus()) {
                                case SUCCESSFUL -> JUnitReportWriter.Status.PASSED;
                                case FAILED -> JUnitReportWriter.Status.FAILED;
                                case ABORTED -> JUnitReportWriter.Status.SKIPPED;
                            };
                            Map<String, StringBuilder> streams = published.getOrDefault(identifier, Map.of());
                            StringBuilder stdout = new StringBuilder(streams.getOrDefault("log", new StringBuilder()));
                            stdout.append(streams.getOrDefault("stdout", new StringBuilder()));
                            StringBuilder stderr = new StringBuilder(streams.getOrDefault("stderr", new StringBuilder()));
                            // Java tests take whatever reached the console meanwhile;
                            // pytest tests already attached theirs through the plugin.
                            if (capture != null && TestProgressReporter.isJavaTest(identifier)) {
                                stdout.append(capture.drain("stdout"));
                                stderr.append(capture.drain("stderr"));
                            }
                            testResults.add(new JUnitReportWriter.TestResult(
                                TestProgressReporter.name(identifier), reportStatus,
                                result.getThrowable().map(Throwable::toString).orElse(""), stdout.toString(), stderr.toString()));
                        }
                    });
                }
                if (verboseLogger != null) {
                    launcher.registerTestExecutionListeners(new org.junit.platform.launcher.TestExecutionListener() {
                        @Override
                        public void executionSkipped(org.junit.platform.launcher.TestIdentifier testIdentifier, String reason) {
                            System.err.println("Skipped JUnit test " + testIdentifier.getDisplayName() + ": " + reason);
                        }
                    });
                }
                try {
                    launcher.execute(request);
                } finally {
                    releaseConsole(capture, reportsDir);
                    if (junitEnabled) {
                        try {
                            JUnitReportWriter.write(reportsDir, listener.getSummary(), testResults);
                        } catch (IOException e) {
                            console.println("Unable to write test report: " + e.getMessage());
                        }
                    }
                }
                TestExecutionSummary summary = listener.getSummary();
                if (verboseLogger != null) {
                    summary.printTo(new java.io.PrintWriter(console, true));
                }
                Optional<Throwable> pytestPreconditionFailure = findPytestPreconditionFailure(summary);
                if (pytestPreconditionFailure.isPresent()) {
                    reporter.error(pytestPreconditionMessage(pytestPreconditionFailure.get()));
                    return 8;
                }
                reporter.summary(publishReports ? reportsDir : null);
                long failures = summary.getTotalFailureCount();
                return failures == 0 ? 0 : 7;
            } catch (Exception e) {
                releaseConsole(capture, null);
                if (isPytestPreconditionFailure(e)) {
                    console.println(pytestPreconditionMessage(e));
                    return 8;
                }
                if (e instanceof IllegalStateException) {
                    console.println(e.getMessage());
                    return 8;
                }
                console.println("Test execution failed: " + e.getMessage());
                return 7;
            }
        } catch (IOException e) {
            releaseConsole(capture, null);
            console.println("Test execution failed: " + e.getMessage());
            return 7;
        } finally {
            releaseConsole(capture, null);
            Thread.currentThread().setContextClassLoader(previousContextClassLoader);
            if (previousBeanIntrospectionsProvider != null) {
                BeanIntrospectionProviders.set(previousBeanIntrospectionsProvider);
            }
            restoreSystemProperty(MICRONAUT_INTROSPECTIONS_USE_CONTEXT_CLASSLOADER, previousIntrospectionClassLoaderProperty);
            restoreSystemProperty(MICRONAUT_PYTHON_ENABLED, previousPythonEnabledProperty);
            restoreSystemProperty(MICRONAUT_SERVER_PORT, previousServerPortProperty);
            restoreSystemProperty(CONFIGURATION_VALIDATOR_FAIL_ON_NOT_PRESENT, previousFailOnNotPresentProperty);
            restoreSystemProperty(CONFIGURATION_VALIDATOR_SUPPRESSIONS, previousConfigurationSuppressionsProperty);
            restoreSystemProperty(LOGGER_CONFIG_PROPERTY, previousLoggerConfigProperty);
        }
    }

    static void enableContextClassLoaderIntrospections() {
        if (System.getProperty(MICRONAUT_INTROSPECTIONS_USE_CONTEXT_CLASSLOADER) == null) {
            System.setProperty(MICRONAUT_INTROSPECTIONS_USE_CONTEXT_CLASSLOADER, "true");
        }
    }

    static void defaultTestServerPort() {
        if (System.getProperty(MICRONAUT_SERVER_PORT) == null) {
            System.setProperty(MICRONAUT_SERVER_PORT, DEFAULT_TEST_SERVER_PORT);
        }
    }

    static List<String> engineIds(PyprojectModel.TestEngine engine) {
        return switch (engine) {
            case JUNIT -> List.of("junit-jupiter");
            case PYTEST -> List.of(PytestTestEngine.ENGINE_ID);
            case BOTH -> List.of("junit-jupiter", PytestTestEngine.ENGINE_ID);
        };
    }

    private static void setDefaultProperty(String name, String value) {
        if (System.getProperty(name) == null) {
            System.setProperty(name, value);
        }
    }

    private static void restoreSystemProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    static String selectApplicationMain(Path resolvedTestsDir) {
        if (Files.isRegularFile(resolvedTestsDir.resolve(TEST_APPLICATION_MAIN))) {
            return TEST_APPLICATION_MAIN;
        }
        return GraalPyContextFactory.APPLICATION_MAIN;
    }

    static Path resolvePytestSourceDir(Path root, Path resolvedTestsDir) {
        Path processedTestSources = root.resolve(DEFAULT_TEST_SOURCES_DIR).normalize();
        if (Files.isDirectory(processedTestSources)) {
            return processedTestSources;
        }
        return resolvedTestsDir;
    }

    private static ClassLoader resolveApplicationClassLoader() {
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        return contextClassLoader != null ? contextClassLoader : PyronautTestMain.class.getClassLoader();
    }

    static void initializeJavaHomeIfMissing(Supplier<String> javaHomeSupplier) {
        String currentJavaHome = System.getProperty("java.home");
        if (currentJavaHome != null && !currentJavaHome.isBlank()) {
            return;
        }
        String javaHome = javaHomeSupplier.get();
        if (javaHome != null && !javaHome.isBlank()) {
            System.setProperty("java.home", javaHome);
        }
    }

    static boolean shouldBootstrapPythonContext(ClassLoader classLoader) {
        if (classLoader.getResource(PYTHON_ENABLED_MARKER) != null) {
            return true;
        }
        String enabled = System.getProperty(MICRONAUT_PYTHON_ENABLED);
        if (enabled == null) {
            enabled = System.getenv("MICRONAUT_PYTHON_ENABLED");
        }
        return Boolean.parseBoolean(enabled);
    }

    static boolean applyExternalPythonDefault(ClassLoader classLoader, Map<String, String> environment) {
        if (classLoader.getResource(PYTHON_ENABLED_MARKER) != null
            || System.getProperty(MICRONAUT_PYTHON_ENABLED) != null
            || environment.get("MICRONAUT_PYTHON_ENABLED") != null) {
            return false;
        }
        System.setProperty(MICRONAUT_PYTHON_ENABLED, Boolean.FALSE.toString());
        return true;
    }

    static ResolvedProjectLayout resolveProjectLayout(Path root, Path classesDir, Path testClassesDir, Path configDir, Path testResourcesDir) throws IOException {
        return resolveProjectLayout(root, classesDir, testClassesDir, configDir, testResourcesDir, List.of(), List.of());
    }

    static ResolvedProjectLayout resolveExternalProjectLayout(Path root, Path classesDir, Path testClassesDir, ExternalProjectLayout external) throws IOException {
        Path output = ExternalProjectLayout.outputDirectory(root);
        Path resolvedTestClassesDir = output.resolve("test-classes").normalize();
        Path resolvedClassesDir = output.resolve("classes").normalize();
        Path processed = Files.isDirectory(resolvedTestClassesDir) ? resolvedTestClassesDir : resolvedClassesDir;
        if (!Files.isDirectory(processed)) {
            throw new IllegalStateException("Missing processed classes directory: " + resolvedClassesDir + ". Run pyronaut process first.");
        }
        LinkedHashSet<URL> urls = new LinkedHashSet<>();
        for (Path entry : external.testClasspath()) {
            if (Files.exists(entry)) {
                urls.add(entry.toUri().toURL());
            }
        }
        if (Files.isDirectory(resolvedTestClassesDir)) {
            urls.add(resolvedTestClassesDir.toUri().toURL());
        }
        if (Files.isDirectory(resolvedClassesDir)) {
            urls.add(resolvedClassesDir.toUri().toURL());
        }
        for (Path resource : external.mainResources()) {
            if (Files.isDirectory(resource)) {
                urls.add(resource.toUri().toURL());
            }
        }
        for (Path resource : external.testResources()) {
            if (Files.isDirectory(resource)) {
                urls.add(resource.toUri().toURL());
            }
        }
        return new ResolvedProjectLayout(processed, List.copyOf(urls));
    }

    private static List<Class<?>> externalTestClasses(Path testClassesRoot, ClassLoader classLoader) {
        if (!Files.isDirectory(testClassesRoot)) {
            return List.of();
        }
        List<Class<?>> classes = new ArrayList<>();
        try (var files = Files.walk(testClassesRoot)) {
            files.filter(path -> path.toString().endsWith(".class"))
                .filter(path -> !path.getFileName().toString().contains("$"))
                .filter(path -> !path.getFileName().toString().equals("module-info.class"))
                .forEach(path -> {
                    String className = testClassesRoot.relativize(path).toString()
                        .replace(java.io.File.separatorChar, '.')
                        .replaceAll("\\.class$", "");
                    try {
                        classes.add(Class.forName(className, false, classLoader));
                    } catch (ClassNotFoundException ignored) {
                        // The processor may have left a non-loadable generated class.
                    }
                });
        } catch (IOException ignored) {
            // Keep classpath-root discovery for incomplete output trees.
        }
        return classes;
    }

    static ResolvedProjectLayout resolveProjectLayout(Path root,
                                                      Path classesDir,
                                                      Path testClassesDir,
                                                      Path configDir,
                                                      Path testResourcesDir,
                                                      List<Path> additionalResourceDirs,
                                                      List<Path> additionalTestResourceDirs) throws IOException {
        Path pyronautDir = root.resolve(DEFAULT_PYRONAUT_DIR).normalize();
        Path resolvedTestClassesDir = root.resolve(testClassesDir).normalize();
        Path resolvedClassesDir = root.resolve(classesDir).normalize();
        boolean hasTestClassesDir = Files.isDirectory(resolvedTestClassesDir);
        Path processedClassesRoot;
        if (hasTestClassesDir) {
            processedClassesRoot = resolvedTestClassesDir;
        } else if (Files.isDirectory(resolvedClassesDir)) {
            processedClassesRoot = resolvedClassesDir;
        } else {
            throw new IllegalStateException("Missing processed classes directory: " + resolvedClassesDir + ". Run pyronaut process first.");
        }

        LinkedHashSet<URL> urls = new LinkedHashSet<>();
        ParentClasspath parentClasspath = parentClasspathEntries();
        addManifestEntries(urls, pyronautDir.resolve("resolved-test-dependencies"), parentClasspath);
        addManifestEntries(urls, pyronautDir.resolve("resolved-runtime-dependencies"), parentClasspath);
        addManifestEntries(urls, pyronautDir.resolve("resolved-build-dependencies"), parentClasspath);
        // Test processing can add imports/decorators to package VFS modules.
        // Put it first so those modules are not shadowed by production output.
        addPathIfDirectory(urls, resolvedTestClassesDir);
        // Tests can still resolve production-generated facades (for example an
        // HTTP client declared in test sources whose implementation is a
        // production bean), so expose both processed output roots.
        addPathIfDirectory(urls, resolvedClassesDir);
        addPathIfDirectory(urls, configDir);
        addResourceDirectories(urls, additionalResourceDirs);
        addPathIfDirectory(urls, testResourcesDir);
        addResourceDirectories(urls, additionalTestResourceDirs);
        return new ResolvedProjectLayout(processedClassesRoot, List.copyOf(urls));
    }

    private Path resolveConfiguredPath(Path root,
                                       Path cliValue,
                                       String defaultValue,
                                       String configuredValue,
                                       String optionName) {
        if (isExplicitlyConfigured(optionName)) {
            return root.resolve(cliValue).normalize();
        }
        if (Path.of(defaultValue).equals(cliValue)) {
            return root.resolve(configuredValue).normalize();
        }
        return root.resolve(cliValue).normalize();
    }

    private boolean isExplicitlyConfigured(String optionName) {
        return commandSpec != null
            && commandSpec.commandLine() != null
            && commandSpec.commandLine().getParseResult() != null
            && commandSpec.commandLine().getParseResult().hasMatchedOption(optionName);
    }

    private static List<Path> resolveConfiguredPaths(Path root, List<String> configuredDirs) {
        if (configuredDirs == null || configuredDirs.isEmpty()) {
            return List.of();
        }
        return configuredDirs.stream()
            .map(Path::of)
            .map(path -> path.isAbsolute() ? path.normalize() : root.resolve(path).normalize())
            .toList();
    }

    private static void addManifestEntries(LinkedHashSet<URL> urls, Path manifest, ParentClasspath parentClasspath) throws IOException {
        if (!Files.exists(manifest)) {
            return;
        }
        for (String line : Files.readAllLines(manifest)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Path path = Path.of(trimmed).toAbsolutePath().normalize();
            if (isLauncherProvidedArtifact(path)) {
                continue;
            }
            if (parentClasspath.contains(path)) {
                continue;
            }
            urls.add(path.toUri().toURL());
        }
    }

    static ParentClasspath parentClasspathEntries() {
        LinkedHashSet<Path> entries = new LinkedHashSet<>();
        addClasspathPropertyEntries(entries, System.getProperty("java.class.path", ""));
        ClassLoader classLoader = resolveApplicationClassLoader();
        while (classLoader instanceof URLClassLoader urlClassLoader) {
            for (URL url : urlClassLoader.getURLs()) {
                if ("file".equals(url.getProtocol())) {
                    try {
                        entries.add(Path.of(url.toURI()).toAbsolutePath().normalize());
                    } catch (Exception ignored) {
                        // Ignore malformed classpath URLs; they cannot be compared safely.
                    }
                }
            }
            classLoader = classLoader.getParent();
        }
        LinkedHashSet<String> fileNames = new LinkedHashSet<>();
        for (Path entry : entries) {
            if (entry.getFileName() != null) {
                fileNames.add(entry.getFileName().toString());
            }
        }
        return new ParentClasspath(entries, fileNames);
    }

    private static void addClasspathPropertyEntries(LinkedHashSet<Path> entries, String classpath) {
        if (classpath == null || classpath.isBlank()) {
            return;
        }
        for (String entry : classpath.split(java.io.File.pathSeparator)) {
            if (!entry.isBlank()) {
                entries.add(Path.of(entry).toAbsolutePath().normalize());
            }
        }
    }

    static boolean isLauncherProvidedArtifact(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String fileName = path.getFileName().toString();
        return LAUNCHER_PROVIDED_ARTIFACT_IDS.stream().anyMatch(artifactId -> matchesLauncherProvidedArtifact(path, fileName, artifactId))
            || LAUNCHER_PROVIDED_ARTIFACT_PREFIXES.stream().anyMatch(fileName::startsWith);
    }

    private static boolean matchesLauncherProvidedArtifact(Path path, String fileName, String artifactId) {
        if (path.getParent() != null && path.getParent().getFileName() != null && artifactId.equals(path.getParent().getFileName().toString())) {
            return true;
        }
        String prefix = artifactId + "-";
        return fileName.startsWith(prefix)
            && fileName.length() > prefix.length()
            && Character.isDigit(fileName.charAt(prefix.length()));
    }

    private static void addPathIfDirectory(LinkedHashSet<URL> urls, Path path) throws IOException {
        if (Files.isDirectory(path)) {
            urls.add(path.toAbsolutePath().normalize().toUri().toURL());
        }
    }

    private static void addResourceDirectories(LinkedHashSet<URL> urls, List<Path> resourceDirs) throws IOException {
        for (Path resourceDir : resourceDirs) {
            addPathIfDirectory(urls, resourceDir);
        }
    }

    @FunctionalInterface
    interface ContextBootstrapper {
        void bootstrap(ClassLoader classLoader, String applicationMain) throws Exception;
    }

    static void applyTestResourcesProperties(java.util.Map<String, String> environment) {
        for (TestResourcesProperty property : TEST_RESOURCES_PROPERTIES) {
            if (System.getProperty(property.systemProperty()) != null) {
                continue;
            }
            String value = environment.get(property.environmentVariable());
            if (value == null || value.isBlank()) {
                continue;
            }
            System.setProperty(property.systemProperty(), value);
        }
    }

    static Optional<String> buildPytestTestsParameter(List<String> rawSelectors) {
        if (rawSelectors == null || rawSelectors.isEmpty()) {
            return Optional.empty();
        }
        List<String> cleaned = new ArrayList<>();
        for (String raw : rawSelectors) {
            if (raw == null) {
                continue;
            }
            String value = raw.trim();
            if (value.isEmpty()) {
                throw new IllegalStateException("Invalid --tests selector: blank value");
            }
            if (value.contains("\n") || value.contains("\r")) {
                throw new IllegalStateException("Invalid --tests selector: newlines are not allowed: " + value);
            }
            cleaned.add(value);
        }
        if (cleaned.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(String.join("|", cleaned));
    }

    static List<String> normalizePytestTestSelectors(Path projectRoot, Path resolvedTestsDir, List<String> rawSelectors) {
        if (rawSelectors == null || rawSelectors.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String raw : rawSelectors) {
            if (raw == null) {
                continue;
            }
            String value = raw.trim();
            int nodeIndex = value.indexOf("::");
            String selector = nodeIndex >= 0 ? value.substring(0, nodeIndex) : value;
            String nodeSuffix = nodeIndex >= 0 ? value.substring(nodeIndex) : "";
            if (selector.endsWith(".py") && !selector.contains("*") && !selector.contains("?")) {
                Path candidate = toProjectPath(projectRoot, resolvedTestsDir, selector).normalize();
                try {
                    if (candidate.startsWith(resolvedTestsDir.normalize())) {
                        normalized.add(toForwardSlash(resolvedTestsDir.normalize().relativize(candidate)) + nodeSuffix);
                        continue;
                    }
                } catch (IllegalArgumentException ignored) {
                    // Keep the user-provided selector when it is not under the configured tests directory.
                }
            }
            normalized.add(raw);
        }
        return List.copyOf(normalized);
    }

    static List<Path> resolveDirectTestFileSelectors(Path projectRoot, Path resolvedTestsDir, List<String> rawSelectors) {
        if (rawSelectors == null || rawSelectors.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Path> resolved = new LinkedHashSet<>();
        for (String raw : rawSelectors) {
            if (raw == null) {
                continue;
            }
            String value = raw.trim();
            if (value.isEmpty()) {
                continue;
            }

            String selector = value;
            int nodeIndex = selector.indexOf("::");
            if (nodeIndex >= 0) {
                selector = selector.substring(0, nodeIndex);
            }

            if (selector.contains("*") || selector.contains("?")) {
                continue;
            }

            Path candidate = null;
            if (selector.endsWith(".py")) {
                candidate = toProjectPath(projectRoot, resolvedTestsDir, selector);
            } else if (!selector.contains("/") && !selector.contains("\\")) {
                candidate = resolvedTestsDir.resolve(selector + ".py");
            }

            if (candidate != null) {
                Path normalized = candidate.normalize();
                if (Files.isRegularFile(normalized)) {
                    resolved.add(normalized);
                }
            }
        }
        return List.copyOf(resolved);
    }

    private static String toForwardSlash(Path path) {
        return path.toString().replace('\\', '/');
    }

    static List<Path> mapToPytestSourceFiles(Path resolvedTestsDir, Path pytestSourceDir, List<Path> originalFiles) {
        if (resolvedTestsDir.equals(pytestSourceDir) || originalFiles == null || originalFiles.isEmpty()) {
            return originalFiles == null ? List.of() : originalFiles;
        }
        return originalFiles.stream()
            .map(file -> mapToPytestSourceFile(resolvedTestsDir, pytestSourceDir, file))
            .toList();
    }

    private static Path mapToPytestSourceFile(Path resolvedTestsDir, Path pytestSourceDir, Path originalFile) {
        try {
            Path relative = resolvedTestsDir.relativize(originalFile);
            Path processedFile = pytestSourceDir.resolve(relative).normalize();
            if (Files.isRegularFile(processedFile)) {
                return processedFile;
            }
        } catch (IllegalArgumentException ignored) {
            // Fall back to the original selector if it is not under the configured tests directory.
        }
        return originalFile;
    }

    static boolean hasOnlyDirectFileSelectors(List<String> rawSelectors) {
        if (rawSelectors == null || rawSelectors.isEmpty()) {
            return false;
        }
        for (String raw : rawSelectors) {
            if (raw == null) {
                continue;
            }
            String value = raw.trim();
            if (value.isEmpty()) {
                continue;
            }
            String selector = value;
            int nodeIndex = selector.indexOf("::");
            if (nodeIndex >= 0) {
                selector = selector.substring(0, nodeIndex);
            }
            if (selector.contains("*") || selector.contains("?")) {
                return false;
            }
        }
        return true;
    }

    private static Path toProjectPath(Path projectRoot, Path resolvedTestsDir, String selector) {
        Path selectorPath = Path.of(selector);
        if (selectorPath.isAbsolute()) {
            return selectorPath;
        }
        Path testsRelative = resolvedTestsDir.resolve(selectorPath);
        if (Files.exists(testsRelative)) {
            return testsRelative;
        }
        return projectRoot.resolve(selectorPath);
    }

    static boolean isPytestPreconditionFailure(Throwable throwable) {
        return findPytestPreconditionFailure(throwable).isPresent();
    }

    static String pytestPreconditionMessage(Throwable throwable) {
        return findPytestPreconditionFailure(throwable)
            .map(Throwable::getMessage)
            .filter(message -> message != null && !message.isBlank())
            .orElseGet(() -> throwable.getMessage());
    }

    /**
     * Stop capturing the standard streams. Output that no test claimed (test
     * session banners, application bootstrap logging) is kept beside the
     * other reports as {@code console.log}.
     */
    private static void releaseConsole(ConsoleCapture capture, Path reportsDir) {
        if (capture == null || ConsoleCapture.active() != capture) {
            return;
        }
        String stdout = capture.drainSession("stdout");
        String stderr = capture.drainSession("stderr");
        capture.close();
        if (reportsDir == null) {
            if (!stdout.isBlank()) {
                System.out.print(stdout);
            }
            if (!stderr.isBlank()) {
                System.err.print(stderr);
            }
            return;
        }
        try {
            Files.createDirectories(reportsDir);
            Files.writeString(reportsDir.resolve(DEFAULT_CONSOLE_LOG), stdout + (stderr.isEmpty() ? "" : "\n--- stderr ---\n" + stderr), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Unable to write console log: " + e.getMessage());
        }
    }

    static void publishReportLocations(Path reportsDir) {
        TestProgressReporter.printReportLocations(System.out, reportsDir, true);
    }

    private static Optional<Throwable> findPytestPreconditionFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (PYTEST_PRECONDITION_EXCEPTION.equals(current.getClass().getName())) {
                return Optional.of(current);
            }
            current = current.getCause();
        }
        return Optional.empty();
    }

    private static Optional<Throwable> findPytestPreconditionFailure(TestExecutionSummary summary) {
        return summary.getFailures()
            .stream()
            .map(TestExecutionSummary.Failure::getException)
            .map(PyronautTestMain::findPytestPreconditionFailure)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .findFirst();
    }

    static void clearLegacyReportAliases(Path projectRoot) {
        try {
            Files.deleteIfExists(projectRoot.resolve(DEFAULT_JUNIT_XML_REPORT).normalize());
        } catch (Exception e) {
            System.err.println("Unable to remove stale mirrored report: " + projectRoot.resolve(DEFAULT_JUNIT_XML_REPORT).normalize() + " (" + e.getMessage() + ")");
        }
        try {
            Files.deleteIfExists(projectRoot.resolve(DEFAULT_HTML_REPORT).normalize());
        } catch (Exception e) {
            System.err.println("Unable to remove stale mirrored report: " + projectRoot.resolve(DEFAULT_HTML_REPORT).normalize() + " (" + e.getMessage() + ")");
        }
        try {
            Files.deleteIfExists(projectRoot.resolve(DEFAULT_NODEID_REPORT).normalize());
        } catch (Exception e) {
            System.err.println("Unable to remove stale mirrored report: " + projectRoot.resolve(DEFAULT_NODEID_REPORT).normalize() + " (" + e.getMessage() + ")");
        }
    }

    public static void main(String[] args) {
        Terminal.notifyLaunched();
        PyronautRuntimeProperties.disableGraalVmImageSingletons();
        PyronautLauncherLogging.initialize();
        int exitCode = new CommandLine(new PyronautTestMain()).execute(args);
        System.exit(exitCode);
    }

    record ResolvedProjectLayout(Path processedClassesRoot, List<URL> classpathUrls) implements AutoCloseable {
        private static final String NATIVE_IMAGE_CODE = "org.graalvm.nativeimage.imagecode";

        ResolvedProjectLayout {
            classpathUrls = List.copyOf(classpathUrls);
        }

        ClassLoader applicationClassLoader() {
            if (usesNativeSystemClassLoader()) {
                return ClassLoader.getSystemClassLoader();
            }
            return new URLClassLoader(classpathUrls.toArray(URL[]::new), resolveApplicationClassLoader());
        }

        @Override
        public void close() throws IOException {
        }

        private static boolean usesNativeSystemClassLoader() {
            return (System.getProperty(NATIVE_IMAGE_CODE) != null || Boolean.getBoolean(PYRONAUT_USE_SYSTEM_APPLICATION_CLASSLOADER))
                && System.getProperty("java.class.path") != null
                && !System.getProperty("java.class.path").isBlank();
        }
    }

    record ParentClasspath(LinkedHashSet<Path> paths, LinkedHashSet<String> fileNames) {
        boolean contains(Path path) {
            if (paths.contains(path)) {
                return true;
            }
            return path.getFileName() != null && fileNames.contains(path.getFileName().toString());
        }
    }

    private record TestResourcesProperty(String environmentVariable, String systemProperty) {
    }
}

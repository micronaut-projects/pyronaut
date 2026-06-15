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
import io.micronaut.core.beans.BeanIntrospectionProviders;
import io.micronaut.core.beans.BeanIntrospectionsProvider;
import io.micronaut.pyronaut.config.classloader.ContextClassLoaderBeanIntrospectionsProvider;
import io.micronaut.pyronaut.config.model.PyprojectModel;
import io.micronaut.pyronaut.config.model.PyprojectModelReader;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import picocli.CommandLine;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private static final String DEFAULT_CONFIG_DIR = "config";
    private static final String DEFAULT_TESTS_DIR = "tests";
    private static final String TEST_APPLICATION_MAIN = "tests.py";
    private static final String DEFAULT_REPORTS_DIR = "__pyronaut__/reports/tests";
    private static final String DEFAULT_JUNIT_XML_REPORT = "junit.xml";
    private static final String DEFAULT_HTML_REPORT = "index.html";
    private static final String DEFAULT_NODEID_REPORT = ".pyronaut-last-nodeid.txt";
    private static final String DEFAULT_EVENTS_REPORT = "events.ndjson";
    private static final String MICRONAUT_INTROSPECTIONS_USE_CONTEXT_CLASSLOADER = "micronaut.introspections.use.context.classloader";
    private static final String MICRONAUT_SERVER_PORT = "micronaut.server.port";
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
            model = modelReader.readFile(root.resolve(PyprojectModelReader.FILE_NAME));
            Path resolvedConfigDir = resolveConfiguredPath(root, configDir, DEFAULT_CONFIG_DIR, model.pyronaut().sources().resources(), "--config-dir");
            Path resolvedTestResourcesDir = root.resolve(model.pyronaut().sources().testResources()).normalize();
            resolvedTestsDir = resolveConfiguredPath(root, testsDir, DEFAULT_TESTS_DIR, model.pyronaut().sources().pythonTest(), "--tests-dir");
            layout = resolveProjectLayout(
                root,
                classesDir,
                testClassesDir,
                resolvedConfigDir,
                resolvedTestResourcesDir,
                resolveConfiguredPaths(root, model.pyronaut().sources().additionalResources()),
                resolveConfiguredPaths(root, model.pyronaut().sources().additionalTestResources())
            );
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
            return 8;
        } catch (Exception e) {
            System.err.println("Test execution failed: " + e.getMessage());
            return 7;
        }

        ClassLoader previousContextClassLoader = Thread.currentThread().getContextClassLoader();
        String previousIntrospectionClassLoaderProperty = System.getProperty(MICRONAUT_INTROSPECTIONS_USE_CONTEXT_CLASSLOADER);
        Path resolvedPytestSourceDir = resolvePytestSourceDir(root, resolvedTestsDir);
        String previousServerPortProperty = System.getProperty(MICRONAUT_SERVER_PORT);
        BeanIntrospectionsProvider previousBeanIntrospectionsProvider = null;
        try (layout) {
            ClassLoader applicationClassLoader = layout.applicationClassLoader();
            enableContextClassLoaderIntrospections();
            previousBeanIntrospectionsProvider = BeanIntrospectionProviders.set(new ContextClassLoaderBeanIntrospectionsProvider());
            defaultTestServerPort();
            Thread.currentThread().setContextClassLoader(applicationClassLoader);
            try {
                contextBootstrapper.bootstrap(applicationClassLoader, selectApplicationMain(resolvedPytestSourceDir));
                LauncherDiscoveryRequestBuilder requestBuilder = LauncherDiscoveryRequestBuilder.request();
                boolean publishReports = false;
                if (selectClasses == null || selectClasses.isEmpty()) {
                    requestBuilder.selectors(DiscoverySelectors.selectClasspathRoots(java.util.Set.of(layout.processedClassesRoot())));
                    Optional<String> pytestTests = buildPytestTestsParameter(tests);
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
                    Path reportsDir = root.resolve(DEFAULT_REPORTS_DIR).normalize();
                    Path junitReport = reportsDir.resolve(DEFAULT_JUNIT_XML_REPORT);
                    Path htmlReport = reportsDir.resolve(DEFAULT_HTML_REPORT);
                    Path nodeIdReport = reportsDir.resolve(DEFAULT_NODEID_REPORT);
                    Path eventsReport = reportsDir.resolve(DEFAULT_EVENTS_REPORT);
                    Files.createDirectories(reportsDir);
                    clearLegacyReportAliases(root);
                    requestBuilder.configurationParameter(PYTEST_REPORT_DIR, reportsDir.toString());
                    requestBuilder.configurationParameter(PYTEST_JUNIT_XML_REPORT, junitReport.toString());
                    requestBuilder.configurationParameter(PYTEST_HTML_REPORT, htmlReport.toString());
                    requestBuilder.configurationParameter(PYTEST_LAST_NODEID_REPORT, nodeIdReport.toString());
                    requestBuilder.configurationParameter(PYTEST_EVENTS_REPORT, eventsReport.toString());
                    publishReports = true;

                    pytestTests.ifPresent(value -> requestBuilder.configurationParameter(PYTEST_TESTS, value));
                } else {
                    for (String className : selectClasses) {
                        requestBuilder.selectors(DiscoverySelectors.selectClass(className));
                    }
                }
                LauncherDiscoveryRequest request = requestBuilder.build();
                Launcher launcher = LauncherFactory.create();
                SummaryGeneratingListener listener = new SummaryGeneratingListener();
                launcher.registerTestExecutionListeners(listener);
                try {
                    launcher.execute(request);
                } finally {
                    if (publishReports) {
                        publishReportLocations(root);
                    }
                }
                TestExecutionSummary summary = listener.getSummary();
                Optional<Throwable> pytestPreconditionFailure = findPytestPreconditionFailure(summary);
                if (pytestPreconditionFailure.isPresent()) {
                    System.err.println(pytestPreconditionMessage(pytestPreconditionFailure.get()));
                    return 8;
                }
                long failures = summary.getTotalFailureCount();
                return failures == 0 ? 0 : 7;
            } catch (Exception e) {
                if (isPytestPreconditionFailure(e)) {
                    System.err.println(pytestPreconditionMessage(e));
                    return 8;
                }
                if (e instanceof IllegalStateException) {
                    System.err.println(e.getMessage());
                    return 8;
                }
                System.err.println("Test execution failed: " + e.getMessage());
                return 7;
            }
        } catch (IOException e) {
            System.err.println("Test execution failed: " + e.getMessage());
            return 7;
        } finally {
            Thread.currentThread().setContextClassLoader(previousContextClassLoader);
            if (previousBeanIntrospectionsProvider != null) {
                BeanIntrospectionProviders.set(previousBeanIntrospectionsProvider);
            }
            restoreSystemProperty(MICRONAUT_INTROSPECTIONS_USE_CONTEXT_CLASSLOADER, previousIntrospectionClassLoaderProperty);
            restoreSystemProperty(MICRONAUT_SERVER_PORT, previousServerPortProperty);
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

    static ResolvedProjectLayout resolveProjectLayout(Path root, Path classesDir, Path testClassesDir, Path configDir, Path testResourcesDir) throws IOException {
        return resolveProjectLayout(root, classesDir, testClassesDir, configDir, testResourcesDir, List.of(), List.of());
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
        if (hasTestClassesDir) {
            addPathIfDirectory(urls, resolvedTestClassesDir);
        } else {
            addPathIfDirectory(urls, resolvedClassesDir);
        }
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

    static void publishReportLocations(Path projectRoot) {
        Path reportsDir = projectRoot.resolve(DEFAULT_REPORTS_DIR).normalize();
        Path html = reportsDir.resolve(DEFAULT_HTML_REPORT);

        System.out.println("Test reports directory: " + reportsDir);
        System.out.println("HTML report: " + html);
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

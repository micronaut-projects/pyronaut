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

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Entry point for {@code pyronaut-test}.
 */
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
    private static final String DEFAULT_CONFIG_DIR = "config";
    private static final String DEFAULT_TESTS_DIR = "tests";
    private static final String DEFAULT_REPORTS_DIR = "__pyronaut__/reports/tests";
    private static final String DEFAULT_JUNIT_XML_REPORT = "junit.xml";
    private static final String DEFAULT_HTML_REPORT = "index.html";
    private static final String DEFAULT_NODEID_REPORT = ".pyronaut-last-nodeid.txt";
    private static final String DEFAULT_EVENTS_REPORT = "events.ndjson";
    private static final String PYTEST_SOURCE_DIR = "pytest.src.dir";

    private static final String PYTEST_TESTS = "pytest.tests";
    private static final String PYTEST_REPORT_DIR = "pytest.report.dir";
    private static final String PYTEST_JUNIT_XML_REPORT = "pytest.report.junit";
    private static final String PYTEST_HTML_REPORT = "pytest.report.html";
    private static final String PYTEST_LAST_NODEID_REPORT = "pytest.report.nodeid";
    private static final String PYTEST_EVENTS_REPORT = "pytest.report.events";

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

    @Override
    public Integer call() {
        Path root = projectDir.toAbsolutePath().normalize();
        try {
            Path resolvedTestClassesDir = root.resolve(testClassesDir).normalize();
            boolean hasTestClassesDir = Files.isDirectory(resolvedTestClassesDir);
            Path resolvedClassesDir = root.resolve(classesDir).normalize();
            Path processedClassesRoot;
            if (hasTestClassesDir) {
                processedClassesRoot = resolvedTestClassesDir;
            } else if (Files.isDirectory(resolvedClassesDir)) {
                processedClassesRoot = resolvedClassesDir;
            } else {
                System.err.println("Missing processed classes directory: " + resolvedClassesDir + ". Run pyronaut process first.");
                return 8;
            }

            Path resolvedConfigDir = root.resolve(configDir).normalize();
            if (!Files.isDirectory(resolvedConfigDir)) {
                resolvedConfigDir = null;
            }

            LauncherDiscoveryRequestBuilder requestBuilder = LauncherDiscoveryRequestBuilder.request();
            boolean publishReports = false;
            if (selectClasses == null || selectClasses.isEmpty()) {
                requestBuilder.selectors(DiscoverySelectors.selectClasspathRoots(java.util.Set.of(processedClassesRoot)));
                Path resolvedTestsDir = root.resolve(testsDir).normalize();
                Optional<String> pytestTests = buildPytestTestsParameter(tests);
                if (Files.isDirectory(resolvedTestsDir)) {
                    List<Path> explicitFiles = resolveDirectTestFileSelectors(root, resolvedTestsDir, tests);
                    boolean onlyDirectSelectors = hasOnlyDirectFileSelectors(tests);
                    if (explicitFiles.isEmpty() || !onlyDirectSelectors) {
                        requestBuilder.selectors(DiscoverySelectors.selectDirectory(resolvedTestsDir.toString()));
                        requestBuilder.configurationParameter(PYTEST_SOURCE_DIR, resolvedTestsDir.toString());
                    } else {
                        for (Path file : explicitFiles) {
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
            long failures = listener.getSummary().getTotalFailureCount();
            return failures == 0 ? 0 : 7;
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
            return 8;
        } catch (Exception e) {
            System.err.println("Test execution failed: " + e.getMessage());
            return 7;
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
        Path junit = reportsDir.resolve(DEFAULT_JUNIT_XML_REPORT);
        Path html = reportsDir.resolve(DEFAULT_HTML_REPORT);
        Path nodeid = reportsDir.resolve(DEFAULT_NODEID_REPORT);
        Path legacyJunit = projectRoot.resolve(DEFAULT_JUNIT_XML_REPORT).normalize();
        Path legacyHtml = projectRoot.resolve(DEFAULT_HTML_REPORT).normalize();
        Path legacyNodeid = projectRoot.resolve(DEFAULT_NODEID_REPORT).normalize();

        recoverCanonicalFromLegacy(junit, legacyJunit);
        recoverCanonicalFromLegacy(html, legacyHtml);
        recoverCanonicalFromLegacy(nodeid, legacyNodeid);

        System.out.println("Test reports directory: " + reportsDir);
        System.out.println("HTML report: " + html);

        syncMirror(junit, legacyJunit);
        syncMirror(html, legacyHtml);
        syncMirror(nodeid, legacyNodeid);
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

    private static void recoverCanonicalFromLegacy(Path canonical, Path legacy) {
        if (Files.exists(canonical) || !Files.exists(legacy)) {
            return;
        }
        try {
            Path parent = canonical.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(legacy, canonical, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            System.err.println("Unable to recover canonical report from legacy location: " + canonical + " (" + e.getMessage() + ")");
        }
    }

    private static void syncMirror(Path source, Path target) {
        if (!Files.exists(source)) {
            try {
                Files.deleteIfExists(target);
            } catch (Exception e) {
                System.err.println("Unable to remove stale mirrored report: " + target + " (" + e.getMessage() + ")");
            }
            return;
        }
        try {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            System.err.println("Unable to mirror report to project root: " + target + " (" + e.getMessage() + ")");
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new PyronautTestMain()).execute(args);
        System.exit(exitCode);
    }
}

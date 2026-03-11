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

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
    private static final String DEFAULT_CONFIG_DIR = "config";
    private static final String DEFAULT_TESTS_DIR = "tests";
    private static final String PYTEST_SOURCE_DIR = "pytest.src.dir";

    @CommandLine.Option(names = "--project-dir", defaultValue = ".", description = "Project directory")
    Path projectDir = Path.of(".");

    @CommandLine.Option(names = "--classes-dir", defaultValue = DEFAULT_CLASSES_DIR, description = "Processed classes directory")
    Path classesDir = Path.of(DEFAULT_CLASSES_DIR);

    @CommandLine.Option(names = "--config-dir", defaultValue = DEFAULT_CONFIG_DIR, description = "Configuration directory")
    Path configDir = Path.of(DEFAULT_CONFIG_DIR);

    @CommandLine.Option(names = "--tests-dir", defaultValue = DEFAULT_TESTS_DIR, description = "Python tests directory")
    Path testsDir = Path.of(DEFAULT_TESTS_DIR);

    @CommandLine.Option(names = "--classpath", split = "${sys:path.separator}", description = "Override test classpath")
    List<Path> classpath;

    @CommandLine.Option(names = "--select-class", description = "Select class to execute")
    List<String> selectClasses = List.of();

    @Override
    public Integer call() {
        Path root = projectDir.toAbsolutePath().normalize();
        try {
            List<Path> testClasspath = classpath == null || classpath.isEmpty()
                ? readManifest(root.resolve(DEFAULT_PYRONAUT_DIR).resolve("resolved-test-dependencies"))
                : classpath;
            List<Path> runtimeClasspath = readManifestIfPresent(root.resolve(DEFAULT_PYRONAUT_DIR).resolve("resolved-runtime-dependencies"));
            List<Path> buildClasspath = readManifestIfPresent(root.resolve(DEFAULT_PYRONAUT_DIR).resolve("resolved-build-dependencies"));
            if (!runtimeClasspath.isEmpty() || !buildClasspath.isEmpty()) {
                LinkedHashSet<Path> mergedClasspath = new LinkedHashSet<>(testClasspath);
                mergedClasspath.addAll(runtimeClasspath);
                mergedClasspath.addAll(buildClasspath);
                testClasspath = List.copyOf(mergedClasspath);
            }

            Path resolvedClassesDir = root.resolve(classesDir).normalize();
            if (!Files.isDirectory(resolvedClassesDir)) {
                System.err.println("Missing processed classes directory: " + resolvedClassesDir + ". Run pyronaut process first.");
                return 8;
            }

            List<URL> urls = new ArrayList<>();
            for (Path path : testClasspath) {
                urls.add(path.toUri().toURL());
            }
            urls.add(resolvedClassesDir.toUri().toURL());

            Path resolvedConfigDir = root.resolve(configDir).normalize();
            if (Files.isDirectory(resolvedConfigDir)) {
                urls.add(resolvedConfigDir.toUri().toURL());
            }

            try (URLClassLoader classLoader = new URLClassLoader(urls.toArray(URL[]::new), Thread.currentThread().getContextClassLoader())) {
                Thread.currentThread().setContextClassLoader(classLoader);
                LauncherDiscoveryRequestBuilder requestBuilder = LauncherDiscoveryRequestBuilder.request();
                if (selectClasses == null || selectClasses.isEmpty()) {
                    requestBuilder.selectors(DiscoverySelectors.selectClasspathRoots(java.util.Set.of(resolvedClassesDir)));
                    Path resolvedTestsDir = root.resolve(testsDir).normalize();
                    if (Files.isDirectory(resolvedTestsDir)) {
                        requestBuilder.selectors(DiscoverySelectors.selectDirectory(resolvedTestsDir.toString()));
                        requestBuilder.configurationParameter(PYTEST_SOURCE_DIR, resolvedTestsDir.toString());
                    }
                } else {
                    for (String className : selectClasses) {
                        requestBuilder.selectors(DiscoverySelectors.selectClass(className));
                    }
                }
                LauncherDiscoveryRequest request = requestBuilder.build();
                Launcher launcher = LauncherFactory.create();
                SummaryGeneratingListener listener = new SummaryGeneratingListener();
                launcher.registerTestExecutionListeners(listener);
                launcher.execute(request);
                long failures = listener.getSummary().getTotalFailureCount();
                return failures == 0 ? 0 : 7;
            }
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
            return 8;
        } catch (Exception e) {
            System.err.println("Test execution failed: " + e.getMessage());
            return 7;
        }
    }

    private static List<Path> readManifest(Path file) {
        if (!Files.exists(file)) {
            throw new IllegalStateException("Missing test scope cache. Run pyronaut install first. (missing: " + file + ")");
        }
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .map(Path::of)
                .toList();
        } catch (Exception e) {
            throw new IllegalStateException("Failed reading test classpath manifest: " + file, e);
        }
    }

    private static List<Path> readManifestIfPresent(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        return readManifest(file);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new PyronautTestMain()).execute(args);
        System.exit(exitCode);
    }
}

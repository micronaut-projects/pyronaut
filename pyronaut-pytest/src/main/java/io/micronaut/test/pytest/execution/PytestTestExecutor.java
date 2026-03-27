/*
 * Copyright 2017-2024 original authors
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
package io.micronaut.test.pytest.execution;

import io.micronaut.test.pytest.PytestFileDescriptor;
import io.micronaut.test.pytest.PytestTestDescriptor;
import io.micronaut.test.pytest.PythonAssertionError;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;


/**
 * Executes Python tests using pytest via GraalPy.
 */
public class PytestTestExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(PytestTestExecutor.class);
    private static final Pattern INTERNAL_STACK_FRAME = Pattern.compile("\\s+at (com\\.oracle\\.truffle\\.|com\\.oracle\\.graal\\.python\\.|org\\.graalvm\\.polyglot\\.|org\\.graalvm\\.python\\.embedding\\.|java\\.base/).*");
    private static final String DEFAULT_REPORTS_DIR = "__pyronaut__/reports/tests";
    private static final String DEFAULT_JUNIT_XML_REPORT = "junit.xml";
    private static final String DEFAULT_HTML_REPORT = "index.html";
    private static final String DEFAULT_NODEID_REPORT = ".pyronaut-last-nodeid.txt";
    private static final String DEFAULT_EVENTS_REPORT = "events.ndjson";

    private final EngineExecutionListener listener;
    private final Context context;
    private final String junitXmlReportPath;
    private final String htmlReportPath;
    private final String lastNodeIdReportPath;
    private final String eventsReportPath;

    public PytestTestExecutor(
        Context context,
        EngineExecutionListener listener,
        String junitXmlReportPath,
        String htmlReportPath,
        String lastNodeIdReportPath,
        String eventsReportPath
    ) {
        this.listener = listener;
        this.context = context;
        Path reportsDir = resolveReportsDir();
        this.junitXmlReportPath = resolveReportPath(junitXmlReportPath, reportsDir.resolve(DEFAULT_JUNIT_XML_REPORT));
        this.htmlReportPath = resolveReportPath(htmlReportPath, reportsDir.resolve(DEFAULT_HTML_REPORT));
        this.lastNodeIdReportPath = resolveReportPath(lastNodeIdReportPath, reportsDir.resolve(DEFAULT_NODEID_REPORT));
        this.eventsReportPath = resolveReportPath(eventsReportPath, reportsDir.resolve(DEFAULT_EVENTS_REPORT));
    }

    static String resolveReportPath(String configuredPath, Path fallbackPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            return fallbackPath.toString();
        }
        return configuredPath;
    }

    private static Path resolveReportsDir() {
        Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        return workingDirectory.resolve(DEFAULT_REPORTS_DIR).normalize();
    }

    /**
     * Executes the test descriptor and its children.
     *
     * @param descriptor The descriptor (engine, file, or test) to execute
     */
    public void execute(TestDescriptor descriptor) {
        LOG.debug("Executing test descriptor: {}", descriptor.getDisplayName());

        listener.executionStarted(descriptor);

        try {
            if (descriptor instanceof PytestFileDescriptor fileDescriptor) {
                executeFile(fileDescriptor);
            } else if (descriptor instanceof PytestTestDescriptor testDescriptor) {
                executeTest(testDescriptor);
            } else {
                // For the engine descriptor, run pytest on all discovered test files
                runPytestForAllTests(descriptor);
            }

            listener.executionFinished(descriptor, TestExecutionResult.successful());

        } catch (Exception e) {
            LOG.error("Error executing test descriptor {}: {}", descriptor.getDisplayName(), e.getMessage());
            listener.executionFinished(descriptor, TestExecutionResult.failed(compactFailure(e)));
        }
    }

    private void executeFile(PytestFileDescriptor fileDescriptor) {
        LOG.debug("Executing Python file: {}", fileDescriptor.getDisplayName());

        try {
            // Run pytest on this file with our custom plugin
            runPytestForFile(fileDescriptor);
        } catch (Exception e) {
            LOG.error("Error running pytest for file {}: {}", fileDescriptor.getDisplayName(), e.getMessage());
            // Mark all child tests as failed
            for (TestDescriptor child : fileDescriptor.getChildren()) {
                if (child instanceof PytestTestDescriptor testDescriptor) {
                    listener.executionStarted(testDescriptor);
                    listener.executionFinished(testDescriptor, TestExecutionResult.failed(compactFailure(e)));
                }
            }
        }
    }

    private void runPytestForFile(PytestFileDescriptor fileDescriptor) throws Exception {
        LOG.debug("Running pytest for file: {}", fileDescriptor.getDisplayName());

        Path filePath = Paths.get(fileDescriptor.getUniqueId().getSegments().get(1).getValue());
        try {
            JUnitPytestTestListener testListener = new JUnitPytestTestListener(
                listener,
                fileDescriptor.getChildren(),
                htmlReportPath,
                lastNodeIdReportPath,
                eventsReportPath
            );
            // Call run_pytest with the file path and listener
            Value result = context.eval("python", """
from pyronaut.test import run_pytest

run_pytest
            """).execute(
                new String[]{filePath.toString()},
                testListener,
                junitXmlReportPath
            );

            LOG.debug("Pytest execution completed for file: {}", fileDescriptor.getDisplayName());

        } catch (Exception e) {
            LOG.error("Error running pytest for file {}: {}", fileDescriptor.getDisplayName(), e.getMessage());
            throw e;
        }
    }

    private void runPytestForAllTests(TestDescriptor engineDescriptor) throws Exception {
        LOG.debug("Running pytest for all discovered tests");

        // Collect all unique test files from the test descriptors
        List<Path> testFiles = engineDescriptor.getChildren().stream()
            .filter(child -> child instanceof PytestTestDescriptor)
            .map(child -> ((PytestTestDescriptor) child).getFilePath())
            .toList();

        if (testFiles.isEmpty()) {
            LOG.debug("No test files found to execute");
            JUnitPytestTestListener testListener = new JUnitPytestTestListener(
                listener,
                engineDescriptor.getChildren(),
                htmlReportPath,
                lastNodeIdReportPath,
                eventsReportPath
            );
            testListener.onResult(TestExecutionResult.successful());
            return;
        }

        LOG.debug("Running pytest on {} test files: {}", testFiles.size(), testFiles);

        try {
            JUnitPytestTestListener testListener = new JUnitPytestTestListener(
                listener,
                engineDescriptor.getChildren(),
                htmlReportPath,
                lastNodeIdReportPath,
                eventsReportPath
            );
            // Convert paths to strings for pytest
            String[] fileArgs = testFiles.stream()
                .map(Path::toString)
                .toArray(String[]::new);

            // Call run_pytest with the file paths and listener
            context.eval("python", """
from pyronaut.test import run_pytest

run_pytest
            """).execute(
                fileArgs,
                testListener,
                junitXmlReportPath
            );

            LOG.debug("Pytest execution completed for all tests");

        } catch (Exception e) {
            LOG.error("Error running pytest for all tests: {}", e.getMessage());
            throw e;
        }
    }

    private void executeTest(PytestTestDescriptor testDescriptor) {
        LOG.debug("Executing Python test: {}", testDescriptor.getDisplayName());

        try {
            // Execute only the file containing this test via pytest; the plugin will map events back
            Path filePath = testDescriptor.getFilePath();
            JUnitPytestTestListener testListener = new JUnitPytestTestListener(
                listener,
                Set.copyOf(List.of(testDescriptor)),
                htmlReportPath,
                lastNodeIdReportPath,
                eventsReportPath
            );
            context.eval("python", """
from pyronaut.test import run_pytest

run_pytest
            """).execute(
                new String[]{filePath.toString()},
                testListener,
                junitXmlReportPath
            );
            LOG.debug("Test {} executed via pytest for file {}", testDescriptor.getDisplayName(), filePath);

        } catch (Exception e) {
            LOG.error("Test {} failed: {}", testDescriptor.getDisplayName(), e.getMessage());
            // If pytest invocation fails at the process level, still notify JUnit about the failure
            listener.executionStarted(testDescriptor);
            listener.executionFinished(testDescriptor, TestExecutionResult.failed(compactFailure(e)));
        }
    }

    private static PythonAssertionError compactFailure(Exception e) {
        String text = e.toString();
        StringBuilder compact = new StringBuilder();
        for (String line : text.split("\\R")) {
            if (INTERNAL_STACK_FRAME.matcher(line).matches()) {
                continue;
            }
            if (!compact.isEmpty()) {
                compact.append(System.lineSeparator());
            }
            compact.append(line);
        }
        String message = compact.isEmpty() ? e.getMessage() : compact.toString();
        String finalMessage = message == null || message.isBlank() ? "Pytest execution failed" : message;
        return new PythonAssertionError(finalMessage);
    }
}

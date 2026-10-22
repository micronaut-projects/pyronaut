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
package io.micronaut.test.pytest.execution;

import io.micronaut.test.pytest.FailureDiagnostics;
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
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;


/**
 * Executes Python tests using pytest via GraalPy.
 */
public class PytestTestExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(PytestTestExecutor.class);
    private static final String DEFAULT_REPORTS_DIR = "__pyronaut__/reports/tests";
    private static final String DEFAULT_JUNIT_XML_REPORT = "junit.xml";
    private static final String DEFAULT_HTML_REPORT = "index.html";
    private static final String DEFAULT_NODEID_REPORT = ".pyronaut-last-nodeid.txt";
    private static final String DEFAULT_EVENTS_REPORT = "events.ndjson";
    private static final String PYTEST_MISSING_MODULE = "No module named 'pytest'";
    private static final int PYTEST_TESTS_FAILED_EXIT_CODE = 1;
    private static final String PYTEST_SESSION_FAILED_PREFIX = "Pytest session failed with exit code: ";
    private static final String PYTEST_MISSING_MESSAGE = """
        Pytest is not installed in the Python environment used by Pyronaut.

        Pyronaut discovered tests, but it could not import the Python 'pytest' module.
        This usually means the project GraalPy virtual environment has not been created or pytest
        has not been installed into the GraalPy environment used by Pyronaut. A CPython virtual
        environment cannot provide packages to the embedded GraalPy runtime.

        From the project directory, run:

          graalpy -m venv .venv
          source .venv/bin/activate
          python -m pip install --upgrade pip pytest

        Then rerun:

          pyronaut test

        If you already use a virtual environment, activate it before running Pyronaut
        and confirm that 'python -m pytest --version' works in the same shell.
        """;

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

        if (descriptor instanceof PytestTestDescriptor testDescriptor) {
            // The callback listener owns the source container and its collected invocations.
            executeTest(testDescriptor);
            return;
        }

        listener.executionStarted(descriptor);

        try {
            if (descriptor instanceof PytestFileDescriptor fileDescriptor) {
                executeFile(fileDescriptor);
            } else {
                // For the engine descriptor, run pytest on all discovered test files
                runPytestForAllTests(descriptor);
            }

            listener.executionFinished(descriptor, TestExecutionResult.successful());

        } catch (Exception e) {
            listener.executionFinished(descriptor, TestExecutionResult.failed(compactFailure(e)));
            if (e instanceof PytestPreconditionException preconditionException) {
                throw preconditionException;
            }
            LOG.error("Error executing test descriptor {}: {}", descriptor.getDisplayName(), e.getMessage());
        }
    }

    private void executeFile(PytestFileDescriptor fileDescriptor) throws Exception {
        LOG.debug("Executing Python file: {}", fileDescriptor.getDisplayName());
        runPytestForFile(fileDescriptor);
    }

    private void runPytestForFile(PytestFileDescriptor fileDescriptor) throws Exception {
        LOG.debug("Running pytest for file: {}", fileDescriptor.getDisplayName());

        Path filePath = Paths.get(fileDescriptor.getUniqueId().getSegments().get(1).getValue());
        runPytest(fileDescriptor.getChildren(),
            pytestArgumentsForDescriptors(fileDescriptor.getChildren(), List.of(filePath.toString())));
    }

    private void runPytestForAllTests(TestDescriptor engineDescriptor) throws Exception {
        LOG.debug("Running pytest for all discovered tests");

        String[] testArguments = pytestArgumentsForDescriptors(engineDescriptor.getChildren(), List.of());
        runPytest(engineDescriptor.getChildren(), testArguments);
    }

    private void executeTest(PytestTestDescriptor testDescriptor) {
        LOG.debug("Executing Python test: {}", testDescriptor.getDisplayName());

        try {
            runPytest(Set.of(testDescriptor), new String[]{pytestNodeId(testDescriptor)});
            LOG.debug("Test {} executed via pytest", testDescriptor.getDisplayName());

        } catch (Exception e) {
            if (e instanceof PytestPreconditionException preconditionException) {
                throw preconditionException;
            }
            LOG.error("Test {} failed: {}", testDescriptor.getDisplayName(), e.getMessage());
            // runPytest already completed this source container with the failure.
        }
    }

    private void runPytest(Set<? extends TestDescriptor> descriptors, String[] arguments) throws Exception {
        JUnitPytestTestListener testListener = new JUnitPytestTestListener(
            listener, descriptors, htmlReportPath, lastNodeIdReportPath, eventsReportPath);
        TestExecutionResult result = TestExecutionResult.successful();
        try {
            if (arguments.length == 0) {
                testListener.onResult(result);
            } else {
                Value exitCode = pytestRunner().execute(arguments, testListener, junitXmlReportPath);
                failIfPytestFailed(testListener, exitCode);
            }
        } catch (Exception e) {
            result = TestExecutionResult.failed(compactFailure(e));
            throw e;
        } finally {
            testListener.finishSources(result);
        }
    }

    private Value pytestRunner() {
        ensurePytestInstalled();
        return context.eval("python", """
from pyronaut.test import run_pytest

run_pytest
            """);
    }

    static String[] pytestArgumentsForDescriptors(Collection<? extends TestDescriptor> descriptors, List<String> fallbackArguments) {
        LinkedHashSet<String> arguments = new LinkedHashSet<>();
        for (TestDescriptor descriptor : descriptors) {
            if (descriptor instanceof PytestTestDescriptor pytestTestDescriptor) {
                arguments.add(pytestNodeId(pytestTestDescriptor));
            } else if (descriptor instanceof PytestFileDescriptor pytestFileDescriptor) {
                List<String> childArguments = Arrays.asList(pytestArgumentsForDescriptors(pytestFileDescriptor.getChildren(), List.of()));
                if (childArguments.isEmpty()) {
                    fallbackArguments.forEach(arguments::add);
                } else {
                    arguments.addAll(childArguments);
                }
            }
        }
        if (arguments.isEmpty()) {
            arguments.addAll(fallbackArguments);
        }
        return arguments.toArray(String[]::new);
    }

    static String pytestNodeId(PytestTestDescriptor descriptor) {
        return descriptor.getPytestNodeId();
    }

    private void ensurePytestInstalled() {
        try {
            context.eval("python", """
                from pyronaut.test.pytest_runner import setup_virtual_filesystem
                setup_virtual_filesystem()
                import pytest
                pytest.__version__
                """);
        } catch (Exception e) {
            if (isMissingPytest(e)) {
                throw new PytestPreconditionException(PYTEST_MISSING_MESSAGE, e);
            }
            throw e;
        }
    }

    static boolean isMissingPytest(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains(PYTEST_MISSING_MODULE)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static PythonAssertionError compactFailure(Exception e) {
        String message = FailureDiagnostics.render(e);
        String finalMessage = message == null || message.isBlank() ? "Pytest execution failed" : message;
        return new PythonAssertionError(finalMessage, e);
    }

    static void failIfPytestFailed(JUnitPytestTestListener testListener, Value result) {
        TestExecutionResult sessionResult = testListener.sessionResult();
        if (sessionResult.getStatus() == TestExecutionResult.Status.FAILED) {
            Throwable throwable = sessionResult.getThrowable()
                .orElseGet(() -> new RuntimeException("Pytest session failed"));
            if (isAlreadyReportedTestFailure(testListener, throwable)) {
                return;
            }
            throw new IllegalStateException(FailureDiagnostics.render(throwable), throwable);
        }
        int exitCode = pytestExitCode(result);
        if (exitCode != 0) {
            if (isAlreadyReportedTestFailure(testListener, exitCode)) {
                return;
            }
            throw new IllegalStateException(PYTEST_SESSION_FAILED_PREFIX + exitCode);
        }
    }

    private static boolean isAlreadyReportedTestFailure(JUnitPytestTestListener testListener, Throwable throwable) {
        return testListener.hasReportedTestFailures()
            && !testListener.hasNonTestFailures()
            && isGenericPytestExitFailure(throwable);
    }

    private static boolean isAlreadyReportedTestFailure(JUnitPytestTestListener testListener, int exitCode) {
        return exitCode == PYTEST_TESTS_FAILED_EXIT_CODE
            && testListener.hasReportedTestFailures()
            && !testListener.hasNonTestFailures();
    }

    private static boolean isGenericPytestExitFailure(Throwable throwable) {
        String message = throwable.getMessage();
        return message != null && message.startsWith(PYTEST_SESSION_FAILED_PREFIX);
    }

    static int pytestExitCode(Value result) {
        if (result == null || result.isNull()) {
            return 0;
        }
        if (result.fitsInInt()) {
            return result.asInt();
        }
        if (result.hasMember("value")) {
            Value value = result.getMember("value");
            if (value != null && value.fitsInInt()) {
                return value.asInt();
            }
        }
        try {
            return Integer.parseInt(result.toString());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}

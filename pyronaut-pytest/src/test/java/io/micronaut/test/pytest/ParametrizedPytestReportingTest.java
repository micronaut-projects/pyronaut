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
package io.micronaut.test.pytest;

import io.micronaut.context.python.GraalPyContextFactory;
import io.micronaut.context.python.PythonContextRuntime;
import io.micronaut.test.pytest.execution.JUnitReportWriter;
import io.micronaut.test.pytest.execution.JUnitPytestTestListener;
import io.micronaut.test.pytest.execution.PytestTestExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.EngineDiscoveryRequest;
import org.junit.platform.engine.EngineExecutionListener;
import org.junit.platform.engine.ExecutionRequest;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestEngine;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.EngineDescriptor;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.engine.reporting.ReportEntry;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ParametrizedPytestReportingTest {
    @TempDir
    Path tempDir;

    @Test
    void namedParametersKeepDistinctJUnitIdentitiesAndMixedOutcomes() throws Exception {
        Path reports = tempDir.resolve("reports");
        var request = LauncherDiscoveryRequestBuilder.request().build();
        var launcher = LauncherFactory.create(LauncherConfig.builder()
            .enableTestEngineAutoRegistration(false)
            .addTestEngines(new ParameterCallbacksEngine(reports))
            .build());
        var summaryListener = new SummaryGeneratingListener();
        List<JUnitReportWriter.TestResult> results = new ArrayList<>();
        Set<String> uniqueIds = new LinkedHashSet<>();
        launcher.registerTestExecutionListeners(summaryListener, new TestExecutionListener() {
            @Override
            public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
                if (identifier.isTest()) {
                    uniqueIds.add(identifier.getUniqueId());
                    JUnitReportWriter.Status status = switch (result.getStatus()) {
                        case SUCCESSFUL -> JUnitReportWriter.Status.PASSED;
                        case FAILED -> JUnitReportWriter.Status.FAILED;
                        case ABORTED -> JUnitReportWriter.Status.SKIPPED;
                    };
                    results.add(new JUnitReportWriter.TestResult(identifier.getDisplayName(), status,
                        result.getThrowable().map(Throwable::toString).orElse(""), "", ""));
                }
            }
        });

        launcher.execute(request);
        var summary = summaryListener.getSummary();
        JUnitReportWriter.write(reports, summary, results);
        var xml = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(reports.resolve("junit.xml").toFile());
        String events = Files.readString(reports.resolve("events.ndjson"));

        assertAll(
            () -> assertEquals(2, events.lines().filter(line -> line.contains("\"eventType\":\"test_finished\"")).count()),
            () -> assertEquals(2, uniqueIds.size(), "each collected pytest case needs its own JUnit identity"),
            () -> assertEquals(List.of("test_parameters.py::test_value[passing]", "test_parameters.py::test_value[failing]"),
                results.stream().map(JUnitReportWriter.TestResult::name).toList()),
            () -> assertEquals(2, summary.getTestsFoundCount()),
            () -> assertEquals(1, summary.getTestsSucceededCount()),
            () -> assertEquals(1, summary.getTestsFailedCount()),
            () -> assertEquals(0, summary.getTestsSkippedCount()),
            () -> assertEquals("2", xml.getDocumentElement().getAttribute("tests")),
            () -> assertEquals("1", xml.getDocumentElement().getAttribute("failures")),
            () -> assertEquals("0", xml.getDocumentElement().getAttribute("errors")),
            () -> assertEquals(2, xml.getElementsByTagName("testcase").getLength()),
            () -> assertEquals(1, xml.getElementsByTagName("failure").getLength())
        );
    }

    @Test
    void ordinaryClassSkippedAndWindowsCasesKeepRuntimeIdentityAndSource() throws Exception {
        var cases = List.of(
            new CallbackCase("tests/test_plain.py", "test_plain", "tests/test_plain.py::test_plain", TestExecutionResult.successful()),
            new CallbackCase("tests/test_class.py", "TestValues::test_value", "tests/test_class.py::TestValues::test_value", TestExecutionResult.successful()),
            new CallbackCase("tests/test_skip.py", "test_skip", "tests/test_skip.py::test_skip[disabled]", TestExecutionResult.aborted(new RuntimeException("disabled"))),
            new CallbackCase("C:/tests/test_windows.py", "test_value", "C:\\tests\\test_windows.py::test_value[one]", TestExecutionResult.successful())
        );
        var launcher = LauncherFactory.create(LauncherConfig.builder().enableTestEngineAutoRegistration(false)
            .addTestEngines(new ParameterCallbacksEngine(tempDir, cases)).build());
        var summary = new SummaryGeneratingListener();
        List<TestIdentifier> started = new ArrayList<>();
        List<TestIdentifier> finished = new ArrayList<>();
        List<TestIdentifier> output = new ArrayList<>();
        launcher.registerTestExecutionListeners(summary, new TestExecutionListener() {
            @Override
            public void executionStarted(TestIdentifier identifier) {
                if (identifier.isTest()) {
                    started.add(identifier);
                }
            }

            @Override
            public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
                if (identifier.isTest()) {
                    finished.add(identifier);
                }
            }

            @Override
            public void reportingEntryPublished(TestIdentifier identifier, ReportEntry entry) {
                output.add(identifier);
            }
        });
        launcher.execute(LauncherDiscoveryRequestBuilder.request().build());
        assertAll(
            () -> assertEquals(4, summary.getSummary().getTestsFoundCount()),
            () -> assertEquals(3, summary.getSummary().getTestsSucceededCount()),
            () -> assertEquals(1, summary.getSummary().getTestsAbortedCount()),
            () -> assertEquals(0, summary.getSummary().getTestsFailedCount()),
            () -> assertEquals(started, finished),
            () -> assertEquals(started, output),
            () -> assertEquals(cases.stream().map(CallbackCase::nodeId).toList(), finished.stream().map(TestIdentifier::getDisplayName).toList()),
            () -> assertTrue(finished.stream().allMatch(identifier -> identifier.getSource().isPresent())),
            () -> assertTrue(finished.get(3).getUniqueIdObject().getLastSegment().getValue().contains("C:/tests/test_windows.py"))
        );
    }

    private record CallbackCase(String file, String function, String nodeId, TestExecutionResult result) {
    }

    @Test
    void parameterBackslashesAreNotNormalizedIntoAnotherCaseIdentity() throws Exception {
        var cases = List.of(
            new CallbackCase("test_paths.py", "test_path", "test_paths.py::test_path[x\\y]", TestExecutionResult.successful()),
            new CallbackCase("test_paths.py", "test_path", "test_paths.py::test_path[x/y]", TestExecutionResult.successful()));
        var launcher = LauncherFactory.create(LauncherConfig.builder().enableTestEngineAutoRegistration(false)
            .addTestEngines(new ParameterCallbacksEngine(tempDir, cases)).build());
        var summary = new SummaryGeneratingListener();
        List<JUnitReportWriter.TestResult> results = new ArrayList<>();
        Set<String> identities = new LinkedHashSet<>();
        launcher.registerTestExecutionListeners(summary, new TestExecutionListener() {
            @Override
            public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
                if (identifier.isTest()) {
                    identities.add(identifier.getUniqueId());
                    results.add(new JUnitReportWriter.TestResult(identifier.getDisplayName(), JUnitReportWriter.Status.PASSED, "", "", ""));
                }
            }
        });
        launcher.execute(LauncherDiscoveryRequestBuilder.request().build());
        JUnitReportWriter.write(tempDir, summary.getSummary(), results);
        var xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(tempDir.resolve("junit.xml").toFile());
        assertAll(
            () -> assertEquals(2, identities.size()),
            () -> assertEquals(cases.stream().map(CallbackCase::nodeId).toList(), results.stream().map(JUnitReportWriter.TestResult::name).toList()),
            () -> assertEquals("2", xml.getDocumentElement().getAttribute("tests")),
            () -> assertEquals(2, xml.getElementsByTagName("testcase").getLength())
        );
    }

    @Test
    void actualPytestNamedParametersReachDistinctRuntimeCases() throws Exception {
        Path file = tempDir.resolve("test_parameters.py");
        Files.writeString(file, """
            import pytest

            @pytest.mark.parametrize("actual, expected", [(1, 1), (2, 3)], ids=["passing", "failing"])
            def test_value(actual, expected):
                assert actual == expected
            """);
        var source = new PytestTestDescriptor(UniqueId.forEngine("pytest-engine")
            .append("source", file.getFileName().toString()).append("test", "test_value"),
            file + "::test_value", MethodSource.from(file.toString(), "test_value"), file, 4, 5, 0, 28);
        List<TestDescriptor> finished = new ArrayList<>();
        List<TestExecutionResult.Status> statuses = new ArrayList<>();
        PythonContextRuntime.setReuseContext(false);
        PythonContextRuntime.resetContext();
        try (var context = GraalPyContextFactory.bootstrapReusableContext(getClass().getClassLoader())) {
            try {
                var listener = new JUnitPytestTestListener(new EngineExecutionListener() {
                    @Override
                    public void executionFinished(TestDescriptor descriptor, TestExecutionResult result) {
                        if (descriptor.isTest()) {
                            finished.add(descriptor);
                            statuses.add(result.getStatus());
                        }
                    }
                }, Set.of(source));
                var runner = context.eval("python", "from pyronaut.test import run_pytest\nrun_pytest");
                assertEquals(1, runner.execute(new String[]{file.toString()}, listener,
                    tempDir.resolve("pytest-junit.xml").toString()).asInt());
                listener.finishSources(TestExecutionResult.successful());
                assertAll(
                    () -> assertEquals(2, finished.size()),
                    () -> assertEquals(2, finished.stream().map(TestDescriptor::getUniqueId).distinct().count()),
                    () -> assertTrue(finished.get(0).getDisplayName().endsWith("::test_value[passing]")),
                    () -> assertTrue(finished.get(1).getDisplayName().endsWith("::test_value[failing]")),
                    () -> assertEquals(List.of(TestExecutionResult.Status.SUCCESSFUL, TestExecutionResult.Status.FAILED), statuses),
                    () -> assertTrue(finished.stream().allMatch(descriptor -> descriptor.getSource().equals(source.getSource()))),
                    () -> assertEquals(2, source.getChildren().size())
                );
            } finally {
                PythonContextRuntime.resetContext();
            }
        }
    }

    @Test
    void executorCompletesSourcesOnceAfterPartialCallbacksAndSessionErrors() throws Exception {
        PythonContextRuntime.setReuseContext(false);
        PythonContextRuntime.resetContext();
        try (var context = GraalPyContextFactory.bootstrapReusableContext(getClass().getClassLoader())) {
            try {
                context.eval("python", """
                    import pyronaut.test

                    def controlled_run(args, listener, junit_xml):
                        if partial_callbacks:
                            listener.beforeTest("test_control.py::test_value[one]", None)
                            listener.afterTest("test_control.py::test_value[one]", None, listener.successfulResult())
                        listener.onResult(listener.failedResult("Pytest session failed with exit code: 2"))
                        return 2

                    pyronaut.test.run_pytest = controlled_run
                    """);
                for (String mode : List.of("source", "file", "engine", "empty-source")) {
                    UniqueId id = UniqueId.forEngine("pytest-engine");
                    var source = new PytestTestDescriptor(id.append("source", "test_control.py").append("test", "test_value"),
                        "test_control.py::test_value", null, Path.of("test_control.py"), 1, 1, 0, 0);
                    var empty = new PytestTestDescriptor(id.append("source", "test_control.py").append("test", "test_uncollected"),
                        "test_control.py::test_uncollected", null, Path.of("test_control.py"), 1, 1, 0, 0);
                    TestDescriptor root = switch (mode) {
                        case "source", "empty-source" -> source;
                        case "file" -> new PytestFileDescriptor(id.append("source", "test_control.py"), "test_control.py", null);
                        default -> new EngineDescriptor(id, "control");
                    };
                    if (root != source) {
                        root.addChild(source);
                        root.addChild(empty);
                    }
                    Map<UniqueId, Integer> starts = new HashMap<>();
                    Map<UniqueId, Integer> finishes = new HashMap<>();
                    Map<UniqueId, TestExecutionResult.Status> statuses = new HashMap<>();
                    EngineExecutionListener events = new EngineExecutionListener() {
                        @Override
                        public void executionStarted(TestDescriptor descriptor) {
                            starts.merge(descriptor.getUniqueId(), 1, Integer::sum);
                        }

                        @Override
                        public void executionFinished(TestDescriptor descriptor, TestExecutionResult result) {
                            finishes.merge(descriptor.getUniqueId(), 1, Integer::sum);
                            statuses.put(descriptor.getUniqueId(), result.getStatus());
                        }
                    };
                    context.getBindings("python").putMember("partial_callbacks", !mode.equals("empty-source"));
                    var executor = new PytestTestExecutor(context, events, tempDir.resolve(mode + ".xml").toString(),
                        tempDir.resolve(mode + ".html").toString(), tempDir.resolve(mode + ".nodeids").toString(),
                        tempDir.resolve(mode + ".ndjson").toString());
                    executor.execute(root);
                    assertAll(mode,
                        () -> assertEquals(starts, finishes),
                        () -> assertTrue(starts.values().stream().allMatch(count -> count == 1)),
                        () -> assertEquals(1, starts.get(source.getUniqueId())),
                        () -> assertEquals(TestExecutionResult.Status.FAILED, statuses.get(source.getUniqueId())),
                        () -> assertEquals(mode.equals("empty-source") ? 0 : 1, source.getChildren().size()),
                        () -> assertEquals(mode.equals("source") || mode.equals("empty-source") ? null : 1, starts.get(empty.getUniqueId()))
                    );
                }
            } finally {
                PythonContextRuntime.resetContext();
            }
        }
    }

    @Test
    void ownUniqueIdRerunsFailClearlyWhileForeignAndSourceSelectorsRemainAccepted() {
        for (String engine : List.of(PytestTestEngine.ENGINE_ID, "pytest-engine")) {
            var request = LauncherDiscoveryRequestBuilder.request().selectors(DiscoverySelectors.selectUniqueId(
                UniqueId.forEngine(engine).append("source", "test_parameters.py")
                    .append("test", "test_value").append("invocation", "test_parameters.py::test_value[passing]"))).build();
            var error = assertThrows(IllegalArgumentException.class,
                () -> PytestTestEngine.rejectUnsupportedUniqueIdSelectors(request));
            assertTrue(error.getMessage().contains("Select a Python file or directory instead"));
        }
        assertDoesNotThrow(() -> PytestTestEngine.rejectUnsupportedUniqueIdSelectors(
            LauncherDiscoveryRequestBuilder.request().selectors(
                DiscoverySelectors.selectUniqueId(UniqueId.forEngine("junit-jupiter")),
                DiscoverySelectors.selectFile("test_parameters.py"),
                DiscoverySelectors.selectDirectory("tests")).build()));
    }

    /** Exercises the production pytest callback adapter through a real JUnit launcher without Python discovery. */
    private static final class ParameterCallbacksEngine implements TestEngine {
        private final Path reports;
        private final List<CallbackCase> cases;

        private ParameterCallbacksEngine(Path reports) {
            this(reports, List.of(
                new CallbackCase("test_parameters.py", "test_value", "test_parameters.py::test_value[passing]", TestExecutionResult.successful()),
                new CallbackCase("test_parameters.py", "test_value", "test_parameters.py::test_value[failing]", TestExecutionResult.failed(new PythonAssertionError("assert 2 == 3")))
            ));
        }

        private ParameterCallbacksEngine(Path reports, List<CallbackCase> cases) {
            this.reports = reports;
            this.cases = cases;
        }

        @Override
        public String getId() {
            return "pytest-report-control";
        }

        @Override
        public TestDescriptor discover(EngineDiscoveryRequest request, UniqueId uniqueId) {
            var engine = new EngineDescriptor(uniqueId, "Pytest callback control");
            for (CallbackCase test : cases) {
                engine.addChild(new PytestTestDescriptor(
                    uniqueId.append(PytestTestDescriptor.SEGMENT_SOURCE, test.file())
                        .append(PytestTestDescriptor.SEGMENT_TEST, test.function()),
                    test.file() + "::" + test.function(),
                    MethodSource.from(test.file(), test.function()),
                    Path.of(test.file()), 1, 1, 0, 0));
            }
            return engine;
        }

        @Override
        public void execute(ExecutionRequest request) {
            var root = request.getRootTestDescriptor();
            var engineListener = request.getEngineExecutionListener();
            engineListener.executionStarted(root);
            var listener = new JUnitPytestTestListener(engineListener, root.getChildren(),
                reports.resolve("index.html").toString(), reports.resolve("nodeids.txt").toString(),
                reports.resolve("events.ndjson").toString());
            for (CallbackCase test : cases) {
                listener.beforeTest(test.nodeId(), null);
                listener.onOutput(test.nodeId(), "stdout", "case output");
                listener.afterTest(test.nodeId(), null, test.result());
            }
            listener.onResult(cases.stream().anyMatch(test -> test.result().getStatus() == TestExecutionResult.Status.FAILED)
                ? TestExecutionResult.failed(new PythonAssertionError("one case failed"))
                : TestExecutionResult.successful());
            listener.finishSources(TestExecutionResult.successful());
            engineListener.executionFinished(root, TestExecutionResult.successful());
        }
    }
}

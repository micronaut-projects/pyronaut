package io.micronaut.pyronaut.dev;

import io.micronaut.dev.test.TestFailure;
import io.micronaut.dev.test.TestId;
import io.micronaut.dev.test.TestOutcome;
import io.micronaut.dev.test.TestOutput;
import io.micronaut.dev.test.TestRunStarted;
import io.micronaut.dev.test.TestRunSummary;
import io.micronaut.dev.test.TestSelection;
import io.micronaut.dev.test.TestStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PyronautTestEventsListenerTest {

    @TempDir
    Path reports;

    // the pytest engine names a test's class by its file's absolute path
    private static final TestId PYTEST = new TestId(
        "[engine:pyronaut-pytest]/[source:test_app.py]/[test:test_hello]", "/work/demo/tests/test_app.py", "test_hello", "test_app.py::test_hello");
    private static final TestId JUNIT = new TestId(
        "[engine:junit-jupiter]/[class:com.example.AppTest]/[method:hello()]", "com.example.AppTest", "hello()", "hello()");

    @Test
    void everyRunWritesTheEventsOfEveryEngineUnderARunOfItsOwn() throws Exception {
        Path file = reports.resolve("events.ndjson");
        Iterator<String> runIds = List.of("first", "second").iterator();
        PyronautTestEventsListener listener = new PyronautTestEventsListener(file, Path.of("/work/demo"), "tests", runIds::next);

        run(listener, "run-1");
        List<String> first = Files.readAllLines(file);
        assertEquals(8, first.size());
        assertTrue(first.getFirst().startsWith("{\"runId\":\"first\",\"seq\":1,\"eventType\":\"session_started\",\"testId\":null,\"status\":null,"));
        assertTrue(first.get(1).contains("\"eventType\":\"test_started\",\"testId\":\"tests/test_app.py::test_hello\",\"status\":null"));
        assertTrue(first.get(2).contains("\"eventType\":\"test_output\",\"testId\":\"tests/test_app.py::test_hello\""));
        assertTrue(first.get(2).endsWith("\"payload\":{\"stream\":\"stdout\",\"text\":\"hello \\\"world\\\"\\n\"}}"));
        assertTrue(first.get(3).contains("\"eventType\":\"test_finished\",\"testId\":\"tests/test_app.py::test_hello\",\"status\":\"FAILED\""));
        assertTrue(first.get(3).contains("\"failure\":\"AssertionError: assert 'gamma' == 'alpha'\\n\\tat test_app.py:5\""));
        assertTrue(first.get(4).contains("\"eventType\":\"test_started\",\"testId\":\"com.example.AppTest::hello()\""));
        assertTrue(first.get(5).contains("\"eventType\":\"test_finished\",\"testId\":\"com.example.AppTest::hello()\",\"status\":\"SUCCESSFUL\""));
        assertTrue(first.get(6).contains("\"eventType\":\"test_finished\",\"testId\":\"com.example.AppTest::skipped()\",\"status\":\"ABORTED\""));
        assertTrue(first.get(6).contains("\"reason\":\"disabled\""));
        assertTrue(first.get(7).contains("\"eventType\":\"session_finished\",\"testId\":null,\"status\":\"FAILED\""));
        assertTrue(first.get(7).endsWith("\"payload\":{\"total\":\"3\",\"passed\":\"1\",\"failed\":\"1\",\"skipped\":\"1\"}}"));
        assertTrue(first.get(7).startsWith("{\"runId\":\"first\",\"seq\":8,"));

        // the next run starts the file again, under a run of its own
        run(listener, "run-2");
        List<String> second = Files.readAllLines(file);
        assertEquals(8, second.size());
        assertTrue(second.getFirst().startsWith("{\"runId\":\"second\",\"seq\":1,\"eventType\":\"session_started\""));
        assertFalse(second.stream().anyMatch(line -> line.contains("\"first\"")));
    }

    @Test
    void writesNothingUnlessTheLauncherNamedTheFile() {
        PyronautTestEventsListener listener = new PyronautTestEventsListener(null, null, "", () -> "run");
        run(listener, "run-1");
        assertEquals("com.example.AppTest::hello()", listener.testId(JUNIT));
    }

    @Test
    void aPytestTestGroupedUnderItsFileRelativeToTheTestsIsNamedFromTheProject() {
        PyronautTestEventsListener listener = new PyronautTestEventsListener(null, Path.of("/work/demo"), "tests", () -> "run");
        TestId relative = new TestId("[engine:pyronaut-pytest]/[source:api/test_app.py]/[test:TestApi::test_get]",
            "api/test_app.py", "test_get", "test_get");
        assertEquals("tests/api/test_app.py::TestApi::test_get", listener.testId(relative));
        assertEquals("tests/test_app.py::test_hello", listener.testId(PYTEST));
    }

    private static void run(PyronautTestEventsListener listener, String runId) {
        listener.runStarted(new TestRunStarted(runId, PyronautTestRunner.ID, TestSelection.all(), Instant.now()));
        listener.testStarted(PYTEST);
        listener.output(PYTEST, TestOutput.STDOUT, "hello \"world\"\n");
        listener.testFinished(PYTEST, new TestOutcome(TestStatus.FAILED, Duration.ofMillis(5),
            new TestFailure("AssertionError", "assert 'gamma' == 'alpha'", "AssertionError: assert 'gamma' == 'alpha'\n\tat test_app.py:5"), null));
        listener.testStarted(JUNIT);
        listener.testFinished(JUNIT, new TestOutcome(TestStatus.PASSED, Duration.ofMillis(1), null, null));
        TestId skipped = new TestId("[engine:junit-jupiter]/[class:com.example.AppTest]/[method:skipped()]", "com.example.AppTest", "skipped()", "skipped()");
        listener.testFinished(skipped, new TestOutcome(TestStatus.SKIPPED, Duration.ZERO, null, "disabled"));
        listener.runFinished(new TestRunSummary(runId, 1, 1, 0, 1, Duration.ofMillis(10), false, true));
    }
}

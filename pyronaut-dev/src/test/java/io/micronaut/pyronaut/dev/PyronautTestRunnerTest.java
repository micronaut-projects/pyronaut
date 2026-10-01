package io.micronaut.pyronaut.dev;

import io.micronaut.dev.test.Cancellation;
import io.micronaut.dev.test.TestEventListener;
import io.micronaut.dev.test.TestId;
import io.micronaut.dev.test.TestOutcome;
import io.micronaut.dev.test.TestRunRequest;
import io.micronaut.dev.test.TestRunStarted;
import io.micronaut.dev.test.TestRunSummary;
import io.micronaut.dev.test.TestRunner;
import io.micronaut.dev.test.TestSelection;
import io.micronaut.dev.test.TestStatus;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class PyronautTestRunnerTest {

    private final ClassLoader generation = new URLClassLoader("generation-2", new URL[0], getClass().getClassLoader());
    private final TestRunRequest request = new TestRunRequest("run-2", generation, List.of(), List.of(), TestSelection.all(), Map.of());

    @Test
    void theGraalPyContextOfARunIsBuiltOverItsGenerationBeforeThePlatformRuns() {
        List<ClassLoader> contexts = new ArrayList<>();
        TestRunSummary passed = new TestRunSummary("run-2", 1, 0, 0, 0, Duration.ZERO, false, true);
        TestRunner platform = new TestRunner() {
            @Override
            public String id() {
                return "junit-platform";
            }

            @Override
            public TestRunSummary run(TestRunRequest request, TestEventListener listener, Cancellation cancellation) {
                // built before any test runs
                assertEquals(List.of(generation), contexts);
                return passed;
            }
        };
        PyronautTestRunner runner = new PyronautTestRunner(platform, loader -> {
            contexts.add(loader);
            // what the context creates as it is built takes the parent tier along as its context loader, not the generation
            assertSame(PyronautTestRunner.class.getClassLoader(), Thread.currentThread().getContextClassLoader());
            return null;
        });

        assertSame(passed, runner.run(request, new TestEventListener() { }, new Cancellation()));
        assertEquals(PyronautTestRunner.ID, runner.id());
    }

    @Test
    void aContextThatCannotBeBuiltIsAnErrorOfTheRun() {
        List<String> events = new ArrayList<>();
        TestRunner platform = new TestRunner() {
            @Override
            public String id() {
                return "junit-platform";
            }

            @Override
            public TestRunSummary run(TestRunRequest request, TestEventListener listener, Cancellation cancellation) {
                throw new AssertionError("no test runs without a context");
            }
        };
        PyronautTestRunner runner = new PyronautTestRunner(platform, loader -> {
            throw new IllegalStateException("Failed to initialize GraalPy context");
        });

        TestRunSummary summary = runner.run(request, new TestEventListener() {
            @Override
            public void runStarted(TestRunStarted event) {
                events.add("started " + event.runId());
            }

            @Override
            public void testFinished(TestId test, TestOutcome outcome) {
                events.add(outcome.status() + " " + outcome.failure().message());
            }

            @Override
            public void runFinished(TestRunSummary summary) {
                events.add("finished");
            }
        }, new Cancellation());

        assertEquals(List.of("started run-2", TestStatus.ERRORED + " Failed to initialize GraalPy context", "finished"), events);
        assertEquals(1, summary.errored());
        assertFalse(summary.isSuccess());
    }
}

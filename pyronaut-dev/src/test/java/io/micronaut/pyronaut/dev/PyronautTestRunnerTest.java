package io.micronaut.pyronaut.dev;

import io.micronaut.context.reload.InPlaceResourceReloader;
import io.micronaut.dev.compile.SourceKind;
import io.micronaut.dev.compile.SourceRoot;
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
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void theContextOfAGenerationLivesOnAcrossItsRunsTakesPatchesAndImportsTheTestsAgain() throws Exception {
        ClassLoader next = new URLClassLoader("generation-3", new URL[0], getClass().getClassLoader());
        List<Path> roots = List.of(Path.of("tests").toAbsolutePath());
        TestRunRequest first = new TestRunRequest("run-2", generation, List.of(), List.of(new SourceRoot(SourceKind.PYTHON, roots.get(0))), TestSelection.all(), Map.of());
        TestRunRequest again = new TestRunRequest("run-3", generation, List.of(), first.testSources(), TestSelection.all(), Map.of());
        TestRunRequest onNext = new TestRunRequest("run-4", next, List.of(), first.testSources(), TestSelection.all(), Map.of());
        List<ClassLoader> built = new ArrayList<>();
        List<Context> contexts = new ArrayList<>();
        List<List<Path>> purged = new ArrayList<>();
        List<Set<String>> patched = new ArrayList<>();
        InPlaceResourceReloader python = new InPlaceResourceReloader() {
            @Override
            public boolean canReload(Set<String> changedResources, Set<String> removedResources) {
                return removedResources.isEmpty();
            }

            @Override
            public Result reload(Set<String> changedResources) {
                patched.add(changedResources);
                return new Result(changedResources.size(), "Python module(s)");
            }
        };
        TestRunner platform = new TestRunner() {
            @Override
            public String id() {
                return "junit-platform";
            }

            @Override
            public TestRunSummary run(TestRunRequest request, TestEventListener listener, Cancellation cancellation) {
                return new TestRunSummary(request.runId(), 1, 0, 0, 0, Duration.ZERO, false, true);
            }
        };
        PyronautTestRunner runner = new PyronautTestRunner(platform, loader -> {
            built.add(loader);
            Context context = Context.create("python");
            contexts.add(context);
            return context;
        }, () -> python, (context, testRoots) -> purged.add(testRoots));
        try {
            runner.run(first, new TestEventListener() { }, new Cancellation());
            assertEquals(List.of(generation), built);
            assertTrue(purged.isEmpty(), "a new context has imported no test");

            // only the generation the context was built over takes a patch, which goes to the reloader of the runtime
            assertTrue(runner.inPlaceReloader(next).isEmpty());
            InPlaceResourceReloader reloader = runner.inPlaceReloader(generation).orElseThrow();
            String module = "META-INF/GRAALPY-VFS/micronaut-application/src/demo/hello.py";
            assertTrue(reloader.canReload(Set.of(module), Set.of()));
            assertEquals(1, reloader.reload(Set.of(module)).count());
            assertEquals(List.of(Set.of(module)), patched);

            // the next run on that generation keeps the context, and imports the test modules again
            runner.run(again, new TestEventListener() { }, new Cancellation());
            assertEquals(List.of(generation), built);
            assertEquals(List.of(roots), purged);

            // a run on a new generation closes the context and builds one over it
            runner.run(onNext, new TestEventListener() { }, new Cancellation());
            assertEquals(List.of(generation, next), built);
            assertTrue(runner.inPlaceReloader(generation).isEmpty());
            assertTrue(runner.inPlaceReloader(next).isPresent());
            assertEquals(1, purged.size());
        } finally {
            runner.closeContext();
            for (Context context : contexts) {
                context.close(true);
            }
        }
    }

    @Test
    void theTestModulesOfTheTestRootsAreRemovedFromSysModules(@org.junit.jupiter.api.io.TempDir Path project) throws Exception {
        Path tests = java.nio.file.Files.createDirectories(project.resolve("tests"));
        java.nio.file.Files.writeString(tests.resolve("test_purged.py"), "value = 1\n");
        Path app = java.nio.file.Files.createDirectories(project.resolve("src"));
        java.nio.file.Files.writeString(app.resolve("kept_app.py"), "value = 1\n");
        TestRunRequest request = new TestRunRequest("run-2", generation, List.of(), List.of(new SourceRoot(SourceKind.PYTHON, tests)), TestSelection.all(), Map.of());
        List<Context> contexts = new ArrayList<>();
        TestRunner platform = new TestRunner() {
            @Override
            public String id() {
                return "junit-platform";
            }

            @Override
            public TestRunSummary run(TestRunRequest request, TestEventListener listener, Cancellation cancellation) {
                return new TestRunSummary(request.runId(), 1, 0, 0, 0, Duration.ZERO, false, true);
            }
        };
        PyronautTestRunner runner = new PyronautTestRunner(platform, loader -> {
            Context context = Context.newBuilder("python").allowAllAccess(true).build();
            contexts.add(context);
            context.eval("python", "import sys\nsys.path[0:0] = [" + pythonString(tests) + ", " + pythonString(app) + "]\nimport test_purged\nimport kept_app\n");
            return context;
        });
        try {
            runner.run(request, new TestEventListener() { }, new Cancellation());
            runner.run(request, new TestEventListener() { }, new Cancellation());
            Context context = contexts.get(0);
            assertFalse(context.eval("python", "'test_purged' in sys.modules").asBoolean());
            assertTrue(context.eval("python", "'kept_app' in sys.modules").asBoolean());
        } finally {
            runner.closeContext();
            for (Context context : contexts) {
                context.close(true);
            }
        }
    }

    private static String pythonString(Path path) {
        return "'" + path.toAbsolutePath().toString().replace("\\", "\\\\") + "'";
    }
}

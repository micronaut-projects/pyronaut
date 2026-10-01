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
package io.micronaut.pyronaut.dev;

import io.micronaut.context.python.GraalPyContextFactory;
import io.micronaut.context.python.PythonContextRuntime;
import io.micronaut.dev.test.Cancellation;
import io.micronaut.dev.test.JUnitPlatformTestRunner;
import io.micronaut.dev.test.TestEventListener;
import io.micronaut.dev.test.TestFailure;
import io.micronaut.dev.test.TestId;
import io.micronaut.dev.test.TestOutcome;
import io.micronaut.dev.test.TestRunRequest;
import io.micronaut.dev.test.TestRunStarted;
import io.micronaut.dev.test.TestRunSummary;
import io.micronaut.dev.test.TestRunner;
import io.micronaut.dev.test.TestStatus;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;

/**
 * Runs a project's tests in test mode on the JUnit Platform, as the test command does, with the GraalPy context
 * the test command bootstraps: one per run, built over the run's class loader generation, so that the Python
 * modules a test imports are the generation's, and the pytest engine and every application context a test starts
 * share it. The context of the run before is closed first, with the engine it was created with, which lets the
 * generation it held go.
 *
 * <p>Registered as the {@code pyronaut} runner, which {@link PyronautTestReload} names in its manifest.</p>
 */
public final class PyronautTestRunner implements TestRunner {

    /**
     * The runner's identifier.
     */
    public static final String ID = "pyronaut";

    private static final Logger LOG = LoggerFactory.getLogger(PyronautTestRunner.class);
    private static final String CONTEXT_ERROR = "initializationError";

    private final TestRunner platform;
    private final Function<ClassLoader, Context> bootstrap;
    private Context context;

    /**
     * Created by the service loader.
     */
    public PyronautTestRunner() {
        this(new JUnitPlatformTestRunner(), PyronautTestRunner::bootstrap);
    }

    PyronautTestRunner(TestRunner platform, Function<ClassLoader, Context> bootstrap) {
        this.platform = platform;
        this.bootstrap = bootstrap;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean isAvailable() {
        return platform.isAvailable();
    }

    @Override
    public TestRunSummary run(TestRunRequest request, TestEventListener listener, Cancellation cancellation) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        long start = System.nanoTime();
        try {
            // the context loads the generation's modules and classes through the loader it is given, while what it
            // creates as it is built, as the thread of the shutdown hook that deletes the files its virtual file system
            // extracted, takes the context loader along: the parent tier's, so that it pins no generation
            thread.setContextClassLoader(PyronautTestRunner.class.getClassLoader());
            closeContext();
            context = bootstrap.apply(request.classLoader());
            LOG.info("Created the GraalPy context of {} in {} ms", request.runId(), Duration.ofNanos(System.nanoTime() - start).toMillis());
        } catch (RuntimeException | LinkageError e) {
            return failed(request, listener, e, Duration.ofNanos(System.nanoTime() - start));
        } finally {
            thread.setContextClassLoader(previous);
        }
        return platform.run(request, listener, cancellation);
    }

    /**
     * Closes the context of the last run, with its engine: the runtime is closing.
     */
    synchronized void closeContext() {
        Context last = context;
        context = null;
        if (last == null) {
            return;
        }
        // reuse off uninstalls the runtime of the context, rather than reloading its modules
        PythonContextRuntime.setReuseContext(false);
        PythonContextRuntime.resetContext();
        Engine engine = null;
        try {
            engine = last.getEngine();
            last.close(true);
        } catch (RuntimeException e) {
            LOG.warn("Cannot close the GraalPy context of the last run: {}", e.getMessage(), e);
        }
        if (engine != null) {
            try {
                engine.close(true);
            } catch (RuntimeException e) {
                LOG.debug("Cannot close the GraalPy engine of the last run: {}", e.getMessage(), e);
            }
        }
    }

    private static Context bootstrap(ClassLoader generation) {
        String applicationMain = System.getProperty(PyronautTestReload.APPLICATION_MAIN_PROPERTY, GraalPyContextFactory.APPLICATION_MAIN);
        try {
            return GraalPyContextFactory.bootstrapReusableContext(generation, Map.of(), applicationMain);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * Reports a run whose GraalPy context could not be built: no test could run.
     */
    private static TestRunSummary failed(TestRunRequest request, TestEventListener listener, Throwable error, Duration duration) {
        listener.runStarted(new TestRunStarted(request.runId(), ID, request.selection(), Instant.now()));
        TestId test = new TestId(request.runId() + "/graalpy", ID, CONTEXT_ERROR, "The GraalPy context could not be created");
        listener.testStarted(test);
        listener.testFinished(test, new TestOutcome(TestStatus.ERRORED, duration, TestFailure.of(error), null));
        TestRunSummary summary = new TestRunSummary(request.runId(), 0, 0, 1, 0, duration, false, false);
        listener.runFinished(summary);
        return summary;
    }
}

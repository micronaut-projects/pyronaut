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
import io.micronaut.context.reload.InPlaceResourceReloader;
import io.micronaut.dev.compile.SourceRoot;
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
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Runs a project's tests in test mode on the JUnit Platform, as the test command does, with the GraalPy context
 * the test command bootstraps, built over the run's class loader generation, so that the Python modules a test
 * imports are the generation's, and the pytest engine and every application context a test starts share it.
 *
 * <p>The context lives as long as its generation. A run on a generation the context was not built over closes it,
 * with the engine it was created with, which lets the generation it held go, and builds one over the new
 * generation. A Python edit that changed only bodies keeps the generation: test mode asks this runner's
 * {@link #inPlaceReloader in-place reloader}, which patches the edited application modules into the live context
 * with context-python's reloader, keeping their module, class and function objects, and the tests run on the same
 * context again.</p>
 *
 * <p>The test modules are handled the other way: before a run on a context that ran tests before, every module
 * imported from a test source root is removed from {@code sys.modules}, and pytest imports it again from its file.
 * pytest imports a test file itself, by its path, and would otherwise collect the module it imported the last time,
 * whatever the file holds now; nothing outside a run refers to a test module, so importing it again is exact where
 * a merge could refuse, and an added or removed test is collected as it is.</p>
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
    private static final Source PURGE_TEST_MODULES = Source.newBuilder("python", """
        def _pyronaut_purge_test_modules(roots, kept):
            import importlib
            import os
            import sys

            def normalized(path):
                return os.path.normcase(os.path.realpath(str(path)))

            def under(path, root):
                return path == root or path.startswith(root.rstrip(os.sep) + os.sep)

            roots = [normalized(root) for root in roots]
            # what a test root may hold besides tests, as a root of "." does: the application's sources, whose modules
            # are patched in place, and the Python environment's
            kept = [normalized(root) for root in kept if root]
            kept += [normalized(prefix) for prefix in {sys.prefix, sys.base_prefix, os.environ.get("VIRTUAL_ENV", "")} if prefix]
            purged = []
            for name, module in list(sys.modules.items()):
                file = getattr(module, "__file__", None)
                if not isinstance(file, str):
                    continue
                try:
                    path = normalized(file)
                except (OSError, ValueError):
                    continue
                for root in roots:
                    if not under(path, root):
                        continue
                    # a kept root inside the test root keeps its modules; one holding the test root keeps nothing
                    if any(under(keep, root) and keep != root and under(path, keep) for keep in kept):
                        continue
                    if "site-packages" in path.split(os.sep):
                        continue
                    del sys.modules[name]
                    purged.append(name)
                    break
            importlib.invalidate_caches()
            return purged
        _pyronaut_purge_test_modules
        """, "pyronaut-purge-test-modules.py").buildLiteral();

    private final TestRunner platform;
    private final Function<ClassLoader, Context> bootstrap;
    private final Supplier<InPlaceResourceReloader> reloader;
    private final BiConsumer<Context, List<Path>> purge;
    // guarded by this: the context of the last run and the generation it was built over
    private Context context;
    private ClassLoader contextLoader;

    /**
     * Created by the service loader.
     */
    public PyronautTestRunner() {
        this(new JUnitPlatformTestRunner(), PyronautTestRunner::bootstrap, PythonContextRuntime::inPlaceReloader, PyronautTestRunner::purgeTestModules);
    }

    PyronautTestRunner(TestRunner platform, Function<ClassLoader, Context> bootstrap) {
        this(platform, bootstrap, PythonContextRuntime::inPlaceReloader, PyronautTestRunner::purgeTestModules);
    }

    PyronautTestRunner(TestRunner platform,
                       Function<ClassLoader, Context> bootstrap,
                       Supplier<InPlaceResourceReloader> reloader,
                       BiConsumer<Context, List<Path>> purge) {
        this.platform = platform;
        this.bootstrap = bootstrap;
        this.reloader = reloader;
        this.purge = purge;
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
            synchronized (this) {
                if (contextLoader == request.classLoader()) {
                    // the generation the context was built over, an edit patched into it: the tests import again
                    if (context != null) {
                        purge.accept(context, testRoots(request));
                    }
                    LOG.info("Reused the GraalPy context {} for {} in {} ms", contextId(), request.runId(), Duration.ofNanos(System.nanoTime() - start).toMillis());
                } else {
                    closeContext();
                    context = bootstrap.apply(request.classLoader());
                    contextLoader = request.classLoader();
                    LOG.info("Created the GraalPy context {} of {} in {} ms", contextId(), request.runId(), Duration.ofNanos(System.nanoTime() - start).toMillis());
                }
            }
        } catch (RuntimeException | LinkageError e) {
            return failed(request, listener, e, Duration.ofNanos(System.nanoTime() - start));
        } finally {
            thread.setContextClassLoader(previous);
        }
        return platform.run(request, listener, cancellation);
    }

    /**
     * The reloader of the context built over the given generation: context-python's, which patches the edited
     * application modules into the contexts of the installed runtime, the reused one this runner built. An edited test
     * module needs no patch: it is imported again before the next run.
     *
     * @param classLoader The loader of the last run
     * @return The reloader, empty when the context of the last run was not built over that loader
     */
    @Override
    public synchronized Optional<InPlaceResourceReloader> inPlaceReloader(ClassLoader classLoader) {
        if (context == null || contextLoader != classLoader) {
            return Optional.empty();
        }
        InPlaceResourceReloader python = reloader.get();
        return Optional.of(new InPlaceResourceReloader() {
            @Override
            public boolean canReload(Set<String> changedResources, Set<String> removedResources) {
                return python.canReload(changedResources, removedResources);
            }

            @Override
            public Result reload(Set<String> changedResources) throws Exception {
                synchronized (PyronautTestRunner.this) {
                    if (contextLoader != classLoader || context == null) {
                        throw new IllegalStateException("The GraalPy context of the generation is closed");
                    }
                    return python.reload(changedResources);
                }
            }

            @Override
            public int getOrder() {
                return python.getOrder();
            }
        });
    }

    /**
     * Closes the context of the last run, with its engine: the runtime is closing, or the next run is on a new
     * generation.
     */
    synchronized void closeContext() {
        Context last = context;
        context = null;
        contextLoader = null;
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

    private String contextId() {
        return context == null ? "-" : Integer.toHexString(System.identityHashCode(context));
    }

    private static List<Path> testRoots(TestRunRequest request) {
        return request.testSources().stream().map(SourceRoot::path).toList();
    }

    /**
     * Removes from {@code sys.modules} the modules imported from the test source roots, which pytest imports by path,
     * but not those of the application's sources or the Python environment a test root may hold.
     */
    private static void purgeTestModules(Context context, List<Path> roots) {
        if (roots.isEmpty()) {
            return;
        }
        String[] kept = {System.getProperty(PyronautTestReload.PYTHON_SOURCES_PROPERTY, "")};
        Value purged = context.eval(PURGE_TEST_MODULES).execute(roots.stream().map(Path::toString).toArray(String[]::new), kept);
        if (LOG.isDebugEnabled()) {
            LOG.debug("Removed the test modules {} to import them again", purged);
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

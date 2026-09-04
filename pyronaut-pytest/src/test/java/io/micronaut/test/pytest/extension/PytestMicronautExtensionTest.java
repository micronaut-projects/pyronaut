package io.micronaut.test.pytest.extension;

import io.micronaut.context.python.GraalPyContextFactory;
import io.micronaut.context.python.PythonContextRuntime;
import io.micronaut.test.annotation.MicronautTestValue;
import io.micronaut.test.annotation.TransactionMode;
import io.micronaut.test.pytest.execution.JUnitPytestTestListener;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.EngineExecutionListener;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PytestMicronautExtensionTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanupPythonContextRuntime() {
        PythonContextRuntime.setReuseContext(false);
        PythonContextRuntime.resetContext();
        PythonContextRuntime.setReuseContext(false);
        PythonContextRuntime.resetContext();
    }

    @Test
    void createMicronautTestValueBuildsInteropSafeDefaults() {
        MicronautTestValue value = PytestMicronautExtension.createMicronautTestValue(
            new String[] {"foo"},
            new String[] {"example.pkg"},
            new String[] {"classpath:application-test.yml"},
            true,
            false,
            false,
            true,
            true
        );

        assertSame(void.class, value.application());
        assertArrayEquals(new String[] {"foo"}, value.environments());
        assertArrayEquals(new String[] {"example.pkg"}, value.packages());
        assertArrayEquals(new String[] {"classpath:application-test.yml"}, value.propertySources());
        assertTrue(value.rollback());
        assertFalse(value.transactional());
        assertFalse(value.rebuildContext());
        assertTrue(value.startApplication());
        assertTrue(value.isResolveParameters());
        assertSame(TransactionMode.SEPARATE_TRANSACTIONS, value.transactionMode());
        assertTrue(value.deduceEnvironment());
        assertArrayEquals(new Class<?>[] { PytestMicronautExtension.PytestApplicationContextBuilder.class }, value.contextBuilder());
    }

    @Test
    void fixtureFailureMessageIncludesRootCauseDiagnostics() {
        RuntimeException failure = new RuntimeException(
            "fixture setup failed",
            new IllegalStateException("root cause detail")
        );

        String message = PytestMicronautExtension.buildFailureMessage(failure);

        assertTrue(message.contains("java.lang.RuntimeException: fixture setup failed"));
        assertTrue(message.contains("java.lang.IllegalStateException: root cause detail"));
    }

    @Test
    void applicationContextClassLoaderPrefersGraalPyContextClassLoader() {
        ClassLoader threadClassLoader = new ClassLoader() {
        };
        ClassLoader applicationClassLoader = new ClassLoader() {
        };
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            PythonContextRuntime.setReuseContext(false);
            PythonContextRuntime.resetContext();
            thread.setContextClassLoader(threadClassLoader);
            PythonContextRuntime.setContext(null, applicationClassLoader);

            assertSame(applicationClassLoader, PytestMicronautExtension.resolveApplicationClassLoader());
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    void applicationContextClassLoaderFallsBackToThreadContextClassLoader() {
        ClassLoader threadClassLoader = new ClassLoader() {
        };
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            PythonContextRuntime.setReuseContext(false);
            PythonContextRuntime.resetContext();
            thread.setContextClassLoader(threadClassLoader);

            assertSame(threadClassLoader, PytestMicronautExtension.resolveApplicationClassLoader());
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    void invokeTestFunctionReportsUnderlyingJavaExceptionMessage() {
        try (Context context = Context.newBuilder("python")
            .allowHostAccess(HostAccess.ALL)
            .allowHostClassLookup(name -> true)
            .build()) {
            Value invocation = context.eval(
                "python",
                """
                import java

                def invoke():
                    raise java.type("java.lang.RuntimeException")("java side detail")
                invoke
                """
            );

            PytestMicronautExtension.TestFunctionResult result = PytestMicronautExtension.invokeTestFunction(invocation);

            assertNull(result.getReturnValue());
            assertNotNull(result.getError());
            assertTrue(result.getError().contains("java.lang.RuntimeException: java side detail"));
            assertFalse(result.getError().contains("ForeignException"));
            assertFalse(result.getError().contains("exceptions must be classes or instances deriving from BaseException"));
        }
    }

    @Test
    void invokeTestFunctionReportsHostExceptionThrownByJavaMethodFromMicronautContext() throws Exception {
        PythonContextRuntime.setReuseContext(false);
        PythonContextRuntime.resetContext();
        try (Context context = GraalPyContextFactory.bootstrapReusableContext(
            PytestMicronautExtensionTest.class.getClassLoader()
        )) {
            Value invocation = context.eval(
                "python",
                """
                import java

                ThrowingHost = java.type("io.micronaut.test.pytest.extension.PytestMicronautExtensionTest$ThrowingHost")

                def invoke():
                    ThrowingHost.fail()
                invoke
                """
            );

            PytestMicronautExtension.TestFunctionResult result = PytestMicronautExtension.invokeTestFunction(invocation);

            assertNull(result.getReturnValue());
            assertNotNull(result.getError());
            assertTrue(result.getError().contains("java.lang.IllegalStateException: host method detail"));
            assertFalse(result.getError().contains("ForeignException"));
            assertFalse(result.getError().contains("exceptions must be classes or instances deriving from BaseException"));
        } finally {
            PythonContextRuntime.setReuseContext(false);
            PythonContextRuntime.resetContext();
        }
    }

    @Test
    void invokeTestFunctionReportsExceptionInInitializerErrorCauseThrownByJavaMethod() throws Exception {
        PythonContextRuntime.setReuseContext(false);
        PythonContextRuntime.resetContext();
        try (Context context = GraalPyContextFactory.bootstrapReusableContext(
            PytestMicronautExtensionTest.class.getClassLoader()
        )) {
            Value invocation = context.eval(
                "python",
                """
                import java

                ThrowingHost = java.type("io.micronaut.test.pytest.extension.PytestMicronautExtensionTest$ThrowingHost")

                def invoke():
                    ThrowingHost.failWithInitializerError()
                invoke
                """
            );

            PytestMicronautExtension.TestFunctionResult result = PytestMicronautExtension.invokeTestFunction(invocation);

            assertNull(result.getReturnValue());
            assertNotNull(result.getError());
            assertTrue(result.getError().contains("java.lang.ExceptionInInitializerError"));
            assertTrue(result.getError().contains("java.lang.IllegalStateException: tls settings missing"));
            assertFalse(result.getError().contains("ForeignException"));
            assertFalse(result.getError().contains("exceptions must be classes or instances deriving from BaseException"));
        } finally {
            PythonContextRuntime.setReuseContext(false);
            PythonContextRuntime.resetContext();
        }
    }

    @Test
    void pytestRunReportsHostExceptionWithoutWrapperLeak() throws Exception {
        Path testFile = tempDir.resolve("test_host_exception.py");
        Path reportsDir = tempDir.resolve("__pyronaut__/reports/tests");
        Path eventsReport = reportsDir.resolve("events.ndjson");
        Files.writeString(
            testFile,
            """
            import java

            ThrowingHost = java.type("io.micronaut.test.pytest.extension.PytestMicronautExtensionTest$ThrowingHost")

            def test_host_method_exception():
                ThrowingHost.fail()
            """,
            StandardCharsets.UTF_8
        );

        PythonContextRuntime.setReuseContext(false);
        PythonContextRuntime.resetContext();
        try (Context context = GraalPyContextFactory.bootstrapReusableContext(
            PytestMicronautExtensionTest.class.getClassLoader()
        )) {
            context.eval(
                "python",
                """
                import glob
                import os
                import sys

                venv = os.environ.get("VIRTUAL_ENV")
                if venv:
                    for site_packages in glob.glob(os.path.join(venv, "lib", "python*", "site-packages")):
                        if site_packages not in sys.path:
                            sys.path.insert(0, site_packages)
                """
            );
            boolean pytestAvailable;
            try {
                context.eval("python", "import pytest");
                pytestAvailable = true;
            } catch (Exception e) {
                pytestAvailable = false;
            }
            assumeTrue(pytestAvailable, "pytest is not visible to the embedded GraalPy context");
            JUnitPytestTestListener listener = new JUnitPytestTestListener(
                EngineExecutionListener.NOOP,
                Set.of(),
                reportsDir.resolve("index.html").toString(),
                reportsDir.resolve(".pyronaut-last-nodeid.txt").toString(),
                eventsReport.toString()
            );
            Value runPytest = context.eval(
                "python",
                """
                from pyronaut.test import run_pytest
                run_pytest
                """
            );

            Value exitCode = runPytest.execute(
                new String[] {testFile.toString()},
                listener,
                reportsDir.resolve("junit.xml").toString()
            );

            assertTrue(exitCode.fitsInInt());
            assertTrue(exitCode.asInt() > 0);
            String events = Files.readString(eventsReport, StandardCharsets.UTF_8);
            assertTrue(events.contains("\"status\":\"FAILED\""));
            assertTrue(events.contains("java.lang.IllegalStateException: host method detail"));
            assertFalse(events.contains("ForeignException"));
            assertFalse(events.contains("exceptions must be classes or instances deriving from BaseException"));
        } finally {
            PythonContextRuntime.setReuseContext(false);
            PythonContextRuntime.resetContext();
        }
    }

    public static final class ThrowingHost {
        private ThrowingHost() {
        }

        public static void fail() {
            throw new IllegalStateException("host method detail");
        }

        public static void failWithInitializerError() {
            throw new ExceptionInInitializerError(new IllegalStateException("tls settings missing"));
        }
    }
}

package io.micronaut.test.pytest.extension;

import io.micronaut.context.python.ContextHolder;
import io.micronaut.test.annotation.MicronautTestValue;
import io.micronaut.test.annotation.TransactionMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PytestMicronautExtensionTest {

    @AfterEach
    void cleanupContextHolder() {
        ContextHolder.setReuseContext(false);
        ContextHolder.resetContext();
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
        assertTrue(message.contains("Caused by: java.lang.IllegalStateException: root cause detail"));
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
            ContextHolder.setReuseContext(false);
            ContextHolder.resetContext();
            thread.setContextClassLoader(threadClassLoader);
            ContextHolder.setContext(null, applicationClassLoader);

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
            ContextHolder.setReuseContext(false);
            ContextHolder.resetContext();
            thread.setContextClassLoader(threadClassLoader);

            assertSame(threadClassLoader, PytestMicronautExtension.resolveApplicationClassLoader());
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}

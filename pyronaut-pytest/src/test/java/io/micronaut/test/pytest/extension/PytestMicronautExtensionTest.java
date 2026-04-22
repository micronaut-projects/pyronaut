package io.micronaut.test.pytest.extension;

import io.micronaut.test.annotation.MicronautTestValue;
import io.micronaut.test.annotation.TransactionMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PytestMicronautExtensionTest {

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
        assertArrayEquals(new Class<?>[0], value.contextBuilder());
    }
}

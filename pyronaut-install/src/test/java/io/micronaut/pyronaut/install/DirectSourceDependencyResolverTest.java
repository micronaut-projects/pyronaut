package io.micronaut.pyronaut.install;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DirectSourceDependencyResolverTest {
    @Test
    void reusesMatchingManifest(@TempDir Path cacheDirectory) throws Exception {
        DirectSourceDependencyResolver resolver = new DirectSourceDependencyResolver();

        assertFalse(resolver.resolve(cacheDirectory, List.of(), List.of(), List.of()).cacheHit());
        assertTrue(resolver.resolve(cacheDirectory, List.of(), List.of(), List.of()).cacheHit());
    }

    @Test
    void fingerprintsScopesAndRepositoriesUnambiguously() {
        assertNotEquals(
            DirectSourceDependencyResolver.fingerprint(List.of("a", "bc"), List.of(), List.of()),
            DirectSourceDependencyResolver.fingerprint(List.of("ab", "c"), List.of(), List.of())
        );
        assertNotEquals(
            DirectSourceDependencyResolver.fingerprint(List.of(), List.of(), List.of()),
            DirectSourceDependencyResolver.fingerprint(List.of(), List.of(), List.of("https://repo.example.test/maven"))
        );
    }
}

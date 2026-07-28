package io.micronaut.pyronaut.install;

import io.micronaut.pyronaut.config.model.PyronautManagedVersions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DirectSourceDependencyResolverTest {
    @Test
    void reusesMatchingManifest(@TempDir Path cacheDirectory) throws Exception {
        Path localRepository = cacheDirectory.resolve("repository");
        writeEmptyPlatformBom(localRepository);
        DirectSourceDependencyResolver resolver = new DirectSourceDependencyResolver();

        assertFalse(resolve(resolver, cacheDirectory, localRepository, false).cacheHit());
        assertTrue(resolve(resolver, cacheDirectory, localRepository, false).cacheHit());
        assertFalse(resolve(resolver, cacheDirectory, localRepository, true).cacheHit());
    }

    private static DirectSourceDependencyResolver.DetailedResult resolve(
        DirectSourceDependencyResolver resolver,
        Path cacheDirectory,
        Path localRepository,
        boolean bypassCache
    ) throws Exception {
        return resolver.resolveDetailed(
            cacheDirectory,
            List.of(),
            List.of(),
            List.of(),
            localRepository,
            true,
            bypassCache,
            List.of()
        );
    }

    private static void writeEmptyPlatformBom(Path repository) throws Exception {
        String version = PyronautManagedVersions.micronautPlatformVersion();
        Path directory = repository.resolve("io/micronaut/platform/micronaut-platform").resolve(version);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("micronaut-platform-" + version + ".pom"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>io.micronaut.platform</groupId>
              <artifactId>micronaut-platform</artifactId>
              <version>%s</version>
              <packaging>pom</packaging>
            </project>
            """.formatted(version));
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
        assertNotEquals(
            DirectSourceDependencyResolver.fingerprint(
                List.of(), List.of(), List.of(), Path.of("repository-one"), List.of("source=one")
            ),
            DirectSourceDependencyResolver.fingerprint(
                List.of(), List.of(), List.of(), Path.of("repository-two"), List.of("source=one")
            )
        );
        assertNotEquals(
            DirectSourceDependencyResolver.fingerprint(
                List.of(), List.of(), List.of(), Path.of("repository"), List.of("source=one")
            ),
            DirectSourceDependencyResolver.fingerprint(
                List.of(), List.of(), List.of(), Path.of("repository"), List.of("source=two")
            )
        );
    }
}

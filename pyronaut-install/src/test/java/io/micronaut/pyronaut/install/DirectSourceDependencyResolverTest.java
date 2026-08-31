package io.micronaut.pyronaut.install;

import io.micronaut.pyronaut.config.model.PyronautManagedVersions;
import io.micronaut.testresources.core.TestResourcesResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void requiresJdbcTestResourcesOnlyForConfiguredMissingProperties() throws Exception {
        TestResourcesResolver resolver = resolver(
            List.of("datasources"),
            List.of(
                "datasources.default.url",
                "datasources.default.username",
                "datasources.default.password"
            )
        );

        assertTrue(DirectSourceDependencyResolver.requiresTestResources(
            List.of(resolver),
            Map.of("datasources.default.db-type", "mysql")
        ));
        assertFalse(DirectSourceDependencyResolver.requiresTestResources(
            List.of(resolver),
            Map.of()
        ));
        assertFalse(DirectSourceDependencyResolver.requiresTestResources(
            List.of(resolver),
            Map.of("kafka.enabled", "true")
        ));
        assertFalse(DirectSourceDependencyResolver.requiresTestResources(
            List.of(resolver),
            Map.of(
                "datasources.default.db-type", "mysql",
                "datasources.default.url", "jdbc:mysql://localhost/example",
                "datasources.default.username", "user",
                "datasources.default.password", "secret"
            )
        ));
    }

    @Test
    void supportsResolversWithoutPropertyEntriesSuchAsKafka() throws Exception {
        TestResourcesResolver resolver = resolver(List.of(), List.of("kafka.bootstrap.servers"));

        assertTrue(DirectSourceDependencyResolver.requiresTestResources(
            List.of(resolver),
            Map.of("kafka.enabled", "true")
        ));
        assertFalse(DirectSourceDependencyResolver.requiresTestResources(
            List.of(resolver),
            Map.of("datasources.default.db-type", "mysql")
        ));
        assertFalse(DirectSourceDependencyResolver.requiresTestResources(
            List.of(),
            Map.of("kafka.enabled", "true")
        ));
    }

    @Test
    void reportsProviderInspectionFailures() {
        TestResourcesResolver failing = new TestResourcesResolver() {
            @Override
            public List<String> getResolvableProperties(
                Map<String, java.util.Collection<String>> propertyEntries,
                Map<String, Object> testResourcesConfig
            ) {
                throw new NoClassDefFoundError("missing-provider-link");
            }

            @Override
            public Optional<String> resolve(
                String propertyName,
                Map<String, Object> properties,
                Map<String, Object> testResourcesConfig
            ) {
                return Optional.empty();
            }
        };

        assertThrows(java.io.IOException.class, () ->
            DirectSourceDependencyResolver.requiresTestResources(
                List.of(failing),
                Map.of("kafka.enabled", "true")
            )
        );
    }

    @Test
    void resolvesAndCachesConditionalMysqlLaunchDependencies(@TempDir Path cacheDirectory) throws Exception {
        AtomicBoolean environmentDisabled = new AtomicBoolean();
        DirectSourceDependencyResolver resolver = new DirectSourceDependencyResolver(
            new MavenClasspathResolver(
                new ProxyConfigurationLoader(),
                name -> "PYRONAUT_TEST_RESOURCES_DISABLED".equals(name) && environmentDisabled.get()
                    ? "true"
                    : null
            )
        );
        List<String> runtime = List.of(
            "io.micronaut.data:micronaut-data-jdbc",
            "io.micronaut.sql:micronaut-jdbc-hikari",
            "com.mysql:mysql-connector-j"
        );
        Map<String, String> inferredConfiguration = Map.of(
            "datasources.default.db-type", "mysql",
            "datasources.default.dialect", "MYSQL"
        );

        DirectSourceDependencyResolver.LaunchResult first = resolver.resolveForLaunch(
            cacheDirectory,
            List.of("io.micronaut.data:micronaut-data-processor"),
            runtime,
            List.of(),
            inferredConfiguration,
            true
        );
        assertTrue(first.testResourcesRequired());
        assertTrue(first.runtime().stream().anyMatch(path -> path.contains("micronaut-test-resources-client")));
        assertFalse(first.runtime().stream().anyMatch(path -> path.contains("micronaut-test-resources-server")));
        assertTrue(first.testResourcesServer().stream().anyMatch(path -> path.contains("test-resources-jdbc-mysql")));
        assertTrue(first.testResourcesServer().stream().anyMatch(path -> path.contains("micronaut-test-resources-server")));
        assertTrue(Files.isRegularFile(cacheDirectory.resolve("resolved-test-resources-server-dependencies")));
        assertTrue(resolver.resolveForLaunch(
            cacheDirectory,
            List.of("io.micronaut.data:micronaut-data-processor"),
            runtime,
            List.of(),
            inferredConfiguration,
            true
        ).cacheHit());

        environmentDisabled.set(true);
        DirectSourceDependencyResolver.LaunchResult disabled = resolver.resolveForLaunch(
            cacheDirectory,
            List.of("io.micronaut.data:micronaut-data-processor"),
            runtime,
            List.of(),
            inferredConfiguration,
            true
        );
        assertFalse(disabled.cacheHit());
        assertFalse(disabled.testResourcesRequired());
        assertFalse(Files.exists(cacheDirectory.resolve("resolved-test-resources-server-dependencies")));
        environmentDisabled.set(false);
        assertTrue(resolver.resolveForLaunch(
            cacheDirectory,
            List.of("io.micronaut.data:micronaut-data-processor"),
            runtime,
            List.of(),
            inferredConfiguration,
            true
        ).testResourcesRequired());

        DirectSourceDependencyResolver.LaunchResult fullyConfigured = resolver.resolveForLaunch(
            cacheDirectory,
            List.of("io.micronaut.data:micronaut-data-processor"),
            runtime,
            List.of(),
            Map.of(
                "datasources.default.db-type", "mysql",
                "datasources.default.dialect", "MYSQL",
                "datasources.default.url", "jdbc:mysql://localhost/example",
                "datasources.default.username", "user",
                "datasources.default.password", "secret",
                "datasources.default.driver-class-name", "com.mysql.cj.jdbc.Driver",
                "datasources.default.x-protocol-url", "mysqlx://localhost/example"
            ),
            true
        );
        assertFalse(fullyConfigured.cacheHit());
        assertFalse(fullyConfigured.testResourcesRequired());
        assertFalse(Files.exists(cacheDirectory.resolve("resolved-test-resources-server-dependencies")));
        Files.writeString(cacheDirectory.resolve("resolved-test-resources-server-dependencies"), "/stale/server.jar\n");
        assertTrue(resolver.resolveForLaunch(
            cacheDirectory,
            List.of("io.micronaut.data:micronaut-data-processor"),
            runtime,
            List.of(),
            Map.of(
                "datasources.default.db-type", "mysql",
                "datasources.default.dialect", "MYSQL",
                "datasources.default.url", "jdbc:mysql://localhost/example",
                "datasources.default.username", "user",
                "datasources.default.password", "secret",
                "datasources.default.driver-class-name", "com.mysql.cj.jdbc.Driver",
                "datasources.default.x-protocol-url", "mysqlx://localhost/example"
            ),
            true
        ).cacheHit());
        assertFalse(Files.exists(cacheDirectory.resolve("resolved-test-resources-server-dependencies")));

        assertTrue(resolver.resolveForLaunch(
            cacheDirectory,
            List.of("io.micronaut.data:micronaut-data-processor"),
            runtime,
            List.of(),
            inferredConfiguration,
            true
        ).testResourcesRequired());
        DirectSourceDependencyResolver.LaunchResult production = resolver.resolveForLaunch(
            cacheDirectory,
            List.of("io.micronaut.data:micronaut-data-processor"),
            runtime,
            List.of(),
            inferredConfiguration,
            false
        );
        assertFalse(production.cacheHit());
        assertFalse(production.testResourcesRequired());
        assertFalse(Files.exists(cacheDirectory.resolve("resolved-test-resources-server-dependencies")));
    }

    private static TestResourcesResolver resolver(List<String> requiredEntries, List<String> resolvable) {
        return new TestResourcesResolver() {
            @Override
            public List<String> getResolvableProperties(
                Map<String, java.util.Collection<String>> propertyEntries,
                Map<String, Object> testResourcesConfig
            ) {
                return resolvable;
            }

            @Override
            public List<String> getRequiredPropertyEntries() {
                return requiredEntries;
            }

            @Override
            public Optional<String> resolve(
                String propertyName,
                Map<String, Object> properties,
                Map<String, Object> testResourcesConfig
            ) {
                return Optional.empty();
            }
        };
    }
}

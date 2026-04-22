package io.micronaut.pyronaut.testresources;

import io.micronaut.http.HttpRequest;
import io.micronaut.testresources.controlpanel.ControlPanelPropertyResolutionListener;
import io.micronaut.testresources.controlpanel.DockerHealthControlPanel;
import io.micronaut.testresources.core.ResolverLoader;
import io.micronaut.testresources.core.TestResourcesResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestResourcesInsightsControllerTest {

    @Test
    void healthAuthMatrixAndPayloadShape() {
        String originalToken = System.getProperty("server.access-token");
        String originalPort = System.getProperty("micronaut.server.port");
        System.setProperty("server.access-token", "token-123");
        System.setProperty("micronaut.server.port", "18080");
        try {
            TestResourcesInsightsController controller = new TestResourcesInsightsController(
                new StubDockerHealthControlPanel(),
                new StubResolverLoader(List.of()),
                new ControlPanelPropertyResolutionListener()
            );

            var missingAuth = controller.health(HttpRequest.GET("http://localhost:8080/api/test-resources/health"));
            assertEquals(401, missingAuth.code());

            var invalidAuth = controller.health(
                HttpRequest.GET("http://localhost:8080/api/test-resources/health").header("Access-Token", "bad-token")
            );
            assertEquals(401, invalidAuth.code());

            var validAuth = controller.health(
                HttpRequest.GET("http://localhost:8080/api/test-resources/health").header("Access-Token", "token-123")
            );
            assertEquals(200, validAuth.code());
            Map<String, Object> payload = validAuth.body();
            assertNotNull(payload);
            assertTrue(payload.containsKey("health"));
            @SuppressWarnings("unchecked")
            Map<String, Object> health = (Map<String, Object>) payload.get("health");
            assertEquals("http://localhost:8080", health.get("uri"));
            assertEquals(8080, health.get("port"));
        } finally {
            restoreSystemProperty("server.access-token", originalToken);
            restoreSystemProperty("micronaut.server.port", originalPort);
        }
    }

    @Test
    void containersPropertiesAndErrorsReturnJsonWithValidAccessToken() {
        String originalToken = System.getProperty("server.access-token");
        String originalPort = System.getProperty("micronaut.server.port");
        System.setProperty("server.access-token", "token-123");
        System.setProperty("micronaut.server.port", "18080");
        try {
            StubResolver resolver = new StubResolver("mysql", "MySQL");
            ControlPanelPropertyResolutionListener listener = new ControlPanelPropertyResolutionListener();
            listener.resolved(
                "datasources.default.url",
                "jdbc:mysql://localhost:3306/default",
                resolver,
                Map.of("datasources", "default"),
                Map.of("enabled", true)
            );
            listener.errored(
                "datasources.default.password",
                resolver,
                new IllegalStateException("boom")
            );
            TestResourcesInsightsController controller = new TestResourcesInsightsController(
                new StubDockerHealthControlPanel(),
                new StubResolverLoader(List.of(resolver)),
                listener
            );
            HttpRequest<?> request = HttpRequest.GET("/api/test-resources/containers")
                .header("Access-Token", "token-123");

            var containers = controller.containers(request);
            var properties = controller.properties(request);
            var errors = controller.errors(request);

            assertEquals(200, containers.code());
            assertEquals(200, properties.code());
            assertEquals(200, errors.code());
            assertTrue(containers.body().containsKey("containers"));
            assertTrue(properties.body().containsKey("properties"));
            assertTrue(errors.body().containsKey("errors"));
            assertTrue(containers.body().get("containers") instanceof List<?>);
            assertTrue(properties.body().get("properties") instanceof List<?>);
            assertTrue(errors.body().get("errors") instanceof List<?>);

            @SuppressWarnings("unchecked")
            List<Map<String, String>> containerRows = (List<Map<String, String>>) containers.body().get("containers");
            @SuppressWarnings("unchecked")
            List<Map<String, String>> propertyRows = (List<Map<String, String>>) properties.body().get("properties");
            @SuppressWarnings("unchecked")
            List<Map<String, String>> errorRows = (List<Map<String, String>>) errors.body().get("errors");

            assertEquals("mysql:8.4.0", containerRows.getFirst().get("name"));
            assertEquals("datasources.default.url", propertyRows.getFirst().get("key"));
            assertEquals("default", propertyRows.getFirst().get("scope"));
            assertEquals("datasources.default.password", errorRows.getFirst().get("property"));
            assertTrue(errorRows.getFirst().get("message").contains("IllegalStateException"));
        } finally {
            restoreSystemProperty("server.access-token", originalToken);
            restoreSystemProperty("micronaut.server.port", originalPort);
        }
    }

    private static void restoreSystemProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static final class StubDockerHealthControlPanel extends DockerHealthControlPanel {
        @Override
        public io.micronaut.testresources.controlpanel.DockerHealth getBody() {
            return new io.micronaut.testresources.controlpanel.DockerHealth(
                io.micronaut.testresources.controlpanel.Status.AVAILABLE,
                null,
                1,
                List.of(new io.micronaut.testresources.controlpanel.TestResourcesContainer(
                    "datasources",
                    "container-1",
                    "mysql:8.4.0",
                    "bridge",
                    "mysql:8.4.0"
                )),
                List.of(),
                List.of()
            );
        }
    }

    private static final class StubResolverLoader implements ResolverLoader {
        private final List<TestResourcesResolver> resolvers;

        private StubResolverLoader(List<TestResourcesResolver> resolvers) {
            this.resolvers = resolvers;
        }

        @Override
        public List<TestResourcesResolver> getResolvers() {
            return resolvers;
        }
    }

    private static final class StubResolver implements TestResourcesResolver {
        private final String id;
        private final String displayName;

        private StubResolver(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        @Override
        public Optional<String> resolve(String expression, Map<String, Object> properties, Map<String, Object> testResourcesConfig) {
            return Optional.empty();
        }

        @Override
        public List<String> getResolvableProperties(Map<String, java.util.Collection<String>> propertyEntries, Map<String, Object> testResourcesConfig) {
            return List.of();
        }

        @Override
        public List<String> getRequiredPropertyEntries() {
            return List.of();
        }

        @Override
        public List<String> getRequiredProperties(String expression) {
            return List.of();
        }

        @Override
        public String getDisplayName() {
            return displayName;
        }

        @Override
        public String getId() {
            return id;
        }
    }
}

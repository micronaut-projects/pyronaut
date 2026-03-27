package io.micronaut.pyronaut.testresources;

import io.micronaut.http.HttpRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
            TestResourcesInsightsController controller = new TestResourcesInsightsController();

            var missingAuth = controller.health(HttpRequest.GET("/api/test-resources/health"));
            assertEquals(401, missingAuth.code());

            var invalidAuth = controller.health(
                HttpRequest.GET("/api/test-resources/health").header("Authorization", "Bearer bad-token")
            );
            assertEquals(403, invalidAuth.code());

            var validAuth = controller.health(
                HttpRequest.GET("/api/test-resources/health").header("Authorization", "Bearer token-123")
            );
            assertEquals(200, validAuth.code());
            Map<String, Object> payload = validAuth.body();
            assertNotNull(payload);
            assertTrue(payload.containsKey("health"));
        } finally {
            restoreSystemProperty("server.access-token", originalToken);
            restoreSystemProperty("micronaut.server.port", originalPort);
        }
    }

    @Test
    void containersPropertiesAndErrorsReturnJsonWithValidAuth() {
        String originalToken = System.getProperty("server.access-token");
        String originalPort = System.getProperty("micronaut.server.port");
        System.setProperty("server.access-token", "token-123");
        System.setProperty("micronaut.server.port", "18080");
        try {
            TestResourcesInsightsController controller = new TestResourcesInsightsController();
            HttpRequest<?> request = HttpRequest.GET("/api/test-resources/containers")
                .header("Authorization", "Bearer token-123");

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
}

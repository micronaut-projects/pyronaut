package example;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@MicronautTest
class ExternalBuildTest {
    @Test
    void mainAndTestResourcesAreVisible() throws IOException {
        try (InputStream main = getClass().getResourceAsStream("/main-marker.txt");
             InputStream test = getClass().getResourceAsStream("/test-marker.txt")) {
            Assertions.assertEquals("external-main-resource", new String(main.readAllBytes(), StandardCharsets.UTF_8).trim());
            Assertions.assertEquals("external-test-resource", new String(test.readAllBytes(), StandardCharsets.UTF_8).trim());
        }
    }
}

package io.micronaut.pyronaut.processor;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessorOptionsTest {
    @Test
    void filtersAndMergesApplicationOptionsAndDisablesOpenApiForTests() throws Exception {
        Path root = Files.createTempDirectory("processor-options");
        Path cache = Files.createDirectories(root.resolve("__pyronaut__"));
        Properties properties = new Properties();
        properties.setProperty("options", "micronaut.openapi.enabled,micronaut.openapi.views.spec,example.option");
        try (var writer = Files.newBufferedWriter(cache.resolve("annotation-processor-options.properties"))) {
            properties.store(writer, "test");
        }
        Path resources = Files.createDirectories(root.resolve("src/main/resources"));
        Files.writeString(resources.resolve("application.toml"), "[micronaut.openapi]\nenabled = false\n[example]\noption = 42\nunrelated = true\n");

        List<String> main = ProcessorOptions.resolve(root, null, List.of(), false);
        List<String> test = ProcessorOptions.resolve(root, null, List.of(), true);
        assertTrue(main.contains("-Amicronaut.openapi.enabled=false"));
        assertTrue(main.contains("-Amicronaut.openapi.views.spec=swagger-ui.enabled=true,redoc.enabled=true"));
        assertTrue(main.contains("-Aexample.option=42"));
        assertTrue(test.contains("-Amicronaut.openapi.enabled=false"));
    }
}

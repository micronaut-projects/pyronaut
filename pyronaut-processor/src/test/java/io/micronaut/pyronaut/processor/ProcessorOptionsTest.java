package io.micronaut.pyronaut.processor;

import io.micronaut.pyronaut.config.model.PyprojectModel;
import io.micronaut.pyronaut.config.model.PyprojectModelReader;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void passesExcludedPythonClassesToTheCompiler() throws Exception {
        Path root = Files.createTempDirectory("processor-options-exclude");
        Files.writeString(root.resolve("pyproject.toml"), """
            [project]
            name = "weather-agent"
            version = "1.0.0"

            [tool.pyronaut.processor]
            exclude = ["weather_agent.core.*", "weather_agent.util.Helper"]
            """);
        PyprojectModel model = new PyprojectModelReader().readProjectDirectory(root);

        List<String> options = ProcessorOptions.resolve(root, model, List.of(), false);
        assertTrue(options.contains("-Amicronaut.python.exclude=weather_agent.core.*,weather_agent.util.Helper"), options.toString());

        List<String> overridden = ProcessorOptions.resolve(root, model, List.of("-Amicronaut.python.exclude=other.*"), false);
        assertTrue(overridden.contains("-Amicronaut.python.exclude=other.*"), overridden.toString());
        assertFalse(overridden.stream().anyMatch(option -> option.contains("weather_agent")), overridden.toString());
    }
}

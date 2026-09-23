package io.micronaut.pyronaut.processor;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void emitsTypeCheckingAndStaticCompilationSettingsAsOptions() throws Exception {
        Path root = Files.createTempDirectory("processor-settings");
        List<String> none = ProcessorOptions.resolve(root, null, List.of(), false, ProcessorOptions.ProcessorSettings.DEFAULT);
        assertTrue(none.stream().noneMatch(option -> option.startsWith("-Amicronaut.python.")), none.toString());

        ProcessorOptions.ProcessorSettings settings = new ProcessorOptions.ProcessorSettings("error", "all", root.resolve("reports"), true);
        List<String> main = ProcessorOptions.resolve(root, null, List.of(), false, settings);
        List<String> test = ProcessorOptions.resolve(root, null, List.of(), true, settings);
        assertTrue(main.contains("-Amicronaut.python.typecheck=error"), main.toString());
        assertTrue(main.contains("-Amicronaut.python.compile.static=all"), main.toString());
        assertTrue(main.contains("-Amicronaut.python.compile.static.strict=true"), main.toString());
        assertTrue(main.contains("-Amicronaut.python.compile.static.report=" + root.resolve("reports/main").toAbsolutePath().normalize()), main.toString());
        assertTrue(test.contains("-Amicronaut.python.compile.static.report=" + root.resolve("reports/test").toAbsolutePath().normalize()), test.toString());

        List<String> explicit = ProcessorOptions.resolve(root, null, List.of("-Amicronaut.python.typecheck=warn"), false, settings);
        assertTrue(explicit.contains("-Amicronaut.python.typecheck=warn"), explicit.toString());
        assertTrue(explicit.stream().noneMatch("-Amicronaut.python.typecheck=error"::equals), explicit.toString());
        assertEquals("type-check error · static all (strict)", settings.describe());
        assertNull(ProcessorOptions.ProcessorSettings.DEFAULT.describe());
    }
}

package io.micronaut.pyronaut.projectgen;

import io.micronaut.pyronaut.config.model.PyronautManagedVersions;
import io.micronaut.projectgen.core.io.PreviewGenerator;
import io.micronaut.projectgen.core.options.ConfigurationFormat;
import io.micronaut.projectgen.core.options.GenericOptionsBuilder;
import io.micronaut.projectgen.core.options.Language;
import io.micronaut.projectgen.core.options.Options;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest(startApplication = false)
class PyronautProjectGeneratorTest {
    @Test
    void defaultPreviewContainsMinimalRunnableApp(PreviewGenerator generator) throws Exception {
        Map<String, String> project = generator.generate(defaultOptions(List.of()));

        assertEquals(Set.of(
            ".agents/skills/pyronaut-cli/SKILL.md",
            ".agents/skills/pyronaut-coding/SKILL.md",
            ".agents/skills/pyronaut-project/SKILL.md",
            ".gitignore",
            "config/application.toml",
            "pyproject.toml",
            "src/demo/controllers.py",
            "src/main.py",
            "tests/test_demo.py",
            "tests-config/application-test.toml"
        ), project.keySet());
        assertFalse(project.containsKey("setup.py"));
        assertTrue(project.get("src/demo/controllers.py").contains("@Get"));
        assertTrue(project.get("src/demo/controllers.py").contains("def index() -> str:"));
        assertFalse(project.get("src/demo/controllers.py").contains("class MyController"));
        assertTrue(project.get("src/main.py").contains("import demo.controllers"));
        assertTrue(project.get("src/main.py").contains("%cyan(%d{HH:mm:ss.SSS}) %gray([%thread]) %highlight(%-5level) %magenta(%logger{36}) - %msg%n"));
        assertTrue(project.get("src/main.py").contains("\"class\": \"ch.qos.logback.core.ConsoleAppender\""));
        assertTrue(project.get("tests/test_demo.py").contains("micronaut_test_fixture"));
        assertTrue(project.get("tests/test_demo.py").contains("from pyronaut import requests"));
        assertTrue(project.get("tests/test_demo.py").contains("requests.with_context(application_context)"));
        assertTrue(project.get("tests/test_demo.py").contains("response = client.get(\"/\")"));
        assertTrue(project.get("tests/test_demo.py").contains("assert response.text == \"Hello World\""));
        assertTrue(project.get("tests/test_demo.py").contains("assert response.headers[\"Content-Type\"].startswith(\"text/plain\")"));
        assertTrue(project.get("config/application.toml").contains("[micronaut.application]\nname = 'demo'"));
        assertFalse(project.get("config/application.toml").contains("micronaut.application.name = 'demo'"));
        assertTrue(project.get("tests-config/application-test.toml").contains("[micronaut.server]\nport = -1"));
        assertFalse(project.get("tests-config/application-test.toml").contains("micronaut.server.port = -1"));
        assertTrue(project.get(".agents/skills/pyronaut-project/SKILL.md").contains("name: pyronaut-project"));
        assertTrue(project.get(".agents/skills/pyronaut-project/SKILL.md").contains("Do not add the legacy `tool.pyronaut.version` key"));
        assertTrue(project.get(".agents/skills/pyronaut-cli/SKILL.md").contains("pyronaut validate-config --scenario dev|run|test|production"));
        assertTrue(project.get(".agents/skills/pyronaut-coding/SKILL.md").contains("do not use `micronaut-jackson-databind`, `hibernate-jpa`, `data-jpa`, or `hibernate-validator`"));
        assertTrue(project.get(".agents/skills/pyronaut-coding/SKILL.md").contains("requests.with_context(my_context)"));
        assertTrue(project.get("pyproject.toml").contains("[tool.pyronaut.test-resources]"));
        assertTrue(project.get("pyproject.toml").contains("enabled = false"));
    }

    @Test
    void pyprojectUsesManagedCoreAndPlatformVersions(PreviewGenerator generator) throws Exception {
        Map<String, String> project = generator.generate(defaultOptions(List.of()));
        String pyproject = project.get("pyproject.toml");

        assertTrue(pyproject.contains("[tool.pyronaut]"));
        assertTrue(pyproject.contains("core.version = '" + PyronautManagedVersions.micronautCoreVersion() + "'"));
        assertTrue(pyproject.contains("platform.version = '" + PyronautManagedVersions.micronautPlatformVersion() + "'"));
        if (PyronautManagedVersions.micronautCoreVersion().endsWith("-SNAPSHOT")
            || PyronautManagedVersions.micronautPlatformVersion().endsWith("-SNAPSHOT")) {
            assertTrue(pyproject.contains("repositories = ['mavenLocal', 'https://central.sonatype.com/repository/maven-snapshots/', 'mavenCentral']"));
        }
        assertFalse(pyproject.contains("[tool.pyronaut]\nversion = "));
    }

    @Test
    void dependenciesRenderIntoPyronautScopes(PreviewGenerator generator) throws Exception {
        Map<String, String> project = generator.generate(defaultOptions(List.of("data-jdbc", "mysql", "json-schema", "test-resources")));
        String pyproject = project.get("pyproject.toml");
        String dependencies = dependenciesSection(pyproject);

        assertTrue(pyproject.contains("[tool.pyronaut.dependencies]"));
        assertTrue(dependencies.contains("runtime = [\n"));
        assertTrue(dependencies.contains("  'io.micronaut:micronaut-http-server-netty'"));
        assertTrue(dependencies.contains("  'io.micronaut.serde:micronaut-serde-jackson'"));
        assertTrue(dependencies.contains("  'io.micronaut.data:micronaut-data-jdbc'"));
        assertTrue(dependencies.contains("  'io.micronaut.sql:micronaut-jdbc-hikari'"));
        assertTrue(dependencies.contains("  'com.mysql:mysql-connector-j'"));
        assertTrue(dependencies.contains("  'io.micronaut.jsonschema:micronaut-json-schema-annotations'"));
        assertTrue(dependencies.contains("build = [\n"));
        assertTrue(dependencies.contains("  'io.micronaut.serde:micronaut-serde-processor'"));
        assertTrue(dependencies.contains("  'io.micronaut.data:micronaut-data-processor'"));
        assertTrue(dependencies.contains("  'io.micronaut.jsonschema:micronaut-json-schema-processor'"));
        assertTrue(dependencies.contains("test = [\n"));
        assertTrue(dependencies.contains("  'io.micronaut.pyronaut:micronaut-pyronaut-pytest'"));
        assertTrue(dependencies.contains("  'io.micronaut.pyronaut:micronaut-pyronaut-requests'"));
        assertFalse(dependencies.contains("runtime = ['"));
        assertFalse(dependencies.contains("build = ['"));
        assertFalse(dependencies.contains("test = ['"));
        assertTrue(pyproject.contains("[tool.pyronaut.test-resources]"));
        assertTrue(pyproject.contains("enabled = true"));
        assertFalse(pyproject.contains("enabled = false"));
    }

    @Test
    void dependencyScopesMirrorPyronautInstallNames(PreviewGenerator generator) throws Exception {
        Map<String, String> project = generator.generate(defaultOptions(List.of("scope-mapping-fixture")));
        String pyproject = project.get("pyproject.toml");

        String runtimeDependencies = dependenciesList(pyproject, "runtime");
        String buildDependencies = dependenciesList(pyproject, "build");
        String testDependencies = dependenciesList(pyproject, "test");

        assertTrue(runtimeDependencies.contains("com.example:compile-dep"));
        assertTrue(runtimeDependencies.contains("com.example:runtime-dep"));
        assertTrue(buildDependencies.contains("com.example:annotation-processor-dep"));
        assertTrue(buildDependencies.contains("com.example:test-annotation-processor-dep"));
        assertTrue(testDependencies.contains("com.example:test-dep"));
        assertFalse(testDependencies.contains("com.example:test-annotation-processor-dep"));
        assertFalse(pyproject.contains("annotationProcessor = ["));
        assertFalse(pyproject.contains("testAnnotationProcessor = ["));
        assertFalse(pyproject.contains("compile = ["));
    }

    @Test
    void featureConfigurationRendersToApplicationToml(PreviewGenerator generator) throws Exception {
        Map<String, String> project = generator.generate(defaultOptions(List.of("mysql")));
        String application = project.get("config/application.toml");

        assertTrue(application.contains("[datasources.default]"));
        assertTrue(application.contains("driver-class-name = 'com.mysql.cj.jdbc.Driver'"));
        assertTrue(application.contains("db-type = 'mysql'"));
        assertTrue(project.get("tests-config/application-test.toml").contains("[micronaut.server]\nport = -1"));
    }

    @Test
    void nestedPythonPackagesDoNotIncludeUnsupportedInitFiles(PreviewGenerator generator) throws Exception {
        Options options = GenericOptionsBuilder.builder()
            .name("demo")
            .packageName("example.service")
            .version("0.1.0")
            .language(Language.PYTHON)
            .configurationFormat(ConfigurationFormat.TOML)
            .build();

        Map<String, String> project = generator.generate(options);

        assertFalse(project.containsKey("src/example/__init__.py"));
        assertFalse(project.containsKey("src/example/service/__init__.py"));
        assertTrue(project.containsKey("src/example/service/controllers.py"));
        assertTrue(project.containsKey("tests/test_example_service.py"));
        assertTrue(project.get("src/main.py").contains("import example.service.controllers"));
    }

    @Test
    void unsupportedFeatureFailsEarly(PreviewGenerator generator) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> generator.generate(defaultOptions(List.of("gradle"))));

        assertTrue(exception.getMessage().contains("not supported for Pyronaut/Python projects"));
    }

    @Test
    void reflectionDependentFeaturesFailEarly(PreviewGenerator generator) {
        for (String feature : List.of("jackson-databind", "data-jpa", "hibernate-jpa", "hibernate-validator")) {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> generator.generate(defaultOptions(List.of(feature))));

            assertTrue(exception.getMessage().contains("not supported for Pyronaut/Python projects"));
        }
    }

    @Test
    void groovyFeaturesFailEarly(PreviewGenerator generator) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> generator.generate(defaultOptions(List.of("groovy-json"))));

        assertTrue(exception.getMessage().contains("not supported for Pyronaut/Python projects"));
    }

    @Test
    void visibleAvailableFeaturesDoNotExposeUnsupportedFeatures(PyronautAvailableFeatures availableFeatures) {
        List<String> visible = availableFeatures.getFeatures()
            .map(feature -> feature.getName())
            .toList();

        assertTrue(visible.containsAll(List.of("http-server-netty", "serde-jackson", "data-jdbc", "mysql", "json-schema", "management", "pyronaut-logback", "pyronaut-pytest")));
        assertFalse(visible.contains("serialization-jackson"));
        assertFalse(visible.contains("netty-server"));
        assertFalse(visible.contains("gradle"));
        assertFalse(visible.contains("java"));
        assertFalse(visible.contains("jackson-databind"));
        assertFalse(visible.contains("data-jpa"));
        assertFalse(visible.contains("hibernate-jpa"));
        assertFalse(visible.contains("hibernate-validator"));
        assertFalse(visible.stream().anyMatch(feature -> feature.startsWith("groovy-")));
    }

    private static Options defaultOptions(List<String> features) {
        return GenericOptionsBuilder.builder()
            .name("demo")
            .packageName("demo")
            .version("0.1.0")
            .language(Language.PYTHON)
            .configurationFormat(ConfigurationFormat.TOML)
            .features(features)
            .build();
    }

    private static String dependenciesSection(String pyproject) {
        int start = pyproject.indexOf("[tool.pyronaut.dependencies]");
        if (start < 0) {
            throw new IllegalArgumentException("Missing dependencies section");
        }
        int nextSection = pyproject.indexOf("\n[", start + 1);
        return nextSection < 0 ? pyproject.substring(start) : pyproject.substring(start, nextSection);
    }

    private static String dependenciesList(String pyproject, String scope) {
        String dependencies = dependenciesSection(pyproject);
        String startMarker = scope + " = [\n";
        int start = dependencies.indexOf(startMarker);
        if (start < 0) {
            throw new IllegalArgumentException("Missing dependency scope: " + scope);
        }
        int end = dependencies.indexOf("\n]", start);
        if (end < 0) {
            throw new IllegalArgumentException("Unclosed dependency scope: " + scope);
        }
        return dependencies.substring(start, end);
    }
}

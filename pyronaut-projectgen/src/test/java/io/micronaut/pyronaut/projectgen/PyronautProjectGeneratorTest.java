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
            "config/micronaut-banner.txt",
            "pyproject.toml",
            "src/demo/__init__.py",
            "src/demo/controller.py",
            "src/main.py",
            "tests/test_demo.py",
            "tests-config/application-test.toml"
        ), project.keySet());
        assertFalse(project.containsKey("setup.py"));
        assertTrue(project.get("src/demo/controller.py").contains("@Controller"));
        assertTrue(project.get("src/main.py").contains("from demo.controller import MyController"));
        assertTrue(project.get("tests/test_demo.py").contains("micronaut_test_fixture"));
        assertTrue(project.get("config/application.toml").contains("name = 'demo'"));
        assertTrue(project.get(".agents/skills/pyronaut-project/SKILL.md").contains("name: pyronaut-project"));
        assertTrue(project.get(".agents/skills/pyronaut-cli/SKILL.md").contains("pyronaut validate-config --scenario run|test|production"));
        assertTrue(project.get(".agents/skills/pyronaut-coding/SKILL.md").contains("do not use `micronaut-jackson-databind`, `hibernate-jpa`, `data-jpa`, or `hibernate-validator`"));
    }

    @Test
    void pyprojectUsesManagedCoreAndPlatformVersions(PreviewGenerator generator) throws Exception {
        Map<String, String> project = generator.generate(defaultOptions(List.of()));
        String pyproject = project.get("pyproject.toml");

        assertTrue(pyproject.contains("[tool.pyronaut]"));
        assertTrue(pyproject.contains("core.version = '" + PyronautManagedVersions.micronautCoreVersion() + "'"));
        assertTrue(pyproject.contains("platform.version = '" + PyronautManagedVersions.micronautPlatformVersion() + "'"));
        assertFalse(pyproject.contains("[tool.pyronaut]\nversion = "));
    }

    @Test
    void dependenciesRenderIntoPyronautScopes(PreviewGenerator generator) throws Exception {
        Map<String, String> project = generator.generate(defaultOptions(List.of("data-jdbc", "mysql", "json-schema", "test-resources")));
        String pyproject = project.get("pyproject.toml");

        assertTrue(pyproject.contains("[tool.pyronaut.dependencies]"));
        assertTrue(pyproject.contains("runtime = ["));
        assertTrue(pyproject.contains("'io.micronaut:micronaut-http-server-netty'"));
        assertTrue(pyproject.contains("'io.micronaut.serde:micronaut-serde-jackson'"));
        assertTrue(pyproject.contains("'io.micronaut.data:micronaut-data-jdbc'"));
        assertTrue(pyproject.contains("'io.micronaut.sql:micronaut-jdbc-hikari'"));
        assertTrue(pyproject.contains("'com.mysql:mysql-connector-j'"));
        assertTrue(pyproject.contains("'io.micronaut.jsonschema:micronaut-json-schema-annotations'"));
        assertTrue(pyproject.contains("build = ["));
        assertTrue(pyproject.contains("'io.micronaut.serde:micronaut-serde-processor'"));
        assertTrue(pyproject.contains("'io.micronaut.data:micronaut-data-processor'"));
        assertTrue(pyproject.contains("'io.micronaut.jsonschema:micronaut-json-schema-processor'"));
        assertTrue(pyproject.contains("test = ["));
        assertTrue(pyproject.contains("'io.micronaut.pyronaut:micronaut-pyronaut-pytest'"));
        assertTrue(pyproject.contains("'io.micronaut.pyronaut:micronaut-pyronaut-requests'"));
        assertTrue(pyproject.contains("[tool.pyronaut.test-resources]"));
        assertTrue(pyproject.contains("enabled = true"));
    }

    @Test
    void dependencyScopesMirrorPyronautInstallNames(PreviewGenerator generator) throws Exception {
        Map<String, String> project = generator.generate(defaultOptions(List.of("scope-mapping-fixture")));
        String pyproject = project.get("pyproject.toml");

        String runtimeDependencies = dependenciesLine(pyproject, "runtime");
        String buildDependencies = dependenciesLine(pyproject, "build");
        String testDependencies = dependenciesLine(pyproject, "test");

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
        assertTrue(project.get("tests-config/application-test.toml").contains("port = -1"));
    }

    @Test
    void nestedPythonPackagesIncludeParentInitFiles(PreviewGenerator generator) throws Exception {
        Options options = GenericOptionsBuilder.builder()
            .name("demo")
            .packageName("example.service")
            .version("0.1.0")
            .language(Language.PYTHON)
            .configurationFormat(ConfigurationFormat.TOML)
            .build();

        Map<String, String> project = generator.generate(options);

        assertTrue(project.containsKey("src/example/__init__.py"));
        assertTrue(project.containsKey("src/example/service/__init__.py"));
        assertTrue(project.containsKey("src/example/service/controller.py"));
        assertTrue(project.containsKey("tests/test_example_service.py"));
        assertTrue(project.get("src/main.py").contains("from example.service.controller import MyController"));
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
    void visibleAvailableFeaturesDoNotExposeUnsupportedFeatures(PyronautAvailableFeatures availableFeatures) {
        List<String> visible = availableFeatures.getFeatures()
            .map(feature -> feature.getName())
            .toList();

        assertTrue(visible.containsAll(List.of("http-server-netty", "serde-jackson", "pyronaut-logback", "pyronaut-pytest")));
        assertFalse(visible.contains("serialization-jackson"));
        assertFalse(visible.contains("netty-server"));
        assertFalse(visible.contains("gradle"));
        assertFalse(visible.contains("java"));
        assertFalse(visible.contains("jackson-databind"));
        assertFalse(visible.contains("data-jpa"));
        assertFalse(visible.contains("hibernate-jpa"));
        assertFalse(visible.contains("hibernate-validator"));
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

    private static String dependenciesLine(String pyproject, String scope) {
        return pyproject.lines()
            .filter(line -> line.startsWith(scope + " = ["))
            .findFirst()
            .orElseThrow();
    }
}

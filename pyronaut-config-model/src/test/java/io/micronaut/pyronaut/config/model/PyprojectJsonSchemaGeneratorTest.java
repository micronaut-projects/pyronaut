package io.micronaut.pyronaut.config.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PyprojectJsonSchemaGeneratorTest {

    private final PyprojectJsonSchemaGenerator generator = new PyprojectJsonSchemaGenerator();

    @Test
    void generatesStrictPyronautSchemaUsingKebabCaseKeys() {
        String schema = generator.generate();

        assertTrue(schema.contains("\"$schema\": \"http://json-schema.org/draft-07/schema#\""));
        assertTrue(schema.contains("\"test-resources\""));
        assertTrue(schema.contains("\"docker\""));
        assertTrue(schema.contains("\"processor\""));
        assertTrue(schema.contains("\"incremental\""));
        assertTrue(schema.contains("\"python-incremental-mode\""));
        assertTrue(schema.contains("\"daemon\""));
        assertTrue(schema.contains("\"sources\""));
        assertTrue(schema.contains("\"core\""));
        assertTrue(schema.contains("\"platform\""));
        assertTrue(schema.contains("\"ide-stubs\""));
        assertTrue(schema.contains("\"control-panel\""));
        assertTrue(schema.contains("\"production-enabled\""));
        assertTrue(schema.contains("\"ide\""));
        assertTrue(schema.contains("\"python-test\""));
        assertTrue(schema.contains("\"test-resources\""));
        assertTrue(schema.contains("\"additional-resources\""));
        assertTrue(schema.contains("\"additional-test-resources\""));
        assertTrue(schema.contains("\"enum\": [\"vscode\", \"pycharm\"]"));
        assertTrue(schema.contains("\"image-name\""));
        assertTrue(schema.contains("\"destination-dir\""));
        assertTrue(schema.contains("\"exclude-patterns\""));
        assertTrue(schema.contains("\"dockerfile-native\""));
        assertTrue(schema.contains("\"static-native-base-image\""));
        assertTrue(schema.contains("\"base-image\""));
        assertTrue(schema.contains("\"fail-on-not-present\""));
        assertTrue(schema.contains("\"validate-dependency-injection\""));
        assertTrue(schema.contains("\"client-timeout\""));
        assertTrue(schema.contains("\"additionalProperties\": false"));
        assertTrue(schema.contains("\"default\": \"jvm\""));
        assertTrue(schema.contains("\"enum\": [\"jvm\", \"native\"]"));
        assertTrue(schema.contains("\"default\": \"jvm\""));
        assertTrue(schema.contains("\"enum\": [\"jvm\", \"native\"]"));
    }

    @Test
    void includesHiddenDeprecatedAliasPropertiesForCompatibility() {
        String schema = generator.generate();

        assertTrue(schema.contains("\"testResources\""));
        assertTrue(schema.contains("\"ideStubs\""));
        assertTrue(schema.contains("\"pythonTest\""));
        assertTrue(schema.contains("\"additionalResources\""));
        assertTrue(schema.contains("\"additionalTestResources\""));
        assertTrue(schema.contains("\"failOnNotPresent\""));
        assertTrue(schema.contains("\"imageName\""));
        assertTrue(schema.contains("\"destinationDir\""));
        assertTrue(schema.contains("\"excludePatterns\""));
        assertTrue(schema.contains("\"dockerfileNative\""));
        assertTrue(schema.contains("\"baseImage\""));
        assertTrue(schema.contains("\"deprecated\": true"));
        assertTrue(schema.contains("\"x-taplo\""));
        assertTrue(schema.contains("\"hidden\": true"));
    }
}

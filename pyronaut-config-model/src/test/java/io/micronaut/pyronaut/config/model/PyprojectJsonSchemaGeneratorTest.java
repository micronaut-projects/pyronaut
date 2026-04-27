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
        assertTrue(schema.contains("\"ide-stubs\""));
        assertTrue(schema.contains("\"ide\""));
        assertTrue(schema.contains("\"enum\": [\"vscode\", \"pycharm\"]"));
        assertTrue(schema.contains("\"image-name\""));
        assertTrue(schema.contains("\"destination-dir\""));
        assertTrue(schema.contains("\"exclude-patterns\""));
        assertTrue(schema.contains("\"dockerfile-native\""));
        assertTrue(schema.contains("\"static-native-base-image\""));
        assertTrue(schema.contains("\"fail-on-not-present\""));
        assertTrue(schema.contains("\"validate-dependency-injection\""));
        assertTrue(schema.contains("\"client-timeout\""));
        assertTrue(schema.contains("\"additionalProperties\": false"));
        assertTrue(schema.contains("\"default\": \"jvm\""));
        assertTrue(schema.contains("\"enum\": [\"jvm\", \"native\"]"));
    }

    @Test
    void includesHiddenDeprecatedAliasPropertiesForCompatibility() {
        String schema = generator.generate();

        assertTrue(schema.contains("\"testResources\""));
        assertTrue(schema.contains("\"ideStubs\""));
        assertTrue(schema.contains("\"failOnNotPresent\""));
        assertTrue(schema.contains("\"imageName\""));
        assertTrue(schema.contains("\"destinationDir\""));
        assertTrue(schema.contains("\"excludePatterns\""));
        assertTrue(schema.contains("\"dockerfileNative\""));
        assertTrue(schema.contains("\"deprecated\": true"));
        assertTrue(schema.contains("\"x-taplo\""));
        assertTrue(schema.contains("\"hidden\": true"));
    }
}

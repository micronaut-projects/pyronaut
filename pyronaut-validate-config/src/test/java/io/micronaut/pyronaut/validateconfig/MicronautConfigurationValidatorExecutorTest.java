package io.micronaut.pyronaut.validateconfig;

import io.micronaut.jsonschema.configuration.validator.ConfigurationError;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MicronautConfigurationValidatorExecutorTest {

    @Test
    void ignoresUnknownKeyCandidatesDerivedFromEnvironmentVariables() {
        ConfigurationError envCandidate = new ConfigurationError(
            "micronaut.server-port", "Property not present in schema", "System.env", "micronaut.server-port", "8080");
        ConfigurationError envValue = new ConfigurationError(
            "micronaut.server.port", "Value is not an integer", "System.env", "micronaut.server.port", "abc");
        ConfigurationError fileUnknownKey = new ConfigurationError(
            "micronaut.server.prot", "Property not present in schema", "application.toml", "micronaut.server.prot", "8080");

        Set<ConfigurationError> retained = MicronautConfigurationValidatorExecutor.withoutAmbiguousEnvironmentKeys(
            new LinkedHashSet<>(List.of(envCandidate, envValue, fileUnknownKey)));

        assertEquals(List.of(envValue, fileUnknownKey), List.copyOf(retained));
    }
}

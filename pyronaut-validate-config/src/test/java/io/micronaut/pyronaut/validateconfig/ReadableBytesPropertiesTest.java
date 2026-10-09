package io.micronaut.pyronaut.validateconfig;

import io.micronaut.core.convert.format.ReadableBytes;
import io.micronaut.jsonschema.configuration.validator.ConfigurationError;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadableBytesPropertiesTest {

    @Test
    void matchesReadableBytesTypeConverterGrammar() {
        for (String accepted : List.of("6MB", "6mb", "512kb", "1GB", "1024", "+1KB")) {
            assertTrue(ReadableBytesProperties.isReadableBytes(accepted), accepted);
        }
        for (String rejected : List.of("", "6 MB", "6TB", "6.5MB", "MB", "6B", "99999999999999999999KB")) {
            assertFalse(ReadableBytesProperties.isReadableBytes(rejected), rejected);
        }
        assertFalse(ReadableBytesProperties.isReadableBytes(6));
    }

    @Test
    void dropsIntegerErrorsOnlyForReadableBytesProperties() {
        ConfigurationError readable = error("micronaut.server.max-request-size", "Expected integer", "6MB");
        ConfigurationError notReadable = error("micronaut.server.port", "Expected integer", "8KB");
        ConfigurationError invalidSize = error("micronaut.server.max-request-buffer-size", "Expected integer", "6 parsecs");

        Set<ConfigurationError> retained = ReadableBytesProperties.withoutReadableBytesErrors(
            new LinkedHashSet<>(List.of(readable, notReadable, invalidSize)),
            property -> property.startsWith("micronaut.server.max-request"));

        assertEquals(List.of(notReadable, invalidSize), List.copyOf(retained));
        assertTrue(ReadableBytesProperties.hasCandidates(Set.of(readable)));
        assertFalse(ReadableBytesProperties.hasCandidates(Set.of(invalidSize)));
    }

    @Test
    void findsReadableBytesOnSettersGettersFieldsAndRecordComponents() {
        assertTrue(ReadableBytesProperties.isReadableBytes(SetterConfig.class, "maxSize"));
        assertFalse(ReadableBytesProperties.isReadableBytes(SetterConfig.class, "port"));
        assertTrue(ReadableBytesProperties.isReadableBytes(GetterConfig.class, "maxSize"));
        assertTrue(ReadableBytesProperties.isReadableBytes(FieldConfig.class, "maxSize"));
        assertTrue(ReadableBytesProperties.isReadableBytes(RecordConfig.class, "maxSize"));
        assertFalse(ReadableBytesProperties.isReadableBytes(null, "maxSize"));
    }

    @Test
    void matchesEachPropertyPrefixes() {
        assertTrue(ReadableBytesProperties.prefixMatches("micronaut.server", "micronaut.server"));
        assertTrue(ReadableBytesProperties.prefixMatches("micronaut.http.services.*", "micronaut.http.services.foo"));
        assertFalse(ReadableBytesProperties.prefixMatches("micronaut.http.services.*", "micronaut.http.services"));
        assertFalse(ReadableBytesProperties.prefixMatches("micronaut.server", "micronaut.server.multipart"));
    }

    private static ConfigurationError error(String property, String message, String value) {
        return new ConfigurationError(property, message, "application.toml", property, value);
    }

    public static class SetterConfig {
        public void setMaxSize(@ReadableBytes long maxSize) {
        }

        public void setPort(int port) {
        }
    }

    public interface GetterConfig {
        @ReadableBytes
        long getMaxSize();
    }

    public static class FieldConfig {
        @ReadableBytes
        private long maxSize;
    }

    public record RecordConfig(@ReadableBytes long maxSize) {
    }
}

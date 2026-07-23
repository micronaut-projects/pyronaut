package io.micronaut.pyronaut.install;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnnotationProcessorOptionDiscoveryTest {
    @Test
    void writesAnEmptyCacheAndRefreshesWhenClasspathChanges() throws Exception {
        Path root = Files.createTempDirectory("processor-option-cache");
        Path artifact = Files.writeString(root.resolve("processor.jar"), "first");

        AnnotationProcessorOptionDiscovery.refresh(root, List.of(artifact));
        Path cache = root.resolve(AnnotationProcessorOptionDiscovery.CACHE_FILE_NAME);
        Properties first = new Properties();
        try (var reader = Files.newBufferedReader(cache)) {
            first.load(reader);
        }
        assertTrue(first.containsKey("classpathFingerprint"));
        assertEquals("", first.getProperty("options"));

        Files.writeString(artifact, "second-content");
        AnnotationProcessorOptionDiscovery.refresh(root, List.of(artifact));
        Properties second = new Properties();
        try (var reader = Files.newBufferedReader(cache)) {
            second.load(reader);
        }
        assertNotEquals(first.getProperty("classpathFingerprint"), second.getProperty("classpathFingerprint"));
    }
}

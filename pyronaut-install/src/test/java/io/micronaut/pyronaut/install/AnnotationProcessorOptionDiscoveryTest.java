package io.micronaut.pyronaut.install;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void discoversOptionsWithoutSlf4jFallbackWarnings() throws Exception {
        Path root = Files.createTempDirectory("processor-option-logging");
        Path services = root.resolve("services");
        Path serviceFile = services.resolve("META-INF/services/io.micronaut.inject.visitor.TypeElementVisitor");
        Files.createDirectories(serviceFile.getParent());
        Files.writeString(serviceFile, LoggingTypeElementVisitor.class.getName() + "\n");
        // The processor classpath carries its own slf4j-api without a provider,
        // exactly like a resolved project build classpath.
        List<Path> classpath = List.of(
            services,
            location(LoggingTypeElementVisitor.class),
            location(io.micronaut.inject.visitor.TypeElementVisitor.class),
            location(io.micronaut.core.order.Ordered.class),
            location(org.slf4j.Logger.class)
        );

        PrintStream originalErr = System.err;
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
        try {
            AnnotationProcessorOptionDiscovery.refresh(root, classpath);
        } finally {
            System.setErr(originalErr);
        }

        Properties written = new Properties();
        try (var reader = Files.newBufferedReader(root.resolve(AnnotationProcessorOptionDiscovery.CACHE_FILE_NAME))) {
            written.load(reader);
        }
        assertTrue(written.getProperty("options").contains(LoggingTypeElementVisitor.OPTION), written.toString());
        String output = stderr.toString(StandardCharsets.UTF_8);
        assertFalse(output.contains("SLF4J"), output);
    }

    private static Path location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }
}

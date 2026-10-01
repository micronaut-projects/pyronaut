package io.micronaut.pyronaut.dev;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PyronautDevRunTest {
    @Test
    void nativeLauncherSkipsMacOsWatchServiceJars() {
        assertTrue(PyronautDevRun.isNativeUnsupportedJar("micronaut-runtime-osx-5.2.10.jar"));
        assertTrue(PyronautDevRun.isNativeUnsupportedJar("directory-watcher-0.19.1.jar"));
        assertFalse(PyronautDevRun.isNativeUnsupportedJar("micronaut-runtime-5.2.10.jar"));
        // On the JVM the jars stay on the classpath.
        assertFalse(new PyronautDevRun().includeClasspathEntry("/repo/micronaut-runtime-osx-5.2.10.jar"));
    }

    @Test
    void developmentRunUsesDevelopmentManifest(@TempDir Path project) throws Exception {
        Path pyronautDir = project.resolve("__pyronaut__");
        Files.createDirectories(pyronautDir.resolve("classes"));
        Files.createDirectories(project.resolve("config"));
        Path runtime = Files.createFile(project.resolve("runtime.jar"));
        Path development = Files.createFile(project.resolve("development.jar"));
        Files.writeString(pyronautDir.resolve("resolved-runtime-dependencies"), runtime + "\n", StandardCharsets.UTF_8);
        Files.writeString(pyronautDir.resolve("resolved-development-runtime-dependencies"), development + "\n", StandardCharsets.UTF_8);

        String previous = System.getProperty("micronaut.environments");
        try {
            System.setProperty("micronaut.environments", "dev");
            List<String> classpath = new TestableDevRun().resolve(project,
                    Path.of("__pyronaut__/classes"), Path.of("config"))
                .classpathUrls().stream().map(Object::toString).toList();
            assertTrue(classpath.stream().anyMatch(value -> value.contains(development.getFileName().toString())));
            assertTrue(classpath.stream().noneMatch(value -> value.contains(runtime.getFileName().toString())));
        } finally {
            if (previous == null) {
                System.clearProperty("micronaut.environments");
            } else {
                System.setProperty("micronaut.environments", previous);
            }
        }
    }

    private static final class TestableDevRun extends PyronautDevRun {
        private ResolvedProjectLayout resolve(Path project, Path classes, Path config) throws Exception {
            return resolveProjectLayout(project, classes, config);
        }
    }

    @Test
    void appliesTestResourcesPropertiesFromEnvironment() {
        String previousUri = System.getProperty("micronaut.test.resources.server.uri");
        try {
            System.clearProperty("micronaut.test.resources.server.uri");
            PyronautDevRun.applyTestResourcesProperties(Map.of(
                "MICRONAUT_TEST_RESOURCES_SERVER_URI", "http://localhost:18080"
            ));
            assertEquals("http://localhost:18080", System.getProperty("micronaut.test.resources.server.uri"));
        } finally {
            if (previousUri == null) {
                System.clearProperty("micronaut.test.resources.server.uri");
            } else {
                System.setProperty("micronaut.test.resources.server.uri", previousUri);
            }
        }
    }
}

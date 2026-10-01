package io.micronaut.pyronaut.dev;

import io.micronaut.dev.compile.SourceKind;
import io.micronaut.dev.manifest.DevManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PyronautDevReloadTest {

    @TempDir
    Path project;

    @Test
    void theResourceDirectoriesTheRunCommandResolvedAreReloadableAndTheJarsAreTheParentTier() throws Exception {
        Path classes = Files.createDirectories(project.resolve("__pyronaut__/classes"));
        // a --config-dir override, not the configured resources directory
        Path override = Files.createDirectories(project.resolve("other-config"));
        Path views = Files.createDirectories(project.resolve("views"));
        Path jar = Files.createDirectories(project.resolve("lib")).resolve("micronaut-context.jar");
        Files.writeString(jar, "");

        PyronautDevReload.Tiers tiers = PyronautDevReload.tiers(
            List.of(jar.toUri().toURL(), classes.toUri().toURL(), override.toUri().toURL(), views.toUri().toURL()),
            classes, java.util.Set.of(views));

        assertEquals(List.of(override), tiers.config());
        assertEquals(List.of(views), tiers.additional());
        assertEquals(List.of(jar), tiers.runtime());
    }

    @Test
    void inANativeLaunchTheProcessorsTheImageHoldsRunFromTheImage() {
        Path processors = project.resolve("repo");
        List<Path> path = List.of(
            processors.resolve("micronaut-inject-java-5.3.0-SNAPSHOT.jar"),
            processors.resolve("micronaut-inject-5.3.0-SNAPSHOT.jar"),
            processors.resolve("micronaut-data-processor-5.0.0.jar"),
            processors.resolve("acme-processor-1.0.jar"),
            processors.resolve("classes"));
        // outside a native launch the CLI names no provided artifact
        assertEquals(path, DevReloadFiles.withoutNativeProvidedArtifacts("", path));
        // micronaut-inject is provided, micronaut-inject-java is not: only the jar of the artifact itself goes
        assertEquals(
            List.of(path.get(0), path.get(3), path.get(4)),
            DevReloadFiles.withoutNativeProvidedArtifacts("io.micronaut:micronaut-inject, io.micronaut.data:micronaut-data-processor", path));
    }

    @Test
    void theNativeRunCommandCreatesTheDevelopmentRuntimeOnlyWhenTheCliAsksForIt() {
        String previous = System.getProperty("pyronaut.dev.reload");
        try {
            System.clearProperty("pyronaut.dev.reload");
            assertEquals(null, new PyronautDevRun().createDevelopmentRuntime());
            System.setProperty("pyronaut.dev.reload", "true");
            assertTrue(new PyronautDevRun().createDevelopmentRuntime() instanceof PyronautDevReload);
        } finally {
            if (previous == null) {
                System.clearProperty("pyronaut.dev.reload");
            } else {
                System.setProperty("pyronaut.dev.reload", previous);
            }
        }
    }

    @Test
    void theManifestCompilesTheJavaSourcesWithThePythonModuleIntoTheProcessedClasses() throws Exception {
        Path classes = Files.createDirectories(project.resolve("__pyronaut__/classes"));
        Path python = Files.createDirectories(project.resolve("src"));
        Path java = Files.createDirectories(project.resolve("src-java"));
        Path config = Files.createDirectories(project.resolve("config"));
        Path views = Files.createDirectories(project.resolve("views"));
        Path devDir = Files.createDirectories(project.resolve("__pyronaut__/micronaut-dev"));
        Path jar = project.resolve("lib/micronaut-context.jar");
        Path processor = project.resolve("lib/micronaut-inject-python.jar");

        Path file = PyronautDevReload.writeManifest(project, devDir, classes, python, java, List.of(config), List.of(views),
            List.of(jar), List.of(processor), List.of("-Amicronaut.openapi.enabled=true"));
        DevManifest manifest = DevManifest.load(file);

        assertEquals("pyronaut_application.PyronautMain", manifest.mainClass());
        assertEquals(List.of(classes), manifest.reloadableRoots());
        assertEquals(List.of(jar), manifest.compileClasspath());
        assertEquals(List.of(processor), manifest.processorPath());
        assertEquals(python, manifest.sourceRoots(SourceKind.PYTHON).getFirst().path());
        assertEquals(java, manifest.sourceRoots(SourceKind.JAVA).getFirst().path());
        // one output: the Python compiler compiles the Java sources with the module
        assertEquals(manifest.classOutput(SourceKind.PYTHON), manifest.classOutput(SourceKind.JAVA));
        assertEquals(List.of("-Amicronaut.openapi.enabled=true"), manifest.compileOptions(SourceKind.PYTHON));
        assertTrue(manifest.resourceRoots().stream().anyMatch(root -> root.path().equals(config)));
        assertTrue(manifest.resourceRoots().stream().anyMatch(root -> root.path().equals(views)));
    }
}

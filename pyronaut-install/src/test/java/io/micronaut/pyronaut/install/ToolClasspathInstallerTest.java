package io.micronaut.pyronaut.install;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ToolClasspathInstallerTest {
    @Test
    void materializesBundledMavenAndControlPanelArtifactsOffline(@TempDir Path tempDir) throws Exception {
        Path packagedTools = packagedTools(tempDir);
        Path localRepository = tempDir.resolve("repository");
        byte[] external = {1, 2, 3};
        byte[] controlPanel = {4, 5, 6};
        byte[] classified = {7, 8, 9};
        writeArtifact(localRepository, "com.example", "external", "1.0", external);
        writeArtifact(localRepository, "com.example", "control-panel", "2.0", controlPanel);
        writeArtifact(localRepository, "com.example", "classified", "3.0", "tests", classified);

        Path descriptor = writeTool(packagedTools, "pyronaut-run", List.of(
            "bundled\tmicronaut-pyronaut-run-1.0.jar",
            "maven\tcom.example\texternal\t1.0\tjar\t\texternal-1.0.jar",
            "maven\tcom.example\tclassified\t3.0\tjar\ttests\tclassified-3.0-tests.jar",
            "control-panel\tcom.example\tcontrol-panel\t2.0\tjar\t\tcontrol-panel-2.0.jar"
        ));
        Path cacheBase = tempDir.resolve("cache");
        ToolClasspathInstaller installer = new ToolClasspathInstaller(new MavenClasspathResolver(), packagedTools, cacheBase);

        Path current = installer.install(null, localRepository, true, false);

        assertTrue(Files.isSymbolicLink(current));
        assertTrue(Files.isRegularFile(current.resolve("tools/pyronaut-run/bin/pyronaut-run")));
        assertArrayEquals(external, Files.readAllBytes(current.resolve("tools/shared/lib/external-1.0.jar")));
        assertArrayEquals(classified, Files.readAllBytes(current.resolve("tools/shared/lib/classified-3.0-tests.jar")));
        assertArrayEquals(controlPanel,
            Files.readAllBytes(current.resolve("tools/pyronaut-dev/lib/control-panel/control-panel-2.0.jar")));
        assertEquals(Files.readAllLines(descriptor), Files.readAllLines(
            current.resolve("tools/pyronaut-run/bin/" + ToolClasspathInstaller.DESCRIPTOR_FILE)));
        Properties metadata = new Properties();
        try (var input = Files.newInputStream(current.resolve("tool-runtime.properties"))) {
            metadata.load(input);
        }
        assertEquals("1.2.3", metadata.getProperty("sdk.version"));
        assertEquals(64, metadata.getProperty("descriptor.sha256").length());
        assertEquals(localRepository.toAbsolutePath().normalize().toString(), metadata.getProperty("local.repository"));
    }

    @Test
    void repairsCorruptLayoutsAndPublishesRefreshesAtomically(@TempDir Path tempDir) throws Exception {
        Path packagedTools = packagedTools(tempDir);
        writeTool(packagedTools, "pyronaut-run", List.of("bundled\tmicronaut-pyronaut-run-1.0.jar"));
        ToolClasspathInstaller installer = new ToolClasspathInstaller(
            new MavenClasspathResolver(), packagedTools, tempDir.resolve("cache")
        );

        Path current = installer.install(null, tempDir.resolve("repository"), true, false);
        Path firstGeneration = Files.readSymbolicLink(current);
        installer.install(null, tempDir.resolve("repository"), true, false);
        assertEquals(firstGeneration, Files.readSymbolicLink(current));
        Files.delete(current.resolve("tools/shared/lib/micronaut-pyronaut-run-1.0.jar"));
        installer.install(null, tempDir.resolve("repository"), true, false);
        Path repairedGeneration = Files.readSymbolicLink(current);
        assertNotEquals(firstGeneration, repairedGeneration);
        assertTrue(Files.isRegularFile(current.resolve("tools/shared/lib/micronaut-pyronaut-run-1.0.jar")));

        Path corruptTarget = tempDir.resolve("corrupt.jar");
        Files.write(corruptTarget, new byte[]{0});
        Path cachedArtifact = current.resolve("tools/shared/lib/micronaut-pyronaut-run-1.0.jar");
        Files.delete(cachedArtifact);
        Files.createSymbolicLink(cachedArtifact, corruptTarget);
        installer.install(null, tempDir.resolve("repository"), true, false);
        Path correctedGeneration = Files.readSymbolicLink(current);
        assertNotEquals(repairedGeneration, correctedGeneration);
        assertArrayEquals(new byte[]{9, 8, 7}, Files.readAllBytes(cachedArtifact));

        installer.install(null, tempDir.resolve("repository"), true, true);
        assertNotEquals(correctedGeneration, Files.readSymbolicLink(current));
    }

    @Test
    void serializesConcurrentCreation(@TempDir Path tempDir) throws Exception {
        Path packagedTools = packagedTools(tempDir);
        writeTool(packagedTools, "pyronaut-run", List.of("bundled\tmicronaut-pyronaut-run-1.0.jar"));
        ToolClasspathInstaller installer = new ToolClasspathInstaller(
            new MavenClasspathResolver(), packagedTools, tempDir.resolve("cache")
        );
        Callable<Path> install = () -> installer.install(null, tempDir.resolve("repository"), true, false);

        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Path> results = executor.invokeAll(List.of(install, install)).stream()
                .map(future -> {
                    try {
                        return future.get();
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                })
                .toList();
            assertEquals(results.get(0), results.get(1));
            assertTrue(Files.isRegularFile(results.get(0).resolve("tools/pyronaut-run/bin/pyronaut-run")));
        }
    }

    @Test
    void reportsResolutionProgress(@TempDir Path tempDir) throws Exception {
        Path packagedTools = packagedTools(tempDir);
        Path localRepository = tempDir.resolve("repository");
        writeArtifact(localRepository, "com.example", "external", "1.0", new byte[]{1, 2, 3});
        writeTool(packagedTools, "pyronaut-run", List.of(
            "maven\tcom.example\texternal\t1.0\tjar\t\texternal-1.0.jar"
        ));
        List<String> events = new ArrayList<>();
        DependencyProgressListener listener = new DependencyProgressListener() {
            @Override public void reset() { events.add("reset"); }
            @Override public void begin() { events.add("begin"); }
            @Override public void artifactPlanned(String name) { events.add("planned:" + name); }
            @Override public void artifactStarted(String name) { events.add("started:" + name); }
            @Override public void artifactTransferFinished(String name) { events.add("transferred:" + name); }
            @Override public void artifactCompleted(String name) { events.add("completed:" + name); }
            @Override public void artifactFailed(String name) { events.add("failed:" + name); }
        };
        ToolClasspathInstaller installer = new ToolClasspathInstaller(
            new MavenClasspathResolver(),
            packagedTools,
            tempDir.resolve("cache")
        );

        installer.install(null, localRepository, true, false, listener);

        assertTrue(events.contains("reset"));
        assertTrue(events.contains("begin"));
        assertTrue(events.stream().anyMatch(event -> event.startsWith("planned:com.example:external:jar:1.0")));
        assertTrue(events.stream().anyMatch(event -> event.startsWith("completed:com.example:external:jar:1.0")));
    }

    @Test
    void toolResolutionUsesConfiguredProxyAndReportsItOnFailure(@TempDir Path tempDir) throws Exception {
        Path packagedTools = packagedTools(tempDir);
        writeTool(packagedTools, "pyronaut-run", List.of(
            "maven\tcom.example\tmissing\t1.0\tjar\t\tmissing-1.0.jar"
        ));
        MavenClasspathResolver resolver = new MavenClasspathResolver(new ProxyConfigurationLoader(
            Map.of("HTTPS_PROXY", "http://proxy.example:3128"),
            tempDir.resolve("missing-settings.toml"),
            tempDir.resolve("missing-settings.xml")
        ));
        ToolClasspathInstaller installer = new ToolClasspathInstaller(resolver, packagedTools, tempDir.resolve("cache"));

        Exception failure = assertThrows(Exception.class,
            () -> installer.install(null, tempDir.resolve("repository"), true, false));

        assertTrue(failure.getMessage().contains("proxy environment -> http://proxy.example:3128"));
    }

    @Test
    void rejectsArtifactsWithConflictingContentsAndFilenames(@TempDir Path tempDir) throws Exception {
        Path packagedTools = packagedTools(tempDir);
        Path localRepository = tempDir.resolve("repository");
        writeArtifact(localRepository, "com.one", "duplicate", "1.0", new byte[]{1});
        writeArtifact(localRepository, "com.two", "duplicate", "1.0", new byte[]{2});
        writeTool(packagedTools, "pyronaut-run", List.of(
            "maven\tcom.one\tduplicate\t1.0\tjar\t\tduplicate-1.0.jar",
            "maven\tcom.two\tduplicate\t1.0\tjar\t\tduplicate-1.0.jar"
        ));
        ToolClasspathInstaller installer = new ToolClasspathInstaller(
            new MavenClasspathResolver(), packagedTools, tempDir.resolve("cache")
        );

        Exception failure = assertThrows(Exception.class,
            () -> installer.install(null, localRepository, true, false));
        assertTrue(failure.getMessage().contains("Conflicting Pyronaut tool artifacts"));
    }

    @Test
    void bundledArtifactWinsWhenMavenDescriptorUsesTheSameFilename(@TempDir Path tempDir) throws Exception {
        Path packagedTools = packagedTools(tempDir);
        Path packagedArtifact = packagedTools.resolve("shared/lib/duplicate-1.0.jar");
        byte[] contents = {1, 2, 3};
        Files.write(packagedArtifact, contents);

        Path localRepository = tempDir.resolve("repository");
        writeArtifact(localRepository, "com.example", "duplicate", "1.0", contents);
        writeTool(packagedTools, "pyronaut-run", List.of(
            "maven\tcom.example\tduplicate\t1.0\tjar\t\tduplicate-1.0.jar"
        ));
        writeTool(packagedTools, "pyronaut-test", List.of(
            "bundled\tduplicate-1.0.jar"
        ));

        ToolClasspathInstaller installer = new ToolClasspathInstaller(
            new MavenClasspathResolver(), packagedTools, tempDir.resolve("cache")
        );
        Path current = installer.install(null, localRepository, true, false);
        Path cachedArtifact = current.resolve("tools/shared/lib/duplicate-1.0.jar");
        Path firstGeneration = Files.readSymbolicLink(current);

        assertTrue(Files.isSymbolicLink(cachedArtifact));
        assertEquals(
            packagedArtifact.toAbsolutePath().normalize(),
            cachedArtifact.getParent().resolve(Files.readSymbolicLink(cachedArtifact)).normalize()
        );

        installer.install(null, localRepository, true, false);
        assertEquals(firstGeneration, Files.readSymbolicLink(current));
    }

    private static Path packagedTools(Path tempDir) throws Exception {
        Path tools = tempDir.resolve("packaged-tools");
        Files.createDirectories(tools.resolve("shared/lib"));
        Files.writeString(tools.resolve(ToolClasspathInstaller.RUNTIME_PROPERTIES_FILE), "sdk.version=1.2.3\n");
        Files.write(tools.resolve("shared/lib/micronaut-pyronaut-run-1.0.jar"), new byte[]{9, 8, 7});
        return tools;
    }

    private static Path writeTool(Path tools, String command, List<String> descriptorLines) throws Exception {
        Path bin = tools.resolve(command).resolve("bin");
        Files.createDirectories(bin);
        Files.writeString(bin.resolve(command), "#!/bin/sh\n");
        Path descriptor = bin.resolve(ToolClasspathInstaller.DESCRIPTOR_FILE);
        Files.writeString(descriptor, String.join("\n", descriptorLines) + "\n");
        return descriptor;
    }

    private static void writeArtifact(Path repository,
                                      String group,
                                      String artifact,
                                      String version,
                                      byte[] content) throws Exception {
        writeArtifact(repository, group, artifact, version, null, content);
    }

    private static void writeArtifact(Path repository,
                                      String group,
                                      String artifact,
                                      String version,
                                      String classifier,
                                      byte[] content) throws Exception {
        Path directory = repository.resolve(group.replace('.', '/')).resolve(artifact).resolve(version);
        Files.createDirectories(directory);
        String suffix = classifier == null ? "" : "-" + classifier;
        Files.write(directory.resolve(artifact + "-" + version + suffix + ".jar"), content);
    }
}

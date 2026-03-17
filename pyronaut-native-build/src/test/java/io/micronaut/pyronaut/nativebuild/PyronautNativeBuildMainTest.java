package io.micronaut.pyronaut.nativebuild;

import io.micronaut.pyronaut.config.model.PyprojectModelReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PyronautNativeBuildMainTest {

    @TempDir
    Path tempDir;

    @Test
    void addsMetadataConfigurationDirectoriesWhenRepositoryContainsModuleMetadata() throws Exception {
        Path project = prepareProject("""
            [project]
            name = "demo"

            [tool.pyronaut]
            version = "5.0.0-SNAPSHOT"

            [tool.pyronaut.dependencies]
            runtime = ["org.example:demo:1.0"]
            """);
        overwriteRuntimeManifest(project, List.of(
            "/repo/m2-repository/org/example/demo/1.0/demo-1.0.jar",
            "/repo/m2-repository/ch/qos/logback/logback-classic/1.4.9/logback-classic-1.4.9.jar"
        ));

        List<List<String>> executed = new ArrayList<>();
        var invoker = (PyronautNativeBuildMain.NativeImageInvoker) (command, workingDirectory) -> {
            executed.add(List.copyOf(command));
            return 0;
        };
        var downloader = (PyronautNativeBuildMain.MetadataRepositoryDownloader) (source, extractedRoot) -> {
            createRequiredSchemas(extractedRoot);
            createModuleMetadata(extractedRoot, "org.example", "demo", "1.0.0", Set.of("1.0"), true);
            createModuleMetadata(extractedRoot, "ch.qos.logback", "logback-classic", "1.4.9", Set.of("1.4.9"), true);
        };

        PyronautNativeBuildMain command = new PyronautNativeBuildMain(new PyprojectModelReader(), invoker, downloader);
        int exit = new CommandLine(command).execute("--project-dir", project.toString(), "--native-image-executable", "/tmp/native-image");

        assertEquals(0, exit);
        assertEquals(1, executed.size());
        List<String> nativeCommand = executed.getFirst();
        assertEquals("/tmp/native-image", nativeCommand.getFirst());
        String directories = nativeCommand.stream()
            .filter(flag -> flag.startsWith("-H:ConfigurationFileDirectories="))
            .findFirst()
            .orElseThrow();
        assertTrue(directories.contains("org.example/demo/1.0.0"));
        assertTrue(directories.contains("ch.qos.logback/logback-classic/1.4.9"));
        assertFalse(directories.contains("ch.qos.logback/logback-classic/1.4.1"));
        assertFalse(directories.contains("ch.qos.logback/logback-classic/1.2.11"));
    }

    @Test
    void skipsMetadataConfigurationWhenDisabledInPyproject() throws Exception {
        Path project = prepareProject("""
            [project]
            name = "demo"

            [tool.pyronaut]
            version = "5.0.0-SNAPSHOT"

            [tool.pyronaut.dependencies]
            runtime = ["org.example:demo:1.0"]

            [tool.pyronaut.build.metadata]
            enabled = false
            """);

        List<List<String>> executed = new ArrayList<>();
        var invoker = (PyronautNativeBuildMain.NativeImageInvoker) (command, workingDirectory) -> {
            executed.add(List.copyOf(command));
            return 0;
        };
        var downloader = (PyronautNativeBuildMain.MetadataRepositoryDownloader) (source, extractedRoot) -> {
            throw new IOException("should not download when disabled");
        };

        PyronautNativeBuildMain command = new PyronautNativeBuildMain(new PyprojectModelReader(), invoker, downloader);
        int exit = new CommandLine(command).execute("--project-dir", project.toString(), "--native-image-executable", "/tmp/native-image");

        assertEquals(0, exit);
        assertEquals(1, executed.size());
        assertFalse(executed.getFirst().stream().anyMatch(flag -> flag.startsWith("-H:ConfigurationFileDirectories=")));
    }

    @Test
    void failsWhenMetadataDownloadFailsWithoutCache() throws Exception {
        Path project = prepareProject("""
            [project]
            name = "demo"

            [tool.pyronaut]
            version = "5.0.0-SNAPSHOT"

            [tool.pyronaut.dependencies]
            runtime = ["org.example:demo:1.0"]
            """);

        var invoker = (PyronautNativeBuildMain.NativeImageInvoker) (command, workingDirectory) -> 0;
        var downloader = (PyronautNativeBuildMain.MetadataRepositoryDownloader) (source, extractedRoot) -> {
            throw new IOException("download failed");
        };

        PyronautNativeBuildMain command = new PyronautNativeBuildMain(new PyprojectModelReader(), invoker, downloader);
        int exit = new CommandLine(command).execute("--project-dir", project.toString(), "--native-image-executable", "/tmp/native-image");

        assertEquals(8, exit);
    }

    @Test
    void verboseFlagIsForwardedToNativeImage() throws Exception {
        Path project = prepareProject("""
            [project]
            name = "demo"

            [tool.pyronaut]
            version = "5.0.0-SNAPSHOT"

            [tool.pyronaut.dependencies]
            runtime = ["org.example:demo:1.0"]
            """);

        List<List<String>> executed = new ArrayList<>();
        var invoker = (PyronautNativeBuildMain.NativeImageInvoker) (command, workingDirectory) -> {
            executed.add(List.copyOf(command));
            return 0;
        };
        var downloader = (PyronautNativeBuildMain.MetadataRepositoryDownloader) (source, extractedRoot) -> {
            createRequiredSchemas(extractedRoot);
            createModuleMetadata(extractedRoot, "org.example", "demo", "1.0.0", Set.of("1.0"), true);
        };

        PyronautNativeBuildMain command = new PyronautNativeBuildMain(new PyprojectModelReader(), invoker, downloader);
        int exit = new CommandLine(command).execute(
            "--project-dir",
            project.toString(),
            "--native-image-executable",
            "/tmp/native-image",
            "--verbose"
        );
        assertEquals(0, exit);

        assertEquals(1, executed.size());
        List<String> nativeCommand = executed.getFirst();
        assertTrue(nativeCommand.contains("--verbose"));
    }

    @Test
    void unmatchedArgsAreForwardedToNativeImage() throws Exception {
        Path project = prepareProject("""
            [project]
            name = "demo"

            [tool.pyronaut]
            version = "5.0.0-SNAPSHOT"

            [tool.pyronaut.dependencies]
            runtime = ["org.example:demo:1.0"]
            """);

        List<List<String>> executed = new ArrayList<>();
        var invoker = (PyronautNativeBuildMain.NativeImageInvoker) (command, workingDirectory) -> {
            executed.add(List.copyOf(command));
            return 0;
        };
        var downloader = (PyronautNativeBuildMain.MetadataRepositoryDownloader) (source, extractedRoot) -> {
            createRequiredSchemas(extractedRoot);
            createModuleMetadata(extractedRoot, "org.example", "demo", "1.0.0", Set.of("1.0"), true);
        };

        PyronautNativeBuildMain command = new PyronautNativeBuildMain(new PyprojectModelReader(), invoker, downloader);
        int exit = new CommandLine(command).execute(
            "--project-dir",
            project.toString(),
            "--native-image-executable",
            "/tmp/native-image",
            "--trace-object-instantiation=ch.qos.logback.classic.Logger",
            "--initialize-at-run-time",
            "io.netty.util.ResourceLeakDetector"
        );
        assertEquals(0, exit);

        assertEquals(1, executed.size());
        List<String> nativeCommand = executed.getFirst();
        assertTrue(nativeCommand.contains("--trace-object-instantiation=ch.qos.logback.classic.Logger"));
        assertTrue(nativeCommand.contains("--initialize-at-run-time"));
        assertTrue(nativeCommand.contains("io.netty.util.ResourceLeakDetector"));
    }

    private Path prepareProject(String pyprojectContent) throws IOException {
        Path project = tempDir.resolve("project-" + System.nanoTime());
        Path pyronautDir = project.resolve("__pyronaut__");
        Path classesDir = pyronautDir.resolve("classes");
        Files.createDirectories(classesDir);
        Files.createDirectories(pyronautDir);
        Files.writeString(project.resolve("pyproject.toml"), pyprojectContent);
        Files.writeString(pyronautDir.resolve("resolved-runtime-dependencies"), "/tmp/runtime.jar\n");
        return project;
    }

    private static void overwriteRuntimeManifest(Path project, List<String> entries) throws IOException {
        Path manifest = project.resolve("__pyronaut__").resolve("resolved-runtime-dependencies");
        Files.writeString(manifest, String.join("\n", entries) + "\n");
    }

    private static void createRequiredSchemas(Path repositoryRoot) throws IOException {
        Path schemasDir = repositoryRoot.resolve("schemas");
        Files.createDirectories(schemasDir);
        Files.writeString(schemasDir.resolve("library-and-framework-list-schema-v1.0.0.json"), "{}\n");
        Files.writeString(schemasDir.resolve("metadata-library-index-schema-v1.0.0.json"), "{}\n");
        Files.writeString(schemasDir.resolve("metadata-root-index-schema-v1.0.0.json"), "{}\n");
        Files.writeString(repositoryRoot.resolve("index.json"), "[]\n");
    }

    private static void createModuleMetadata(Path repositoryRoot,
                                             String group,
                                             String artifact,
                                             String metadataVersion,
                                             Set<String> testedVersions,
                                             boolean latest) throws IOException {
        String module = group + ":" + artifact;
        String relativeModuleDir = "metadata/" + group + "/" + artifact;

        Path rootIndex = repositoryRoot.resolve("index.json");
        String rootEntry = """
            {
              \"module\": \"%s\",
              \"directory\": \"%s\"
            }
            """.formatted(module, relativeModuleDir).trim();
        appendJsonArrayEntry(rootIndex, rootEntry);

        Path moduleDir = repositoryRoot.resolve(relativeModuleDir);
        Files.createDirectories(moduleDir.resolve(metadataVersion));

        String tested = testedVersions.stream()
            .sorted()
            .map(version -> "\"" + version + "\"")
            .reduce((a, b) -> a + ", " + b)
            .orElse("");
        String moduleIndexEntry = """
            {
              \"module\": \"%s\",
              \"metadata-version\": \"%s\",
              \"tested-versions\": [%s],
              \"latest\": %s
            }
            """.formatted(module, metadataVersion, tested, latest).trim();
        appendJsonArrayEntry(moduleDir.resolve("index.json"), moduleIndexEntry);

        Files.writeString(moduleDir.resolve(metadataVersion).resolve("reachability-metadata.json"), "{}\n");
    }

    private static void appendJsonArrayEntry(Path file, String jsonObject) throws IOException {
        if (!Files.exists(file)) {
            Files.writeString(file, "[\n" + jsonObject + "\n]\n");
            return;
        }
        String existing = Files.readString(file).trim();
        if (existing.equals("[]")) {
            Files.writeString(file, "[\n" + jsonObject + "\n]\n");
            return;
        }
        int closing = existing.lastIndexOf(']');
        String prefix = existing.substring(0, closing).trim();
        if (prefix.endsWith("[")) {
            Files.writeString(file, "[\n" + jsonObject + "\n]\n");
            return;
        }
        Files.writeString(file, prefix + ",\n" + jsonObject + "\n]\n");
    }
}

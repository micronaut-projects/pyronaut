package io.micronaut.pyronaut.nativebuild;

import io.micronaut.pyronaut.config.model.PyprojectModelReader;
import io.micronaut.pyronaut.run.PyronautRunMain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
    void buildsNativeImageWithFixedPyronautRunMainEntryPoint() throws Exception {
        Path project = prepareProject("""
            [project]
            name = "demo"

            [tool.pyronaut.build.metadata]
            enabled = false
            """);

        List<List<String>> executed = new ArrayList<>();
        var invoker = (PyronautNativeBuildMain.NativeImageInvoker) (command, workingDirectory) -> {
            executed.add(List.copyOf(command));
            return 0;
        };
        var downloader = (PyronautNativeBuildMain.MetadataRepositoryDownloader) (source, extractedRoot) -> {
            throw new IOException("metadata must not be downloaded");
        };

        PyronautNativeBuildMain command = new PyronautNativeBuildMain(new PyprojectModelReader(), invoker, downloader);
        int exit = new CommandLine(command).execute("--project-dir", project.toString(), "--native-image-executable", "/tmp/native-image");

        assertEquals(0, exit);
        List<String> nativeCommand = executed.getFirst();
        assertEquals(PyronautRunMain.class.getName(), nativeCommand.get(nativeCommand.size() - 2));
        int classpathIndex = nativeCommand.indexOf("-cp");
        assertTrue(classpathIndex >= 0);
        assertTrue(nativeCommand.get(classpathIndex + 1).contains(PyronautRunMain.class.getProtectionDomain().getCodeSource().getLocation().getPath()));
        assertTrue(nativeCommand.contains("-H:+RuntimeClassLoading"));
        assertTrue(nativeCommand.contains("-H:+AllowJRTFileSystem"));
    }

    @Test
    void excludesPythonOnlyRuntimeArtifactsFromJavaImages() throws Exception {
        Path project = prepareProject("""
            [project]
            name = "demo"

            [tool.pyronaut.build.metadata]
            enabled = false
            """);
        Path pythonRuntime = createJar(project.resolve("__pyronaut__/m2-repository/io/micronaut/micronaut-context-python-5.2.0.jar"));
        Path javaRuntime = createJar(project.resolve("__pyronaut__/m2-repository/io/micronaut/micronaut-context-5.2.0.jar"));
        overwriteRuntimeManifest(project, List.of(pythonRuntime.toString(), javaRuntime.toString()));
        List<List<String>> executed = new ArrayList<>();
        var invoker = (PyronautNativeBuildMain.NativeImageInvoker) (command, workingDirectory) -> {
            executed.add(List.copyOf(command));
            return 0;
        };
        var downloader = (PyronautNativeBuildMain.MetadataRepositoryDownloader) (source, extractedRoot) -> {
            throw new IOException("metadata must not be downloaded");
        };

        PyronautNativeBuildMain command = new PyronautNativeBuildMain(new PyprojectModelReader(), invoker, downloader);
        assertEquals(0, new CommandLine(command).execute("--project-dir", project.toString(), "--native-image-executable", "/tmp/native-image"));
        String classpath = executed.getFirst().get(executed.getFirst().indexOf("-cp") + 1);
        assertTrue(classpath.contains(javaRuntime.toAbsolutePath().toString()));
        assertFalse(classpath.contains(pythonRuntime.toAbsolutePath().toString()));
    }

    @Test
    void usesExplicitUserPackagesForClosedWorldImagesOnly() throws Exception {
        Path project = prepareProject("""
            [project]
            name = "demo"

            [tool.pyronaut.build.metadata]
            enabled = false
            """);
        List<List<String>> executed = new ArrayList<>();
        var invoker = (PyronautNativeBuildMain.NativeImageInvoker) (command, workingDirectory) -> {
            executed.add(List.copyOf(command));
            return 0;
        };
        var downloader = (PyronautNativeBuildMain.MetadataRepositoryDownloader) (source, extractedRoot) -> {
            throw new IOException("metadata must not be downloaded");
        };

        PyronautNativeBuildMain command = new PyronautNativeBuildMain(new PyprojectModelReader(), invoker, downloader);
        assertEquals(0, new CommandLine(command).execute(
            "--project-dir", project.toString(),
            "--user-package", "example.app",
            "--user-package", "example.app",
            "--user-package", "example.other"
        ));
        List<String> nativeCommand = executed.getFirst();
        assertEquals(1, nativeCommand.stream().filter("-H:Preserve=package=example.app.*"::equals).count());
        assertTrue(nativeCommand.contains("-H:Preserve=package=example.other.*"));

        executed.clear();
        Path runtimeJar = createJar(project.resolve("__pyronaut__/m2-repository/example/runtime.jar"));
        overwriteRuntimeManifest(project, List.of(runtimeJar.toString()));
        assertEquals(0, new CommandLine(command).execute(
            "--project-dir", project.toString(),
            "--base-image",
            "--user-package", "example.app"
        ));
        assertFalse(executed.getFirst().contains("-H:Preserve=package=example.app.*"));
    }

    @Test
    void buildsReusableCremaBaseImageWithReportAndSbom() throws Exception {
        Path project = prepareProject("""
            [project]
            name = "demo"

            [tool.pyronaut.build.metadata]
            enabled = false
            """);
        Path runtimeJar = createJar(project.resolve("__pyronaut__/m2-repository/example/runtime.jar"));
        overwriteRuntimeManifest(project, List.of(runtimeJar.toString()));

        List<List<String>> executed = new ArrayList<>();
        var invoker = (PyronautNativeBuildMain.NativeImageInvoker) (command, workingDirectory) -> {
            executed.add(List.copyOf(command));
            return 0;
        };
        var downloader = (PyronautNativeBuildMain.MetadataRepositoryDownloader) (source, extractedRoot) -> {
            throw new IOException("metadata must not be downloaded");
        };

        PyronautNativeBuildMain command = new PyronautNativeBuildMain(new PyprojectModelReader(), invoker, downloader);
        int exit = new CommandLine(command).execute(
            "--project-dir", project.toString(),
            "--native-image-executable", "/tmp/native-image",
            "--base-image"
        );

        assertEquals(0, exit);
        List<String> nativeCommand = executed.getFirst();
        assertEquals("/tmp/native-image", nativeCommand.getFirst());
        assertTrue(nativeCommand.contains("-H:+RuntimeClassLoading"));
        assertTrue(nativeCommand.contains("--emit"));
        assertTrue(nativeCommand.contains("build-report"));
        assertTrue(nativeCommand.contains("-H:IncludeSBOM=embed,export"));
        assertFalse(nativeCommand.contains("--no-fallback"));
        assertTrue(nativeCommand.contains(PyronautRunMain.class.getName()));
        assertTrue(nativeCommand.get(nativeCommand.indexOf("-cp") + 1).contains(runtimeJar.toString()));
    }

    @Test
    void buildsBundledBaseWithoutProjectRuntimeDependencies() throws Exception {
        Path project = prepareProject("""
            [project]
            name = "demo"

            [tool.pyronaut.build.metadata]
            enabled = false
            """);
        Path runtimeJar = createJar(project.resolve("__pyronaut__/m2-repository/example/runtime.jar"));
        overwriteRuntimeManifest(project, List.of(runtimeJar.toString()));
        List<List<String>> executed = new ArrayList<>();
        var invoker = (PyronautNativeBuildMain.NativeImageInvoker) (command, workingDirectory) -> {
            executed.add(List.copyOf(command));
            return 0;
        };
        var downloader = (PyronautNativeBuildMain.MetadataRepositoryDownloader) (source, extractedRoot) -> {
            throw new IOException("metadata must not be downloaded");
        };

        PyronautNativeBuildMain command = new PyronautNativeBuildMain(new PyprojectModelReader(), invoker, downloader);
        assertEquals(0, new CommandLine(command).execute(
            "--project-dir", project.toString(),
            "--default-base-image"
        ));
        String classpath = executed.getFirst().get(executed.getFirst().indexOf("-cp") + 1);
        assertFalse(classpath.contains(runtimeJar.toString()));
    }

    @Test
    void rejectsMainClassOverride() throws Exception {
        Path project = prepareProject("""
            [project]
            name = "demo"

            [tool.pyronaut.build.metadata]
            enabled = false
            """);

        var invoker = (PyronautNativeBuildMain.NativeImageInvoker) (command, workingDirectory) -> 0;
        var downloader = (PyronautNativeBuildMain.MetadataRepositoryDownloader) (source, extractedRoot) -> {
            throw new IOException("metadata must not be downloaded");
        };

        PyronautNativeBuildMain command = new PyronautNativeBuildMain(new PyprojectModelReader(), invoker, downloader);
        int exit = new CommandLine(command).execute("--project-dir", project.toString(), "--main-class=example.Main");

        assertEquals(8, exit);
    }

    @Test
    void excludesBundledNativeImageConfigWhenExternalMetadataApplies() throws Exception {
        Path project = prepareProject("""
            [project]
            name = "demo"

            [tool.pyronaut]

            [tool.pyronaut.dependencies]
            runtime = ["io.netty:netty-handler:4.2.10.Final", "org.example:demo:1.0"]
            """);
        Path nettyJar = createJar(
            project.resolve("__pyronaut__/m2-repository/io/netty/netty-handler/4.2.10.Final/netty-handler-4.2.10.Final.jar"),
            "META-INF/native-image/io.netty/netty-handler/native-image.properties"
        );
        overwriteRuntimeManifest(project, List.of(
            nettyJar.toString(),
            "/repo/m2-repository/org/example/demo/1.0/demo-1.0.jar"
        ));

        List<List<String>> executed = new ArrayList<>();
        var invoker = (PyronautNativeBuildMain.NativeImageInvoker) (command, workingDirectory) -> {
            executed.add(List.copyOf(command));
            return 0;
        };
        var downloader = (PyronautNativeBuildMain.MetadataRepositoryDownloader) (source, extractedRoot) -> {
            createRequiredSchemas(extractedRoot);
            createModuleMetadata(extractedRoot, "io.netty", "netty-handler", "4.1.80.Final", Set.of("4.2.10.Final"), true);
            createModuleMetadata(extractedRoot, "org.example", "demo", "1.0.0", Set.of("1.0"), true);
        };

        PyronautNativeBuildMain command = new PyronautNativeBuildMain(new PyprojectModelReader(), invoker, downloader);
        int exit = new CommandLine(command).execute("--project-dir", project.toString(), "--native-image-executable", "/tmp/native-image");

        assertEquals(0, exit);
        List<String> nativeCommand = executed.getFirst();
        int excludeIndex = nativeCommand.indexOf("--exclude-config");
        assertTrue(excludeIndex >= 0);
        assertEquals(".*\\Q" + nettyJar.getFileName() + "\\E.*", nativeCommand.get(excludeIndex + 1));
        assertEquals("^/META-INF/native-image/.*", nativeCommand.get(excludeIndex + 2));
    }

    @Test
    void addsProjectConfigResourcesToClasspathAndConfigurationDirectories() throws Exception {
        Path project = prepareProject("""
            [project]
            name = "demo"

            [tool.pyronaut]

            [tool.pyronaut.dependencies]
            runtime = ["org.example:demo:1.0"]
            """);
        Path configDir = project.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("application.toml"), "micronaut.application.name = \"demo\"\n");
        Files.createDirectories(configDir.resolve("nested"));
        Files.writeString(configDir.resolve("nested").resolve("extra.txt"), "hello\n");
        Path dependencyJar = createJar(
            project.resolve("__pyronaut__/m2-repository/io/example/runtime-lib/1.0/runtime-lib-1.0.jar"),
            "META-INF/GRAALPY-VFS/micronaut-application/src/logback/__init__.py"
        );
        overwriteRuntimeManifest(project, List.of(dependencyJar.toString()));
        Path graalPyVfs = project.resolve("__pyronaut__").resolve("classes").resolve("META-INF").resolve("GRAALPY-VFS").resolve("micronaut-application");
        Files.createDirectories(graalPyVfs);
        Files.writeString(graalPyVfs.resolve("fileslist.txt"), "main.py\n");

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
        int exit = new CommandLine(command).execute("--project-dir", project.toString(), "--native-image-executable", "/tmp/native-image");

        assertEquals(0, exit);
        List<String> nativeCommand = executed.getFirst();
        int classpathIndex = nativeCommand.indexOf("-cp");
        assertTrue(classpathIndex >= 0);
        assertTrue(nativeCommand.get(classpathIndex + 1).contains(configDir.toAbsolutePath().normalize().toString()));

        Path generatedDir = project.resolve("__pyronaut__").resolve("native-image-config");
        String resourceConfig = Files.readString(generatedDir.resolve("resource-config.json"));
        assertTrue(resourceConfig.contains("\\\\QMETA-INF/GRAALPY-VFS/micronaut-application/fileslist.txt\\\\E"));
        assertTrue(resourceConfig.contains("\\\\QMETA-INF/GRAALPY-VFS/micronaut-application/src/logback/__init__.py\\\\E"));
        assertTrue(resourceConfig.contains("\\\\Qapplication.toml\\\\E"));
        assertTrue(resourceConfig.contains("\\\\Qnested/extra.txt\\\\E"));

        String directories = nativeCommand.stream()
            .filter(flag -> flag.startsWith("-H:ConfigurationFileDirectories="))
            .findFirst()
            .orElseThrow();
        assertTrue(directories.contains(generatedDir.toAbsolutePath().normalize().toString()));
    }

    @Test
    void resolvesRelativeRuntimeManifestEntriesAgainstProjectDirectory() throws Exception {
        Path project = prepareProject("""
            [project]
            name = "demo"

            [tool.pyronaut]

            [tool.pyronaut.dependencies]
            runtime = ["org.example:demo:1.0"]
            """);
        Path dependencyJar = createJar(
            project.resolve("__pyronaut__/m2-repository/io/micronaut/micronaut-runtime/5.0.0/micronaut-runtime-5.0.0.jar")
        );
        overwriteRuntimeManifest(project, List.of("__pyronaut__/m2-repository/io/micronaut/micronaut-runtime/5.0.0/micronaut-runtime-5.0.0.jar"));

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
        int exit = new CommandLine(command).execute("--project-dir", project.toString(), "--native-image-executable", "/tmp/native-image");

        assertEquals(0, exit);
        List<String> nativeCommand = executed.getFirst();
        int classpathIndex = nativeCommand.indexOf("-cp");
        assertTrue(classpathIndex >= 0);
        assertTrue(nativeCommand.get(classpathIndex + 1).contains(dependencyJar.toAbsolutePath().normalize().toString()));
    }

    @Test
    void skipsMetadataConfigurationWhenDisabledInPyproject() throws Exception {
        Path project = prepareProject("""
            [project]
            name = "demo"

            [tool.pyronaut]

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
        Files.writeString(schemasDir.resolve("metadata-library-index-schema-v2.0.0.json"), "{}\n");
        Files.writeString(repositoryRoot.resolve("index.json"), "[]\n");
    }

    private static void createModuleMetadata(Path repositoryRoot,
                                             String group,
                                             String artifact,
                                             String metadataVersion,
                                             Set<String> testedVersions,
                                             boolean latest) throws IOException {
        String module = group + ":" + artifact;
        String relativeModuleDir = group + "/" + artifact;

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

    private static Path createJar(Path jar, String... entries) throws IOException {
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (String entry : entries) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.write(new byte[0]);
                zip.closeEntry();
            }
        }
        return jar;
    }
}

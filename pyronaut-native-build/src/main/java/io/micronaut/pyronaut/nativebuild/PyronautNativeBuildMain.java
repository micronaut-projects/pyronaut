/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.pyronaut.nativebuild;

import io.micronaut.pyronaut.config.model.PyprojectModel;
import io.micronaut.pyronaut.config.model.PyprojectModelException;
import io.micronaut.pyronaut.config.model.PyprojectModelReader;
import org.graalvm.reachability.GraalVMReachabilityMetadataRepository;
import org.graalvm.reachability.internal.FileSystemRepository;
import picocli.CommandLine;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.zip.ZipInputStream;

/**
 * Native build entrypoint that resolves reachability metadata before running native-image.
 */
@CommandLine.Command(name = "pyronaut-native-build", mixinStandardHelpOptions = true, description = "Build native image with reachability metadata support")
public final class PyronautNativeBuildMain implements Callable<Integer> {
    private static final int SUCCESS = 0;
    private static final int PRECONDITION_FAILED = 8;
    private static final int INTERNAL_ERROR = 10;

    private static final String DEFAULT_MAIN_CLASS = "pyronaut_application.PyronautMain";
    private static final String DEFAULT_OUTPUT = "__pyronaut__/native/application";
    private static final String DEFAULT_PYRONAUT_DIR = "__pyronaut__";
    private static final String DEFAULT_CLASSES_DIR = "__pyronaut__/classes";
    private static final String DEFAULT_RUNTIME_MANIFEST = "__pyronaut__/resolved-runtime-dependencies";
    private static final String DEFAULT_METADATA_VERSION = "0.11.5";
    private static final String DEFAULT_METADATA_URL = "https://repo1.maven.org/maven2/org/graalvm/buildtools/graalvm-reachability-metadata/" + DEFAULT_METADATA_VERSION + "/graalvm-reachability-metadata-" + DEFAULT_METADATA_VERSION + "-repository.zip";
    private static final String VERSIONED_METADATA_URL_TEMPLATE = "https://repo1.maven.org/maven2/org/graalvm/buildtools/graalvm-reachability-metadata/%s/graalvm-reachability-metadata-%s-repository.zip";

    @CommandLine.Option(names = "--project-dir", defaultValue = ".", description = "Project directory")
    Path projectDir = Path.of(".");

    @CommandLine.Option(names = "--main-class", defaultValue = DEFAULT_MAIN_CLASS, description = "Main class")
    String mainClass = DEFAULT_MAIN_CLASS;

    @CommandLine.Option(names = "--output", defaultValue = DEFAULT_OUTPUT, description = "Native image output path")
    Path output = Path.of(DEFAULT_OUTPUT);

    @CommandLine.Option(names = "--native-image-executable", defaultValue = "native-image", description = "Native-image executable")
    String nativeImageExecutable = "native-image";

    @CommandLine.Option(names = "--verbose", description = "Print and pass verbose mode to native-image")
    boolean verbose;

    private final PyprojectModelReader modelReader;
    private final NativeImageInvoker nativeImageInvoker;
    private final MetadataRepositoryDownloader metadataRepositoryDownloader;

    public PyronautNativeBuildMain() {
        this(new PyprojectModelReader(), new ProcessNativeImageInvoker(), new HttpMetadataRepositoryDownloader());
    }

    PyronautNativeBuildMain(
        PyprojectModelReader modelReader,
        NativeImageInvoker nativeImageInvoker,
        MetadataRepositoryDownloader metadataRepositoryDownloader
    ) {
        this.modelReader = modelReader;
        this.nativeImageInvoker = nativeImageInvoker;
        this.metadataRepositoryDownloader = metadataRepositoryDownloader;
    }

    @Override
    public Integer call() {
        Path root = projectDir.toAbsolutePath().normalize();
        try {
            Path classesDir = root.resolve(DEFAULT_CLASSES_DIR).normalize();
            if (!Files.isDirectory(classesDir)) {
                System.err.println("Missing processed classes directory: " + classesDir + ". Run pyronaut process first.");
                return PRECONDITION_FAILED;
            }
            Path runtimeManifest = root.resolve(DEFAULT_RUNTIME_MANIFEST).normalize();
            if (!Files.exists(runtimeManifest)) {
                System.err.println("Missing runtime scope cache. Run pyronaut install first. (missing: " + runtimeManifest + ")");
                return PRECONDITION_FAILED;
            }

            List<Path> runtimeClasspath = readManifest(runtimeManifest);
            List<Path> nativeClasspath = new ArrayList<>(runtimeClasspath);
            nativeClasspath.add(classesDir);

            Path outputPath = root.resolve(output).normalize();
            Path outputParent = outputPath.getParent();
            if (outputParent != null) {
                Files.createDirectories(outputParent);
            }

            PyprojectModel model = modelReader.readProjectDirectory(root);
            MetadataOptions metadataOptions = metadataOptions(model);
            List<Path> metadataDirs = resolveMetadataDirectories(root, model, runtimeClasspath, metadataOptions);

            List<String> command = new ArrayList<>();
            command.add(nativeImageExecutable);
            command.add("-cp");
            command.add(joinClasspath(nativeClasspath));
            if (!metadataDirs.isEmpty()) {
                command.add("-H:ConfigurationFileDirectories=" + joinMetadataDirs(metadataDirs));
            }
            if (verbose) {
                command.add("--verbose");
            }
            command.add(mainClass);
            command.add(outputPath.toString());

            int exitCode = nativeImageInvoker.run(command, root);
            if (exitCode == SUCCESS) {
                System.out.println("Native build complete: " + outputPath);
                System.out.println("Run it with: " + outputPath);
            }
            return exitCode;
        } catch (PyprojectModelException e) {
            System.err.println(e.getMessage());
            return PRECONDITION_FAILED;
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
            return PRECONDITION_FAILED;
        } catch (Exception e) {
            System.err.println("Native build failed: " + e.getMessage());
            return INTERNAL_ERROR;
        }
    }

    private List<Path> resolveMetadataDirectories(Path root,
                                                  PyprojectModel model,
                                                  List<Path> runtimeClasspath,
                                                  MetadataOptions metadataOptions) throws IOException {
        if (!metadataOptions.enabled) {
            System.err.println("Reachability metadata repository: disabled");
            return List.of();
        }

        URI sourceUri = metadataSource(metadataOptions);
        Path cacheRoot = root.resolve(DEFAULT_PYRONAUT_DIR).resolve("reachability-metadata");
        Path cacheKeyRoot = cacheRoot.resolve(sha256(sourceUri.toString()));
        Path extractedRoot = cacheKeyRoot.resolve("repository");

        boolean downloaded = false;
        if (!Files.isDirectory(extractedRoot)) {
            Files.createDirectories(cacheKeyRoot);
            try {
                metadataRepositoryDownloader.download(sourceUri, extractedRoot);
                downloaded = true;
            } catch (IOException e) {
                if (!Files.isDirectory(extractedRoot)) {
                    throw new IllegalStateException("Failed resolving reachability metadata repository from " + sourceUri + ": " + e.getMessage(), e);
                }
            }
        }

        Path repositoryRoot = locateRepositoryRoot(extractedRoot);
        Set<String> excluded = Set.copyOf(metadataOptions.excludedModules);
        Set<String> gavs = runtimeArtifacts(model, runtimeClasspath, excluded);

        GraalVMReachabilityMetadataRepository repository = new FileSystemRepository(repositoryRoot);
        Set<Path> selected = repository.findConfigurationsFor(query -> {
            query.forArtifacts(gavs);
            query.useLatestConfigWhenVersionIsUntested();
        }).stream().map(configuration -> configuration.getDirectory().toAbsolutePath().normalize()).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        String source = downloaded ? "download" : "cache";
        String versionText = metadataOptions.version == null || metadataOptions.version.isBlank() ? "default" : metadataOptions.version;
        System.err.println("Reachability metadata repository: source=" + source + ", uri=" + sourceUri + ", version=" + versionText);
        System.err.println("Reachability metadata directories applied: " + selected.size());
        return List.copyOf(selected);
    }

    private static MetadataOptions metadataOptions(PyprojectModel model) {
        PyprojectModel.Metadata metadata = model.pyronaut().build().metadata();
        if (metadata == null) {
            return new MetadataOptions(true, null, null, List.of());
        }
        boolean enabled = metadata.enabled() == null || metadata.enabled();
        List<String> excluded = metadata.excludedModules() == null ? List.of() : metadata.excludedModules();
        return new MetadataOptions(enabled, metadata.version(), metadata.repositoryUrl(), excluded);
    }

    private static URI metadataSource(MetadataOptions options) {
        if (options.repositoryUrl != null && !options.repositoryUrl.isBlank()) {
            return URI.create(options.repositoryUrl);
        }
        if (options.version != null && !options.version.isBlank()) {
            String version = options.version.trim();
            return URI.create(String.format(Locale.ROOT, VERSIONED_METADATA_URL_TEMPLATE, version, version));
        }
        return URI.create(DEFAULT_METADATA_URL);
    }

    private static Path locateRepositoryRoot(Path extractedRoot) throws IOException {
        if (Files.isDirectory(extractedRoot.resolve("schemas"))) {
            return extractedRoot;
        }
        try (var children = Files.list(extractedRoot)) {
            Path topLevel = children
                .filter(Files::isDirectory)
                .filter(path -> Files.isDirectory(path.resolve("schemas")))
                .findFirst()
                .orElse(null);
            if (topLevel != null) {
                return topLevel;
            }
        }
        try (var stream = Files.walk(extractedRoot, 6)) {
            List<Path> candidates = stream
                .filter(Files::isDirectory)
                .filter(path -> Files.isDirectory(path.resolve("schemas")))
                .sorted((a, b) -> Integer.compare(a.getNameCount(), b.getNameCount()))
                .toList();
            Path candidate = candidates.isEmpty() ? null : candidates.getFirst();
            if (candidate != null) {
                return candidate;
            }
        }
        throw new IllegalStateException("Reachability metadata repository is missing schemas directory: " + extractedRoot);
    }

    private static Set<String> runtimeArtifacts(PyprojectModel model, List<Path> runtimeClasspath, Set<String> excludedModules) {
        Set<String> artifacts = new LinkedHashSet<>();
        for (Path entry : runtimeClasspath) {
            String gav = gavFromClasspathEntry(entry);
            if (gav == null) {
                continue;
            }
            String module = gav.substring(0, gav.lastIndexOf(':'));
            if (excludedModules.contains(module)) {
                continue;
            }
            artifacts.add(gav);
        }
        for (String coordinate : model.pyronaut().dependencies().runtime()) {
            if (coordinate == null || coordinate.isBlank()) {
                continue;
            }
            String[] pieces = coordinate.trim().split(":");
            if (pieces.length < 3) {
                continue;
            }
            String module = pieces[0] + ":" + pieces[1];
            if (excludedModules.contains(module)) {
                continue;
            }
            artifacts.add(module + ":" + pieces[2]);
        }
        return artifacts;
    }

    private static String gavFromClasspathEntry(Path entry) {
        if (entry == null) {
            return null;
        }
        Path normalized = entry.toAbsolutePath().normalize();
        if (normalized.getNameCount() < 4) {
            return null;
        }

        int nameCount = normalized.getNameCount();
        String artifact = normalized.getName(nameCount - 3).toString();
        String version = normalized.getName(nameCount - 2).toString();
        String fileName = normalized.getFileName().toString();
        if (!fileName.startsWith(artifact + "-" + version) || !(fileName.endsWith(".jar") || fileName.endsWith(".pom"))) {
            return null;
        }

        int repoRootIndex = -1;
        for (int i = 0; i < nameCount; i++) {
            String segment = normalized.getName(i).toString();
            if ("m2-repository".equals(segment)) {
                repoRootIndex = i;
            }
        }
        if (repoRootIndex < 0) {
            for (int i = 0; i < nameCount; i++) {
                String segment = normalized.getName(i).toString();
                if ("repository".equals(segment)) {
                    repoRootIndex = i;
                }
            }
        }
        if (repoRootIndex < 0 || repoRootIndex + 3 >= nameCount) {
            return null;
        }

        StringBuilder group = new StringBuilder();
        for (int i = repoRootIndex + 1; i < nameCount - 3; i++) {
            if (!group.isEmpty()) {
                group.append('.');
            }
            group.append(normalized.getName(i));
        }
        if (group.isEmpty()) {
            return null;
        }
        return group + ":" + artifact + ":" + version;
    }

    private static List<Path> readManifest(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .map(Path::of)
                .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Failed reading runtime classpath manifest: " + file, e);
        }
    }

    private static String joinClasspath(List<Path> entries) {
        return entries.stream().map(path -> path.toAbsolutePath().normalize().toString()).reduce((a, b) -> a + java.io.File.pathSeparator + b).orElse("");
    }

    private static String joinMetadataDirs(List<Path> entries) {
        return entries.stream().map(path -> path.toAbsolutePath().normalize().toString()).reduce((a, b) -> a + "," + b).orElse("");
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                out.append(String.format(Locale.ROOT, "%02x", b));
            }
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed creating metadata cache key", e);
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new PyronautNativeBuildMain()).execute(args);
        System.exit(exitCode);
    }

    interface NativeImageInvoker {
        int run(List<String> command, Path workingDirectory) throws Exception;
    }

    static final class ProcessNativeImageInvoker implements NativeImageInvoker {
        @Override
        public int run(List<String> command, Path workingDirectory) throws Exception {
            Process process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .inheritIO()
                .start();
            return process.waitFor();
        }
    }

    interface MetadataRepositoryDownloader {
        void download(URI source, Path extractedRoot) throws IOException;
    }

    static final class HttpMetadataRepositoryDownloader implements MetadataRepositoryDownloader {
        @Override
        public void download(URI source, Path extractedRoot) throws IOException {
            Path tempDir = extractedRoot.getParent().resolve("download-tmp");
            deleteRecursively(tempDir);
            Files.createDirectories(tempDir);
            Path tempExtractRoot = tempDir.resolve("repository");
            Files.createDirectories(tempExtractRoot);

            URL url = source.toURL();
            try (InputStream in = url.openStream(); ZipInputStream zip = new ZipInputStream(in)) {
                java.util.zip.ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String normalized = entry.getName().replace('\\', '/');
                    if (normalized.startsWith("/") || normalized.contains("../")) {
                        continue;
                    }
                    Path target = tempExtractRoot.resolve(normalized).normalize();
                    if (!target.startsWith(tempExtractRoot)) {
                        continue;
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                    } else {
                        Path parent = target.getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }
                        Files.copy(zip, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }

            deleteRecursively(extractedRoot);
            Files.createDirectories(extractedRoot.getParent());
            Files.move(tempExtractRoot, extractedRoot, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            deleteRecursively(tempDir);
        }

        private static void deleteRecursively(Path root) throws IOException {
            if (root == null || !Files.exists(root)) {
                return;
            }
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    record MetadataOptions(boolean enabled, String version, String repositoryUrl, List<String> excludedModules) {
    }
}

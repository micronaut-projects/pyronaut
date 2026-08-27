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
import io.micronaut.pyronaut.run.PyronautRunMain;
import org.graalvm.reachability.GraalVMReachabilityMetadataRepository;
import org.graalvm.reachability.internal.FileSystemRepository;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Native build entrypoint that resolves reachability metadata before running native-image.
 */
@CommandLine.Command(name = "pyronaut-native-build", mixinStandardHelpOptions = true, description = "Build native image with reachability metadata support")
public final class PyronautNativeBuildMain implements Callable<Integer> {
    private static final int SUCCESS = 0;
    private static final int PRECONDITION_FAILED = 8;
    private static final int INTERNAL_ERROR = 10;

    private static final String APPLICATION_MAIN_CLASS = PyronautRunMain.class.getName();
    private static final String DEFAULT_OUTPUT = "__pyronaut__/native/application";
    private static final String DEFAULT_PYRONAUT_DIR = "__pyronaut__";
    private static final String DEFAULT_CLASSES_DIR = "__pyronaut__/classes";
    private static final String DEFAULT_RUNTIME_MANIFEST = "__pyronaut__/resolved-runtime-dependencies";
    private static final String DEFAULT_CONFIG_DIR = "config";
    private static final String GENERATED_NATIVE_IMAGE_CONFIG_DIR = "__pyronaut__/native-image-config";
    private static final Map<Path, String> GAV_CACHE = new ConcurrentHashMap<>();
    private static final Map<Path, List<Path>> POM_CACHE = new ConcurrentHashMap<>();
    private static final String DEFAULT_METADATA_VERSION = loadDefaultMetadataVersion();
    private static final String DEFAULT_METADATA_URL = "https://repo1.maven.org/maven2/org/graalvm/buildtools/graalvm-reachability-metadata/" + DEFAULT_METADATA_VERSION + "/graalvm-reachability-metadata-" + DEFAULT_METADATA_VERSION + "-repository.zip";
    private static final String VERSIONED_METADATA_URL_TEMPLATE = "https://repo1.maven.org/maven2/org/graalvm/buildtools/graalvm-reachability-metadata/%s/graalvm-reachability-metadata-%s-repository.zip";

    @CommandLine.Option(names = "--project-dir", defaultValue = ".", description = "Project directory")
    Path projectDir = Path.of(".");

    @CommandLine.Option(names = "--output", defaultValue = DEFAULT_OUTPUT, description = "Native image output path")
    Path output = Path.of(DEFAULT_OUTPUT);

    @CommandLine.Option(names = "--native-image-executable", defaultValue = "native-image", description = "Native-image executable")
    String nativeImageExecutable = "native-image";

    @CommandLine.Option(names = "--verbose", description = "Print and pass verbose mode to native-image")
    boolean verbose;

    @CommandLine.Option(names = "--base-image", description = "Build a reusable Crema runtime image")
    boolean baseImage;

    @CommandLine.Option(names = "--default-base-image", description = "Build the bundled Crema runtime without project dependencies")
    boolean defaultBaseImage;

    @CommandLine.Option(names = "--default-base-image-path", description = "Use a downloaded prebuilt default Crema runtime")
    Path defaultBaseImagePath;

    @CommandLine.Option(names = "--include-python", description = "Include the Python and Truffle production runtime")
    boolean includePython;

    @CommandLine.Option(names = "--no-sbom", description = "Do not embed or export a native-image SBOM")
    boolean noSbom;

    @CommandLine.Option(names = "--offline", description = "Do not download reachability metadata")
    boolean offline;

    @CommandLine.Option(names = "--user-package", description = "Application package to preserve in a closed-world native image")
    List<String> userPackages = new ArrayList<>();

    @CommandLine.Unmatched
    List<String> passthroughNativeImageArgs = new ArrayList<>();

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

    private static String loadDefaultMetadataVersion() {
        try (InputStream input = PyronautNativeBuildMain.class.getResourceAsStream("metadata-version.txt")) {
            if (input == null) {
                throw new IllegalStateException("Missing reachability metadata version resource");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new IllegalStateException("Failed reading reachability metadata version", e);
        }
    }

    @Override
    public Integer call() {
        Path root = projectDir.toAbsolutePath().normalize();
        try {
            rejectMainClassOverride();
            if (defaultBaseImagePath != null && !defaultBaseImage) {
                throw new IllegalStateException("--default-base-image-path requires --default-base-image");
            }
            if (defaultBaseImage && defaultBaseImagePath != null) {
                return copyPrebuiltBaseImage(root);
            }
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

            List<Path> runtimeClasspath = readManifest(root, runtimeManifest);
            if (baseImage || defaultBaseImage) {
                return buildBaseImage(root, runtimeClasspath, defaultBaseImage);
            }
            List<Path> runnerClasspath = pyronautRunClasspathEntries(includePython);
            List<Path> runnerFilteredRuntimeClasspath = excludeRunnerProvidedModules(runtimeClasspath, runnerClasspath);
            List<Path> applicationRuntimeClasspath = runnerFilteredRuntimeClasspath.stream()
                .filter(path -> isProductionRunnerClasspathEntry(path, includePython))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            // Keep the Java launcher free of Python/Truffle and logback
            // artifacts. The Python launcher opts in through --include-python.
            List<Path> nativeClasspath = new ArrayList<>(runnerClasspath);
            nativeClasspath.addAll(applicationRuntimeClasspath);
            if (!includePython) {
                nativeClasspath.removeIf(PyronautNativeBuildMain::isPythonOnlyClasspathEntry);
            }
            removeDuplicateVirtualFileSystemEntries(nativeClasspath, runnerClasspath.size());
            nativeClasspath.add(classesDir);
            Path configDir = root.resolve(DEFAULT_CONFIG_DIR).normalize();
            if (Files.isDirectory(configDir)) {
                nativeClasspath.add(configDir);
            }

            Path outputPath = root.resolve(output).normalize();
            Path outputParent = outputPath.getParent();
            if (outputParent != null) {
                Files.createDirectories(outputParent);
            }

            PyprojectModel model = modelReader.readProjectDirectory(root);
            MetadataOptions metadataOptions = metadataOptions(model);
            MetadataSelection metadataSelection = resolveMetadataDirectories(root, model, runtimeClasspath, metadataOptions);
            List<Path> configurationDirs = new ArrayList<>();
            Path projectResourceConfigDir = generateProjectResourceConfig(root, classesDir, configDir, runtimeClasspath);
            if (projectResourceConfigDir != null) {
                configurationDirs.add(projectResourceConfigDir);
            }
            configurationDirs.addAll(projectNativeImageConfigurationDirs(configDir));
            configurationDirs.addAll(metadataSelection.directories());

            PyronautNativeImageBuilder builder = new PyronautNativeImageBuilder(
                outputPath,
                (command, workingDirectory) -> runNativeImage(command, workingDirectory)
            )
                .nativeImageExecutable(Path.of(nativeImageExecutable))
                .workingDirectory(root)
                .includePython(includePython)
                .emitBuildReport(false)
                .includeSbom(!noSbom)
                .addClasspath(nativeClasspath)
            .mainClass(APPLICATION_MAIN_CLASS)
            .preservePackages(userPackages);
            if (userPackages.isEmpty()) {
                // Keep package discovery as a compatibility fallback for callers
                // invoking this executable directly. The builder remains the sole
                // owner of how preservation is rendered as native-image options.
                builder.preservePackages(discoverUserPackages(classesDir, userPackages));
            }
            List<String> configurationArguments = new ArrayList<>();
            NativeImageConfigurationSupport.addBundledConfigurationExclusions(
                configurationArguments, nativeClasspath, metadataSelection.modules(), PyronautNativeBuildMain::gavFromClasspathEntry, verbose
            );
            builder.addNativeImageArguments(configurationArguments);
            if (!configurationDirs.isEmpty()) {
                builder.addNativeImageArgument("-H:ConfigurationFileDirectories=" + joinMetadataDirs(configurationDirs));
            }
            if (verbose) {
                builder.addNativeImageArgument("--verbose");
            }
            builder.addNativeImageArguments(passthroughNativeImageArgs);
            int exitCode = builder.build().exitCode();
            if (exitCode == SUCCESS) {
                System.out.println("Native build complete: " + outputPath);
                System.out.println("Run it with: " + outputPath);
            }
            return exitCode;
        } catch (PyprojectModelException | IllegalStateException e) {
            System.err.println(e.getMessage());
            return PRECONDITION_FAILED;
        } catch (Exception e) {
            System.err.println("Native build failed: " + e.getMessage());
            return INTERNAL_ERROR;
        }
    }

    private Integer copyPrebuiltBaseImage(Path root) throws IOException {
        Path source = defaultBaseImagePath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new IllegalStateException("Prebuilt default base image does not exist: " + source);
        }
        Path outputPath = root.resolve(output).normalize();
        Path outputParent = outputPath.getParent();
        if (outputParent != null) {
            Files.createDirectories(outputParent);
        }
        if (!source.equals(outputPath)) {
            Files.copy(source, outputPath, StandardCopyOption.REPLACE_EXISTING);
        }
        if (!outputPath.toFile().setExecutable(true, false)) {
            throw new IOException("Unable to mark prebuilt default base image executable: " + outputPath);
        }
        System.out.println("Prebuilt default base image copied: " + outputPath);
        return SUCCESS;
    }

    private Integer buildBaseImage(Path root,
                                   List<Path> runtimeClasspath,
                                   boolean bundledOnly) throws IOException, InterruptedException {
        List<Path> runnerClasspath = pyronautRunClasspathEntries(includePython);
        List<Path> baseClasspath = bundledOnly
            ? new ArrayList<>()
            : new ArrayList<>(excludeRunnerProvidedModules(runtimeClasspath, runnerClasspath));
        baseClasspath.addAll(runnerClasspath);
        List<String> configurationArguments = new ArrayList<>();
        if (!bundledOnly) {
            Path pyproject = root.resolve("pyproject.toml");
            PyprojectModel model = Files.isRegularFile(pyproject) ? modelReader.readProjectDirectory(root) : null;
            MetadataOptions metadataOptions = model == null
                ? new MetadataOptions(true, null, null, List.of())
                : metadataOptions(model);
            MetadataSelection metadataSelection = resolveMetadataDirectories(root, model, runtimeClasspath, metadataOptions);
            NativeImageConfigurationSupport.addBundledConfigurationExclusions(
                configurationArguments, baseClasspath, metadataSelection.modules(), PyronautNativeBuildMain::gavFromClasspathEntry, verbose
            );
        }
        PyronautNativeImageBuilder builder = new PyronautNativeImageBuilder(
            root.resolve(output).normalize(),
            (command, workingDirectory) -> runNativeImage(command, workingDirectory)
        )
            .nativeImageExecutable(Path.of(nativeImageExecutable))
            .workingDirectory(root)
            .includePython(includePython)
            .includeSbom(!noSbom)
            .addClasspath(baseClasspath)
            .addNativeImageArguments(configurationArguments);
        if (verbose) {
            builder.addNativeImageArgument("--verbose");
        }
        passthroughNativeImageArgs.forEach(builder::addNativeImageArgument);
        PyronautNativeImageBuilder.BuildResult result = builder.build();
        if (result.exitCode() == SUCCESS) {
            System.out.println("Base image build complete: " + result.executable());
        }
        return result.exitCode();
    }

    private int runNativeImage(List<String> command, Path workingDirectory) throws IOException, InterruptedException {
        try {
            return nativeImageInvoker.run(command, workingDirectory);
        } catch (IOException | InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Native-image invocation failed", e);
        }
    }

    private void rejectMainClassOverride() {
        if (passthroughNativeImageArgs.stream().anyMatch(arg -> arg.equals("--main-class") || arg.startsWith("--main-class="))) {
            throw new IllegalStateException("--main-class is not supported for native builds; PyronautRunMain is always used");
        }
    }

    private static List<Path> pyronautRunClasspathEntries(boolean includePython) {
        String classpath = System.getProperty("java.class.path", "");
        if (classpath.isBlank()) {
            throw new IllegalStateException("Unable to locate the bundled Pyronaut runner classpath");
        }
        LinkedHashMap<String, Path> unique = new LinkedHashMap<>();
        java.util.Arrays.stream(classpath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
            .map(Path::of)
            .map(path -> path.toAbsolutePath().normalize())
            .filter(Files::exists)
            .filter(path -> isProductionRunnerClasspathEntry(path, includePython))
            .forEach(path -> {
                String gav = gavFromClasspathEntry(path);
                unique.putIfAbsent(gav == null ? path.toString() : gav, path);
            });
        return unique.values().stream().toList();
    }

    private static boolean isProductionRunnerClasspathEntry(Path path, boolean includePython) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString().toLowerCase(Locale.ROOT);
        String location = path.toString().toLowerCase(Locale.ROOT);
        if (name.contains("pyronaut-native-build")
            || name.contains("graalvm-reachability-metadata")
            || name.contains("picocli-codegen")
            || name.contains("inject-java")
            || name.contains("junit")
            || name.contains("opentest4j")) {
            return false;
        }
        if (includePython) {
            return true;
        }
        return !location.contains("pyronaut-run-python")
            && !name.contains("pyronaut-logback")
            && !location.contains("pyronaut-logback")
            && !name.contains("graalpy")
            && !name.contains("python")
            && !name.contains("truffle")
            && !name.contains("polyglot")
            && !name.contains("logback");
    }

    private static boolean isPythonOnlyClasspathEntry(Path path) {
        String location = path.toString().toLowerCase(Locale.ROOT);
        return location.contains("pyronaut-run-python")
            || location.contains("pyronaut-logback")
            || location.contains("context-python")
            || location.contains("inject-python")
            || location.contains("graalpy")
            || location.contains("python-")
            || location.contains("truffle-")
            || location.contains("polyglot-")
            || location.contains("logback-");
    }

    private static List<Path> excludeRunnerProvidedModules(List<Path> runtimeClasspath, List<Path> runnerClasspath) {
        Set<String> providedModules = new LinkedHashSet<>();
        for (Path entry : runnerClasspath) {
            String gav = gavFromClasspathEntry(entry);
            if (gav != null) {
                providedModules.add(gav.substring(0, gav.lastIndexOf(':')));
            }
        }
        if (providedModules.isEmpty()) {
            return runtimeClasspath;
        }
        return runtimeClasspath.stream()
            .filter(entry -> {
                String gav = gavFromClasspathEntry(entry);
                return gav == null || !providedModules.contains(gav.substring(0, gav.lastIndexOf(':')));
            })
            .toList();
    }

    private static void removeDuplicateVirtualFileSystemEntries(List<Path> classpath, int runtimeEntries) {
        Set<String> runtimeResources = new HashSet<>();
        for (int i = 0; i < runtimeEntries && i < classpath.size(); i++) {
            runtimeResources.addAll(virtualFileSystemEntries(classpath.get(i)));
        }
        Iterator<Path> entries = classpath.listIterator(runtimeEntries);
        while (entries.hasNext()) {
            Path entry = entries.next();
            Set<String> resources = virtualFileSystemEntries(entry);
            if (!resources.isEmpty() && resources.stream().anyMatch(runtimeResources::contains)) {
                entries.remove();
            }
        }
    }

    private static Set<String> virtualFileSystemEntries(Path entry) {
        if (entry == null || !Files.isRegularFile(entry) || !entry.getFileName().toString().endsWith(".jar")) {
            return Set.of();
        }
        Set<String> resources = new HashSet<>();
        try (var zip = new ZipInputStream(Files.newInputStream(entry))) {
            ZipEntry zipEntry;
            while ((zipEntry = zip.getNextEntry()) != null) {
                if (!zipEntry.isDirectory() && zipEntry.getName().startsWith("META-INF/GRAALPY-VFS/")) {
                    resources.add(zipEntry.getName());
                }
            }
        } catch (IOException ignored) {
            return Set.of();
        }
        return resources;
    }

    private MetadataSelection resolveMetadataDirectories(Path root,
                                                         PyprojectModel model,
                                                         List<Path> runtimeClasspath,
                                                         MetadataOptions metadataOptions) throws IOException {
        if (!metadataOptions.enabled) {
            System.err.println("Reachability metadata repository: disabled");
            return new MetadataSelection(List.of(), Set.of());
        }

        URI sourceUri = metadataSource(metadataOptions);
        Path cacheRoot = root.resolve(DEFAULT_PYRONAUT_DIR).resolve("reachability-metadata");
        Path cacheKeyRoot = cacheRoot.resolve(sha256(sourceUri.toString()));
        Path extractedRoot = cacheKeyRoot.resolve("repository");

        boolean downloaded = false;
        if (!Files.isDirectory(extractedRoot) && offline) {
            throw new IllegalStateException("Reachability metadata is not cached for " + sourceUri + " and --offline was specified");
        }
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
        Set<String> selectedModules = selected.stream()
            .map(path -> moduleFromMetadataDirectory(repositoryRoot, path))
            .filter(module -> module != null && !module.isBlank())
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        String source = downloaded ? "download" : "cache";
        String versionText = metadataOptions.version == null || metadataOptions.version.isBlank() ? "default" : metadataOptions.version;
        System.err.println("Reachability metadata repository: source=" + source + ", uri=" + sourceUri + ", version=" + versionText);
        System.err.println("Reachability metadata directories applied: " + selected.size());
        return new MetadataSelection(List.copyOf(selected), Set.copyOf(selectedModules));
    }

    private static String moduleFromMetadataDirectory(Path repositoryRoot, Path metadataDir) {
        try {
            Path relative = repositoryRoot.relativize(metadataDir.toAbsolutePath().normalize());
            int groupIndex = 0;
            if (relative.getNameCount() >= 4 && "metadata".equals(relative.getName(0).toString())) {
                groupIndex = 1;
            }
            if (relative.getNameCount() < groupIndex + 3) {
                return null;
            }
            return relative.getName(groupIndex) + ":" + relative.getName(groupIndex + 1);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Path generateProjectResourceConfig(Path root,
                                                      Path classesDir,
                                                      Path configDir,
                                                      List<Path> runtimeClasspath) throws IOException {
        Set<String> resources = new LinkedHashSet<>();
        collectClasspathResources(resources, classesDir, true);
        collectClasspathResources(resources, configDir, false);
        collectJarResources(resources, runtimeClasspath);
        if (resources.isEmpty()) {
            return null;
        }
        Path generatedDir = root.resolve(GENERATED_NATIVE_IMAGE_CONFIG_DIR).normalize();
        Files.createDirectories(generatedDir);
        Files.writeString(generatedDir.resolve("resource-config.json"), buildResourceConfig(List.copyOf(resources)), StandardCharsets.UTF_8);
        return generatedDir;
    }

    private static List<Path> projectNativeImageConfigurationDirs(Path configDir) throws IOException {
        Path nativeImageRoot = configDir.resolve("META-INF").resolve("native-image");
        if (!Files.isDirectory(nativeImageRoot)) {
            return List.of();
        }
        try (var stream = Files.walk(nativeImageRoot)) {
            return stream
                .filter(Files::isDirectory)
                .filter(path -> !path.equals(nativeImageRoot))
                .filter(path -> {
                    try (var files = Files.list(path)) {
                        return files.anyMatch(Files::isRegularFile);
                    } catch (IOException e) {
                        return false;
                    }
                })
                .sorted()
                .toList();
        }
    }

    private static Set<String> discoverUserPackages(Path classesDir,
                                                    List<String> configuredPackages) throws IOException {
        Set<String> packages = new TreeSet<>();
        for (String configuredPackage : configuredPackages) {
            if (configuredPackage != null && !configuredPackage.isBlank()) {
                packages.add(configuredPackage.trim());
            }
        }
        if (!packages.isEmpty()) {
            return packages;
        }
        try (var stream = Files.walk(classesDir)) {
            stream.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".class"))
                .map(classesDir::relativize)
                .map(Path::getParent)
                .filter(Objects::nonNull)
                .map(path -> path.toString().replace(File.separatorChar, '.'))
                .filter(name -> !name.isBlank())
                .forEach(packages::add);
        }
        return packages;
    }

    private static void collectClasspathResources(Set<String> resources, Path root, boolean excludeCompiledArtifacts) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream
                .filter(Files::isRegularFile)
                .map(path -> root.relativize(path).toString().replace('\\', '/'))
                .filter(path -> !path.isBlank())
                .filter(path -> !excludeCompiledArtifacts || (!path.endsWith(".class") && !path.endsWith(".java")))
                .sorted()
                .forEach(resources::add);
        }
    }

    private static void collectJarResources(Set<String> resources, List<Path> classpath) {
        for (Path entry : classpath) {
            if (entry == null) {
                continue;
            }
            Path normalized = entry.toAbsolutePath().normalize();
            if (!Files.isRegularFile(normalized) || !normalized.getFileName().toString().endsWith(".jar")) {
                continue;
            }
            try (var zip = new ZipInputStream(Files.newInputStream(normalized))) {
                ZipEntry zipEntry;
                while ((zipEntry = zip.getNextEntry()) != null) {
                    if (zipEntry.isDirectory()) {
                        continue;
                    }
                    String name = zipEntry.getName();
                    if (name.startsWith("META-INF/GRAALPY-VFS/")) {
                        resources.add(name);
                    }
                }
            } catch (IOException ignored) {
                // Skip unreadable jars; native-image will surface real classpath issues separately.
            }
        }
    }

    private static String buildResourceConfig(List<String> resources) {
        StringBuilder builder = new StringBuilder();
        builder.append("{\n");
        builder.append("  \"resources\": {\n");
        builder.append("    \"includes\": [\n");
        for (int i = 0; i < resources.size(); i++) {
            String resource = resources.get(i);
            builder.append("      {\n");
            builder.append("        \"pattern\": \"\\\\Q");
            builder.append(escapeJson(resource));
            builder.append("\\\\E\"\n");
            builder.append("      }");
            if (i + 1 < resources.size()) {
                builder.append(',');
            }
            builder.append('\n');
        }
        builder.append("    ],\n");
        builder.append("    \"excludes\": []\n");
        builder.append("  },\n");
        builder.append("  \"bundles\": []\n");
        builder.append("}\n");
        return builder.toString();
    }

    private static String escapeJson(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            switch (current) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (current < 0x20) {
                        builder.append(String.format(Locale.ROOT, "\\u%04x", (int) current));
                    } else {
                        builder.append(current);
                    }
                }
            }
        }
        return builder.toString();
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
                .sorted(Comparator.comparingInt(Path::getNameCount))
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
        if (model == null) {
            return artifacts;
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
        return GAV_CACHE.computeIfAbsent(normalized, PyronautNativeBuildMain::readGavFromPom);
    }

    private static String readGavFromPom(Path artifact) {
        for (Path pom : pomCandidates(artifact)) {
            try {
                Document document = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(pom.toFile());
                Element project = document.getDocumentElement();
                String group = childText(project, "groupId");
                String artifactId = childText(project, "artifactId");
                String version = childText(project, "version");
                if (group != null && artifactId != null && version != null) {
                    return group + ":" + artifactId + ":" + version;
                }
            } catch (Exception ignored) {
                // A malformed or unrelated POM must not prevent native-image
                // construction; callers can still use the classpath entry.
            }
        }
        String gradleCoordinate = gradleCacheCoordinate(artifact);
        if (gradleCoordinate != null) {
            return gradleCoordinate;
        }
        // Compatibility for manifests produced by older tooling which did not
        // stage the corresponding POM. POM metadata always wins when present.
        return legacyMavenCoordinate(artifact);
    }

    private static String gradleCacheCoordinate(Path artifact) {
        Path normalized = artifact.toAbsolutePath().normalize();
        int filesDirectory = -1;
        for (int i = 0; i < normalized.getNameCount(); i++) {
            if ("files-2.1".equals(normalized.getName(i).toString())) {
                filesDirectory = i;
                break;
            }
        }
        if (filesDirectory < 0 || normalized.getNameCount() <= filesDirectory + 4) {
            return null;
        }
        int groupEnd = filesDirectory + 1;
        int artifactIndex = normalized.getNameCount() - 4;
        int versionIndex = normalized.getNameCount() - 3;
        if (artifactIndex <= groupEnd || versionIndex <= artifactIndex) {
            return null;
        }
        String artifactId = normalized.getName(artifactIndex).toString();
        String version = normalized.getName(versionIndex).toString();
        String fileName = normalized.getFileName().toString();
        if (!fileName.startsWith(artifactId + "-" + version + ".")) {
            return null;
        }
        String group = java.util.stream.IntStream.range(groupEnd, artifactIndex)
            .mapToObj(index -> normalized.getName(index).toString())
            .collect(java.util.stream.Collectors.joining("."));
        return group.isBlank() ? null : group + ":" + artifactId + ":" + version;
    }

    private static String legacyMavenCoordinate(Path artifact) {
        Path normalized = artifact.toAbsolutePath().normalize();
        int repository = -1;
        for (int i = 0; i < normalized.getNameCount(); i++) {
            if ("m2-repository".equals(normalized.getName(i).toString())) {
                repository = i;
            }
        }
        if (repository < 0 || normalized.getNameCount() - repository < 5) {
            return null;
        }
        int versionIndex = normalized.getNameCount() - 2;
        int artifactIndex = versionIndex - 1;
        int groupEnd = artifactIndex - 1;
        String fileName = normalized.getFileName().toString();
        String artifactId = normalized.getName(artifactIndex).toString();
        String version = normalized.getName(versionIndex).toString();
        if (!fileName.startsWith(artifactId + "-" + version + ".")) {
            return null;
        }
        String group = java.util.stream.IntStream.range(repository + 1, groupEnd + 1)
            .mapToObj(index -> normalized.getName(index).toString())
            .collect(java.util.stream.Collectors.joining("."));
        return group.isBlank() ? null : group + ":" + artifactId + ":" + version;
    }

    private static String childText(Element parent, String name) {
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && name.equals(element.getLocalName() == null ? element.getTagName() : element.getLocalName())) {
                String value = element.getTextContent();
                return value == null || value.isBlank() ? null : value.trim();
            }
        }
        return null;
    }

    private static List<Path> pomCandidates(Path artifact) {
        Path normalized = artifact.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null) {
            return List.of();
        }
        return POM_CACHE.computeIfAbsent(parent, ignored -> findPomCandidates(normalized, parent));
    }

    private static List<Path> findPomCandidates(Path artifact, Path parent) {
        List<Path> candidates = new ArrayList<>();
        try (var files = Files.list(parent)) {
            files.filter(path -> path.getFileName().toString().endsWith(".pom"))
                .forEach(candidates::add);
        } catch (IOException ignored) {
            // Continue with Gradle cache discovery below.
        }
        if (!candidates.isEmpty()) {
            return List.copyOf(candidates);
        }

        // Gradle stores JARs and POMs under different hash directories below
        // .../modules-2/files-2.1/group/artifact/version/. Search only that
        // version directory, avoiding an unbounded repository walk.
        Path versionDirectory = parent.getParent();
        if (versionDirectory == null || !isGradleVersionDirectory(versionDirectory)) {
            return List.of();
        }
        try (var files = Files.walk(versionDirectory, 3)) {
            files.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".pom"))
                .forEach(candidates::add);
        } catch (IOException ignored) {
            // No POM metadata available for this entry.
        }
        return List.copyOf(candidates);
    }

    private static boolean isGradleVersionDirectory(Path versionDirectory) {
        Path artifactDirectory = versionDirectory.getParent();
        Path groupDirectory = artifactDirectory == null ? null : artifactDirectory.getParent();
        Path filesDirectory = groupDirectory == null ? null : groupDirectory.getParent();
        Path modulesDirectory = filesDirectory == null ? null : filesDirectory.getParent();
        Path filesName = filesDirectory == null ? null : filesDirectory.getFileName();
        Path modulesName = modulesDirectory == null ? null : modulesDirectory.getFileName();
        return filesName != null
            && "files-2.1".equals(filesName.toString())
            && modulesName != null
            && "modules-2".equals(modulesName.toString());
    }

    private static List<Path> readManifest(Path root, Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .map(Path::of)
                .map(path -> path.isAbsolute() ? path : root.resolve(path).normalize())
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

    static void main(String[] args) {
        int exitCode = new CommandLine(new PyronautNativeBuildMain()).execute(args);
        System.exit(exitCode);
    }

    interface NativeImageInvoker {
        int run(List<String> command, Path workingDirectory) throws Exception;
    }

    static final class ProcessNativeImageInvoker implements NativeImageInvoker {
        @Override
        public int run(List<String> command, Path workingDirectory) throws Exception {
            ProcessBuilder processBuilder = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .inheritIO();
            // The distribution launcher exports a broad CLASSPATH containing
            // both production runners. Native images must use only the
            // explicit language-specific -cp assembled above.
            processBuilder.environment().remove("CLASSPATH");
            Process process = processBuilder.start();
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

    record MetadataSelection(List<Path> directories, Set<String> modules) {
    }
}

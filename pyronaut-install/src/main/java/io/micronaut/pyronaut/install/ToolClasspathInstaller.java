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
package io.micronaut.pyronaut.install;

import io.micronaut.pyronaut.config.model.PyprojectModel;
import io.micronaut.pyronaut.config.model.PyprojectModelException;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Materializes the non-installer launcher classpaths omitted from the SDK wheel. */
final class ToolClasspathInstaller {
    static final String DESCRIPTOR_FILE = "pyronaut-tool-classpath.tsv";
    static final String RUNTIME_PROPERTIES_FILE = "tool-runtime.properties";
    static final String TOOLS_DIR_PROPERTY = "pyronaut.packaged.tools.dir";
    static final String TOOLS_DIR_ENV = "PYRONAUT_PACKAGED_TOOLS_DIR";
    static final String CACHE_DIR_PROPERTY = "pyronaut.tools.cache.dir";
    static final String CACHE_DIR_ENV = "PYRONAUT_TOOLS_CACHE_DIR";
    private static final Map<Path, Object> JVM_LOCKS = new ConcurrentHashMap<>();

    private final MavenClasspathResolver resolver;
    private final Path packagedTools;
    private final Path cacheBase;

    ToolClasspathInstaller() {
        this(new MavenClasspathResolver(), findPackagedTools(), findCacheBase());
    }

    ToolClasspathInstaller(MavenClasspathResolver resolver, Path packagedTools, Path cacheBase) {
        this.resolver = resolver;
        this.packagedTools = packagedTools;
        this.cacheBase = cacheBase;
    }

    Path install(PyprojectModel model,
                 Path localRepository,
                 boolean offline,
                 boolean refresh) throws IOException {
        return install(model, localRepository, offline, refresh, null);
    }

    Path install(PyprojectModel model,
                 Path localRepository,
                 boolean offline,
                 boolean refresh,
                 DependencyProgressListener progressListener) throws IOException {
        if (packagedTools == null || !Files.isRegularFile(packagedTools.resolve(RUNTIME_PROPERTIES_FILE))) {
            return null;
        }
        List<ToolDescriptor> descriptors = readDescriptors(packagedTools);
        if (descriptors.isEmpty()) {
            return null;
        }
        String sdkVersion = readSdkVersion(packagedTools);
        String descriptorHash = readDescriptorHash(packagedTools, descriptors);
        Path versionRoot = cacheBase.resolve(safePathSegment(sdkVersion));
        Files.createDirectories(versionRoot);
        Path lockFile = versionRoot.resolve(".install.lock");
        Object jvmLock = JVM_LOCKS.computeIfAbsent(lockFile.toAbsolutePath().normalize(), ignored -> new Object());
        synchronized (jvmLock) {
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                Path current = versionRoot.resolve("current");
                if (!refresh && completeLayout(current, descriptors, sdkVersion, descriptorHash, localRepository)) {
                    return current;
                }
                Path layout = versionRoot.resolve(descriptorHash + "-" + UUID.randomUUID());
                materialize(layout, descriptors, sdkVersion, descriptorHash,
                    model == null ? defaultModel() : model, localRepository, offline, refresh, progressListener);
                updateCurrentLink(versionRoot, layout.getFileName());
            }
        }
        return versionRoot.resolve("current");
    }

    private void materialize(Path layout,
                             List<ToolDescriptor> descriptors,
                             String sdkVersion,
                             String descriptorHash,
                             PyprojectModel model,
                             Path localRepository,
                             boolean offline,
                             boolean refresh,
                             DependencyProgressListener progressListener) throws IOException {
        Map<ArtifactKey, Artifact> requested = new LinkedHashMap<>();
        for (ToolDescriptor descriptor : descriptors) {
            for (ToolEntry entry : descriptor.entries()) {
                if (entry.maven()) {
                    requested.putIfAbsent(entry.key(), entry.artifact());
                }
            }
        }
        List<Artifact> artifacts = new ArrayList<>(requested.values());
        List<Path> resolved = resolver.resolveToolArtifacts(model, artifacts, localRepository, offline, refresh, progressListener);
        if (resolved.size() != artifacts.size()) {
            throw new PyprojectModelException("Incomplete Pyronaut tool runtime resolution");
        }
        Map<ArtifactKey, Path> resolvedByKey = new LinkedHashMap<>();
        for (int i = 0; i < artifacts.size(); i++) {
            resolvedByKey.put(ArtifactKey.of(artifacts.get(i)), resolved.get(i));
        }

        Path temporary = Files.createTempDirectory(layout.getParent(), "." + layout.getFileName() + "-");
        try {
            Path temporaryTools = temporary.resolve("tools");
            Path sharedLib = temporaryTools.resolve("shared/lib");
            Files.createDirectories(sharedLib);
            Map<String, ArtifactMaterialization> sourcesByFileName = new LinkedHashMap<>();
            Map<String, ArtifactMaterialization> controlPanelSourcesByFileName = new LinkedHashMap<>();
            for (ToolDescriptor descriptor : descriptors) {
                Path sourceTool = descriptor.file().getParent().getParent();
                copyDirectory(sourceTool.resolve("bin"), temporaryTools.resolve(descriptor.command()).resolve("bin"));
                for (ToolEntry entry : descriptor.entries()) {
                    Path source = entry.maven()
                        ? resolvedByKey.get(entry.key())
                        : packagedTools.resolve("shared/lib").resolve(entry.fileName());
                    if (source == null || !Files.isRegularFile(source)) {
                        throw new IOException("Missing Pyronaut tool artifact " + entry.fileName());
                    }
                    Map<String, ArtifactMaterialization> destinations = entry.controlPanel()
                        ? controlPanelSourcesByFileName
                        : sourcesByFileName;
                    ArtifactMaterialization existing = destinations.get(entry.fileName());
                    if (existing == null) {
                        destinations.put(entry.fileName(), new ArtifactMaterialization(source, false));
                    } else {
                        destinations.put(entry.fileName(), existing.merge(source, entry.fileName()));
                    }
                }
            }
            for (Map.Entry<String, ArtifactMaterialization> entry : sourcesByFileName.entrySet()) {
                entry.getValue().writeTo(sharedLib.resolve(entry.getKey()));
            }
            Path controlPanelLib = temporaryTools.resolve("pyronaut-dev/lib/control-panel");
            for (Map.Entry<String, ArtifactMaterialization> entry : controlPanelSourcesByFileName.entrySet()) {
                entry.getValue().writeTo(controlPanelLib.resolve(entry.getKey()));
            }
            Properties metadata = new Properties();
            metadata.setProperty("descriptor.sha256", descriptorHash);
            metadata.setProperty("sdk.version", sdkVersion);
            metadata.setProperty("local.repository", localRepository.toAbsolutePath().normalize().toString());
            try (var output = Files.newOutputStream(temporary.resolve("tool-runtime.properties"))) {
                metadata.store(output, null);
            }
            moveAtomically(temporary, layout);
        } finally {
            if (Files.exists(temporary)) {
                deleteDirectory(temporary);
            }
        }
    }

    private boolean completeLayout(Path layout,
                                   List<ToolDescriptor> descriptors,
                                   String sdkVersion,
                                   String descriptorHash,
                                   Path localRepository) {
        Path metadataFile = layout.resolve("tool-runtime.properties");
        if (!Files.isRegularFile(metadataFile)) {
            return false;
        }
        Properties metadata = new Properties();
        try (InputStream input = Files.newInputStream(metadataFile)) {
            metadata.load(input);
        } catch (IOException e) {
            return false;
        }
        if (!sdkVersion.equals(metadata.getProperty("sdk.version"))
            || !descriptorHash.equals(metadata.getProperty("descriptor.sha256"))) {
            return false;
        }
        for (ToolDescriptor descriptor : descriptors) {
            Path bin = layout.resolve("tools").resolve(descriptor.command()).resolve("bin").resolve(descriptor.command());
            if (!Files.isRegularFile(bin)) {
                return false;
            }
            for (ToolEntry entry : descriptor.entries()) {
                Path artifact = entry.controlPanel()
                    ? layout.resolve("tools/pyronaut-dev/lib/control-panel").resolve(entry.fileName())
                    : layout.resolve("tools/shared/lib").resolve(entry.fileName());
                if (!validArtifactLink(artifact, expectedSource(entry, localRepository))) {
                    return false;
                }
            }
        }
        return true;
    }

    private Path expectedSource(ToolEntry entry, Path localRepository) {
        if (!entry.maven()) {
            return packagedTools.resolve("shared/lib").resolve(entry.fileName()).toAbsolutePath().normalize();
        }
        return localRepository.toAbsolutePath().normalize()
            .resolve(entry.group().replace('.', '/'))
            .resolve(entry.artifactId())
            .resolve(entry.version())
            .resolve(entry.fileName());
    }

    private static boolean validArtifactLink(Path artifact, Path expectedSource) {
        if (!Files.isRegularFile(artifact) || !Files.isRegularFile(expectedSource)) {
            return false;
        }
        try {
            if (Files.isSymbolicLink(artifact)) {
                Path target = artifact.getParent().resolve(Files.readSymbolicLink(artifact)).normalize();
                return target.equals(expectedSource);
            }
            return Files.mismatch(artifact, expectedSource) == -1L;
        } catch (IOException e) {
            return false;
        }
    }

    private static void updateCurrentLink(Path versionRoot, Path layoutName) throws IOException {
        Path current = versionRoot.resolve("current");
        Path pending = versionRoot.resolve(".current-" + ProcessHandle.current().pid());
        Files.deleteIfExists(pending);
        Files.createSymbolicLink(pending, layoutName);
        try {
            Files.move(pending, current, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(pending, current, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static List<ToolDescriptor> readDescriptors(Path toolsRoot) throws IOException {
        try (var children = Files.list(toolsRoot)) {
            List<Path> descriptors = children
                .filter(Files::isDirectory)
                .map(path -> path.resolve("bin").resolve(DESCRIPTOR_FILE))
                .filter(Files::isRegularFile)
                .sorted()
                .toList();
            List<ToolDescriptor> result = new ArrayList<>(descriptors.size());
            for (Path descriptor : descriptors) {
                List<ToolEntry> entries = new ArrayList<>();
                for (String line : Files.readAllLines(descriptor, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    String[] fields = line.split("\\t", -1);
                    if (fields.length == 2 && "bundled".equals(fields[0])) {
                        entries.add(ToolEntry.bundled(validFileName(fields[1], descriptor)));
                    } else if (fields.length == 7 && "maven".equals(fields[0])) {
                        entries.add(ToolEntry.maven(fields[1], fields[2], fields[3], fields[4], fields[5],
                            validFileName(fields[6], descriptor)));
                    } else if (fields.length == 7 && "control-panel".equals(fields[0])) {
                        entries.add(ToolEntry.controlPanel(fields[1], fields[2], fields[3], fields[4], fields[5],
                            validFileName(fields[6], descriptor)));
                    } else {
                        throw new IOException("Invalid Pyronaut tool descriptor entry in " + descriptor + ": " + line);
                    }
                }
                String command = descriptor.getParent().getParent().getFileName().toString();
                result.add(new ToolDescriptor(command, descriptor, List.copyOf(entries)));
            }
            return List.copyOf(result);
        }
    }

    private static String validFileName(String value, Path descriptor) throws IOException {
        if (value.isBlank() || !Path.of(value).getFileName().toString().equals(value)) {
            throw new IOException("Invalid artifact filename in " + descriptor + ": " + value);
        }
        return value;
    }

    private static String readSdkVersion(Path toolsRoot) throws IOException {
        Properties properties = readRuntimeProperties(toolsRoot);
        String version = properties.getProperty("sdk.version");
        if (version == null || version.isBlank()) {
            throw new IOException("Missing sdk.version in " + toolsRoot.resolve(RUNTIME_PROPERTIES_FILE));
        }
        return version.trim();
    }

    private static String readDescriptorHash(Path toolsRoot, List<ToolDescriptor> descriptors) throws IOException {
        String declared = readRuntimeProperties(toolsRoot).getProperty("descriptor.sha256");
        return declared == null || declared.isBlank() ? descriptorHash(descriptors) : declared.trim();
    }

    private static Properties readRuntimeProperties(Path toolsRoot) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(toolsRoot.resolve(RUNTIME_PROPERTIES_FILE))) {
            properties.load(input);
        }
        return properties;
    }

    private static String descriptorHash(List<ToolDescriptor> descriptors) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (ToolDescriptor descriptor : descriptors) {
                digest.update(descriptor.command().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(descriptor.file()));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", e);
        } catch (IOException e) {
            throw new PyprojectModelException("Unable to fingerprint Pyronaut tool descriptors", e);
        }
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source)) {
            throw new IOException("Missing Pyronaut launcher directory: " + source);
        }
        try (var paths = Files.walk(source)) {
            for (Path path : paths.sorted(Comparator.naturalOrder()).toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void linkOrCopy(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try {
            Files.createSymbolicLink(target, source.toAbsolutePath().normalize());
        } catch (UnsupportedOperationException | IOException e) {
            try {
                Files.createLink(target, source);
            } catch (UnsupportedOperationException | IOException ignored) {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private static void deleteDirectory(Path directory) throws IOException {
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
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

    private static Path findPackagedTools() {
        String configured = System.getProperty(TOOLS_DIR_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(TOOLS_DIR_ENV);
        }
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        try {
            Path location = Path.of(ToolClasspathInstaller.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path parent = Files.isDirectory(location) ? location : location.getParent();
            while (parent != null) {
                if (Files.isRegularFile(parent.resolve(RUNTIME_PROPERTIES_FILE))) {
                    return parent;
                }
                parent = parent.getParent();
            }
        } catch (URISyntaxException | RuntimeException ignored) {
            // Development installDist layouts do not contain wheel descriptors.
        }
        return null;
    }

    private static Path findCacheBase() {
        String configured = System.getProperty(CACHE_DIR_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(CACHE_DIR_ENV);
        }
        return configured == null || configured.isBlank()
            ? Path.of(System.getProperty("user.home"), ".pyronaut", "tools")
            : Path.of(configured).toAbsolutePath().normalize();
    }

    static PyprojectModel defaultModel() {
        return defaultModel(List.of("mavenCentral"));
    }

    static PyprojectModel defaultModel(List<String> repositories) {
        PyprojectModel.Pyronaut pyronaut = new PyprojectModel.Pyronaut(
            null,
            null,
            List.copyOf(repositories),
            null,
            new PyprojectModel.Dependencies(List.of(), List.of(), List.of(), List.of()),
            null, null, null, null, null, null, null, null, null, null, false
        );
        return new PyprojectModel(null, null, pyronaut);
    }

    private static String safePathSegment(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private record ToolDescriptor(String command, Path file, List<ToolEntry> entries) {
    }

    private record ArtifactMaterialization(Path source, boolean copyRequired) {
        ArtifactMaterialization merge(Path additionalSource, String fileName) throws IOException {
            if (Files.mismatch(source, additionalSource) != -1L) {
                throw new IOException("Conflicting Pyronaut tool artifacts share filename " + fileName);
            }
            boolean sameOrigin = source.toAbsolutePath().normalize()
                .equals(additionalSource.toAbsolutePath().normalize());
            return copyRequired || sameOrigin ? this : new ArtifactMaterialization(source, true);
        }

        void writeTo(Path target) throws IOException {
            if (!copyRequired) {
                linkOrCopy(source, target);
                return;
            }
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    private record ToolEntry(boolean maven,
                             boolean controlPanel,
                             String group,
                             String artifactId,
                             String version,
                             String extension,
                             String classifier,
                             String fileName) {
        static ToolEntry bundled(String fileName) {
            return new ToolEntry(false, false, null, null, null, null, null, fileName);
        }

        static ToolEntry maven(String group,
                               String artifactId,
                               String version,
                               String extension,
                               String classifier,
                               String fileName) {
            return new ToolEntry(true, false, group, artifactId, version, extension, classifier, fileName);
        }

        static ToolEntry controlPanel(String group,
                                      String artifactId,
                                      String version,
                                      String extension,
                                      String classifier,
                                      String fileName) {
            return new ToolEntry(true, true, group, artifactId, version, extension, classifier, fileName);
        }

        Artifact artifact() {
            return new DefaultArtifact(group, artifactId, classifier, extension, version);
        }

        ArtifactKey key() {
            return new ArtifactKey(group, artifactId, version, extension, classifier);
        }
    }

    private record ArtifactKey(String group, String artifactId, String version, String extension, String classifier) {
        static ArtifactKey of(Artifact artifact) {
            return new ArtifactKey(
                artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion(), artifact.getExtension(), artifact.getClassifier()
            );
        }
    }
}

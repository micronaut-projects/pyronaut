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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
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
import java.util.concurrent.ConcurrentHashMap;

/** Materializes the non-installer launcher classpaths omitted from the SDK wheel. */
final class ToolClasspathInstaller {
    static final String DESCRIPTOR_FILE = "pyronaut-tool-classpath.tsv";
    static final String RUNTIME_PROPERTIES_FILE = "tool-runtime.properties";
    static final String TOOLS_DIR_PROPERTY = "pyronaut.packaged.tools.dir";
    static final String TOOLS_DIR_ENV = "PYRONAUT_PACKAGED_TOOLS_DIR";
    static final String CACHE_DIR_PROPERTY = "pyronaut.tools.cache.dir";
    static final String CACHE_DIR_ENV = "PYRONAUT_TOOLS_CACHE_DIR";
    private static final String DESCRIPTOR_HASH_PROPERTY = "descriptor.sha256";
    private static final String SDK_VERSION_PROPERTY = "sdk.version";
    private static final String TOOLS_DIR = "tools";
    private static final String SHARED_LIB = "shared/lib";
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
                    if (DependencyLock.current().requiresResolution()) {
                        // Recording or enforcing a dependency lock has to observe
                        // every artifact, also when the layout is already complete.
                        resolver.resolveToolArtifacts(model == null ? defaultModel() : model, mavenArtifacts(descriptors),
                            localRepository, offline, false, progressListener);
                    }
                    return current;
                }
                Path layout = materialize(versionRoot, descriptors, sdkVersion, descriptorHash,
                    model == null ? defaultModel() : model, localRepository, offline, refresh, progressListener);
                updateCurrentLink(versionRoot, layout.getFileName());
            }
        }
        return versionRoot.resolve("current");
    }

    /**
     * Builds the layout in a temporary directory and publishes it under the hash of its contents, so identical
     * inputs always produce the same directory name.
     */
    private Path materialize(Path versionRoot,
                             List<ToolDescriptor> descriptors,
                             String sdkVersion,
                             String descriptorHash,
                             PyprojectModel model,
                             Path localRepository,
                             boolean offline,
                             boolean refresh,
                             DependencyProgressListener progressListener) throws IOException {
        List<Artifact> artifacts = mavenArtifacts(descriptors);
        List<Path> resolved = resolver.resolveToolArtifacts(model, artifacts, localRepository, offline, refresh, progressListener);
        if (resolved.size() != artifacts.size()) {
            throw new PyprojectModelException("Incomplete Pyronaut tool runtime resolution");
        }
        Map<ArtifactKey, Path> resolvedByKey = new LinkedHashMap<>();
        for (int i = 0; i < artifacts.size(); i++) {
            resolvedByKey.put(ArtifactKey.of(artifacts.get(i)), resolved.get(i));
        }

        Path temporary = Files.createTempDirectory(versionRoot, "." + descriptorHash + "-");
        try {
            populate(temporary.resolve(TOOLS_DIR), descriptors, resolvedByKey);
            // Named before the metadata is written: it records the local repository path, which must not
            // change the name when only the location of identical artifacts differs.
            Path layout = versionRoot.resolve(contentHash(temporary));
            Properties metadata = new Properties();
            metadata.setProperty(DESCRIPTOR_HASH_PROPERTY, descriptorHash);
            metadata.setProperty(SDK_VERSION_PROPERTY, sdkVersion);
            metadata.setProperty("local.repository", localRepository.toAbsolutePath().normalize().toString());
            ReproducibleProperties.write(metadata, temporary.resolve(RUNTIME_PROPERTIES_FILE), null);
            if (!Files.isDirectory(layout, LinkOption.NOFOLLOW_LINKS)
                || !completeLayout(layout, descriptors, sdkVersion, descriptorHash, localRepository)) {
                publish(temporary, layout);
            }
            return layout;
        } finally {
            if (Files.exists(temporary)) {
                deleteDirectory(temporary);
            }
        }
    }

    private static List<Artifact> mavenArtifacts(List<ToolDescriptor> descriptors) {
        Map<ArtifactKey, Artifact> requested = new LinkedHashMap<>();
        for (ToolDescriptor descriptor : descriptors) {
            for (ToolEntry entry : descriptor.entries()) {
                if (entry.maven()) {
                    requested.putIfAbsent(entry.key(), entry.artifact());
                }
            }
        }
        return new ArrayList<>(requested.values());
    }

    private void populate(Path tools,
                          List<ToolDescriptor> descriptors,
                          Map<ArtifactKey, Path> resolvedByKey) throws IOException {
        Path sharedLib = tools.resolve(SHARED_LIB);
        Files.createDirectories(sharedLib);
        Map<String, Path> sourcesByFileName = new LinkedHashMap<>();
        Map<String, Path> controlPanelSourcesByFileName = new LinkedHashMap<>();
        for (ToolDescriptor descriptor : descriptors) {
            Path sourceTool = descriptor.file().getParent().getParent();
            copyDirectory(sourceTool.resolve("bin"), tools.resolve(descriptor.command()).resolve("bin"));
            for (ToolEntry entry : descriptor.entries()) {
                addSource(entry, resolvedByKey,
                    entry.controlPanel() ? controlPanelSourcesByFileName : sourcesByFileName);
            }
        }
        for (Map.Entry<String, Path> entry : sourcesByFileName.entrySet()) {
            linkOrCopy(entry.getValue(), sharedLib.resolve(entry.getKey()));
        }
        Path controlPanelLib = tools.resolve("pyronaut-dev/lib/control-panel");
        for (Map.Entry<String, Path> entry : controlPanelSourcesByFileName.entrySet()) {
            linkOrCopy(entry.getValue(), controlPanelLib.resolve(entry.getKey()));
        }
    }

    private void addSource(ToolEntry entry,
                           Map<ArtifactKey, Path> resolvedByKey,
                           Map<String, Path> destinations) throws IOException {
        Path source = entry.maven()
            ? resolvedByKey.get(entry.key())
            : packagedTools.resolve(SHARED_LIB).resolve(entry.fileName());
        if (source == null || !Files.isRegularFile(source)) {
            throw new IOException("Missing Pyronaut tool artifact " + entry.fileName());
        }
        Path existing = destinations.get(entry.fileName());
        if (existing != null && Files.mismatch(existing, source) != -1L) {
            throw new IOException("Conflicting Pyronaut tool artifacts share filename " + entry.fileName());
        }
        // A filename can be referenced by both a Maven descriptor and a
        // bundled descriptor. The bundled wheel artifact must win so the
        // resulting cache remains valid for every descriptor, regardless
        // of descriptor sort order.
        if (existing == null || !entry.maven()) {
            destinations.put(entry.fileName(), source);
        }
    }

    private static void publish(Path temporary, Path layout) throws IOException {
        if (!Files.exists(layout, LinkOption.NOFOLLOW_LINKS)) {
            moveAtomically(temporary, layout);
            return;
        }
        // A damaged layout with the same name is moved aside first, as directories cannot be replaced atomically.
        Path damaged = Files.createTempDirectory(layout.getParent(), "." + layout.getFileName() + "-damaged-");
        moveAtomically(layout, damaged.resolve("layout"));
        try {
            moveAtomically(temporary, layout);
        } finally {
            deleteDirectory(damaged);
        }
    }

    private boolean completeLayout(Path layout,
                                   List<ToolDescriptor> descriptors,
                                   String sdkVersion,
                                   String descriptorHash,
                                   Path localRepository) {
        if (!metadataMatches(layout, sdkVersion, descriptorHash)) {
            return false;
        }
        Map<String, Path> sharedSources = preferredSources(descriptors, localRepository);
        for (ToolDescriptor descriptor : descriptors) {
            Path bin = layout.resolve(TOOLS_DIR).resolve(descriptor.command()).resolve("bin").resolve(descriptor.command());
            if (!Files.isRegularFile(bin)) {
                return false;
            }
            for (ToolEntry entry : descriptor.entries()) {
                Path artifact = entry.controlPanel()
                    ? layout.resolve("tools/pyronaut-dev/lib/control-panel").resolve(entry.fileName())
                    : layout.resolve("tools/shared/lib").resolve(entry.fileName());
                Path expected = entry.controlPanel()
                    ? expectedSource(entry, localRepository)
                    : sharedSources.get(entry.fileName());
                if (!validArtifactLink(artifact, expected)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean metadataMatches(Path layout, String sdkVersion, String descriptorHash) {
        Path metadataFile = layout.resolve(RUNTIME_PROPERTIES_FILE);
        if (!Files.isRegularFile(metadataFile)) {
            return false;
        }
        Properties metadata = new Properties();
        try (InputStream input = Files.newInputStream(metadataFile)) {
            metadata.load(input);
        } catch (IOException _) {
            return false;
        }
        return sdkVersion.equals(metadata.getProperty(SDK_VERSION_PROPERTY))
            && descriptorHash.equals(metadata.getProperty(DESCRIPTOR_HASH_PROPERTY));
    }

    private Map<String, Path> preferredSources(List<ToolDescriptor> descriptors, Path localRepository) {
        Map<String, Path> sources = new LinkedHashMap<>();
        for (ToolDescriptor descriptor : descriptors) {
            for (ToolEntry entry : descriptor.entries()) {
                if (entry.controlPanel()) {
                    continue;
                }
                Path source = expectedSource(entry, localRepository);
                if (!entry.maven()) {
                    sources.put(entry.fileName(), source);
                } else {
                    sources.putIfAbsent(entry.fileName(), source);
                }
            }
        }
        return sources;
    }

    private Path expectedSource(ToolEntry entry, Path localRepository) {
        if (!entry.maven()) {
            return packagedTools.resolve(SHARED_LIB).resolve(entry.fileName()).toAbsolutePath().normalize();
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
        String version = properties.getProperty(SDK_VERSION_PROPERTY);
        if (version == null || version.isBlank()) {
            throw new IOException("Missing sdk.version in " + toolsRoot.resolve(RUNTIME_PROPERTIES_FILE));
        }
        return version.trim();
    }

    private static String readDescriptorHash(Path toolsRoot, List<ToolDescriptor> descriptors) throws IOException {
        String declared = readRuntimeProperties(toolsRoot).getProperty(DESCRIPTOR_HASH_PROPERTY);
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

    /**
     * Hashes the kind, relative path and bytes of every entry in a layout. Linked artifacts are hashed by the
     * contents they resolve to, so the hash does not depend on where the Maven repository lives.
     *
     * <p>Each entry is one unambiguous record: a kind tag, the length-prefixed UTF-8 path and, for files, the
     * fixed-width SHA-256 of the contents.
     */
    static String contentHash(Path root) throws IOException {
        MessageDigest digest = sha256();
        List<Path> entries;
        try (var paths = Files.walk(root)) {
            entries = paths.filter(path -> !path.equals(root))
                .sorted(Comparator.comparing(path -> relativeName(root, path)))
                .toList();
        }
        for (Path entry : entries) {
            boolean directory = Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS);
            digest.update(kindTag(entry, directory));
            byte[] name = relativeName(root, entry).getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(name.length).array());
            digest.update(name);
            if (!directory) {
                digest.update(fileHash(entry));
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static byte kindTag(Path entry, boolean directory) {
        if (directory) {
            return 'd';
        }
        return (byte) (Files.isExecutable(entry) ? 'x' : 'f');
    }

    private static byte[] fileHash(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            for (int read = input.read(buffer); read != -1; read = input.read(buffer)) {
                digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", e);
        }
    }

    private static String relativeName(Path root, Path path) {
        return root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
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

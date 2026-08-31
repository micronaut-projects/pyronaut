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
package io.micronaut.pyronaut.jarbuild;

import io.micronaut.pyronaut.jarbuild.launcher.PyronautFatJarLauncher;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a deterministic, indexed Pyronaut FAT JAR.
 */
public final class FatJarPackager {
    static final String ARCHIVE_ROOT = "PYRONAUT-INF/";
    static final String CLASSPATH_INDEX = ARCHIVE_ROOT + "classpath.idx";
    static final String APPLICATION_CLASS_INDEX = "META-INF/pyronaut/application-classes.idx";
    private static final String APP_CLASSES_ROOT = ARCHIVE_ROOT + "app/classes/";
    private static final String RESOURCE_ROOT = ARCHIVE_ROOT + "app/resources/";
    private static final String LIB_ROOT = ARCHIVE_ROOT + "lib/";
    private static final String LAUNCHER_PACKAGE = "io/micronaut/pyronaut/jarbuild/launcher/";
    private static final long REPRODUCIBLE_TIMESTAMP = 0L;

    /**
     * Creates a FAT JAR packager.
     */
    public FatJarPackager() {
    }

    /**
     * Packages an application.
     *
     * @param request packaging inputs
     * @return packaging result
     * @throws IOException when an input cannot be read or the output cannot be written
     */
    public FatJarResult packageApplication(FatJarRequest request) throws IOException {
        validate(request);
        Path output = request.output();
        Files.createDirectories(output.getParent());
        Path temporary = Files.createTempFile(output.getParent(), "." + output.getFileName(), ".tmp");
        int applicationEntries = 0;
        int archiveEntries;
        List<Root> roots = new ArrayList<>();
        Set<String> written = new HashSet<>();
        try {
            Root applicationRoot = new Root(APP_CLASSES_ROOT, false);
            roots.add(applicationRoot);
            List<RootInput> resourceRoots = new ArrayList<>();
            int resourceIndex = 0;
            for (Path resourceDirectory : request.resourceDirectories()) {
                Root root = new Root(RESOURCE_ROOT + formattedIndex(resourceIndex++) + "/", multiRelease(resourceDirectory));
                roots.add(root);
                resourceRoots.add(new RootInput(root, resourceDirectory));
            }
            List<RootInput> classpathRoots = new ArrayList<>();
            int classpathIndex = 0;
            for (Path classpathEntry : request.classpath()) {
                Root root = new Root(LIB_ROOT + formattedIndex(classpathIndex++) + "/", multiRelease(classpathEntry));
                roots.add(root);
                classpathRoots.add(new RootInput(root, classpathEntry));
            }
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                zip.setLevel(Deflater.BEST_COMPRESSION);
                write(zip, written, JarFile.MANIFEST_NAME, manifest(request));
                List<String> applicationClasses = applicationClasses(request.applicationClasses());
                applicationEntries += copyDirectoryWithEntry(
                    zip,
                    written,
                    request.applicationClasses(),
                    applicationRoot.prefix(),
                    APPLICATION_CLASS_INDEX,
                    String.join("\n", applicationClasses) + (applicationClasses.isEmpty() ? "" : "\n")
                );
                for (RootInput resourceRoot : resourceRoots) {
                    applicationEntries += copyDirectory(
                        zip, written, resourceRoot.input(), resourceRoot.root().prefix(), false
                    );
                }

                write(zip, written, CLASSPATH_INDEX, classpathIndex(request.mainClass(), roots));
                for (RootInput classpathRoot : classpathRoots) {
                    if (Files.isDirectory(classpathRoot.input())) {
                        copyDirectory(zip, written, classpathRoot.input(), classpathRoot.root().prefix(), true);
                    } else {
                        copyJar(zip, written, classpathRoot.input(), classpathRoot.root().prefix());
                    }
                }
                copyLauncherClasses(zip, written);
                archiveEntries = written.size();
            }
            move(temporary, output);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return new FatJarResult(output, applicationEntries, request.classpath().size(), archiveEntries);
    }

    private static void validate(FatJarRequest request) {
        Path output = request.output();
        if (output.getParent() == null || output.equals(output.getRoot()) || Files.isDirectory(output)) {
            throw new IllegalArgumentException("Output must be a JAR file path: " + output);
        }
        if (!Files.isDirectory(request.applicationClasses())) {
            throw new IllegalArgumentException("Missing application classes directory: " + request.applicationClasses());
        }
        rejectOutputWithinDirectory(output, request.applicationClasses(), "application classes");
        request.resourceDirectories().forEach(path -> {
            if (!Files.isDirectory(path)) {
                throw new IllegalArgumentException("Missing resource directory: " + path);
            }
            rejectOutputWithinDirectory(output, path, "resource");
        });
        request.classpath().forEach(path -> {
            if (!Files.isDirectory(path) && !Files.isRegularFile(path)) {
                throw new IllegalArgumentException("Missing classpath entry: " + path);
            }
            if (Files.isRegularFile(path) && !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                throw new IllegalArgumentException("Classpath file is not a JAR: " + path);
            }
            if (output.equals(path) || (Files.isDirectory(path) && output.startsWith(path))) {
                throw new IllegalArgumentException("Output cannot overwrite or be contained in classpath input: " + path);
            }
        });
    }

    private static void rejectOutputWithinDirectory(Path output, Path input, String description) {
        if (output.startsWith(input)) {
            throw new IllegalArgumentException("Output cannot be contained in " + description + " directory: " + input);
        }
    }

    private static byte[] manifest(FatJarRequest request) throws IOException {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(Attributes.Name.MAIN_CLASS, PyronautFatJarLauncher.class.getName());
        attributes.put(Attributes.Name.IMPLEMENTATION_TITLE, request.applicationName());
        attributes.put(Attributes.Name.IMPLEMENTATION_VERSION, request.applicationVersion());
        attributes.putValue("Pyronaut-Fat-Jar", "true");
        try (var output = new java.io.ByteArrayOutputStream()) {
            manifest.write(output);
            return output.toByteArray();
        }
    }

    private static void copyLauncherClasses(ZipOutputStream zip, Set<String> written) throws IOException {
        Path location;
        try {
            location = Path.of(PyronautFatJarLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IOException("Unable to locate FAT JAR launcher classes", e);
        }
        if (Files.isDirectory(location)) {
            copyDirectoryPrefix(zip, written, location.resolve(LAUNCHER_PACKAGE), location, false);
            return;
        }
        try (JarFile jar = new JarFile(location.toFile(), false)) {
            List<JarEntry> entries = jar.stream()
                .filter(entry -> !entry.isDirectory())
                .filter(entry -> entry.getName().startsWith(LAUNCHER_PACKAGE))
                .filter(entry -> entry.getName().endsWith(".class"))
                .sorted(Comparator.comparing(JarEntry::getName))
                .toList();
            for (JarEntry entry : entries) {
                try (InputStream input = jar.getInputStream(entry)) {
                    write(zip, written, entry.getName(), input);
                }
            }
        }
    }

    private static int copyDirectory(ZipOutputStream zip, Set<String> written, Path directory, String prefix,
                                     boolean omitInvalidatedMetadata) throws IOException {
        return copyDirectoryPrefix(zip, written, directory, directory, omitInvalidatedMetadata, prefix);
    }

    private static int copyDirectoryWithEntry(ZipOutputStream zip, Set<String> written, Path directory, String prefix,
                                              String addedName, String addedValue) throws IOException {
        List<Path> files;
        try (var stream = Files.walk(directory)) {
            files = stream
                .filter(Files::isRegularFile)
                .sorted(Comparator.comparing(path -> archivePath(directory.relativize(path))))
                .toList();
        }
        boolean added = false;
        int copied = 0;
        for (Path file : files) {
            String relative = archivePath(directory.relativize(file));
            validateEntryName(relative);
            if (addedName.equals(relative)) {
                continue;
            }
            if (!added && addedName.compareTo(relative) < 0) {
                write(zip, written, prefix + addedName, addedValue);
                added = true;
                copied++;
            }
            try (InputStream input = Files.newInputStream(file)) {
                write(zip, written, prefix + relative, input);
            }
            copied++;
        }
        if (!added) {
            write(zip, written, prefix + addedName, addedValue);
            copied++;
        }
        return copied;
    }

    private static int copyDirectoryPrefix(ZipOutputStream zip, Set<String> written, Path directory, Path base,
                                           boolean omitInvalidatedMetadata) throws IOException {
        return copyDirectoryPrefix(zip, written, directory, base, omitInvalidatedMetadata, "");
    }

    private static int copyDirectoryPrefix(ZipOutputStream zip, Set<String> written, Path directory, Path base,
                                           boolean omitInvalidatedMetadata, String prefix) throws IOException {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        List<Path> files;
        try (var stream = Files.walk(directory)) {
            files = stream
                .filter(Files::isRegularFile)
                .sorted(Comparator.comparing(path -> archivePath(base.relativize(path))))
                .toList();
        }
        int copied = 0;
        for (Path file : files) {
            String relative = archivePath(base.relativize(file));
            validateEntryName(relative);
            if (omitInvalidatedMetadata && invalidatedMetadata(relative)) {
                continue;
            }
            try (InputStream input = Files.newInputStream(file)) {
                if (omitInvalidatedMetadata && JarFile.MANIFEST_NAME.equalsIgnoreCase(relative)) {
                    write(zip, written, prefix + relative, sanitizedManifest(input));
                } else {
                    write(zip, written, prefix + relative, input);
                }
            }
            copied++;
        }
        return copied;
    }

    private static void copyJar(ZipOutputStream zip, Set<String> written, Path source, String prefix) throws IOException {
        try (JarFile jar = new JarFile(source.toFile(), false)) {
            List<JarEntry> entries = jar.stream()
                .filter(entry -> !entry.isDirectory())
                .sorted(Comparator.comparing(JarEntry::getName))
                .toList();
            Set<String> sourceEntries = new HashSet<>();
            for (JarEntry entry : entries) {
                String name = entry.getName();
                validateEntryName(name);
                if (!sourceEntries.add(name)) {
                    throw new IOException("Duplicate entry '" + name + "' in " + source);
                }
                if (invalidatedMetadata(name)) {
                    continue;
                }
                try (InputStream input = jar.getInputStream(entry)) {
                    if (JarFile.MANIFEST_NAME.equalsIgnoreCase(name)) {
                        write(zip, written, prefix + name, sanitizedManifest(input));
                    } else {
                        write(zip, written, prefix + name, input);
                    }
                }
            }
        }
    }

    private static byte[] sanitizedManifest(InputStream input) throws IOException {
        Manifest manifest = new Manifest(input);
        removeInvalidatedManifestAttributes(manifest.getMainAttributes());
        manifest.getEntries().values().forEach(FatJarPackager::removeInvalidatedManifestAttributes);
        try (var output = new java.io.ByteArrayOutputStream()) {
            manifest.write(output);
            return output.toByteArray();
        }
    }

    private static void removeInvalidatedManifestAttributes(Attributes attributes) {
        attributes.keySet().removeIf(key -> {
            String name = key.toString().toUpperCase(Locale.ROOT);
            return Attributes.Name.CLASS_PATH.toString().equalsIgnoreCase(name)
                || "SIGNATURE-VERSION".equals(name)
                || "MAGIC".equals(name)
                || name.contains("-DIGEST");
        });
    }

    private static List<String> applicationClasses(Path classesDirectory) throws IOException {
        try (var stream = Files.walk(classesDirectory)) {
            return stream
                .filter(Files::isRegularFile)
                .map(classesDirectory::relativize)
                .map(FatJarPackager::archivePath)
                .filter(name -> name.endsWith(".class"))
                .filter(name -> !name.startsWith("META-INF/"))
                .filter(name -> !name.startsWith("pyronaut_application/"))
                .filter(name -> !name.endsWith("module-info.class"))
                .filter(name -> !name.endsWith("package-info.class"))
                .filter(name -> !name.contains("$Definition"))
                .filter(name -> !name.contains("$Introspection"))
                .map(name -> name.substring(0, name.length() - ".class".length()).replace('/', '.'))
                .sorted()
                .toList();
        }
    }

    private static boolean multiRelease(Path entry) throws IOException {
        if (Files.isDirectory(entry)) {
            Path manifest = entry.resolve(JarFile.MANIFEST_NAME);
            if (!Files.isRegularFile(manifest)) {
                return false;
            }
            try (InputStream input = Files.newInputStream(manifest)) {
                return Boolean.parseBoolean(new Manifest(input).getMainAttributes().getValue(Attributes.Name.MULTI_RELEASE));
            }
        }
        try (JarFile jar = new JarFile(entry.toFile(), false)) {
            Manifest manifest = jar.getManifest();
            return manifest != null && Boolean.parseBoolean(manifest.getMainAttributes().getValue(Attributes.Name.MULTI_RELEASE));
        }
    }

    private static String classpathIndex(String mainClass, List<Root> roots) {
        StringBuilder index = new StringBuilder("format=1\n");
        index.append("main-class=").append(mainClass).append('\n');
        for (Root root : roots) {
            index.append("root=").append(root.prefix()).append('|').append(root.multiRelease()).append('\n');
        }
        return index.toString();
    }

    private static boolean invalidatedMetadata(String name) {
        String normalized = name.toUpperCase(Locale.ROOT);
        if ("META-INF/INDEX.LIST".equals(normalized)) {
            return true;
        }
        return normalized.startsWith("META-INF/")
            && (normalized.endsWith(".SF") || normalized.endsWith(".RSA")
            || normalized.endsWith(".DSA") || normalized.endsWith(".EC"));
    }

    private static void validateEntryName(String name) {
        if (name.isBlank() || name.startsWith("/") || name.contains("\\")
            || name.indexOf('\0') >= 0 || name.matches("^[A-Za-z]:/.*")
            || java.util.Arrays.stream(name.split("/", -1)).anyMatch(segment -> segment.equals(".") || segment.equals(".."))) {
            throw new IllegalArgumentException("Unsafe archive entry: " + name);
        }
    }

    private static String archivePath(Path path) {
        return path.toString().replace(path.getFileSystem().getSeparator(), "/");
    }

    private static String formattedIndex(int index) {
        return String.format(Locale.ROOT, "%04d", index);
    }

    private static void write(ZipOutputStream zip, Set<String> written, String name, String value) throws IOException {
        write(zip, written, name, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void write(ZipOutputStream zip, Set<String> written, String name, byte[] value) throws IOException {
        if (!written.add(name)) {
            throw new IOException("Duplicate output entry: " + name);
        }
        ZipEntry entry = entry(name);
        zip.putNextEntry(entry);
        zip.write(value);
        zip.closeEntry();
    }

    private static void write(ZipOutputStream zip, Set<String> written, String name, InputStream input) throws IOException {
        if (!written.add(name)) {
            throw new IOException("Duplicate output entry: " + name);
        }
        ZipEntry entry = entry(name);
        zip.putNextEntry(entry);
        input.transferTo(zip);
        zip.closeEntry();
    }

    private static ZipEntry entry(String name) {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(REPRODUCIBLE_TIMESTAMP);
        return entry;
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record Root(String prefix, boolean multiRelease) {
    }

    private record RootInput(Root root, Path input) {
    }
}

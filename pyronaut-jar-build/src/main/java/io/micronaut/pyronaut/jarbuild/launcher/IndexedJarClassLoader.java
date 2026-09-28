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
package io.micronaut.pyronaut.jarbuild.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.SecureClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

final class IndexedJarClassLoader extends SecureClassLoader implements AutoCloseable {
    private static final String INDEX_PATH = "PYRONAUT-INF/classpath.idx";
    private static final String ROOT_PREFIX = "root=";
    private static final String MAIN_CLASS_PREFIX = "main-class=";
    private static final String MICRONAUT_SERVICES = "META-INF/micronaut";

    static {
        registerAsParallelCapable();
    }

    private final Path archive;
    private final JarFile jarFile;
    private final List<Root> roots;
    private final String mainClass;

    IndexedJarClassLoader(Path archive) throws IOException {
        super(ClassLoader.getPlatformClassLoader());
        this.archive = archive.toAbsolutePath().normalize();
        // Verify outer-JAR signatures lazily as indexed entries are read. The
        // packager removes invalid dependency signatures, but users may sign
        // the completed artifact and expect application classes to remain
        // covered by that signature.
        jarFile = new JarFile(this.archive.toFile(), true);
        Index index = readIndex();
        roots = index.roots();
        mainClass = index.mainClass();
    }

    String mainClass() {
        return mainClass;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        String resourceName = name.replace('.', '/') + ".class";
        for (Root root : roots) {
            JarEntry entry = findEntry(root, resourceName);
            if (entry == null) {
                continue;
            }
            try {
                byte[] bytes = read(entry);
                definePackageIfNecessary(name, root);
                return defineClass(name, bytes, 0, bytes.length, new CodeSource(root.url(), entry.getCertificates()));
            } catch (IOException e) {
                throw new ClassNotFoundException("Failed reading " + name + " from " + archive, e);
            }
        }
        throw new ClassNotFoundException(name);
    }

    @Override
    protected URL findResource(String name) {
        for (Root root : roots) {
            JarEntry entry = findEntry(root, name);
            if (entry != null) {
                URL url = entryUrlOrNull(entry);
                if (url != null) {
                    return url;
                }
            }
        }
        JarEntry services = findMicronautServiceDirectory(name);
        return services == null ? null : entryUrlOrNull(services);
    }

    @Override
    protected Enumeration<URL> findResources(String name) {
        List<URL> resources = new ArrayList<>();
        JarEntry services = findMicronautServiceDirectory(name);
        if (services != null) {
            // Roots hold no directory entries; the packager merges every
            // root's service markers into one directory at the archive root.
            URL url = entryUrlOrNull(services);
            return Collections.enumeration(url == null ? List.of() : List.of(url));
        }
        for (Root root : roots) {
            JarEntry entry = findEntry(root, name);
            if (entry != null) {
                URL url = entryUrlOrNull(entry);
                if (url != null) {
                    resources.add(url);
                }
            }
        }
        return Collections.enumeration(resources);
    }

    @Override
    public void close() throws IOException {
        jarFile.close();
    }

    private Index readIndex() throws IOException {
        JarEntry indexEntry = jarFile.getJarEntry(INDEX_PATH);
        if (indexEntry == null) {
            throw new IOException("Missing FAT JAR classpath index: " + INDEX_PATH);
        }
        String configuredMainClass = null;
        List<Root> configuredRoots = new ArrayList<>();
        try (InputStream input = jarFile.getInputStream(indexEntry)) {
            for (String line : new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                if (line.startsWith(MAIN_CLASS_PREFIX)) {
                    configuredMainClass = line.substring(MAIN_CLASS_PREFIX.length());
                } else if (line.startsWith(ROOT_PREFIX)) {
                    String value = line.substring(ROOT_PREFIX.length());
                    int separator = value.lastIndexOf('|');
                    if (separator < 1) {
                        throw new IOException("Invalid FAT JAR classpath root: " + line);
                    }
                    String prefix = value.substring(0, separator);
                    boolean multiRelease = Boolean.parseBoolean(value.substring(separator + 1));
                    configuredRoots.add(new Root(prefix, multiRelease, readManifest(prefix), rootUrl(prefix)));
                }
            }
        }
        if (configuredMainClass == null || configuredMainClass.isBlank()) {
            throw new IOException("Missing target main class in " + INDEX_PATH);
        }
        if (configuredRoots.isEmpty()) {
            throw new IOException("Missing classpath roots in " + INDEX_PATH);
        }
        return new Index(configuredMainClass, List.copyOf(configuredRoots));
    }

    private Manifest readManifest(String prefix) throws IOException {
        JarEntry entry = jarFile.getJarEntry(prefix + "META-INF/MANIFEST.MF");
        if (entry == null) {
            return null;
        }
        try (InputStream input = jarFile.getInputStream(entry)) {
            return new Manifest(input);
        }
    }

    private JarEntry findMicronautServiceDirectory(String name) {
        String directory = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        if (!directory.equals(MICRONAUT_SERVICES) && !directory.startsWith(MICRONAUT_SERVICES + "/")) {
            return null;
        }
        JarEntry entry = jarFile.getJarEntry(directory + "/");
        return entry != null && entry.isDirectory() ? entry : null;
    }

    private JarEntry findEntry(Root root, String logicalName) {
        if (root.multiRelease() && !logicalName.startsWith("META-INF/versions/")) {
            for (int version = Runtime.version().feature(); version >= 9; version--) {
                JarEntry versioned = jarFile.getJarEntry(root.prefix() + "META-INF/versions/" + version + "/" + logicalName);
                if (versioned != null) {
                    return versioned;
                }
            }
        }
        return jarFile.getJarEntry(root.prefix() + logicalName);
    }

    private byte[] read(JarEntry entry) throws IOException {
        try (InputStream input = jarFile.getInputStream(entry)) {
            return input.readAllBytes();
        }
    }

    private URL entryUrlOrNull(JarEntry entry) {
        try {
            return URI.create("jar:" + archive.toUri() + "!/" + encodeEntryName(entry.getName())).toURL();
        } catch (MalformedURLException | IllegalArgumentException e) {
            // Resource lookups must never fail because of an unusual entry name.
            return null;
        }
    }

    private URL rootUrl(String prefix) throws MalformedURLException {
        return URI.create("jar:" + archive.toUri() + "!/" + encodeEntryName(prefix)).toURL();
    }

    /**
     * Percent-encodes a JAR entry name for use in a {@code jar:} URL. Each
     * {@code /}-separated segment is encoded individually so that spaces,
     * {@code %}, {@code #} and non-ASCII characters neither break URI parsing
     * nor truncate the entry path; the JAR URL handler decodes them again.
     */
    static String encodeEntryName(String name) {
        StringBuilder encoded = new StringBuilder(name.length() + 16);
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if (isUnreservedUrlPathCharacter(c)) {
                encoded.append(c);
            } else {
                encoded.append('%')
                    .append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)))
                    .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return encoded.toString();
    }

    private static boolean isUnreservedUrlPathCharacter(char c) {
        return (c >= 'a' && c <= 'z')
            || (c >= 'A' && c <= 'Z')
            || (c >= '0' && c <= '9')
            || c == '/' || c == '-' || c == '.' || c == '_' || c == '~'
            || c == '$' || c == '!' || c == '*' || c == '\'' || c == '(' || c == ')'
            || c == ',' || c == '+' || c == '=' || c == '@' || c == ':' || c == ';' || c == '&';
    }

    private void definePackageIfNecessary(String className, Root root) {
        int separator = className.lastIndexOf('.');
        if (separator < 1) {
            return;
        }
        String packageName = className.substring(0, separator);
        Package defined = getDefinedPackage(packageName);
        if (defined != null) {
            validatePackageSealing(packageName, defined, root);
            return;
        }
        Manifest manifest = root.manifest();
        Attributes main = manifest == null ? null : manifest.getMainAttributes();
        Attributes attributes = manifest == null ? null : manifest.getAttributes(packageName.replace('.', '/') + "/");
        String specificationTitle = attribute(attributes, main, Attributes.Name.SPECIFICATION_TITLE);
        String specificationVersion = attribute(attributes, main, Attributes.Name.SPECIFICATION_VERSION);
        String specificationVendor = attribute(attributes, main, Attributes.Name.SPECIFICATION_VENDOR);
        String implementationTitle = attribute(attributes, main, Attributes.Name.IMPLEMENTATION_TITLE);
        String implementationVersion = attribute(attributes, main, Attributes.Name.IMPLEMENTATION_VERSION);
        String implementationVendor = attribute(attributes, main, Attributes.Name.IMPLEMENTATION_VENDOR);
        String sealed = attribute(attributes, main, Attributes.Name.SEALED);
        URL sealBase = "true".equalsIgnoreCase(sealed) ? root.url() : null;
        try {
            definePackage(packageName, specificationTitle, specificationVersion, specificationVendor,
                implementationTitle, implementationVersion, implementationVendor, sealBase);
        } catch (IllegalArgumentException alreadyDefined) {
            Package concurrentlyDefined = getDefinedPackage(packageName);
            if (concurrentlyDefined == null) {
                throw alreadyDefined;
            }
            validatePackageSealing(packageName, concurrentlyDefined, root);
        }
    }

    private static void validatePackageSealing(String packageName, Package defined, Root root) {
        boolean rootSealed = "true".equalsIgnoreCase(attribute(
            packageAttributes(packageName, root.manifest()),
            root.manifest() == null ? null : root.manifest().getMainAttributes(),
            Attributes.Name.SEALED
        ));
        if (defined.isSealed() ? !defined.isSealed(root.url()) : rootSealed) {
            throw new SecurityException("Sealing violation for package " + packageName);
        }
    }

    private static Attributes packageAttributes(String packageName, Manifest manifest) {
        return manifest == null ? null : manifest.getAttributes(packageName.replace('.', '/') + "/");
    }

    private static String attribute(Attributes attributes, Attributes main, Attributes.Name name) {
        String value = attributes == null ? null : attributes.getValue(name);
        return value == null && main != null ? main.getValue(name) : value;
    }

    private record Index(String mainClass, List<Root> roots) {
    }

    private record Root(String prefix, boolean multiRelease, Manifest manifest, URL url) {
    }
}

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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

/**
 * A read-only view of one classpath root of a Pyronaut FAT JAR, presenting the
 * entries below the root's {@code PYRONAUT-INF/...} prefix as if they were at
 * the top of their own archive.
 *
 * <p>Directory-scanning libraries such as Flyway and Spring treat the
 * {@link JarFile} returned by a {@code jar:} URL's {@link java.net.JarURLConnection}
 * as the classpath root and list its entries by name. Handing them the outer
 * archive would hide every prefixed entry, so they receive this view instead.
 * The view opens its own handle on the archive, so callers may close it
 * independently of the class loader.</p>
 */
final class RootJarFile extends JarFile {
    private final String prefix;
    private final Set<String> directories;

    /**
     * @param archive the FAT JAR
     * @param prefix the root's entry prefix, ending in {@code /}
     * @param directories the root's directory names, relative to the root and without a trailing {@code /}
     */
    RootJarFile(Path archive, String prefix, Set<String> directories) throws IOException {
        super(archive.toFile(), false);
        this.prefix = prefix;
        this.directories = directories;
    }

    @Override
    public Enumeration<JarEntry> entries() {
        return Collections.enumeration(entryList());
    }

    @Override
    public Stream<JarEntry> stream() {
        return entryList().stream();
    }

    @Override
    public int size() {
        return entryList().size();
    }

    @Override
    public ZipEntry getEntry(String name) {
        return getJarEntry(name);
    }

    @Override
    public JarEntry getJarEntry(String name) {
        if (name == null) {
            throw new NullPointerException("name");
        }
        String directory = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        if (!name.endsWith("/")) {
            ZipEntry entry = super.getEntry(prefix + name);
            if (entry != null && !entry.isDirectory()) {
                return relative(entry, name);
            }
        }
        return !directory.isEmpty() && directories.contains(directory) ? new JarEntry(directory + "/") : null;
    }

    @Override
    public synchronized InputStream getInputStream(ZipEntry entry) throws IOException {
        if (entry.isDirectory()) {
            return getJarEntry(entry.getName()) == null ? null : InputStream.nullInputStream();
        }
        ZipEntry target = super.getEntry(prefix + entry.getName());
        return target == null || target.isDirectory() ? null : super.getInputStream(target);
    }

    @Override
    public Manifest getManifest() throws IOException {
        JarEntry entry = getJarEntry(MANIFEST_NAME);
        if (entry == null) {
            return null;
        }
        try (InputStream input = getInputStream(entry)) {
            return new Manifest(input);
        }
    }

    private List<JarEntry> entryList() {
        List<JarEntry> entries = new ArrayList<>();
        for (String directory : new TreeSet<>(directories)) {
            if (!directory.isEmpty()) {
                entries.add(new JarEntry(directory + "/"));
            }
        }
        Enumeration<JarEntry> archiveEntries = super.entries();
        while (archiveEntries.hasMoreElements()) {
            JarEntry entry = archiveEntries.nextElement();
            String name = entry.getName();
            if (!entry.isDirectory() && name.length() > prefix.length() && name.startsWith(prefix)) {
                entries.add(relative(entry, name.substring(prefix.length())));
            }
        }
        return entries;
    }

    private static JarEntry relative(ZipEntry entry, String name) {
        JarEntry relative = new JarEntry(name);
        relative.setTime(entry.getTime());
        if (entry.getSize() >= 0) {
            relative.setSize(entry.getSize());
        }
        if (entry.getCrc() >= 0) {
            relative.setCrc(entry.getCrc());
        }
        return relative;
    }
}

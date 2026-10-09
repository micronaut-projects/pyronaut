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

import io.micronaut.pyronaut.jarbuild.FatJarPackager;
import io.micronaut.pyronaut.jarbuild.FatJarRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises directory lookups on {@link IndexedJarClassLoader} in-process.
 */
class IndexedJarClassLoaderDirectoryTest {
    @TempDir
    Path temporaryDirectory;

    private IndexedJarClassLoader loader;

    @BeforeEach
    void packageArchive() throws IOException {
        Path classes = Files.createDirectories(temporaryDirectory.resolve("classes"));
        Path first = temporaryDirectory.resolve("resources-1");
        write(first.resolve("db/migration/V1__first.sql"), "first");
        write(first.resolve("db/migration/nested/V2__nested.sql"), "nested");
        write(first.resolve("odd dir 100%/data.txt"), "odd");
        write(first.resolve("META-INF/MANIFEST.MF"), "Manifest-Version: 1.0\nImplementation-Title: resources\n");
        Path second = temporaryDirectory.resolve("resources-2");
        write(second.resolve("db/migration/V3__second.sql"), "second");
        Path dependency = temporaryDirectory.resolve("dependency.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(dependency))) {
            output.putNextEntry(new JarEntry("lib/data.txt"));
            output.write("lib".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        Path archive = temporaryDirectory.resolve("app.jar");
        new FatJarPackager().packageApplication(
            FatJarRequest.builder(archive, classes, "app.Main")
                .applicationName("app")
                .applicationVersion("1.0")
                .resourceDirectory(first)
                .resourceDirectory(second)
                .classpath(List.of(dependency))
                .build()
        );
        loader = new IndexedJarClassLoader(archive);
    }

    @AfterEach
    void closeLoader() throws IOException {
        loader.close();
    }

    @Test
    void returnsOneScannableUrlPerRootHoldingTheDirectory() throws IOException {
        List<URL> urls = Collections.list(loader.getResources("db/migration"));
        assertEquals(2, urls.size());
        assertEquals(urls, Collections.list(loader.getResources("db/migration/")));
        assertEquals(urls.get(0), loader.getResource("db/migration"));
        assertTrue(urls.get(0).toString().endsWith("!/PYRONAUT-INF/app/resources/0000/db/migration/"), urls.get(0).toString());
        // Flyway matches a file to its scanned directory by URL path containment.
        assertTrue(loader.getResource("db/migration/V1__first.sql").getPath().contains(urls.get(0).getPath()));

        JarURLConnection connection = (JarURLConnection) urls.get(0).openConnection();
        assertEquals("db/migration/", connection.getEntryName());
        assertTrue(connection.getJarEntry().isDirectory());
        assertEquals(-1, connection.getJarEntry().getSize());
        for (int i = 0; i < 2; i++) {
            try (InputStream input = connection.getInputStream()) {
                assertEquals(0, input.readAllBytes().length);
            }
        }
        try (JarFile view = connection.getJarFile()) {
            List<String> names = Collections.list(view.entries()).stream().map(JarEntry::getName).toList();
            assertTrue(names.containsAll(List.of(
                "db/", "db/migration/", "db/migration/nested/",
                "db/migration/V1__first.sql", "db/migration/nested/V2__nested.sql"
            )), names.toString());
            assertFalse(names.contains("db/migration/V3__second.sql"), names.toString());
            assertFalse(names.stream().anyMatch(name -> name.startsWith("PYRONAUT-INF/")), names.toString());
            assertEquals(names.size(), view.size());
            assertEquals(names.size(), view.stream().count());

            JarEntry file = view.getJarEntry("db/migration/V1__first.sql");
            assertFalse(file.isDirectory());
            assertEquals(5, file.getSize());
            try (InputStream input = view.getInputStream(file)) {
                assertEquals("first", new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
            assertTrue(view.getEntry("db/migration").isDirectory());
            assertTrue(view.getJarEntry("db/migration/").isDirectory());
            assertNotNull(view.getInputStream(view.getJarEntry("db/migration/")));
            assertNull(view.getJarEntry("db/absent"));
            assertNull(view.getInputStream(new JarEntry("db/absent.sql")));
            assertNull(view.getInputStream(new JarEntry("db/absent/")));
            assertThrows(NullPointerException.class, () -> view.getJarEntry(null));
            assertEquals("resources", view.getManifest().getMainAttributes().getValue("Implementation-Title"));
        }
        try (JarFile view = ((JarURLConnection) urls.get(1).openConnection()).getJarFile()) {
            assertNotNull(view.getJarEntry("db/migration/V3__second.sql"));
            assertNull(view.getJarEntry("db/migration/V1__first.sql"));
            assertNull(view.getManifest());
        }
    }

    @Test
    void resolvesUrlsRelativeToTheRoot() throws IOException {
        URL directory = loader.getResource("db/migration/nested");
        assertEquals("nested", read(directory, "V2__nested.sql"));
        assertEquals("first", read(directory, "../V1__first.sql"));
        assertEquals("first", read(directory, "/db/migration/V1__first.sql"));
        URL withReference = new URL(directory, "V2__nested.sql#section");
        assertEquals("section", withReference.getRef());

        URL absent = new URL(directory, "absent.sql");
        assertThrows(FileNotFoundException.class, () -> absent.openConnection().connect());
        assertEquals(-1, absent.openConnection().getContentLengthLong());
        assertEquals(6, new URL(directory, "V2__nested.sql").openConnection().getContentLengthLong());
    }

    @Test
    void servesEveryRootForTheEmptyNameButNoStreamForIt() throws IOException {
        List<URL> roots = Collections.list(loader.getResources(""));
        assertEquals(4, roots.size(), roots.toString());
        JarURLConnection connection = (JarURLConnection) roots.get(0).openConnection();
        assertNull(connection.getEntryName());
        assertNull(connection.getJarEntry());
        assertThrows(IOException.class, connection::getInputStream);
        connection.getJarFile().close();
    }

    @Test
    void decodesPercentEncodedDirectoryNames() throws IOException {
        URL directory = loader.getResource("odd dir 100%");
        assertTrue(directory.toString().contains("odd%20dir%20100%25/"), directory.toString());
        JarURLConnection connection = (JarURLConnection) directory.openConnection();
        assertEquals("odd dir 100%/", connection.getEntryName());
        connection.getJarFile().close();
        try (InputStream input = new URL(directory, "data.txt").openStream()) {
            assertEquals("odd", new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void ignoresMissingAndAbsoluteDirectoryNames() throws IOException {
        assertNull(loader.getResource("db/absent"));
        assertNull(loader.getResource("/db/migration"));
        assertFalse(loader.getResources("db/absent").hasMoreElements());
        assertEquals(1, Collections.list(loader.getResources("lib")).size());
    }

    private static String read(URL context, String spec) throws IOException {
        try (InputStream input = new URL(context, spec).openStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void write(Path path, String contents) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, contents, StandardCharsets.UTF_8);
    }
}

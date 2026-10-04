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
package io.micronaut.pyronaut.config.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NativeProvidedJarResolverTest {
    @Test
    void resolvesOnlyManifestCoordinatesAndTheirSourcePairs(@TempDir Path directory) throws Exception {
        Files.createFile(directory.resolve("micronaut-jdbc-hikari-6.0.0.jar"));
        Files.createFile(directory.resolve("micronaut-jdbc-hikari-6.0.0-sources.jar"));
        Files.createFile(directory.resolve("micronaut-http-server-6.0.0.jar"));
        String previousArtifacts = System.getProperty(NativeProvidedJarResolver.ARTIFACTS_PROPERTY);
        String previousJars = System.getProperty(NativeProvidedJarResolver.JARS_PROPERTY);
        try {
            System.setProperty(NativeProvidedJarResolver.ARTIFACTS_PROPERTY, "io.micronaut.sql:micronaut-jdbc-hikari");
            System.setProperty(NativeProvidedJarResolver.JARS_PROPERTY, directory.toString());

            List<NativeProvidedJarResolver.JarPair> jars = new NativeProvidedJarResolver().resolve();

            assertEquals(1, jars.size());
            assertEquals("micronaut-jdbc-hikari", jars.getFirst().artifactId());
            assertEquals(directory.resolve("micronaut-jdbc-hikari-6.0.0-sources.jar"), jars.getFirst().source());
        } finally {
            restore(NativeProvidedJarResolver.ARTIFACTS_PROPERTY, previousArtifacts);
            restore(NativeProvidedJarResolver.JARS_PROPERTY, previousJars);
        }
    }

    @Test
    void acceptsAPathSeparatedJarListAndAllowsMissingSources(@TempDir Path directory) throws Exception {
        Path jar = Files.createFile(directory.resolve("micronaut-jdbc-hikari-6.0.0.jar"));
        String previousArtifacts = System.getProperty(NativeProvidedJarResolver.ARTIFACTS_PROPERTY);
        String previousJars = System.getProperty(NativeProvidedJarResolver.JARS_PROPERTY);
        try {
            System.setProperty(NativeProvidedJarResolver.ARTIFACTS_PROPERTY, "io.micronaut.sql:micronaut-jdbc-hikari");
            System.setProperty(NativeProvidedJarResolver.JARS_PROPERTY, jar.toString());

            NativeProvidedJarResolver.JarPair pair = new NativeProvidedJarResolver().resolve().getFirst();

            assertEquals(jar, pair.binary());
            assertNull(pair.source());
        } finally {
            restore(NativeProvidedJarResolver.ARTIFACTS_PROPERTY, previousArtifacts);
            restore(NativeProvidedJarResolver.JARS_PROPERTY, previousJars);
        }
    }

    @Test
    void resolvesSourcesFromAnotherConfiguredMetadataDirectory(@TempDir Path directory) throws Exception {
        Path binaries = Files.createDirectory(directory.resolve("binaries"));
        Path sources = Files.createDirectory(directory.resolve("sources"));
        Path binary = Files.createFile(binaries.resolve("micronaut-jdbc-hikari-6.0.0.jar"));
        Path source = Files.createFile(sources.resolve("micronaut-jdbc-hikari-6.0.0-sources.jar"));
        String previousArtifacts = System.getProperty(NativeProvidedJarResolver.ARTIFACTS_PROPERTY);
        String previousJars = System.getProperty(NativeProvidedJarResolver.JARS_PROPERTY);
        try {
            System.setProperty(NativeProvidedJarResolver.ARTIFACTS_PROPERTY, "io.micronaut.sql:micronaut-jdbc-hikari");
            System.setProperty(NativeProvidedJarResolver.JARS_PROPERTY, binaries + java.io.File.pathSeparator + sources);

            NativeProvidedJarResolver.JarPair pair = new NativeProvidedJarResolver().resolve().getFirst();

            assertEquals(binary, pair.binary());
            assertEquals(source, pair.source());
        } finally {
            restore(NativeProvidedJarResolver.ARTIFACTS_PROPERTY, previousArtifacts);
            restore(NativeProvidedJarResolver.JARS_PROPERTY, previousJars);
        }
    }

    @Test
    void readsCoordinatesFromAManifestFileReference(@TempDir Path directory) throws Exception {
        Files.createFile(directory.resolve("micronaut-jdbc-hikari-6.0.0.jar"));
        Files.createFile(directory.resolve("micronaut-http-server-6.0.0.jar"));
        Path manifest = Files.writeString(directory.resolve("native-provided-classpath.txt"),
            "# provided by the native launcher\nio.micronaut.sql:micronaut-jdbc-hikari\n\nio.micronaut:micronaut-core\n");
        String previousArtifacts = System.getProperty(NativeProvidedJarResolver.ARTIFACTS_PROPERTY);
        String previousJars = System.getProperty(NativeProvidedJarResolver.JARS_PROPERTY);
        try {
            System.setProperty(NativeProvidedJarResolver.ARTIFACTS_PROPERTY, "@" + manifest);
            System.setProperty(NativeProvidedJarResolver.JARS_PROPERTY, directory.toString());

            assertEquals(List.of("io.micronaut.sql:micronaut-jdbc-hikari", "io.micronaut:micronaut-core"),
                NativeProvidedJarResolver.providedArtifactCoordinates());
            List<NativeProvidedJarResolver.JarPair> jars = new NativeProvidedJarResolver().resolve();
            assertEquals(1, jars.size());
            assertEquals("micronaut-jdbc-hikari", jars.getFirst().artifactId());
        } finally {
            restore(NativeProvidedJarResolver.ARTIFACTS_PROPERTY, previousArtifacts);
            restore(NativeProvidedJarResolver.JARS_PROPERTY, previousJars);
        }
    }

    @Test
    void doesNotResolveArtifactsFromInvalidManifestCoordinates(@TempDir Path directory) throws Exception {
        Files.createFile(directory.resolve("micronaut-jdbc-hikari-6.0.0.jar"));
        Files.createFile(directory.resolve("-6.0.0.jar"));
        Path manifest = Files.writeString(directory.resolve("native-provided-classpath.txt"),
            "# ignored:coordinate\n  \nnot-a-coordinate\n :micronaut-jdbc-hikari \nio.micronaut:\n");
        String previousArtifacts = System.getProperty(NativeProvidedJarResolver.ARTIFACTS_PROPERTY);
        String previousJars = System.getProperty(NativeProvidedJarResolver.JARS_PROPERTY);
        try {
            System.setProperty(NativeProvidedJarResolver.ARTIFACTS_PROPERTY, "@" + manifest);
            System.setProperty(NativeProvidedJarResolver.JARS_PROPERTY, directory.toString());

            assertEquals(List.of("io.micronaut:"), NativeProvidedJarResolver.providedArtifactCoordinates());
            assertEquals(List.of(), new NativeProvidedJarResolver().resolve());
        } finally {
            restore(NativeProvidedJarResolver.ARTIFACTS_PROPERTY, previousArtifacts);
            restore(NativeProvidedJarResolver.JARS_PROPERTY, previousJars);
        }
    }

    @Test
    void reportsUnreadableManifestFiles(@TempDir Path directory) {
        String previousArtifacts = System.getProperty(NativeProvidedJarResolver.ARTIFACTS_PROPERTY);
        try {
            for (Path manifest : List.of(directory.resolve("missing-manifest.txt"), directory)) {
                System.setProperty(NativeProvidedJarResolver.ARTIFACTS_PROPERTY, "@" + manifest);

                UncheckedIOException error = assertThrows(UncheckedIOException.class,
                    NativeProvidedJarResolver::providedArtifactCoordinates);

                assertEquals("Failed to read " + NativeProvidedJarResolver.ARTIFACTS_PROPERTY + " manifest " + manifest,
                    error.getMessage());
                assertNotNull(error.getCause());
            }
        } finally {
            restore(NativeProvidedJarResolver.ARTIFACTS_PROPERTY, previousArtifacts);
        }
    }

    private static void restore(String name, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previousValue);
        }
    }
}

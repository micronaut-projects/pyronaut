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
package io.micronaut.pyronaut.dev;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The files the launchers of the development runtime share: the project's paths, the classpath
 * manifests {@code pyronaut install} writes, and what {@code pyronaut process} leaves that the
 * development runtime takes over. It depends on neither the run nor the test launcher, so that
 * each of them loads without the other.
 */
final class DevReloadFiles {

    static final String PYRONAUT_DIR = "__pyronaut__";
    static final String DEV_DIR = "micronaut-dev";
    static final String BUILD_DEPENDENCIES_MANIFEST = "resolved-build-dependencies";
    static final String PROCESSOR_OPTIONS = "resolved-processor-options";
    /**
     * The coordinates of the artifacts a native image holds, which the CLI passes to a native launch.
     */
    static final String NATIVE_PROVIDED_ARTIFACTS = "pyronaut.dev.native.provided.artifacts";

    private DevReloadFiles() {
    }

    /**
     * Resolves a configured path against the project directory.
     *
     * @param root The project directory
     * @param configured The path as configured
     * @return The absolute path
     */
    static Path resolve(Path root, String configured) {
        Path path = Path.of(configured);
        return (path.isAbsolute() ? path : root.resolve(path)).toAbsolutePath().normalize();
    }

    /**
     * @param url A file URL of a classpath
     * @return Its path
     */
    static Path path(URL url) {
        try {
            return Path.of(url.toURI()).toAbsolutePath().normalize();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Not a file URL: " + url, e);
        }
    }

    /**
     * @param file A manifest of paths, one per line
     * @return The paths, none when the file does not exist
     */
    static List<Path> readLines(Path file) {
        return readStrings(file).stream().map(Path::of).toList();
    }

    /**
     * @param file A file of values, one per line
     * @return The values that are not blank, none when the file does not exist
     */
    static List<String> readStrings(Path file) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    /**
     * Writes values, one per line.
     *
     * @param file The file
     * @param lines The values
     * @throws IOException if the file cannot be written
     */
    static void writeLines(Path file, List<String> lines) throws IOException {
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    /**
     * A path without the jars of the artifacts the native image holds, as {@code pyronaut process} runs in a native
     * launch: a processor the image holds runs from the image, not from a jar loaded at runtime. Outside a native
     * launch the CLI names none, and the path is kept as it is.
     *
     * @param paths The path
     * @return The path without those jars
     */
    static List<Path> withoutNativeProvidedArtifacts(List<Path> paths) {
        return withoutNativeProvidedArtifacts(System.getProperty(NATIVE_PROVIDED_ARTIFACTS, ""), paths);
    }

    /**
     * Whether a jar is the one of an artifact: its name, a dash and its version, so that {@code micronaut-inject}
     * does not match {@code micronaut-inject-java}.
     */
    private static boolean isJarOf(String jarName, String artifactId) {
        int length = artifactId.length();
        return jarName.length() > length + 1 && jarName.startsWith(artifactId) && jarName.charAt(length) == '-'
            && Character.isDigit(jarName.charAt(length + 1));
    }

    static List<Path> withoutNativeProvidedArtifacts(String providedArtifacts, List<Path> paths) {
        Set<String> artifactIds = new TreeSet<>();
        for (String coordinate : providedArtifacts.split(",")) {
            String trimmed = coordinate.strip();
            if (!trimmed.isEmpty()) {
                artifactIds.add(trimmed.substring(trimmed.lastIndexOf(':') + 1));
            }
        }
        if (artifactIds.isEmpty()) {
            return paths;
        }
        List<Path> kept = new ArrayList<>(paths.size());
        for (Path path : paths) {
            Path fileName = path.getFileName();
            String name = fileName == null ? "" : fileName.toString();
            if (!name.endsWith(".jar") || artifactIds.stream().noneMatch(artifactId -> isJarOf(name, artifactId))) {
                kept.add(path);
            }
        }
        return kept;
    }

    /**
     * Deletes a directory and what it holds, if it exists.
     *
     * @param directory The directory
     * @throws IOException if a file cannot be deleted
     */
    static void deleteRecursively(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }
}

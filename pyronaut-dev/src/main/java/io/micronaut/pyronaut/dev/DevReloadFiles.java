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
import java.util.Comparator;
import java.util.List;

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

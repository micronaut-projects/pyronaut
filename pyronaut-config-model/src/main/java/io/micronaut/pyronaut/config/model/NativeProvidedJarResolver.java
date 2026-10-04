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

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves metadata JARs shipped beside a native Pyronaut launcher.
 *
 * <p>The resolved paths are deliberately intended for metadata readers only;
 * callers must not append them to an application runtime classpath.</p>
 */
public final class NativeProvidedJarResolver {
    public static final String ARTIFACTS_PROPERTY = "pyronaut.dev.native.provided.artifacts";
    public static final String JARS_PROPERTY = "pyronaut.dev.native.provided.jars";

    public List<JarPair> resolve() {
        Set<String> artifactIds = artifactIds();
        if (artifactIds.isEmpty()) {
            return List.of();
        }
        List<JarPair> resolved = new ArrayList<>();
        for (Path candidate : jarPaths()) {
            String artifactId = artifactId(candidate.getFileName().toString());
            if (artifactId == null || !artifactIds.contains(artifactId)) {
                continue;
            }
            resolved.add(new JarPair(candidate, sourceJar(candidate), artifactId));
        }
        return List.copyOf(resolved);
    }

    /**
     * Returns the Maven coordinates configured by {@link #ARTIFACTS_PROPERTY}.
     *
     * <p>The property holds either a comma-separated coordinate list or
     * {@code @<file>} naming a manifest with one coordinate per line. The file
     * form keeps launcher command lines below the Windows cmd.exe limit.</p>
     *
     * @return the configured coordinates, never {@code null}
     */
    public static List<String> providedArtifactCoordinates() {
        String configured = System.getProperty(ARTIFACTS_PROPERTY, "").trim();
        List<String> values;
        if (configured.startsWith("@")) {
            try {
                values = Files.readAllLines(Path.of(configured.substring(1)));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read " + ARTIFACTS_PROPERTY + " manifest " + configured.substring(1), e);
            }
        } else {
            values = List.of(configured.split(","));
        }
        return values.stream()
            .map(String::trim)
            .filter(value -> !value.isEmpty() && !value.startsWith("#") && value.indexOf(':') > 0)
            .toList();
    }

    private static Set<String> artifactIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (String coordinate : providedArtifactCoordinates()) {
            String trimmed = coordinate.trim();
            int separator = trimmed.indexOf(':');
            if (separator > 0 && separator < trimmed.length() - 1) {
                ids.add(trimmed.substring(separator + 1));
            }
        }
        return ids;
    }

    private static List<Path> jarPaths() {
        List<Path> paths = new ArrayList<>();
        for (String value : System.getProperty(JARS_PROPERTY, "").split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (value.isBlank()) {
                continue;
            }
            Path path = Path.of(value).toAbsolutePath().normalize();
            if (Files.isDirectory(path)) {
                try (var entries = Files.list(path)) {
                    entries.filter(file -> Files.isRegularFile(file) && file.getFileName().toString().endsWith(".jar")
                        && !file.getFileName().toString().endsWith("-sources.jar")).forEach(paths::add);
                } catch (IOException ignored) {
                    // The caller may provide optional launcher locations.
                }
            } else if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar")
                && !path.getFileName().toString().endsWith("-sources.jar")) {
                paths.add(path);
            }
        }
        return paths.stream().distinct().sorted().toList();
    }

    private static Path sourceJar(Path binary) {
        String sourceName = binary.getFileName().toString().replaceFirst("\\.jar$", "-sources.jar");
        for (Path directory : jarDirectories()) {
            Path source = directory.resolve(sourceName);
            if (Files.isRegularFile(source)) {
                return source;
            }
        }
        Path sibling = binary.resolveSibling(sourceName);
        return Files.isRegularFile(sibling) ? sibling : null;
    }

    private static List<Path> jarDirectories() {
        return System.getProperty(JARS_PROPERTY, "").isBlank() ? List.of() :
            java.util.Arrays.stream(System.getProperty(JARS_PROPERTY).split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .filter(value -> !value.isBlank())
                .map(Path::of)
                .map(path -> path.toAbsolutePath().normalize())
                .filter(Files::isDirectory)
                .distinct()
                .toList();
    }

    private static String artifactId(String fileName) {
        if (!fileName.endsWith(".jar") || fileName.endsWith("-sources.jar")) {
            return null;
        }
        String base = fileName.substring(0, fileName.length() - 4);
        for (int index = 0; index < base.length() - 1; index++) {
            if (base.charAt(index) == '-' && Character.isDigit(base.charAt(index + 1))) {
                return base.substring(0, index);
            }
        }
        return null;
    }

    /**
     * A binary jar and its optional sources jar.
     * @param binary binary jar
     * @param source source jar
     * @param artifactId artifact identifier
     */
    public record JarPair(Path binary, Path source, String artifactId) {
    }
}

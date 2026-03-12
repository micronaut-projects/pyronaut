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
package io.micronaut.pyronaut.processor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

final class ProcessorSourceCache {
    static final String MAIN_HASH_FILE = "processor-main.sha256";
    static final String TEST_HASH_FILE = "processor-test.sha256";

    private ProcessorSourceCache() {
    }

    static boolean cacheHit(Path cacheDir, String hashFileName, String hash, Path outputDirectory) {
        Path hashFile = cacheDir.resolve(hashFileName);
        if (!Files.exists(hashFile) || !Files.isDirectory(outputDirectory)) {
            return false;
        }
        try {
            String current = Files.readString(hashFile, StandardCharsets.UTF_8).trim();
            return current.equals(hash);
        } catch (Exception e) {
            throw new PyronautProcessorException("Failed to read processor cache hash: " + hashFile, e);
        }
    }

    static void writeHash(Path cacheDir, String hashFileName, String hash) {
        Path hashFile = cacheDir.resolve(hashFileName);
        try {
            Files.createDirectories(cacheDir);
            Files.writeString(hashFile, hash, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new PyronautProcessorException("Failed to write processor cache hash: " + hashFile, e);
        }
    }

    static String fingerprint(Path pythonSources,
                              Path javaSources,
                              List<Path> annotationProcessorPath,
                              List<Path> classpath,
                              List<String> options) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateString(digest, "python\n");
            updateDirectory(digest, pythonSources, ".py");
            updateString(digest, "java\n");
            updateDirectory(digest, javaSources, ".java");
            updatePathList(digest, "processor-path", annotationProcessorPath);
            updatePathList(digest, "classpath", classpath);
            updateStringList(digest, "options", options);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", e);
        }
    }

    static long countSources(Path sourceDirectory, String extension) {
        if (!Files.isDirectory(sourceDirectory)) {
            return 0L;
        }
        try (Stream<Path> paths = Files.walk(sourceDirectory)) {
            return paths
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .filter(name -> name.endsWith(extension))
                .count();
        } catch (Exception e) {
            throw new PyronautProcessorException("Failed to scan source directory: " + sourceDirectory, e);
        }
    }

    private static void updateDirectory(MessageDigest digest, Path sourceDirectory, String extension) {
        if (!Files.isDirectory(sourceDirectory)) {
            updateString(digest, "missing\n");
            return;
        }
        try (Stream<Path> paths = Files.walk(sourceDirectory)) {
            paths
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(extension))
                .sorted(Comparator.comparing(path -> sourceDirectory.relativize(path).toString()))
                .forEach(path -> {
                    Path relative = sourceDirectory.relativize(path);
                    updateString(digest, relative.toString());
                    updateBytes(digest, readFile(path));
                });
        } catch (Exception e) {
            throw new PyronautProcessorException("Failed to hash source directory: " + sourceDirectory, e);
        }
    }

    private static byte[] readFile(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (Exception e) {
            throw new PyronautProcessorException("Failed to read source file for hash: " + file, e);
        }
    }

    private static void updatePathList(MessageDigest digest, String key, List<Path> values) {
        updateString(digest, key + "\n");
        for (Path value : values) {
            updateString(digest, value.toAbsolutePath().normalize().toString());
            updateString(digest, "\n");
        }
    }

    private static void updateStringList(MessageDigest digest, String key, List<String> values) {
        updateString(digest, key + "\n");
        for (String value : values) {
            updateString(digest, value);
            updateString(digest, "\n");
        }
    }

    private static void updateString(MessageDigest digest, String value) {
        updateBytes(digest, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void updateBytes(MessageDigest digest, byte[] value) {
        digest.update(value);
        digest.update((byte) 0);
    }
}

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
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

final class ProcessorSourceCache {
    static final String MAIN_HASH_FILE = "processor-main.sha256";
    static final String TEST_HASH_FILE = "processor-test.sha256";

    private ProcessorSourceCache() {
    }

    static boolean cacheHit(Path cacheDir, String hashFileName, String hash, Path outputDirectory) {
        return cacheHit(cacheDir, hashFileName, hash, outputDirectory, false);
    }

    static boolean cacheHit(Path cacheDir,
                            String hashFileName,
                            String hash,
                            Path outputDirectory,
                            boolean validateOutputs) {
        Path hashFile = cacheDir.resolve(hashFileName);
        if (!Files.exists(hashFile) || !Files.isDirectory(outputDirectory)) {
            return false;
        }
        try {
            List<String> lines = Files.readAllLines(hashFile, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.getFirst().equals(hash)) {
                return false;
            }
            if (!validateOutputs) {
                return true;
            }
            if (lines.size() == 1) {
                return false;
            }
            Path normalizedOutputDirectory = outputDirectory.toAbsolutePath().normalize();
            Set<String> expected = lines.subList(1, lines.size()).stream()
                .map(ProcessorSourceCache::decodePath)
                .map(path -> path.replace(outputDirectory.getFileSystem().getSeparator(), "/"))
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
            try (Stream<Path> paths = Files.walk(normalizedOutputDirectory)) {
                paths.filter(Files::isRegularFile)
                    .map(normalizedOutputDirectory::relativize)
                    .map(path -> path.toString().replace(outputDirectory.getFileSystem().getSeparator(), "/"))
                    .forEach(expected::remove);
                return expected.isEmpty();
            }
        } catch (Exception e) {
            return false;
        }
    }

    static void writeHash(Path cacheDir, String hashFileName, String hash) {
        writeHash(cacheDir, hashFileName, hash, null);
    }

    static void writeHash(Path cacheDir,
                          String hashFileName,
                          String hash,
                          Path outputDirectory) {
        Path hashFile = cacheDir.resolve(hashFileName);
        try {
            Files.createDirectories(cacheDir);
            StringBuilder contents = new StringBuilder(hash);
            if (outputDirectory != null && Files.isDirectory(outputDirectory)) {
                try (Stream<Path> paths = Files.walk(outputDirectory)) {
                    for (Path output : paths.filter(Files::isRegularFile)
                        .map(outputDirectory::relativize)
                        .sorted()
                        .toList()) {
                        contents.append(System.lineSeparator())
                            .append(encodePath(output));
                    }
                }
            }
            Files.writeString(hashFile, contents, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new PyronautProcessorException("Failed to write processor cache hash: " + hashFile, e);
        }
    }

    static void invalidate(Path cacheDir, String hashFileName) {
        Path hashFile = cacheDir.resolve(hashFileName);
        try {
            Files.deleteIfExists(hashFile);
        } catch (Exception e) {
            throw new PyronautProcessorException("Failed to invalidate processor cache hash: " + hashFile, e);
        }
    }

    static InputSnapshot snapshot(Path pythonSources,
                                  Path javaSources,
                                  List<Path> annotationProcessorPath,
                                  List<Path> classpath,
                                  boolean compilePythonBytecode,
                                  boolean incremental,
                                  String pythonIncrementalMode,
                                  List<String> options,
                                  Path contentCacheFile) {
        return snapshot(pythonSources, javaSources, annotationProcessorPath, classpath,
            compilePythonBytecode, incremental, pythonIncrementalMode, options,
            DigestCache.load(contentCacheFile), contentCacheFile);
    }

    static InputSnapshot snapshot(Path pythonSources,
                                  Path javaSources,
                                  List<Path> annotationProcessorPath,
                                  List<Path> classpath,
                                  boolean compilePythonBytecode,
                                  boolean incremental,
                                  String pythonIncrementalMode,
                                  List<String> options,
                                  DigestCache contentCache,
                                  Path contentCacheFile) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateString(digest, "python\n");
            long sourceCount = updateDirectory(digest, pythonSources, ".py");
            updateString(digest, "java\n");
            sourceCount += updateDirectory(digest, javaSources, ".java");
            updatePathList(digest, "processor-path", annotationProcessorPath, contentCache);
            updatePathList(digest, "classpath", classpath, contentCache);
            updateString(digest, "compile-python-bytecode=" + compilePythonBytecode + "\n");
            updateString(digest, "incremental=" + incremental + "\n");
            updateString(digest, "python-incremental-mode=" + pythonIncrementalMode + "\n");
            updateStringList(digest, "options", options);
            contentCache.store(contentCacheFile);
            return new InputSnapshot(HexFormat.of().formatHex(digest.digest()), sourceCount);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", e);
        }
    }

    static String fingerprint(Path pythonSources,
                              Path javaSources,
                              List<Path> annotationProcessorPath,
                              List<Path> classpath,
                              boolean compilePythonBytecode,
                              boolean incremental,
                              String pythonIncrementalMode,
                              List<String> options) {
        return fingerprint(
            pythonSources,
            javaSources,
            annotationProcessorPath,
            classpath,
            compilePythonBytecode,
            incremental,
            pythonIncrementalMode,
            options,
            null
        );
    }

    static String fingerprint(Path pythonSources,
                              Path javaSources,
                              List<Path> annotationProcessorPath,
                              List<Path> classpath,
                              boolean compilePythonBytecode,
                              boolean incremental,
                              String pythonIncrementalMode,
                              List<String> options,
                              Path contentCacheFile) {
        return snapshot(pythonSources, javaSources, annotationProcessorPath, classpath,
            compilePythonBytecode, incremental, pythonIncrementalMode, options,
            contentCacheFile).fingerprint();
    }

    static String fingerprint(Path pythonSources,
                              Path javaSources,
                              List<Path> annotationProcessorPath,
                              List<Path> classpath,
                              boolean compilePythonBytecode,
                              boolean incremental,
                              List<String> options) {
        return fingerprint(
            pythonSources,
            javaSources,
            annotationProcessorPath,
            classpath,
            compilePythonBytecode,
            incremental,
            "conservative",
            options
        );
    }

    static String fingerprint(Path pythonSources,
                              Path javaSources,
                              List<Path> annotationProcessorPath,
                              List<Path> classpath,
                              boolean compilePythonBytecode,
                              List<String> options) {
        return fingerprint(
            pythonSources,
            javaSources,
            annotationProcessorPath,
            classpath,
            compilePythonBytecode,
            false,
            options
        );
    }

    static String fingerprint(Path pythonSources,
                              Path javaSources,
                              List<Path> annotationProcessorPath,
                              List<Path> classpath,
                              List<String> options) {
        return fingerprint(pythonSources, javaSources, annotationProcessorPath, classpath, false, false, options);
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

    private static long updateDirectory(MessageDigest digest, Path sourceDirectory, String extension) {
        if (!Files.isDirectory(sourceDirectory)) {
            updateString(digest, "missing\n");
            return 0L;
        }
        try (Stream<Path> paths = Files.walk(sourceDirectory)) {
            List<Path> files = paths
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(extension))
                .sorted(Comparator.comparing(path -> sourceDirectory.relativize(path).toString()))
                .toList();
            for (Path path : files) {
                    Path relative = sourceDirectory.relativize(path);
                    updateString(digest, relative.toString());
                    updateBytes(digest, readFile(path));
            }
            return files.size();
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

    private static void updatePathList(MessageDigest digest,
                                       String key,
                                       List<Path> values,
                                       DigestCache contentCache) {
        updateString(digest, key + "\n");
        for (Path value : values) {
            Path normalized = value.toAbsolutePath().normalize();
            updateString(digest, normalized.toString());
            updateString(digest, "\n");
            updatePathContents(digest, normalized, contentCache);
        }
    }

    private static void updatePathContents(MessageDigest digest, Path path, DigestCache contentCache) {
        if (!Files.exists(path)) {
            updateString(digest, "missing\n");
            return;
        }
        if (Files.isRegularFile(path)) {
            updateFileDigest(digest, path, contentCache);
            return;
        }
        if (!Files.isDirectory(path)) {
            updateString(digest, "unsupported\n");
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            paths.filter(Files::isRegularFile)
                .sorted(Comparator.comparing(entry -> path.relativize(entry).toString()))
                .forEach(entry -> {
                    updateString(digest, path.relativize(entry).toString());
                    updateFileDigest(digest, entry, contentCache);
                });
        } catch (Exception e) {
            throw new PyronautProcessorException("Failed to hash classpath entry: " + path, e);
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

    private static void updateFileDigest(MessageDigest digest, Path file, DigestCache contentCache) {
        updateString(digest, contentCache.digest(file));
    }

    private static String encodePath(Path path) {
        String value = path.toString().replace(path.getFileSystem().getSeparator(), "/");
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodePath(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    static record InputSnapshot(String fingerprint, long sourceCount) {
    }

    static final class DigestCache {
        private final Map<String, Entry> entries;
        private final Set<String> seen = new HashSet<>();
        private boolean dirty;

        private DigestCache(Map<String, Entry> entries) {
            this.entries = entries;
        }

        static DigestCache load(Path cacheFile) {
            if (cacheFile == null || !Files.isRegularFile(cacheFile)) {
                return new DigestCache(new HashMap<>());
            }
            Map<String, Entry> entries = new HashMap<>();
            try {
                for (String line : Files.readAllLines(cacheFile, StandardCharsets.UTF_8)) {
                    String[] fields = line.split("\\t", -1);
                    if (fields.length == 4) {
                        entries.put(
                            new String(Base64.getUrlDecoder().decode(fields[0]), StandardCharsets.UTF_8),
                            new Entry(Long.parseLong(fields[1]), Long.parseLong(fields[2]), fields[3])
                        );
                    }
                }
            } catch (Exception ignored) {
                entries.clear();
            }
            return new DigestCache(entries);
        }

        String digest(Path file) {
            Path normalized = file.toAbsolutePath().normalize();
            String key = normalized.toString();
            seen.add(key);
            try {
                long size = Files.size(normalized);
                long modified = Files.getLastModifiedTime(normalized).to(TimeUnit.NANOSECONDS);
                Entry cached = entries.get(key);
                if (cached != null && cached.size == size && cached.modified == modified) {
                    return cached.digest;
                }
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                digest.update(Files.readAllBytes(normalized));
                String value = HexFormat.of().formatHex(digest.digest());
                entries.put(key, new Entry(size, modified, value));
                dirty = true;
                return value;
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 algorithm is unavailable", e);
            } catch (Exception e) {
                throw new PyronautProcessorException("Failed to read source file for hash: " + file, e);
            }
        }

        void store(Path cacheFile) {
            if (cacheFile == null) {
                return;
            }
            try {
                if (entries.size() != seen.size()) {
                    entries.keySet().retainAll(seen);
                    dirty = true;
                }
                if (!dirty) {
                    return;
                }
                Path parent = cacheFile.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                StringBuilder contents = new StringBuilder();
                entries.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> contents.append(Base64.getUrlEncoder().withoutPadding()
                            .encodeToString(entry.getKey().getBytes(StandardCharsets.UTF_8)))
                        .append('\t').append(entry.getValue().size)
                        .append('\t').append(entry.getValue().modified)
                        .append('\t').append(entry.getValue().digest)
                        .append(System.lineSeparator()));
                Files.writeString(cacheFile, contents, StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new PyronautProcessorException("Failed to write processor content cache: " + cacheFile, e);
            }
        }

        private record Entry(long size, long modified, String digest) {
        }
    }
}

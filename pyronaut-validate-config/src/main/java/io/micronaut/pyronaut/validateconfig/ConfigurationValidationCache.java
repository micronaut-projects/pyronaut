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
package io.micronaut.pyronaut.validateconfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class ConfigurationValidationCache {
    private static final String KEY_INPUTS_FINGERPRINT = "inputsFingerprint";
    private static final String KEY_RESOURCES_FINGERPRINT = "resourcesFingerprint";
    private static final String KEY_LAST_RESULT = "lastResult";
    private static final String MISSING = "missing";
    private static final String UNREADABLE = "<unreadable>";

    private ConfigurationValidationCache() {
    }

    static CacheEntry readIfUpToDate(Path cacheFile, String inputsFingerprint, String resourcesFingerprint) {
        if (!Files.isRegularFile(cacheFile)) {
            return null;
        }
        Properties props = new Properties();
        try (InputStream is = Files.newInputStream(cacheFile)) {
            props.load(is);
        } catch (IOException ignored) {
            return null;
        }
        boolean matches = Objects.equals(inputsFingerprint, props.getProperty(KEY_INPUTS_FINGERPRINT))
            && Objects.equals(resourcesFingerprint, props.getProperty(KEY_RESOURCES_FINGERPRINT));
        if (!matches) {
            return null;
        }
        String last = props.getProperty(KEY_LAST_RESULT);
        try {
            return new CacheEntry(last != null ? LastResult.valueOf(last) : LastResult.SUCCESS);
        } catch (IllegalArgumentException ignored) {
            return new CacheEntry(LastResult.SUCCESS);
        }
    }

    static void write(Path cacheFile, String inputsFingerprint, String resourcesFingerprint, LastResult lastResult) throws IOException {
        Files.createDirectories(cacheFile.getParent());
        Properties props = new Properties();
        props.setProperty(KEY_INPUTS_FINGERPRINT, inputsFingerprint);
        props.setProperty(KEY_RESOURCES_FINGERPRINT, resourcesFingerprint);
        props.setProperty(KEY_LAST_RESULT, lastResult.name());
        try (OutputStream os = Files.newOutputStream(cacheFile)) {
            props.store(os, "Pyronaut validate-config cache");
        }
    }

    static String fingerprintResources(List<Path> resourceDirs, Iterable<String> ignorePatterns) throws IOException {
        if (resourceDirs == null || resourceDirs.isEmpty()) {
            return "no-resources";
        }
        MessageDigest digest = sha256();
        for (Path dir : resourceDirs) {
            update(digest, dir.toString());
            update(digest, fingerprintDirectoryContents(dir, ignorePatterns));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String fingerprintClasspath(List<String> classpathElements) {
        if (classpathElements == null || classpathElements.isEmpty()) {
            return "no-classpath";
        }
        MessageDigest digest = sha256();
        for (String classpathElement : classpathElements) {
            if (classpathElement == null || classpathElement.isBlank()) {
                continue;
            }
            Path path;
            try {
                path = Path.of(classpathElement).normalize();
            } catch (Exception ignored) {
                update(digest, "invalid:" + classpathElement);
                continue;
            }
            update(digest, path.toString());
            if (!Files.exists(path)) {
                update(digest, MISSING);
                continue;
            }
            try {
                if (Files.isDirectory(path)) {
                    update(digest, fingerprintDirectoryContents(path, List.of()));
                } else {
                    update(digest, Long.toString(Files.size(path)));
                    FileTime lastModifiedTime = Files.getLastModifiedTime(path);
                    update(digest, Long.toString(lastModifiedTime.toMillis()));
                }
            } catch (IOException ignored) {
                update(digest, UNREADABLE);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String fingerprintDirectoryContents(Path dir, Iterable<String> ignorePatterns) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            return MISSING;
        }
        MessageDigest digest = sha256();
        List<Pattern> globs = compile(ignorePatterns);
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile).sorted().forEach(path -> {
                Path rel = dir.relativize(path);
                if (matchesAny(globs, rel)) {
                    return;
                }
                update(digest, rel.toString());
                try {
                    update(digest, Long.toString(Files.size(path)));
                    update(digest, Long.toString(Files.getLastModifiedTime(path).toMillis()));
                } catch (IOException ignored) {
                    update(digest, UNREADABLE);
                }
            });
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static List<Pattern> compile(Iterable<String> patterns) {
        if (patterns == null) {
            return List.of();
        }
        List<Pattern> out = new ArrayList<>();
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            out.add(Pattern.compile(globToRegex(pattern.trim())));
        }
        return List.copyOf(out);
    }

    private static boolean matchesAny(List<Pattern> patterns, Path relative) {
        if (patterns.isEmpty() || relative == null) {
            return false;
        }
        String normalized = relative.toString().replace('\\', '/');
        for (Pattern pattern : patterns) {
            if (pattern.matcher(normalized).matches()) {
                return true;
            }
        }
        return false;
    }

    private static String globToRegex(String glob) {
        StringBuilder out = new StringBuilder(glob.length() * 2);
        out.append('^');
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                boolean doubleStar = i + 1 < glob.length() && glob.charAt(i + 1) == '*';
                out.append(doubleStar ? ".*" : "[^/]*");
                if (doubleStar) {
                    i++;
                }
            } else if (c == '?') {
                out.append("[^/]");
            } else {
                if (".()[]{}+$^|\\".indexOf(c) >= 0) {
                    out.append('\\');
                }
                out.append(c);
            }
        }
        out.append('$');
        return out.toString();
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    enum LastResult {
        SUCCESS,
        FAILURE
    }

    record CacheEntry(LastResult lastResult) {
    }
}

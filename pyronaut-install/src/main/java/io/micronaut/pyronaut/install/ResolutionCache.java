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
package io.micronaut.pyronaut.install;

import io.micronaut.pyronaut.config.model.PyronautManagedVersions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Cache utilities for resolved dependency manifests.
 */
final class ResolutionCache {
    private static final String HASH_FILE = "pyproject.sha256";
    private static final String INSTALL_CACHE_VERSION = "test-resources-server-classpath-v1";

    private ResolutionCache() {
    }

    static String installHash(Path pyprojectFile, Path localRepositoryPath) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("installCacheVersion=".getBytes(StandardCharsets.UTF_8));
            digest.update(INSTALL_CACHE_VERSION.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Files.readAllBytes(pyprojectFile));
            digest.update((byte) 0);
            digest.update("localRepository=".getBytes(StandardCharsets.UTF_8));
            digest.update(localRepositoryPath.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update("micronautCoreVersion=".getBytes(StandardCharsets.UTF_8));
            digest.update(PyronautManagedVersions.micronautCoreVersion().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update("micronautPlatformVersion=".getBytes(StandardCharsets.UTF_8));
            digest.update(PyronautManagedVersions.micronautPlatformVersion().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update("micronautControlPanelVersion=".getBytes(StandardCharsets.UTF_8));
            digest.update(PyronautManagedVersions.micronautControlPanelVersion().getBytes(StandardCharsets.UTF_8));
            byte[] hashed = digest.digest();
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", e);
        }
    }

    static String externalInstallHash(Path projectRoot, Path localRepositoryPath) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("external-install-v1".getBytes(StandardCharsets.UTF_8));
            for (String name : List.of("pom.xml", "mvnw", "settings.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "gradlew", "gradle.properties")) {
                Path file = projectRoot.resolve(name);
                if (Files.isRegularFile(file)) {
                    digest.update(name.getBytes(StandardCharsets.UTF_8));
                    digest.update(Files.readAllBytes(file));
                }
            }
            for (String directory : List.of(".mvn", "gradle/wrapper", "buildSrc", "gradle")) {
                Path dir = projectRoot.resolve(directory);
                if (Files.isDirectory(dir)) {
                    try (var files = Files.walk(dir)) {
                        for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                            digest.update(projectRoot.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
                            digest.update(Files.readAllBytes(file));
                        }
                    }
                }
            }
            digest.update(localRepositoryPath.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", e);
        }
    }

    static boolean cacheHit(Path cacheDir, String hash, List<InstallScope> scopes) throws IOException {
        Path hashFile = cacheDir.resolve(HASH_FILE);
        if (!Files.exists(hashFile)) {
            return false;
        }
        String cachedHash = Files.readString(hashFile, StandardCharsets.UTF_8).trim();
        if (!cachedHash.equals(hash)) {
            return false;
        }
        for (InstallScope scope : scopes) {
            if (!Files.exists(cacheDir.resolve(scope.manifestFile()))) {
                return false;
            }
        }
        return true;
    }

    static void write(Path cacheDir, String hash, Map<InstallScope, List<String>> resolved) throws IOException {
        Files.createDirectories(cacheDir);
        for (Map.Entry<InstallScope, List<String>> entry : resolved.entrySet()) {
            Path manifest = cacheDir.resolve(entry.getKey().manifestFile());
            Files.write(manifest, entry.getValue(), StandardCharsets.UTF_8);
        }
        Files.writeString(cacheDir.resolve(HASH_FILE), hash, StandardCharsets.UTF_8);
    }
}

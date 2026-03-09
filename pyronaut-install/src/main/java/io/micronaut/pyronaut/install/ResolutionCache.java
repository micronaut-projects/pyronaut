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

    private ResolutionCache() {
    }

    static String pyprojectHash(Path pyprojectFile) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(Files.readAllBytes(pyprojectFile));
            return HexFormat.of().formatHex(hashed);
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

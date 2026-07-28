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

import io.micronaut.pyronaut.config.model.PyprojectModel;
import io.micronaut.pyronaut.config.model.PyronautManagedVersions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Resolves and caches dependencies declared by a direct source invocation. */
public final class DirectSourceDependencyResolver {
    private static final String HASH_FILE = "direct-source-declarations.sha256";
    private final MavenClasspathResolver resolver = new MavenClasspathResolver();

    /**
     * Resolves declarations and persists manifests for subsequent direct-source invocations.
     *
     * @param cacheDirectory persistent direct-source cache directory
     * @param build build-scoped Maven coordinates
     * @param runtime runtime-scoped Maven coordinates
     * @param repositories Maven repository URLs
     * @return resolved classpath manifests and cache status
     * @throws IOException if the cache cannot be read or written
     */
    public Result resolve(Path cacheDirectory, List<String> build, List<String> runtime, List<String> repositories) throws IOException {
        String hash = fingerprint(build, runtime, repositories);
        Path buildManifest = cacheDirectory.resolve(InstallScope.BUILD.manifestFile());
        Path runtimeManifest = cacheDirectory.resolve(InstallScope.RUNTIME.manifestFile());
        if (Files.isRegularFile(cacheDirectory.resolve(HASH_FILE)) && hash.equals(Files.readString(cacheDirectory.resolve(HASH_FILE)).trim())
            && Files.isRegularFile(buildManifest) && Files.isRegularFile(runtimeManifest)) {
            return new Result(read(buildManifest), read(runtimeManifest), true);
        }
        PyprojectModel.Pyronaut pyronaut = new PyprojectModel.Pyronaut(null, PyronautManagedVersions.micronautPlatformVersion(),
            repositories, new PyprojectModel.Dependencies(runtime, List.of(), build, List.of()), null, null, null, null, null, null, null, null, null, null);
        PyprojectModel model = new PyprojectModel(null, null, pyronaut);
        Path local = MavenClasspathResolver.resolveLocalMavenRepository();
        List<String> buildResult = resolver.resolveScope(model, InstallScope.BUILD, local, false).stream().map(Path::toString).toList();
        List<String> runtimeResult = resolver.resolveScope(model, InstallScope.RUNTIME, local, false).stream().map(Path::toString).toList();
        Files.createDirectories(cacheDirectory);
        Files.write(buildManifest, buildResult);
        Files.write(runtimeManifest, runtimeResult);
        Files.writeString(cacheDirectory.resolve(HASH_FILE), hash);
        return new Result(buildResult, runtimeResult, false);
    }

    private static List<String> read(Path path) throws IOException {
        return Files.readAllLines(path).stream().filter(value -> !value.isBlank()).toList();
    }

    /**
     * Computes the declaration fingerprint used for cache invalidation.
     * @param build build declarations
     * @param runtime runtime declarations
     * @param repositories repository declarations
     * @return SHA-256 fingerprint
     */
    static String fingerprint(List<String> build, List<String> runtime, List<String> repositories) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "build", build);
            update(digest, "runtime", runtime);
            update(digest, "repositories", repositories);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void update(MessageDigest digest, String name, List<String> values) {
        digest.update(name.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        for (String value : values) {
            digest.update(value.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
    }
    /**
     * Result of direct-source dependency resolution.
     *
     * @param build resolved build classpath entries
     * @param runtime resolved runtime classpath entries
     * @param cacheHit whether existing manifests were reused
     */
    public record Result(
        List<String> build,
        List<String> runtime,
        boolean cacheHit
    ) {
    }
}

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
import java.util.ArrayList;
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
        DetailedResult detailed = resolveDetailed(
            cacheDirectory,
            build,
            runtime,
            repositories,
            MavenClasspathResolver.resolveLocalMavenRepository(),
            false,
            false,
            List.of()
        );
        return new Result(detailed.build(), detailed.runtime(), detailed.cacheHit());
    }

    /**
     * Resolves declarations with install command repository and cache controls.
     *
     * @param cacheDirectory persistent direct-source cache directory
     * @param build build-scoped Maven coordinates
     * @param runtime runtime-scoped Maven coordinates
     * @param repositories Maven repository URLs
     * @param localRepository local Maven repository
     * @param offline whether repository access is offline
     * @param bypassCache whether manifests must be rewritten
     * @param fingerprintInputs additional source and launcher cache inputs
     * @return detailed resolved classpaths
     * @throws IOException if the cache cannot be read or written
     */
    public DetailedResult resolveDetailed(Path cacheDirectory,
                                          List<String> build,
                                          List<String> runtime,
                                          List<String> repositories,
                                          Path localRepository,
                                          boolean offline,
                                          boolean bypassCache,
                                          List<String> fingerprintInputs) throws IOException {
        String hash = fingerprint(build, runtime, repositories, localRepository, fingerprintInputs);
        Path buildManifest = cacheDirectory.resolve(InstallScope.BUILD.manifestFile());
        Path runtimeManifest = cacheDirectory.resolve(InstallScope.RUNTIME.manifestFile());
        if (!bypassCache && Files.isRegularFile(cacheDirectory.resolve(HASH_FILE)) && hash.equals(Files.readString(cacheDirectory.resolve(HASH_FILE)).trim())
            && Files.isRegularFile(buildManifest) && Files.isRegularFile(runtimeManifest)) {
            List<String> cachedBuild = read(buildManifest);
            List<String> cachedRuntime = read(runtimeManifest);
            return new DetailedResult(
                cachedBuild,
                cachedRuntime,
                artifactsFromClasspath(cachedBuild),
                artifactsFromClasspath(cachedRuntime),
                true
            );
        }
        PyprojectModel.Pyronaut pyronaut = new PyprojectModel.Pyronaut(null, PyronautManagedVersions.micronautPlatformVersion(),
            repositories, new PyprojectModel.Dependencies(runtime, List.of(), build, List.of()), null, null, null, null, null, null, null, null, null, null);
        PyprojectModel model = new PyprojectModel(null, null, pyronaut);
        MavenClasspathResolver.ResolvedScopeDetails buildDetails =
            resolver.resolveScopeDetails(model, InstallScope.BUILD, localRepository, offline, bypassCache);
        MavenClasspathResolver.ResolvedScopeDetails runtimeDetails =
            resolver.resolveScopeDetails(model, InstallScope.RUNTIME, localRepository, offline, bypassCache);
        List<String> buildResult = buildDetails.classpath().stream().map(Path::toString).toList();
        List<String> runtimeResult = runtimeDetails.classpath().stream().map(Path::toString).toList();
        Files.createDirectories(cacheDirectory);
        Files.write(buildManifest, buildResult);
        Files.write(runtimeManifest, runtimeResult);
        Files.writeString(cacheDirectory.resolve(HASH_FILE), hash);
        return new DetailedResult(
            buildResult,
            runtimeResult,
            artifactsFromResolvedDetails(buildDetails.editorArtifacts()),
            artifactsFromResolvedDetails(runtimeDetails.editorArtifacts()),
            false
        );
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
        return fingerprint(build, runtime, repositories, null, List.of());
    }

    static String fingerprint(List<String> build,
                              List<String> runtime,
                              List<String> repositories,
                              Path localRepository,
                              List<String> additionalInputs) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "build", build);
            update(digest, "runtime", runtime);
            update(digest, "repositories", repositories);
            update(digest, "local-repository", localRepository == null
                ? List.of()
                : List.of(localRepository.toAbsolutePath().normalize().toString()));
            update(digest, "additional", additionalInputs);
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

    private static List<ResolvedArtifact> artifactsFromResolvedDetails(List<MavenClasspathResolver.ResolvedEditorArtifact> artifacts) {
        return artifacts.stream()
            .map(artifact -> new ResolvedArtifact(
                artifact.groupId(),
                artifact.artifactId(),
                artifact.version(),
                artifact.binaryJar().toAbsolutePath().normalize().toString(),
                artifact.sourceJar() == null ? null : artifact.sourceJar().toAbsolutePath().normalize().toString()
            ))
            .toList();
    }

    private static List<ResolvedArtifact> artifactsFromClasspath(List<String> classpath) {
        List<ResolvedArtifact> artifacts = new ArrayList<>();
        for (String entry : classpath) {
            Path binary = Path.of(entry).toAbsolutePath().normalize();
            String fileName = binary.getFileName() == null ? "" : binary.getFileName().toString();
            Path source = fileName.endsWith(".jar")
                ? binary.resolveSibling(fileName.substring(0, fileName.length() - 4) + "-sources.jar")
                : null;
            artifacts.add(new ResolvedArtifact(
                null,
                null,
                null,
                binary.toString(),
                source != null && Files.isRegularFile(source) ? source.toString() : null
            ));
        }
        return List.copyOf(artifacts);
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

    /**
     * Detailed direct-source resolution result for editor integration.
     *
     * @param build resolved build classpath
     * @param runtime resolved runtime classpath
     * @param buildArtifacts build artifact metadata
     * @param runtimeArtifacts runtime artifact metadata
     * @param cacheHit whether existing manifests were reused
     */
    public record DetailedResult(
        List<String> build,
        List<String> runtime,
        List<ResolvedArtifact> buildArtifacts,
        List<ResolvedArtifact> runtimeArtifacts,
        boolean cacheHit
    ) {
    }

    /**
     * Resolved editor artifact metadata.
     *
     * @param groupId Maven group, if known
     * @param artifactId Maven artifact, if known
     * @param version Maven version, if known
     * @param binaryJar binary JAR path
     * @param sourceJar source JAR path, if available
     */
    public record ResolvedArtifact(
        String groupId,
        String artifactId,
        String version,
        String binaryJar,
        String sourceJar
    ) {
    }
}

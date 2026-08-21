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

import io.micronaut.core.annotation.Internal;
import io.micronaut.pyronaut.config.model.PyprojectModel;
import io.micronaut.pyronaut.config.model.PyronautManagedVersions;
import io.micronaut.pyronaut.directsource.DirectSourceDeclarations;
import io.micronaut.testresources.core.TestResourcesResolver;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;

/** Resolves and caches dependencies declared by a direct source invocation. */
public final class DirectSourceDependencyResolver {
    private static final String HASH_FILE = "direct-source-declarations.sha256";
    private static final String LAUNCH_METADATA_FILE = "direct-source-launch.properties";
    private static final String LAUNCH_CACHE_VERSION = "3";
    private final MavenClasspathResolver resolver;

    /** Creates a resolver using the configured Maven environment. */
    public DirectSourceDependencyResolver() {
        this(new MavenClasspathResolver());
    }

    DirectSourceDependencyResolver(MavenClasspathResolver resolver) {
        this.resolver = resolver;
    }

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
                List.of(),
                artifactsFromClasspath(cachedBuild),
                artifactsFromClasspath(cachedRuntime),
                true
            );
        }
        PyprojectModel.Pyronaut pyronaut = new PyprojectModel.Pyronaut(null, PyronautManagedVersions.micronautPlatformVersion(),
            repositories, null, new PyprojectModel.Dependencies(runtime, List.of(), build, List.of()), null, null, null, null, null, null, null, null, null, null, false);
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
            List.of(),
            artifactsFromResolvedDetails(buildDetails.editorArtifacts()),
            artifactsFromResolvedDetails(runtimeDetails.editorArtifacts()),
            false
        );
    }

    public DetailedResult resolveDetailed(Path cacheDirectory,
                                          List<DirectSourceDeclarations.Dependency> declarations,
                                          List<String> repositories,
                                          Path localRepository,
                                          boolean offline,
                                          boolean bypassCache,
                                          List<String> fingerprintInputs) throws IOException {
        List<String> build = declarations.stream().filter(DirectSourceDeclarations.Dependency::build).map(DirectSourceDeclarations.Dependency::coordinate).toList();
        List<String> runtime = declarations.stream().filter(d -> d.scope() == DirectSourceDeclarations.Scope.RUNTIME).map(DirectSourceDeclarations.Dependency::coordinate).toList();
        List<String> test = declarations.stream().filter(DirectSourceDeclarations.Dependency::test).map(DirectSourceDeclarations.Dependency::coordinate).toList();
        List<String> boms = declarations.stream().filter(DirectSourceDeclarations.Dependency::bom).map(DirectSourceDeclarations.Dependency::coordinate).toList();
        Map<String, List<String>> artifactExclusions = new LinkedHashMap<>();
        declarations.forEach(d -> artifactExclusions.put(moduleKey(d.coordinate()), d.exclusions()));
        String hash = fingerprint(build, runtime, test, boms, artifactExclusions, repositories, localRepository, fingerprintInputs);
        Path buildManifest = cacheDirectory.resolve(InstallScope.BUILD.manifestFile());
        Path runtimeManifest = cacheDirectory.resolve(InstallScope.RUNTIME.manifestFile());
        Path testManifest = cacheDirectory.resolve(InstallScope.TEST.manifestFile());
        if (!bypassCache && Files.isRegularFile(cacheDirectory.resolve(HASH_FILE)) && hash.equals(Files.readString(cacheDirectory.resolve(HASH_FILE)).trim())
            && Files.isRegularFile(buildManifest) && Files.isRegularFile(runtimeManifest) && Files.isRegularFile(testManifest)) {
            return new DetailedResult(read(buildManifest), read(runtimeManifest), read(testManifest),
                artifactsFromClasspath(read(buildManifest)), artifactsFromClasspath(read(runtimeManifest)), true);
        }
        PyprojectModel model = model(build, runtime, test, boms, artifactExclusions, repositories);
        var buildDetails = resolver.resolveScopeDetails(model, InstallScope.BUILD, localRepository, offline, bypassCache);
        var runtimeDetails = resolver.resolveScopeDetails(model, InstallScope.RUNTIME, localRepository, offline, bypassCache);
        var testDetails = resolver.resolveScopeDetails(model, InstallScope.TEST, localRepository, offline, bypassCache);
        List<String> buildResult = classpathStrings(buildDetails);
        List<String> runtimeResult = classpathStrings(runtimeDetails);
        List<String> testResult = classpathStrings(testDetails);
        Files.createDirectories(cacheDirectory);
        Files.write(buildManifest, buildResult);
        Files.write(runtimeManifest, runtimeResult);
        Files.write(testManifest, testResult);
        Files.writeString(cacheDirectory.resolve(HASH_FILE), hash);
        return new DetailedResult(buildResult, runtimeResult, testResult,
            artifactsFromResolvedDetails(buildDetails.editorArtifacts()), artifactsFromResolvedDetails(runtimeDetails.editorArtifacts()), false);
    }

    /**
     * Resolves a direct-source launch, including conditional Test Resources dependencies.
     *
     * @param cacheDirectory persistent direct-source cache directory
     * @param build build-scoped Maven coordinates
     * @param runtime runtime-scoped Maven coordinates
     * @param repositories Maven repository URLs
     * @param runtimeProperties inline runtime {@code @AppConfig} properties
     * @param testResourcesEligible whether the launch command permits automatic Test Resources
     * @return launch classpaths, conditional Test Resources decision, and cache status
     * @throws IOException if the cache cannot be read or written
     */
    @Internal
    public LaunchResult resolveForLaunch(Path cacheDirectory,
                                         List<String> build,
                                         List<String> runtime,
                                         List<String> repositories,
                                         Map<String, String> runtimeProperties,
                                         boolean testResourcesEligible) throws IOException {
        return resolveForLaunch(cacheDirectory, build, runtime, List.of(), List.of(), Map.of(), repositories, runtimeProperties, testResourcesEligible);
    }

    public LaunchResult resolveForLaunch(Path cacheDirectory,
                                         List<String> build,
                                         List<String> runtime,
                                         List<String> test,
                                         List<String> boms,
                                         Map<String, List<String>> exclusions,
                                         List<String> repositories,
                                         Map<String, String> runtimeProperties,
                                         boolean testResourcesEligible) throws IOException {
        Path localRepository = MavenClasspathResolver.resolveLocalMavenRepository();
        boolean effectiveTestResourcesEligibility =
            testResourcesEligible && !resolver.isTestResourcesDisabledViaEnvironment();
        List<String> launchInputs = launchFingerprintInputs(
            runtimeProperties,
            effectiveTestResourcesEligibility
        );
        String hash = fingerprint(build, runtime, test, boms, exclusions, repositories, localRepository, launchInputs);
        Path buildManifest = cacheDirectory.resolve(InstallScope.BUILD.manifestFile());
        Path runtimeManifest = cacheDirectory.resolve(InstallScope.RUNTIME.manifestFile());
        Path testManifest = cacheDirectory.resolve(InstallScope.TEST.manifestFile());
        Path serverManifest = cacheDirectory.resolve(InstallScope.TEST_RESOURCES_SERVER.manifestFile());
        Path metadata = cacheDirectory.resolve(LAUNCH_METADATA_FILE);
        if (Files.isRegularFile(cacheDirectory.resolve(HASH_FILE))
            && hash.equals(Files.readString(cacheDirectory.resolve(HASH_FILE)).trim())
            && Files.isRegularFile(buildManifest)
            && Files.isRegularFile(runtimeManifest)
            && (test.isEmpty() || Files.isRegularFile(testManifest))
            && Files.isRegularFile(metadata)) {
            boolean required = readTestResourcesRequired(metadata);
            if (!required || Files.isRegularFile(serverManifest)) {
                return new LaunchResult(
                    read(buildManifest),
                    read(runtimeManifest),
                    required ? read(serverManifest) : List.of(),
                    required,
                    true
                );
            }
        }

        PyprojectModel baseModel = model(build, runtime, test, boms, exclusions, repositories, null);
        boolean required = false;
        MavenClasspathResolver.ResolvedScopeDetails serverDetails = null;
        if (effectiveTestResourcesEligibility && runtimeProperties != null && !runtimeProperties.isEmpty()) {
            PyprojectModel enabledModel = model(build, runtime, test, boms, exclusions, repositories, enabledTestResources());
            serverDetails = resolver.resolveScopeDetails(
                enabledModel,
                InstallScope.TEST_RESOURCES_SERVER,
                localRepository,
                false,
                false,
                true
            );
            required = requiresTestResources(serverDetails.classpath(), runtimeProperties);
            if (required) {
                baseModel = enabledModel;
            }
        }

        MavenClasspathResolver.ResolvedScopeDetails buildDetails =
            resolver.resolveScopeDetails(baseModel, InstallScope.BUILD, localRepository, false, false);
        MavenClasspathResolver.ResolvedScopeDetails runtimeDetails =
            resolver.resolveScopeDetails(baseModel, InstallScope.RUNTIME, localRepository, false, false);
        MavenClasspathResolver.ResolvedScopeDetails testDetails =
            resolver.resolveScopeDetails(baseModel, InstallScope.TEST, localRepository, false, false);
        List<String> buildResult = classpathStrings(buildDetails);
        List<String> runtimeResult = classpathStrings(runtimeDetails);
        List<String> testResult = classpathStrings(testDetails);
        List<String> serverResult = required && serverDetails != null ? classpathStrings(serverDetails) : List.of();
        Files.createDirectories(cacheDirectory);
        Files.write(buildManifest, buildResult);
        Files.write(runtimeManifest, runtimeResult);
        Files.write(testManifest, testResult);
        if (required) {
            Files.write(serverManifest, serverResult);
        } else {
            Files.deleteIfExists(serverManifest);
        }
        Files.writeString(metadata, "testResourcesRequired=" + required + System.lineSeparator());
        Files.writeString(cacheDirectory.resolve(HASH_FILE), hash);
        return new LaunchResult(buildResult, runtimeResult, serverResult, required, false);
    }

    private static PyprojectModel model(List<String> build,
                                        List<String> runtime,
                                        List<String> test,
                                        List<String> boms,
                                        Map<String, List<String>> exclusions,
                                        List<String> repositories,
                                        PyprojectModel.TestResources testResources) {
        PyprojectModel.Pyronaut pyronaut = new PyprojectModel.Pyronaut(
            null,
            PyronautManagedVersions.micronautPlatformVersion(),
            repositories,
            null,
            new PyprojectModel.Dependencies(runtime, List.of(), build, test, boms, List.of(), exclusions),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            testResources,
            false
        );
        return new PyprojectModel(null, null, pyronaut);
    }

    private static PyprojectModel.TestResources enabledTestResources() {
        return new PyprojectModel.TestResources(
            false,
            true,
            null,
            null,
            true,
            List.of(),
            null,
            false,
            null,
            null,
            null,
            Map.of(),
            Map.of(),
            false,
            null,
            "none",
            List.of()
        );
    }

    private static List<String> launchFingerprintInputs(Map<String, String> runtimeProperties,
                                                        boolean testResourcesEligible) {
        List<String> inputs = new ArrayList<>();
        inputs.add("launch-cache-version=" + LAUNCH_CACHE_VERSION);
        inputs.add("test-resources-eligible=" + testResourcesEligible);
        if (runtimeProperties != null) {
            runtimeProperties.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> inputs.add("runtime-property=" + entry.getKey() + "=" + entry.getValue()));
        }
        return List.copyOf(inputs);
    }

    private static boolean readTestResourcesRequired(Path metadata) throws IOException {
        return Files.readAllLines(metadata).stream()
            .map(String::trim)
            .anyMatch("testResourcesRequired=true"::equals);
    }

    private static List<String> classpathStrings(MavenClasspathResolver.ResolvedScopeDetails details) {
        return details.classpath().stream().map(Path::toString).toList();
    }

    private static boolean requiresTestResources(List<Path> serverClasspath,
                                                 Map<String, String> runtimeProperties) throws IOException {
        if (serverClasspath.isEmpty()) {
            return false;
        }
        URL[] urls = serverClasspath.stream()
            .map(Path::toUri)
            .map(uri -> {
                try {
                    return uri.toURL();
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            })
            .toArray(URL[]::new);
        try (URLClassLoader classLoader = new URLClassLoader(urls, DirectSourceDependencyResolver.class.getClassLoader())) {
            List<TestResourcesResolver> resolvers;
            try {
                resolvers = ServiceLoader.load(TestResourcesResolver.class, classLoader)
                    .stream()
                    .map(ServiceLoader.Provider::get)
                    .toList();
            } catch (ServiceConfigurationError | LinkageError | RuntimeException e) {
                throw new IOException("Unable to inspect inferred Test Resources providers", e);
            }
            return requiresTestResources(resolvers, runtimeProperties);
        }
    }

    static boolean requiresTestResources(Collection<? extends TestResourcesResolver> resolvers,
                                         Map<String, String> runtimeProperties) throws IOException {
        Map<String, Object> testResourcesConfig = testResourcesConfig(runtimeProperties);
        for (TestResourcesResolver resolver : resolvers) {
            try {
                List<String> requiredEntries = safeList(resolver.getRequiredPropertyEntries());
                Map<String, Collection<String>> propertyEntries = propertyEntries(runtimeProperties, requiredEntries);
                List<String> resolvable = safeList(resolver.getResolvableProperties(propertyEntries, testResourcesConfig));
                boolean configuredNamespace = resolvable.stream()
                    .anyMatch(property -> namespaceConfigured(property, requiredEntries, runtimeProperties.keySet()));
                if (configuredNamespace && resolvable.stream().anyMatch(property -> !runtimeProperties.containsKey(property))) {
                    return true;
                }
            } catch (LinkageError | RuntimeException e) {
                throw new IOException("Unable to inspect inferred Test Resources provider " + resolver.getClass().getName(), e);
            }
        }
        return false;
    }

    private static List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static Map<String, Collection<String>> propertyEntries(Map<String, String> runtimeProperties,
                                                                   List<String> requiredEntries) {
        Map<String, Collection<String>> entries = new LinkedHashMap<>();
        for (String prefix : requiredEntries) {
            Set<String> values = new LinkedHashSet<>();
            String entryPrefix = prefix + ".";
            runtimeProperties.keySet().stream()
                .filter(key -> key.startsWith(entryPrefix))
                .map(key -> key.substring(entryPrefix.length()))
                .map(value -> {
                    int separator = value.indexOf('.');
                    return separator < 0 ? value : value.substring(0, separator);
                })
                .filter(value -> !value.isBlank())
                .sorted()
                .forEach(values::add);
            entries.put(prefix, List.copyOf(values));
        }
        return entries;
    }

    private static Map<String, Object> testResourcesConfig(Map<String, String> runtimeProperties) {
        Map<String, Object> config = new LinkedHashMap<>();
        String prefix = TestResourcesResolver.TEST_RESOURCES_PROPERTY + ".";
        runtimeProperties.entrySet().stream()
            .filter(entry -> entry.getKey().startsWith(prefix))
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> config.put(entry.getKey().substring(prefix.length()), entry.getValue()));
        return config;
    }

    private static boolean namespaceConfigured(String resolvableProperty,
                                               List<String> requiredEntries,
                                               Set<String> configuredProperties) {
        if (resolvableProperty == null || resolvableProperty.isBlank()) {
            return false;
        }
        if (!requiredEntries.isEmpty()) {
            return requiredEntries.stream().anyMatch(entry ->
                resolvableProperty.startsWith(entry + ".")
                    && configuredProperties.stream().anyMatch(property -> property.startsWith(entry + "."))
            );
        }
        int separator = resolvableProperty.indexOf('.');
        String namespace = separator < 0 ? resolvableProperty : resolvableProperty.substring(0, separator);
        return configuredProperties.stream().anyMatch(property ->
            property.equals(namespace) || property.startsWith(namespace + ".")
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

    private static String fingerprint(List<String> build, List<String> runtime, List<String> test, List<String> boms,
                                      Map<String, List<String>> exclusions, List<String> repositories, Path localRepository,
                                      List<String> additionalInputs) {
        List<String> extra = new ArrayList<>(additionalInputs);
        extra.addAll(boms.stream().map(v -> "bom=" + v).toList());
        exclusions.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .forEach(e -> extra.add("exclusions=" + e.getKey() + "=" + e.getValue()));
        return fingerprint(build, runtime, repositories, localRepository,
            java.util.stream.Stream.concat(extra.stream(), test.stream().map(v -> "test=" + v)).toList());
    }

    private static String moduleKey(String coordinate) {
        String[] parts = coordinate.split(":");
        return parts.length >= 2 ? parts[0] + ":" + parts[1] : coordinate;
    }

    private static PyprojectModel model(List<String> build, List<String> runtime, List<String> test,
                                        List<String> boms, Map<String, List<String>> exclusions,
                                        List<String> repositories) {
        PyprojectModel.Pyronaut pyronaut = new PyprojectModel.Pyronaut(null, PyronautManagedVersions.micronautPlatformVersion(),
            repositories, null, new PyprojectModel.Dependencies(runtime, List.of(), build, test, boms, List.of(), exclusions),
            null, null, null, null, null, null, null, null, null, null, false);
        return new PyprojectModel(null, null, pyronaut);
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
     * @param test resolved test classpath
     * @param buildArtifacts build artifact metadata
     * @param runtimeArtifacts runtime artifact metadata
     * @param cacheHit whether existing manifests were reused
     */
    public record DetailedResult(
        List<String> build,
        List<String> runtime,
        List<String> test,
        List<ResolvedArtifact> buildArtifacts,
        List<ResolvedArtifact> runtimeArtifacts,
        boolean cacheHit
    ) {
    }

    /**
     * Internal result used to launch a direct source.
     *
     * @param build resolved build classpath
     * @param runtime resolved runtime classpath
     * @param testResourcesServer inferred Test Resources server classpath
     * @param testResourcesRequired whether inline configuration requires Test Resources
     * @param cacheHit whether existing launch metadata and manifests were reused
     */
    @Internal
    public record LaunchResult(
        List<String> build,
        List<String> runtime,
        List<String> testResourcesServer,
        boolean testResourcesRequired,
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

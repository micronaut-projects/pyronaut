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
import io.micronaut.pyronaut.config.model.PyprojectModelException;
import io.micronaut.pyronaut.config.model.PyronautManagedVersions;
import io.micronaut.testresources.buildtools.MavenDependency;
import io.micronaut.testresources.buildtools.ModuleIdentifier;
import io.micronaut.testresources.buildtools.TestResourcesClasspath;
import io.micronaut.testresources.buildtools.VersionInfo;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession.CloseableSession;
import org.eclipse.aether.RepositorySystemSession.SessionBuilder;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.repository.Authentication;
import org.eclipse.aether.repository.Proxy;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.supplier.SessionBuilderSupplier;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.DependencyFilterUtils;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.eclipse.aether.util.repository.DefaultProxySelector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Resolves classpaths using Apache Maven Resolver.
 */
final class MavenClasspathResolver {
    private static final String TEST_RESOURCES_CLIENT_MODULE = "io.micronaut.testresources:micronaut-test-resources-client";
    private static final String TEST_RESOURCES_SERVER_MODULE = "io.micronaut.testresources:micronaut-test-resources-server";
    private static final String MICRONAUT_TOML_MODULE = "io.micronaut.toml:micronaut-toml";
    private static final String MICRONAUT_CONTEXT_PYTHON_MODULE = "io.micronaut:micronaut-context-python";
    private static final String MICRONAUT_INJECT_PYTHON_MODULE = "io.micronaut:micronaut-inject-python";
    private static final String MICRONAUT_MANAGEMENT_MODULE = "io.micronaut:micronaut-management";
    private static final String CONTROL_PANEL_MANAGEMENT_MODULE = "io.micronaut.controlpanel:micronaut-control-panel-management";
    private static final String CONTROL_PANEL_UI_MODULE = "io.micronaut.controlpanel:micronaut-control-panel-ui";
    private static final String MICRONAUT_SECURITY_GROUP = "io.micronaut.security";
    private static final String MICRONAUT_SECURITY_ARTIFACT_PREFIX = "micronaut-security";
    private static final String JUNIT_PLATFORM_LAUNCHER_MODULE = "org.junit.platform:junit-platform-launcher";
    private static final String JUNIT_JUPITER_ENGINE_MODULE = "org.junit.jupiter:junit-jupiter-engine";
    private static final String PYRONAUT_GROUP = "io.micronaut.pyronaut";
    private static final String PYRONAUT_BOM_ARTIFACT = "micronaut-pyronaut-bom";
    private static final Set<String> EXTRA_FORBIDDEN_SERVER_MODULES = Set.of(
        "io.micronaut.testresources:micronaut-test-resources-build-tools",
        "io.micronaut.testresources:micronaut-test-resources-client",
        TEST_RESOURCES_SERVER_MODULE
    );

    private final RepositorySystem repositorySystem;
    private final ProxyConfigurationLoader proxyConfigurationLoader;
    private final Function<String, String> envReader;
    private final Supplier<String> pyronautVersionProvider;

    MavenClasspathResolver() {
        this(newRepositorySystem(), new ProxyConfigurationLoader(), System::getenv, MavenClasspathResolver::resolvePyronautVersion);
    }

    MavenClasspathResolver(ProxyConfigurationLoader proxyConfigurationLoader) {
        this(newRepositorySystem(), proxyConfigurationLoader, System::getenv, MavenClasspathResolver::resolvePyronautVersion);
    }

    MavenClasspathResolver(ProxyConfigurationLoader proxyConfigurationLoader,
                          Function<String, String> envReader) {
        this(newRepositorySystem(), proxyConfigurationLoader, envReader, MavenClasspathResolver::resolvePyronautVersion);
    }

    MavenClasspathResolver(ProxyConfigurationLoader proxyConfigurationLoader,
                           Function<String, String> envReader,
                           Supplier<String> pyronautVersionProvider) {
        this(newRepositorySystem(), proxyConfigurationLoader, envReader, pyronautVersionProvider);
    }

    MavenClasspathResolver(RepositorySystem repositorySystem, ProxyConfigurationLoader proxyConfigurationLoader) {
        this(repositorySystem, proxyConfigurationLoader, System::getenv, MavenClasspathResolver::resolvePyronautVersion);
    }

    MavenClasspathResolver(RepositorySystem repositorySystem,
                           ProxyConfigurationLoader proxyConfigurationLoader,
                           Function<String, String> envReader) {
        this(repositorySystem, proxyConfigurationLoader, envReader, MavenClasspathResolver::resolvePyronautVersion);
    }

    MavenClasspathResolver(RepositorySystem repositorySystem,
                           ProxyConfigurationLoader proxyConfigurationLoader,
                           Function<String, String> envReader,
                           Supplier<String> pyronautVersionProvider) {
        this.repositorySystem = repositorySystem;
        this.proxyConfigurationLoader = proxyConfigurationLoader;
        this.envReader = envReader;
        this.pyronautVersionProvider = pyronautVersionProvider;
    }

    List<Path> resolveScope(PyprojectModel model,
                            InstallScope scope,
                            Path localRepositoryPath,
                            boolean offline) {
        return resolveScopeDetails(model, scope, localRepositoryPath, offline).classpath();
    }

    ResolvedScopeDetails resolveScopeDetails(PyprojectModel model,
                                             InstallScope scope,
                                             Path localRepositoryPath,
                                             boolean offline) {
        return resolveScopeDetails(model, scope, localRepositoryPath, offline, false);
    }

    ResolvedScopeDetails resolveScopeDetails(PyprojectModel model,
                                             InstallScope scope,
                                             Path localRepositoryPath,
                                             boolean offline,
                                             boolean forceUpdates) {
        List<RemoteRepository> repositories = toRepositories(repositoriesForModel(model), forceUpdates);
        ProxyConfigurationLoader.ProxyConfiguration proxyConfiguration = proxyConfigurationLoader.load().orElse(null);
        try (CloseableSession session = newSession(localRepositoryPath, offline, proxyConfiguration, forceUpdates)) {
            List<Dependency> managedDependencies = managedDependencies(model, repositories, session);
            Map<String, String> managedVersions = new LinkedHashMap<>();
            for (Dependency dependency : managedDependencies) {
                Artifact artifact = dependency.getArtifact();
                if (artifact == null || artifact.getVersion() == null || artifact.getVersion().isBlank()) {
                    continue;
                }
                managedVersions.putIfAbsent(artifact.getGroupId() + ":" + artifact.getArtifactId(), artifact.getVersion());
            }

            List<String> coordinates = coordinatesForScope(model, scope, managedVersions);
            if (coordinates.isEmpty()) {
                return new ResolvedScopeDetails(List.of(), null, List.of());
            }

            CollectRequest collectRequest = new CollectRequest();
            collectRequest.setRepositories(repositories);
            if (scope != InstallScope.TEST_RESOURCES_SERVER) {
                managedDependencies.forEach(collectRequest::addManagedDependency);
            }

            for (String coordinate : coordinates) {
                collectRequest.addDependency(toDependency(coordinate, managedVersions));
            }

            DependencyRequest dependencyRequest = new DependencyRequest(
                collectRequest,
                DependencyFilterUtils.classpathFilter(JavaScopes.RUNTIME)
            );

            DependencyResult result = repositorySystem.resolveDependencies(session, dependencyRequest);
            if (forceUpdates && !offline && evictResolvedArtifacts(localRepositoryPath, result)) {
                result = repositorySystem.resolveDependencies(session, dependencyRequest);
            }
            List<Path> classpath = result.getArtifactResults().stream()
                .map(artifactResult -> artifactResult.getArtifact())
                .filter(Objects::nonNull)
                .map(Artifact::getPath)
                .filter(Objects::nonNull)
                .map(Path::toAbsolutePath)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
            List<ResolvedEditorArtifact> editorArtifacts = result.getArtifactResults().stream()
                .map(ArtifactResult::getArtifact)
                .filter(Objects::nonNull)
                .map(artifact -> toResolvedEditorArtifact(artifact, repositories, session))
                .toList();
            validateProductionControlPanelSecurity(model, scope, result);
            return new ResolvedScopeDetails(classpath, result.getRoot(), editorArtifacts);
        } catch (DependencyResolutionException e) {
            String message = "Dependency resolution failed for scope '" + scope.cliValue() + "': " + e.getMessage();
            if (proxyConfiguration != null) {
                message = message + " (proxy " + proxyConfiguration.summary() + ")";
            }
            throw new PyprojectModelException(message, e);
        }
    }

    private static boolean evictResolvedArtifacts(Path localRepositoryPath, DependencyResult result) {
        Path localRepositoryRoot = localRepositoryPath.toAbsolutePath().normalize();
        boolean evicted = false;
        for (ArtifactResult artifactResult : result.getArtifactResults()) {
            Artifact artifact = artifactResult.getArtifact();
            if (artifact == null || artifact.getPath() == null) {
                continue;
            }
            Path artifactPath = artifact.getPath().toAbsolutePath().normalize();
            if (!artifactPath.startsWith(localRepositoryRoot)) {
                continue;
            }
            if (isMavenLocalArtifact(artifactResult, artifactPath)) {
                continue;
            }
            evicted |= deleteLocalArtifactFile(artifactPath);
        }
        return evicted;
    }

    private static boolean isMavenLocalArtifact(ArtifactResult artifactResult, Path artifactPath) {
        if (artifactResult.getRepository() != null && "mavenLocal".equals(artifactResult.getRepository().getId())) {
            return true;
        }
        Path remoteRepositories = artifactPath.resolveSibling("_remote.repositories");
        if (!Files.exists(remoteRepositories)) {
            return true;
        }
        try {
            String localInstallMarker = artifactPath.getFileName() + ">=";
            return Files.readAllLines(remoteRepositories).stream()
                .anyMatch(line -> line.equals(localInstallMarker));
        } catch (IOException e) {
            throw new PyprojectModelException("Failed reading local dependency provenance: " + remoteRepositories, e);
        }
    }

    private static boolean deleteLocalArtifactFile(Path artifactPath) {
        boolean deleted = false;
        try {
            deleted |= Files.deleteIfExists(artifactPath);
            deleted |= Files.deleteIfExists(artifactPath.resolveSibling(artifactPath.getFileName() + ".sha1"));
            deleted |= Files.deleteIfExists(artifactPath.resolveSibling(artifactPath.getFileName() + ".md5"));
            deleted |= Files.deleteIfExists(artifactPath.resolveSibling(artifactPath.getFileName() + ".lastUpdated"));
        } catch (IOException e) {
            throw new PyprojectModelException("Failed refreshing local dependency artifact: " + artifactPath, e);
        }
        return deleted;
    }

    private List<String> coordinatesForScope(PyprojectModel model,
                                             InstallScope scope,
                                             Map<String, String> managedVersions) {
        if (model.pyronaut() == null || model.pyronaut().dependencies() == null) {
            return List.of();
        }
        PyprojectModel.Dependencies dependencies = model.pyronaut().dependencies();
        if (scope == InstallScope.BUILD) {
            LinkedHashSet<String> build = new LinkedHashSet<>();
            if (dependencies.build() != null) {
                build.addAll(dependencies.build());
            }
            addDefaultCoordinate(build, MICRONAUT_INJECT_PYTHON_MODULE, managedVersions);
            addDefaultCoordinate(build, MICRONAUT_CONTEXT_PYTHON_MODULE, managedVersions);
            return List.copyOf(build);
        }
        if (scope == InstallScope.RUNTIME) {
            LinkedHashSet<String> runtime = new LinkedHashSet<>();
            if (dependencies.runtime() != null) {
                runtime.addAll(dependencies.runtime());
            }
            addDefaultCoordinate(runtime, MICRONAUT_CONTEXT_PYTHON_MODULE, managedVersions);
            addDefaultCoordinate(runtime, MICRONAUT_TOML_MODULE, managedVersions);
            String testResourcesClient = testResourcesClientCoordinate(model, managedVersions);
            if (testResourcesClient != null) {
                runtime.add(testResourcesClient);
            }
            if (controlPanelProductionEnabled(model)) {
                addDefaultCoordinate(runtime, MICRONAUT_MANAGEMENT_MODULE, managedVersions);
                runtime.add(controlPanelManagementCoordinate());
                runtime.add(controlPanelUiCoordinate());
            }
            return List.copyOf(runtime);
        }
        if (scope == InstallScope.DEVELOPMENT_RUNTIME) {
            LinkedHashSet<String> runtime = new LinkedHashSet<>(coordinatesForScope(model, InstallScope.RUNTIME, managedVersions));
            addDefaultCoordinate(runtime, MICRONAUT_MANAGEMENT_MODULE, managedVersions);
            if (controlPanelEnabled(model)) {
                runtime.add(controlPanelManagementCoordinate());
                runtime.add(controlPanelUiCoordinate());
            }
            return List.copyOf(runtime);
        }
        if (scope == InstallScope.TEST_RESOURCES_SERVER) {
            return testResourcesServerCoordinates(model, managedVersions);
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        merged.addAll(coordinatesForScope(model, InstallScope.RUNTIME, managedVersions));
        if (dependencies.test() != null) {
            merged.addAll(dependencies.test());
        }
        addDefaultCoordinate(merged, JUNIT_PLATFORM_LAUNCHER_MODULE, managedVersions);
        addDefaultCoordinate(merged, JUNIT_JUPITER_ENGINE_MODULE, managedVersions);
        return List.copyOf(merged);
    }

    private List<String> testResourcesServerCoordinates(PyprojectModel model,
                                                        Map<String, String> managedVersions) {
        if (isTestResourcesDisabledViaEnvironment()) {
            return List.of();
        }
        if (model.pyronaut() == null || model.pyronaut().testResources() == null) {
            return List.of();
        }
        PyprojectModel.TestResources testResources = model.pyronaut().testResources();
        if (!Boolean.TRUE.equals(testResources.enabled())) {
            return List.of();
        }
        if (!testResources.configured() && !supportsDefaultTestResourcesResolution(model, testResources)) {
            return List.of();
        }

        String version = defaultTestResourcesVersion(testResources, managedVersions);
        LinkedHashSet<MavenDependency> coordinates = new LinkedHashSet<>();
        List<MavenDependency> appDependencies = appDependenciesForServerInference(model);
        List<MavenDependency> inferred = version == null
            ? TestResourcesClasspath.inferTestResourcesClasspath(appDependencies)
            : TestResourcesClasspath.inferTestResourcesClasspath(appDependencies, version);
        coordinates.addAll(inferred);

        List<String> additionalModules = testResources.additionalModules() == null ? List.of() : testResources.additionalModules();
        for (String additionalModule : additionalModules) {
            MavenDependency normalized = normalizeAdditionalModuleCoordinate(additionalModule, version);
            if (normalized != null) {
                coordinates.add(normalized);
            }
        }

        return coordinates.stream()
            .filter(this::isDependencyAllowedOnServerClasspath)
            .map(MavenClasspathResolver::coordinate)
            .toList();
    }

    private List<MavenDependency> appDependenciesForServerInference(PyprojectModel model) {
        if (model.pyronaut() == null || model.pyronaut().dependencies() == null) {
            return List.of();
        }
        PyprojectModel.Dependencies dependencies = model.pyronaut().dependencies();
        LinkedHashSet<MavenDependency> appCoordinates = new LinkedHashSet<>();
        if (dependencies.runtime() != null) {
            dependencies.runtime().stream()
                .map(this::toMavenDependency)
                .filter(Objects::nonNull)
                .forEach(appCoordinates::add);
        }
        if (dependencies.test() != null) {
            dependencies.test().stream()
                .map(this::toMavenDependency)
                .filter(Objects::nonNull)
                .forEach(appCoordinates::add);
        }
        return List.copyOf(appCoordinates);
    }

    private MavenDependency normalizeAdditionalModuleCoordinate(String value, String testResourcesVersion) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.contains(":")) {
            String[] parts = trimmed.split(":");
            if (parts.length == 2) {
                return new MavenDependency(parts[0], parts[1], testResourcesVersion);
            }
            if (parts.length == 3) {
                return new MavenDependency(parts[0], parts[1], parts[2]);
            }
            throw new PyprojectModelException("Invalid dependency coordinate: '" + trimmed + "'. Expected group:artifact[:version]");
        }

        String artifactId = trimmed.startsWith("micronaut-test-resources-")
            ? trimmed
            : "micronaut-test-resources-" + trimmed;
        return new MavenDependency("io.micronaut.testresources", artifactId, testResourcesVersion);
    }

    private boolean isDependencyAllowedOnServerClasspath(MavenDependency dependency) {
        if (EXTRA_FORBIDDEN_SERVER_MODULES.contains(moduleKey(dependency))) {
            return false;
        }
        return TestResourcesClasspath.isDependencyAllowedOnServerClasspath(
            new ModuleIdentifier(dependency.getGroup(), dependency.getArtifact())
        );
    }

    private static String coordinate(MavenDependency dependency) {
        if (dependency.getVersion() == null || dependency.getVersion().isBlank()) {
            return dependency.getGroup() + ":" + dependency.getArtifact();
        }
        return dependency.getGroup() + ":" + dependency.getArtifact() + ":" + dependency.getVersion();
    }

    private MavenDependency toMavenDependency(String coordinate) {
        if (coordinate == null) {
            return null;
        }
        String trimmed = coordinate.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String[] parts = trimmed.split(":");
        if (parts.length == 2) {
            return new MavenDependency(parts[0], parts[1], null);
        }
        if (parts.length == 3) {
            return new MavenDependency(parts[0], parts[1], parts[2]);
        }
        throw new PyprojectModelException("Invalid dependency coordinate: '" + coordinate + "'. Expected group:artifact[:version]");
    }

    private static String moduleKey(MavenDependency dependency) {
        return dependency.getGroup() + ":" + dependency.getArtifact();
    }

    private static boolean controlPanelEnabled(PyprojectModel model) {
        return model.pyronaut() != null
            && model.pyronaut().controlPanel() != null
            && Boolean.TRUE.equals(model.pyronaut().controlPanel().enabled());
    }

    private static boolean controlPanelProductionEnabled(PyprojectModel model) {
        return model.pyronaut() != null
            && model.pyronaut().controlPanel() != null
            && Boolean.TRUE.equals(model.pyronaut().controlPanel().productionEnabled());
    }

    private static String controlPanelUiCoordinate() {
        return controlPanelCoordinate(CONTROL_PANEL_UI_MODULE);
    }

    private static String controlPanelManagementCoordinate() {
        return controlPanelCoordinate(CONTROL_PANEL_MANAGEMENT_MODULE);
    }

    private static String controlPanelCoordinate(String module) {
        String version = normalizedVersion(PyronautManagedVersions.micronautControlPanelVersion());
        if (version == null) {
            throw new PyprojectModelException("Missing bundled Micronaut Control Panel version");
        }
        return module + ":" + version;
    }

    private static void validateProductionControlPanelSecurity(PyprojectModel model,
                                                               InstallScope scope,
                                                               DependencyResult result) {
        if (!controlPanelProductionEnabled(model)) {
            return;
        }
        if (scope != InstallScope.RUNTIME && scope != InstallScope.DEVELOPMENT_RUNTIME && scope != InstallScope.TEST) {
            return;
        }
        boolean hasSecurity = result.getArtifactResults().stream()
            .map(ArtifactResult::getArtifact)
            .filter(Objects::nonNull)
            .anyMatch(MavenClasspathResolver::isMicronautSecurityArtifact);
        if (!hasSecurity) {
            throw new PyprojectModelException(
                "tool.pyronaut.control-panel.production-enabled requires a Micronaut Security runtime dependency"
            );
        }
    }

    private static boolean isMicronautSecurityArtifact(Artifact artifact) {
        return MICRONAUT_SECURITY_GROUP.equals(artifact.getGroupId())
            && artifact.getArtifactId() != null
            && artifact.getArtifactId().startsWith(MICRONAUT_SECURITY_ARTIFACT_PREFIX);
    }

    private static String normalizedVersion(String version) {
        if (version == null) {
            return null;
        }
        String trimmed = version.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String resolvePyronautVersion() {
        Package pkg = MavenClasspathResolver.class.getPackage();
        if (pkg == null) {
            return null;
        }
        return pkg.getImplementationVersion();
    }

    private static boolean requiresPyronautManagedDependencies(PyprojectModel model) {
        if (model.pyronaut() == null || model.pyronaut().dependencies() == null) {
            return false;
        }
        PyprojectModel.Dependencies dependencies = model.pyronaut().dependencies();
        return containsVersionlessPyronautDependency(dependencies.runtime())
            || containsVersionlessPyronautDependency(dependencies.build())
            || containsVersionlessPyronautDependency(dependencies.test());
    }

    private static boolean containsVersionlessPyronautDependency(List<String> coordinates) {
        if (coordinates == null || coordinates.isEmpty()) {
            return false;
        }
        for (String coordinate : coordinates) {
            if (coordinate == null) {
                continue;
            }
            String trimmed = coordinate.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split(":");
            if (parts.length == 2 && PYRONAUT_GROUP.equals(parts[0])) {
                return true;
            }
        }
        return false;
    }

    private String testResourcesClientCoordinate(PyprojectModel model,
                                                 Map<String, String> managedVersions) {
        if (isTestResourcesDisabledViaEnvironment()) {
            return null;
        }
        if (model.pyronaut() == null || model.pyronaut().testResources() == null) {
            return null;
        }
        PyprojectModel.TestResources testResources = model.pyronaut().testResources();
        if (!Boolean.TRUE.equals(testResources.enabled())) {
            return null;
        }
        if (!testResources.configured() && !supportsDefaultTestResourcesResolution(model, testResources)) {
            return null;
        }
        return TEST_RESOURCES_CLIENT_MODULE + ":" + defaultTestResourcesVersion(testResources, managedVersions);
    }

    private boolean supportsDefaultTestResourcesResolution(PyprojectModel model,
                                                           PyprojectModel.TestResources testResources) {
        if (testResources.version() != null && !testResources.version().isBlank()) {
            return true;
        }
        if (model.pyronaut() == null
            || (model.pyronaut().coreVersion() == null && model.pyronaut().platformVersion() == null)) {
            return false;
        }
        return normalizedVersion(model.pyronaut().coreVersion()) != null
            || normalizedVersion(model.pyronaut().platformVersion()) != null;
    }

    private static String defaultTestResourcesVersion(PyprojectModel.TestResources testResources,
                                                      Map<String, String> managedVersions) {
        String configured = normalizedVersion(testResources.version());
        if (configured != null) {
            return configured;
        }
        String managedServer = normalizedVersion(managedVersions.get(TEST_RESOURCES_SERVER_MODULE));
        if (managedServer != null) {
            return managedServer;
        }
        String managedClient = normalizedVersion(managedVersions.get(TEST_RESOURCES_CLIENT_MODULE));
        if (managedClient != null) {
            return managedClient;
        }
        return normalizedVersion(VersionInfo.getVersion());
    }

    private static void addDefaultCoordinate(Set<String> coordinates, String module, Map<String, String> managedVersions) {
        String coordinate = defaultManagedCoordinate(module, managedVersions);
        if (coordinate != null) {
            coordinates.add(coordinate);
        }
    }

    private static String defaultManagedCoordinate(String module, Map<String, String> managedVersions) {
        String version = normalizedVersion(managedVersions.get(module));
        return version == null ? null : module + ":" + version;
    }

    private boolean isTestResourcesDisabledViaEnvironment() {
        String value = envReader.apply("PYRONAUT_TEST_RESOURCES_DISABLED");
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("1")
            || normalized.equals("true")
            || normalized.equals("yes")
            || normalized.equals("on");
    }

    private List<Dependency> managedDependencies(PyprojectModel model,
                                                 List<RemoteRepository> repositories,
                                                 CloseableSession session) {
        if (model.pyronaut() == null) {
            return List.of();
        }
        String coreVersion = normalizedVersion(model.pyronaut().coreVersion());
        String platformVersion = normalizedVersion(model.pyronaut().platformVersion());
        if (coreVersion == null && platformVersion == null) {
            return List.of();
        }
        if (platformVersion == null) {
            platformVersion = coreVersion;
        }
        Map<String, Dependency> managed = new LinkedHashMap<>();
        LinkedHashSet<String> visitedBoms = new LinkedHashSet<>();
        if (coreVersion != null) {
            addManagedDependenciesFromBom(
                new DefaultArtifact("io.micronaut", "micronaut-core-bom", "", "pom", coreVersion),
                repositories,
                session,
                visitedBoms,
                managed
            );
        }
        addManagedDependenciesFromBom(
            new DefaultArtifact("io.micronaut.platform", "micronaut-platform", "", "pom", platformVersion),
            repositories,
            session,
            visitedBoms,
            managed
        );
        if (requiresPyronautManagedDependencies(model)) {
            String toolVersion = normalizedVersion(pyronautVersionProvider.get());
            if (toolVersion == null) {
                throw new PyprojectModelException(
                    "Versionless Pyronaut dependencies require the running Pyronaut tool version, but it is unavailable"
                );
            }
            addManagedDependenciesFromBom(
                new DefaultArtifact(PYRONAUT_GROUP, PYRONAUT_BOM_ARTIFACT, "", "pom", toolVersion),
                repositories,
                session,
                visitedBoms,
                managed
            );
        }
        return List.copyOf(managed.values());
    }

    private void addManagedDependenciesFromBom(Artifact bomArtifact,
                                               List<RemoteRepository> repositories,
                                               CloseableSession session,
                                               LinkedHashSet<String> visitedBoms,
                                               Map<String, Dependency> managedDependencies) {
        String bomKey = bomArtifact.getGroupId() + ":" + bomArtifact.getArtifactId() + ":" + bomArtifact.getVersion();
        if (!visitedBoms.add(bomKey)) {
            return;
        }

        ArtifactDescriptorRequest request = new ArtifactDescriptorRequest();
        request.setArtifact(bomArtifact);
        request.setRepositories(repositories);
        ArtifactDescriptorResult result;
        try {
            result = repositorySystem.readArtifactDescriptor(session, request);
        } catch (ArtifactDescriptorException e) {
            throw new PyprojectModelException("Failed to read managed dependency BOM: " + bomArtifact, e);
        }

        for (Dependency dependency : result.getManagedDependencies()) {
            Artifact artifact = dependency.getArtifact();
            if (artifact == null) {
                continue;
            }
            if ("pom".equals(artifact.getExtension()) && "import".equals(dependency.getScope())) {
                addManagedDependenciesFromBom(artifact, repositories, session, visitedBoms, managedDependencies);
                continue;
            }
            managedDependencies.putIfAbsent(managedDependencyKey(artifact), dependency);
            if ("pom".equals(artifact.getExtension())) {
                addManagedDependenciesFromBom(artifact, repositories, session, visitedBoms, managedDependencies);
            } else if (artifact.getArtifactId().endsWith("-bom")) {
                addManagedDependenciesFromOptionalBom(
                    new DefaultArtifact(artifact.getGroupId(), artifact.getArtifactId(), "", "pom", artifact.getVersion()),
                    repositories,
                    session,
                    visitedBoms,
                    managedDependencies
                );
            }
        }
    }

    private void addManagedDependenciesFromOptionalBom(Artifact bomArtifact,
                                                       List<RemoteRepository> repositories,
                                                       CloseableSession session,
                                                       LinkedHashSet<String> visitedBoms,
                                                       Map<String, Dependency> managedDependencies) {
        try {
            addManagedDependenciesFromBom(bomArtifact, repositories, session, visitedBoms, managedDependencies);
        } catch (PyprojectModelException ignored) {
            // Some managed artifacts use a *-bom name without being published as a Maven BOM.
        }
    }

    private static String managedDependencyKey(Artifact artifact) {
        String classifier = artifact.getClassifier() == null ? "" : artifact.getClassifier();
        return artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getExtension() + ":" + classifier;
    }

    private static Dependency toDependency(String coordinate, Map<String, String> managedVersions) {
        String[] parts = coordinate.split(":");
        if (parts.length == 3) {
            return new Dependency(new DefaultArtifact(parts[0], parts[1], "jar", parts[2]), JavaScopes.RUNTIME);
        }
        if (parts.length == 2) {
            String managedVersion = managedVersions.get(parts[0] + ":" + parts[1]);
            return new Dependency(new DefaultArtifact(parts[0], parts[1], "jar", managedVersion), JavaScopes.RUNTIME);
        }
        throw new PyprojectModelException("Invalid dependency coordinate: '" + coordinate + "'. Expected group:artifact[:version]");
    }

    private CloseableSession newSession(Path localRepositoryPath,
                                        boolean offline,
                                        ProxyConfigurationLoader.ProxyConfiguration proxyConfiguration,
                                        boolean forceUpdates) {
        SessionBuilder sessionBuilder = new SessionBuilderSupplier(repositorySystem).get();
        sessionBuilder.setOffline(offline);
        if (forceUpdates) {
            sessionBuilder.setUpdatePolicy(RepositoryPolicy.UPDATE_POLICY_ALWAYS);
        }
        sessionBuilder.withLocalRepositoryBaseDirectories(localRepositoryPath.toAbsolutePath());
        if (proxyConfiguration != null) {
            DefaultProxySelector proxySelector = new DefaultProxySelector();
            proxySelector.add(
                new Proxy(
                    proxyConfiguration.protocol(),
                    proxyConfiguration.host(),
                    proxyConfiguration.port(),
                    authentication(proxyConfiguration)
                ),
                proxyConfiguration.nonProxyHosts()
            );
            sessionBuilder.setProxySelector(proxySelector);
        }
        return sessionBuilder.build();
    }

    private static Authentication authentication(ProxyConfigurationLoader.ProxyConfiguration proxyConfiguration) {
        if ((proxyConfiguration.username() == null || proxyConfiguration.username().isBlank())
            && (proxyConfiguration.password() == null || proxyConfiguration.password().isBlank())) {
            return null;
        }
        AuthenticationBuilder authenticationBuilder = new AuthenticationBuilder();
        if (proxyConfiguration.username() != null && !proxyConfiguration.username().isBlank()) {
            authenticationBuilder.addUsername(proxyConfiguration.username());
        }
        if (proxyConfiguration.password() != null && !proxyConfiguration.password().isBlank()) {
            authenticationBuilder.addPassword(proxyConfiguration.password());
        }
        return authenticationBuilder.build();
    }

    private static RepositorySystem newRepositorySystem() {
        return new RepositorySystemSupplier().get();
    }

    private static List<RemoteRepository> toRepositories(List<String> configuredRepositories, boolean forceUpdates) {
        List<String> repositories = configuredRepositories == null || configuredRepositories.isEmpty()
            ? List.of("mavenCentral")
            : configuredRepositories;

        Map<String, RemoteRepository> resolved = new LinkedHashMap<>();
        for (String repository : repositories) {
            String value = repository.trim();
            if (value.isEmpty()) {
                continue;
            }
            String lower = value.toLowerCase(Locale.ROOT);
            if ("mavencentral".equals(lower)) {
                resolved.put("mavenCentral", newRemoteRepository("mavenCentral", "https://repo1.maven.org/maven2/", forceUpdates));
            } else if ("mavenlocal".equals(lower)) {
                String localPath = resolveLocalMavenRepository().toUri().toString();
                RepositoryPolicy localPolicy = new RepositoryPolicy(true, RepositoryPolicy.UPDATE_POLICY_NEVER, RepositoryPolicy.CHECKSUM_POLICY_IGNORE);
                resolved.put(
                    "mavenLocal",
                    new RemoteRepository.Builder("mavenLocal", "default", localPath)
                        .setReleasePolicy(localPolicy)
                        .setSnapshotPolicy(localPolicy)
                        .build()
                );
            } else {
                String id = "repo-" + resolved.size();
                String url = value.contains("://") ? value : Path.of(value).toAbsolutePath().toUri().toString();
                resolved.put(id, newRemoteRepository(id, url, forceUpdates));
            }
        }
        return List.copyOf(resolved.values());
    }

    private static List<String> repositoriesForModel(PyprojectModel model) {
        if (model.pyronaut() == null) {
            return List.of();
        }
        List<String> repositories = model.pyronaut().repositories() == null ? List.of() : model.pyronaut().repositories();
        if (model.pyronaut().coreVersion() != null
            && model.pyronaut().coreVersion().endsWith("-SNAPSHOT")
            && repositories.stream().noneMatch(repository -> repository != null && "mavenlocal".equals(repository.trim().toLowerCase(Locale.ROOT)))) {
            List<String> withMavenLocal = new ArrayList<>(repositories.size() + 1);
            withMavenLocal.add("mavenLocal");
            withMavenLocal.addAll(repositories);
            return withMavenLocal;
        }
        return repositories;
    }

    private static RemoteRepository newRemoteRepository(String id, String url, boolean forceUpdates) {
        RemoteRepository.Builder builder = new RemoteRepository.Builder(id, "default", url);
        if (forceUpdates) {
            RepositoryPolicy policy = new RepositoryPolicy(
                true,
                RepositoryPolicy.UPDATE_POLICY_ALWAYS,
                RepositoryPolicy.CHECKSUM_POLICY_WARN
            );
            builder.setReleasePolicy(policy);
            builder.setSnapshotPolicy(policy);
        }
        return builder.build();
    }

    static Path resolveLocalMavenRepository() {
        String configuredLocalRepo = System.getProperty("maven.repo.local");
        if (configuredLocalRepo != null && !configuredLocalRepo.isBlank()) {
            return Path.of(configuredLocalRepo).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home"), ".m2", "repository");
    }

    private ResolvedEditorArtifact toResolvedEditorArtifact(Artifact artifact,
                                                            List<RemoteRepository> repositories,
                                                            CloseableSession session) {
        Path binaryPath = artifact.getPath();
        Path sourcePath = resolveSourceArtifact(artifact, repositories, session);
        return new ResolvedEditorArtifact(
            artifact.getGroupId(),
            artifact.getArtifactId(),
            artifact.getVersion(),
            binaryPath == null ? null : binaryPath.toAbsolutePath(),
            sourcePath == null ? null : sourcePath.toAbsolutePath()
        );
    }

    private Path resolveSourceArtifact(Artifact artifact,
                                       List<RemoteRepository> repositories,
                                       CloseableSession session) {
        if (artifact.getPath() == null || !"jar".equalsIgnoreCase(artifact.getExtension())) {
            return null;
        }
        Artifact sourceArtifact = new DefaultArtifact(
            artifact.getGroupId(),
            artifact.getArtifactId(),
            "sources",
            artifact.getExtension(),
            artifact.getVersion()
        );
        ArtifactRequest request = new ArtifactRequest(sourceArtifact, repositories, null);
        try {
            ArtifactResult result = repositorySystem.resolveArtifact(session, request);
            Artifact resolved = result.getArtifact();
            return resolved == null ? null : resolved.getPath();
        } catch (ArtifactResolutionException e) {
            return null;
        }
    }

    record ResolvedScopeDetails(List<Path> classpath, DependencyNode root, List<ResolvedEditorArtifact> editorArtifacts) {
    }

    record ResolvedEditorArtifact(String groupId, String artifactId, String version, Path binaryJar, Path sourceJar) {
    }
}

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
import io.micronaut.testresources.buildtools.KnownModules;
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
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.Authentication;
import org.eclipse.aether.repository.Proxy;
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

import java.nio.file.Path;
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
    private static final String JUNIT_PLATFORM_LAUNCHER_MODULE = "org.junit.platform:junit-platform-launcher";
    private static final String JUNIT_JUPITER_ENGINE_MODULE = "org.junit.jupiter:junit-jupiter-engine";
    private static final String PYRONAUT_GROUP = "io.micronaut.pyronaut";
    private static final String PYRONAUT_BOM_ARTIFACT = "micronaut-pyronaut-bom";
    private static final String MYSQL_CONNECTOR_J_MODULE = "mysql:mysql-connector-j";
    private static final String MYSQL_CONNECTOR_J_MODULE_MODERN = "com.mysql:mysql-connector-j";
    private static final Set<String> EXTRA_FORBIDDEN_SERVER_MODULES = Set.of(
        "io.micronaut.testresources:micronaut-test-resources-build-tools",
        "io.micronaut.testresources:micronaut-test-resources-client"
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
        List<RemoteRepository> repositories = toRepositories(model.pyronaut() == null ? List.of() : model.pyronaut().repositories());
        ProxyConfigurationLoader.ProxyConfiguration proxyConfiguration = proxyConfigurationLoader.load().orElse(null);
        try (CloseableSession session = newSession(localRepositoryPath, offline, proxyConfiguration)) {
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
            return new ResolvedScopeDetails(classpath, result.getRoot(), editorArtifacts);
        } catch (DependencyResolutionException e) {
            String message = "Dependency resolution failed for scope '" + scope.cliValue() + "': " + e.getMessage();
            if (proxyConfiguration != null) {
                message = message + " (proxy " + proxyConfiguration.summary() + ")";
            }
            throw new PyprojectModelException(message, e);
        }
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
            return List.copyOf(runtime);
        }
        if (scope == InstallScope.DEVELOPMENT_RUNTIME) {
            LinkedHashSet<String> runtime = new LinkedHashSet<>(coordinatesForScope(model, InstallScope.RUNTIME, managedVersions));
            addDefaultCoordinate(runtime, MICRONAUT_MANAGEMENT_MODULE, managedVersions);
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
        if (coordinates.stream().noneMatch(this::isServerModule)) {
            coordinates.add(new MavenDependency("io.micronaut.testresources", "micronaut-test-resources-server", version));
        }
        if (appDependencies.stream().anyMatch(this::isMysqlConnectorJCoordinate)) {
            coordinates.add(testResourcesModule(KnownModules.JDBC_MYSQL, version));
            appDependencies.stream()
                .filter(this::isMysqlConnectorJCoordinate)
                .forEach(coordinates::add);
        }

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

    private boolean isMysqlConnectorJCoordinate(MavenDependency dependency) {
        String module = dependency.getModule();
        return MYSQL_CONNECTOR_J_MODULE.equals(module) || MYSQL_CONNECTOR_J_MODULE_MODERN.equals(module);
    }

    private boolean isServerModule(MavenDependency dependency) {
        return TEST_RESOURCES_SERVER_MODULE.equals(moduleKey(dependency));
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

    private static MavenDependency testResourcesModule(String module, String version) {
        return new MavenDependency("io.micronaut.testresources", "micronaut-test-resources-" + module, version);
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
        if (model.pyronaut() == null || model.pyronaut().version() == null) {
            return false;
        }
        return !model.pyronaut().version().isBlank();
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
        if (model.pyronaut() == null || model.pyronaut().version() == null || model.pyronaut().version().isBlank()) {
            return List.of();
        }
        String pyronautVersion = model.pyronaut().version();
        Map<String, Dependency> managed = new LinkedHashMap<>();
        LinkedHashSet<String> visitedBoms = new LinkedHashSet<>();
        addManagedDependenciesFromBom(
            new DefaultArtifact("io.micronaut", "micronaut-core-bom", "", "pom", pyronautVersion),
            repositories,
            session,
            visitedBoms,
            managed
        );
        addManagedDependenciesFromBom(
            new DefaultArtifact("io.micronaut.platform", "micronaut-platform", "", "pom", pyronautVersion),
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
                                        ProxyConfigurationLoader.ProxyConfiguration proxyConfiguration) {
        SessionBuilder sessionBuilder = new SessionBuilderSupplier(repositorySystem).get();
        sessionBuilder.setOffline(offline);
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

    private static List<RemoteRepository> toRepositories(List<String> configuredRepositories) {
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
                resolved.put("mavenCentral", new RemoteRepository.Builder("mavenCentral", "default", "https://repo1.maven.org/maven2/").build());
            } else if ("mavenlocal".equals(lower)) {
                String localPath = resolveLocalMavenRepository().toUri().toString();
                resolved.put("mavenLocal", new RemoteRepository.Builder("mavenLocal", "default", localPath).build());
            } else {
                String id = "repo-" + resolved.size();
                String url = value.contains("://") ? value : Path.of(value).toAbsolutePath().toUri().toString();
                resolved.put(id, new RemoteRepository.Builder(id, "default", url).build());
            }
        }
        return List.copyOf(resolved.values());
    }

    private static Path resolveLocalMavenRepository() {
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

    record ResolvedEditorArtifact(Path binaryJar, Path sourceJar) {
    }
}

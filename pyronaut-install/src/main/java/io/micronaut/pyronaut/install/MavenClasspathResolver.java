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
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.graph.DependencyVisitor;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.repository.Authentication;
import org.eclipse.aether.repository.Proxy;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorPolicy;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.AbstractRepositoryListener;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.transfer.TransferEvent;
import org.eclipse.aether.transfer.TransferListener;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.supplier.SessionBuilderSupplier;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.jdk.JdkTransporterFactory;
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
import java.util.stream.Collectors;

/**
 * Resolves classpaths using Apache Maven Resolver.
 */
@SuppressWarnings({"checkstyle:InnerTypeLast", "checkstyle:NeedBraces"})
final class MavenClasspathResolver {
    private static final String TEST_RESOURCES_CLIENT_MODULE = "io.micronaut.testresources:micronaut-test-resources-client";
    private static final String TEST_RESOURCES_SERVER_MODULE = "io.micronaut.testresources:micronaut-test-resources-server";
    private static final String TEST_RESOURCES_CONTROL_PANEL_MODULE = "io.micronaut.testresources:micronaut-test-resources-control-panel";
    private static final String NASHORN_MODULE = "org.openjdk.nashorn:nashorn-core";
    private static final String POM_EXTENSION = "pom";
    private static final String MICRONAUT_TOML_MODULE = "io.micronaut.toml:micronaut-toml";
    private static final String MICRONAUT_OPENAPI_PROCESSOR_MODULE = "io.micronaut.openapi:micronaut-openapi";
    private static final String MICRONAUT_OPENAPI_ANNOTATIONS_MODULE = "io.micronaut.openapi:micronaut-openapi-annotations";
    private static final String MICRONAUT_CONTEXT_PYTHON_MODULE = "io.micronaut:micronaut-context-python";
    private static final String MICRONAUT_INJECT_PYTHON_MODULE = "io.micronaut:micronaut-inject-python";
    private static final String MICRONAUT_MANAGEMENT_MODULE = "io.micronaut:micronaut-management";
    private static final String MICRONAUT_RUNTIME_OSX_MODULE = "io.micronaut:micronaut-runtime-osx";
    private static final String MICRONAUT_CACHE_CAFFEINE_MODULE = "io.micronaut.cache:micronaut-cache-caffeine";
    private static final String CONTROL_PANEL_MANAGEMENT_MODULE = "io.micronaut.controlpanel:micronaut-control-panel-management";
    private static final String CONTROL_PANEL_UI_MODULE = "io.micronaut.controlpanel:micronaut-control-panel-ui";
    private static final String MICRONAUT_SECURITY_GROUP = "io.micronaut.security";
    private static final String MICRONAUT_SECURITY_ARTIFACT_PREFIX = "micronaut-security";
    private static final String JUNIT_PLATFORM_LAUNCHER_MODULE = "org.junit.platform:junit-platform-launcher";
    private static final String JUNIT_JUPITER_ENGINE_MODULE = "org.junit.jupiter:junit-jupiter-engine";
    private static final String PYRONAUT_GROUP = "io.micronaut.pyronaut";
    private static final String PYRONAUT_BOM_ARTIFACT = "micronaut-pyronaut-bom";
    private static final String SONATYPE_SNAPSHOTS_REPOSITORY = "https://central.sonatype.com/repository/maven-snapshots/";
    private static final Set<String> EXTRA_FORBIDDEN_SERVER_MODULES = Set.of(
        "io.micronaut.testresources:micronaut-test-resources-build-tools",
        "io.micronaut.testresources:micronaut-test-resources-client"
    );
    private static final Set<String> MICRONAUT_CACHE_IMPLEMENTATION_MODULES = Set.of(
        MICRONAUT_CACHE_CAFFEINE_MODULE,
        "io.micronaut.cache:micronaut-cache-ehcache",
        "io.micronaut.cache:micronaut-cache-hazelcast",
        "io.micronaut.cache:micronaut-cache-infinispan",
        "io.micronaut.cache:micronaut-cache-noop"
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

    /**
     * Resolves the development-only Control Panel support using the platform
     * packaged with Pyronaut. External Maven/Gradle builds intentionally do
     * not contribute their dependency-management version to this resolution.
     */
    List<Path> resolveManagedDevelopmentSupport(Path localRepositoryPath, boolean offline) {
        boolean enabled = "true".equalsIgnoreCase(envReader.apply("PYRONAUT_CONTROL_PANEL_ENABLED"));
        if (!enabled) {
            // An external Maven or Gradle project owns its runtime dependency
            // versions. Do not add the SDK's development-support graph unless
            // the project explicitly enables the Control Panel; otherwise a
            // different Micronaut platform version can be mixed into the
            // application's JVM development classpath.
            return List.of();
        }
        PyprojectModel.Pyronaut pyronaut = new PyprojectModel.Pyronaut(
            null,
            PyronautManagedVersions.micronautPlatformVersion(),
            List.of(),
            null,
            new PyprojectModel.Dependencies(List.of(), List.of(), List.of(), List.of()),
            null,
            new PyprojectModel.ControlPanel(enabled, "/control-panel", false),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false
        );
        return resolveScope(new PyprojectModel(null, null, pyronaut), InstallScope.DEVELOPMENT_RUNTIME, localRepositoryPath, offline);
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
        return resolveScopeDetails(model, scope, localRepositoryPath, offline, forceUpdates, null);
    }

    ResolvedScopeDetails resolveScopeDetails(PyprojectModel model,
                                             InstallScope scope,
                                             Path localRepositoryPath,
                                             boolean offline,
                                             boolean forceUpdates,
                                             DependencyProgressListener progressListener) {
        return resolveScopeDetails(model, scope, localRepositoryPath, offline, forceUpdates, progressListener, null);
    }

    /**
     * Resolves a scope for a project rooted at {@code projectDirectory}. Relative
     * file based repositories configured in {@code pyproject.toml} are resolved
     * against that directory rather than the JVM working directory.
     */
    ResolvedScopeDetails resolveScopeDetails(PyprojectModel model,
                                             InstallScope scope,
                                             Path localRepositoryPath,
                                             boolean offline,
                                             boolean forceUpdates,
                                             DependencyProgressListener progressListener,
                                             Path projectDirectory) {
        return resolveScopeDetails(model, scope, localRepositoryPath, offline, forceUpdates, progressListener, projectDirectory, true);
    }

    ResolvedScopeDetails resolveTestResourcesProviderDetails(PyprojectModel model,
                                                             Path localRepositoryPath,
                                                             boolean offline,
                                                             boolean forceUpdates) {
        return resolveTestResourcesProviderDetails(model, localRepositoryPath, offline, forceUpdates, null);
    }

    ResolvedScopeDetails resolveTestResourcesProviderDetails(PyprojectModel model,
                                                             Path localRepositoryPath,
                                                             boolean offline,
                                                             boolean forceUpdates,
                                                             DependencyProgressListener progressListener) {
        return resolveScopeDetails(
            model,
            InstallScope.TEST_RESOURCES_SERVER,
            localRepositoryPath,
            offline,
            forceUpdates,
            progressListener,
            null,
            false
        );
    }

    List<Path> resolveToolArtifacts(PyprojectModel model,
                                    List<Artifact> artifacts,
                                    Path localRepositoryPath,
                                    boolean offline,
                                    boolean forceUpdates) {
        return resolveToolArtifacts(model, artifacts, localRepositoryPath, offline, forceUpdates, null);
    }

    List<Path> resolveToolArtifacts(PyprojectModel model,
                                    List<Artifact> artifacts,
                                    Path localRepositoryPath,
                                    boolean offline,
                                    boolean forceUpdates,
                                    DependencyProgressListener progressListener) {
        return resolveToolArtifacts(model, artifacts, localRepositoryPath, offline, forceUpdates, progressListener, null);
    }

    List<Path> resolveToolArtifacts(PyprojectModel model,
                                    List<Artifact> artifacts,
                                    Path localRepositoryPath,
                                    boolean offline,
                                    boolean forceUpdates,
                                    DependencyProgressListener progressListener,
                                    Path projectDirectory) {
        if (artifacts.isEmpty()) {
            return List.of();
        }
        // Keep repository identities in offline mode so Maven Resolver's
        // enhanced local repository manager can match _remote.repositories
        // provenance. The offline session still prohibits every download.
        List<RemoteRepository> repositories = toRepositories(repositoriesForModel(model), forceUpdates, projectDirectory);
        ProxyConfigurationLoader.ProxyConfiguration proxyConfiguration = proxyConfigurationLoader.load().orElse(null);
        try (CloseableSession session = newSession(localRepositoryPath, offline, proxyConfiguration, forceUpdates, progressListener)) {
            List<ArtifactRequest> requests = artifacts.stream()
                .map(artifact -> new ArtifactRequest(artifact, repositories, null))
                .toList();
            if (progressListener != null) {
                progressListener.reset();
                progressListener.begin();
                artifacts.forEach(artifact -> progressListener.artifactPlanned(artifact.toString()));
            }
            return repositorySystem.resolveArtifacts(session, requests).stream()
                .map(ArtifactResult::getArtifact)
                .map(Artifact::getPath)
                .filter(Objects::nonNull)
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .toList();
        } catch (ArtifactResolutionException e) {
            if (progressListener != null) {
                e.getResults().stream()
                    .map(ArtifactResult::getRequest)
                    .map(ArtifactRequest::getArtifact)
                    .filter(Objects::nonNull)
                    .forEach(artifact -> progressListener.artifactFailed(artifact.toString()));
            }
            List<String> unresolved = e.getResults().stream()
                .filter(result -> !result.isResolved())
                .map(ArtifactResult::getRequest)
                .map(ArtifactRequest::getArtifact)
                .filter(Objects::nonNull)
                .map(Artifact::toString)
                .distinct()
                .toList();
            String mode = offline ? " in offline mode" : "";
            String details = unresolved.isEmpty() ? "" : ": " + String.join(", ", unresolved.stream().limit(8).toList());
            if (unresolved.size() > 8) {
                details += " (+" + (unresolved.size() - 8) + " more)";
            }
            if (proxyConfiguration != null) {
                details += " (proxy " + proxyConfiguration.summary() + ")";
            }
            throw new PyprojectModelException("Failed to resolve required Pyronaut tool runtime artifacts" + mode + details, e);
        }
    }

    private ResolvedScopeDetails resolveScopeDetails(PyprojectModel model,
                                                     InstallScope scope,
                                                     Path localRepositoryPath,
                                                     boolean offline,
                                                     boolean forceUpdates,
                                                     DependencyProgressListener progressListener,
                                                     Path projectDirectory,
                                                     boolean includeDefaultDependencies) {
        // Pass the configured repositories unchanged, also in offline mode. Maven
        // Resolver's enhanced local repository manager only serves artifacts whose
        // recorded origin repository (_remote.repositories) is part of the request,
        // so dropping the remote repositories would reject previously downloaded
        // artifacts. The offline session still prohibits every download.
        List<RemoteRepository> repositories = toRepositories(repositoriesForModel(model), forceUpdates, projectDirectory);
        ProxyConfigurationLoader.ProxyConfiguration proxyConfiguration = proxyConfigurationLoader.load().orElse(null);
        try (CloseableSession session = newSession(localRepositoryPath, offline, proxyConfiguration, forceUpdates, progressListener)) {
            List<Dependency> managedDependencies = managedDependencies(model, repositories, session);
            if (progressListener != null) progressListener.reset();
            Map<String, String> managedVersions = new LinkedHashMap<>();
            for (Dependency dependency : managedDependencies) {
                Artifact artifact = dependency.getArtifact();
                if (artifact == null || artifact.getVersion() == null || artifact.getVersion().isBlank()) {
                    continue;
                }
                managedVersions.putIfAbsent(artifact.getGroupId() + ":" + artifact.getArtifactId(), artifact.getVersion());
            }
            LinkedHashSet<String> coordinates = new LinkedHashSet<>(coordinatesForScope(
                model,
                scope,
                managedVersions,
                includeDefaultDependencies
            ));
            if (coordinates.isEmpty()) {
                return new ResolvedScopeDetails(List.of(), null, List.of());
            }

            CollectRequest collectRequest = new CollectRequest();
            collectRequest.setRepositories(repositories);
            managedDependencies.forEach(collectRequest::addManagedDependency);

            for (String coordinate : coordinates) {
                collectRequest.addDependency(toDependency(model, scope, coordinate, managedVersions));
            }

            // Build the complete dependency graph before resolving artifacts. The
            // combined resolver otherwise interleaves graph discovery and downloads,
            // making a percentage denominator unstable (and causing a long 99% plateau).
            CollectResult collected = repositorySystem.collectDependencies(session, collectRequest);
            // Collection can materialize managed/BOM edges during its first pass;
            // repeat once so those newly materialized artifacts are included before
            // the fixed progress denominator is used by resolution.
            collected = repositorySystem.collectDependencies(session, collectRequest);
            if (progressListener != null) progressListener.begin();
            if (progressListener != null && model.pyronaut() != null && requiresPyronautManagedDependencies(model)) {
                String toolVersion = normalizedVersion(pyronautVersionProvider.get());
                if (toolVersion != null) progressListener.artifactPlanned(PYRONAUT_GROUP + ":" + PYRONAUT_BOM_ARTIFACT + ":pom:" + toolVersion);
            }
            if (progressListener != null) {
                collected.getRoot().accept(new DependencyVisitor() {
                    @Override
                    public boolean visitEnter(DependencyNode node) {
                        if (node.getArtifact() != null) progressListener.artifactPlanned(node.getArtifact().toString());
                        return true;
                    }

                    @Override
                    public boolean visitLeave(DependencyNode node) {
                        return true;
                    }
                });
            }

            DependencyRequest dependencyRequest = new DependencyRequest(
                collected.getRoot(),
                DependencyFilterUtils.classpathFilter(JavaScopes.RUNTIME)
            );

            DependencyResult result = repositorySystem.resolveDependencies(session, dependencyRequest);
            if (forceUpdates && !offline && evictResolvedArtifacts(localRepositoryPath, result)) {
                result = repositorySystem.resolveDependencies(session, dependencyRequest);
            }
            List<Path> classpath = result.getArtifactResults().stream()
                .map(artifactResult -> artifactResult.getArtifact())
                .filter(Objects::nonNull)
                // A POM-only artifact is resolved for its dependency management
                // and its transitives; the .pom file itself is not classpath
                // content.
                .filter(artifact -> !POM_EXTENSION.equals(artifact.getExtension()))
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
        } catch (DependencyResolutionException | DependencyCollectionException e) {
            String message = "Dependency resolution failed for scope '" + scope.cliValue() + "': " + e.getMessage()
                + missingDescriptorDetails(e, localRepositoryPath, offline);
            if (proxyConfiguration != null) {
                message = message + " (proxy " + proxyConfiguration.summary() + ")";
            }
            throw new PyprojectModelException(message, e);
        }
    }

    private static String missingDescriptorDetails(Exception failure, Path localRepositoryPath, boolean offline) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ArtifactDescriptorException descriptorFailure) {
                Throwable reason = descriptorFailure.getCause() == null ? descriptorFailure : descriptorFailure.getCause();
                return " (" + descriptorFailure.getMessage() + ": " + reason.getMessage()
                    + "). Each dependency's POM is required to resolve its transitive dependencies. "
                    + missingDescriptorRemedy(descriptorFailure, localRepositoryPath, offline);
            }
        }
        return "";
    }

    private static String missingDescriptorRemedy(ArtifactDescriptorException failure,
                                                  Path localRepositoryPath,
                                                  boolean offline) {
        String rerun = offline ? "rerun without --offline" : "rerun";
        Artifact artifact = failure.getResult() == null || failure.getResult().getRequest() == null
            ? null
            : failure.getResult().getRequest().getArtifact();
        if (artifact != null) {
            // A partial cache entry (typically a JAR copied into the repository
            // without its POM) is never repaired by Maven Resolver, which
            // considers the artifact present. Deleting the entry makes the next
            // online resolution download the JAR and POM together.
            List<Path> entries = new ArrayList<>();
            for (Path repository : List.of(localRepositoryPath, resolveLocalMavenRepository())) {
                Path entry = repository.toAbsolutePath().normalize()
                    .resolve(artifact.getGroupId().replace('.', '/'))
                    .resolve(artifact.getArtifactId())
                    .resolve(artifact.getBaseVersion());
                if (Files.isDirectory(entry) && !entries.contains(entry)) {
                    entries.add(entry);
                }
            }
            if (!entries.isEmpty()) {
                return "The local Maven repository holds an incomplete entry for " + artifact.getGroupId() + ":"
                    + artifact.getArtifactId() + ":" + artifact.getBaseVersion() + ". Delete "
                    + entries.stream().map(Path::toString).collect(Collectors.joining(" and "))
                    + " and " + rerun + " to download the artifact together with its POM.";
            }
        }
        return offline
            ? "Rerun without --offline to download the missing POM."
            : "Make sure a configured repository provides the artifact and its POM.";
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

    /**
     * The coordinates a scope installs, including the ones added by default.
     *
     * @param model           the parsed pyproject.toml
     * @param scope           the scope being installed
     * @param managedVersions the versions the platform BOM manages
     * @return the coordinates, in the order they are added
     */
    List<String> coordinatesForScope(PyprojectModel model,
                                     InstallScope scope,
                                     Map<String, String> managedVersions) {
        return coordinatesForScope(model, scope, managedVersions, true);
    }

    private List<String> coordinatesForScope(PyprojectModel model,
                                             InstallScope scope,
                                             Map<String, String> managedVersions,
                                             boolean includeDefaultDependencies) {
        if (model.pyronaut() == null) {
            return List.of();
        }
        if (scope == InstallScope.TEST_RESOURCES_SERVER) {
            return testResourcesServerCoordinates(model, managedVersions, includeDefaultDependencies);
        }
        if (model.pyronaut().dependencies() == null) {
            return List.of();
        }
        PyprojectModel.Dependencies dependencies = model.pyronaut().dependencies();
        if (scope == InstallScope.BUILD) {
            LinkedHashSet<String> build = new LinkedHashSet<>();
            if (dependencies.build() != null) {
                build.addAll(dependencies.build());
            }
            addDefaultCoordinate(build, dependencies, MICRONAUT_INJECT_PYTHON_MODULE, managedVersions);
            addDefaultCoordinate(build, dependencies, MICRONAUT_CONTEXT_PYTHON_MODULE, managedVersions);
            addDefaultCoordinate(build, dependencies, MICRONAUT_OPENAPI_PROCESSOR_MODULE, managedVersions);
            return List.copyOf(build);
        }
        if (scope == InstallScope.RUNTIME) {
            LinkedHashSet<String> runtime = new LinkedHashSet<>();
            if (dependencies.runtime() != null) {
                runtime.addAll(dependencies.runtime());
            }
            addDefaultCoordinate(runtime, dependencies, MICRONAUT_CONTEXT_PYTHON_MODULE, managedVersions);
            addDefaultCoordinate(runtime, dependencies, MICRONAUT_TOML_MODULE, managedVersions);
            addDefaultCoordinate(runtime, dependencies, MICRONAUT_OPENAPI_ANNOTATIONS_MODULE, managedVersions);
            String testResourcesClient = testResourcesClientCoordinate(model, managedVersions);
            if (testResourcesClient != null) {
                runtime.add(testResourcesClient);
            }
            if (controlPanelProductionEnabled(model)) {
                addDefaultCoordinate(runtime, dependencies, MICRONAUT_MANAGEMENT_MODULE, managedVersions);
                addDefaultCacheImplementationIfMissing(runtime, managedVersions);
                runtime.add(controlPanelManagementCoordinate());
                runtime.add(controlPanelUiCoordinate());
            }
            return List.copyOf(runtime);
        }
        if (scope == InstallScope.DEVELOPMENT_RUNTIME) {
            LinkedHashSet<String> runtime = new LinkedHashSet<>(coordinatesForScope(model, InstallScope.RUNTIME, managedVersions));
            if (dependencies.developmentRuntime() != null) {
                runtime.addAll(dependencies.developmentRuntime());
            }
            addDefaultCoordinate(runtime, dependencies, MICRONAUT_MANAGEMENT_MODULE, managedVersions);
            if (isMacOs()) {
                addDefaultCoordinate(runtime, dependencies, MICRONAUT_RUNTIME_OSX_MODULE, managedVersions);
            }
            addDefaultCacheImplementationIfMissing(runtime, managedVersions);
            if (!model.pyronaut().controlPanelConfigured() || controlPanelEnabled(model)) {
                runtime.add(controlPanelManagementCoordinate());
                runtime.add(controlPanelUiCoordinate());
            }
            return List.copyOf(runtime);
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        merged.addAll(coordinatesForScope(model, InstallScope.RUNTIME, managedVersions));
        if (dependencies.test() != null) {
            merged.addAll(dependencies.test());
        }
        addDefaultCoordinate(merged, dependencies, JUNIT_PLATFORM_LAUNCHER_MODULE, managedVersions);
        addDefaultCoordinate(merged, dependencies, JUNIT_JUPITER_ENGINE_MODULE, managedVersions);
        return List.copyOf(merged);
    }

    private List<String> testResourcesServerCoordinates(PyprojectModel model,
                                                        Map<String, String> managedVersions,
                                                        boolean includeDefaultDependencies) {
        if (!resolvesTestResourcesServer(model)) {
            return List.of();
        }
        PyprojectModel.TestResources testResources = model.pyronaut().testResources();

        String version = defaultTestResourcesVersion(testResources, managedVersions);
        LinkedHashSet<String> resolvedCoordinates = new LinkedHashSet<>();
        if (includeDefaultDependencies) {
            for (String defaultDependency : InstallScope.TEST_RESOURCES_SERVER.defaultDependencies()) {
                if (defaultDependency.startsWith("io.micronaut.testresources:") && version != null) {
                    resolvedCoordinates.add(defaultDependency + ":" + version);
                } else {
                    resolvedCoordinates.add(defaultDependency);
                }
            }
        }
        LinkedHashSet<MavenDependency> coordinates = new LinkedHashSet<>();
        List<MavenDependency> appDependencies = appDependenciesForServerInference(model);
        List<MavenDependency> inferred = Boolean.FALSE.equals(testResources.inferClasspath())
            ? List.of()
            : version == null
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

        coordinates.stream()
            .filter(this::isDependencyAllowedOnServerClasspath)
            .map(MavenClasspathResolver::coordinate)
            .forEach(resolvedCoordinates::add);
        return List.copyOf(resolvedCoordinates);
    }

    boolean resolvesTestResourcesServer(PyprojectModel model) {
        if (isTestResourcesDisabledViaEnvironment()
            || model.pyronaut() == null
            || model.pyronaut().testResources() == null) {
            return false;
        }
        PyprojectModel.TestResources testResources = model.pyronaut().testResources();
        return Boolean.TRUE.equals(testResources.enabled())
            && (testResources.configured() || supportsDefaultTestResourcesResolution(model, testResources));
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
        if (parts.length == 4 || parts.length == 5) {
            // group:artifact:extension:version, optionally with a classifier before the version --
            // the form a POM-only aggregator has to be declared in, which toDependency accepts.
            // This is only used to infer which Test Resources modules the server needs, and that
            // inference is by module identity, so the extension and any classifier are irrelevant
            // here. Rejecting the coordinate outright meant a single valid POM-only dependency
            // anywhere in runtime or test failed the whole test-resources-server scope.
            return new MavenDependency(parts[0], parts[1], parts[parts.length - 1]);
        }
        throw new PyprojectModelException("Invalid dependency coordinate: '" + coordinate
            + "'. Expected group:artifact[:version], group:artifact:extension:version"
            + " or group:artifact:extension:classifier:version");
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
        String configuredVersion = System.getProperty("pyronaut.version");
        if (configuredVersion != null && !configuredVersion.isBlank()) {
            return configuredVersion;
        }
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
            || containsVersionlessPyronautDependency(dependencies.developmentRuntime())
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

    /**
     * Whether this is macOS, where file watching needs a native watch service.
     *
     * <p>Without {@code micronaut-runtime-osx} the JDK falls back to {@code PollingWatchService} and
     * nothing arrives in any useful time, so a development feature that waits on a file change simply
     * never fires. Only added to the development runtime: a production artifact built on a Mac should
     * not carry a macOS-specific module.
     *
     * @return whether the current operating system is macOS
     */
    private static boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ENGLISH).contains("mac");
    }

    /**
     * Adds a default module unless the project excludes it globally, so that an
     * exclusion keeps the module off the classpath rather than only removing it
     * as a transitive dependency.
     */
    private static void addDefaultCoordinate(Set<String> coordinates,
                                             PyprojectModel.Dependencies dependencies,
                                             String module,
                                             Map<String, String> managedVersions) {
        if (dependencies.exclusions() != null && dependencies.exclusions().contains(module)) {
            return;
        }
        addDefaultCoordinate(coordinates, module, managedVersions);
    }

    private static void addDefaultCoordinate(Set<String> coordinates, String module, Map<String, String> managedVersions) {
        String coordinate = defaultManagedCoordinate(module, managedVersions);
        if (coordinate != null) {
            coordinates.add(coordinate);
        }
    }

    private static void addDefaultCacheImplementationIfMissing(Set<String> coordinates, Map<String, String> managedVersions) {
        if (!hasCacheImplementation(coordinates)) {
            addDefaultCoordinate(coordinates, MICRONAUT_CACHE_CAFFEINE_MODULE, managedVersions);
        }
    }

    private static boolean hasCacheImplementation(Set<String> coordinates) {
        return coordinates.stream()
            .map(MavenClasspathResolver::moduleKey)
            .anyMatch(MICRONAUT_CACHE_IMPLEMENTATION_MODULES::contains);
    }

    private static String defaultManagedCoordinate(String module, Map<String, String> managedVersions) {
        String version = normalizedVersion(managedVersions.get(module));
        return version == null ? null : module + ":" + version;
    }

    private static String moduleKey(String coordinate) {
        String[] parts = coordinate.split(":");
        if (parts.length < 2) {
            return coordinate;
        }
        return parts[0] + ":" + parts[1];
    }

    boolean isTestResourcesDisabledViaEnvironment() {
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
        PyprojectModel.TestResources testResources = model.pyronaut().testResources();
        String testResourcesVersion = !isTestResourcesDisabledViaEnvironment()
            && testResources != null && Boolean.TRUE.equals(testResources.enabled())
            ? normalizedVersion(testResources.version())
            : null;
        PyprojectModel.Dependencies configured = model.pyronaut().dependencies();
        if (coreVersion == null && platformVersion == null && testResourcesVersion == null
            && (configured == null || configured.boms() == null || configured.boms().isEmpty())) {
            return List.of();
        }
        if (platformVersion == null) {
            platformVersion = coreVersion;
        }
        Map<String, Dependency> managed = new LinkedHashMap<>();
        LinkedHashSet<String> visitedBoms = new LinkedHashSet<>();
        if (configured != null && configured.boms() != null) {
            for (String bom : configured.boms()) {
                String[] parts = splitCoordinate(bom, 3, "BOM");
                addManagedDependenciesFromBom(
                    new DefaultArtifact(parts[0], parts[1], "", "pom", parts[2]),
                    repositories, session, visitedBoms, managed
                );
            }
        }
        if (testResourcesVersion != null) {
            addManagedDependenciesFromBom(
                new DefaultArtifact("io.micronaut.testresources", "micronaut-test-resources-bom", "", "pom", testResourcesVersion),
                repositories, session, visitedBoms, managed
            );
        }
        if (coreVersion != null) {
            addManagedDependenciesFromBom(
                new DefaultArtifact("io.micronaut", "micronaut-core-bom", "", "pom", coreVersion),
                repositories,
                session,
                visitedBoms,
                managed
            );
        }
        if (platformVersion != null) {
            addManagedDependenciesFromBom(
                new DefaultArtifact("io.micronaut.platform", "micronaut-platform", "", "pom", platformVersion),
                repositories, session, visitedBoms, managed
            );
        }
        if (requiresPyronautManagedDependencies(model)) {
            String toolVersion = normalizedVersion(pyronautVersionProvider.get());
            if (toolVersion == null) {
                throw new PyprojectModelException(
                    "Versionless Pyronaut dependencies require the running Pyronaut tool version, but it is unavailable"
                );
            }
            try {
                addManagedDependenciesFromBom(
                    new DefaultArtifact(PYRONAUT_GROUP, PYRONAUT_BOM_ARTIFACT, "", "pom", toolVersion),
                    repositories,
                    session,
                    visitedBoms,
                    managed
                );
            } catch (PyprojectModelException ignored) {
                // Every Pyronaut module is published with the tool version. The
                // BOM is useful for custom management, but a source checkout or
                // an incomplete local repository must not make direct-source
                // versionless Pyronaut dependencies unusable.
            }
            addFallbackPyronautManagedDependencies(model, toolVersion, managed);
        }
        return List.copyOf(managed.values());
    }

    private static void addFallbackPyronautManagedDependencies(PyprojectModel model,
                                                               String toolVersion,
                                                               Map<String, Dependency> managedDependencies) {
        PyprojectModel.Dependencies dependencies = model.pyronaut().dependencies();
        List<List<String>> scopes = List.of(
            dependencies.runtime(),
            dependencies.developmentRuntime(),
            dependencies.build(),
            dependencies.test()
        );
        for (List<String> coordinates : scopes) {
            for (String coordinate : coordinates) {
                if (coordinate == null) {
                    continue;
                }
                String[] parts = coordinate.trim().split(":");
                if (parts.length != 2 || !PYRONAUT_GROUP.equals(parts[0])) {
                    continue;
                }
                Artifact artifact = new DefaultArtifact(PYRONAUT_GROUP, parts[1], "jar", toolVersion);
                managedDependencies.putIfAbsent(
                    managedDependencyKey(artifact),
                    new Dependency(artifact, JavaScopes.RUNTIME, false, List.of())
                );
            }
        }
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

    private static Dependency toDependency(PyprojectModel model,
                                           InstallScope scope,
                                           String coordinate,
                                           Map<String, String> managedVersions) {
        String[] parts = coordinate.split(":");
        List<Exclusion> exclusions = exclusions(model, scope, coordinate);
        if (parts.length == 4 || parts.length == 5) {
            // group:artifact:extension:version, optionally with a classifier as
            // group:artifact:extension:classifier:version. Resolver's own parser
            // understands both, and this is the only way to depend on an
            // artifact that is not published as a jar -- a POM-only aggregator
            // such as org.graalvm.polyglot:js, for example.
            return new Dependency(new DefaultArtifact(coordinate), JavaScopes.RUNTIME, false, exclusions);
        }
        if (parts.length == 3) {
            return new Dependency(new DefaultArtifact(parts[0], parts[1], "jar", parts[2]), JavaScopes.RUNTIME, false, exclusions);
        }
        if (parts.length == 2) {
            String managedVersion = managedVersions.get(parts[0] + ":" + parts[1]);
            if (managedVersion == null || managedVersion.isBlank()) {
                throw new PyprojectModelException(
                    "No managed version is available for dependency '" + coordinate
                        + "'. Configure a Micronaut platform or BOM that manages it."
                );
            }
            return new Dependency(new DefaultArtifact(parts[0], parts[1], "jar", managedVersion), JavaScopes.RUNTIME, false, exclusions);
        }
        throw new PyprojectModelException("Invalid dependency coordinate: '" + coordinate
            + "'. Expected group:artifact[:version], group:artifact:extension:version"
            + " or group:artifact:extension:classifier:version");
    }

    private static List<Exclusion> exclusions(PyprojectModel model, InstallScope scope, String coordinate) {
        String module = moduleKey(coordinate);
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (scope == InstallScope.TEST_RESOURCES_SERVER && TEST_RESOURCES_CONTROL_PANEL_MODULE.equals(module)) {
            values.add(NASHORN_MODULE);
        }
        if (model.pyronaut() == null || model.pyronaut().dependencies() == null) {
            return values.stream().map(value -> {
                String[] parts = splitCoordinate(value, 2, "exclusion");
                return new Exclusion(parts[0], parts[1], "*", "*");
            }).toList();
        }
        PyprojectModel.Dependencies dependencies = model.pyronaut().dependencies();
        if (dependencies.exclusions() != null) {
            values.addAll(dependencies.exclusions());
        }
        if (dependencies.artifactExclusions() != null) {
            values.addAll(dependencies.artifactExclusions().getOrDefault(module, List.of()));
        }
        if (values.isEmpty()) {
            return List.of();
        }
        return values.stream().map(value -> {
            String[] parts = splitCoordinate(value, 2, "exclusion");
            return new Exclusion(parts[0], parts[1], "*", "*");
        }).toList();
    }

    private static String[] splitCoordinate(String value, int expected, String kind) {
        if (value == null) {
            throw new PyprojectModelException("Invalid " + kind + " coordinate: 'null'");
        }
        String[] parts = value.trim().split(":");
        if (parts.length != expected || java.util.Arrays.stream(parts).anyMatch(String::isBlank)) {
            throw new PyprojectModelException("Invalid " + kind + " coordinate: '" + value + "'. Expected group:artifact" + (expected == 3 ? ":version" : ""));
        }
        return parts;
    }

    private CloseableSession newSession(Path localRepositoryPath,
                                        boolean offline,
                                        ProxyConfigurationLoader.ProxyConfiguration proxyConfiguration,
                                        boolean forceUpdates,
                                        DependencyProgressListener progressListener) {
        SessionBuilder sessionBuilder = new SessionBuilderSupplier(repositorySystem).get();
        sessionBuilder.setOffline(offline);
        // The supplier's default policy treats a missing POM as an empty
        // descriptor, so a JAR cached without its POM (or one whose POM cannot
        // be fetched, for example offline) silently loses every transitive
        // dependency. Fail instead: a classpath missing transitives only
        // surfaces later as NoClassDefFoundError at application runtime.
        // Missing BOM and other POM-only descriptors remain optional.
        sessionBuilder.setArtifactDescriptorPolicy((session, request) -> {
            Artifact artifact = request.getArtifact();
            boolean pomOnly = artifact != null && POM_EXTENSION.equals(artifact.getExtension());
            return pomOnly ? ArtifactDescriptorPolicy.IGNORE_ERRORS : ArtifactDescriptorPolicy.IGNORE_INVALID;
        });
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
        if (progressListener != null) {
            sessionBuilder.withRepositoryListener(new ProgressRepositoryListener(progressListener));
            sessionBuilder.withTransferListener(new ProgressTransferListener(progressListener));
        }
        return sessionBuilder.build();
    }

    private static final class ProgressRepositoryListener extends AbstractRepositoryListener {
        private final DependencyProgressListener listener;

        private ProgressRepositoryListener(DependencyProgressListener listener) {
            this.listener = listener;
        }

        @Override
        public void artifactResolving(RepositoryEvent event) {
            if (event.getArtifact() != null) listener.artifactPlanned(event.getArtifact().toString());
        }

        @Override
        public void artifactResolved(RepositoryEvent event) {
            if (event.getArtifact() != null) listener.artifactCompleted(event.getArtifact().toString());
        }
    }

    private static final class ProgressTransferListener implements TransferListener {
        private final DependencyProgressListener listener;

        private ProgressTransferListener(DependencyProgressListener listener) {
            this.listener = listener;
        }

        @Override
        public void transferInitiated(TransferEvent event) {
        }

        @Override
        public void transferStarted(TransferEvent event) {
            if (event.getResource() != null) {
                listener.artifactStarted(event.getResource().getResourceName());
                listener.artifactProgressed(event.getResource().getResourceName(),
                    event.getTransferredBytes(), event.getResource().getContentLength());
            }
        }

        @Override
        public void transferProgressed(TransferEvent event) {
            if (event.getResource() != null) {
                listener.artifactProgressed(event.getResource().getResourceName(),
                    event.getTransferredBytes(), event.getResource().getContentLength());
            }
        }

        @Override
        public void transferCorrupted(TransferEvent event) {
        }

        @Override
        public void transferSucceeded(TransferEvent event) {
            if (event.getResource() != null) listener.artifactTransferFinished(event.getResource().getResourceName());
        }

        @Override
        public void transferFailed(TransferEvent event) {
            // Transfer failures may belong to optional source artifacts. The enclosing
            // dependency resolution result is the authoritative scope failure signal.
        }
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
        return new RepositorySystemSupplier() {
            @Override
            protected Map<String, TransporterFactory> createTransporterFactories() {
                Map<String, TransporterFactory> factories = new LinkedHashMap<>();
                factories.put(FileTransporterFactory.NAME, new FileTransporterFactory());
                factories.put(JdkTransporterFactory.NAME, new JdkTransporterFactory(getChecksumExtractor(), getPathProcessor()));
                return factories;
            }
        }.get();
    }

    static List<RemoteRepository> toRepositories(List<String> configuredRepositories, boolean forceUpdates) {
        return toRepositories(configuredRepositories, forceUpdates, null);
    }

    static List<RemoteRepository> toRepositories(List<String> configuredRepositories,
                                                 boolean forceUpdates,
                                                 Path projectDirectory) {
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
                String url = value.contains("://") ? value : repositoryPath(value, projectDirectory).toUri().toString();
                resolved.put(id, newRemoteRepository(id, url, forceUpdates));
            }
        }
        return List.copyOf(resolved.values());
    }

    private static Path repositoryPath(String value, Path projectDirectory) {
        Path path = Path.of(value);
        if (!path.isAbsolute() && projectDirectory != null) {
            path = projectDirectory.resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private List<String> repositoriesForModel(PyprojectModel model) {
        if (model.pyronaut() == null) {
            return List.of();
        }
        List<String> repositories = model.pyronaut().repositories() == null ? List.of() : model.pyronaut().repositories();
        if (!snapshotRepositoryRequired(model) || !snapshotRepositoryEnabled()) {
            return repositories;
        }
        List<String> withSnapshots = new ArrayList<>(repositories.size() + 2);
        withSnapshots.addAll(repositories);
        boolean localConfigured = repositories.stream()
            .anyMatch(repository -> repository != null && "mavenlocal".equals(repository.trim().toLowerCase(Locale.ROOT)));
        if (!localConfigured) {
            withSnapshots.add(resolveLocalMavenRepository().toString());
        }
        withSnapshots.add(SONATYPE_SNAPSHOTS_REPOSITORY);
        return withSnapshots;
    }

    private boolean snapshotRepositoryRequired(PyprojectModel model) {
        String coreVersion = normalizedVersion(model.pyronaut().coreVersion());
        if (coreVersion != null && coreVersion.endsWith("-SNAPSHOT")) {
            return true;
        }

        // Direct-source projects may use a released Micronaut platform while
        // their versionless Pyronaut dependencies are managed by the
        // snapshot BOM belonging to the running Pyronaut snapshot build.
        String pyronautVersion = requiresPyronautManagedDependencies(model)
            ? normalizedVersion(pyronautVersionProvider.get())
            : null;
        return pyronautVersion != null && pyronautVersion.endsWith("-SNAPSHOT");
    }

    /**
     * Automatic Sonatype snapshot lookup can be disabled for air-gapped or
     * strictly reproducible builds with either a JVM property or environment
     * variable. It remains enabled by default for Micronaut snapshot builds.
     */
    private static boolean snapshotRepositoryEnabled() {
        String configured = System.getProperty("pyronaut.sonatype.snapshots.enabled");
        if (configured == null) {
            configured = System.getenv("PYRONAUT_SONATYPE_SNAPSHOTS");
        }
        return configured == null || Boolean.parseBoolean(configured);
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

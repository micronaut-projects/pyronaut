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

/**
 * Resolves classpaths using Apache Maven Resolver.
 */
final class MavenClasspathResolver {
    private final RepositorySystem repositorySystem;
    private final ProxyConfigurationLoader proxyConfigurationLoader;

    MavenClasspathResolver() {
        this(newRepositorySystem(), new ProxyConfigurationLoader());
    }

    MavenClasspathResolver(ProxyConfigurationLoader proxyConfigurationLoader) {
        this(newRepositorySystem(), proxyConfigurationLoader);
    }

    MavenClasspathResolver(RepositorySystem repositorySystem, ProxyConfigurationLoader proxyConfigurationLoader) {
        this.repositorySystem = repositorySystem;
        this.proxyConfigurationLoader = proxyConfigurationLoader;
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
        List<String> coordinates = coordinatesForScope(model, scope);
        if (coordinates.isEmpty()) {
            return new ResolvedScopeDetails(List.of(), null);
        }

        List<RemoteRepository> repositories = toRepositories(model.pyronaut() == null ? List.of() : model.pyronaut().repositories());
        ProxyConfigurationLoader.ProxyConfiguration proxyConfiguration = proxyConfigurationLoader.load().orElse(null);
        try (CloseableSession session = newSession(localRepositoryPath, offline, proxyConfiguration)) {
            CollectRequest collectRequest = new CollectRequest();
            collectRequest.setRepositories(repositories);
            List<Dependency> managedDependencies = managedDependencies(model, repositories, session);
            managedDependencies.forEach(collectRequest::addManagedDependency);
            Map<String, String> managedVersions = new LinkedHashMap<>();
            for (Dependency dependency : managedDependencies) {
                Artifact artifact = dependency.getArtifact();
                if (artifact == null || artifact.getVersion() == null || artifact.getVersion().isBlank()) {
                    continue;
                }
                managedVersions.putIfAbsent(artifact.getGroupId() + ":" + artifact.getArtifactId(), artifact.getVersion());
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
            return new ResolvedScopeDetails(classpath, result.getRoot());
        } catch (DependencyResolutionException e) {
            String message = "Dependency resolution failed for scope '" + scope.cliValue() + "': " + e.getMessage();
            if (proxyConfiguration != null) {
                message = message + " (proxy " + proxyConfiguration.summary() + ")";
            }
            throw new PyprojectModelException(message, e);
        }
    }

    record ResolvedScopeDetails(List<Path> classpath, DependencyNode root) {
    }

    private static List<String> coordinatesForScope(PyprojectModel model, InstallScope scope) {
        if (model.pyronaut() == null || model.pyronaut().dependencies() == null) {
            return List.of();
        }
        PyprojectModel.Dependencies dependencies = model.pyronaut().dependencies();
        if (scope == InstallScope.BUILD) {
            return dependencies.build() == null ? List.of() : dependencies.build();
        }
        if (scope == InstallScope.RUNTIME) {
            return dependencies.runtime() == null ? List.of() : dependencies.runtime();
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (dependencies.runtime() != null) {
            merged.addAll(dependencies.runtime());
        }
        if (dependencies.test() != null) {
            merged.addAll(dependencies.test());
        }
        return List.copyOf(merged);
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
                String localPath = Path.of(System.getProperty("user.home"), ".m2", "repository").toUri().toString();
                resolved.put("mavenLocal", new RemoteRepository.Builder("mavenLocal", "default", localPath).build());
            } else {
                String id = "repo-" + resolved.size();
                String url = value.contains("://") ? value : Path.of(value).toAbsolutePath().toUri().toString();
                resolved.put(id, new RemoteRepository.Builder(id, "default", url).build());
            }
        }
        return List.copyOf(resolved.values());
    }
}

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
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.supplier.SessionBuilderSupplier;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.DependencyFilterUtils;

import java.nio.file.Path;
import java.util.ArrayList;
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
    private static final String MICRONAUT_PLATFORM_VERSION = "4.10.2";

    private final RepositorySystem repositorySystem;

    MavenClasspathResolver() {
        this.repositorySystem = newRepositorySystem();
    }

    List<Path> resolveScope(PyprojectModel model,
                            InstallScope scope,
                            Path localRepositoryPath,
                            boolean offline) {
        List<String> coordinates = coordinatesForScope(model, scope);
        if (coordinates.isEmpty()) {
            return List.of();
        }

        List<RemoteRepository> repositories = toRepositories(model.pyronaut() == null ? List.of() : model.pyronaut().repositories());
        try (CloseableSession session = newSession(localRepositoryPath, offline)) {
            CollectRequest collectRequest = new CollectRequest();
            collectRequest.setRepositories(repositories);
            managedDependencies(model).forEach(collectRequest::addManagedDependency);

            for (String coordinate : coordinates) {
                collectRequest.addDependency(toDependency(coordinate));
            }

            DependencyRequest dependencyRequest = new DependencyRequest(
                collectRequest,
                DependencyFilterUtils.classpathFilter(JavaScopes.RUNTIME)
            );

            DependencyResult result = repositorySystem.resolveDependencies(session, dependencyRequest);
            return result.getArtifactResults().stream()
                .map(artifactResult -> artifactResult.getArtifact())
                .filter(Objects::nonNull)
                .map(Artifact::getPath)
                .filter(Objects::nonNull)
                .map(Path::toAbsolutePath)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        } catch (DependencyResolutionException e) {
            throw new PyprojectModelException("Dependency resolution failed for scope '" + scope.cliValue() + "': " + e.getMessage(), e);
        }
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

    private static List<Dependency> managedDependencies(PyprojectModel model) {
        if (model.pyronaut() == null || model.pyronaut().version() == null || model.pyronaut().version().isBlank()) {
            return List.of();
        }
        String pyronautVersion = model.pyronaut().version();
        List<Dependency> managed = new ArrayList<>();
        managed.add(new Dependency(new DefaultArtifact("io.micronaut", "micronaut-core-bom", "", "pom", pyronautVersion), "import"));
        managed.add(new Dependency(new DefaultArtifact("io.micronaut.platform", "micronaut-platform", "", "pom", MICRONAUT_PLATFORM_VERSION), "import"));
        return managed;
    }

    private static Dependency toDependency(String coordinate) {
        String[] parts = coordinate.split(":");
        if (parts.length == 3) {
            return new Dependency(new DefaultArtifact(parts[0], parts[1], "jar", parts[2]), JavaScopes.RUNTIME);
        }
        if (parts.length == 2) {
            return new Dependency(new DefaultArtifact(parts[0], parts[1], "jar", null), JavaScopes.RUNTIME);
        }
        throw new PyprojectModelException("Invalid dependency coordinate: '" + coordinate + "'. Expected group:artifact[:version]");
    }

    private CloseableSession newSession(Path localRepositoryPath, boolean offline) {
        SessionBuilder sessionBuilder = new SessionBuilderSupplier(repositorySystem).get();
        sessionBuilder.setOffline(offline);
        sessionBuilder.withLocalRepositoryBaseDirectories(localRepositoryPath.toAbsolutePath());
        return sessionBuilder.build();
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

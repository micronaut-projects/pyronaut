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
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenClasspathResolverSourcesTest {
    @Test
    void batchesSourcesAndRetainsPartialSuccessesAndDuplicateBinaries(@TempDir Path tempDir) throws Exception {
        Path repository = tempDir.resolve("repository");
        for (String artifactId : List.of("available", "missing")) {
            Path directory = Files.createDirectories(repository.resolve("example/" + artifactId + "/1.0"));
            Files.writeString(directory.resolve(artifactId + "-1.0.jar"), "binary");
            Files.writeString(directory.resolve(artifactId + "-1.0.pom"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId>
                  <artifactId>%s</artifactId>
                  <version>1.0</version>
                </project>
                """.formatted(artifactId));
        }
        Files.writeString(repository.resolve("example/available/1.0/available-1.0-sources.jar"), "sources");
        PyprojectModel model = new PyprojectModel(null, null, new PyprojectModel.Pyronaut(
            null, null, List.of(repository.toUri().toString()), null,
            new PyprojectModel.Dependencies(List.of("example:available:1.0", "example:missing:1.0"),
                List.of(), List.of(), List.of(), List.of(), List.of(), Map.of()),
            null, null, null, null, null, null, null, null, null, null, false));
        List<List<ArtifactRequest>> sourceBatches = new ArrayList<>();
        RepositorySystem delegate = new RepositorySystemSupplier() {
            @Override
            protected Map<String, TransporterFactory> createTransporterFactories() {
                return Map.of(FileTransporterFactory.NAME, new FileTransporterFactory());
            }
        }.get();
        try {
            RepositorySystem recordingSystem = (RepositorySystem) Proxy.newProxyInstance(
                RepositorySystem.class.getClassLoader(), new Class<?>[]{RepositorySystem.class}, (proxy, method, args) -> {
                    if ("resolveArtifacts".equals(method.getName())) {
                        sourceBatches.add(((List<?>) args[1]).stream().map(ArtifactRequest.class::cast).toList());
                    }
                    try {
                        Object value = method.invoke(delegate, args);
                        if (value instanceof DependencyResult result) {
                            List<ArtifactResult> artifacts = new ArrayList<>(result.getArtifactResults());
                            artifacts.add(artifacts.getFirst());
                            result.setArtifactResults(artifacts);
                        }
                        return value;
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
            MavenClasspathResolver resolver = new MavenClasspathResolver(recordingSystem, new ProxyConfigurationLoader());
            var result = resolver.resolveScopeDetails(model, InstallScope.RUNTIME, tempDir.resolve("local"), false);

            assertEquals(1, sourceBatches.size());
            assertEquals(3, sourceBatches.getFirst().size());
            assertTrue(sourceBatches.getFirst().stream().allMatch(request -> "sources".equals(request.getArtifact().getClassifier())));
            var editorArtifacts = result.editorArtifacts();
            assertEquals(List.of("available", "missing", "available"), editorArtifacts.stream()
                .map(MavenClasspathResolver.ResolvedEditorArtifact::artifactId).toList());
            assertEquals("sources", Files.readString(editorArtifacts.getFirst().sourceJar()));
            assertNull(editorArtifacts.get(1).sourceJar());
            assertEquals(editorArtifacts.getFirst(), editorArtifacts.getLast());
            assertEquals(2, result.classpath().size());
        } finally {
            delegate.shutdown();
        }
    }
}

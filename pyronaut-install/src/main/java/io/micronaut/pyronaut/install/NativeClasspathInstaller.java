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
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves portable native launcher classpath descriptors into local path manifests. */
final class NativeClasspathInstaller {
    static final List<String> IMAGE_NAMES = List.of("pyronaut-dev", "pyronaut-run", "pyronaut-run-python");
    static final String RESOLVED_DIRECTORY = "resolved";

    private final MavenClasspathResolver resolver;

    NativeClasspathInstaller(MavenClasspathResolver resolver) {
        this.resolver = resolver;
    }

    void install(PyprojectModel model,
                 Path descriptorDirectory,
                 Path localRepository,
                 boolean offline,
                 boolean refresh,
                 DependencyProgressListener progressListener) throws IOException {
        if (!Files.isDirectory(descriptorDirectory)) {
            throw new IOException("Missing native classpath descriptor directory: " + descriptorDirectory);
        }
        Map<String, List<DescriptorEntry>> descriptors = new LinkedHashMap<>();
        Map<ArtifactKey, Artifact> requested = new LinkedHashMap<>();
        for (String imageName : IMAGE_NAMES) {
            Path descriptor = descriptorDirectory.resolve(imageName + ".tsv");
            List<DescriptorEntry> entries = readDescriptor(descriptor);
            descriptors.put(imageName, entries);
            for (DescriptorEntry entry : entries) {
                requested.putIfAbsent(entry.key(), entry.artifact());
            }
        }

        List<Artifact> artifacts = new ArrayList<>(requested.values());
        List<Path> resolved = resolver.resolveToolArtifacts(
            model,
            artifacts,
            localRepository,
            offline,
            refresh,
            progressListener
        );
        if (resolved.size() != artifacts.size()) {
            throw new IOException("Incomplete native launcher classpath resolution");
        }
        Map<ArtifactKey, Path> resolvedByKey = new LinkedHashMap<>();
        for (int i = 0; i < artifacts.size(); i++) {
            Path path = resolved.get(i).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                throw new IOException("Resolved native launcher artifact does not exist: " + path);
            }
            resolvedByKey.put(ArtifactKey.of(artifacts.get(i)), path);
        }

        Path outputDirectory = descriptorDirectory.resolve(RESOLVED_DIRECTORY);
        Files.createDirectories(outputDirectory);
        for (Map.Entry<String, List<DescriptorEntry>> descriptor : descriptors.entrySet()) {
            List<String> paths = descriptor.getValue().stream()
                .map(entry -> resolvedByKey.get(entry.key()))
                .map(path -> {
                    if (path == null) {
                        throw new IllegalStateException("Missing resolved native launcher artifact");
                    }
                    return path.toString();
                })
                .toList();
            Path output = outputDirectory.resolve(descriptor.getKey() + ".txt");
            Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
            Files.writeString(
                temporary,
                String.join(System.lineSeparator(), paths) + System.lineSeparator(),
                StandardCharsets.UTF_8
            );
            try {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static List<DescriptorEntry> readDescriptor(Path descriptor) throws IOException {
        if (!Files.isRegularFile(descriptor)) {
            throw new IOException("Missing native classpath descriptor: " + descriptor);
        }
        List<DescriptorEntry> entries = new ArrayList<>();
        for (String line : Files.readAllLines(descriptor, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            String[] fields = line.split("\\t", -1);
            if (fields.length != 7 || !"maven".equals(fields[0])) {
                throw new IOException("Invalid native classpath descriptor entry in " + descriptor + ": " + line);
            }
            for (int index = 1; index <= 4; index++) {
                if (fields[index].isBlank() || fields[index].indexOf('/') >= 0 || fields[index].indexOf('\\') >= 0) {
                    throw new IOException("Invalid native classpath coordinate in " + descriptor + ": " + line);
                }
            }
            String fileName = fields[6];
            if (fileName.isBlank() || !Path.of(fileName).getFileName().toString().equals(fileName)) {
                throw new IOException("Invalid native classpath artifact filename in " + descriptor + ": " + fileName);
            }
            String classifierSuffix = fields[5].isBlank() ? "" : "-" + fields[5];
            String expectedFileName = fields[2] + "-" + fields[3] + classifierSuffix + "." + fields[4];
            if (!expectedFileName.equals(fileName)) {
                throw new IOException(
                    "Native classpath filename does not match its Maven coordinate in " + descriptor + ": " + fileName
                );
            }
            entries.add(new DescriptorEntry(fields[1], fields[2], fields[3], fields[4], fields[5], fileName));
        }
        if (entries.isEmpty()) {
            throw new IOException("Native classpath descriptor is empty: " + descriptor);
        }
        return List.copyOf(entries);
    }

    private record DescriptorEntry(String group,
                                   String artifactId,
                                   String version,
                                   String extension,
                                   String classifier,
                                   String fileName) {
        Artifact artifact() {
            return new DefaultArtifact(group, artifactId, classifier, extension, version);
        }

        ArtifactKey key() {
            return new ArtifactKey(group, artifactId, version, extension, classifier);
        }
    }

    private record ArtifactKey(String group, String artifactId, String version, String extension, String classifier) {
        static ArtifactKey of(Artifact artifact) {
            return new ArtifactKey(
                artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion(), artifact.getExtension(), artifact.getClassifier()
            );
        }
    }
}

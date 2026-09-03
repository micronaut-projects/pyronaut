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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NativeClasspathInstallerTest {
    @Test
    void resolvesAllNativeDescriptorsAndPreservesOrder(@TempDir Path tempDir) throws Exception {
        Path repository = tempDir.resolve("repository");
        Path first = writeArtifact(repository, "com.example", "first", "1.0", "jar", "", "first");
        Path second = writeArtifact(repository, "com.example", "second", "2.0", "jar", "tests", "second");
        Path descriptors = tempDir.resolve("descriptors");
        Files.createDirectories(descriptors);
        for (String image : NativeClasspathInstaller.IMAGE_NAMES) {
            Files.write(descriptors.resolve(image + ".tsv"), List.of(
                descriptor("com.example", "second", "2.0", "jar", "tests", second.getFileName().toString()),
                descriptor("com.example", "first", "1.0", "jar", "", first.getFileName().toString()),
                descriptor("com.example", "first", "1.0", "jar", "", first.getFileName().toString())
            ));
        }

        List<String> progressEvents = new ArrayList<>();
        DependencyProgressListener progressListener = new DependencyProgressListener() {
            @Override public void artifactPlanned(String name) { progressEvents.add("planned:" + name); }
            @Override public void reset() { progressEvents.add("reset"); }
            @Override public void begin() { progressEvents.add("begin"); }
            @Override public void artifactStarted(String name) { progressEvents.add("started:" + name); }
            @Override public void artifactTransferFinished(String name) { progressEvents.add("transferred:" + name); }
            @Override public void artifactCompleted(String name) { progressEvents.add("completed:" + name); }
            @Override public void artifactFailed(String name) { progressEvents.add("failed:" + name); }
        };

        new NativeClasspathInstaller(new MavenClasspathResolver()).install(
            ToolClasspathInstaller.defaultModel(List.of()),
            descriptors,
            repository,
            true,
            false,
            progressListener
        );

        for (String image : NativeClasspathInstaller.IMAGE_NAMES) {
            assertEquals(
                List.of(second.toAbsolutePath().normalize().toString(), first.toAbsolutePath().normalize().toString(),
                    first.toAbsolutePath().normalize().toString()),
                Files.readAllLines(descriptors.resolve("resolved").resolve(image + ".txt"))
            );
        }
        assertTrue(progressEvents.contains("reset"));
        assertTrue(progressEvents.contains("begin"));
        assertTrue(progressEvents.stream().anyMatch(event -> event.startsWith("planned:com.example:second:jar:tests:2.0")));
    }

    @Test
    void rejectsHostPathsAndDoesNotPublishOutputs(@TempDir Path tempDir) throws Exception {
        Path descriptors = tempDir.resolve("descriptors");
        Files.createDirectories(descriptors);
        for (String image : NativeClasspathInstaller.IMAGE_NAMES) {
            Files.writeString(descriptors.resolve(image + ".tsv"), "/Users/builder/.gradle/cache/example.jar\n");
        }

        assertThrows(Exception.class, () -> new NativeClasspathInstaller(new MavenClasspathResolver()).install(
            ToolClasspathInstaller.defaultModel(List.of()),
            descriptors,
            tempDir.resolve("repository"),
            true,
            false,
            null
        ));
        assertTrue(Files.notExists(descriptors.resolve("resolved")));
    }

    @Test
    void nativeResolutionUsesPyronautProxySettingsAndPreservesExistingOutputsOnFailure(@TempDir Path tempDir) throws Exception {
        Path descriptors = tempDir.resolve("descriptors");
        Files.createDirectories(descriptors);
        for (String image : NativeClasspathInstaller.IMAGE_NAMES) {
            Files.writeString(descriptors.resolve(image + ".tsv"),
                descriptor("com.example", "missing", "1.0", "jar", "", "missing-1.0.jar") + "\n");
        }
        Path resolved = descriptors.resolve(NativeClasspathInstaller.RESOLVED_DIRECTORY);
        Files.createDirectories(resolved);
        Path existing = resolved.resolve("pyronaut-dev.txt");
        Files.writeString(existing, "existing\n");
        Path pyronautSettings = tempDir.resolve("settings.toml");
        Files.writeString(pyronautSettings, "[proxy]\nhost=\"pyronaut-proxy\"\nport=8080\n");
        MavenClasspathResolver resolver = new MavenClasspathResolver(new ProxyConfigurationLoader(
            Map.of(), pyronautSettings, tempDir.resolve("missing-maven-settings.xml")
        ));

        Exception failure = assertThrows(Exception.class, () -> new NativeClasspathInstaller(resolver).install(
            ToolClasspathInstaller.defaultModel(),
            descriptors,
            tempDir.resolve("repository"),
            true,
            false,
            null
        ));

        assertTrue(failure.getMessage().contains("proxy ~/.pyronaut/settings.toml -> http://pyronaut-proxy:8080"));
        assertEquals(List.of("existing"), Files.readAllLines(existing));
    }

    private static String descriptor(String group,
                                     String artifact,
                                     String version,
                                     String extension,
                                     String classifier,
                                     String fileName) {
        return String.join("\t", "maven", group, artifact, version, extension, classifier, fileName);
    }

    private static Path writeArtifact(Path repository,
                                      String group,
                                      String artifact,
                                      String version,
                                      String extension,
                                      String classifier,
                                      String contents) throws Exception {
        Path directory = repository.resolve(group.replace('.', '/')).resolve(artifact).resolve(version);
        Files.createDirectories(directory);
        String classifierSuffix = classifier.isEmpty() ? "" : "-" + classifier;
        Path file = directory.resolve(artifact + "-" + version + classifierSuffix + "." + extension);
        Files.writeString(file, contents);
        return file;
    }
}

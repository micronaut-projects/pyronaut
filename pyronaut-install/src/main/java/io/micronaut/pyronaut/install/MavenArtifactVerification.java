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

import io.micronaut.json.JsonMapper;
import io.micronaut.json.tree.JsonNode;
import io.micronaut.pyronaut.config.model.PyprojectModelException;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;

/** Verifies selected groups against a trusted bundle lock, independently of a project's lock. */
final class MavenArtifactVerification {
    static final String REPORT_FILE = "verified-maven-artifacts.json";
    private static final MavenArtifactVerification OFF = new MavenArtifactVerification(null, List.of(), Map.of(), new byte[0]);
    private final Path file;
    private final List<String> groups;
    private final Map<String, DependencyLock.Entry> entries;
    private final Map<String, List<DependencyLock.Entry>> snapshots = new LinkedHashMap<>();
    private final byte[] contents;
    private final Set<String> violations = new ConcurrentSkipListSet<>();
    private final Map<String, VerifiedArtifact> verified = new ConcurrentSkipListMap<>();

    private MavenArtifactVerification(Path file, List<String> groups, Map<String, DependencyLock.Entry> entries, byte[] contents) {
        this.file = file;
        this.groups = groups;
        this.entries = entries;
        this.contents = contents;
        for (DependencyLock.Entry entry : entries.values()) {
            Artifact artifact = new DefaultArtifact(entry.coordinates());
            if (artifact.isSnapshot()) {
                String normalizedPath = DependencyLock.layoutPath(artifact.setVersion(artifact.getBaseVersion()));
                snapshots.computeIfAbsent(normalizedPath, ignored -> new ArrayList<>()).add(entry);
            }
        }
    }

    static MavenArtifactVerification off() {
        return OFF;
    }

    static boolean requiresResolution() {
        return MavenRepositoryConfiguration.load().verification().active();
    }

    static MavenArtifactVerification load(JsonNode maven, Path settingsDirectory) throws IOException {
        JsonNode verification = maven == null ? null : maven.get("verification");
        if (verification == null) {
            return OFF;
        }
        JsonNode lockFile = verification.isObject() ? verification.get("lock-file") : null;
        JsonNode configuredGroups = verification.isObject() ? verification.get("groups") : null;
        if (lockFile == null || !lockFile.isString() || lockFile.getStringValue().isBlank()
            || configuredGroups == null || !configuredGroups.isArray() || configuredGroups.size() == 0) {
            throw new PyprojectModelException("[maven.verification] requires a non-empty lock-file string and groups array");
        }
        List<String> groups = new ArrayList<>();
        for (JsonNode group : configuredGroups.values()) {
            if (!group.isString() || !group.getStringValue().matches("[A-Za-z0-9_-]++(?:\\.[A-Za-z0-9_-]++)*+(?:\\.\\*)?")) {
                throw new PyprojectModelException("[maven.verification].groups entries must be Maven group IDs, optionally ending in .*");
            }
            groups.add(group.getStringValue());
        }
        Path file = settingsDirectory.resolve(lockFile.getStringValue()).normalize();
        byte[] contents;
        try {
            contents = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new PyprojectModelException("Failed reading trusted Maven verification lock " + file, e);
        }
        return new MavenArtifactVerification(file, List.copyOf(groups), readEntries(file, contents), contents);
    }

    private static Map<String, DependencyLock.Entry> readEntries(Path file, byte[] contents) throws IOException {
        Map<String, DependencyLock.Entry> entries = new LinkedHashMap<>();
        try {
            for (DependencyLock.Entry entry : DependencyLock.read(file, contents)) {
                int fields = entry.coordinates().split(":", -1).length;
                if (fields != 4 && fields != 5) {
                    throw new IllegalArgumentException("Expected group:artifact:extension[:classifier]:version: " + entry.coordinates());
                }
                Artifact artifact = new DefaultArtifact(entry.coordinates());
                if (!DependencyLock.layoutPath(artifact).equals(entry.path())) {
                    throw new IllegalArgumentException("Artifact coordinates do not match path: " + entry.path());
                }
                if (entries.putIfAbsent(entry.path(), entry) != null) {
                    throw new IllegalArgumentException("Duplicate artifact path: " + entry.path());
                }
            }
        } catch (IllegalArgumentException e) {
            throw new PyprojectModelException("Invalid Maven verification lock " + file + ": " + e.getMessage(), e);
        }
        return Map.copyOf(entries);
    }

    boolean active() {
        return file != null;
    }

    byte[] fingerprintBytes() {
        return contents.clone();
    }

    void verifyPom(Artifact artifact) {
        // POMs build the graph; binaries are verified after refresh has selected their final bytes.
        if (artifact != null && "pom".equals(artifact.getExtension())) {
            verifyArtifact(artifact);
        }
    }

    void verifyArtifact(Artifact artifact) {
        if (!active() || artifact == null || artifact.getPath() == null) {
            return;
        }
        String artifactFile = artifact.getPath().toAbsolutePath().normalize().toString();
        if (!selected(artifact.getGroupId())) {
            verified.put(artifactFile, new VerifiedArtifact(artifact.toString(), artifact.getGroupId(), DependencyLock.layoutPath(artifact), artifactFile));
            return;
        }
        String path = DependencyLock.layoutPath(artifact);
        DependencyLock.Entry entry = entries.get(path);
        if (entry == null && artifact.getVersion().endsWith("-SNAPSHOT")) {
            // Maven's local cache normalizes timestamps; accept only an unambiguous trusted snapshot.
            List<DependencyLock.Entry> candidates = snapshots.getOrDefault(path, List.of());
            if (candidates.size() == 1) {
                entry = candidates.getFirst();
            }
        }
        if (entry == null) {
            violations.add(artifact + " is missing from the trusted bundle lock");
            return;
        }
        try {
            String actual = DependencyLock.sha256(artifact.getPath());
            if (!entry.sha256().equalsIgnoreCase(actual)) {
                violations.add(artifact + ": expected SHA-256 " + entry.sha256() + ", but " + artifact.getPath() + " has " + actual);
            } else {
                verified.put(artifactFile, new VerifiedArtifact(entry.coordinates(), artifact.getGroupId(), entry.path(), artifactFile));
            }
        } catch (IOException e) {
            violations.add("Cannot verify " + artifact + ": " + e.getMessage());
        }
    }

    List<VerifiedArtifact> verifiedArtifacts() {
        return verified.values().stream().toList();
    }

    static void writeReport(Path cacheDir, List<VerifiedArtifact> artifacts) throws IOException {
        Path report = cacheDir.resolve(REPORT_FILE);
        MavenRepositoryConfiguration configuration = MavenRepositoryConfiguration.load();
        if (!configuration.verification().active()) {
            Files.deleteIfExists(report);
            return;
        }
        Map<String, VerifiedArtifact> unique = new LinkedHashMap<>();
        for (VerifiedArtifact artifact : artifacts) {
            if (artifact != null && artifact.file() != null) {
                String file = Path.of(artifact.file()).toAbsolutePath().normalize().toString();
                unique.putIfAbsent(file, new VerifiedArtifact(artifact.coordinates(), artifact.groupId(), artifact.path(), file));
            }
        }
        List<Map<String, Object>> serialized = unique.values().stream()
            .sorted(Comparator.comparing(VerifiedArtifact::file))
            .map(artifact -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("coordinates", artifact.coordinates());
                result.put("groupId", artifact.groupId());
                result.put("path", artifact.path());
                result.put("file", artifact.file());
                return result;
            })
            .toList();
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("version", 1);
        document.put("mavenSettingsSha256", configuration.fingerprint());
        document.put("artifacts", serialized);
        Files.createDirectories(cacheDir);
        Files.writeString(report, JsonMapper.createDefault().writeValueAsString(document), StandardCharsets.UTF_8);
    }

    private boolean selected(String group) {
        return groups.stream().anyMatch(pattern -> pattern.endsWith(".*")
            ? group.startsWith(pattern.substring(0, pattern.length() - 1)) : group.equals(pattern));
    }

    void check() {
        if (!violations.isEmpty()) {
            throw new PyprojectModelException("Maven bundle verification failed against " + file + ":\n  "
                + String.join("\n  ", violations.stream().limit(20).toList())
                + "\nUse artifacts matching the trusted bundle, or an isolated local repository for that bundle.");
        }
    }

    record VerifiedArtifact(String coordinates, String groupId, String path, String file) {
    }
}

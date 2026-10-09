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

import io.micronaut.json.tree.JsonNode;
import io.micronaut.pyronaut.config.model.PyprojectModelException;
import io.micronaut.toml.Parser;
import io.micronaut.toml.TomlStreamReadException;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.repository.ArtifactRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * The {@code pyronaut.lock} file: every Maven artifact (JARs and the POMs needed
 * to read their dependencies) that Pyronaut resolves for a project, with the
 * repository it came from and its SHA-256 checksum.
 *
 * <p>A lock is used in one of four modes. Without a lock file nothing is
 * checked. When a lock file exists, every resolved artifact that the lock
 * lists must match its checksum ({@link Mode#VERIFY}). With {@code --locked}
 * every resolved artifact must also be listed ({@link Mode#LOCKED}), and a
 * lock repository restricts resolution to a directory holding exactly the
 * locked files. {@code pyronaut lock} records what was resolved
 * ({@link Mode#RECORD}).</p>
 *
 * <p>The lock is process wide because the installers each create their own
 * {@link MavenClasspathResolver}; {@link PyronautInstallMain} activates it
 * once per invocation.</p>
 */
@SuppressWarnings("checkstyle:InnerTypeLast")
final class DependencyLock {
    static final String FILE_NAME = "pyronaut.lock";
    static final String LOCK_FILE_ENV = "PYRONAUT_LOCK_FILE";
    static final String LOCKED_ENV = "PYRONAUT_LOCKED";
    static final String LOCK_REPOSITORY_ENV = "PYRONAUT_LOCK_REPOSITORY";
    static final String LOCK_REPOSITORY_ID = "pyronaut-lock";
    static final int FORMAT_VERSION = 1;
    private static final int MAX_REPORTED_VIOLATIONS = 20;
    private static final Set<String> UNLOCKED_CLASSIFIERS = Set.of("sources", "javadoc");
    private static final DependencyLock OFF = new DependencyLock(null, Mode.OFF, Map.of(), null);
    private static volatile DependencyLock current = OFF;

    private final Path file;
    private final Mode mode;
    private final Map<String, Entry> locked;
    private final Path repository;
    private final SortedMap<String, Entry> recorded = new ConcurrentSkipListMap<>();
    private final Set<String> violations = new ConcurrentSkipListSet<>();
    private final Map<Path, String> checksums = new ConcurrentHashMap<>();

    private DependencyLock(Path file, Mode mode, Map<String, Entry> locked, Path repository) {
        this.file = file;
        this.mode = mode;
        this.locked = locked;
        this.repository = repository;
    }

    /**
     * How the lock file takes part in resolution.
     */
    enum Mode {
        /** No lock file: nothing is checked or recorded. */
        OFF,
        /** Artifacts listed in the lock must match their checksum. */
        VERIFY,
        /** Every artifact must be listed in the lock and match its checksum. */
        LOCKED,
        /** Resolved artifacts are recorded into the lock file. */
        RECORD
    }

    /**
     * A locked artifact.
     *
     * @param coordinates the Maven coordinates, {@code group:artifact:extension[:classifier]:version}
     * @param path        the path in the Maven repository layout
     * @param repository  the URL of the repository the artifact was resolved from
     * @param url         the URL the artifact can be downloaded from
     * @param sha256      the SHA-256 checksum of the artifact
     */
    record Entry(String coordinates, String path, String repository, String url, String sha256) {
    }

    static DependencyLock off() {
        return OFF;
    }

    static DependencyLock current() {
        return current;
    }

    static void activate(DependencyLock lock) {
        current = lock == null ? OFF : lock;
    }

    /**
     * Opens the lock for an install invocation.
     *
     * @param file       the lock file
     * @param record     whether resolved artifacts are recorded ({@code pyronaut lock})
     * @param locked     whether every artifact must be listed in the lock
     * @param repository a directory, in Maven repository layout, holding the
     *                   locked artifacts; resolution then uses nothing else
     * @return the lock
     * @throws IOException if the lock file cannot be read
     */
    static DependencyLock open(Path file, boolean record, boolean locked, Path repository) throws IOException {
        Path normalized = file.toAbsolutePath().normalize();
        if (record) {
            if (locked || repository != null) {
                throw new IllegalArgumentException("--write-lock cannot be combined with --locked or --lock-repository");
            }
            DependencyLock lock = new DependencyLock(normalized, Mode.RECORD, Map.of(), null);
            // Several installer invocations (SDK setup and the project) record
            // into one lock file, so the entries already written are kept.
            if (Files.isRegularFile(normalized)) {
                read(normalized).forEach(entry -> lock.recorded.put(entry.path(), entry));
            }
            return lock;
        }
        boolean enforced = locked || repository != null;
        if (!Files.isRegularFile(normalized)) {
            if (enforced) {
                throw new IllegalArgumentException("Locked resolution requires " + normalized
                    + ", which does not exist. Run `pyronaut lock` to create it.");
            }
            return OFF;
        }
        Map<String, Entry> entries = new ConcurrentHashMap<>();
        read(normalized).forEach(entry -> entries.put(entry.path(), entry));
        Path lockRepository = null;
        if (repository != null) {
            lockRepository = repository.toAbsolutePath().normalize();
            if (!Files.isDirectory(lockRepository)) {
                throw new IllegalArgumentException("Lock repository " + lockRepository + " is not a directory");
            }
        }
        return new DependencyLock(normalized, enforced ? Mode.LOCKED : Mode.VERIFY, Map.copyOf(entries), lockRepository);
    }

    Mode mode() {
        return mode;
    }

    Path file() {
        return file;
    }

    boolean active() {
        return mode != Mode.OFF;
    }

    /**
     * Whether every resolution must observe each artifact, so that results
     * cached from an earlier invocation cannot be reused unchecked.
     */
    boolean requiresResolution() {
        return mode == Mode.RECORD || mode == Mode.LOCKED;
    }

    Path repository() {
        return repository;
    }

    /**
     * A fingerprint of the lock for the install cache key, so a changed lock
     * file or mode resolves again.
     */
    String fingerprint() {
        if (mode == Mode.OFF) {
            return "off";
        }
        try {
            String contents = Files.isRegularFile(file) ? sha256(file) : "";
            return mode + ":" + contents + ":" + (repository == null ? "" : repository);
        } catch (IOException e) {
            throw new PyprojectModelException("Failed reading " + file, e);
        }
    }

    /**
     * The repositories to resolve from: with a lock repository only that
     * directory is used, otherwise the configured repositories.
     */
    List<RemoteRepository> repositories(List<RemoteRepository> configured) {
        if (repository == null) {
            return configured;
        }
        // The lock repository holds only the artifacts themselves, without the
        // .sha1/.md5 files Maven publishes; SHA-256 is verified against the lock.
        RepositoryPolicy policy = new RepositoryPolicy(true, RepositoryPolicy.UPDATE_POLICY_NEVER, RepositoryPolicy.CHECKSUM_POLICY_IGNORE);
        return List.of(new RemoteRepository.Builder(LOCK_REPOSITORY_ID, "default", repository.toUri().toString())
            .setReleasePolicy(policy)
            .setSnapshotPolicy(policy)
            .build());
    }

    /**
     * Records or verifies an artifact that Maven Resolver has resolved.
     *
     * @param artifact     the resolved artifact
     * @param origin       the repository the artifact was resolved from; the
     *                     local repository when its origin is unknown
     * @param repositories the repositories of the request, used as the origin
     *                     of an artifact found in the local repository without
     *                     provenance
     */
    void artifactResolved(Artifact artifact, ArtifactRepository origin, List<RemoteRepository> repositories) {
        if (mode == Mode.OFF || artifact == null || artifact.getPath() == null) {
            return;
        }
        if (artifact.getClassifier() != null && UNLOCKED_CLASSIFIERS.contains(artifact.getClassifier())) {
            // Source and Javadoc JARs are only used for editor support and
            // are optional: they never reach a classpath.
            return;
        }
        String path = layoutPath(artifact);
        Path file = artifact.getPath().toAbsolutePath().normalize();
        String checksum;
        try {
            checksum = checksums.computeIfAbsent(file, DependencyLock::uncheckedSha256);
        } catch (PyprojectModelException e) {
            violations.add("Unable to compute the checksum of " + artifact + ": " + e.getMessage());
            return;
        }
        if (mode == Mode.RECORD) {
            RemoteRepository remote = originRepository(origin, repositories);
            String repositoryUrl = remote == null ? "" : withTrailingSlash(remote.getUrl());
            recorded.put(path, new Entry(coordinates(artifact), path, repositoryUrl,
                repositoryUrl.isEmpty() ? "" : repositoryUrl + path, checksum));
            return;
        }
        Entry entry = locked.get(path);
        if (entry == null) {
            if (mode == Mode.LOCKED) {
                violations.add(coordinates(artifact) + " is not in " + FILE_NAME);
            }
            return;
        }
        if (!entry.sha256().equalsIgnoreCase(checksum)) {
            violations.add(coordinates(artifact) + " does not match " + FILE_NAME + ": expected sha256 "
                + entry.sha256() + " but " + file + " has " + checksum);
        }
    }

    /**
     * Fails when a resolution produced an artifact the lock rejects.
     */
    void check() {
        if (violations.isEmpty()) {
            return;
        }
        List<String> reported = new ArrayList<>(violations);
        StringBuilder message = new StringBuilder("Dependency lock verification failed (")
            .append(file)
            .append("):");
        reported.stream().limit(MAX_REPORTED_VIOLATIONS).forEach(violation -> message.append("\n  - ").append(violation));
        if (reported.size() > MAX_REPORTED_VIOLATIONS) {
            message.append("\n  (+").append(reported.size() - MAX_REPORTED_VIOLATIONS).append(" more)");
        }
        message.append("\nIf the dependencies changed intentionally, run `pyronaut lock` to update ")
            .append(FILE_NAME)
            .append(". A checksum mismatch can also mean a corrupted or tampered local repository entry.");
        throw new PyprojectModelException(message.toString());
    }

    /**
     * A hint appended to resolution failures in locked mode.
     */
    String resolutionHint() {
        if (repository != null) {
            return ". Locked resolution only uses the lock repository " + repository
                + "; make sure it contains every artifact listed in " + file
                + ", or run `pyronaut lock` if the dependencies changed";
        }
        return "";
    }

    /**
     * Writes the recorded artifacts to the lock file.
     */
    void write() throws IOException {
        if (mode != Mode.RECORD) {
            return;
        }
        check();
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
        Files.writeString(temporary, render(recorded.values()), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String render(Collection<Entry> entries) {
        StringBuilder toml = new StringBuilder()
            .append("# This file is generated by `pyronaut lock`. Do not edit it by hand.\n")
            .append("# It lists every Maven artifact Pyronaut resolves, with its SHA-256 checksum.\n")
            .append("version = ").append(FORMAT_VERSION).append('\n');
        entries.stream()
            .sorted(java.util.Comparator.comparing(Entry::path))
            .forEach(entry -> toml.append("\n[[artifact]]\n")
                .append("coordinates = ").append(quote(entry.coordinates())).append('\n')
                .append("path = ").append(quote(entry.path())).append('\n')
                .append("repository = ").append(quote(entry.repository())).append('\n')
                .append("url = ").append(quote(entry.url())).append('\n')
                .append("sha256 = ").append(quote(entry.sha256())).append('\n'));
        return toml.toString();
    }

    static List<Entry> read(Path file) throws IOException {
        JsonNode parsed;
        try (InputStream input = Files.newInputStream(file)) {
            parsed = Parser.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (TomlStreamReadException e) {
            throw new IllegalArgumentException("Invalid " + file + ": " + e.getMessage(), e);
        }
        JsonNode version = parsed.get("version");
        if (version == null || !version.isNumber() || version.getIntValue() != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported " + file + " format version; expected version = "
                + FORMAT_VERSION + ". Run `pyronaut lock` to regenerate it.");
        }
        JsonNode artifacts = parsed.get("artifact");
        if (artifacts == null) {
            return List.of();
        }
        if (!artifacts.isArray()) {
            throw new IllegalArgumentException("Invalid " + file + ": artifact must be an array of tables");
        }
        List<Entry> entries = new ArrayList<>(artifacts.size());
        for (int i = 0; i < artifacts.size(); i++) {
            JsonNode artifact = artifacts.get(i);
            String path = requiredString(file, artifact, "path");
            String sha256 = requiredString(file, artifact, "sha256");
            if (!sha256.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("Invalid " + file + ": sha256 of " + path + " is not a SHA-256 checksum");
            }
            if (path.startsWith("/") || path.contains("\\") || List.of(path.split("/")).contains("..")) {
                throw new IllegalArgumentException("Invalid " + file + ": artifact path " + path + " must be relative");
            }
            entries.add(new Entry(
                optionalString(artifact, "coordinates"),
                path,
                optionalString(artifact, "repository"),
                optionalString(artifact, "url"),
                sha256.toLowerCase(Locale.ROOT)
            ));
        }
        return List.copyOf(entries);
    }

    /**
     * The path of an artifact in the Maven repository layout, as served by a
     * remote repository.
     */
    static String layoutPath(Artifact artifact) {
        String classifier = artifact.getClassifier() == null || artifact.getClassifier().isEmpty()
            ? ""
            : "-" + artifact.getClassifier();
        return artifact.getGroupId().replace('.', '/') + "/" + artifact.getArtifactId() + "/" + artifact.getBaseVersion()
            + "/" + artifact.getArtifactId() + "-" + artifact.getVersion() + classifier + "." + artifact.getExtension();
    }

    private static String coordinates(Artifact artifact) {
        return artifact.toString();
    }

    private static RemoteRepository originRepository(ArtifactRepository origin, List<RemoteRepository> repositories) {
        if (origin instanceof RemoteRepository remote) {
            return remote;
        }
        // Found in the local repository without provenance, for example a
        // module seeded from the SDK wheel: attribute it to the first
        // configured network repository, which is where it is published.
        if (repositories == null || repositories.isEmpty()) {
            return null;
        }
        return repositories.stream()
            .filter(repository -> !"file".equalsIgnoreCase(repository.getProtocol()))
            .findFirst()
            .orElse(repositories.getFirst());
    }

    private static String withTrailingSlash(String url) {
        return url.endsWith("/") ? url : url + "/";
    }

    private static String requiredString(Path file, JsonNode node, String name) {
        String value = optionalString(node, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Invalid " + file + ": every artifact requires " + name);
        }
        return value;
    }

    private static String optionalString(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        return value == null || !value.isString() ? "" : value.getStringValue();
    }

    private static String quote(String value) {
        StringBuilder quoted = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (c < 0x20) {
                        quoted.append(String.format("\\u%04x", (int) c));
                    } else {
                        quoted.append(c);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }

    private static String uncheckedSha256(Path file) {
        try {
            return sha256(file);
        } catch (IOException e) {
            throw new PyprojectModelException(e.getMessage(), e);
        }
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            try (InputStream input = Files.newInputStream(file)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", e);
        }
    }
}

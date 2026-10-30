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
import io.micronaut.pyronaut.config.model.PyronautHome;
import io.micronaut.toml.Parser;
import org.eclipse.aether.repository.Authentication;
import org.eclipse.aether.repository.AuthenticationSelector;
import org.eclipse.aether.util.repository.DefaultMirrorSelector;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * User Maven settings shared by every embedded resolver invocation.
 *
 * @param repositories defaults used when a project does not declare repositories
 * @param prependRepositories repositories searched before project repositories
 * @param mirrors Maven mirror selection rules
 * @param credentials credentials for effective repository URLs
 * @param versionOverrides forced versions by {@code group:artifact}
 * @param retryCount HTTP retry count, when explicitly configured
 * @param verification trusted artifact verification policy
 * @param fingerprint digest of the settings directory, settings and trusted lock contents, or empty when settings are absent
 */
record MavenRepositoryConfiguration(List<String> repositories,
                                    List<String> prependRepositories,
                                    List<Mirror> mirrors,
                                    List<MavenRepositoryCredentials> credentials,
                                    Map<String, String> versionOverrides,
                                    Integer retryCount,
                                    MavenArtifactVerification verification,
                                    String fingerprint) {
    static MavenRepositoryConfiguration load() {
        return load(PyronautHome.configHome().resolve("settings.toml"));
    }

    static MavenRepositoryConfiguration load(Path settings) {
        if (!Files.exists(settings)) {
            return new MavenRepositoryConfiguration(List.of(), List.of(), List.of(), List.of(), Map.of(), null,
                MavenArtifactVerification.off(), "");
        }
        try {
            byte[] contents = Files.readAllBytes(settings);
            JsonNode root = Parser.parse(new String(contents, StandardCharsets.UTF_8));
            JsonNode maven = root.get("maven");
            Path settingsDirectory = settings.toAbsolutePath().getParent().toRealPath();
            if (maven != null && !maven.isObject()) {
                throw new PyprojectModelException("[maven] must be a table in " + settings);
            }
            MavenArtifactVerification verification = MavenArtifactVerification.load(maven, settingsDirectory);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(settingsDirectory.toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(contents);
            if (verification.active()) {
                digest.update((byte) 0);
                digest.update(verification.fingerprintBytes());
            }
            String fingerprint = HexFormat.of().formatHex(digest.digest());
            if (maven == null) {
                return new MavenRepositoryConfiguration(List.of(), List.of(), List.of(), List.of(), Map.of(), null,
                    verification, fingerprint);
            }
            return new MavenRepositoryConfiguration(readRepositories(maven, settingsDirectory, "repositories", false),
                readRepositories(maven, settingsDirectory, "prepend-repositories", true), readMirrors(maven),
                MavenRepositoryCredentials.read(maven.get("credentials")), readVersionOverrides(maven),
                readRetryCount(maven), verification, fingerprint);
        } catch (IOException e) {
            throw new PyprojectModelException("Failed reading Maven settings from " + settings, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", e);
        }
    }

    DefaultMirrorSelector mirrorSelector() {
        DefaultMirrorSelector selector = new DefaultMirrorSelector();
        for (Mirror mirror : mirrors) {
            selector.add(mirror.id(), mirror.url(), "default", false, false, mirror.mirrorOf(), "*");
        }
        return selector;
    }

    AuthenticationSelector authenticationSelector(Function<String, String> environment) {
        return repository -> {
            for (MavenRepositoryCredentials credential : credentials) {
                Authentication authentication = credential.authentication(repository, environment);
                if (authentication != null) {
                    return authentication;
                }
            }
            return null;
        };
    }

    private static List<String> readRepositories(JsonNode maven, Path settingsDirectory, String key, boolean allowEmpty) {
        JsonNode configured = maven.get(key);
        if (configured == null) {
            return List.of();
        }
        if (!configured.isArray() || (!allowEmpty && configured.size() == 0)) {
            throw new PyprojectModelException("[maven]." + key + " must be " + (allowEmpty ? "a" : "a non-empty") + " string array");
        }
        List<String> repositories = new ArrayList<>();
        for (JsonNode value : configured.values()) {
            if (!value.isString() || value.getStringValue().isBlank()) {
                throw new PyprojectModelException("[maven]." + key + " entries must be non-empty strings");
            }
            String repository = value.getStringValue().trim();
            if (!repository.contains("://") && !repository.equalsIgnoreCase("mavenCentral") && !repository.equalsIgnoreCase("mavenLocal")) {
                repository = settingsDirectory.resolve(repository).normalize().toString();
            }
            repositories.add(repository);
        }
        return List.copyOf(repositories);
    }

    private static List<Mirror> readMirrors(JsonNode maven) {
        JsonNode configured = maven.get("mirrors");
        if (configured == null) {
            return List.of();
        }
        if (!configured.isArray()) {
            throw new PyprojectModelException("[maven].mirrors must be an array of tables");
        }
        List<Mirror> mirrors = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode entry : configured.values()) {
            Mirror mirror = new Mirror(requiredString(entry, "id"), requiredString(entry, "url"), requiredString(entry, "mirror-of"));
            if (!ids.add(mirror.id()) || DependencyLock.LOCK_REPOSITORY_ID.equals(mirror.id())) {
                throw new PyprojectModelException("Duplicate or reserved Maven mirror id: " + mirror.id());
            }
            try {
                if (!URI.create(mirror.url()).isAbsolute()) {
                    throw new IllegalArgumentException("URL must be absolute");
                }
            } catch (IllegalArgumentException e) {
                throw new PyprojectModelException("Invalid Maven mirror URL for " + mirror.id(), e);
            }
            mirrors.add(mirror);
        }
        return List.copyOf(mirrors);
    }

    private static Map<String, String> readVersionOverrides(JsonNode maven) {
        JsonNode configured = maven.get("version-overrides");
        if (configured == null) {
            return Map.of();
        }
        if (!configured.isObject()) {
            throw new PyprojectModelException("[maven.version-overrides] must be a table");
        }
        Map<String, String> overrides = new LinkedHashMap<>();
        for (var entry : configured.entries()) {
            String module = entry.getKey();
            if (!module.matches("[^:/\\\\\\s]+:[^:/\\\\\\s]+")) {
                throw new PyprojectModelException("[maven.version-overrides] keys must be group:artifact coordinates: " + entry.getKey());
            }
            JsonNode value = entry.getValue();
            if (!value.isString() || !value.getStringValue().matches("[^\\s\\\\/:]+")) {
                throw new PyprojectModelException("[maven.version-overrides]." + entry.getKey()
                    + " must be a version without whitespace, slashes or colons");
            }
            overrides.put(module, value.getStringValue());
        }
        return Map.copyOf(overrides);
    }

    private static Integer readRetryCount(JsonNode maven) {
        JsonNode configured = maven.get("retry-count");
        if (configured == null) {
            return null;
        }
        Number raw = configured.isNumber() ? configured.getNumberValue() : null;
        if (!(raw instanceof Integer || raw instanceof Long || raw instanceof BigInteger)) {
            throw new PyprojectModelException("[maven].retry-count must be a non-negative integer");
        }
        BigInteger value = raw instanceof BigInteger bigInteger ? bigInteger : BigInteger.valueOf(raw.longValue());
        if (value.signum() < 0 || value.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new PyprojectModelException("[maven].retry-count must be a non-negative integer");
        }
        return value.intValue();
    }

    private static String requiredString(JsonNode entry, String key) {
        JsonNode value = entry.isObject() ? entry.get(key) : null;
        if (value == null || !value.isString() || value.getStringValue().isBlank()) {
            throw new PyprojectModelException("[maven].mirrors entries require a non-empty '" + key + "' string");
        }
        return value.getStringValue().trim();
    }

    record Mirror(String id, String url, String mirrorOf) {
    }
}

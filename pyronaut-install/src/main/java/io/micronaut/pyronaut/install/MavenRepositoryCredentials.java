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
import org.eclipse.aether.repository.Authentication;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.util.repository.AuthenticationBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Credentials scoped to one effective HTTP repository URL, including its path. */
final class MavenRepositoryCredentials {
    private static final String CONFIGURATION = "[maven].credentials";

    private final URI url;
    private final SecretValue username;
    private final SecretValue password;

    private MavenRepositoryCredentials(URI url, SecretValue username, SecretValue password) {
        this.url = url;
        this.username = username;
        this.password = password;
    }

    static List<MavenRepositoryCredentials> read(JsonNode configured) {
        if (configured == null) {
            return List.of();
        }
        if (!configured.isArray()) {
            throw new PyprojectModelException(CONFIGURATION + " must be an array of tables");
        }
        List<MavenRepositoryCredentials> credentials = new ArrayList<>();
        Set<URI> urls = new HashSet<>();
        for (JsonNode entry : configured.values()) {
            String value = stringValue(entry, "url");
            URI url = value == null ? null : repositoryUrl(value.trim());
            if (url == null) {
                throw new PyprojectModelException(CONFIGURATION + " requires an HTTP(S) 'url' without user info, query, or fragment");
            }
            if (!urls.add(url)) {
                throw new PyprojectModelException(CONFIGURATION + " contains duplicate repository URLs");
            }
            credentials.add(new MavenRepositoryCredentials(url, SecretValue.read(entry, "username"), SecretValue.read(entry, "password")));
        }
        return List.copyOf(credentials);
    }

    Authentication authentication(RemoteRepository repository, Function<String, String> environment) {
        if (!url.equals(repositoryUrl(repository.getUrl()))) {
            return null;
        }
        return new AuthenticationBuilder()
            .addUsername(username.resolve(environment))
            .addPassword(password.resolve(environment))
            .build();
    }

    private static URI repositoryUrl(String value) {
        try {
            URI uri = URI.create(value).normalize();
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                return null;
            }
            String normalized = uri.toString();
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return URI.create(normalized);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String stringValue(JsonNode entry, String key) {
        if (!entry.isObject()) {
            throw new PyprojectModelException(CONFIGURATION + " entries must be tables");
        }
        JsonNode value = entry.get(key);
        if (value == null) {
            return null;
        }
        if (!value.isString()) {
            throw new PyprojectModelException(CONFIGURATION + " '" + key + "' must be a string");
        }
        return value.getStringValue();
    }

    private record SecretValue(String value, String variable) {
        static SecretValue read(JsonNode entry, String key) {
            String value = stringValue(entry, key);
            String variable = stringValue(entry, key + "-env");
            if ((value == null) == (variable == null)) {
                throw new PyprojectModelException(CONFIGURATION + " requires exactly one of '" + key + "' and '" + key + "-env'");
            }
            if (variable != null && variable.isBlank()) {
                throw new PyprojectModelException(CONFIGURATION + " '" + key + "-env' must name an environment variable");
            }
            return new SecretValue(value, variable == null ? null : variable.trim());
        }

        String resolve(Function<String, String> environment) {
            if (variable == null) {
                return value;
            }
            String resolved = environment.apply(variable);
            if (resolved == null) {
                throw new PyprojectModelException("Environment variable '" + variable + "' configured by " + CONFIGURATION + " is not set");
            }
            return resolved;
        }

        @Override
        public String toString() {
            return "<redacted>";
        }
    }
}

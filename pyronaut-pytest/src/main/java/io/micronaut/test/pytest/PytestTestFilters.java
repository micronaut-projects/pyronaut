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
package io.micronaut.test.pytest;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parsed representation of repeatable {@code --tests} selectors.
 */
public final class PytestTestFilters {
    private final List<String> raw;
    private final List<String> passthrough;
    private final List<Pattern> wildcard;

    private PytestTestFilters(List<String> raw, List<String> passthrough, List<Pattern> wildcard) {
        this.raw = raw;
        this.passthrough = passthrough;
        this.wildcard = wildcard;
    }

    public static PytestTestFilters from(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return new PytestTestFilters(List.of(), List.of(), List.of());
        }
        String[] parts = encoded.split("\\|");
        List<String> raw = new ArrayList<>(parts.length);
        List<String> passthrough = new ArrayList<>();
        List<Pattern> wildcard = new ArrayList<>();
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String value = part.trim();
            if (value.isEmpty()) {
                continue;
            }
            raw.add(value);
            if (value.contains("::") || value.endsWith(".py")) {
                passthrough.add(normalizeNodeId(value));
            } else {
                wildcard.add(toWildcardPattern(value));
            }
        }
        return new PytestTestFilters(List.copyOf(raw), List.copyOf(passthrough), List.copyOf(wildcard));
    }

    public List<String> raw() {
        return raw;
    }

    public boolean isEmpty() {
        return raw.isEmpty();
    }

    public boolean matches(String normalizedNodeId) {
        if (isEmpty()) {
            return true;
        }
        String id = normalizeNodeId(normalizedNodeId);
        for (String pass : passthrough) {
            if (id.contains(pass) || id.endsWith(pass)) {
                return true;
            }
        }
        for (Pattern p : wildcard) {
            if (p.matcher(id).matches()) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeNodeId(String value) {
        return value.replace('\\', '/');
    }

    private static Pattern toWildcardPattern(String raw) {
        String normalized = raw.replace('\\', '/');
        StringBuilder out = new StringBuilder(normalized.length() * 2);
        out.append(".*");
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c == '*') {
                out.append(".*");
            } else if (c == '?') {
                out.append('.');
            } else {
                if (".[]{}()+-^$|\\".indexOf(c) >= 0) {
                    out.append('\\');
                }
                out.append(c);
            }
        }
        out.append(".*");
        return Pattern.compile(out.toString());
    }
}

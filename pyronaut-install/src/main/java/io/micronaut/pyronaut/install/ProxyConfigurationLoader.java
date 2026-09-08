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
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

final class ProxyConfigurationLoader {
    private static final String SOURCE_ENV = "environment";
    private static final String SOURCE_PYRONAUT = "~/.pyronaut/settings.toml";
    private static final String SOURCE_M2 = "~/.m2/settings.xml";

    private final Map<String, String> environment;
    private final Path pyronautSettingsPath;
    private final Path m2SettingsPath;

    ProxyConfigurationLoader() {
        this(
            System.getenv(),
            Path.of(System.getProperty("user.home"), ".pyronaut", "settings.toml"),
            Path.of(System.getProperty("user.home"), ".m2", "settings.xml")
        );
    }

    ProxyConfigurationLoader(Map<String, String> environment, Path pyronautSettingsPath, Path m2SettingsPath) {
        this.environment = environment;
        this.pyronautSettingsPath = pyronautSettingsPath;
        this.m2SettingsPath = m2SettingsPath;
    }

    Optional<ProxyConfiguration> load() {
        Optional<ProxyConfiguration> environmentProxy = loadFromEnvironment();
        if (environmentProxy.isPresent()) {
            return environmentProxy;
        }

        Optional<ProxyConfiguration> pyronautProxy = loadFromPyronautSettings();
        if (pyronautProxy.isPresent()) {
            return pyronautProxy;
        }

        return loadFromMavenSettings();
    }

    private Optional<ProxyConfiguration> loadFromEnvironment() {
        String proxyUrl = firstNonBlank(
            environment.get("HTTPS_PROXY"),
            environment.get("https_proxy"),
            environment.get("HTTP_PROXY"),
            environment.get("http_proxy")
        );
        if (proxyUrl == null) {
            return Optional.empty();
        }
        String nonProxyHosts = firstNonBlank(environment.get("NO_PROXY"), environment.get("no_proxy"));
        return Optional.of(parseProxyUrl(proxyUrl, nonProxyHosts, SOURCE_ENV));
    }

    private Optional<ProxyConfiguration> loadFromPyronautSettings() {
        if (!Files.isRegularFile(pyronautSettingsPath)) {
            return Optional.empty();
        }
        String contents;
        try {
            contents = Files.readString(pyronautSettingsPath);
        } catch (IOException e) {
            throw new PyprojectModelException("Failed reading proxy settings from ~/.pyronaut/settings.toml", e);
        }
        JsonNode parseResult;
        try {
            parseResult = Parser.parse(contents);
        } catch (TomlStreamReadException e) {
            throw new PyprojectModelException("Invalid proxy settings in ~/.pyronaut/settings.toml: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new PyprojectModelException("Failed reading proxy settings from ~/.pyronaut/settings.toml", e);
        }
        if (!parseResult.isObject()) {
            throw new PyprojectModelException("Invalid proxy settings in ~/.pyronaut/settings.toml: expected a table");
        }
        JsonNode table = parseResult.get("proxy");
        if (table == null) {
            return Optional.empty();
        }
        if (!table.isObject()) {
            throw new PyprojectModelException("Invalid proxy settings in ~/.pyronaut/settings.toml: 'proxy' must be a table");
        }
        String url = trimToNull(stringValue(table, "url"));
        String nonProxyHosts = trimToNull(stringValue(table, "nonProxyHosts"));
        if (nonProxyHosts == null) {
            nonProxyHosts = trimToNull(stringValue(table, "noProxyHosts"));
        }

        ProxyConfiguration parsed = url == null
            ? null
            : parseProxyUrl(url, nonProxyHosts, SOURCE_PYRONAUT);

        String host = trimToNull(stringValue(table, "host"));
        if (host == null && parsed != null) {
            host = parsed.host();
        }
        if (host == null) {
            return Optional.empty();
        }

        String protocol = trimToNull(stringValue(table, "protocol"));
        if (protocol == null && parsed != null) {
            protocol = parsed.protocol();
        }
        if (protocol == null) {
            protocol = "http";
        }

        Integer port = toInteger(longValue(table, "port"));
        if (port == null && parsed != null) {
            port = parsed.port();
        }
        if (port == null) {
            port = defaultPort(protocol);
        }

        String username = trimToNull(stringValue(table, "username"));
        if (username == null && parsed != null) {
            username = parsed.username();
        }

        String password = trimToNull(stringValue(table, "password"));
        if (password == null && parsed != null) {
            password = parsed.password();
        }

        String effectiveNonProxyHosts = nonProxyHosts;
        if (effectiveNonProxyHosts == null && parsed != null) {
            effectiveNonProxyHosts = parsed.nonProxyHosts();
        }

        return Optional.of(new ProxyConfiguration(
            SOURCE_PYRONAUT,
            normalizeProtocol(protocol),
            host,
            port,
            username,
            password,
            normalizeNonProxyHosts(effectiveNonProxyHosts)
        ));
    }

    private Optional<ProxyConfiguration> loadFromMavenSettings() {
        if (!Files.isRegularFile(m2SettingsPath)) {
            return Optional.empty();
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(m2SettingsPath.toFile());
            NodeList proxies = document.getElementsByTagName("proxy");
            if (proxies.getLength() == 0) {
                return Optional.empty();
            }

            Element selected = selectProxyElement(proxies);
            if (selected == null) {
                return Optional.empty();
            }

            String host = textValue(selected, "host");
            if (host == null) {
                return Optional.empty();
            }

            String protocol = normalizeProtocol(textValue(selected, "protocol"));
            if (protocol == null) {
                protocol = "http";
            }
            int port = parsePort(textValue(selected, "port"), defaultPort(protocol));

            return Optional.of(new ProxyConfiguration(
                SOURCE_M2,
                protocol,
                host,
                port,
                textValue(selected, "username"),
                textValue(selected, "password"),
                normalizeNonProxyHosts(textValue(selected, "nonProxyHosts"))
            ));
        } catch (Exception e) {
            throw new PyprojectModelException("Failed reading proxy settings from ~/.m2/settings.xml", e);
        }
    }

    private static Element selectProxyElement(NodeList proxies) {
        // Maven treats a proxy without an <active> element as active and skips
        // proxies explicitly marked <active>false</active>.
        Element fallback = null;
        for (int i = 0; i < proxies.getLength(); i++) {
            Node node = proxies.item(i);
            if (!(node instanceof Element element)) {
                continue;
            }
            String active = textValue(element, "active");
            if (active == null) {
                if (fallback == null) {
                    fallback = element;
                }
            } else if (active.equalsIgnoreCase("true")) {
                return element;
            }
        }
        return fallback;
    }

    private static String textValue(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return null;
        }
        String value = nodes.item(0).getTextContent();
        return trimToNull(value);
    }

    private static ProxyConfiguration parseProxyUrl(String value, String nonProxyHosts, String source) {
        URI uri;
        try {
            uri = URI.create(value.contains("://") ? value : "http://" + value);
        } catch (IllegalArgumentException e) {
            throw new PyprojectModelException("Invalid proxy URL in " + source + ": " + value + " (" + e.getMessage() + ")", e);
        }
        String protocol = normalizeProtocol(uri.getScheme());
        if (protocol == null) {
            protocol = "http";
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new PyprojectModelException("Invalid proxy URL in " + source + ": " + value);
        }
        int port = uri.getPort() >= 0 ? uri.getPort() : defaultPort(protocol);

        String username = null;
        String password = null;
        String userInfo = trimToNull(uri.getUserInfo());
        if (userInfo != null) {
            String[] parts = userInfo.split(":", 2);
            username = trimToNull(parts[0]);
            if (parts.length == 2) {
                password = trimToNull(parts[1]);
            }
        }

        return new ProxyConfiguration(
            source,
            protocol,
            host,
            port,
            username,
            password,
            normalizeNonProxyHosts(nonProxyHosts)
        );
    }

    private static Integer toInteger(Long value) {
        if (value == null) {
            return null;
        }
        return value.intValue();
    }

    private static String stringValue(JsonNode table, String key) {
        JsonNode value = table.get(key);
        if (value == null) {
            return null;
        }
        if (!value.isString()) {
            throw new PyprojectModelException("Invalid proxy setting '" + key + "': expected string");
        }
        return value.getStringValue();
    }

    private static Long longValue(JsonNode table, String key) {
        JsonNode value = table.get(key);
        if (value == null) {
            return null;
        }
        Number number = value.isNumber() ? value.getNumberValue() : null;
        if (!(number instanceof Integer || number instanceof Long || number instanceof BigInteger)) {
            throw new PyprojectModelException("Invalid proxy setting '" + key + "': expected integer");
        }
        BigInteger integer = number instanceof BigInteger bigInteger
            ? bigInteger
            : BigInteger.valueOf(number.longValue());
        if (integer.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0
            || integer.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0) {
            throw new PyprojectModelException("Invalid proxy setting '" + key + "': integer out of range");
        }
        return integer.longValue();
    }

    private static int parsePort(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new PyprojectModelException("Invalid proxy port value: " + value, e);
        }
    }

    private static int defaultPort(String protocol) {
        return "https".equals(protocol) ? 443 : 80;
    }

    private static String normalizeProtocol(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.equals("http") || lower.equals("https")) {
            return lower;
        }
        throw new PyprojectModelException("Unsupported proxy protocol: " + value);
    }

    private static String normalizeNonProxyHosts(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        List<String> parts = java.util.Arrays.stream(trimmed.split("[,|]"))
            .map(String::trim)
            .filter(part -> !part.isEmpty())
            .map(ProxyConfigurationLoader::normalizeNonProxyHost)
            .filter(part -> !part.isEmpty())
            .distinct()
            .toList();
        if (parts.isEmpty()) {
            return null;
        }
        return String.join("|", parts);
    }

    /**
     * Rewrites a {@code NO_PROXY} style entry into the glob form understood by
     * Maven Resolver: a leading {@code .suffix} becomes {@code *.suffix} and a
     * trailing {@code :port} is dropped.
     */
    private static String normalizeNonProxyHost(String part) {
        String host = part;
        if (!host.startsWith("[")) {
            int colon = host.lastIndexOf(':');
            if (colon > 0 && colon == host.indexOf(':') && host.substring(colon + 1).chars().allMatch(Character::isDigit)) {
                host = host.substring(0, colon);
            }
        }
        if (host.startsWith(".")) {
            host = "*" + host;
        }
        return host;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    record ProxyConfiguration(String source,
                              String protocol,
                              String host,
                              int port,
                              String username,
                              String password,
                              String nonProxyHosts) {
        String summary() {
            return source + " -> " + protocol + "://" + host + ":" + port;
        }
    }
}

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
package io.micronaut.pyronaut.config.model;

import java.nio.file.Path;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Optional Micronaut Control Panel modules that Pyronaut adds to a
 * development Control Panel launch only when the application already
 * depends on the library the panel inspects.
 *
 * <p>The Python CLI keeps an equivalent table in
 * {@code pyronaut_cli_v2/cli.py} ({@code _CONTROL_PANEL_FEATURES}).</p>
 */
public enum ControlPanelFeature {
    DATASOURCE("micronaut-control-panel-datasource", List.of("io.micronaut.sql:micronaut-jdbc")),
    HIBERNATE("micronaut-control-panel-hibernate", List.of("org.hibernate.orm:hibernate-core")),
    KAFKA("micronaut-control-panel-kafka", List.of("io.micronaut.kafka:micronaut-kafka")),
    OBJECT_STORAGE(
        "micronaut-control-panel-object-storage",
        List.of("io.micronaut.objectstorage:micronaut-object-storage-core")
    ),
    CACHE("micronaut-control-panel-cache", List.of(
        "io.micronaut.cache:micronaut-cache-caffeine",
        "io.micronaut.cache:micronaut-cache-ehcache",
        "io.micronaut.cache:micronaut-cache-hazelcast",
        "io.micronaut.cache:micronaut-cache-infinispan"
    ));

    /**
     * Maven group of every Control Panel module.
     */
    public static final String GROUP = "io.micronaut.controlpanel";

    private final String artifactId;
    private final List<String> triggers;

    ControlPanelFeature(String artifactId, List<String> triggers) {
        this.artifactId = artifactId;
        this.triggers = triggers;
    }

    /**
     * @return the Control Panel module artifact id
     */
    public String artifactId() {
        return artifactId;
    }

    /**
     * @return the Control Panel module as {@code group:artifact}
     */
    public String moduleKey() {
        return GROUP + ":" + artifactId;
    }

    /**
     * @return the {@code group:artifact} keys whose presence enables this panel
     */
    public List<String> triggers() {
        return triggers;
    }

    /**
     * Detects the panels enabled by resolved application modules.
     *
     * @param moduleKeys resolved {@code group:artifact} keys
     * @return the matching panels
     */
    public static Set<ControlPanelFeature> detectModules(Collection<String> moduleKeys) {
        Set<ControlPanelFeature> detected = EnumSet.noneOf(ControlPanelFeature.class);
        for (ControlPanelFeature feature : values()) {
            if (feature.triggers.stream().anyMatch(moduleKeys::contains)) {
                detected.add(feature);
            }
        }
        return detected;
    }

    /**
     * Detects the panels enabled by an application classpath. Entries are
     * matched by their versioned JAR file name, which is stable across Maven
     * and Gradle repository layouts.
     *
     * @param classpath application classpath entries
     * @return the matching panels
     */
    public static Set<ControlPanelFeature> detectClasspath(Collection<Path> classpath) {
        Set<ControlPanelFeature> detected = EnumSet.noneOf(ControlPanelFeature.class);
        for (Path entry : classpath) {
            String artifactId = versionedJarArtifactId(entry);
            if (artifactId == null) {
                continue;
            }
            for (ControlPanelFeature feature : values()) {
                if (feature.triggers.stream().anyMatch(trigger -> trigger.endsWith(":" + artifactId))) {
                    detected.add(feature);
                }
            }
        }
        return detected;
    }

    /**
     * Returns the panel a Control Panel module JAR provides, if any.
     *
     * @param entry a classpath entry
     * @return the panel, or {@code null} when the entry is not an optional panel module
     */
    public static ControlPanelFeature forJar(Path entry) {
        String artifactId = versionedJarArtifactId(entry);
        if (artifactId == null) {
            return null;
        }
        for (ControlPanelFeature feature : values()) {
            if (feature.artifactId.equals(artifactId)) {
                return feature;
            }
        }
        return null;
    }

    /**
     * Selects the bundled Control Panel entries for a launch. Optional panel
     * modules are kept only when the application classpath contains the
     * library the panel inspects; every other entry is kept.
     *
     * @param bundled bundled Control Panel classpath entries
     * @param applicationClasspath application classpath entries
     * @return the entries to add to the launch classpath
     */
    public static List<Path> select(List<Path> bundled, Collection<Path> applicationClasspath) {
        Set<ControlPanelFeature> features = detectClasspath(applicationClasspath);
        return bundled.stream()
            .filter(entry -> {
                ControlPanelFeature feature = forJar(entry);
                return feature == null || features.contains(feature);
            })
            .toList();
    }

    private static String versionedJarArtifactId(Path entry) {
        Path fileName = entry.getFileName();
        if (fileName == null) {
            return null;
        }
        String name = fileName.toString();
        if (!name.endsWith(".jar")) {
            return null;
        }
        String baseName = name.substring(0, name.length() - ".jar".length());
        for (int i = 0; i < baseName.length() - 1; i++) {
            if (baseName.charAt(i) == '-' && Character.isDigit(baseName.charAt(i + 1))) {
                return baseName.substring(0, i);
            }
        }
        return null;
    }
}

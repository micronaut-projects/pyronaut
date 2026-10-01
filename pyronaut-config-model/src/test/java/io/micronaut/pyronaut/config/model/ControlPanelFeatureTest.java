/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.micronaut.pyronaut.config.model;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlPanelFeatureTest {
    @Test
    void detectsPanelsFromResolvedModules() {
        assertEquals(
            Set.of(ControlPanelFeature.DATASOURCE, ControlPanelFeature.KAFKA),
            ControlPanelFeature.detectModules(List.of(
                "io.micronaut.sql:micronaut-jdbc",
                "io.micronaut.kafka:micronaut-kafka",
                "io.micronaut:micronaut-http-server"
            ))
        );
        assertTrue(ControlPanelFeature.detectModules(List.of("io.micronaut:micronaut-http-server")).isEmpty());
    }

    @Test
    void detectsPanelsFromClasspathFileNames() {
        assertEquals(
            Set.of(ControlPanelFeature.DATASOURCE, ControlPanelFeature.HIBERNATE,
                ControlPanelFeature.OBJECT_STORAGE, ControlPanelFeature.CACHE),
            ControlPanelFeature.detectClasspath(List.of(
                Path.of("/m2/io/micronaut/sql/micronaut-jdbc/7.2.0/micronaut-jdbc-7.2.0.jar"),
                Path.of("/gradle/org.hibernate.orm/hibernate-core/7.1.0.Final/hash/hibernate-core-7.1.0.Final.jar"),
                Path.of("/m2/micronaut-object-storage-core-3.0.0.jar"),
                Path.of("/m2/micronaut-cache-caffeine-6.1.1.jar"),
                Path.of("/project/__pyronaut__/classes")
            ))
        );
        // A module whose name only starts with a trigger artifact id does not match.
        assertTrue(ControlPanelFeature.detectClasspath(List.of(Path.of("/m2/micronaut-jdbc-hikari-7.2.0.jar"))).isEmpty());
    }

    @Test
    void selectsOptionalPanelsOnlyForApplicationDependencies() {
        Path ui = Path.of("/tools/lib/control-panel/micronaut-control-panel-ui-2.1.0.jar");
        Path datasource = Path.of("/tools/lib/control-panel/micronaut-control-panel-datasource-2.1.0.jar");
        Path kafka = Path.of("/tools/lib/control-panel/micronaut-control-panel-kafka-2.1.0.jar");
        List<Path> bundled = List.of(ui, datasource, kafka);

        assertEquals(List.of(ui), ControlPanelFeature.select(bundled, List.of(
            Path.of("/m2/micronaut-http-server-5.0.0.jar")
        )));
        assertEquals(List.of(ui, datasource), ControlPanelFeature.select(bundled, List.of(
            Path.of("/m2/io/micronaut/sql/micronaut-jdbc/7.2.0/micronaut-jdbc-7.2.0.jar")
        )));
    }

    @Test
    void identifiesOptionalPanelJars() {
        assertEquals(ControlPanelFeature.KAFKA,
            ControlPanelFeature.forJar(Path.of("/lib/control-panel/micronaut-control-panel-kafka-2.1.0.jar")));
        assertNull(ControlPanelFeature.forJar(Path.of("/lib/control-panel/micronaut-control-panel-ui-2.1.0.jar")));
        assertEquals("io.micronaut.controlpanel:micronaut-control-panel-object-storage",
            ControlPanelFeature.OBJECT_STORAGE.moduleKey());
    }
}

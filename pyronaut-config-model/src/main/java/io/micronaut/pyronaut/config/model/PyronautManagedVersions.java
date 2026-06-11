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

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Packaged Micronaut dependency versions used when {@code pyproject.toml}
 * does not override them.
 */
public final class PyronautManagedVersions {
    private static final String RESOURCE = "io/micronaut/pyronaut/config/model/pyronaut-managed-versions.properties";
    private static final String MICRONAUT_CORE_VERSION = "micronaut.core.version";
    private static final String MICRONAUT_PLATFORM_VERSION = "micronaut.platform.version";
    private static final String MICRONAUT_CONTROL_PANEL_VERSION = "micronaut.control-panel.version";
    private static final Properties PROPERTIES = load();

    private PyronautManagedVersions() {
    }

    public static String micronautCoreVersion() {
        return property(MICRONAUT_CORE_VERSION);
    }

    public static String micronautPlatformVersion() {
        return property(MICRONAUT_PLATFORM_VERSION);
    }

    public static String micronautControlPanelVersion() {
        return property(MICRONAUT_CONTROL_PANEL_VERSION);
    }

    private static String property(String name) {
        String value = System.getProperty("pyronaut." + name);
        if (value == null || value.isBlank()) {
            value = PROPERTIES.getProperty(name);
        }
        return value == null || value.isBlank() ? null : value;
    }

    private static Properties load() {
        Properties properties = new Properties();
        ClassLoader classLoader = PyronautManagedVersions.class.getClassLoader();
        try (InputStream input = classLoader.getResourceAsStream(RESOURCE)) {
            if (input != null) {
                properties.load(input);
            }
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
        return properties;
    }
}

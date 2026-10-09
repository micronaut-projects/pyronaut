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
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Resolves the directories that Pyronaut keeps state in, consistently with the Python CLI.
 *
 * <p>The JVM takes {@code user.home} from the passwd entry, while the CLI uses
 * {@code $HOME}. When they differ (a relocated {@code HOME}, or a build sandbox
 * whose passwd home is not writable), using {@code user.home} would split
 * Pyronaut state between two locations. The Pyronaut home is therefore, in
 * order: the {@value #PROPERTY} system property, the {@value #ENV} environment
 * variable (which the CLI always exports to the tools it starts), and finally
 * {@code .pyronaut} under {@link #userHome()}.</p>
 */
public final class PyronautHome {
    public static final String PROPERTY = "pyronaut.home";
    public static final String ENV = "PYRONAUT_HOME";

    private PyronautHome() {
    }

    /**
     * @return the Pyronaut home directory, {@code ~/.pyronaut} by default
     */
    public static Path pyronautHome() {
        return pyronautHome(System::getProperty, System.getenv());
    }

    /**
     * @return the user's home directory: {@code $HOME} where set (except on
     * Windows, matching Python's {@code Path.home()}), otherwise {@code user.home}
     */
    public static Path userHome() {
        return userHome(System::getProperty, System.getenv());
    }

    static Path pyronautHome(UnaryOperator<String> properties, Map<String, String> environment) {
        String configured = nonBlank(properties.apply(PROPERTY));
        if (configured == null) {
            configured = nonBlank(environment.get(ENV));
        }
        if (configured != null) {
            return Path.of(expandTilde(configured, properties, environment)).toAbsolutePath().normalize();
        }
        return userHome(properties, environment).resolve(".pyronaut");
    }

    static Path userHome(UnaryOperator<String> properties, Map<String, String> environment) {
        String osName = properties.apply("os.name");
        boolean windows = osName != null && osName.toLowerCase(Locale.ROOT).startsWith("windows");
        String home = windows ? null : nonBlank(environment.get("HOME"));
        if (home == null) {
            home = properties.apply("user.home");
        }
        return Path.of(home).toAbsolutePath().normalize();
    }

    private static String expandTilde(String value, UnaryOperator<String> properties, Map<String, String> environment) {
        if (value.equals("~")) {
            return userHome(properties, environment).toString();
        }
        if (value.startsWith("~/") || value.startsWith("~\\")) {
            return userHome(properties, environment).resolve(value.substring(2)).toString();
        }
        return value;
    }

    private static String nonBlank(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}

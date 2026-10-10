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

import java.nio.file.Files;
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
 *
 * <p>State is split into configuration ({@link #configHome()}), caches
 * ({@link #cacheHome()}) and data ({@link #dataHome()}). By default all three
 * are the Pyronaut home. An explicit Pyronaut home always keeps them together;
 * otherwise each one is, in order: the directory the CLI exported for it
 * ({@value #CONFIG_DIR_ENV}, {@value #CACHE_DIR_ENV}, {@value #DATA_DIR_ENV}),
 * the XDG base directory layout when it is enabled (see {@link #xdgEnabled()}),
 * and finally {@code ~/.pyronaut}.</p>
 */
public final class PyronautHome {
    public static final String PROPERTY = "pyronaut.home";
    public static final String ENV = "PYRONAUT_HOME";
    public static final String CONFIG_DIR_ENV = "PYRONAUT_CONFIG_DIR";
    public static final String CACHE_DIR_ENV = "PYRONAUT_CACHE_DIR";
    public static final String DATA_DIR_ENV = "PYRONAUT_DATA_DIR";
    public static final String XDG_ENV = "PYRONAUT_XDG";
    public static final String XDG_DIR_NAME = "pyronaut";

    private static final String DOT_PYRONAUT = ".pyronaut";
    private static final String XDG_CONFIG_HOME = "XDG_CONFIG_HOME";
    private static final String XDG_CACHE_HOME = "XDG_CACHE_HOME";
    private static final String XDG_DATA_HOME = "XDG_DATA_HOME";

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

    /**
     * @return the directory holding user configuration such as {@code settings.toml}
     */
    public static Path configHome() {
        return configHome(System::getProperty, System.getenv());
    }

    /**
     * @return the directory holding re-creatable caches such as IDE stubs and AOT caches
     */
    public static Path cacheHome() {
        return cacheHome(System::getProperty, System.getenv());
    }

    /**
     * @return the directory holding provisioned SDKs, launchers and tool runtimes
     */
    public static Path dataHome() {
        return dataHome(System::getProperty, System.getenv());
    }

    /**
     * Whether the XDG base directory layout is in use. It never is when a
     * Pyronaut home is configured explicitly. Otherwise {@value #XDG_ENV}
     * ({@code true} or {@code false}) decides when set. Without it, an
     * existing {@code $XDG_CONFIG_HOME/pyronaut} (which {@code pyronaut setup}
     * creates) enables the layout, an existing {@code ~/.pyronaut} keeps that
     * installation where it is, and a fresh installation uses the layout on
     * Linux or when an {@code XDG_*_HOME} base directory is set.
     *
     * @return true if configuration, caches and data follow the XDG base directories
     */
    public static boolean xdgEnabled() {
        return xdgEnabled(System::getProperty, System.getenv());
    }

    static Path pyronautHome(UnaryOperator<String> properties, Map<String, String> environment) {
        Path explicit = explicitHome(properties, environment);
        return explicit != null ? explicit : userHome(properties, environment).resolve(DOT_PYRONAUT);
    }

    static Path configHome(UnaryOperator<String> properties, Map<String, String> environment) {
        return kindHome(properties, environment, CONFIG_DIR_ENV, XDG_CONFIG_HOME, ".config");
    }

    static Path cacheHome(UnaryOperator<String> properties, Map<String, String> environment) {
        return kindHome(properties, environment, CACHE_DIR_ENV, XDG_CACHE_HOME, ".cache");
    }

    static Path dataHome(UnaryOperator<String> properties, Map<String, String> environment) {
        return kindHome(properties, environment, DATA_DIR_ENV, XDG_DATA_HOME, ".local", "share");
    }

    static boolean xdgEnabled(UnaryOperator<String> properties, Map<String, String> environment) {
        if (explicitHome(properties, environment) != null) {
            return false;
        }
        String flag = nonBlank(environment.get(XDG_ENV));
        if (flag != null) {
            String normalized = flag.toLowerCase(Locale.ROOT);
            if (normalized.equals("1") || normalized.equals("true") || normalized.equals("yes") || normalized.equals("on")) {
                return true;
            }
            if (normalized.equals("0") || normalized.equals("false") || normalized.equals("no") || normalized.equals("off")) {
                return false;
            }
        }
        if (Files.isDirectory(xdgBase(properties, environment, XDG_CONFIG_HOME, ".config").resolve(XDG_DIR_NAME))) {
            return true;
        }
        if (Files.isDirectory(userHome(properties, environment).resolve(DOT_PYRONAUT))) {
            return false;
        }
        for (String variable : new String[] {XDG_CONFIG_HOME, XDG_CACHE_HOME, XDG_DATA_HOME}) {
            String configured = nonBlank(environment.get(variable));
            if (configured != null && Path.of(configured).isAbsolute()) {
                return true;
            }
        }
        String osName = properties.apply("os.name");
        return osName != null && osName.toLowerCase(Locale.ROOT).startsWith("linux");
    }

    private static Path kindHome(UnaryOperator<String> properties,
                                 Map<String, String> environment,
                                 String exportedEnv,
                                 String xdgEnv,
                                 String... xdgDefault) {
        Path explicit = explicitHome(properties, environment);
        if (explicit != null) {
            return explicit;
        }
        String exported = nonBlank(environment.get(exportedEnv));
        if (exported != null) {
            return Path.of(expandTilde(exported, properties, environment)).toAbsolutePath().normalize();
        }
        if (xdgEnabled(properties, environment)) {
            return xdgBase(properties, environment, xdgEnv, xdgDefault).resolve(XDG_DIR_NAME);
        }
        return userHome(properties, environment).resolve(DOT_PYRONAUT);
    }

    private static Path explicitHome(UnaryOperator<String> properties, Map<String, String> environment) {
        String configured = nonBlank(properties.apply(PROPERTY));
        if (configured == null) {
            configured = nonBlank(environment.get(ENV));
        }
        return configured == null
            ? null
            : Path.of(expandTilde(configured, properties, environment)).toAbsolutePath().normalize();
    }

    private static Path xdgBase(UnaryOperator<String> properties,
                                Map<String, String> environment,
                                String xdgEnv,
                                String... xdgDefault) {
        // The XDG specification ignores relative values.
        String configured = nonBlank(environment.get(xdgEnv));
        if (configured != null && Path.of(configured).isAbsolute()) {
            return Path.of(configured).normalize();
        }
        Path base = userHome(properties, environment);
        for (String segment : xdgDefault) {
            base = base.resolve(segment);
        }
        return base;
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

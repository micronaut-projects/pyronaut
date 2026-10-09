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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PyronautHomeTest {

    private static final Path PASSWD_HOME = Path.of("/homeless-shelter").toAbsolutePath();
    private static final Path ENV_HOME = Path.of("/tmp/fresh-home").toAbsolutePath();

    @Test
    void prefersHomeEnvironmentOverUserHome() {
        assertEquals(ENV_HOME, PyronautHome.userHome(properties("Linux", Map.of()), Map.of("HOME", ENV_HOME.toString())));
        assertEquals(ENV_HOME.resolve(".pyronaut"),
            PyronautHome.pyronautHome(properties("Linux", Map.of()), Map.of("HOME", ENV_HOME.toString())));
    }

    @Test
    void fallsBackToUserHomeWithoutHome() {
        assertEquals(PASSWD_HOME.resolve(".pyronaut"), PyronautHome.pyronautHome(properties("Linux", Map.of()), Map.of()));
        assertEquals(PASSWD_HOME, PyronautHome.userHome(properties("Linux", Map.of()), Map.of("HOME", "  ")));
    }

    @Test
    void ignoresHomeOnWindows() {
        assertEquals(PASSWD_HOME,
            PyronautHome.userHome(properties("Windows 11", Map.of()), Map.of("HOME", ENV_HOME.toString())));
    }

    @Test
    void pyronautHomeEnvironmentRelocatesState() {
        Path configured = Path.of("/opt/pyronaut-state").toAbsolutePath();
        assertEquals(configured, PyronautHome.pyronautHome(
            properties("Linux", Map.of()),
            Map.of("HOME", ENV_HOME.toString(), PyronautHome.ENV, configured.toString())
        ));
    }

    @Test
    void pyronautHomePropertyWinsOverEnvironment() {
        Path property = Path.of("/opt/from-property").toAbsolutePath();
        assertEquals(property, PyronautHome.pyronautHome(
            properties("Linux", Map.of(PyronautHome.PROPERTY, property.toString())),
            Map.of(PyronautHome.ENV, "/opt/from-env")
        ));
    }

    @Test
    void expandsTildeAgainstHome() {
        assertEquals(ENV_HOME.resolve("state"), PyronautHome.pyronautHome(
            properties("Linux", Map.of()),
            Map.of("HOME", ENV_HOME.toString(), PyronautHome.ENV, "~/state")
        ));
    }

    @Test
    void defaultLayoutOutsideLinuxKeepsEverythingUnderDotPyronaut(@TempDir Path home) {
        Map<String, String> env = Map.of("HOME", home.toString());
        UnaryOperator<String> props = properties("Mac OS X", Map.of());
        assertFalse(PyronautHome.xdgEnabled(props, env));
        assertEquals(home.resolve(".pyronaut"), PyronautHome.configHome(props, env));
        assertEquals(home.resolve(".pyronaut"), PyronautHome.cacheHome(props, env));
        assertEquals(home.resolve(".pyronaut"), PyronautHome.dataHome(props, env));
    }

    @Test
    void freshLinuxInstallationDefaultsToXdg(@TempDir Path home) {
        Map<String, String> env = Map.of("HOME", home.toString());
        UnaryOperator<String> props = properties("Linux", Map.of());
        assertTrue(PyronautHome.xdgEnabled(props, env));
        assertEquals(home.resolve(".config/pyronaut"), PyronautHome.configHome(props, env));
        assertEquals(home.resolve(".cache/pyronaut"), PyronautHome.cacheHome(props, env));
        assertEquals(home.resolve(".local/share/pyronaut"), PyronautHome.dataHome(props, env));
    }

    @Test
    void xdgBaseVariableEnablesXdgByDefaultOnAnyPlatform(@TempDir Path home) {
        UnaryOperator<String> props = properties("Mac OS X", Map.of());
        assertTrue(PyronautHome.xdgEnabled(props,
            Map.of("HOME", home.toString(), "XDG_DATA_HOME", home.resolve("data").toString())));
        assertFalse(PyronautHome.xdgEnabled(props, Map.of("HOME", home.toString(), "XDG_DATA_HOME", "relative")));
    }

    @Test
    void existingDotPyronautKeepsItsLayoutOnLinux(@TempDir Path home) throws IOException {
        Files.createDirectories(home.resolve(".pyronaut"));
        UnaryOperator<String> props = properties("Linux", Map.of());
        Map<String, String> env = Map.of("HOME", home.toString(), "XDG_CONFIG_HOME", home.resolve("cfg").toString());
        assertFalse(PyronautHome.xdgEnabled(props, env));
        assertEquals(home.resolve(".pyronaut"), PyronautHome.dataHome(props, env));
        assertTrue(PyronautHome.xdgEnabled(props, Map.of("HOME", home.toString(), PyronautHome.XDG_ENV, "true")));
    }

    @Test
    void xdgConfigFolderEnablesTheXdgLayout(@TempDir Path home) throws IOException {
        Files.createDirectories(home.resolve(".config").resolve("pyronaut"));
        Map<String, String> env = Map.of("HOME", home.toString());
        UnaryOperator<String> props = properties("Linux", Map.of());
        assertTrue(PyronautHome.xdgEnabled(props, env));
        assertEquals(home.resolve(".config/pyronaut"), PyronautHome.configHome(props, env));
        assertEquals(home.resolve(".cache/pyronaut"), PyronautHome.cacheHome(props, env));
        assertEquals(home.resolve(".local/share/pyronaut"), PyronautHome.dataHome(props, env));
    }

    @Test
    void xdgBaseDirectoriesAreHonoured(@TempDir Path root) throws IOException {
        Path config = Files.createDirectories(root.resolve("cfg").resolve("pyronaut")).getParent();
        Map<String, String> env = Map.of(
            "HOME", root.resolve("home").toString(),
            "XDG_CONFIG_HOME", config.toString(),
            "XDG_CACHE_HOME", root.resolve("cache").toString(),
            "XDG_DATA_HOME", "relative/data"
        );
        UnaryOperator<String> props = properties("Linux", Map.of());
        assertEquals(config.resolve("pyronaut"), PyronautHome.configHome(props, env));
        assertEquals(root.resolve("cache/pyronaut"), PyronautHome.cacheHome(props, env));
        // Relative XDG values are ignored, as the specification requires.
        assertEquals(root.resolve("home/.local/share/pyronaut"), PyronautHome.dataHome(props, env));
    }

    @Test
    void xdgEnvironmentFlagOverridesTheMarker(@TempDir Path home) throws IOException {
        UnaryOperator<String> props = properties("Linux", Map.of());
        assertTrue(PyronautHome.xdgEnabled(props, Map.of("HOME", home.toString(), PyronautHome.XDG_ENV, "true")));
        Files.createDirectories(home.resolve(".config").resolve("pyronaut"));
        Map<String, String> disabled = Map.of("HOME", home.toString(), PyronautHome.XDG_ENV, "false");
        assertFalse(PyronautHome.xdgEnabled(props, disabled));
        assertEquals(home.resolve(".pyronaut"), PyronautHome.configHome(props, disabled));
    }

    @Test
    void explicitPyronautHomeOverridesXdgAndExportedDirectories(@TempDir Path home) throws IOException {
        Files.createDirectories(home.resolve(".config").resolve("pyronaut"));
        Path state = home.resolve("state");
        Map<String, String> env = Map.of(
            "HOME", home.toString(),
            PyronautHome.ENV, state.toString(),
            PyronautHome.CACHE_DIR_ENV, home.resolve("exported-cache").toString()
        );
        UnaryOperator<String> props = properties("Linux", Map.of());
        assertFalse(PyronautHome.xdgEnabled(props, env));
        assertEquals(state, PyronautHome.configHome(props, env));
        assertEquals(state, PyronautHome.cacheHome(props, env));
        assertEquals(state, PyronautHome.dataHome(props, env));
    }

    @Test
    void exportedDirectoriesWinOverTheDetectedLayout(@TempDir Path home) {
        Map<String, String> env = Map.of(
            "HOME", home.toString(),
            PyronautHome.CONFIG_DIR_ENV, home.resolve("c").toString(),
            PyronautHome.CACHE_DIR_ENV, home.resolve("k").toString(),
            PyronautHome.DATA_DIR_ENV, home.resolve("d").toString()
        );
        UnaryOperator<String> props = properties("Linux", Map.of());
        assertEquals(home.resolve("c"), PyronautHome.configHome(props, env));
        assertEquals(home.resolve("k"), PyronautHome.cacheHome(props, env));
        assertEquals(home.resolve("d"), PyronautHome.dataHome(props, env));
    }

    private static UnaryOperator<String> properties(String osName, Map<String, String> extra) {
        Map<String, String> values = new HashMap<>(extra);
        values.put("os.name", osName);
        values.put("user.home", PASSWD_HOME.toString());
        return values::get;
    }
}

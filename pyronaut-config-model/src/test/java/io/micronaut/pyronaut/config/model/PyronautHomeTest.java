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

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private static UnaryOperator<String> properties(String osName, Map<String, String> extra) {
        Map<String, String> values = new HashMap<>(extra);
        values.put("os.name", osName);
        values.put("user.home", PASSWD_HOME.toString());
        return values::get;
    }
}

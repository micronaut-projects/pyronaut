/*
 * Copyright 2017-2024 original authors
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

import io.micronaut.pyronaut.config.model.PyprojectModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenClasspathResolverScopeTest {

    private static final String RUNTIME_OSX = "io.micronaut:micronaut-runtime-osx";

    private static PyprojectModel model() {
        return new PyprojectModel(null, null, new PyprojectModel.Pyronaut(
            null, null, List.of(),
            new PyprojectModel.Dependencies(List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), Map.of()),
            null, null, null, null, null, null, null, null, null, null, false));
    }

    private static List<String> coordinates(InstallScope scope) {
        return new MavenClasspathResolver().coordinatesForScope(
            model(), scope, Map.of(RUNTIME_OSX, "5.2.5"));
    }

    private static boolean onMacOs() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ENGLISH).contains("mac");
    }

    @Test
    void addsTheNativeWatchServiceToTheDevelopmentRuntimeOnMacOs() {
        List<String> developmentRuntime = coordinates(InstallScope.DEVELOPMENT_RUNTIME);
        if (onMacOs()) {
            assertTrue(developmentRuntime.contains(RUNTIME_OSX + ":5.2.5"),
                "macOS falls back to a polling watch service without this: " + developmentRuntime);
        } else {
            assertFalse(developmentRuntime.stream().anyMatch(c -> c.startsWith(RUNTIME_OSX)),
                "a macOS-specific module has no business on another operating system: " + developmentRuntime);
        }
    }

    private static final String MICRONAUT_DEV = "io.micronaut:micronaut-dev";

    private static List<String> projectCoordinates(PyprojectModel.Project project, InstallScope scope) {
        PyprojectModel base = model();
        return new MavenClasspathResolver().coordinatesForScope(
            new PyprojectModel(project, null, base.pyronaut()), scope,
            Map.of(MICRONAUT_DEV, "5.3.0", MICRONAUT_DEV + "-test-report", "5.3.0", MICRONAUT_DEV + "-livereload", "5.3.0"));
    }

    @Test
    void addsTheReloadingRuntimeToTheDevelopmentRuntimeOfAProjectOnly() {
        PyprojectModel.Project project = new PyprojectModel.Project("demo", "1.0.0", List.of());
        assertTrue(projectCoordinates(project, InstallScope.DEVELOPMENT_RUNTIME).contains(MICRONAUT_DEV + ":5.3.0"));
        // the live test report of test mode, and the LiveReload server that serves it
        assertTrue(projectCoordinates(project, InstallScope.DEVELOPMENT_RUNTIME).contains(MICRONAUT_DEV + "-test-report:5.3.0"));
        assertTrue(projectCoordinates(project, InstallScope.DEVELOPMENT_RUNTIME).contains(MICRONAUT_DEV + "-livereload:5.3.0"));
        assertFalse(projectCoordinates(project, InstallScope.RUNTIME).stream().anyMatch(c -> c.startsWith(MICRONAUT_DEV + ":")));
        assertFalse(projectCoordinates(project, InstallScope.TEST).stream().anyMatch(c -> c.startsWith(MICRONAUT_DEV + ":")));
        // the development support resolved for an external build has no [project]
        assertFalse(projectCoordinates(null, InstallScope.DEVELOPMENT_RUNTIME).stream().anyMatch(c -> c.startsWith(MICRONAUT_DEV + ":")));
    }

    @Test
    void neverAddsTheNativeWatchServiceToTheRuntime() {
        // Development runtime is seeded from runtime, so the addition has to sit strictly after that
        // seed: an artifact built on a Mac must not carry a macOS-specific module.
        assertFalse(coordinates(InstallScope.RUNTIME).stream().anyMatch(c -> c.startsWith(RUNTIME_OSX)));
        assertFalse(coordinates(InstallScope.TEST).stream().anyMatch(c -> c.startsWith(RUNTIME_OSX)));
    }
}

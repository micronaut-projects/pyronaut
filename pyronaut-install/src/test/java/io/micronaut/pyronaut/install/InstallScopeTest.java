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

import io.micronaut.pyronaut.config.model.ExternalProjectLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstallScopeTest {

    @TempDir
    Path tempDir;

    @Test
    void testResourcesServerDefinesStandaloneRuntimeDefaults() {
        assertEquals(
            List.of(
                "io.micronaut:micronaut-http-server",
                "io.micronaut.testresources:micronaut-test-resources-core",
                "io.micronaut.testresources:micronaut-test-resources-control-panel",
                "io.micronaut.testresources:micronaut-test-resources-server",
                "io.micronaut.serde:micronaut-serde-jackson",
                "ch.qos.logback:logback-classic",
                "org.slf4j:jul-to-slf4j",
                "io.micronaut:micronaut-http-server-netty"
            ),
            InstallScope.TEST_RESOURCES_SERVER.defaultDependencies()
        );
        for (InstallScope scope : InstallScope.values()) {
            if (scope != InstallScope.TEST_RESOURCES_SERVER) {
                assertTrue(scope.defaultDependencies().isEmpty());
            }
        }
    }

    @Test
    void enabledExternalCacheRequiresCompleteServerManifest() throws Exception {
        Path manifest = tempDir.resolve(InstallScope.TEST_RESOURCES_SERVER.manifestFile());

        assertFalse(PyronautInstallMain.externalCacheComplete(externalLayout(true), manifest));
        Files.writeString(manifest, "/resolved/server.jar\n");
        assertTrue(PyronautInstallMain.externalCacheComplete(externalLayout(true), manifest));
    }

    @Test
    void disabledExternalCacheRemovesStaleServerManifest() throws Exception {
        Path manifest = tempDir.resolve(InstallScope.TEST_RESOURCES_SERVER.manifestFile());
        Files.writeString(manifest, "/stale/server.jar\n");

        assertTrue(PyronautInstallMain.externalCacheComplete(externalLayout(false), manifest));
        assertTrue(Files.notExists(manifest));
    }

    private static ExternalProjectLayout externalLayout(boolean testResourcesEnabled) {
        return new ExternalProjectLayout(
            ExternalProjectLayout.ProjectKind.GRADLE,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            testResourcesEnabled,
            List.of()
        );
    }
}

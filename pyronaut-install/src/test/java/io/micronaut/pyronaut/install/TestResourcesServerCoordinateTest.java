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

import io.micronaut.pyronaut.config.model.PyprojectModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The test-resources-server scope infers which Test Resources modules the server needs from the
 * project's own dependencies. A POM-only aggregator has to be declared in the four-part form, which
 * the main resolver accepts -- so this scope has to accept it too, or one valid dependency anywhere
 * in runtime or test fails the whole scope.
 */
class TestResourcesServerCoordinateTest {

    @TempDir
    Path tempDir;

    /** The form a POM-only aggregator has to be declared in. org.graalvm.polyglot:js is one. */
    private static final String POM_ONLY = "org.graalvm.polyglot:js:pom:25.4.4.1.1";

    private static final String WITH_CLASSIFIER = "com.example:thing:jar:linux-amd64:1.0.0";

    private static PyprojectModel modelWithRuntime(List<String> runtime, boolean inferClasspath) {
        PyprojectModel.TestResources testResources = new PyprojectModel.TestResources(
            true, Boolean.TRUE, "2.9.0", null, inferClasspath, List.of(), null, null, null, null,
            null, Map.of(), Map.of(), null, null, null, List.of());
        return new PyprojectModel(null, null, new PyprojectModel.Pyronaut(
            null, null, List.of(),
            new PyprojectModel.Dependencies(runtime, List.of(), List.of(), List.of(),
                List.of(), List.of(), Map.of()),
            null, null, null, null, null, null, null, null, null, testResources, false));
    }

    private static PyprojectModel modelWithAdditionalModules(List<String> additionalModules) {
        PyprojectModel.TestResources testResources = new PyprojectModel.TestResources(
            true, Boolean.TRUE, "2.9.0", null, false, additionalModules, null, null, null, null,
            null, Map.of(), Map.of(), null, null, null, List.of());
        return new PyprojectModel(null, null, new PyprojectModel.Pyronaut(
            null, null, List.of(),
            new PyprojectModel.Dependencies(List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), Map.of()),
            null, null, null, null, null, null, null, null, null, testResources, false));
    }

    private static List<String> additionalModuleCoordinates(List<String> additionalModules,
                                                            Map<String, String> managedVersions) {
        return new MavenClasspathResolver().coordinatesForScope(
            modelWithAdditionalModules(additionalModules), InstallScope.TEST_RESOURCES_SERVER, managedVersions);
    }

    private static List<String> serverCoordinates(List<String> runtime) {
        return serverCoordinates(runtime, true);
    }

    private static List<String> serverCoordinates(List<String> runtime, boolean inferClasspath) {
        return new MavenClasspathResolver().coordinatesForScope(
            modelWithRuntime(runtime, inferClasspath), InstallScope.TEST_RESOURCES_SERVER,
            Map.of("io.micronaut.testresources:micronaut-test-resources-server", "2.9.0"));
    }

    @Test
    void resolvesTheServerScopeWithAPomOnlyDependencyOnTheRuntimeClasspath() {
        List<String> coordinates = assertDoesNotThrow(
            () -> serverCoordinates(List.of("io.micronaut.sql:micronaut-jdbc-hikari", POM_ONLY)),
            "a POM-only dependency the main resolver accepts must not fail this scope");
        // The aggregator itself has no business on the server classpath; it is only read to work out
        // which Test Resources modules to add.
        assertFalse(coordinates.stream().anyMatch(c -> c.startsWith("org.graalvm.polyglot:js")),
            "the aggregator is for inference only: " + coordinates);
    }

    @Test
    void resolvesTheServerScopeWithAClassifiedDependency() {
        assertDoesNotThrow(() -> serverCoordinates(List.of(WITH_CLASSIFIER)),
            "the five-part form is equally valid and must not fail this scope");
    }

    @Test
    void honorsDisabledClasspathInferenceForApplicationDependencies() {
        List<String> runtime = List.of("io.micronaut.discovery:micronaut-discovery-client");
        String vaultModule = "io.micronaut.testresources:micronaut-test-resources-hashicorp-vault";

        assertFalse(serverCoordinates(runtime, false).stream().anyMatch(c -> c.startsWith(vaultModule)),
            "disabled inference must not add the Vault provider: " + serverCoordinates(runtime, false));
        assertTrue(serverCoordinates(runtime, true).stream().anyMatch(c -> c.startsWith(vaultModule)),
            "enabled inference should preserve the Test Resources mapping");
    }

    @Test
    void resolvesAnEmptyServerScopeWithoutPyronautConfiguration() {
        MavenClasspathResolver resolver = new MavenClasspathResolver();
        List<Path> classpath = resolver.resolveScopeDetails(
            new PyprojectModel(null, null, null),
            InstallScope.TEST_RESOURCES_SERVER,
            tempDir.resolve("repository"),
            true
        ).classpath();

        assertTrue(classpath.isEmpty());
    }

    private static final String OLLAMA_TEST_RESOURCE =
        "io.micronaut.langchain4j:micronaut-langchain4j-ollama-testresource";

    @Test
    void shortAdditionalModuleNameUsesTheTestResourcesVersion() {
        List<String> coordinates = additionalModuleCoordinates(List.of("jdbc-mysql"), Map.of());

        assertTrue(coordinates.contains("io.micronaut.testresources:micronaut-test-resources-jdbc-mysql:2.9.0"),
            "a short module name maps to the Test Resources group and version: " + coordinates);
    }

    @Test
    void versionlessTestResourcesCoordinateUsesTheTestResourcesVersion() {
        List<String> coordinates = additionalModuleCoordinates(
            List.of("io.micronaut.testresources:micronaut-test-resources-kafka"),
            Map.of("io.micronaut.testresources:micronaut-test-resources-kafka", "9.9.9"));

        assertTrue(coordinates.contains("io.micronaut.testresources:micronaut-test-resources-kafka:2.9.0"),
            "a Test Resources coordinate follows the Test Resources version: " + coordinates);
    }

    @Test
    void versionlessThirdPartyCoordinateUsesTheManagedVersion() {
        List<String> coordinates = additionalModuleCoordinates(
            List.of(OLLAMA_TEST_RESOURCE),
            Map.of(OLLAMA_TEST_RESOURCE, "2.3.0"));

        assertTrue(coordinates.contains(OLLAMA_TEST_RESOURCE + ":2.3.0"),
            "a module outside the Test Resources group takes the BOM-managed version: " + coordinates);
        assertFalse(coordinates.contains(OLLAMA_TEST_RESOURCE + ":2.9.0"),
            "the Test Resources version must not be applied to another group: " + coordinates);
    }

    @Test
    void versionlessThirdPartyCoordinateWithoutManagedVersionIsLeftForManagedResolution() {
        List<String> coordinates = additionalModuleCoordinates(List.of(OLLAMA_TEST_RESOURCE), Map.of());

        assertTrue(coordinates.contains(OLLAMA_TEST_RESOURCE),
            "an unmanaged module is left versionless so resolution reports the missing version: " + coordinates);
    }

    @Test
    void explicitAdditionalModuleVersionIsPreserved() {
        List<String> coordinates = additionalModuleCoordinates(
            List.of(OLLAMA_TEST_RESOURCE + ":1.0.0", "io.micronaut.testresources:micronaut-test-resources-kafka:2.8.0"),
            Map.of(OLLAMA_TEST_RESOURCE, "2.3.0"));

        assertTrue(coordinates.contains(OLLAMA_TEST_RESOURCE + ":1.0.0"), coordinates.toString());
        assertTrue(coordinates.contains("io.micronaut.testresources:micronaut-test-resources-kafka:2.8.0"),
            coordinates.toString());
        assertEquals(1, coordinates.stream().filter(c -> c.startsWith(OLLAMA_TEST_RESOURCE)).count(),
            coordinates.toString());
    }

    @Test
    void additionalModulesStillHonorTheServerClasspathFilter() {
        List<String> coordinates = additionalModuleCoordinates(
            List.of("io.micronaut.testresources:micronaut-test-resources-client"), Map.of());

        assertFalse(coordinates.stream().anyMatch(c -> c.startsWith("io.micronaut.testresources:micronaut-test-resources-client")),
            "modules forbidden on the server classpath are still filtered: " + coordinates);
    }
}

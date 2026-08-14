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
package io.micronaut.test.pytest.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PytestDiscoverySelectorResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void recognizesModuleLevelJUnitAnnotation() throws IOException {
        Path source = Files.writeString(tempDir.resolve("junit_module.py"), """
            from micronaut.test.extensions.junit5.annotation import MicronautTest

            MicronautTest()

            def test_module():
                pass
            """);

        assertTrue(PytestDiscoverySelectorResolver.isJUnitModule(source));
    }

    @Test
    void recognizesAliasedModuleLevelJUnitAnnotation() throws IOException {
        Path source = Files.writeString(tempDir.resolve("aliased_junit_module.py"), """
            from micronaut.test.extensions.junit5.annotation import MicronautTest as TestApp

            TestApp()
            """);

        assertTrue(PytestDiscoverySelectorResolver.isJUnitModule(source));
    }

    @Test
    void doesNotTreatPytestFixtureConfigurationAsJUnitModule() throws IOException {
        Path source = Files.writeString(tempDir.resolve("pytest_module.py"), """
            import pytest
            from pyronaut.test import MicronautTest, micronaut_test_fixture

            @pytest.fixture
            def context(request):
                return micronaut_test_fixture(request, MicronautTest())
            """);

        assertFalse(PytestDiscoverySelectorResolver.isJUnitModule(source));
    }
}

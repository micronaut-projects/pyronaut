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
package io.micronaut.test.pytest;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.testkit.engine.EngineTestKit;

import java.nio.file.Paths;
import java.util.Set;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathRoots;

/**
 * Integration test for the PytestTestEngine.
 */
class PytestEngineTest {

    @Test
    void engineCanDiscoverPythonTests() {
        EngineTestKit
            .engine(PytestTestEngine.ENGINE_ID)
            .selectors(DiscoverySelectors.selectDirectory("src/test/python"))
            .execute()
            .testEvents()
            .debug();
    }

    @Test
    void engineCanDiscoverPythonTestsSystemProperty() {
        EngineTestKit
            .engine(PytestTestEngine.ENGINE_ID)
            .configurationParameter(PytestTestEngine.TEST_SOURCE_DIR, "src/test/python")
            .execute()
            .testEvents()
            .debug();
    }

    @Test
    void engineHonorsTestsSelectorsForWildcardAndNodeIdPassthrough() {
        EngineTestKit
            .engine(PytestTestEngine.ENGINE_ID)
            .selectors(DiscoverySelectors.selectDirectory("src/test/python"))
            .configurationParameter(PytestTestEngine.TESTS, "*test_simple_pass*|src/test/python/test_simple.py::test_simple_pass")
            .execute()
            .testEvents();
    }
}

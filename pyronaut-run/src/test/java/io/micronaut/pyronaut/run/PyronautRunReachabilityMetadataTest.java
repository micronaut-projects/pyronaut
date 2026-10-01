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
package io.micronaut.pyronaut.run;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PyronautRunReachabilityMetadataTest {

    @Test
    void registersReferenceCountedRetainForPythonReactiveCallbacks() throws Exception {
        try (InputStream resource = getClass().getResourceAsStream(
            "/META-INF/native-image/io.micronaut/micronaut-pyronaut-run/reachability-metadata.json")) {
            assertNotNull(resource);
            String metadata = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(metadata.contains("\"type\": \"io.micronaut.core.io.buffer.ReferenceCounted\""));
            assertTrue(metadata.contains("\"name\": \"retain\""));
            assertTrue(metadata.contains("\"parameterTypes\": []"));
        }
    }
}

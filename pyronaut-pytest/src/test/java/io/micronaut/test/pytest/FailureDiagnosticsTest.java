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
package io.micronaut.test.pytest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailureDiagnosticsTest {

    @Test
    void rendersCauseChainAndFiltersGraalInternals() {
        IllegalStateException root = new IllegalStateException("root cause detail");
        root.setStackTrace(new StackTraceElement[] {
            new StackTraceElement("com.oracle.truffle.SomeFrame", "invoke", "SomeFrame.java", 10),
            new StackTraceElement("example.micronaut.Fixture", "start", "Fixture.java", 42)
        });
        RuntimeException wrapper = new RuntimeException("fixture failed", root);

        String diagnostic = FailureDiagnostics.render(wrapper);

        assertTrue(diagnostic.contains("java.lang.RuntimeException: fixture failed"));
        assertTrue(diagnostic.contains("Caused by: java.lang.IllegalStateException: root cause detail"));
        assertTrue(diagnostic.contains("example.micronaut.Fixture.start(Fixture.java:42)"));
        assertFalse(diagnostic.contains("com.oracle.truffle.SomeFrame"));
    }
}

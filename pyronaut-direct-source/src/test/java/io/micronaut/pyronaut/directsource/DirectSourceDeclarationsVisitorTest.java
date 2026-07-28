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
package io.micronaut.pyronaut.directsource;

import io.micronaut.python.processing.PythonCall;
import io.micronaut.python.processing.PythonSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class DirectSourceDeclarationsVisitorTest {
    @Test
    void collectsDependenciesRepositoriesScopesAndProperties() {
        DirectSourceDeclarationsVisitor visitor = new DirectSourceDeclarationsVisitor();
        visitor.visit(new PythonSource("app.py", "python", List.of(
            new PythonCall("Dependency", List.of(), Map.of(
                "group", "com.example",
                "module", "runtime",
                "version", "1.0"
            )),
            new PythonCall("Dependency", List.of(), Map.of(
                "group", "com.example",
                "module", "processor",
                "scope", "Scope.BUILD"
            )),
            new PythonCall("MavenRepository", List.of("https://repo.example.test"), Map.of()),
            new PythonCall("AppConfig", List.of(), Map.of("name", "example.value", "value", "ok"))
        )), null);

        DirectSourceDeclarationRequest request =
            assertThrows(DirectSourceDeclarationRequest.class, () -> visitor.finish(null));

        assertEquals(
            List.of(
                new DirectSourceDeclarations.Dependency("com.example:runtime:1.0", false),
                new DirectSourceDeclarations.Dependency("com.example:processor", true)
            ),
            request.declarations().dependencies()
        );
        assertEquals(List.of("https://repo.example.test"), request.declarations().repositories());
        assertEquals(Map.of("example.value", "ok"), request.declarations().runtimeProperties());
    }
}

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
package io.micronaut.pyronaut.requests;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestsResponseTest {

    @Test
    void jsonParsesArrayResponseBodies() {
        Path pythonSource = Path.of(
            "src/main/resources/META-INF/GRAALPY-VFS/micronaut-application/src"
        ).toAbsolutePath();
        String pythonPath = pythonSource.toString().replace("\\", "\\\\").replace("'", "\\'");
        try (Context context = Context.newBuilder("python")
            .allowAllAccess(true)
            .build()) {
            Value result = context.eval("python", """
                import sys
                import java

                sys.path.insert(0, '%s')

                HttpResponse = java.type("io.micronaut.http.HttpResponse")
                MediaType = java.type("io.micronaut.http.MediaType")

                from pyronaut.requests import Response

                response = Response(
                    "http://localhost/genres/list",
                    HttpResponse.ok().contentType(MediaType.APPLICATION_JSON_TYPE),
                    body=b'[{"id":1,"name":"DevOps"},{"id":2,"name":"Micro-services"}]',
                )
                value = response.json()
                len(value) == 2 and value[0]["name"] == "DevOps" and value[1]["name"] == "Micro-services"
                """.formatted(pythonPath));

            assertTrue(result.asBoolean());
        }
    }
}

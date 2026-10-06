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

import io.micronaut.context.python.GraalPyContextFactory;
import io.micronaut.context.python.PythonContextRuntime;
import io.micronaut.http.HttpResponse;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestsNamespaceGraalPyTest {
    private Context context;

    @BeforeEach
    void setup() throws Exception {
        context = GraalPyContextFactory.bootstrapReusableContext(getClass().getClassLoader());
        context.eval("python", "import sys; original_path = tuple(sys.path)");
        context.eval("python", Files.readString(Path.of("src/test/python/test_requests_namespace.py")));
    }

    @AfterEach
    void cleanup() {
        if (context != null) {
            PythonContextRuntime.setReuseContext(false);
            PythonContextRuntime.resetContext();
            context.close();
        }
    }

    @Test
    void explicitPyronautRequestsRemainsAvailable() {
        assertDoesNotThrow(() -> context.eval("python", "test_explicit_pyronaut_requests_remains_available()"));
    }

    @Test
    void legacyRequestsWithContextRemainsAvailable() {
        assertDoesNotThrow(() -> context.eval("python", "test_legacy_requests_with_context_remains_available()"));
    }

    @Test
    void installedRequestsHttpAdapterIsAvailable() {
        assertDoesNotThrow(() -> context.eval("python", "test_installed_requests_http_adapter_is_available()"));
    }

    @Test
    void installedRequestsSessionKeepsTransportAndHeaders() {
        assertDoesNotThrow(() -> context.eval("python", "test_installed_requests_session_keeps_transport_and_headers()"));
    }

    @Test
    void installedRequestsSubmodulesPreserveExceptionIdentity() {
        assertDoesNotThrow(() -> context.eval("python", "test_installed_requests_submodules_preserve_exception_identity()"));
    }

    @Test
    void preservesFrameworkExtensionAndExistingPathPrecedence() {
        assertDoesNotThrow(() -> context.eval("python", """
            assert tuple(sys.path[:len(original_path)]) == original_path
            assert requests.with_context is pyronaut_requests.with_context
            """));
    }

    @Test
    void micronautResponseErrorKeepsLegacyPublicExceptionIdentity() {
        context.getBindings("python").putMember("missing_response", HttpResponse.notFound());
        context.eval("python", """
            response = pyronaut_requests.Response("http://localhost/does-not-exist", missing_response, body=b"")
            caught_error = None
            caught_public_error = False
            try:
                response.raise_for_status()
            except requests.exceptions.HTTPError as error:
                caught_error = error
                caught_public_error = True
            except pyronaut_requests.exceptions.HTTPError as error:
                caught_error = error
            """);
        assertEquals(404, context.eval("python", "response.status_code").asInt());
        assertTrue(context.eval("python", "caught_error.response is response").asBoolean());
        assertTrue(context.eval("python", "caught_public_error").asBoolean(),
            "Micronaut-backed context response must be caught by requests.exceptions.HTTPError");
    }
}

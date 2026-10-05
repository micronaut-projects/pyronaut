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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestsExceptionBridgeGraalPyTest {
    private Context context;

    @BeforeEach
    void setup() throws Exception {
        context = GraalPyContextFactory.bootstrapReusableContext(getClass().getClassLoader());
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
    void sdkThenStandardRequestsPreservesPreviouslyImportedExceptionBases() {
        verifyImportOrder("""
            from pyronaut import requests as framework_requests
            names = ("RequestException", "HTTPError", "ConnectionError", "Timeout", "TooManyRedirects")
            original_sdk_types = {name: getattr(framework_requests.exceptions, name) for name in names}
            import requests
            """);
    }

    @Test
    void standardThenSdkRequestsPreservesBothExceptionHierarchies() {
        verifyImportOrder("""
            import requests
            from pyronaut import requests as framework_requests
            names = ("RequestException", "HTTPError", "ConnectionError", "Timeout", "TooManyRedirects")
            original_sdk_types = {name: getattr(framework_requests.exceptions, name) for name in names}
            """);
    }

    private void verifyImportOrder(String imports) {
        context.getBindings("python").putMember("missing_response", HttpResponse.notFound());
        context.eval("python", imports);
        context.eval("python", """
            standard_types = {name: getattr(requests.exceptions, name) for name in names}
            standard_session = requests.Session
            first_types = {name: getattr(framework_requests.exceptions, name) for name in names}
            requests._bridge_exception_types()
            assert first_types == {name: getattr(framework_requests.exceptions, name) for name in names}
            for name in names:
                sdk_type = getattr(framework_requests.exceptions, name)
                standard_type = standard_types[name]
                original_type = original_sdk_types[name]
                error = sdk_type("sdk error")
                assert isinstance(error, framework_requests.exceptions.RequestException)
                try:
                    raise error
                except standard_type as caught:
                    assert caught is error
                try:
                    raise error
                except original_type as caught:
                    assert caught is error
                assert getattr(requests.exceptions, name) is standard_type
            assert requests.Session is standard_session
            from requests.adapters import HTTPAdapter
            with requests.Session() as session:
                assert isinstance(session.get_adapter("https://localhost/"), HTTPAdapter)
                session.headers["X-Bridge"] = "preserved"
                prepared = session.prepare_request(requests.Request("GET", "http://localhost/"))
                assert prepared.headers["X-Bridge"] == "preserved"
            response = framework_requests.Response("http://localhost/does-not-exist", missing_response, body=b"")
            caught_error = None
            caught_public_error = False
            try:
                response.raise_for_status()
            except requests.exceptions.HTTPError as error:
                caught_error = error
                caught_public_error = True
            except original_sdk_types["HTTPError"] as error:
                caught_error = error
            """);
        assertEquals(404, context.eval("python", "response.status_code").asInt());
        assertTrue(context.eval("python", "caught_error.response is response").asBoolean());
        assertTrue(context.eval("python", "caught_public_error").asBoolean());
    }
}

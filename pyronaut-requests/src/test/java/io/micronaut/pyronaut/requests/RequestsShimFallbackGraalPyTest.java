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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class RequestsShimFallbackGraalPyTest {
    @Test
    void absentInstalledRequestsKeepsOriginalFrameworkApi() throws Exception {
        Context context = GraalPyContextFactory.bootstrapReusableContext(getClass().getClassLoader());
        try {
            context.getBindings("python").putMember("missing_response", HttpResponse.notFound());
            assertDoesNotThrow(() -> context.eval("python", """
                import os
                import sys
                assert "requests" not in sys.modules
                virtualenv = os.environ.pop("VIRTUAL_ENV", None)
                if virtualenv:
                    sys.path[:] = [path for path in sys.path if not os.path.abspath(path).startswith(os.path.abspath(virtualenv) + os.sep)]
                import requests
                from pyronaut import requests as framework_requests
                for name in ("request", "get", "post", "put", "delete", "patch", "head", "options", "Session", "exceptions", "with_context"):
                    assert getattr(requests, name) is getattr(framework_requests, name)
                response = framework_requests.Response("http://localhost/does-not-exist", missing_response, body=b"")
                try:
                    response.raise_for_status()
                except requests.exceptions.HTTPError as error:
                    assert error.response is response
                    assert response.status_code == 404
                else:
                    raise AssertionError("Expected the original framework HTTPError")
                """));
        } finally {
            PythonContextRuntime.setReuseContext(false);
            PythonContextRuntime.resetContext();
            context.close();
        }
    }
}

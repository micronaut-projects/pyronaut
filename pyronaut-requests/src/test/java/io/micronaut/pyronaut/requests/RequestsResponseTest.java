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
        try (Context context = newPythonContext()) {
            Value result = context.eval("python", """
                import java

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
                """);

            assertTrue(result.asBoolean());
        }
    }

    @Test
    void signedJavaByteArrayResponseBodiesAreConvertedToPythonBytes() {
        try (Context context = newPythonContext()) {
            context.getBindings("python").putMember("signedBody", new byte[] {0, 1, -1, -128, 127});
            Value result = context.eval("python", """
                import java

                HttpResponse = java.type("io.micronaut.http.HttpResponse")

                from pyronaut.requests import Response

                response = Response(
                    "http://localhost/files/books.xlsx",
                    HttpResponse.ok(),
                    body=signedBody,
                )
                response.content == bytes([0, 1, 255, 128, 127])
                """);

            assertTrue(result.asBoolean());
        }
    }

    @Test
    void repeatedQueryParametersArePreserved() {
        try (Context context = newPythonContext()) {
            Value result = context.eval("python", """
                import java
                import pyronaut.requests as requests

                HttpResponse = java.type("io.micronaut.http.HttpResponse")

                class FakeInvoker:
                    captured = []

                    @staticmethod
                    def exchange(client, request, body_type):
                        FakeInvoker.captured.append(request)
                        return type("Result", (), {
                            "success": True,
                            "response": HttpResponse.ok(),
                            "body": b"",
                        })()

                requests.HttpClientInvoker = FakeInvoker
                response = requests.Session(register=False).get(
                    "/fruits/q",
                    params=[("names", "apple"), ("names", "pineapple")],
                )
                uri = str(FakeInvoker.captured[0].getUri())
                response.status_code == 200 and uri == "/fruits/q?names=apple&names=pineapple"
                """);

            assertTrue(result.asBoolean());
        }
    }

    @Test
    void sessionBuildsResponseFromInvokerResponseWrapper() {
        try (Context context = newPythonContext()) {
            Value result = context.eval("python", """
                import java
                import pyronaut.requests as requests

                HttpResponse = java.type("io.micronaut.http.HttpResponse")

                class FakeInvoker:
                    @staticmethod
                    def exchange(client, request, body_type):
                        return type("Result", (), {
                            "success": True,
                            "response": HttpResponse.ok(),
                            "body": b'{"status":"ok"}',
                        })()

                requests.HttpClientInvoker = FakeInvoker
                response = requests.Session(register=False).get("/health")
                response.status_code == 200 and response.json()["status"] == "ok"
                """);

            assertTrue(result.asBoolean());
        }
    }

    @Test
    void dictQueryListValuesAreExpanded() {
        try (Context context = newPythonContext()) {
            Value result = context.eval("python", """
                import java
                import pyronaut.requests as requests

                HttpResponse = java.type("io.micronaut.http.HttpResponse")

                class FakeInvoker:
                    captured = []

                    @staticmethod
                    def exchange(client, request, body_type):
                        FakeInvoker.captured.append(request)
                        return type("Result", (), {
                            "success": True,
                            "response": HttpResponse.ok(),
                            "body": b"",
                        })()

                requests.HttpClientInvoker = FakeInvoker
                response = requests.Session(register=False).get(
                    "/fruits/q",
                    params={"names": ["apple", "pineapple"]},
                )
                uri = str(FakeInvoker.captured[0].getUri())
                response.status_code == 200 and uri == "/fruits/q?names=apple&names=pineapple"
                """);

            assertTrue(result.asBoolean());
        }
    }

    @Test
    void formDataIsSentAsJavaStringBody() {
        try (Context context = newPythonContext()) {
            Value result = context.eval("python", """
                import java
                import pyronaut.requests as requests

                HttpResponse = java.type("io.micronaut.http.HttpResponse")
                HttpStatus = java.type("io.micronaut.http.HttpStatus")
                JString = java.type("java.lang.String")

                class FakeInvoker:
                    captured = []

                    @staticmethod
                    def exchange(client, request, body_type):
                        FakeInvoker.captured.append(request)
                        return type("Result", (), {
                            "success": True,
                            "response": HttpResponse.status(HttpStatus.SEE_OTHER).header("Location", "/rooms/1"),
                            "body": b"",
                        })()

                requests.HttpClientInvoker = FakeInvoker
                response = requests.Session(register=False).post(
                    "/rooms",
                    data={"name": "Room A", "symbol": "a b"},
                    allow_redirects=False,
                )
                request = FakeInvoker.captured[0]
                body = request.getBody(JString).get()
                content_type = request.getContentType().get().toString()
                response.status_code == 303 and body == "name=Room+A&symbol=a+b" and content_type == "application/x-www-form-urlencoded"
                """);

            assertTrue(result.asBoolean());
        }
    }

    @Test
    void multipartFilesUseMicronautMultipartBody() {
        try (Context context = newPythonContext()) {
            Value result = context.eval("python", """
                import java
                import pyronaut.requests as requests

                HttpResponse = java.type("io.micronaut.http.HttpResponse")
                MultipartBody = java.type("io.micronaut.http.client.multipart.MultipartBody")

                class FakeInvoker:
                    captured = []

                    @staticmethod
                    def exchange(client, request, body_type):
                        FakeInvoker.captured.append(request)
                        return type("Result", (), {
                            "success": True,
                            "response": HttpResponse.created("/pictures/alvaro"),
                            "body": b"",
                        })()

                requests.HttpClientInvoker = FakeInvoker
                response = requests.Session(register=False).post(
                    "/pictures/alvaro",
                    data={"description": "avatar"},
                    files={"fileUpload": ("test-file.txt", b"micronaut", "text/plain")},
                )
                request = FakeInvoker.captured[0]
                content_type = request.getContentType().get().toString()
                response.status_code == 201 \\
                    and request.getBody(MultipartBody).isPresent() \\
                    and content_type == "multipart/form-data"
                """);

            assertTrue(result.asBoolean());
        }
    }

    @Test
    void falseyAllowRedirectsDoesNotFollowRedirects() {
        try (Context context = newPythonContext()) {
            Value result = context.eval("python", """
                import pyronaut.requests as requests
                import java

                HttpResponse = java.type("io.micronaut.http.HttpResponse")
                HttpStatus = java.type("io.micronaut.http.HttpStatus")

                class FalseyRedirects:
                    def __bool__(self):
                        return False

                class FakeInvoker:
                    calls = 0

                    @staticmethod
                    def exchange(client, request, body_type):
                        FakeInvoker.calls += 1
                        return type("Result", (), {
                            "success": True,
                            "response": HttpResponse.status(HttpStatus.SEE_OTHER).header("Location", "/next"),
                            "body": b"",
                        })()

                requests.HttpClientInvoker = FakeInvoker
                response = requests.Session(register=False).get("/", allow_redirects=FalseyRedirects())
                response.status_code == 303 and FakeInvoker.calls == 1
                """);

            assertTrue(result.asBoolean());
        }
    }

    private static Context newPythonContext() {
        Path pythonSource = Path.of(
            "src/main/resources/META-INF/GRAALPY-VFS/micronaut-application/src"
        ).toAbsolutePath();
        String pythonPath = pythonSource.toString().replace("\\", "\\\\").replace("'", "\\'");
        Context context = Context.newBuilder("python")
            .allowAllAccess(true)
            .build();
        context.eval("python", """
                import sys

                sys.path.insert(0, '%s')
                """.formatted(pythonPath));
        return context;
    }
}

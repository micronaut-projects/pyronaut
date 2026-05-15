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

import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/**
 * Executes Micronaut HTTP client requests and converts failures into interop-friendly results.
 */
public final class HttpClientInvoker {
    private HttpClientInvoker() {
    }

    public static Result exchange(HttpClient client, MutableHttpRequest<?> request, Class<?> bodyType) {
        try {
            BlockingHttpClient blocking = client.toBlocking();
            Object resp = blocking.exchange(request, bodyType);
            return Result.success(resp);
        } catch (Throwable t) {
            if (t instanceof HttpClientResponseException hre) {
                if (hre.getResponse() != null) {
                    return Result.success(hre.getResponse());
                }
            }
            return Result.failure(t);
        }
    }

    static byte[] responseBodyBytes(HttpResponse<?> response) {
        return response.getBody(byte[].class)
            .or(() -> response.getBody(String.class).map(value -> value.getBytes(StandardCharsets.UTF_8)))
            .orElse(null);
    }

    private static String stackTraceToString(Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    /**
     * Interop-friendly HTTP client invocation result.
     */
    @SuppressWarnings("checkstyle:VisibilityModifier")
    public static final class Result {
        public final boolean success;
        public final Object response;
        public final byte[] body;
        public final String message;
        public final String exceptionClass;
        public final String stack;

        private Result(boolean success, Object response, Throwable error) {
            this.success = success;
            this.response = response;
            this.body = response instanceof HttpResponse<?> httpResponse ? responseBodyBytes(httpResponse) : null;
            if (error != null) {
                this.message = error.getMessage();
                this.exceptionClass = error.getClass().getName();
                this.stack = stackTraceToString(error);
            } else {
                this.message = null;
                this.exceptionClass = null;
                this.stack = null;
            }
        }

        static Result success(Object resp) {
            return new Result(true, resp, null);
        }

        static Result failure(Throwable t) {
            return new Result(false, null, t);
        }
    }
}

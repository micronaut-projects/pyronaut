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
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpClientInvokerTest {

    @Test
    void successIncludesBodyBytesFromErrorResponse() {
        HttpResponse<?> response = HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
            .contentType(MediaType.APPLICATION_JSON_TYPE)
            .body("{\"status\":\"DOWN\"}");

        HttpClientInvoker.Result result = HttpClientInvoker.Result.success(response);

        assertArrayEquals("{\"status\":\"DOWN\"}".getBytes(StandardCharsets.UTF_8), result.body);
        assertEquals(503, result.statusCode);
        assertEquals("Service Unavailable", result.reason);
        assertArrayEquals(new String[] {"Content-Type"}, result.headerNames);
        assertArrayEquals(new String[] {"application/json"}, result.headerValues);
    }
}

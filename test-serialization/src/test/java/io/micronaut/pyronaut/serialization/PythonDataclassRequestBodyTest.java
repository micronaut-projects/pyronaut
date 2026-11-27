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
package io.micronaut.pyronaut.serialization;

import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.EmbeddedServer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A dataclass request body gets the defaults the dataclass declares and has to carry its fields without a default.
 */
class PythonDataclassRequestBodyTest {

    @Test
    void appliesTheDefaultsOfTheDataclassToAMissingField() {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class);
             HttpClient httpClient = server.getApplicationContext().createBean(HttpClient.class, server.getURL())) {
            BlockingHttpClient client = httpClient.toBlocking();

            assertEquals(
                Map.of("name", "x", "count", "1", "status", "WANT_TO_READ", "label", "default-label"),
                client.retrieve(HttpRequest.POST("/books", "{\"name\":\"x\",\"count\":1}"), Map.class)
            );
            assertEquals(
                Map.of("name", "z", "count", "3", "status", "WANT_TO_READ", "label", "mine"),
                client.retrieve(HttpRequest.POST("/books", "{\"name\":\"z\",\"count\":3,\"label\":\"mine\"}"), Map.class)
            );
            assertEquals(
                Map.of("name", "y", "count", "2", "status", "READING", "label", "mine"),
                client.retrieve(HttpRequest.POST("/books", "{\"name\":\"y\",\"count\":2,\"status\":\"READING\",\"label\":\"mine\"}"), Map.class)
            );
        }
    }

    @Test
    void rejectsABodyWithoutTheRequiredFieldsOfTheDataclass() {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class);
             HttpClient httpClient = server.getApplicationContext().createBean(HttpClient.class, server.getURL())) {
            BlockingHttpClient client = httpClient.toBlocking();

            HttpClientResponseException empty = assertThrows(
                HttpClientResponseException.class,
                () -> client.retrieve(HttpRequest.POST("/books", "{}"), Map.class)
            );
            assertEquals(HttpStatus.BAD_REQUEST, empty.getStatus());

            HttpClientResponseException missingCount = assertThrows(
                HttpClientResponseException.class,
                () -> client.retrieve(HttpRequest.POST("/books", "{\"name\":\"x\"}"), Map.class)
            );
            assertEquals(HttpStatus.BAD_REQUEST, missingCount.getStatus());
        }
    }

    @Test
    void passesTheBodyToTheRouteAndToAServiceAsADataclassInstance() {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class);
             HttpClient httpClient = server.getApplicationContext().createBean(HttpClient.class, server.getURL())) {
            BlockingHttpClient client = httpClient.toBlocking();

            assertEquals(
                Map.of(
                    "route_type", "BookRequest", "route_is_dataclass", "True", "route_note", "None", "route_status", "WANT_TO_READ",
                    "service_type", "BookRequest", "service_is_dataclass", "True", "service_note", "None", "service_status", "WANT_TO_READ"
                ),
                client.retrieve(HttpRequest.POST("/books/asdict", "{\"name\":\"x\",\"count\":1}"), Map.class)
            );
        }
    }
}

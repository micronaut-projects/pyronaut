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
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.EmbeddedServer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PythonSerdeSerializationTest {

    @Test
    void serializesNestedPythonSerdeableListPropertiesFromHttpRoute() {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class);
             HttpClient httpClient = server.getApplicationContext().createBean(HttpClient.class, server.getURL())) {
            BlockingHttpClient client = httpClient.toBlocking();

            assertEquals(
                "{\"properties\":{\"periods\":[{\"temperature\":68,\"temperatureUnit\":\"F\",\"windSpeed\":\"9 mph\",\"windDirection\":\"NW\",\"detailedForecast\":\"Clear near MTR grid 88,126\"}]}}",
                client.retrieve("/forecast")
            );
        }
    }

    @Test
    void servesAControllerWrittenAgainstThePyronautFacades() {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class);
             HttpClient httpClient = server.getApplicationContext().createBean(HttpClient.class, server.getURL())) {
            BlockingHttpClient client = httpClient.toBlocking();

            // serde.JsonProperty renames the property, http.ok builds the response
            assertEquals("{\"name\":\"Fred\",\"person_age\":42}", client.retrieve("/people/Fred"));
            // http.HttpResponse.status(http.status.NOT_FOUND)
            HttpClientResponseException missing = assertThrows(HttpClientResponseException.class, () -> client.retrieve("/people/nobody"));
            assertEquals(HttpStatus.NOT_FOUND, missing.getStatus());
            // @http.Status(http.status.CREATED) with an http.Body parameter
            HttpResponse<String> created = client.exchange(HttpRequest.POST("/people", "{\"name\":\"Wilma\",\"person_age\":40}"), String.class);
            assertEquals(HttpStatus.CREATED, created.getStatus());
            assertEquals("{\"name\":\"Wilma\",\"person_age\":40}", created.body());
            // trace: str | None = http.Header("X-Trace") binds the header
            assertEquals("Fred:abc", client.retrieve(HttpRequest.GET("/people/Fred/trace").header("X-Trace", "abc")));
            // ctx: context.ApplicationContext = context.Inject() receives the bean
            assertEquals("True", client.retrieve("/people/running"));
        }
    }

    @Test
    void serializesNestedPythonSerdeableListPropertiesFromTestSourceHttpRoute() {
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, Map.of("spec.name", "PythonSerdeSerializationTest"));
             HttpClient httpClient = server.getApplicationContext().createBean(HttpClient.class, server.getURL())) {
            BlockingHttpClient client = httpClient.toBlocking();

            assertEquals(
                "{\"properties\":{\"periods\":[{\"temperature\":68,\"temperatureUnit\":\"F\",\"windSpeed\":\"9 mph\",\"windDirection\":\"NW\",\"detailedForecast\":\"Clear near MTR grid 88,126\"}]}}",
                client.retrieve("/gridpoints/MTR/88,126/forecast")
            );
        }
    }
}

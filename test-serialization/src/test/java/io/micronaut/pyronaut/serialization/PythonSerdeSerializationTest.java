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
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.runtime.server.EmbeddedServer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

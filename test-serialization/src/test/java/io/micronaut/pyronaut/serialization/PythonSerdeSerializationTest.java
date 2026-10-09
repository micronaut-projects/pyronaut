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
import io.micronaut.core.annotation.AnnotationClassValue;
import io.micronaut.core.beans.BeanIntrospection;
import io.micronaut.core.beans.exceptions.IntrospectionException;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.json.JsonMapper;
import io.micronaut.runtime.server.EmbeddedServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            // ctx: inject.ApplicationContext = inject.Inject() receives the bean
            assertEquals("True", client.retrieve("/people/running"));
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void aliasesPreserveSerdeOptInMembersAndSingletonScope() throws Exception {
        try (var context = ApplicationContext.run()) {
            JsonMapper mapper = context.getBean(JsonMapper.class);
            assertTrue(mapper.getClass().getName().startsWith("io.micronaut.serde."));
            Class service = Class.forName("example.micronaut.PeopleService");
            assertSame(context.getBean(service), context.getBean(service));
            assertTrue(context.getBeanDefinition(service).isSingleton());

            var household = introspection("Household");
            var person = introspection("Person");
            String nested = "{\"people\":[{\"name\":\"Fred\",\"person_age\":42}]}";
            assertEquals(nested, mapper.writeValueAsString(household.instantiate(List.of(person.instantiate("Fred", 42, null)))));
            assertEquals(nested, mapper.writeValueAsString(mapper.readValue(nested, household.getBeanType())));

            var named = introspection("NamedPayload");
            var metadata = named.getAnnotationMetadata();
            assertFalse(metadata.booleanValue("io.micronaut.serde.annotation.Serdeable", "validate").orElseThrow());
            assertEquals("io.micronaut.serde.config.naming.SnakeCaseStrategy", metadata
                .getValue("io.micronaut.serde.annotation.Serdeable", "naming", AnnotationClassValue.class).orElseThrow().getName());
            String namedJson = "{\"first_name\":\"Wilma\"}";
            assertEquals(namedJson, mapper.writeValueAsString(named.instantiate("Wilma")));
            assertEquals(namedJson, mapper.writeValueAsString(mapper.readValue(namedJson, named.getBeanType())));

            var output = introspection("OutputPayload");
            var input = introspection("InputPayload");
            String directional = "{\"name\":\"Fred\"}";
            assertEquals(directional, mapper.writeValueAsString(output.instantiate("Fred")));
            assertThrows(IntrospectionException.class, () -> mapper.readValue(directional, output.getBeanType()));
            Object decoded = mapper.readValue(directional, input.getBeanType());
            assertEquals("Fred", input.getRequiredProperty("name", String.class).get(decoded));
            assertThrows(IOException.class, () -> mapper.writeValueAsString(decoded));

            var internal = introspection("InternalPayload");
            assertThrows(IOException.class, () -> mapper.writeValueAsString(internal.instantiate("Fred")));
            assertThrows(IntrospectionException.class, () -> mapper.readValue(directional, internal.getBeanType()));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BeanIntrospection<Object> introspection(String name) throws ClassNotFoundException {
        return BeanIntrospection.getIntrospection((Class) Class.forName("example.micronaut." + name));
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

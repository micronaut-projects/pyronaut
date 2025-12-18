package io.micronaut.pyronaut.starter.controllers;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
class ModalControllerTest {

    @Test
    void modalPreview(@Client("/") HttpClient httpClient) {
        Map<String, Object> form = Map.of("name", "pyronaut-demo", "version", "1.0.0");
        HttpRequest<?> request = HttpRequest.POST("/modal/preview", form)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .accept(MediaType.TEXT_HTML);
        BlockingHttpClient client = httpClient.toBlocking();
        assertDoesNotThrow(() -> client.exchange(request));
    }
}

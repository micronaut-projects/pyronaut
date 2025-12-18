package io.micronaut.pyronaut.starter;

import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest
class UtilsJsTest {

    @Test
    void testUtilsJs(@Client("/") HttpClient httpClient) {
        BlockingHttpClient client = httpClient.toBlocking();
        HttpRequest<?> request = HttpRequest.GET("/assets/javascripts/utils.js");
        HttpResponse<?> response = assertDoesNotThrow(() -> client.exchange(request));
        assertTrue(response.getHeaders().get(HttpHeaders.CACHE_CONTROL, String.class).isPresent());
        assertEquals("private, max-age=60", response.getHeaders().get(HttpHeaders.CACHE_CONTROL));
    }
}

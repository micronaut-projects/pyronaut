package example;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Controller("/")
public final class ResourceController {
    @Get
    String resource() throws IOException {
        try (InputStream stream = ResourceController.class.getResourceAsStream("/main-marker.txt")) {
            if (stream == null) {
                return "missing-main-resource";
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }
}

package io.micronaut.pyronaut.testresources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FallbackTestResourcesServerMainTest {

    @TempDir
    Path tempDir;

    @Test
    void insightsEndpointsRequireBearerAuthAndExposeStablePayload() throws Exception {
        String token = "token-123";
        Path portFile = tempDir.resolve("server.port");
        Thread serverThread = launchServer(portFile, token);
        int port = waitForPort(portFile);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest missingTokenRequest = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/test-resources/health"))
            .GET()
            .timeout(Duration.ofSeconds(5))
            .build();
        HttpResponse<String> missingTokenResponse = client.send(missingTokenRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, missingTokenResponse.statusCode());

        HttpRequest invalidTokenRequest = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/test-resources/health"))
            .header("Authorization", "Bearer bad-token")
            .GET()
            .timeout(Duration.ofSeconds(5))
            .build();
        HttpResponse<String> invalidTokenResponse = client.send(invalidTokenRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(403, invalidTokenResponse.statusCode());

        HttpRequest validHealthRequest = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/test-resources/health"))
            .header("Authorization", "Bearer " + token)
            .GET()
            .timeout(Duration.ofSeconds(5))
            .build();
        HttpResponse<String> validHealthResponse = client.send(validHealthRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, validHealthResponse.statusCode());
        assertTrue(validHealthResponse.body().contains("\"health\""));
        assertTrue(validHealthResponse.body().contains("\"status\":\"UP\""));

        assertArrayPayload(client, port, token, "containers", "containers");
        assertArrayPayload(client, port, token, "properties", "properties");
        assertArrayPayload(client, port, token, "errors", "errors");

        stopServer(client, port, token);
        serverThread.join(5_000);
        assertTrue(!serverThread.isAlive());
    }

    private static Thread launchServer(Path portFile, String token) {
        Thread thread = new Thread(() -> {
            try {
                FallbackTestResourcesServerMain.main(new String[] {
                    "--port=0",
                    "--port-file=" + portFile,
                    "--access-token=" + token,
                    "--idle-timeout-minutes=10"
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, "fallback-server-test");
        thread.start();
        return thread;
    }

    private static int waitForPort(Path portFile) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(portFile)) {
                String value = Files.readString(portFile).trim();
                if (!value.isEmpty()) {
                    return Integer.parseInt(value);
                }
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("Timed out waiting for server port file");
    }

    private static void assertArrayPayload(HttpClient client, int port, String token, String endpoint, String key) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/test-resources/" + endpoint))
            .header("Authorization", "Bearer " + token)
            .GET()
            .timeout(Duration.ofSeconds(5))
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"" + key + "\":[]"));
    }

    private static void stopServer(HttpClient client, int port, String token) throws IOException, InterruptedException {
        HttpRequest stopRequest = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/stop"))
            .header("Access-Token", token)
            .POST(HttpRequest.BodyPublishers.noBody())
            .timeout(Duration.ofSeconds(5))
            .build();
        HttpResponse<String> stopResponse = client.send(stopRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, stopResponse.statusCode());
    }
}

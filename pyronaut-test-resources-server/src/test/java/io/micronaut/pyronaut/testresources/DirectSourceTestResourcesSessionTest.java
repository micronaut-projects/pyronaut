package io.micronaut.pyronaut.testresources;

import io.micronaut.testresources.buildtools.ServerSettings;
import io.micronaut.testresources.buildtools.ServerUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DirectSourceTestResourcesSessionTest {
    @Test
    void startsAndStopsAnOwnedServer(@TempDir Path projectRoot) throws Exception {
        RecordingServerManager manager = new RecordingServerManager();
        List<String> output = new CopyOnWriteArrayList<>();

        DirectSourceTestResourcesSession session =
            DirectSourceTestResourcesSession.open(projectRoot, manager, output::add);

        assertTrue(session.owned());
        assertTrue(output.contains("[test-resources] start owned server"), output.toString());
        assertTrue(output.stream().anyMatch(line ->
            line.equals("[test-resources] server running on port 9123 (http://localhost:9123); logs: "
                + projectRoot.resolve(".micronaut/test-resources/logs"))
        ), output.toString());
        assertEquals("http://localhost:9123", session.clientProperties().get(
            "micronaut.test.resources.server.uri"
        ));
        assertEquals(manager.startedToken, session.clientProperties().get(
            "micronaut.test.resources.server.access.token"
        ));
        session.close();
        assertTrue(output.contains("[test-resources] stop owned server"), output.toString());
        assertEquals(1, manager.stopCalls);
        assertFalse(Files.exists(projectRoot.resolve(".micronaut/test-resources/test-resources.properties")));
    }

    @Test
    void usesConfiguredClientTimeoutForOwnedServer(@TempDir Path projectRoot) throws Exception {
        Files.writeString(projectRoot.resolve("pyproject.toml"), """
            [tool.pyronaut.test-resources]
            enabled = true
            client-timeout = 720
            """);
        RecordingServerManager manager = new RecordingServerManager();

        try (DirectSourceTestResourcesSession ignored =
                 DirectSourceTestResourcesSession.open(projectRoot, manager, line -> { })) {
            assertEquals("720", ignored.clientProperties().get(
                "micronaut.test.resources.server.client.read.timeout"
            ));
        }
    }

    @Test
    void mirrorsContainerProgressAndErrorsFromOwnedServer(@TempDir Path projectRoot) throws Exception {
        RecordingServerManager manager = new RecordingServerManager();
        List<String> output = new CopyOnWriteArrayList<>();
        Path serverLog = projectRoot.resolve(".micronaut/test-resources/logs/test-resources.log");
        Files.createDirectories(serverLog.getParent());
        Files.writeString(
            serverLog,
            "16:09:00.000 [old] INFO example - Creating container for image: stale/image\n"
        );

        try (DirectSourceTestResourcesSession ignored =
                 DirectSourceTestResourcesSession.open(projectRoot, manager, output::add)) {
            Files.writeString(serverLog, """
                16:10:04.527 [pool-2-thread-1] INFO  tc.testcontainers/ryuk:0.14.0 - Creating container for image: testcontainers/ryuk:0.14.0
                16:10:04.600 [pool-2-thread-1] INFO  tc.testcontainers/ryuk:0.14.0 - Connected to Docker
                16:10:04.889 [pool-2-thread-1] INFO  tc.testcontainers/ryuk:0.14.0 - Container testcontainers/ryuk:0.14.0 started in PT0.362391S
                16:10:05.000 [pool-2-thread-1] ERROR example - container failed
                """, StandardOpenOption.APPEND);
            awaitOutput(output, "Creating container for image:");
            awaitOutput(output, "started in PT0.362391S");
            awaitOutput(output, "container failed");
        }

        assertFalse(output.stream().anyMatch(line -> line.contains("stale/image")), output.toString());
        assertFalse(output.stream().anyMatch(line -> line.contains("Connected to Docker")), output.toString());
    }

    @Test
    void keepsOwnedServerForDirectSourceRestart(@TempDir Path projectRoot) throws Exception {
        RecordingServerManager manager = new RecordingServerManager();
        String previous = System.getProperty("pyronaut.dev.direct.restartable");
        System.setProperty("pyronaut.dev.direct.restartable", "true");
        try {
            DirectSourceTestResourcesSession session =
                DirectSourceTestResourcesSession.open(projectRoot, manager, line -> { });
            session.close();
            assertEquals(0, manager.stopCalls);
            assertTrue(Files.exists(projectRoot.resolve(".micronaut/test-resources/test-resources.properties")));
        } finally {
            if (previous == null) {
                System.clearProperty("pyronaut.dev.direct.restartable");
            } else {
                System.setProperty("pyronaut.dev.direct.restartable", previous);
            }
        }
    }

    @Test
    void attachesWithoutStoppingAnExternalServer(@TempDir Path projectRoot) throws Exception {
        RecordingServerManager manager = new RecordingServerManager();
        manager.running = true;
        List<String> output = new CopyOnWriteArrayList<>();
        Path settingsDirectory = projectRoot.resolve(".micronaut/test-resources");
        ServerUtils.writeServerSettings(
            settingsDirectory,
            new ServerSettings(8123, "external-token", 25, null)
        );

        DirectSourceTestResourcesSession session =
            DirectSourceTestResourcesSession.open(projectRoot, manager, output::add);

        assertFalse(session.owned());
        assertTrue(output.contains("[test-resources] attach external server"), output.toString());
        assertEquals("external-token", session.clientProperties().get(
            "micronaut.test.resources.server.access.token"
        ));
        assertEquals("25", session.clientProperties().get(
            "micronaut.test.resources.server.client.read.timeout"
        ));
        session.close();
        assertEquals(0, manager.stopCalls);
        assertTrue(Files.exists(settingsDirectory.resolve("test-resources.properties")));
    }

    private static void awaitOutput(List<String> output, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (output.stream().anyMatch(line -> line.contains(expected))) {
                return;
            }
            Thread.sleep(20);
        }
        assertTrue(output.stream().anyMatch(line -> line.contains(expected)), output.toString());
    }

    @Test
    void startupFailureStopsOnlyTheServerWithTheRequestedToken(@TempDir Path projectRoot) {
        RecordingServerManager manager = new RecordingServerManager();
        manager.failStart = true;

        assertThrows(IOException.class, () ->
            DirectSourceTestResourcesSession.open(projectRoot, manager)
        );

        assertEquals(1, manager.stopCalls);
        assertFalse(Files.exists(projectRoot.resolve(".micronaut/test-resources/test-resources.properties")));
    }

    @Test
    void startupFailureDoesNotStopAConcurrentExternalServer(@TempDir Path projectRoot) {
        RecordingServerManager manager = new RecordingServerManager();
        manager.failStart = true;
        manager.externalTokenOnStart = true;

        assertThrows(IOException.class, () ->
            DirectSourceTestResourcesSession.open(projectRoot, manager)
        );

        assertEquals(0, manager.stopCalls);
        assertTrue(Files.exists(projectRoot.resolve(".micronaut/test-resources/test-resources.properties")));
    }

    private static final class RecordingServerManager implements PyronautTestResourcesServerMain.ServerManager {
        private boolean running;
        private int stopCalls;
        private String startedToken;
        private boolean failStart;
        private boolean externalTokenOnStart;

        @Override
        public PyronautTestResourcesServerMain.ServerStatus start(
            PyronautTestResourcesServerMain.ServerStartRequest request
        ) throws IOException {
            running = true;
            startedToken = request.accessToken();
            String persistedToken = externalTokenOnStart ? "external-token" : startedToken;
            ServerUtils.writeServerSettings(
                request.settingsDir(),
                new ServerSettings(9123, persistedToken, request.clientTimeout(), null)
            );
            Files.writeString(request.portFile(), "9123");
            if (failStart) {
                throw new IOException("startup failed");
            }
            return new PyronautTestResourcesServerMain.ServerStatus(true, 9123, "http://localhost:9123");
        }

        @Override
        public PyronautTestResourcesServerMain.ServerStatus status(Path settingsDir) {
            return running
                ? new PyronautTestResourcesServerMain.ServerStatus(true, 8123, "http://localhost:8123")
                : new PyronautTestResourcesServerMain.ServerStatus(false, -1, "");
        }

        @Override
        public boolean stop(Path settingsDir) {
            stopCalls++;
            running = false;
            return true;
        }
    }
}

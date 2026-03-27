package io.micronaut.pyronaut.tui.commands;

import io.micronaut.python.cli.ui.UiController;
import io.micronaut.python.cli.ui.UiModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PyronautDelegatingTuiCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void prepareTestResourcesDeletesStaleSettingsWhenHealthCheckFails() throws Exception {
        Path project = tempDir.resolve("demo");
        Path settingsDir = project.resolve(".micronaut/test-resources");
        Files.createDirectories(settingsDir);
        Files.writeString(settingsDir.resolve("server.port"), "51565\n", StandardCharsets.UTF_8);
        Files.writeString(
            settingsDir.resolve("test-resources.properties"),
            "server.uri=http\\://localhost\\:51565\nserver.access.token=abc\n",
            StandardCharsets.UTF_8
        );

        TestablePyronautDelegatingTuiCommand command = new TestablePyronautDelegatingTuiCommand(false);

        command.prepareTestResources(project);

        assertFalse(Files.exists(settingsDir.resolve("server.port")));
        assertFalse(Files.exists(settingsDir.resolve("test-resources.properties")));
    }

    @Test
    void startProcessAddsJavaToolOptionsWhenReachableSettingsExist() throws Exception {
        Path project = tempDir.resolve("demo");
        Path settingsDir = project.resolve(".micronaut/test-resources");
        Files.createDirectories(settingsDir);
        Files.writeString(
            settingsDir.resolve("test-resources.properties"),
            "server.uri=http\\://localhost\\:18080\n"
                + "server.access.token=token-123\n"
                + "server.client.read.timeout=60\n",
            StandardCharsets.UTF_8
        );
        Path script = project.resolve("capture-env.sh");
        Path output = project.resolve("env.txt");
        Files.writeString(
            script,
            "#!/bin/sh\nprintf '%s' \"$JAVA_TOOL_OPTIONS\" > \"$1\"\n",
            StandardCharsets.UTF_8
        );
        script.toFile().setExecutable(true);

        TestablePyronautDelegatingTuiCommand command = new TestablePyronautDelegatingTuiCommand(true);

        Process process = command.start(project, List.of(script.toString(), output.toString()));
        assertEquals(0, process.waitFor());
        String javaToolOptions = Files.readString(output, StandardCharsets.UTF_8);
        assertTrue(javaToolOptions.contains("-Dmicronaut.test.resources.server.uri=http://localhost:18080"));
        assertTrue(javaToolOptions.contains("-Dmicronaut.test.resources.server.access.token=token-123"));
        assertTrue(javaToolOptions.contains("-Dmicronaut.test.resources.server.client.read.timeout=60"));
    }

    @Test
    void applyTestResourcesEnvironmentUsesActiveSessionValuesBeforeStaleProperties() throws Exception {
        Path project = tempDir.resolve("active-session-demo");
        Path settingsDir = project.resolve(".micronaut/test-resources");
        Files.createDirectories(settingsDir);
        Files.writeString(
            settingsDir.resolve("test-resources.properties"),
            "server.uri=http\\://localhost\\:18080\n"
                + "server.access.token=stale-token\n"
                + "server.client.read.timeout=60\n",
            StandardCharsets.UTF_8
        );

        PyronautDelegatingTuiCommand command = new PyronautDelegatingTuiCommand();
        setField(command, "activeTestResourcesConnection", new java.util.concurrent.atomic.AtomicReference<>(
            invokeConnection(
                command,
                "fromEnvironment",
                new Class<?>[]{java.util.Map.class},
                java.util.Map.of(
                    "JAVA_TOOL_OPTIONS",
                    "-Dmicronaut.test.resources.server.uri=http://localhost:19090 "
                        + "-Dmicronaut.test.resources.server.access.token=token-456 "
                        + "-Dmicronaut.test.resources.server.client.read.timeout=45"
                )
            )
        ));

        ProcessBuilder builder = new ProcessBuilder("echo");
        command.applyTestResourcesEnvironment(project, builder);

        String javaToolOptions = builder.environment().get("JAVA_TOOL_OPTIONS");
        assertTrue(javaToolOptions.contains("-Dmicronaut.test.resources.server.uri=http://localhost:19090"));
        assertTrue(javaToolOptions.contains("-Dmicronaut.test.resources.server.access.token=token-456"));
        assertTrue(javaToolOptions.contains("-Dmicronaut.test.resources.server.client.read.timeout=45"));
        assertFalse(javaToolOptions.contains("stale-token"));
    }

    @Test
    void testResourcesServiceOutputRoutesToDedicatedBufferAndNotifiesOnce() throws Exception {
        UiController controller = new UiController();
        PyronautDelegatingTuiCommand command = new PyronautDelegatingTuiCommand();

        setField(command, "controller", controller);

        invoke(command, "routeOutputLine", new Class<?>[]{Process.class, String.class}, null, "[test-resources-service] Server Running: http://localhost:19090");
        invoke(command, "routeOutputLine", new Class<?>[]{Process.class, String.class}, null, "[test-resources-service] another line");
        invoke(command, "routeOutputLine", new Class<?>[]{Process.class, String.class}, null, "application line");

        assertEquals(List.of("application line"), controller.getActivityLogLines());
        assertEquals(
            List.of(
                "[test-resources-service] Server Running: http://localhost:19090",
                "[test-resources-service] another line"
            ),
            controller.getTestResourcesLogLines()
        );
        assertEquals(1, controller.getNotificationHistory().size());
        UiModel.Notification notification = controller.getNotificationHistory().getFirst();
        assertNotNull(notification);
        assertEquals("Test resources service started", notification.message());
        assertEquals(UiModel.Severity.INFO, notification.severity());
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... args) throws Exception {
        var method = target.getClass().getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static Object invokeConnection(Object target, String name, Class<?>[] parameterTypes, Object... args) throws Exception {
        for (Class<?> innerClass : target.getClass().getDeclaredClasses()) {
            if (innerClass.getSimpleName().equals("TestResourcesConnection")) {
                var method = innerClass.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method.invoke(null, args);
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class TestablePyronautDelegatingTuiCommand {
        private final boolean reachable;

        private TestablePyronautDelegatingTuiCommand(boolean reachable) {
            this.reachable = reachable;
        }

        void prepareTestResources(Path project) throws Exception {
            Path settingsDir = project.resolve(".micronaut/test-resources");
            Path settingsFile = settingsDir.resolve("test-resources.properties");
            if (reachable) {
                return;
            }
            Files.deleteIfExists(settingsDir.resolve("server.port"));
            Files.deleteIfExists(settingsFile);
        }

        Process start(Path project, List<String> command) throws Exception {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(project.toFile());
            builder.redirectErrorStream(true);
            if (reachable) {
                Path settingsFile = project.resolve(".micronaut/test-resources/test-resources.properties");
                Properties properties = new Properties();
                try (var in = Files.newInputStream(settingsFile)) {
                    properties.load(in);
                }
                String options = String.join(" ", List.of(
                    "-Dmicronaut.test.resources.server.uri=" + properties.getProperty("server.uri"),
                    "-Dmicronaut.test.resources.server.access.token=" + properties.getProperty("server.access.token"),
                    "-Dmicronaut.test.resources.server.client.read.timeout=" + properties.getProperty("server.client.read.timeout")
                ));
                builder.environment().put("JAVA_TOOL_OPTIONS", options);
            }
            return builder.start();
        }
    }
}

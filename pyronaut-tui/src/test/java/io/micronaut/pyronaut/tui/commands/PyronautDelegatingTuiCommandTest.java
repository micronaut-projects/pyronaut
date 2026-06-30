package io.micronaut.pyronaut.tui.commands;

import io.micronaut.python.cli.ui.UiController;
import io.micronaut.python.cli.ui.UiModel;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Properties;
import java.util.Set;

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
    void startProcessAddsTestResourcesEnvironmentWhenReachableSettingsExist() throws Exception {
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
            "#!/bin/sh\nprintf '%s\\n%s\\n%s' \"$MICRONAUT_TEST_RESOURCES_SERVER_URI\" \"$MICRONAUT_TEST_RESOURCES_SERVER_ACCESS_TOKEN\" \"$MICRONAUT_TEST_RESOURCES_SERVER_CLIENT_READ_TIMEOUT\" > \"$1\"\n",
            StandardCharsets.UTF_8
        );
        script.toFile().setExecutable(true);

        TestablePyronautDelegatingTuiCommand command = new TestablePyronautDelegatingTuiCommand(true);

        Process process = command.start(project, List.of(script.toString(), output.toString()));
        assertEquals(0, process.waitFor());
        List<String> environmentLines = Files.readAllLines(output, StandardCharsets.UTF_8);
        assertEquals(List.of("http://localhost:18080", "token-123", "60"), environmentLines);
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
                    "MICRONAUT_TEST_RESOURCES_SERVER_URI", "http://localhost:19090",
                    "MICRONAUT_TEST_RESOURCES_SERVER_ACCESS_TOKEN", "token-456",
                    "MICRONAUT_TEST_RESOURCES_SERVER_CLIENT_READ_TIMEOUT", "45"
                )
            )
        ));

        ProcessBuilder builder = new ProcessBuilder("echo");
        command.applyTestResourcesEnvironment(project, builder);

        assertEquals("http://localhost:19090", builder.environment().get("MICRONAUT_TEST_RESOURCES_SERVER_URI"));
        assertEquals("token-456", builder.environment().get("MICRONAUT_TEST_RESOURCES_SERVER_ACCESS_TOKEN"));
        assertEquals("45", builder.environment().get("MICRONAUT_TEST_RESOURCES_SERVER_CLIENT_READ_TIMEOUT"));
        assertFalse("stale-token".equals(builder.environment().get("MICRONAUT_TEST_RESOURCES_SERVER_ACCESS_TOKEN")));
    }

    @Test
    void testResourcesServiceOutputRoutesToDedicatedBufferAndNotifiesOnce() throws Exception {
        UiController controller = new UiController();
        PyronautDelegatingTuiCommand command = new PyronautDelegatingTuiCommand();

        setField(command, "controller", controller);

        invoke(command, "routeOutputLine", new Class<?>[]{Process.class, String.class}, null, "[test-resources] server running on port 19090 (http://localhost:19090)");
        invoke(command, "routeOutputLine", new Class<?>[]{Process.class, String.class}, null, "[test-resources-service] Server Running: http://localhost:19090");
        invoke(command, "routeOutputLine", new Class<?>[]{Process.class, String.class}, null, "tc.mysql:8.4.0 - Creating container for image: mysql:8.4.0");
        invoke(command, "routeOutputLine", new Class<?>[]{Process.class, String.class}, null, "tc.mysql:8.4.0 - Container mysql:8.4.0 started in PT0.24592S");
        invoke(command, "routeOutputLine", new Class<?>[]{Process.class, String.class}, null, "[test-resources-service] another line");
        invoke(command, "routeOutputLine", new Class<?>[]{Process.class, String.class}, null, "application line");

        assertEquals(List.of("application line"), controller.getActivityLogLines());
        assertEquals(
            List.of(
                "[test-resources] server running on port 19090 (http://localhost:19090)",
                "[test-resources-service] Server Running: http://localhost:19090",
                "tc.mysql:8.4.0 - Creating container for image: mysql:8.4.0",
                "tc.mysql:8.4.0 - Container mysql:8.4.0 started in PT0.24592S",
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

    @Test
    void basicTestResourcesSnapshotSuppressesAuthFallbackNoise() throws Exception {
        UiController controller = new UiController();
        PyronautDelegatingTuiCommand command = new PyronautDelegatingTuiCommand();
        setField(command, "controller", controller);

        Object connection = invokeConnection(
            command,
            "fromEnvironment",
            new Class<?>[]{java.util.Map.class},
            java.util.Map.of(
                "MICRONAUT_TEST_RESOURCES_SERVER_URI", "http://localhost:19090",
                "MICRONAUT_TEST_RESOURCES_SERVER_ACCESS_TOKEN", "token-456",
                "MICRONAUT_TEST_RESOURCES_SERVER_CLIENT_READ_TIMEOUT", "45"
            )
        );

        invoke(command, "showBasicTestResourcesSnapshot", new Class<?>[]{connection.getClass()}, connection);

        var snapshot = controller.getTestResourcesSnapshot();
        assertEquals(UiController.TestResourcesStatus.RUNNING, snapshot.status());
        assertEquals("CONNECTED @ http://localhost:19090", snapshot.healthMessage());
        assertTrue(snapshot.errors().isEmpty());
    }

    @Test
    void testResourcesLogsAreTailedFromFilesWithoutDuplicatingOldLines() throws Exception {
        Path project = tempDir.resolve("tail-logs");
        Path logsDir = project.resolve(".micronaut/test-resources/logs");
        Files.createDirectories(logsDir);
        Path logFile = logsDir.resolve("test-resources.log");
        Files.writeString(logFile, "first line\nsecond line\n", StandardCharsets.UTF_8);

        UiController controller = new UiController();
        PyronautDelegatingTuiCommand command = new PyronautDelegatingTuiCommand();
        setField(command, "controller", controller);
        setField(command, "testResourcesLogTail", new java.util.concurrent.atomic.AtomicReference<>(
            newInner(command, "TestResourcesLogTail", new Class<?>[]{Path.class}, project)
        ));

        invoke(command, "refreshTestResourcesLogs", new Class<?>[]{Path.class}, project);
        assertEquals(List.of("first line", "second line"), controller.getTestResourcesLogLines());

        Files.writeString(logFile, "first line\nsecond line\nthird line\n", StandardCharsets.UTF_8);
        invoke(command, "refreshTestResourcesLogs", new Class<?>[]{Path.class}, project);
        assertEquals(List.of("first line", "second line", "third line"), controller.getTestResourcesLogLines());
    }

    @Test
    void testResourcesLogsStripAnsiEscapeSequences() throws Exception {
        Path project = tempDir.resolve("tail-ansi-logs");
        Path logsDir = project.resolve(".micronaut/test-resources/logs");
        Files.createDirectories(logsDir);
        Path logFile = logsDir.resolve("test-resources.log");
        Files.writeString(logFile, "\u001B[31mERROR\u001B[0m container failed\n", StandardCharsets.UTF_8);

        UiController controller = new UiController();
        PyronautDelegatingTuiCommand command = new PyronautDelegatingTuiCommand();
        setField(command, "controller", controller);
        setField(command, "testResourcesLogTail", new java.util.concurrent.atomic.AtomicReference<>(
            newInner(command, "TestResourcesLogTail", new Class<?>[]{Path.class}, project)
        ));

        invoke(command, "refreshTestResourcesLogs", new Class<?>[]{Path.class}, project);
        assertEquals(List.of("ERROR container failed"), controller.getTestResourcesLogLines());
    }

    @Test
    void summarizeArrayPayloadParsesControlPanelContainersAndProperties() throws Exception {
        PyronautDelegatingTuiCommand command = new PyronautDelegatingTuiCommand();
        String dockerBody = """
            {
              "managedContainers":[
                {"scope":"datasources","id":"abc123","name":"mysql:8.4.0","network":"bridge","imageName":"mysql:8.4.0"}
              ],
              "startingContainers":["mysql:8.4.0"],
              "pullingContainers":["testcontainers/ryuk:0.13.0"]
            }
            """;
        String panelBody = """
            {
              "resolvedProperties":[
                {
                  "property":"datasources.default.url",
                  "resolvedValue":"jdbc:mysql://localhost:3306/default",
                  "properties":{"datasources":"default"},
                  "testResourcesConfig":{"enabled":"true"}
                }
              ],
              "errors":[
                {
                  "property":"datasources.default.password",
                  "stackTrace":"java.lang.IllegalStateException: boom\\n\\tat example.Test.main(Test.java:1)"
                }
              ]
            }
            """;

        @SuppressWarnings("unchecked")
        List<String> managedContainers = (List<String>) invoke(
            command,
            "summarizeArrayPayload",
            new Class<?>[]{String.class, String.class},
            dockerBody,
            "managedContainers"
        );
        @SuppressWarnings("unchecked")
        List<String> startingContainers = (List<String>) invoke(
            command,
            "summarizeArrayPayload",
            new Class<?>[]{String.class, String.class},
            dockerBody,
            "startingContainers"
        );
        @SuppressWarnings("unchecked")
        List<String> pullingContainers = (List<String>) invoke(
            command,
            "summarizeArrayPayload",
            new Class<?>[]{String.class, String.class},
            dockerBody,
            "pullingContainers"
        );
        @SuppressWarnings("unchecked")
        List<String> properties = (List<String>) invoke(
            command,
            "summarizeArrayPayload",
            new Class<?>[]{String.class, String.class},
            panelBody,
            "resolvedProperties"
        );
        @SuppressWarnings("unchecked")
        List<String> errors = (List<String>) invoke(
            command,
            "summarizeArrayPayload",
            new Class<?>[]{String.class, String.class},
            panelBody,
            "errors"
        );

        assertEquals(List.of("mysql:8.4.0 [running] image=mysql:8.4.0 scope=datasources id=abc123"), managedContainers);
        assertEquals(List.of("mysql:8.4.0"), startingContainers);
        assertEquals(List.of("testcontainers/ryuk:0.13.0"), pullingContainers);
        assertEquals(List.of("datasources.default.url=jdbc:mysql://localhost:3306/default"), properties);
        assertEquals(List.of("datasources.default.password [resolver] java.lang.IllegalStateException: boom"), errors);
    }

    @Test
    void buildManagedRunCommandUsesDirectJavaInvocationAndProjectClasspath() throws Exception {
        Path project = tempDir.resolve("managed-run");
        Files.createDirectories(project.resolve("__pyronaut__/classes"));
        Files.createDirectories(project.resolve("__pyronaut__"));
        Files.writeString(project.resolve("pyproject.toml"), """
            [project]
            name = "managed-run"

            [tool.pyronaut]
            """, StandardCharsets.UTF_8);
        Path runtimeJar = project.resolve("deps/runtime-one.jar").toAbsolutePath().normalize();
        Files.createDirectories(runtimeJar.getParent());
        Files.writeString(runtimeJar, "", StandardCharsets.UTF_8);
        Files.writeString(
            project.resolve("__pyronaut__/resolved-runtime-dependencies"),
            runtimeJar + System.lineSeparator(),
            StandardCharsets.UTF_8
        );
        Path developmentRuntimeJar = project.resolve("deps/runtime-dev.jar").toAbsolutePath().normalize();
        Files.writeString(developmentRuntimeJar, "", StandardCharsets.UTF_8);
        Files.writeString(
            project.resolve("__pyronaut__/resolved-development-runtime-dependencies"),
            developmentRuntimeJar + System.lineSeparator(),
            StandardCharsets.UTF_8
        );

        Path toolRoot = tempDir.resolve("tool/pyronaut-run");
        Path executable = toolRoot.resolve("bin/pyronaut-run");
        Path libDir = toolRoot.resolve("lib");
        Files.createDirectories(executable.getParent());
        Files.createDirectories(libDir);
        Files.writeString(executable, "#!/bin/sh\n", StandardCharsets.UTF_8);
        executable.toFile().setExecutable(true);
        Path toolJar = libDir.resolve("micronaut-pyronaut-run.jar").toAbsolutePath().normalize();
        Files.writeString(toolJar, "", StandardCharsets.UTF_8);

        PyronautDelegatingTuiCommand command = new PyronautDelegatingTuiCommand();
        setField(command, "runExecutable", executable);
        setField(command, "testExecutable", executable);

        Object target = enumConstant(command, "ManagedCommandTarget", "RUN");
        Object managedCommand = invoke(
            command,
            "buildManagedCommand",
            new Class<?>[]{Path.class, target.getClass()},
            project,
            target
        );
        @SuppressWarnings("unchecked")
        List<String> commandLine = (List<String>) invoke(managedCommand, "command", new Class<?>[]{});
        String managementServerUri = (String) invoke(managedCommand, "managementServerUri", new Class<?>[]{});

        assertTrue(commandLine.getFirst().endsWith("/bin/java") || "java".equals(commandLine.getFirst()));
        assertTrue(commandLine.contains("--sun-misc-unsafe-memory-access=allow"));
        assertTrue(commandLine.contains("--enable-native-access=ALL-UNNAMED"));
        assertTrue(commandLine.contains("-Dendpoints.all.enabled=true"));
        assertTrue(commandLine.contains("-Dendpoints.all.sensitive=false"));
        assertTrue(commandLine.contains("-Dendpoints.loggers.write-sensitive=false"));
        assertTrue(commandLine.contains("-Dmicronaut.control-panel.enabled=true"));
        assertTrue(commandLine.contains("-Dmicronaut.control-panel.path=/control-panel"));
        assertTrue(commandLine.contains("-Dmicronaut.control-panel.security.access=ANONYMOUS"));
        assertTrue(commandLine.contains("-Dmicronaut.environments=dev"));
        String managementPortArg = commandLine.stream()
            .filter(arg -> arg.startsWith("-Dendpoints.all.port="))
            .findFirst()
            .orElseThrow();
        assertTrue(managementServerUri.endsWith(managementPortArg.substring("-Dendpoints.all.port=".length())));
        assertTrue(commandLine.contains("io.micronaut.pyronaut.run.PyronautRunMain"));
        int cpIndex = commandLine.indexOf("-cp");
        assertTrue(cpIndex > 0);
        String classpath = commandLine.get(cpIndex + 1);
        assertTrue(classpath.contains(developmentRuntimeJar.toString()));
        assertFalse(classpath.contains(runtimeJar.toString()));
        assertTrue(classpath.contains(project.resolve("__pyronaut__/classes").toAbsolutePath().normalize().toString()));
        assertTrue(classpath.contains(toolJar.toString()));
    }

    @Test
    void buildManagedRunCommandOmitsControlPanelJvmArgsWhenDisabled() throws Exception {
        Path project = tempDir.resolve("managed-run-control-panel-disabled");
        Files.createDirectories(project.resolve("__pyronaut__/classes"));
        Files.createDirectories(project.resolve("__pyronaut__"));
        Files.writeString(project.resolve("pyproject.toml"), """
            [project]
            name = "managed-run-control-panel-disabled"

            [tool.pyronaut]

            [tool.pyronaut.control-panel]
            enabled = false
            """, StandardCharsets.UTF_8);
        Path developmentRuntimeJar = project.resolve("deps/runtime-dev.jar").toAbsolutePath().normalize();
        Files.createDirectories(developmentRuntimeJar.getParent());
        Files.writeString(developmentRuntimeJar, "", StandardCharsets.UTF_8);
        Files.writeString(
            project.resolve("__pyronaut__/resolved-development-runtime-dependencies"),
            developmentRuntimeJar + System.lineSeparator(),
            StandardCharsets.UTF_8
        );

        Path toolRoot = tempDir.resolve("tool/pyronaut-run-control-panel-disabled");
        Path executable = toolRoot.resolve("bin/pyronaut-run");
        Path libDir = toolRoot.resolve("lib");
        Files.createDirectories(executable.getParent());
        Files.createDirectories(libDir);
        Files.writeString(executable, "#!/bin/sh\n", StandardCharsets.UTF_8);
        executable.toFile().setExecutable(true);
        Files.writeString(libDir.resolve("micronaut-pyronaut-run.jar"), "", StandardCharsets.UTF_8);

        PyronautDelegatingTuiCommand command = new PyronautDelegatingTuiCommand();
        setField(command, "runExecutable", executable);
        setField(command, "testExecutable", executable);

        Object target = enumConstant(command, "ManagedCommandTarget", "RUN");
        Object managedCommand = invoke(
            command,
            "buildManagedCommand",
            new Class<?>[]{Path.class, target.getClass()},
            project,
            target
        );
        @SuppressWarnings("unchecked")
        List<String> commandLine = (List<String>) invoke(managedCommand, "command", new Class<?>[]{});

        assertTrue(commandLine.stream().noneMatch(arg -> arg.startsWith("-Dmicronaut.control-panel.")));
        assertTrue(commandLine.stream().noneMatch(arg -> arg.startsWith("-Dmicronaut.environments=")));
    }

    @Test
    void buildManagedNativeRunCommandUsesManifestProvidedClasspathFilter() throws Exception {
        Path project = tempDir.resolve("managed-run-native-filter");
        Files.createDirectories(project.resolve("__pyronaut__/classes"));
        Files.createDirectories(project.resolve("__pyronaut__"));
        Path runtimeJar = project.resolve("deps/micronaut-context-python-5.1.0.jar").toAbsolutePath().normalize();
        Path viewsJar = project.resolve("deps/micronaut-views-core-6.0.0.jar").toAbsolutePath().normalize();
        Path testResourcesClientJar = project.resolve("deps/micronaut-test-resources-client-4.0.0.jar").toAbsolutePath().normalize();
        Files.createDirectories(runtimeJar.getParent());
        Files.writeString(runtimeJar, "", StandardCharsets.UTF_8);
        Files.writeString(viewsJar, "", StandardCharsets.UTF_8);
        Files.writeString(testResourcesClientJar, "", StandardCharsets.UTF_8);
        Files.writeString(
            project.resolve("__pyronaut__/resolved-development-runtime-dependencies"),
            runtimeJar + System.lineSeparator() + viewsJar + System.lineSeparator() + testResourcesClientJar + System.lineSeparator(),
            StandardCharsets.UTF_8
        );

        Path nativeExecutable = tempDir.resolve("tool/pyronaut-dev/native/pyronaut-dev");
        Path libDir = tempDir.resolve("tool/pyronaut-dev/lib");
        Files.createDirectories(nativeExecutable.getParent());
        Files.createDirectories(libDir);
        Files.writeString(nativeExecutable, "#!/bin/sh\n", StandardCharsets.UTF_8);
        nativeExecutable.toFile().setExecutable(true);
        Files.writeString(nativeExecutable.getParent().resolve("native-provided-classpath.txt"), """
            micronaut-context-python-5.1.0.jar
            """, StandardCharsets.UTF_8);
        Files.writeString(libDir.resolve("micronaut-context-python-5.1.0.jar"), "", StandardCharsets.UTF_8);
        Files.writeString(libDir.resolve("micronaut-views-core-6.0.0.jar"), "", StandardCharsets.UTF_8);

        PyronautDelegatingTuiCommand command = new PyronautDelegatingTuiCommand();
        setField(command, "nativeDevExecutable", nativeExecutable);
        setField(command, "nativeCommands", Set.of("run"));

        Object target = enumConstant(command, "ManagedCommandTarget", "RUN");
        Object managedCommand = invoke(
            command,
            "buildManagedCommand",
            new Class<?>[]{Path.class, target.getClass()},
            project,
            target
        );
        @SuppressWarnings("unchecked")
        List<String> commandLine = (List<String>) invoke(managedCommand, "command", new Class<?>[]{});

        assertEquals(nativeExecutable.toString(), commandLine.getFirst());
        String classpath = commandLine.stream()
            .filter(arg -> arg.startsWith("-Djava.class.path="))
            .findFirst()
            .orElseThrow()
            .substring("-Djava.class.path=".length());
        assertFalse(classpath.contains(runtimeJar.toString()));
        assertTrue(classpath.contains(viewsJar.toString()));
        assertFalse(classpath.contains(testResourcesClientJar.toString()));
        assertTrue(classpath.contains(project.resolve("__pyronaut__/classes").toAbsolutePath().normalize().toString()));
        String testResourcesClientClasspath = commandLine.stream()
            .filter(arg -> arg.startsWith("-Dpyronaut.dev.test.resources.client.classpath="))
            .findFirst()
            .orElseThrow()
            .substring("-Dpyronaut.dev.test.resources.client.classpath=".length());
        assertTrue(testResourcesClientClasspath.contains(testResourcesClientJar.toString()));
    }

    @Test
    void buildManagedTestCommandUsesNativeExecutableWhenNoLibDirectoryExists() throws Exception {
        Path project = tempDir.resolve("managed-test-native");
        Files.createDirectories(project.resolve("__pyronaut__/test-classes"));
        Files.createDirectories(project.resolve("__pyronaut__"));
        Path runtimeJar = project.resolve("deps/runtime-one.jar").toAbsolutePath().normalize();
        Path testJar = project.resolve("deps/test-one.jar").toAbsolutePath().normalize();
        Path buildJar = project.resolve("deps/build-one.jar").toAbsolutePath().normalize();
        Files.createDirectories(runtimeJar.getParent());
        Files.writeString(runtimeJar, "", StandardCharsets.UTF_8);
        Files.writeString(testJar, "", StandardCharsets.UTF_8);
        Files.writeString(buildJar, "", StandardCharsets.UTF_8);
        Files.writeString(
            project.resolve("__pyronaut__/resolved-runtime-dependencies"),
            runtimeJar + System.lineSeparator(),
            StandardCharsets.UTF_8
        );
        Files.writeString(
            project.resolve("__pyronaut__/resolved-test-dependencies"),
            testJar + System.lineSeparator(),
            StandardCharsets.UTF_8
        );
        Files.writeString(
            project.resolve("__pyronaut__/resolved-build-dependencies"),
            buildJar + System.lineSeparator(),
            StandardCharsets.UTF_8
        );

        Path nativeExecutable = tempDir.resolve("tool/native/pyronaut-test");
        Files.createDirectories(nativeExecutable.getParent());
        Files.writeString(nativeExecutable, "#!/bin/sh\n", StandardCharsets.UTF_8);
        nativeExecutable.toFile().setExecutable(true);

        PyronautDelegatingTuiCommand command = new PyronautDelegatingTuiCommand();
        setField(command, "testExecutable", nativeExecutable);

        Object target = enumConstant(command, "ManagedCommandTarget", "TEST");
        Object managedCommand = invoke(
            command,
            "buildManagedCommand",
            new Class<?>[]{Path.class, target.getClass()},
            project,
            target
        );
        @SuppressWarnings("unchecked")
        List<String> commandLine = (List<String>) invoke(managedCommand, "command", new Class<?>[]{});

        assertEquals(List.of(nativeExecutable.toString(), "--project-dir", project.toString()), commandLine);
    }

    @Test
    void buildManagedTestCommandTreatsNativeLayoutAsNativeEvenWhenToolRootHasLibDirectory() throws Exception {
        Path project = tempDir.resolve("managed-test-native-layout");
        Files.createDirectories(project.resolve("__pyronaut__/test-classes"));
        Files.createDirectories(project.resolve("__pyronaut__"));
        Path runtimeJar = project.resolve("deps/runtime-one.jar").toAbsolutePath().normalize();
        Path testJar = project.resolve("deps/test-one.jar").toAbsolutePath().normalize();
        Path buildJar = project.resolve("deps/build-one.jar").toAbsolutePath().normalize();
        Files.createDirectories(runtimeJar.getParent());
        Files.writeString(runtimeJar, "", StandardCharsets.UTF_8);
        Files.writeString(testJar, "", StandardCharsets.UTF_8);
        Files.writeString(buildJar, "", StandardCharsets.UTF_8);
        Files.writeString(
            project.resolve("__pyronaut__/resolved-runtime-dependencies"),
            runtimeJar + System.lineSeparator(),
            StandardCharsets.UTF_8
        );
        Files.writeString(
            project.resolve("__pyronaut__/resolved-test-dependencies"),
            testJar + System.lineSeparator(),
            StandardCharsets.UTF_8
        );
        Files.writeString(
            project.resolve("__pyronaut__/resolved-build-dependencies"),
            buildJar + System.lineSeparator(),
            StandardCharsets.UTF_8
        );

        Path toolRoot = tempDir.resolve("tool/pyronaut-test");
        Path nativeExecutable = toolRoot.resolve("native/pyronaut-test");
        Path libDir = toolRoot.resolve("lib");
        Path binDir = toolRoot.resolve("bin");
        Files.createDirectories(nativeExecutable.getParent());
        Files.createDirectories(libDir);
        Files.createDirectories(binDir);
        Files.writeString(nativeExecutable, "#!/bin/sh\n", StandardCharsets.UTF_8);
        nativeExecutable.toFile().setExecutable(true);
        Files.writeString(libDir.resolve("micronaut-pyronaut-test.jar"), "", StandardCharsets.UTF_8);
        Files.writeString(binDir.resolve("pyronaut-test"), "#!/bin/sh\n", StandardCharsets.UTF_8);

        PyronautDelegatingTuiCommand command = new PyronautDelegatingTuiCommand();
        setField(command, "testExecutable", nativeExecutable);

        Object target = enumConstant(command, "ManagedCommandTarget", "TEST");
        Object managedCommand = invoke(
            command,
            "buildManagedCommand",
            new Class<?>[]{Path.class, target.getClass()},
            project,
            target
        );
        @SuppressWarnings("unchecked")
        List<String> commandLine = (List<String>) invoke(managedCommand, "command", new Class<?>[]{});

        assertEquals(List.of(nativeExecutable.toString(), "--project-dir", project.toString()), commandLine);
    }

    @Test
    void refreshApplicationEndpointsLoadsRoutesIntoController() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/routes", exchange -> {
            byte[] body = """
                [
                  {"method":"GET","uri":"/hello/{name}"},
                  {"method":"GET","uri":"/control-panel"},
                  {"method":"HEAD","uri":"/control-panel"},
                  {"method":"POST","uri":"/control-panel/application-control-panel-controller/refresh"},
                  {"method":"POST","uri":"/control-panel/application-control-panel-controller/stop"},
                  {"method":"GET","uri":"/control-panel/categories/{categoryId}"},
                  {"method":"HEAD","uri":"/control-panel/categories/{categoryId}"},
                  {"method":"POST","uri":"/control-panel/loggers-control-panel-controller/{logger}"},
                  {"method":"GET","uri":"/control-panel/{controlPanelName}"},
                  {"method":"HEAD","uri":"/control-panel/{controlPanelName}"},
                  {"method":"POST","uri":"/books"},
                  {"method":"GET","uri":"/hello/{name}"}
                ]
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            UiController controller = new UiController();
            PyronautDelegatingTuiCommand command = new PyronautDelegatingTuiCommand();
            setField(command, "controller", controller);

            boolean refreshed = (boolean) invoke(
                command,
                "refreshApplicationEndpoints",
                new Class<?>[]{String.class},
                "http://127.0.0.1:" + server.getAddress().getPort()
            );

            assertTrue(refreshed);
            assertEquals(List.of("GET  /hello/{name}", "POST /books"), controller.getEndpoints());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refreshApplicationEndpointsLoadsRoutesFromMicronautObjectPayload() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/routes", exchange -> {
            byte[] body = """
                {
                  "{[/hello/{name}],method=[GET],produces=[application/json]}":{"method":"java.util.Map helloworld.Controller.hello()"},
                  "{[/health],method=[GET],produces=[application/json]}":{"method":"java.lang.Object io.micronaut.management.endpoint.health.HealthEndpoint.getHealth()"},
                  "{[/control-panel],method=[GET],produces=[text/html]}":{"method":"java.lang.Object io.micronaut.controlpanel.ControlPanelController.index()"},
                  "{[/control-panel],method=[HEAD],produces=[text/html]}":{"method":"java.lang.Object io.micronaut.controlpanel.ControlPanelController.index()"},
                  "{[/control-panel/application-control-panel-controller/refresh],method=[POST],produces=[application/json]}":{"method":"java.lang.Object io.micronaut.controlpanel.ApplicationControlPanelController.refresh()"},
                  "{[/control-panel/application-control-panel-controller/stop],method=[POST],produces=[application/json]}":{"method":"java.lang.Object io.micronaut.controlpanel.ApplicationControlPanelController.stop()"},
                  "{[/control-panel/categories/{categoryId}],method=[GET],produces=[text/html]}":{"method":"java.lang.Object io.micronaut.controlpanel.ControlPanelController.category()"},
                  "{[/control-panel/categories/{categoryId}],method=[HEAD],produces=[text/html]}":{"method":"java.lang.Object io.micronaut.controlpanel.ControlPanelController.category()"},
                  "{[/control-panel/loggers-control-panel-controller/{logger}],method=[POST],produces=[application/json]}":{"method":"java.lang.Object io.micronaut.controlpanel.LoggersControlPanelController.setLogger()"},
                  "{[/control-panel/{controlPanelName}],method=[GET],produces=[text/html]}":{"method":"java.lang.Object io.micronaut.controlpanel.ControlPanelController.panel()"},
                  "{[/control-panel/{controlPanelName}],method=[HEAD],produces=[text/html]}":{"method":"java.lang.Object io.micronaut.controlpanel.ControlPanelController.panel()"},
                  "{[/books],method=[POST],produces=[application/json]}":{"method":"java.lang.Object helloworld.BookController.create()"}
                }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            UiController controller = new UiController();
            PyronautDelegatingTuiCommand command = new PyronautDelegatingTuiCommand();
            setField(command, "controller", controller);

            boolean refreshed = (boolean) invoke(
                command,
                "refreshApplicationEndpoints",
                new Class<?>[]{String.class},
                "http://127.0.0.1:" + server.getAddress().getPort()
            );

            assertTrue(refreshed);
            assertEquals(List.of("GET  /hello/{name}", "POST /books"), controller.getEndpoints());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refreshApplicationEndpointsLinksHealthStatusToControlPanelWhenEnabled() throws Exception {
        Path project = tempDir.resolve("control-panel-link-enabled");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), """
            [project]
            name = "control-panel-link-enabled"

            [tool.pyronaut]

            [tool.pyronaut.control-panel]
            path = "/dev/panel"
            """, StandardCharsets.UTF_8);

        HttpServer server = healthRoutesServer();
        server.start();
        try {
            UiController controller = new UiController();
            PyronautDelegatingTuiCommand command = new PyronautDelegatingTuiCommand();
            setField(command, "controller", controller);
            setField(command, "currentProject", project);

            String serverUri = "http://127.0.0.1:" + server.getAddress().getPort();
            boolean refreshed = (boolean) invoke(
                command,
                "refreshApplicationEndpoints",
                new Class<?>[]{String.class},
                serverUri
            );

            assertTrue(refreshed);
            assertEquals("UP", controller.getManagementHealthStatus());
            assertEquals(serverUri + "/dev/panel", controller.getManagementHealthUrl());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refreshApplicationEndpointsLinksHealthStatusToHealthEndpointWhenControlPanelDisabled() throws Exception {
        Path project = tempDir.resolve("control-panel-link-disabled");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pyproject.toml"), """
            [project]
            name = "control-panel-link-disabled"

            [tool.pyronaut]

            [tool.pyronaut.control-panel]
            enabled = false
            """, StandardCharsets.UTF_8);

        HttpServer server = healthRoutesServer();
        server.start();
        try {
            UiController controller = new UiController();
            PyronautDelegatingTuiCommand command = new PyronautDelegatingTuiCommand();
            setField(command, "controller", controller);
            setField(command, "currentProject", project);

            String serverUri = "http://127.0.0.1:" + server.getAddress().getPort();
            boolean refreshed = (boolean) invoke(
                command,
                "refreshApplicationEndpoints",
                new Class<?>[]{String.class},
                serverUri
            );

            assertTrue(refreshed);
            assertEquals("UP", controller.getManagementHealthStatus());
            assertEquals(serverUri + "/health", controller.getManagementHealthUrl());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void hasReachableTestResourcesServerAcceptsLiveSocketBackedSettings() throws Exception {
        Path project = tempDir.resolve("reachable-settings");
        Path settingsDir = project.resolve(".micronaut/test-resources");
        Files.createDirectories(settingsDir);

        try (ServerSocket serverSocket = new ServerSocket(0)) {
            Files.writeString(
                settingsDir.resolve("test-resources.properties"),
                "server.uri=http\\://localhost\\:" + serverSocket.getLocalPort() + "\n"
                    + "server.access.token=token-123\n",
                StandardCharsets.UTF_8
            );

            PyronautDelegatingTuiCommand command = new PyronautDelegatingTuiCommand();

            assertTrue(command.hasReachableTestResourcesServer(settingsDir.resolve("test-resources.properties")));
        }
    }

    private static HttpServer healthRoutesServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/routes", exchange -> {
            byte[] body = "[{\"method\":\"GET\",\"uri\":\"/hello\"}]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.createContext("/health", exchange -> {
            byte[] body = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        return server;
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

    private static Object newInner(Object target, String simpleName, Class<?>[] parameterTypes, Object... args) throws Exception {
        for (Class<?> innerClass : target.getClass().getDeclaredClasses()) {
            if (innerClass.getSimpleName().equals(simpleName)) {
                Class<?>[] ctorTypes = new Class<?>[parameterTypes.length + 1];
                Object[] ctorArgs = new Object[args.length + 1];
                ctorTypes[0] = target.getClass();
                ctorArgs[0] = target;
                System.arraycopy(parameterTypes, 0, ctorTypes, 1, parameterTypes.length);
                System.arraycopy(args, 0, ctorArgs, 1, args.length);
                var constructor = innerClass.getDeclaredConstructor(ctorTypes);
                constructor.setAccessible(true);
                return constructor.newInstance(ctorArgs);
            }
        }
        throw new NoSuchMethodException(simpleName);
    }

    private static Object enumConstant(Object target, String simpleName, String constant) {
        for (Class<?> innerClass : target.getClass().getDeclaredClasses()) {
            if (innerClass.getSimpleName().equals(simpleName) && innerClass.isEnum()) {
                @SuppressWarnings({"rawtypes", "unchecked"})
                Object value = Enum.valueOf((Class<? extends Enum>) innerClass.asSubclass(Enum.class), constant);
                return value;
            }
        }
        throw new IllegalArgumentException(simpleName);
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
                builder.environment().put("MICRONAUT_TEST_RESOURCES_SERVER_URI", properties.getProperty("server.uri"));
                builder.environment().put("MICRONAUT_TEST_RESOURCES_SERVER_ACCESS_TOKEN", properties.getProperty("server.access.token"));
                builder.environment().put("MICRONAUT_TEST_RESOURCES_SERVER_CLIENT_READ_TIMEOUT", properties.getProperty("server.client.read.timeout"));
            }
            return builder.start();
        }
    }
}

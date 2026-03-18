/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package io.micronaut.pyronaut.testresources;

import io.micronaut.pyronaut.config.model.PyprojectModel;
import io.micronaut.pyronaut.config.model.PyprojectModelException;
import io.micronaut.pyronaut.config.model.PyprojectModelReader;
import io.micronaut.testresources.buildtools.ServerSettings;
import io.micronaut.testresources.buildtools.ServerUtils;
import picocli.CommandLine;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "pyronaut-test-resources-server", mixinStandardHelpOptions = true, description = "Manage standalone Micronaut test resources server")
public final class PyronautTestResourcesServerMain implements Callable<Integer> {
    private static final int SUCCESS = 0;
    private static final int PRECONDITION_FAILED = 8;
    private static final int INTERNAL_ERROR = 10;

    @CommandLine.Option(names = "--project-dir", defaultValue = ".", description = "Project directory")
    Path projectDir = Path.of(".");

    @CommandLine.Parameters(index = "0", arity = "0..1", defaultValue = "start", description = "Action: start|stop|status")
    String action = "start";

    private final PyprojectModelReader modelReader;
    private final ServerManager serverManager;

    public PyronautTestResourcesServerMain() {
        this(new PyprojectModelReader(), new DefaultServerManager());
    }

    PyronautTestResourcesServerMain(PyprojectModelReader modelReader, ServerManager serverManager) {
        this.modelReader = modelReader;
        this.serverManager = serverManager;
    }

    public static void main(String[] args) {
        int exit = new CommandLine(new PyronautTestResourcesServerMain()).execute(args);
        if (exit != 0) {
            System.exit(exit);
        }
    }

    @Override
    public Integer call() {
        Path root = projectDir.toAbsolutePath().normalize();
        try {
            PyprojectModel model = modelReader.readProjectDirectory(root);
            PyprojectModel.TestResources config = model.pyronaut().testResources();
            Path settingsDir = resolveSettingsDir(root, config);
            Path portFile = settingsDir.resolve("server.port");

            return switch (normalizeAction(action)) {
                case START -> start(root, settingsDir, portFile, config);
                case STATUS -> status(settingsDir);
                case STOP -> stop(settingsDir);
            };
        } catch (PyprojectModelException e) {
            System.err.println(e.getMessage());
            return PRECONDITION_FAILED;
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
            return PRECONDITION_FAILED;
        } catch (Exception e) {
            System.err.println("test-resources-server failed: " + e.getMessage());
            return INTERNAL_ERROR;
        }
    }

    private Integer start(Path root, Path settingsDir, Path portFile, PyprojectModel.TestResources config) throws IOException {
        if (config.enabled() != null && !config.enabled()) {
            System.out.println("Test resources server is disabled by configuration.");
            return SUCCESS;
        }
        OptimizationResolution optimization = resolveOptimization(root, config);
        optimization.warning().ifPresent(warning -> System.err.println(warning));

        ServerStartRequest request = new ServerStartRequest(
            settingsDir,
            portFile,
            config.explicitPort(),
            accessToken(config),
            optimization.cdsDirectory().orElse(null),
            config.clientTimeout(),
            config.serverIdleTimeoutMinutes(),
            config.serverSystemProperties(),
            config.serverEnvironment(),
            config.debugServer() != null && config.debugServer(),
            config.javaExecutable()
        );
        ServerStatus started = serverManager.start(request);
        System.out.println("running uri=" + started.uri() + " port=" + started.port());
        return SUCCESS;
    }

    private Integer status(Path settingsDir) {
        ServerStatus status = serverManager.status(settingsDir);
        if (status.running()) {
            System.out.println("running uri=" + status.uri() + " port=" + status.port());
        } else {
            System.out.println("stopped");
        }
        return SUCCESS;
    }

    private Integer stop(Path settingsDir) throws IOException {
        boolean stopped = serverManager.stop(settingsDir);
        System.out.println(stopped ? "stopped" : "already-stopped");
        return SUCCESS;
    }

    private static Path resolveSettingsDir(Path root, PyprojectModel.TestResources config) {
        boolean shared = config.sharedServer() != null && config.sharedServer();
        if (shared) {
            return ServerUtils.getDefaultSharedSettingsPath(config.sharedServerNamespace());
        }
        return root.resolve(".micronaut/test-resources").normalize();
    }

    private static String accessToken(PyprojectModel.TestResources config) {
        boolean shared = config.sharedServer() != null && config.sharedServer();
        if (shared && config.explicitPort() != null) {
            return null;
        }
        return UUID.randomUUID().toString();
    }

    private static OptimizationResolution resolveOptimization(Path root, PyprojectModel.TestResources config) {
        String mode = config.startupOptimization() == null ? "auto" : config.startupOptimization().trim().toLowerCase(java.util.Locale.ROOT);
        Path cdsDir = root.resolve("__pyronaut__/test-resources-cds").normalize();
        List<String> leydenArgs = config.leydenJvmArgs() == null ? List.of() : config.leydenJvmArgs();
        return switch (mode) {
            case "none" -> new OptimizationResolution(Optional.empty(), Optional.empty());
            case "cds" -> new OptimizationResolution(Optional.of(cdsDir), Optional.empty());
            case "leyden" -> new OptimizationResolution(Optional.of(cdsDir), Optional.of("startupOptimization=leyden unsupported here; falling back to CDS/plain startup"));
            case "auto" -> {
                if (!leydenArgs.isEmpty()) {
                    yield new OptimizationResolution(Optional.of(cdsDir), Optional.of("startupOptimization=auto attempted Leyden args but will fall back to CDS/plain when unsupported"));
                }
                yield new OptimizationResolution(Optional.of(cdsDir), Optional.empty());
            }
            default -> throw new IllegalStateException("Invalid startupOptimization: " + mode);
        };
    }

    private static Action normalizeAction(String raw) {
        if (raw == null || raw.isBlank()) {
            return Action.START;
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "start" -> Action.START;
            case "stop" -> Action.STOP;
            case "status" -> Action.STATUS;
            default -> throw new IllegalStateException("Invalid action: " + raw + ". Use start|stop|status");
        };
    }

    enum Action {
        START,
        STOP,
        STATUS
    }

    interface ServerManager {
        ServerStatus start(ServerStartRequest request) throws IOException;

        ServerStatus status(Path settingsDir);

        boolean stop(Path settingsDir) throws IOException;
    }

    record ServerStartRequest(Path settingsDir,
                              Path portFile,
                              Integer explicitPort,
                              String accessToken,
                              Path cdsDir,
                              Integer clientTimeout,
                              Integer idleTimeoutMinutes,
                              java.util.Map<String, String> systemProperties,
                              java.util.Map<String, String> environment,
                              boolean debugServer,
                              String javaExecutable) {
    }

    record ServerStatus(boolean running, int port, String uri) {
    }

    record OptimizationResolution(Optional<Path> cdsDirectory, Optional<String> warning) {
    }

    static final class DefaultServerManager implements ServerManager {
        @Override
        public ServerStatus start(ServerStartRequest request) throws IOException {
            Files.createDirectories(request.settingsDir());
            Optional<ServerSettings> existing = ServerUtils.readServerSettings(request.settingsDir());
            if (existing.isPresent() && ServerUtils.isServerStarted(existing.get().getPort())) {
                int port = existing.get().getPort();
                return new ServerStatus(true, port, "http://localhost:" + port);
            }
            if (existing.isPresent()) {
                Files.deleteIfExists(request.settingsDir().resolve(ServerUtils.PROPERTIES_FILE_NAME));
            }
            Files.deleteIfExists(request.portFile());

            int port = request.explicitPort() != null ? request.explicitPort() : findRandomPort();
            FallbackTestResourcesServerLauncher.launch(request, port);
            waitForServerPort(port, Duration.ofSeconds(15));
            ServerUtils.writeServerSettings(request.settingsDir(), new ServerSettings(
                port,
                request.accessToken(),
                request.clientTimeout(),
                request.idleTimeoutMinutes()
            ));
            return new ServerStatus(true, port, "http://localhost:" + port);
        }

        @Override
        public ServerStatus status(Path settingsDir) {
            Optional<ServerSettings> settings = ServerUtils.readServerSettings(settingsDir);
            if (settings.isEmpty()) {
                return new ServerStatus(false, -1, "");
            }
            int port = settings.get().getPort();
            boolean running = ServerUtils.isServerStarted(port);
            return new ServerStatus(running, port, running ? "http://localhost:" + port : "");
        }

        @Override
        public boolean stop(Path settingsDir) throws IOException {
            Optional<ServerSettings> settings = ServerUtils.readServerSettings(settingsDir);
            if (settings.isEmpty()) {
                return false;
            }
            ServerUtils.stopServer(settingsDir);
            return true;
        }

        private static int findRandomPort() throws IOException {
            try (ServerSocket socket = new ServerSocket()) {
                socket.bind(new InetSocketAddress("127.0.0.1", 0));
                return socket.getLocalPort();
            }
        }

        private static void waitForServerPort(int port, Duration timeout) {
            long endNanos = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < endNanos) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
                    return;
                } catch (IOException ignored) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            throw new IllegalStateException("Port file not created. Server probably failed to start.");
        }
    }
}

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
package io.micronaut.pyronaut.testresources;

import io.micronaut.pyronaut.config.model.PyprojectModel;
import io.micronaut.pyronaut.config.model.PyprojectModelException;
import io.micronaut.pyronaut.config.model.PyprojectModelReader;
import io.micronaut.testresources.buildtools.ServerFactory;
import io.micronaut.testresources.buildtools.ServerSettings;
import io.micronaut.testresources.buildtools.ServerUtils;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@CommandLine.Command(name = "pyronaut-test-resources-server", mixinStandardHelpOptions = true, description = "Manage standalone Micronaut test resources server")
public final class PyronautTestResourcesServerMain implements Callable<Integer> {
    private static final int SUCCESS = 0;
    private static final int PRECONDITION_FAILED = 8;
    private static final int INTERNAL_ERROR = 10;
    private static final String SERVER_CLASSPATH_MANIFEST = "__pyronaut__/resolved-test-resources-server-dependencies";
    private static final String OWNED_SESSION_FILE = "__pyronaut__/test-resources-session.json";
    private static final Pattern OWNER_TOKEN_PATTERN = Pattern.compile("\\\"ownerToken\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    @CommandLine.Option(names = "--project-dir", defaultValue = ".", description = "Project directory")
    Path projectDir = Path.of(".");

    @CommandLine.Parameters(index = "0", arity = "0..1", defaultValue = "start", description = "Action: start|stop|status")
    String action = "start";

    @CommandLine.Option(names = "--owner-token", hidden = true, description = "Internal orchestration ownership token")
    String ownerToken;

    private final PyprojectModelReader modelReader;
    private final ServerManager serverManager;
    private final Function<String, String> envReader;

    public PyronautTestResourcesServerMain() {
        this(new PyprojectModelReader(), new DefaultServerManager(), System::getenv);
    }

    PyronautTestResourcesServerMain(PyprojectModelReader modelReader, ServerManager serverManager) {
        this(modelReader, serverManager, System::getenv);
    }

    PyronautTestResourcesServerMain(PyprojectModelReader modelReader,
                                    ServerManager serverManager,
                                    Function<String, String> envReader) {
        this.modelReader = modelReader;
        this.serverManager = serverManager;
        this.envReader = envReader;
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
            Path sessionFile = root.resolve(OWNED_SESSION_FILE).toAbsolutePath().normalize();

            return switch (normalizeAction(action)) {
                case START -> start(root, settingsDir, portFile, config);
                case STATUS -> status(settingsDir);
                case STOP -> stop(settingsDir, sessionFile);
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
        if (isTestResourcesDisabledViaEnvironment()) {
            System.out.println("Test resources server startup skipped (PYRONAUT_TEST_RESOURCES_DISABLED=true).");
            return SUCCESS;
        }
        if (config.enabled() != null && !config.enabled()) {
            System.out.println("Test resources server is disabled by configuration.");
            return SUCCESS;
        }
        OptimizationResolution optimization = resolveOptimization(root, config);
        optimization.warning().ifPresent(warning -> System.err.println(warning));

        ServerStartRequest request = new ServerStartRequest(
            settingsDir,
            portFile,
            root.resolve(SERVER_CLASSPATH_MANIFEST).toAbsolutePath().normalize(),
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

    private Integer stop(Path settingsDir, Path sessionFile) throws IOException {
        if (!matchesOwningSession(sessionFile)) {
            System.out.println("stop-skipped-ownership-mismatch");
            return SUCCESS;
        }
        boolean stopped = serverManager.stop(settingsDir);
        Files.deleteIfExists(sessionFile);
        deleteServerSettings(settingsDir);
        System.out.println(stopped ? "stopped" : "already-stopped");
        return SUCCESS;
    }

    private boolean matchesOwningSession(Path sessionFile) {
        String configuredToken = ownerToken == null ? "" : ownerToken.trim();
        if (configuredToken.isEmpty()) {
            return true;
        }
        if (!Files.exists(sessionFile)) {
            return false;
        }
        String persistedToken = readOwnerToken(sessionFile).orElse("");
        return !persistedToken.isBlank() && configuredToken.equals(persistedToken);
    }

    private static void deleteServerSettings(Path settingsDir) {
        try {
            Files.deleteIfExists(settingsDir.resolve("server.port"));
            Files.deleteIfExists(settingsDir.resolve("test-resources.properties"));
            if (Files.exists(settingsDir) && Files.isDirectory(settingsDir)) {
                try (var entries = Files.list(settingsDir)) {
                    if (!entries.findAny().isPresent()) {
                        Files.deleteIfExists(settingsDir);
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static Optional<String> readOwnerToken(Path sessionFile) {
        try {
            String content = Files.readString(sessionFile);
            Matcher matcher = OWNER_TOKEN_PATTERN.matcher(content);
            if (matcher.find()) {
                return Optional.ofNullable(matcher.group(1));
            }
        } catch (IOException ignored) {
            return Optional.empty();
        }
        return Optional.empty();
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

    private boolean isTestResourcesDisabledViaEnvironment() {
        String value = envReader.apply("PYRONAUT_TEST_RESOURCES_DISABLED");
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("1")
            || normalized.equals("true")
            || normalized.equals("yes")
            || normalized.equals("on");
    }

    private static OptimizationResolution resolveOptimization(Path root, PyprojectModel.TestResources config) {
        String mode = config.startupOptimization() == null ? "auto" : config.startupOptimization().trim().toLowerCase(java.util.Locale.ROOT);
        if (!config.configured()) {
            mode = "none";
        }
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
                              Path classpathManifest,
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
        private final Function<ServerStartRequest, ServerFactory> serverFactoryFactory;

        DefaultServerManager() {
            this(RealTestResourcesServerFactory::new);
        }

        DefaultServerManager(Function<ServerStartRequest, ServerFactory> serverFactoryFactory) {
            this.serverFactoryFactory = serverFactoryFactory;
        }

        @Override
        public ServerStatus start(ServerStartRequest request) throws IOException {
            Files.createDirectories(request.settingsDir());
            ServerFactory serverFactory = serverFactoryFactory.apply(request);
            ServerSettings settings = ServerUtils.startOrConnectToExistingServer(
                request.explicitPort(),
                request.portFile(),
                request.settingsDir(),
                request.accessToken(),
                request.cdsDir(),
                classpathEntries(request.classpathManifest()),
                request.clientTimeout(),
                request.idleTimeoutMinutes(),
                serverFactory
            );
            return new ServerStatus(true, settings.getPort(), "http://localhost:" + settings.getPort());
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

        private static List<File> classpathEntries(Path manifestPath) throws IOException {
            if (manifestPath == null || !Files.exists(manifestPath)) {
                throw new IllegalStateException(
                    "Missing test resources server classpath manifest: " + (manifestPath == null ? "<null>" : manifestPath.toAbsolutePath())
                );
            }
            LinkedHashSet<File> entries = new LinkedHashSet<>();
            for (String line : Files.readAllLines(manifestPath, java.nio.charset.StandardCharsets.UTF_8)) {
                String value = line == null ? "" : line.trim();
                if (value.isEmpty()) {
                    continue;
                }
                entries.add(Path.of(value).toAbsolutePath().normalize().toFile());
            }
            for (String value : RealTestResourcesServerFactory.selfModuleClasspathEntries()) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                entries.add(Path.of(value).toAbsolutePath().normalize().toFile());
            }
            return List.copyOf(entries);
        }
    }
}

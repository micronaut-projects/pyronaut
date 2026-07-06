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
package io.micronaut.pyronaut.tui.commands;

import io.micronaut.python.cli.ui.Mode;
import io.micronaut.python.cli.ui.PyronautTui;
import io.micronaut.python.cli.ui.StreamsCapture;
import io.micronaut.python.cli.ui.UiController;
import io.micronaut.python.cli.ui.UiModel;
import io.micronaut.pyronaut.config.model.PyprojectModel;
import io.micronaut.pyronaut.config.model.PyprojectModelReader;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

@SuppressWarnings({"checkstyle:FileLength", "checkstyle:InnerTypeLast"})
@Command(name = "delegating-tui", description = "Runs the Tamboui TUI delegated to v2 standalone commands", mixinStandardHelpOptions = true)
public final class PyronautDelegatingTuiCommand implements Callable<Integer> {

    private static final int PRECONDITION_FAILED = 8;
    private static final String EVENTS_REPORT = "events.ndjson";
    private static final String TUI_LOG = "pyronaut-tui.log";
    private static final String RUNTIME_DEPENDENCIES = "__pyronaut__/resolved-runtime-dependencies";
    private static final String DEVELOPMENT_RUNTIME_DEPENDENCIES = "__pyronaut__/resolved-development-runtime-dependencies";
    private static final String BUILD_DEPENDENCIES = "__pyronaut__/resolved-build-dependencies";
    private static final String TEST_DEPENDENCIES = "__pyronaut__/resolved-test-dependencies";
    private static final String CLASSES_DIR = "__pyronaut__/classes";
    private static final String TEST_CLASSES_DIR = "__pyronaut__/test-classes";
    private static final String TEST_RESOURCES_PROPERTIES = ".micronaut/test-resources/test-resources.properties";
    private static final String TEST_RESOURCES_LOGS_DIR = ".micronaut/test-resources/logs";
    private static final String RUN_MAIN_CLASS = "io.micronaut.pyronaut.run.PyronautRunMain";
    private static final String TEST_MAIN_CLASS = "io.micronaut.pyronaut.test.PyronautTestMain";
    private static final String CONTROL_PANEL_ENABLED_PROPERTY = "micronaut.control-panel.enabled";
    private static final String CONTROL_PANEL_PATH_PROPERTY = "micronaut.control-panel.path";
    private static final String CONTROL_PANEL_SECURITY_ACCESS_PROPERTY = "micronaut.control-panel.security.access";
    private static final String MICRONAUT_ENVIRONMENTS_PROPERTY = "micronaut.environments";
    private static final String MICRONAUT_ENVIRONMENTS_ENV = "MICRONAUT_ENVIRONMENTS";
    private static final String DEVELOPMENT_ENVIRONMENT = "dev";
    private static final List<String> DELEGATE_JVM_FLAGS = List.of(
        "--sun-misc-unsafe-memory-access=allow",
        "--enable-native-access=ALL-UNNAMED"
    );
    private static final List<String> DEV_MANAGEMENT_JVM_FLAGS = List.of(
        "-Dendpoints.all.enabled=true",
        "-Dendpoints.all.sensitive=false",
        "-Dendpoints.loggers.write-sensitive=false"
    );
    private static final String MANAGEMENT_METHOD_PREFIX = "io.micronaut.management.endpoint.";
    private static final long WATCH_DEBOUNCE_MILLIS = 250;
    private static final long TEST_RESOURCES_POLL_MILLIS = 2000;
    private static final long TEST_RESOURCES_MAX_BACKOFF_MILLIS = 8000;
    private static final Duration TEST_RESOURCES_SETTINGS_TIMEOUT = Duration.ofSeconds(15);
    private static final Pattern SERVER_URI = Pattern.compile("Server Running:\\s*(\\S+)");
    private static final String TEST_RESOURCES_IMAGE_PULL_MARKER = "Pulling docker image:";
    private static final String TEST_RESOURCES_CONTAINER_CREATE_MARKER = "Creating container for image:";
    private static final String TEST_RESOURCES_CONTAINER_STARTED_MARKER = " started in PT";
    private static final Pattern ANSI_ESCAPE_PATTERN = Pattern.compile("\\u001B\\[[;?0-9]*[ -/]*[@-~]");

    @Option(names = "--project-dir", required = true, description = "Project directory")
    Path projectDir;

    @Option(names = "--mode", defaultValue = "run", description = "Initial mode: run|test")
    String mode;

    @Option(names = "--report-dir", description = "Path to pyronaut-test report directory")
    Path reportDir;

    @Option(names = "--validate-executable", required = true, description = "Path to pyronaut-validate-config executable")
    Path validateExecutable;

    @Option(names = "--install-executable", required = true, description = "Path to pyronaut-install executable")
    Path installExecutable;

    @Option(names = "--process-executable", required = true, description = "Path to pyronaut-processor executable")
    Path processExecutable;

    @Option(names = "--run-executable", required = true, description = "Path to pyronaut-run executable")
    Path runExecutable;

    @Option(names = "--test-executable", required = true, description = "Path to pyronaut-test executable")
    Path testExecutable;

    @Option(names = "--native-dev-executable", description = "Path to pyronaut-dev native executable")
    Path nativeDevExecutable;

    @Option(names = "--native-command", split = ",", description = "Lifecycle command to route through pyronaut-dev")
    Set<String> nativeCommands = Set.of();

    @Option(names = "--trace-delegation", description = "Log delegated command lines")
    boolean traceDelegation;

    private final ExecutorService controlExecutor = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
    private final AtomicReference<ManagedProcess> activeProcess = new AtomicReference<>();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final AtomicLong executionGeneration = new AtomicLong();
    private final AtomicReference<WatchLoop> watchLoop = new AtomicReference<>();
    private final AtomicReference<TestResourcesPoller> testResourcesPoller = new AtomicReference<>();
    private final AtomicReference<TestResourcesLogTail> testResourcesLogTail = new AtomicReference<>();
    private final AtomicReference<TestResourcesConnection> activeTestResourcesConnection = new AtomicReference<>();
    private final AtomicBoolean testResourcesRunningNotified = new AtomicBoolean(false);
    private final HttpClient insightsClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final PyprojectModelReader modelReader;

    private UiController controller;
    private PyronautTui tui;
    private volatile Mode activeMode;
    private Path currentProject;
    private Path currentReportDir;
    private Path currentLogFile;

    public PyronautDelegatingTuiCommand() {
        this(new PyprojectModelReader());
    }

    PyronautDelegatingTuiCommand(PyprojectModelReader modelReader) {
        this.modelReader = modelReader;
    }

    @Override
    public Integer call() throws Exception {
        if (System.console() == null) {
            System.err.println("pyronaut --tui requires a real interactive terminal. Run it directly from your shell.");
            return PRECONDITION_FAILED;
        }

        var resolvedProject = projectDir.toAbsolutePath().normalize();
        var resolvedReportDir = reportDir == null
                ? resolvedProject.resolve("__pyronaut__/reports/tests")
                : reportDir.toAbsolutePath().normalize();
        var initialMode = parseMode(mode);
        currentLogFile = resolvedProject.resolve("__pyronaut__/logs").resolve(TUI_LOG);
        Files.createDirectories(currentLogFile.getParent());

        StreamsCapture.installGlobal();
        tui = new PyronautTui();
        controller = tui.getController();
        tui.setMode(initialMode);
        activeMode = initialMode;
        startTestResourcesPolling(resolvedProject);

        tui.setOnQuit(() -> controlExecutor.submit(() -> {
            stopWatcher();
            shutdownActiveProcess();
        }));
        tui.setOnRunRequested(() -> controlExecutor.submit(() -> switchToRun(resolvedProject)));
        tui.setOnTestRequested(() -> controlExecutor.submit(() -> switchToTest(resolvedProject, resolvedReportDir)));

        if (initialMode == Mode.RUN) {
            controlExecutor.submit(() -> runMode(resolvedProject, false));
        } else {
            controlExecutor.submit(() -> testMode(resolvedProject, resolvedReportDir, false));
        }

        try {
            try {
                tui.run();
                return 0;
            } catch (RuntimeException e) {
                System.err.println("Failed to initialize Tamboui terminal UI. "
                        + "Please run from a real interactive terminal and verify TERM is set.");
                System.err.println(e.getMessage());
                return PRECONDITION_FAILED;
            }
        } finally {
            shuttingDown.set(true);
            stopWatcher();
            stopTestResourcesPolling();
            controlExecutor.shutdownNow();
            shutdownActiveProcess();
            StreamsCapture.getInstance().restore();
        }
    }

    private void switchToRun(Path project) {
        activeMode = Mode.RUN;
        controller.resetExecutionState();
        controller.setRunning();
        tui.setMode(Mode.RUN);
        runMode(project, true);
    }

    private void switchToTest(Path project, Path reports) {
        activeMode = Mode.TEST;
        controller.resetExecutionState();
        controller.startTesting();
        tui.setMode(Mode.TEST);
        testMode(project, reports, true);
    }

    private void runMode(Path project, boolean modeSwitch) {
        activeMode = Mode.RUN;
        currentProject = project;
        currentReportDir = reportDir == null ? project.resolve("__pyronaut__/reports/tests") : reportDir.toAbsolutePath().normalize();
        startWatcher(project, Mode.RUN);
        runModeCycle(project, modeSwitch ? "Switching to run mode" : "Preparing run workflow", false);
    }

    private void testMode(Path project, Path reports, boolean modeSwitch) {
        activeMode = Mode.TEST;
        currentProject = project;
        currentReportDir = reports;
        startWatcher(project, Mode.TEST);
        testModeCycle(project, reports, modeSwitch ? "Switching to test mode" : "Executing test workflow", false);
    }

    private void runModeCycle(Path project, String reason, boolean restart) {
        shutdownActiveProcess();
        if (restart) {
            controller.notify("Change detected, restarting run workflow", UiModel.Severity.INFO);
        }
        controller.notify(reason + " (validate -> process -> dev)", UiModel.Severity.INFO);
        controller.startCompiling();

        var validationCode = runForeground(
            project,
            buildForegroundCommand(
                project,
                "validate-config",
                validateExecutable,
                List.of("--project-dir", project.toString(), "--scenario", "dev")
            ),
            false
        );
        if (validationCode != 0) {
            controller.stopCompiling();
            controller.notify("Configuration validation failed with exit code " + validationCode, UiModel.Severity.ERROR);
            return;
        }
        var processCode = runForeground(
            project,
            buildForegroundCommand(project, "process", processExecutable, List.of("--project-dir", project.toString(), "--pass", "main")),
            false
        );
        if (processCode != 0) {
            controller.stopCompiling();
            controller.notify("Process failed with exit code " + processCode, UiModel.Severity.ERROR);
            return;
        }
        controller.stopCompiling();

        try {
            var runCommand = buildManagedCommand(project, ManagedCommandTarget.RUN);
            if (traceDelegation) {
                controller.addActivityOutput("[tui-delegate] " + String.join(" ", runCommand.command));
            }
            long generation = executionGeneration.incrementAndGet();
            var process = startProcess(project, runCommand.command);
            activeProcess.set(new ManagedProcess(generation, process, runCommand.managementServerUri));
            controller.setRunning();
            controller.notify(restart ? "Run command restarted" : "Run command started", UiModel.Severity.SUCCESS);
            attachOutputReaders(process, true);
            Thread.ofVirtual().start(() -> onBackgroundProcessExit(generation, process, "Run command"));
        } catch (IOException | IllegalStateException e) {
            controller.notify("Failed starting run command: " + e.getMessage(), UiModel.Severity.ERROR);
        }
    }

    private void testModeCycle(Path project, Path reports, String reason, boolean restart) {
        shutdownActiveProcess();
        controller.startTesting();
        if (restart) {
            controller.notify("Change detected, rerunning tests", UiModel.Severity.INFO);
        }
        controller.notify(reason + " (validate -> process -> test)", UiModel.Severity.INFO);
        controller.startCompiling();

        var validationCode = runForeground(
            project,
            buildForegroundCommand(
                project,
                "validate-config",
                validateExecutable,
                List.of("--project-dir", project.toString(), "--scenario", "test")
            ),
            false
        );
        if (validationCode != 0) {
            controller.stopCompiling();
            controller.notify("Configuration validation failed with exit code " + validationCode, UiModel.Severity.ERROR);
            controller.stopTesting();
            return;
        }
        var processCode = runForeground(
            project,
            buildForegroundCommand(project, "process", processExecutable, List.of("--project-dir", project.toString(), "--pass", "test")),
            false
        );
        if (processCode != 0) {
            controller.stopCompiling();
            controller.notify("Process failed with exit code " + processCode, UiModel.Severity.ERROR);
            controller.stopTesting();
            return;
        }
        controller.stopCompiling();

        int testCode;
        try {
            testCode = runForegroundWithIncrementalEvents(
                project,
                buildManagedCommand(project, ManagedCommandTarget.TEST).command,
                reports.resolve(EVENTS_REPORT)
            );
        } catch (IOException | IllegalStateException e) {
            controller.notify("Failed starting test command: " + e.getMessage(), UiModel.Severity.ERROR);
            controller.stopTesting();
            return;
        }
        var summary = summarizeReports(reports);
        renderSummary(summary);
        if (testCode == 0 && summary.failed == 0) {
            controller.notify("Tests completed successfully", UiModel.Severity.SUCCESS);
        } else if (summary.failed > 0) {
            controller.notify("Tests completed with failures", UiModel.Severity.ERROR);
        } else {
            controller.notify("Test command exited with code " + testCode, UiModel.Severity.WARNING);
        }
        controller.stopTesting();
    }

    private int runForeground(Path project, List<String> command, boolean parseServerUri) {
        if (traceDelegation) {
            controller.addActivityOutput("[tui-delegate] " + String.join(" ", command));
        }
        try {
            long generation = executionGeneration.incrementAndGet();
            var process = startProcess(project, command);
            activeProcess.set(new ManagedProcess(generation, process, null));
            var reader = Thread.ofVirtual().unstarted(() -> consumeOutput(process, parseServerUri));
            reader.start();
            var code = waitFor(process);
            reader.join(1000);
            clearActiveProcessIfMatch(generation, process);
            return code;
        } catch (IOException e) {
            controller.notify("Failed running command: " + e.getMessage(), UiModel.Severity.ERROR);
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private int runForegroundWithIncrementalEvents(Path project, List<String> command, Path eventsFile) {
        if (traceDelegation) {
            controller.addActivityOutput("[tui-delegate] " + String.join(" ", command));
        }
        var stream = new IncrementalEventStream(eventsFile);
        try {
            long generation = executionGeneration.incrementAndGet();
            var process = startProcess(project, command);
            activeProcess.set(new ManagedProcess(generation, process, null));
            var reader = Thread.ofVirtual().unstarted(() -> consumeOutput(process, false));
            reader.start();
            stream.start();
            var code = waitFor(process);
            stream.close();
            reader.join(1000);
            clearActiveProcessIfMatch(generation, process);
            return code;
        } catch (IOException e) {
            controller.notify("Failed running command: " + e.getMessage(), UiModel.Severity.ERROR);
            stream.close();
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stream.close();
            return -1;
        }
    }

    private Process startProcess(Path project, List<String> command) throws IOException {
        var builder = new ProcessBuilder(command);
        builder.directory(project.toFile());
        builder.redirectErrorStream(true);
        var testResourcesConnection = applyTestResourcesEnvironment(project, builder);
        if (testResourcesConnection.isPresent()) {
            activeTestResourcesConnection.set(testResourcesConnection.get());
        }
        return builder.start();
    }

    private ManagedCommand buildManagedCommand(Path project, ManagedCommandTarget target) throws IOException {
        Optional<TestResourcesConnection> connection = resolveTestResourcesConnection(project);
        return switch (target) {
            case RUN -> {
                if (usesNativeCommand("run")) {
                    yield new ManagedCommand(
                        buildNativeDevCommand(project, "run", List.of("--project-dir", project.toString()), connection),
                        null
                    );
                }
                int managementPort = findAvailablePort();
                String managementServerUri = "http://127.0.0.1:" + managementPort;
                var jvmArgs = new ArrayList<>(DEV_MANAGEMENT_JVM_FLAGS);
                jvmArgs.add("-Dendpoints.all.port=" + managementPort);
                addDevelopmentControlPanelJvmArgs(project, jvmArgs);
                yield new ManagedCommand(
                    buildJavaDelegateCommand(
                        project,
                        runExecutable,
                        RUN_MAIN_CLASS,
                        readRuntimeClasspathEntries(project, runExecutable),
                        List.of("--project-dir", project.toString()),
                        jvmArgs,
                        connection
                    ),
                    managementServerUri
                );
            }
            case TEST -> new ManagedCommand(buildManagedTestCommand(project, connection), null);
        };
    }

    private List<String> buildManagedTestCommand(Path project,
                                                 Optional<TestResourcesConnection> connection) throws IOException {
        if (usesNativeCommand("test")) {
            return buildNativeDevCommand(project, "test", List.of("--project-dir", project.toString()), connection);
        }
        if (isJavaLauncherDistribution(testExecutable)) {
            return buildJavaDelegateCommand(
                project,
                testExecutable,
                TEST_MAIN_CLASS,
                readTestClasspathEntries(project, testExecutable),
                List.of("--project-dir", project.toString()),
                List.of(),
                connection
            );
        }
        return List.of(testExecutable.toString(), "--project-dir", project.toString());
    }

    private List<String> buildForegroundCommand(Path project,
                                                String commandName,
                                                Path executable,
                                                List<String> arguments) {
        if (usesNativeCommand(commandName)) {
            return buildNativeDevCommand(project, commandName, arguments, Optional.empty());
        }
        var command = new ArrayList<String>(arguments.size() + 1);
        command.add(executable.toString());
        command.addAll(arguments);
        return command;
    }

    private boolean usesNativeCommand(String commandName) {
        return nativeDevExecutable != null && nativeCommands.contains(commandName);
    }

    private List<String> buildNativeDevCommand(Path project,
                                               String commandName,
                                               List<String> arguments,
                                               Optional<TestResourcesConnection> connection) {
        if (nativeDevExecutable == null) {
            throw new IllegalStateException("Missing pyronaut-dev native executable for " + commandName);
        }
        var command = new ArrayList<String>();
        command.add(nativeDevExecutable.toString());
        if ("run".equals(commandName) || "test".equals(commandName)) {
            command.add("-Djava.class.path=" + String.join(File.pathSeparator, nativeApplicationClasspathEntries(project, commandName)));
            String testResourcesClientClasspath = nativeTestResourcesClientClasspath(project);
            if (!testResourcesClientClasspath.isBlank()) {
                command.add("-Dpyronaut.dev.test.resources.client.classpath=" + testResourcesClientClasspath);
            }
            connection.ifPresent(value -> value.appendJvmArgs(command));
        }
        command.add(commandName);
        command.addAll(arguments);
        return command;
    }

    private static List<String> buildJavaDelegateCommand(Path project,
                                                         Path executable,
                                                         String mainClass,
                                                         List<String> classpathEntries,
                                                         List<String> arguments,
                                                         List<String> extraJvmArgs,
                                                         Optional<TestResourcesConnection> connection) {
        List<String> command = new ArrayList<>();
        command.add(resolveJavaExecutable());
        command.addAll(DELEGATE_JVM_FLAGS);
        command.addAll(extraJvmArgs);
        connection.ifPresent(value -> value.appendJvmArgs(command));
        command.add("-cp");
        command.add(String.join(File.pathSeparator, classpathEntries));
        command.add(mainClass);
        command.addAll(arguments);
        return command;
    }

    private void addDevelopmentControlPanelJvmArgs(Path project, List<String> jvmArgs) {
        ControlPanelSettings settings = controlPanelSettings(project);
        if (!settings.enabled()) {
            return;
        }
        jvmArgs.add("-D" + CONTROL_PANEL_ENABLED_PROPERTY + "=true");
        jvmArgs.add("-D" + CONTROL_PANEL_PATH_PROPERTY + "=" + settings.path());
        jvmArgs.add("-D" + CONTROL_PANEL_SECURITY_ACCESS_PROPERTY + "=ANONYMOUS");
        jvmArgs.add("-D" + MICRONAUT_ENVIRONMENTS_PROPERTY + "=" + developmentEnvironments());
    }

    private String developmentEnvironments() {
        String configured = firstNonBlank(
            System.getProperty(MICRONAUT_ENVIRONMENTS_PROPERTY),
            System.getenv(MICRONAUT_ENVIRONMENTS_ENV)
        );
        LinkedHashSet<String> environments = new LinkedHashSet<>();
        if (configured != null && !configured.isBlank()) {
            for (String environment : configured.split(",")) {
                String trimmed = environment.trim();
                if (!trimmed.isEmpty()) {
                    environments.add(trimmed);
                }
            }
        }
        environments.add(DEVELOPMENT_ENVIRONMENT);
        return String.join(",", environments);
    }

    private ControlPanelSettings controlPanelSettings(Path project) {
        if (project == null) {
            return ControlPanelSettings.disabled();
        }
        try {
            PyprojectModel model = modelReader.readProjectDirectory(project);
            if (model.pyronaut() == null || model.pyronaut().controlPanel() == null) {
                return ControlPanelSettings.disabled();
            }
            PyprojectModel.ControlPanel controlPanel = model.pyronaut().controlPanel();
            if (!Boolean.TRUE.equals(controlPanel.enabled())) {
                return ControlPanelSettings.disabled();
            }
            return new ControlPanelSettings(true, controlPanel.path());
        } catch (Exception e) {
            appendLog("[control-panel] failed reading configuration: " + e.getMessage());
            return ControlPanelSettings.disabled();
        }
    }

    private static String appendUrlPath(String serverUri, String path) {
        if (serverUri == null || serverUri.isBlank()) {
            return path;
        }
        String normalizedPath = path == null || path.isBlank() ? "/control-panel" : path;
        String base = serverUri.endsWith("/") ? serverUri.substring(0, serverUri.length() - 1) : serverUri;
        return base + normalizedPath;
    }

    private static int findAvailablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static List<String> readRuntimeClasspathEntries(Path project, Path executable) throws IOException {
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        entries.addAll(readManifestEntries(resolveRunRuntimeManifest(project), "runtime"));
        Path classesDir = project.resolve(CLASSES_DIR).toAbsolutePath().normalize();
        if (!Files.isDirectory(classesDir)) {
            throw new IllegalStateException("Missing processed classes directory: " + classesDir + ". Run pyronaut process first.");
        }
        entries.add(classesDir.toString());
        addExecutableLibEntries(executable, entries);
        return List.copyOf(entries);
    }

    private List<String> nativeApplicationClasspathEntries(Path project, String commandName) {
        try {
            LinkedHashSet<String> entries = new LinkedHashSet<>();
            if ("run".equals(commandName)) {
                entries.addAll(readManifestEntries(resolveRunRuntimeManifest(project), "runtime"));
                Path classesDir = project.resolve(CLASSES_DIR).toAbsolutePath().normalize();
                if (!Files.isDirectory(classesDir)) {
                    throw new IllegalStateException("Missing processed classes directory: " + classesDir + ". Run pyronaut process first.");
                }
                entries.add(classesDir.toString());
            } else if ("test".equals(commandName)) {
                entries.addAll(readManifestEntries(project.resolve(TEST_DEPENDENCIES), "test"));
                Path runtimeManifest = project.resolve(RUNTIME_DEPENDENCIES);
                if (Files.exists(runtimeManifest)) {
                    entries.addAll(readManifestEntries(runtimeManifest, "runtime"));
                }
                Path buildManifest = project.resolve(BUILD_DEPENDENCIES);
                if (Files.exists(buildManifest)) {
                    entries.addAll(readManifestEntries(buildManifest, "build"));
                }
                Path testClassesDir = project.resolve(TEST_CLASSES_DIR).toAbsolutePath().normalize();
                Path classesDir = project.resolve(CLASSES_DIR).toAbsolutePath().normalize();
                if (Files.isDirectory(testClassesDir)) {
                    entries.add(testClassesDir.toString());
                } else if (Files.isDirectory(classesDir)) {
                    entries.add(classesDir.toString());
                } else {
                    throw new IllegalStateException("Missing processed classes directory: " + classesDir + ". Run pyronaut process first.");
                }
            }
            return filterNativeLauncherProvidedEntries(entries);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to build native " + commandName + " classpath: " + e.getMessage(), e);
        }
    }

    private String nativeTestResourcesClientClasspath(Path project) {
        try {
            LinkedHashSet<String> entries = new LinkedHashSet<>();
            Path developmentRuntimeManifest = project.resolve(DEVELOPMENT_RUNTIME_DEPENDENCIES);
            if (Files.exists(developmentRuntimeManifest)) {
                entries.addAll(readManifestEntries(developmentRuntimeManifest, "development-runtime").stream()
                    .filter(PyronautDelegatingTuiCommand::isNativeTestResourcesClientArtifact)
                    .toList());
            }
            Path testManifest = project.resolve(TEST_DEPENDENCIES);
            if (Files.exists(testManifest)) {
                entries.addAll(readManifestEntries(testManifest, "test").stream()
                    .filter(PyronautDelegatingTuiCommand::isNativeTestResourcesClientArtifact)
                    .toList());
            }
            Path runtimeManifest = project.resolve(RUNTIME_DEPENDENCIES);
            if (Files.exists(runtimeManifest)) {
                entries.addAll(readManifestEntries(runtimeManifest, "runtime").stream()
                    .filter(PyronautDelegatingTuiCommand::isNativeTestResourcesClientArtifact)
                    .toList());
            }
            return String.join(File.pathSeparator, entries);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to build native test resources client classpath: " + e.getMessage(), e);
        }
    }

    private List<String> filterNativeLauncherProvidedEntries(Set<String> entries) throws IOException {
        var manifestNames = nativeLauncherProvidedManifestJarNames();
        Set<String> launcherNames;
        Set<String> launcherArtifactIds;
        if (manifestNames.isEmpty()) {
            launcherNames = nativeLauncherProvidedLibJarNames();
            launcherArtifactIds = versionedJarArtifactIds(launcherNames);
        } else {
            launcherNames = manifestNames;
            launcherArtifactIds = Set.of();
        }
        return entries.stream()
            .filter(entry -> !isNativeLauncherProvidedArtifact(entry, launcherNames, launcherArtifactIds))
            .toList();
    }

    private Set<String> nativeLauncherProvidedManifestJarNames() throws IOException {
        if (nativeDevExecutable == null) {
            return Set.of();
        }
        Path manifest = nativeDevExecutable.toAbsolutePath().normalize().getParent().resolve("native-provided-classpath.txt");
        if (!Files.isRegularFile(manifest)) {
            return Set.of();
        }
        try (var lines = Files.lines(manifest, StandardCharsets.UTF_8)) {
            return lines
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .filter(line -> !line.startsWith("#"))
                .collect(java.util.stream.Collectors.toSet());
        }
    }

    private Set<String> nativeLauncherProvidedLibJarNames() throws IOException {
        if (nativeDevExecutable == null) {
            return Set.of();
        }
        Path executable = nativeDevExecutable.toAbsolutePath().normalize();
        List<Path> candidates = List.of(
            executable.getParent().getParent().resolve("lib"),
            executable.getParent().getParent().getParent().resolve("lib")
        );
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                try (var stream = Files.list(candidate)) {
                    return stream
                        .filter(path -> path.getFileName().toString().endsWith(".jar"))
                        .map(path -> path.getFileName().toString())
                        .collect(java.util.stream.Collectors.toSet());
                }
            }
        }
        return Set.of();
    }

    private static Set<String> versionedJarArtifactIds(Set<String> fileNames) {
        return fileNames.stream()
            .map(PyronautDelegatingTuiCommand::versionedJarArtifactId)
            .flatMap(Optional::stream)
            .collect(java.util.stream.Collectors.toSet());
    }

    private static boolean isNativeLauncherProvidedArtifact(String entry,
                                                           Set<String> launcherNames,
                                                           Set<String> launcherArtifactIds) {
        String fileName = Path.of(entry).getFileName().toString();
        if (isNativeTestResourcesClientArtifact(fileName)) {
            return true;
        }
        if (launcherNames.contains(fileName)) {
            return true;
        }
        return versionedJarArtifactId(fileName)
            .map(launcherArtifactIds::contains)
            .orElse(false);
    }

    private static Optional<String> versionedJarArtifactId(String fileName) {
        if (!fileName.endsWith(".jar")) {
            return Optional.empty();
        }
        String baseName = fileName.substring(0, fileName.length() - ".jar".length());
        for (int i = 0; i < baseName.length() - 1; i++) {
            if (baseName.charAt(i) == '-' && Character.isDigit(baseName.charAt(i + 1))) {
                return Optional.of(baseName.substring(0, i));
            }
        }
        return Optional.empty();
    }

    private static boolean isNativeTestResourcesClientArtifact(String entry) {
        String fileName = Path.of(entry).getFileName().toString();
        return fileName.startsWith("micronaut-test-resources-client-")
            || fileName.startsWith("micronaut-test-resources-core-")
            || fileName.startsWith("micronaut-test-resources-codec-");
    }

    private static List<String> readTestClasspathEntries(Path project, Path executable) throws IOException {
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        entries.addAll(readManifestEntries(project.resolve(TEST_DEPENDENCIES), "test"));
        Path runtimeManifest = project.resolve(RUNTIME_DEPENDENCIES);
        if (Files.exists(runtimeManifest)) {
            entries.addAll(readManifestEntries(runtimeManifest, "runtime"));
        }
        Path buildManifest = project.resolve(BUILD_DEPENDENCIES);
        if (Files.exists(buildManifest)) {
            entries.addAll(readManifestEntries(buildManifest, "build"));
        }
        Path testClassesDir = project.resolve(TEST_CLASSES_DIR).toAbsolutePath().normalize();
        Path classesDir = project.resolve(CLASSES_DIR).toAbsolutePath().normalize();
        if (Files.isDirectory(testClassesDir)) {
            entries.add(testClassesDir.toString());
        } else if (Files.isDirectory(classesDir)) {
            entries.add(classesDir.toString());
        } else {
            throw new IllegalStateException("Missing processed classes directory: " + classesDir + ". Run pyronaut process first.");
        }
        addExecutableLibEntries(executable, entries);
        return List.copyOf(entries);
    }

    private static List<String> readManifestEntries(Path manifestPath, String scope) throws IOException {
        if (!Files.exists(manifestPath)) {
            throw new IllegalStateException("Missing " + scope + " scope cache: " + manifestPath + ". Run pyronaut install first.");
        }
        List<String> entries = new ArrayList<>();
        for (String line : Files.readAllLines(manifestPath, StandardCharsets.UTF_8)) {
            String value = line == null ? "" : line.trim();
            if (!value.isEmpty()) {
                entries.add(Path.of(value).toAbsolutePath().normalize().toString());
            }
        }
        return entries;
    }

    private static Path resolveRunRuntimeManifest(Path project) {
        Path developmentManifest = project.resolve(DEVELOPMENT_RUNTIME_DEPENDENCIES);
        if (Files.exists(developmentManifest)) {
            return developmentManifest;
        }
        return project.resolve(RUNTIME_DEPENDENCIES);
    }

    private static void addExecutableLibEntries(Path executable, LinkedHashSet<String> entries) throws IOException {
        Path libDir = executable.toAbsolutePath().normalize().getParent().getParent().resolve("lib");
        if (!Files.isDirectory(libDir)) {
            throw new IllegalStateException("Missing delegate library directory: " + libDir);
        }
        try (var stream = Files.list(libDir)) {
            stream
                .filter(path -> path.getFileName().toString().endsWith(".jar"))
                .sorted()
                .map(path -> path.toAbsolutePath().normalize().toString())
                .forEach(entries::add);
        }
    }

    private static boolean isJavaLauncherDistribution(Path executable) {
        Path normalized = executable.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null) {
            return false;
        }
        if (!"bin".equals(parent.getFileName().toString())) {
            return false;
        }
        Path grandParent = parent.getParent();
        if (grandParent == null) {
            return false;
        }
        return Files.isDirectory(grandParent.resolve("lib"));
    }

    private static String resolveJavaExecutable() {
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            Path javaBin = Path.of(javaHome).resolve("bin").resolve("java");
            if (Files.isExecutable(javaBin)) {
                return javaBin.toAbsolutePath().normalize().toString();
            }
        }
        return "java";
    }

    Optional<TestResourcesConnection> applyTestResourcesEnvironment(Path project, ProcessBuilder builder) throws IOException {
        var connection = resolveTestResourcesConnection(project);
        if (connection.isEmpty()) {
            return Optional.empty();
        }
        var env = builder.environment();
        connection.get().applyToEnvironment(env);
        return connection;
    }

    private static void setEnvironmentValue(Map<String, String> env, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        env.put(key, value);
    }

    boolean hasReachableTestResourcesServer(Path settingsFile) {
        try {
            return loadTestResourcesConnection(settingsFile)
                .filter(this::isSocketReachableTestResourcesConnection)
                .isPresent();
        } catch (Exception e) {
            appendLog("[test-resources] stale or unreachable settings ignored: " + e.getMessage());
            return false;
        }
    }

    private Optional<TestResourcesConnection> resolveTestResourcesConnection(Path project) {
        var active = activeTestResourcesConnection.get();
        if (active != null && active.isUsable()) {
            return Optional.of(active);
        }
        var fromProperties = loadTestResourcesConnection(project.resolve(TEST_RESOURCES_PROPERTIES));
        if (fromProperties.isPresent() && isSocketReachableTestResourcesConnection(fromProperties.get())) {
            return fromProperties;
        }
        return Optional.empty();
    }

    private Optional<TestResourcesConnection> loadTestResourcesConnection(Path settingsFile) {
        try {
            if (!Files.exists(settingsFile)) {
                return Optional.empty();
            }
            Properties properties = new Properties();
            try (var in = Files.newInputStream(settingsFile)) {
                properties.load(in);
            }
            var connection = TestResourcesConnection.fromProperties(properties);
            return connection.isUsable() ? Optional.of(connection) : Optional.empty();
        } catch (IOException e) {
            appendLog("[test-resources] failed reading settings: " + e.getMessage());
            return Optional.empty();
        }
    }

    private boolean isSocketReachableTestResourcesConnection(TestResourcesConnection connection) {
        try {
            URI uri = URI.create(connection.serverUri());
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
            if (host == null || host.isBlank()) {
                return false;
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 1000);
                return true;
            }
        } catch (Exception e) {
            appendLog("[test-resources] stale or unreachable settings ignored: " + e.getMessage());
            return false;
        }
    }

    void appendLog(String line) {
        if (currentLogFile == null) {
            return;
        }
        String sanitized = stripAnsi(line);
        try {
            Files.writeString(
                currentLogFile,
                sanitized + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND
            );
        } catch (IOException ignored) {
        }
    }

    private void attachOutputReaders(Process process, boolean parseServerUri) {
        Thread.ofVirtual().start(() -> consumeOutput(process, parseServerUri));
    }

    private void consumeOutput(Process process, boolean parseServerUri) {
        try (var reader = new BufferedReader(process.inputReader())) {
            String line;
            while ((line = reader.readLine()) != null) {
                routeOutputLine(process, line);
                appendLog(line);
                if (parseServerUri) {
                    var matcher = SERVER_URI.matcher(line);
                    if (matcher.find()) {
                        String serverUri = matcher.group(1);
                        controller.setUrl(serverUri);
                        refreshApplicationEndpointsAsync(serverUri, resolveEndpointsServerUri(process, serverUri));
                    }
                }
            }
        } catch (IOException e) {
            controller.notify("Failed reading command output: " + e.getMessage(), UiModel.Severity.WARNING);
        }
    }

    private String resolveEndpointsServerUri(Process process, String applicationServerUri) {
        var managed = activeProcess.get();
        if (managed != null && managed.process == process && managed.managementServerUri != null && !managed.managementServerUri.isBlank()) {
            return managed.managementServerUri;
        }
        return applicationServerUri;
    }

    private void refreshApplicationEndpointsAsync(String applicationServerUri, String endpointsServerUri) {
        Thread.ofVirtual().start(() -> {
            try {
                refreshApplicationEndpoints(applicationServerUri, endpointsServerUri);
            } catch (Exception e) {
                appendLog("[endpoints] discovery failed: " + e.getMessage());
            }
        });
    }

    private boolean refreshApplicationEndpoints(String serverUri) throws IOException, InterruptedException {
        return refreshApplicationEndpoints(serverUri, serverUri);
    }

    private boolean refreshApplicationEndpoints(String applicationServerUri, String endpointsServerUri) throws IOException, InterruptedException {
        controller.clearEndpoints();
        controller.clearManagementHealth();
        var routes = fetchApplicationEndpointRoutes(endpointsServerUri);
        if (routes.statusCode() != 200) {
            appendLog("[endpoints] routes endpoint returned status " + routes.statusCode());
            return false;
        }
        List<String> endpoints = summarizeRoutePayload(routes.body());
        controller.setEndpoints(endpoints);
        refreshManagementHealth(applicationServerUri, endpointsServerUri);
        return !endpoints.isEmpty();
    }

    private HttpResponse<String> fetchApplicationEndpointRoutes(String serverUri) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(serverUri + "/routes"))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();
        return insightsClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void refreshManagementHealth(String applicationServerUri, String endpointsServerUri) throws IOException, InterruptedException {
        String healthUri = endpointsServerUri + "/health";
        var request = HttpRequest.newBuilder(URI.create(healthUri))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();
        var response = insightsClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            appendLog("[endpoints] health endpoint returned status " + response.statusCode());
            return;
        }
        String status = firstNonBlank(jsonString(response.body(), "status"), "UNKNOWN");
        controller.setManagementHealth(managementHealthLinkUri(applicationServerUri, healthUri), status);
    }

    private String managementHealthLinkUri(String applicationServerUri, String healthUri) {
        ControlPanelSettings settings = controlPanelSettings(currentProject);
        if (!settings.enabled()) {
            return healthUri;
        }
        return appendUrlPath(applicationServerUri, settings.path());
    }

    private void routeOutputLine(Process process, String line) {
        if (isTestResourcesServiceLine(line)) {
            controller.addTestResourcesOutput(line);
            if (line.toLowerCase(Locale.ROOT).contains("server running:")) {
                notifyTestResourcesRunning();
            }
            return;
        }
        controller.addActivityOutput(line);
    }

    private boolean isTestResourcesServiceLine(String line) {
        return line.startsWith("[test-resources-service]")
            || line.startsWith("[test-resources]")
            || line.contains(TEST_RESOURCES_IMAGE_PULL_MARKER)
            || line.contains(TEST_RESOURCES_CONTAINER_CREATE_MARKER)
            || line.contains(TEST_RESOURCES_CONTAINER_STARTED_MARKER);
    }

    private void notifyTestResourcesRunning() {
        if (testResourcesRunningNotified.compareAndSet(false, true)) {
            controller.notify("Test resources service started", UiModel.Severity.INFO);
        }
    }

    private void clearActiveProcessIfMatch(long generation, Process process) {
        var current = activeProcess.get();
        if (current != null && current.generation == generation && current.process == process) {
            activeProcess.compareAndSet(current, null);
        }
    }

    private void shutdownActiveProcess() {
        var managed = activeProcess.getAndSet(null);
        if (managed == null) {
            return;
        }
        var process = managed.process;
        if (!process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private int waitFor(Process process) {
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return -1;
        }
    }

    private void onBackgroundProcessExit(long generation, Process process, String label) {
        var code = waitFor(process);
        var current = activeProcess.get();
        if (current != null && current.generation == generation && current.process == process) {
            activeProcess.compareAndSet(current, null);
            if (!shuttingDown.get()) {
                if (code == 0) {
                    controller.notify(label + " exited", UiModel.Severity.INFO);
                } else {
                    controller.notify(label + " exited with code " + code, UiModel.Severity.ERROR);
                }
            }
        }
    }

    private void startWatcher(Path project, Mode mode) {
        var existing = watchLoop.get();
        if (existing != null && existing.project.equals(project) && existing.mode == mode) {
            return;
        }
        stopWatcher();
        try {
            var loop = new WatchLoop(project, mode);
            watchLoop.set(loop);
            loop.start();
        } catch (IOException e) {
            controller.notify("Unable to start file watcher: " + e.getMessage(), UiModel.Severity.WARNING);
        }
    }

    private void stopWatcher() {
        var existing = watchLoop.getAndSet(null);
        if (existing != null) {
            existing.stop();
        }
    }

    private void startTestResourcesPolling(Path project) {
        var existing = testResourcesPoller.get();
        if (existing != null && existing.project.equals(project)) {
            return;
        }
        stopTestResourcesPolling();
        testResourcesLogTail.set(new TestResourcesLogTail(project));
        testResourcesRunningNotified.set(false);
        controller.setTestResourcesLoading();
        refreshTestResourcesLogs(project);
        refreshTestResourcesSnapshot(project);
        var poller = new TestResourcesPoller(project);
        testResourcesPoller.set(poller);
        poller.start();
    }

    private void stopTestResourcesPolling() {
        var existing = testResourcesPoller.getAndSet(null);
        if (existing != null) {
            existing.stop();
        }
        testResourcesLogTail.set(null);
    }

    private void refreshTestResourcesLogs(Path project) {
        var tail = testResourcesLogTail.get();
        if (tail == null || !tail.project.equals(project)) {
            return;
        }
        tail.refresh();
    }

    private boolean refreshTestResourcesSnapshot(Path project) {
        try {
            var connection = resolveTestResourcesConnection(project);
            if (connection.isEmpty()) {
                controller.setTestResourcesUnavailable("waiting for active test resources session");
                return false;
            }
            var session = connection.get();
            var serverUri = session.serverUri();
            var token = session.token();

            var health = fetchInsights(serverUri, "/api/test-resources/health", token);
            if (health.statusCode() == 401 || health.statusCode() == 403) {
                appendLog("[test-resources] insights auth failed with status " + health.statusCode()
                    + "; using basic connection summary");
                return showBasicTestResourcesSnapshot(session);
            }
            if (health.statusCode() != 200) {
                if (refreshControlPanelSnapshot(serverUri, token, "health endpoint failed with status " + health.statusCode())) {
                    return true;
                }
                return showBasicTestResourcesSnapshot(session, "health endpoint failed with status " + health.statusCode());
            }

            var containers = fetchInsights(serverUri, "/api/test-resources/containers", token);
            var propertiesResponse = fetchInsights(serverUri, "/api/test-resources/properties", token);
            var errors = fetchInsights(serverUri, "/api/test-resources/errors", token);
            if (containers.statusCode() != 200 || propertiesResponse.statusCode() != 200 || errors.statusCode() != 200) {
                if (refreshControlPanelSnapshot(
                    serverUri,
                    token,
                    "insights endpoints failed (containers=" + containers.statusCode()
                        + ", properties=" + propertiesResponse.statusCode()
                        + ", errors=" + errors.statusCode() + ")"
                )) {
                    return true;
                }
                return showBasicTestResourcesSnapshot(
                    session,
                    "insights endpoints failed (containers=" + containers.statusCode()
                        + ", properties=" + propertiesResponse.statusCode()
                        + ", errors=" + errors.statusCode() + ")"
                );
            }

            var healthStatus = jsonString(health.body(), "status");
            var healthUri = jsonString(health.body(), "uri");
            var healthPort = jsonLong(health.body(), "port");
            String healthMessage = (healthStatus == null ? "UNKNOWN" : healthStatus)
                + (healthUri == null ? "" : " @ " + healthUri)
                + (healthPort >= 0 ? " (port " + healthPort + ")" : "");

            var controlPanel = fetchControlPanelSnapshot(serverUri, token);
            var containerRows = summarizeArrayPayload(containers.body(), "containers");
            var propertyRows = summarizeArrayPayload(propertiesResponse.body(), "properties");
            var errorRows = summarizeArrayPayload(errors.body(), "errors");
            if (containerRows.isEmpty() && controlPanel.isPresent()) {
                containerRows = controlPanel.get().containers();
            }
            if (propertyRows.isEmpty() && controlPanel.isPresent()) {
                propertyRows = controlPanel.get().properties();
            }
            if (errorRows.isEmpty() && controlPanel.isPresent()) {
                errorRows = controlPanel.get().errors();
            }

            controller.setTestResourcesRunning(healthMessage, containerRows, propertyRows, errorRows);
            return true;
        } catch (Exception e) {
            var connection = resolveTestResourcesConnection(project);
            if (connection.isPresent()) {
                return showBasicTestResourcesSnapshot(connection.get(), "insights refresh failed: " + e.getMessage());
            }
            controller.setTestResourcesError("insights refresh failed: " + e.getMessage());
            return false;
        }
    }

    private boolean showBasicTestResourcesSnapshot(TestResourcesConnection session) {
        return showBasicTestResourcesSnapshot(session, null);
    }

    private boolean showBasicTestResourcesSnapshot(TestResourcesConnection session, String note) {
        List<String> errors = note == null || note.isBlank() ? List.of() : List.of(note);
        controller.setTestResourcesRunning("CONNECTED @ " + session.serverUri(), List.of(), List.of(), errors);
        return true;
    }

    private boolean refreshControlPanelSnapshot(String serverUri, String token, String fallbackReason) throws IOException, InterruptedException {
        var controlPanel = fetchControlPanelSnapshot(serverUri, token);
        if (controlPanel.isEmpty()) {
            var docker = fetchInsights(serverUri, "/control-panel/docker", token);
            var panels = fetchInsights(serverUri, "/control-panel", token);
            if (docker.statusCode() == 401 || docker.statusCode() == 403 || panels.statusCode() == 401 || panels.statusCode() == 403) {
                controller.setTestResourcesAuthFailed("insights auth failed (control-panel)");
                return false;
            }
            if (docker.statusCode() != 200 || panels.statusCode() != 200) {
                controller.setTestResourcesError(
                    fallbackReason + "; control-panel status docker=" + docker.statusCode() + ", panel=" + panels.statusCode()
                );
                return false;
            }
            controller.setTestResourcesError(fallbackReason + "; control-panel returned no usable data");
            return false;
        }

        var snapshot = controlPanel.get();
        controller.setTestResourcesRunning(snapshot.healthMessage(), snapshot.containers(), snapshot.properties(), snapshot.errors());
        return true;
    }

    private Optional<ControlPanelSnapshot> fetchControlPanelSnapshot(String serverUri, String token) throws IOException, InterruptedException {
        var docker = fetchInsights(serverUri, "/control-panel/docker", token);
        var panels = fetchInsights(serverUri, "/control-panel", token);
        if (docker.statusCode() == 401 || docker.statusCode() == 403 || panels.statusCode() == 401 || panels.statusCode() == 403) {
            return Optional.empty();
        }
        if (docker.statusCode() != 200 || panels.statusCode() != 200) {
            return Optional.empty();
        }

        String dockerStatus = firstNonBlank(jsonString(docker.body(), "dockerStatus"), "UNKNOWN");
        long running = jsonLong(docker.body(), "runningContainers");
        long inProgress = summarizeArrayPayload(docker.body(), "startingContainers").size()
            + summarizeArrayPayload(docker.body(), "pullingContainers").size();
        String healthMessage = dockerStatus
            + (running >= 0 ? " (running containers: " + running + ")" : "")
            + (inProgress > 0 ? ", in progress: " + inProgress : "");

        var containers = mergeRows(
            summarizeArrayPayload(docker.body(), "managedContainers"),
            summarizeArrayPayload(docker.body(), "startingContainers"),
            summarizeArrayPayload(docker.body(), "pullingContainers")
        );

        return Optional.of(new ControlPanelSnapshot(
            healthMessage,
            containers,
            summarizeArrayPayload(panels.body(), "resolvedProperties"),
            summarizeArrayPayload(panels.body(), "errors")
        ));
    }

    private static List<String> mergeRows(List<String>... groups) {
        var rows = new ArrayList<String>();
        for (var group : groups) {
            rows.addAll(group);
        }
        return List.copyOf(rows);
    }

    private record ControlPanelSnapshot(
        String healthMessage,
        List<String> containers,
        List<String> properties,
        List<String> errors
    ) {
    }

    private HttpResponse<String> fetchInsights(String serverUri, String path, String token) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(serverUri + path))
            .header("Access-Token", token)
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();
        return insightsClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static List<String> summarizeArrayPayload(String body, String key) {
        var items = extractArrayItems(body, key);
        if (items.isEmpty()) {
            return List.of();
        }
        var rows = new ArrayList<String>(items.size());
        for (String item : items) {
            var trimmed = item.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                rows.add(summarizeObject(key, trimmed));
            } else if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                rows.add(unescapeJson(trimmed.substring(1, trimmed.length() - 1)));
            } else {
                rows.add(trimmed);
            }
        }
        return List.copyOf(rows);
    }

    private static String summarizeObject(String key, String object) {
        return switch (key) {
            case "routes" -> {
                String method = firstNonBlank(jsonString(object, "method"), joinJsonStringArray(object, "methods"));
                String uri = firstNonBlank(jsonString(object, "uri"), jsonString(object, "path"));
                if (method == null && uri == null) {
                    yield object;
                }
                if (method == null) {
                    yield uri;
                }
                if (uri == null) {
                    yield method;
                }
                yield method + " " + uri;
            }
            case "containers" -> {
                String name = firstNonBlank(jsonString(object, "name"), "<unknown>");
                String status = firstNonBlank(jsonString(object, "status"), "unknown");
                String image = firstNonBlank(jsonString(object, "image"), "unknown-image");
                String scope = firstNonBlank(jsonString(object, "scope"), "default");
                yield name + " [" + status + "] image=" + image + " scope=" + scope;
            }
            case "managedContainers" -> {
                String name = firstNonBlank(jsonString(object, "name"), "<unknown>");
                String id = firstNonBlank(jsonString(object, "id"), "unknown");
                String image = firstNonBlank(jsonString(object, "imageName"), "unknown-image");
                String scope = firstNonBlank(jsonString(object, "scope"), "default");
                yield name + " [running] image=" + image + " scope=" + scope + " id=" + id;
            }
            case "startingContainers" -> {
                String name = firstNonBlank(jsonString(object, "name"), jsonString(object, "imageName"));
                if (name == null) {
                    yield "starting";
                }
                yield name + " [starting]";
            }
            case "pullingContainers" -> {
                String name = firstNonBlank(jsonString(object, "name"), jsonString(object, "imageName"));
                if (name == null) {
                    yield "pulling";
                }
                yield name + " [pulling]";
            }
            case "properties" -> {
                String keyName = firstNonBlank(jsonString(object, "key"), "<key>");
                String value = firstNonBlank(jsonString(object, "value"), "<value>");
                String resolver = firstNonBlank(jsonString(object, "resolver"), "resolver");
                String scope = firstNonBlank(jsonString(object, "scope"), "default");
                yield keyName + "=" + value + " (" + resolver + "/" + scope + ")";
            }
            case "resolvedProperties" -> {
                String keyName = firstNonBlank(jsonString(object, "property"), "<key>");
                String value = firstNonBlank(jsonString(object, "resolvedValue"), "<value>");
                yield keyName + "=" + value;
            }
            case "errors" -> {
                String property = firstNonBlank(jsonString(object, "property"), "<property>");
                String resolver = firstNonBlank(jsonString(object, "resolver"), "resolver");
                String message = jsonString(object, "message");
                if (message == null) {
                    message = firstStackTraceLine(jsonString(object, "stackTrace"));
                }
                message = firstNonBlank(message, "unknown error");
                yield property + " [" + resolver + "] " + message;
            }
            default -> object;
        };
    }

    private static List<String> summarizeRoutePayload(String body) {
        List<String> items = extractArrayItems(body, "routes");
        if (items.isEmpty()) {
            items = extractTopLevelArrayItems(body);
        }
        if (items.isEmpty()) {
            items = summarizeTopLevelRouteObject(body);
            if (!items.isEmpty()) {
                return items;
            }
        }
        if (items.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> routes = new LinkedHashSet<>();
        for (String item : items) {
            String trimmed = item.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String route = summarizeObject("routes", trimmed);
            if (!isControlPanelRoute(route)) {
                routes.add(route);
            }
        }
        return alignRouteRows(List.copyOf(routes));
    }

    private static List<String> summarizeTopLevelRouteObject(String body) {
        List<RouteEntry> entries = extractTopLevelRouteEntries(body);
        if (entries.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> routes = new LinkedHashSet<>();
        for (RouteEntry entry : entries) {
            if (isManagementRoute(entry.value)) {
                continue;
            }
            String route = summarizeRouteKey(entry.key);
            if (route != null && !route.isBlank() && !isControlPanelRoute(route)) {
                routes.add(route);
            }
        }
        return alignRouteRows(List.copyOf(routes));
    }

    private static String summarizeRouteKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String method = extractBracketedValue(key, "method=[");
        String uri = null;
        int uriStart = key.indexOf("{[");
        if (uriStart >= 0) {
            int uriEnd = key.indexOf("]", uriStart + 2);
            if (uriEnd > uriStart + 2) {
                uri = key.substring(uriStart + 1, uriEnd + 1);
            }
        }
        if (uri != null && uri.startsWith("[") && uri.endsWith("]")) {
            uri = uri.substring(1, uri.length() - 1);
        }
        if (method == null && uri == null) {
            return null;
        }
        if (method == null) {
            return uri;
        }
        if (uri == null) {
            return method;
        }
        return method + " " + uri;
    }

    private static String extractBracketedValue(String body, String marker) {
        int start = body.indexOf(marker);
        if (start < 0) {
            return null;
        }
        int valueStart = start + marker.length();
        int valueEnd = body.indexOf(']', valueStart);
        if (valueEnd < 0) {
            return null;
        }
        return body.substring(valueStart, valueEnd);
    }

    private static boolean isManagementRoute(String routeValue) {
        String method = jsonString(routeValue, "method");
        return method != null && method.contains(MANAGEMENT_METHOD_PREFIX);
    }

    private static boolean isControlPanelRoute(String route) {
        String path = RouteDisplay.parse(route).path;
        return path != null && (path.equals("/control-panel") || path.startsWith("/control-panel/"));
    }

    private static List<RouteEntry> extractTopLevelRouteEntries(String body) {
        String trimmed = body == null ? "" : body.trim();
        if (!trimmed.startsWith("{")) {
            return List.of();
        }
        int depth = 0;
        boolean inString = false;
        boolean escaping = false;
        boolean expectingKey = false;
        int keyStart = -1;
        String pendingKey = null;
        int valueStart = -1;
        var entries = new ArrayList<RouteEntry>();
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (inString) {
                if (escaping) {
                    escaping = false;
                } else if (ch == '\\') {
                    escaping = true;
                } else if (ch == '"') {
                    inString = false;
                    if (depth == 1 && expectingKey && keyStart >= 0) {
                        pendingKey = unescapeJson(trimmed.substring(keyStart, i));
                        keyStart = -1;
                        expectingKey = false;
                    }
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
                if (depth == 1 && expectingKey) {
                    keyStart = i + 1;
                } else if (depth == 1 && pendingKey != null && valueStart < 0) {
                    valueStart = i;
                }
                continue;
            }
            if (ch == '{' || ch == '[') {
                if (depth == 1 && pendingKey != null && valueStart < 0) {
                    valueStart = i;
                }
                depth++;
                if (ch == '{' && depth == 1) {
                    expectingKey = true;
                }
                continue;
            }
            if (ch == '}' || ch == ']') {
                if (depth == 2 && pendingKey != null && valueStart >= 0) {
                    entries.add(new RouteEntry(pendingKey, trimmed.substring(valueStart, i + 1)));
                    pendingKey = null;
                    valueStart = -1;
                }
                if (depth == 1 && ch == '}') {
                    break;
                }
                depth--;
                continue;
            }
            if (depth == 1 && pendingKey != null && valueStart < 0 && !Character.isWhitespace(ch) && ch == 'n') {
                int end = findTopLevelValueEnd(trimmed, i);
                entries.add(new RouteEntry(pendingKey, trimmed.substring(i, end)));
                pendingKey = null;
                valueStart = -1;
                i = end - 1;
                continue;
            }
            if (depth == 1 && ch == ',') {
                expectingKey = true;
            }
        }
        return List.copyOf(entries);
    }

    private static int findTopLevelValueEnd(String body, int start) {
        int index = start;
        while (index < body.length() && body.charAt(index) != ',' && body.charAt(index) != '}') {
            index++;
        }
        return index;
    }

    private static List<String> alignRouteRows(List<String> routes) {
        int width = 0;
        var parsed = new ArrayList<RouteDisplay>(routes.size());
        for (String route : routes) {
            var display = RouteDisplay.parse(route);
            parsed.add(display);
            if (display.path != null) {
                width = Math.max(width, display.method.length());
            }
        }
        if (width == 0) {
            return routes;
        }
        var aligned = new ArrayList<String>(routes.size());
        for (RouteDisplay display : parsed) {
            aligned.add(display.render(width));
        }
        return List.copyOf(aligned);
    }

    private static List<String> extractArrayItems(String body, String key) {
        String marker = "\"" + key + "\"";
        int keyIndex = body.indexOf(marker);
        if (keyIndex < 0) {
            return List.of();
        }
        int colon = body.indexOf(':', keyIndex + marker.length());
        if (colon < 0) {
            return List.of();
        }
        int start = body.indexOf('[', colon + 1);
        if (start < 0) {
            return List.of();
        }

        int depth = 0;
        boolean inString = false;
        boolean escaping = false;
        int itemStart = -1;
        var items = new ArrayList<String>();
        for (int i = start; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (inString) {
                if (escaping) {
                    escaping = false;
                } else if (ch == '\\') {
                    escaping = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                if (depth == 1 && itemStart < 0) {
                    itemStart = i;
                }
                inString = true;
                continue;
            }
            if (ch == '[' || ch == '{') {
                if (depth == 1 && itemStart < 0 && ch != '[') {
                    itemStart = i;
                }
                depth++;
                continue;
            }
            if (ch == ']' || ch == '}') {
                if (ch == ']' && depth == 1 && itemStart >= 0) {
                    items.add(body.substring(itemStart, i));
                    itemStart = -1;
                }
                depth--;
                if (depth == 1 && itemStart >= 0 && ch == '}') {
                    items.add(body.substring(itemStart, i + 1));
                    itemStart = -1;
                    continue;
                }
                if (depth == 0) {
                    return List.copyOf(items);
                }
                continue;
            }
            if (depth == 1) {
                if (!Character.isWhitespace(ch) && ch != ',') {
                    if (itemStart < 0) {
                        itemStart = i;
                    }
                } else if (ch == ',' && itemStart >= 0) {
                    items.add(body.substring(itemStart, i));
                    itemStart = -1;
                }
            }
        }
        return List.copyOf(items);
    }

    private static List<String> extractTopLevelArrayItems(String body) {
        String trimmed = body == null ? "" : body.trim();
        if (!trimmed.startsWith("[")) {
            return List.of();
        }
        return extractArrayItems("{\"routes\":" + trimmed + "}", "routes");
    }

    private static String unescapeJson(String value) {
        return value
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\");
    }

    private static String joinJsonStringArray(String body, String key) {
        List<String> items = extractArrayItems(body, key);
        if (items.isEmpty()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        for (String item : items) {
            String trimmed = item.trim();
            if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
                values.add(unescapeJson(trimmed.substring(1, trimmed.length() - 1)));
            }
        }
        if (values.isEmpty()) {
            return null;
        }
        return String.join(",", values);
    }

    private static String firstStackTraceLine(String stackTrace) {
        if (stackTrace == null || stackTrace.isBlank()) {
            return null;
        }
        int newline = stackTrace.indexOf('\n');
        if (newline < 0) {
            return stackTrace.trim();
        }
        return stackTrace.substring(0, newline).trim();
    }

    private static String jsonString(String body, String key) {
        int keyIndex = body.indexOf('"' + key + '"');
        if (keyIndex < 0) {
            return null;
        }
        int colon = body.indexOf(':', keyIndex);
        if (colon < 0) {
            return null;
        }
        int firstQuote = body.indexOf('"', colon + 1);
        if (firstQuote < 0) {
            return null;
        }
        int secondQuote = body.indexOf('"', firstQuote + 1);
        if (secondQuote < 0) {
            return null;
        }
        return unescapeJson(body.substring(firstQuote + 1, secondQuote));
    }

    private static long jsonLong(String body, String key) {
        int keyIndex = body.indexOf('"' + key + '"');
        if (keyIndex < 0) {
            return -1;
        }
        int colon = body.indexOf(':', keyIndex);
        if (colon < 0) {
            return -1;
        }
        int i = colon + 1;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < body.length() && Character.isDigit(body.charAt(i))) {
            i++;
        }
        if (start == i) {
            return -1;
        }
        try {
            return Long.parseLong(body.substring(start, i));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String firstNonBlank(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private void onWatchChanges(Mode mode, List<Path> changedFiles) {
        if (shuttingDown.get() || activeMode != mode || changedFiles.isEmpty() || currentProject == null) {
            return;
        }
        var updates = changedFiles.stream()
            .sorted(Comparator.naturalOrder())
            .map(path -> new UiModel.FileUpdate(path.toString(), UiModel.UpdateType.CHANGED))
            .toList();
        controller.updateFiles(updates);
        controller.startCompiling();
        controller.notify("Detected file changes: " + changedFiles.size(), UiModel.Severity.INFO);
        if (mode == Mode.RUN) {
            runModeCycle(currentProject, "Refreshing run workflow", true);
        } else {
            var reports = currentReportDir == null
                ? currentProject.resolve("__pyronaut__/reports/tests")
                : currentReportDir;
            testModeCycle(currentProject, reports, "Refreshing test workflow", true);
        }
    }

    private Set<String> watchRootsForMode(Mode mode) {
        if (mode == Mode.TEST) {
            return Set.of("src", "config", "tests");
        }
        return Set.of("src", "config");
    }

    private ReportSummary summarizeReports(Path reportsDir) {
        var junit = reportsDir.resolve("junit.xml");
        if (!Files.exists(junit)) {
            return new ReportSummary(0, 0, 0, 0, List.of());
        }

        try {
            var dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            var document = dbf.newDocumentBuilder().parse(junit.toFile());
            return summarizeDocument(document);
        } catch (Exception e) {
            controller.notify("Failed parsing junit report: " + e.getMessage(), UiModel.Severity.WARNING);
            return new ReportSummary(0, 0, 0, 0, List.of());
        }
    }

    private ReportSummary summarizeDocument(Document document) {
        var suites = new ArrayList<Element>();
        var root = document.getDocumentElement();
        if (root == null) {
            return new ReportSummary(0, 0, 0, 0, List.of());
        }
        if ("testsuite".equals(root.getTagName())) {
            suites.add(root);
        } else if ("testsuites".equals(root.getTagName())) {
            var nodes = root.getElementsByTagName("testsuite");
            for (int i = 0; i < nodes.getLength(); i++) {
                var node = nodes.item(i);
                if (node instanceof Element element) {
                    suites.add(element);
                }
            }
        }

        int total = 0;
        int failed = 0;
        int skipped = 0;
        var cases = new ArrayList<TestCaseResult>();

        for (var suite : suites) {
            total += intAttr(suite, "tests");
            failed += intAttr(suite, "failures") + intAttr(suite, "errors");
            skipped += intAttr(suite, "skipped");
            var testcases = suite.getElementsByTagName("testcase");
            for (int i = 0; i < testcases.getLength(); i++) {
                var node = testcases.item(i);
                if (!(node instanceof Element testcase)) {
                    continue;
                }
                cases.add(toCase(testcase));
            }
        }

        if (total == 0 && !cases.isEmpty()) {
            total = cases.size();
        }
        int passed = Math.max(0, total - failed - skipped);
        return new ReportSummary(total, passed, failed, skipped, cases);
    }

    private TestCaseResult toCase(Element testcase) {
        var className = attr(testcase, "classname");
        var name = attr(testcase, "name");

        var failure = firstChild(testcase, "failure").or(() -> firstChild(testcase, "error"));
        if (failure.isPresent()) {
            var message = failure.get().getAttribute("message");
            if (message == null || message.isBlank()) {
                message = failure.get().getTextContent();
            }
            return new TestCaseResult(className, name, UiModel.Status.FAILED, trimMessage(message));
        }
        if (firstChild(testcase, "skipped").isPresent()) {
            return new TestCaseResult(className, name, UiModel.Status.SKIPPED, null);
        }
        return new TestCaseResult(className, name, UiModel.Status.PASSED, null);
    }

    private void renderSummary(ReportSummary summary) {
        controller.startTesting();
        controller.resetTestTree();

        var classIds = new LinkedHashMap<String, Integer>();
        int nextId = 1;
        for (var testCase : summary.cases) {
            var className = testCase.className.isBlank() ? "tests" : testCase.className;
            Integer classId = classIds.get(className);
            if (classId == null) {
                classId = nextId++;
                classIds.put(className, classId);
                controller.addTestNode(classId, 0, (byte) 1, className, className);
            }
            var methodId = nextId++;
            var displayName = testCase.name.isBlank() ? "<unknown>" : testCase.name;
            controller.addTestNode(methodId, classId, (byte) 2, displayName, displayName);
            controller.nodeFinished(methodId, testCase.status, testCase.message);
            if (testCase.message != null && !testCase.message.isBlank()) {
                controller.addTestLog(methodId, testCase.message);
            }
        }

        controller.updateTestSummary(summary.passed, summary.failed, summary.skipped, 0, 0);
        if (summary.total == 0) {
            controller.notify("No test reports found under " + reportDir, UiModel.Severity.WARNING);
        }
        controller.stopTesting();
    }

    private static String attr(Element element, String name) {
        var value = element.getAttribute(name);
        return value == null ? "" : value.trim();
    }

    private static Optional<Element> firstChild(Element parent, String tagName) {
        var children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element && tagName.equals(element.getTagName())) {
                return Optional.of(element);
            }
        }
        return Optional.empty();
    }

    private static int intAttr(Element element, String name) {
        var value = attr(element, name);
        if (value.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            try {
                return (int) Math.floor(Double.parseDouble(value));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
    }

    private static String trimMessage(String message) {
        if (message == null) {
            return null;
        }
        var normalized = message.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() <= 400) {
            return normalized;
        }
        return normalized.substring(0, 400);
    }

    private static Mode parseMode(String raw) {
        var normalized = raw == null ? "run" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "run" -> Mode.RUN;
            case "test" -> Mode.TEST;
            default -> throw new IllegalArgumentException("Invalid --mode. Use run|test");
        };
    }

    private final class TestResourcesPoller {
        private final Path project;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private Thread thread;

        private TestResourcesPoller(Path project) {
            this.project = project;
        }

        private void start() {
            thread = Thread.ofVirtual().name("pyronaut-tui-test-resources").unstarted(this::run);
            thread.start();
        }

        private void stop() {
            running.set(false);
            if (thread != null) {
                thread.interrupt();
            }
        }

        private void run() {
            int failures = 0;
            while (running.get() && !shuttingDown.get()) {
                refreshTestResourcesLogs(project);
                boolean ok = refreshTestResourcesSnapshot(project);
                if (ok) {
                    failures = 0;
                } else {
                    failures = Math.min(failures + 1, 4);
                }
                long multiplier = failures == 0 ? 1 : (1L << Math.min(2, failures));
                long sleepMillis = Math.min(TEST_RESOURCES_MAX_BACKOFF_MILLIS, TEST_RESOURCES_POLL_MILLIS * multiplier);
                try {
                    Thread.sleep(sleepMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private final class TestResourcesLogTail {
        private final Path project;
        private final Map<Path, Integer> lineOffsets = new HashMap<>();

        private TestResourcesLogTail(Path project) {
            this.project = project;
        }

        private void refresh() {
            Path logsDir = project.resolve(TEST_RESOURCES_LOGS_DIR);
            if (!Files.isDirectory(logsDir)) {
                lineOffsets.clear();
                return;
            }
            List<Path> logFiles;
            try (var stream = Files.list(logsDir)) {
                logFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".log"))
                    .sorted()
                    .toList();
            } catch (IOException e) {
                appendLog("[test-resources] failed reading log directory: " + e.getMessage());
                return;
            }
            lineOffsets.keySet().removeIf(path -> !logFiles.contains(path));
            boolean prefixWithFile = logFiles.size() > 1;
            for (Path logFile : logFiles) {
                tailFile(logFile, prefixWithFile);
            }
        }

        private void tailFile(Path logFile, boolean prefixWithFile) {
            final List<String> lines;
            try {
                lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            } catch (IOException e) {
                appendLog("[test-resources] failed reading log file " + logFile.getFileName() + ": " + e.getMessage());
                return;
            }
            int offset = lineOffsets.getOrDefault(logFile, 0);
            if (offset > lines.size()) {
                offset = 0;
            }
            for (int i = offset; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line == null || line.isEmpty()) {
                    continue;
                }
                String sanitized = stripAnsi(line);
                controller.addTestResourcesOutput(prefixWithFile
                    ? "[" + logFile.getFileName() + "] " + sanitized
                    : sanitized);
            }
            lineOffsets.put(logFile, lines.size());
        }
    }

    private static String stripAnsi(String line) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        return ANSI_ESCAPE_PATTERN.matcher(line).replaceAll("");
    }

    private final class WatchLoop {
        private final Path project;
        private final Mode mode;
        private final Set<String> watchedRoots;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final WatchService watchService;
        private final Map<WatchKey, Path> keyToDirectory = new HashMap<>();
        private final Set<Path> changedPaths = new HashSet<>();
        private Thread thread;
        private long lastChangeAt = -1L;

        private WatchLoop(Path project, Mode mode) throws IOException {
            this.project = project;
            this.mode = mode;
            this.watchedRoots = watchRootsForMode(mode);
            this.watchService = FileSystems.getDefault().newWatchService();
            registerRoots();
        }

        private void start() {
            thread = Thread.ofVirtual().name("pyronaut-tui-watcher").unstarted(this::run);
            thread.start();
        }

        private void stop() {
            running.set(false);
            try {
                watchService.close();
            } catch (IOException ignored) {
            }
            if (thread != null) {
                thread.interrupt();
            }
        }

        private void run() {
            while (running.get() && !shuttingDown.get()) {
                try {
                    WatchKey key = watchService.poll(100, TimeUnit.MILLISECONDS);
                    if (key != null) {
                        collectChanges(key);
                    }
                    maybeFlushChanges();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    if (running.get()) {
                        controller.notify("File watcher error: " + e.getMessage(), UiModel.Severity.WARNING);
                    }
                    return;
                }
            }
        }

        private void collectChanges(WatchKey key) {
            var base = keyToDirectory.get(key);
            if (base == null) {
                key.reset();
                return;
            }
            for (WatchEvent<?> rawEvent : key.pollEvents()) {
                var kind = rawEvent.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                var event = (WatchEvent<Path>) rawEvent;
                var changed = base.resolve(event.context()).toAbsolutePath().normalize();
                if (Files.isDirectory(changed)) {
                    if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                        registerDirectoryRecursively(changed);
                    }
                    continue;
                }
                var relative = project.relativize(changed);
                if (!isRelevant(relative)) {
                    continue;
                }
                changedPaths.add(relative);
                lastChangeAt = System.currentTimeMillis();
            }
            key.reset();
        }

        private void maybeFlushChanges() {
            if (changedPaths.isEmpty() || lastChangeAt < 0) {
                return;
            }
            long idleMillis = System.currentTimeMillis() - lastChangeAt;
            if (idleMillis < WATCH_DEBOUNCE_MILLIS) {
                return;
            }
            var batch = changedPaths.stream().sorted().toList();
            changedPaths.clear();
            controlExecutor.submit(() -> onWatchChanges(mode, batch));
        }

        private void registerRoots() {
            for (var rootName : watchedRoots) {
                var root = project.resolve(rootName).normalize();
                registerDirectoryRecursively(root);
            }
        }

        private void registerDirectoryRecursively(Path root) {
            if (!Files.isDirectory(root)) {
                return;
            }
            try {
                Files.walk(root)
                    .filter(Files::isDirectory)
                    .forEach(dir -> {
                        try {
                            var key = dir.register(
                                watchService,
                                StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_DELETE,
                                StandardWatchEventKinds.ENTRY_MODIFY
                            );
                            keyToDirectory.put(key, dir);
                        } catch (IOException ignored) {
                        }
                    });
            } catch (IOException ignored) {
            }
        }

        private boolean isRelevant(Path relativePath) {
            if (relativePath.getNameCount() == 0) {
                return false;
            }
            var first = relativePath.getName(0).toString();
            if ("__pyronaut__".equals(first) || ".pytest_cache".equals(first) || "build".equals(first) || ".gradle".equals(first)) {
                return false;
            }
            return watchedRoots.contains(first);
        }
    }

    private final class IncrementalEventStream implements AutoCloseable {
        private final Path eventsFile;
        private final AtomicBoolean running = new AtomicBoolean();
        private final IncrementalTestTracker tracker = new IncrementalTestTracker();
        private Thread thread;
        private long offset;
        private final StringBuilder tailBuffer = new StringBuilder();

        private IncrementalEventStream(Path eventsFile) {
            this.eventsFile = eventsFile;
        }

        private void start() {
            tracker.reset();
            running.set(true);
            thread = Thread.ofVirtual().name("pyronaut-tui-test-events").unstarted(this::runLoop);
            thread.start();
        }

        private void runLoop() {
            while (running.get() && !shuttingDown.get()) {
                try {
                    drainNewLines();
                    Thread.sleep(80);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ignored) {
                }
            }
            try {
                drainNewLines();
            } catch (Exception ignored) {
            }
        }

        private void drainNewLines() throws IOException {
            if (!Files.exists(eventsFile)) {
                return;
            }
            byte[] bytes = Files.readAllBytes(eventsFile);
            if (bytes.length < offset) {
                offset = 0;
                tailBuffer.setLength(0);
                tracker.reset();
            }
            if (bytes.length == offset) {
                return;
            }
            int start = (int) offset;
            offset = bytes.length;
            tailBuffer.append(new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8));
            int lineBreak;
            while ((lineBreak = tailBuffer.indexOf("\n")) >= 0) {
                String line = tailBuffer.substring(0, lineBreak).trim();
                tailBuffer.delete(0, lineBreak + 1);
                if (!line.isEmpty()) {
                    tracker.accept(line);
                }
            }
        }

        @Override
        public void close() {
            running.set(false);
            if (thread != null) {
                thread.interrupt();
                try {
                    thread.join(500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private final class IncrementalTestTracker {
        private final Map<String, Integer> classNodeIds = new LinkedHashMap<>();
        private final Map<String, Integer> methodNodeIds = new LinkedHashMap<>();
        private final Map<String, UiModel.Status> methodStatuses = new LinkedHashMap<>();
        private int nextNodeId = 1;
        private String activeRunId;
        private long lastSeq;

        private void reset() {
            classNodeIds.clear();
            methodNodeIds.clear();
            methodStatuses.clear();
            nextNodeId = 1;
            activeRunId = null;
            lastSeq = 0;
            controller.startTesting();
            controller.resetTestTree();
            controller.updateTestSummary(0, 0, 0, 0, 0);
        }

        private void accept(String line) {
            String eventType = jsonString(line, "eventType");
            if (eventType == null) {
                return;
            }
            String runId = jsonString(line, "runId");
            long seq = jsonLong(line, "seq");
            if (activeRunId == null && "session_started".equals(eventType)) {
                activeRunId = runId;
                lastSeq = seq;
            }
            if (activeRunId != null && runId != null && !activeRunId.equals(runId)) {
                return;
            }
            if (seq > 0 && seq <= lastSeq) {
                return;
            }
            if (seq > 0) {
                lastSeq = seq;
            }

            switch (eventType) {
                case "session_started" -> {
                    controller.startTesting();
                    controller.resetTestTree();
                    controller.notify("Tests running (live updates)", UiModel.Severity.INFO);
                }
                case "test_started" -> onTestStarted(jsonString(line, "testId"));
                case "test_output" -> onTestOutput(jsonString(line, "testId"), jsonString(line, "stream"), jsonString(line, "text"));
                case "test_finished" -> onTestFinished(jsonString(line, "testId"), jsonString(line, "status"), jsonString(line, "failure"));
                case "session_finished" -> updateSummary();
                default -> {
                }
            }
        }

        private void onTestStarted(String testId) {
            if (testId == null || testId.isBlank()) {
                return;
            }
            int methodId = ensureNode(testId);
            methodStatuses.put(testId, UiModel.Status.RUNNING);
            controller.nodeStarted(methodId);
            updateSummary();
        }

        private void onTestOutput(String testId, String stream, String text) {
            if (testId == null || testId.isBlank() || text == null || text.isBlank()) {
                return;
            }
            int methodId = ensureNode(testId);
            String prefix = stream == null || stream.isBlank() ? "output" : stream;
            controller.addTestLog(methodId, "[" + prefix + "] " + text.strip());
        }

        private void onTestFinished(String testId, String status, String failureMessage) {
            if (testId == null || testId.isBlank()) {
                return;
            }
            int methodId = ensureNode(testId);
            UiModel.Status mapped = mapTestStatus(status);
            methodStatuses.put(testId, mapped);
            controller.nodeFinished(methodId, mapped, failureMessage);
            updateSummary();
        }

        private int ensureNode(String testId) {
            Integer existing = methodNodeIds.get(testId);
            if (existing != null) {
                return existing;
            }
            String className = testId;
            String methodName = testId;
            int sep = testId.indexOf("::");
            if (sep >= 0) {
                className = testId.substring(0, sep);
                methodName = testId.substring(sep + 2);
            }

            Integer classId = classNodeIds.get(className);
            if (classId == null) {
                classId = nextNodeId++;
                classNodeIds.put(className, classId);
                controller.addTestNode(classId, 0, (byte) 1, className, className);
            }

            int methodId = nextNodeId++;
            methodNodeIds.put(testId, methodId);
            controller.addTestNode(methodId, classId, (byte) 2, methodName, methodName);
            return methodId;
        }

        private UiModel.Status mapTestStatus(String status) {
            if (status == null) {
                return UiModel.Status.PASSED;
            }
            return switch (status.trim().toUpperCase(Locale.ROOT)) {
                case "FAILED" -> UiModel.Status.FAILED;
                case "ABORTED", "SKIPPED" -> UiModel.Status.SKIPPED;
                case "RUNNING" -> UiModel.Status.RUNNING;
                case "PENDING" -> UiModel.Status.PENDING;
                default -> UiModel.Status.PASSED;
            };
        }

        private void updateSummary() {
            long passed = methodStatuses.values().stream().filter(s -> s == UiModel.Status.PASSED).count();
            long failed = methodStatuses.values().stream().filter(s -> s == UiModel.Status.FAILED).count();
            long skipped = methodStatuses.values().stream().filter(s -> s == UiModel.Status.SKIPPED).count();
            long running = methodStatuses.values().stream().filter(s -> s == UiModel.Status.RUNNING).count();
            long pending = methodStatuses.values().stream().filter(s -> s == UiModel.Status.PENDING).count();
            controller.updateTestSummary(passed, failed, skipped, running, pending);
        }

        private static String jsonString(String line, String key) {
            int keyIndex = line.indexOf('"' + key + '"');
            if (keyIndex < 0) {
                return null;
            }
            int colon = line.indexOf(':', keyIndex);
            if (colon < 0) {
                return null;
            }
            int pos = colon + 1;
            while (pos < line.length() && Character.isWhitespace(line.charAt(pos))) {
                pos++;
            }
            if (pos >= line.length() || line.startsWith("null", pos)) {
                return null;
            }
            if (line.charAt(pos) != '"') {
                return null;
            }
            pos++;
            var out = new StringBuilder();
            boolean escaping = false;
            while (pos < line.length()) {
                char ch = line.charAt(pos++);
                if (escaping) {
                    out.append(switch (ch) {
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        case '"' -> '"';
                        case '\\' -> '\\';
                        default -> ch;
                    });
                    escaping = false;
                    continue;
                }
                if (ch == '\\') {
                    escaping = true;
                    continue;
                }
                if (ch == '"') {
                    break;
                }
                out.append(ch);
            }
            return out.toString();
        }

        private static long jsonLong(String line, String key) {
            int keyIndex = line.indexOf('"' + key + '"');
            if (keyIndex < 0) {
                return 0L;
            }
            int colon = line.indexOf(':', keyIndex);
            if (colon < 0) {
                return 0L;
            }
            int pos = colon + 1;
            while (pos < line.length() && Character.isWhitespace(line.charAt(pos))) {
                pos++;
            }
            int end = pos;
            while (end < line.length() && Character.isDigit(line.charAt(end))) {
                end++;
            }
            if (end == pos) {
                return 0L;
            }
            try {
                return Long.parseLong(line.substring(pos, end));
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
    }

    private static final class ManagedProcess {
        private final long generation;
        private final Process process;
        private final String managementServerUri;

        private ManagedProcess(long generation, Process process, String managementServerUri) {
            this.generation = generation;
            this.process = process;
            this.managementServerUri = managementServerUri;
        }
    }

    private record ManagedCommand(List<String> command, String managementServerUri) {
    }

    private record ControlPanelSettings(boolean enabled, String path) {
        private static ControlPanelSettings disabled() {
            return new ControlPanelSettings(false, "/control-panel");
        }
    }

    private record RouteEntry(String key, String value) {
    }

    private static final class RouteDisplay {
        private final String original;
        private final String method;
        private final String path;

        private RouteDisplay(String original, String method, String path) {
            this.original = original;
            this.method = method;
            this.path = path;
        }

        private static RouteDisplay parse(String route) {
            if (route == null) {
                return new RouteDisplay("", "", null);
            }
            int firstSpace = route.indexOf(' ');
            if (firstSpace > 0 && firstSpace + 1 < route.length() && route.charAt(firstSpace + 1) == '/') {
                return new RouteDisplay(route, route.substring(0, firstSpace), route.substring(firstSpace + 1));
            }
            return new RouteDisplay(route, route, null);
        }

        private String render(int methodWidth) {
            if (path == null) {
                return original;
            }
            return String.format("%-" + methodWidth + "s %s", method, path);
        }
    }

    private static final class TestCaseResult {
        private final String className;
        private final String name;
        private final UiModel.Status status;
        private final String message;

        private TestCaseResult(String className, String name, UiModel.Status status, String message) {
            this.className = className == null ? "" : className;
            this.name = name == null ? "" : name;
            this.status = status;
            this.message = message;
        }
    }

    private static final class ReportSummary {
        private final int total;
        private final int passed;
        private final int failed;
        private final int skipped;
        private final List<TestCaseResult> cases;

        private ReportSummary(int total, int passed, int failed, int skipped, List<TestCaseResult> cases) {
            this.total = total;
            this.passed = passed;
            this.failed = failed;
            this.skipped = skipped;
            this.cases = cases;
        }
    }

    private record TestResourcesConnection(String serverUri, String token, String readTimeout) {
        private static final String SERVER_URI_KEY = "micronaut.test.resources.server.uri";
        private static final String TOKEN_KEY = "micronaut.test.resources.server.access.token";
        private static final String READ_TIMEOUT_KEY = "micronaut.test.resources.server.client.read.timeout";

        private boolean isUsable() {
            return serverUri != null && !serverUri.isBlank() && token != null && !token.isBlank();
        }

        private void applyToEnvironment(Map<String, String> env) {
            setEnvironmentValue(env, "MICRONAUT_TEST_RESOURCES_SERVER_URI", serverUri);
            setEnvironmentValue(env, "MICRONAUT_TEST_RESOURCES_SERVER_ACCESS_TOKEN", token);
            setEnvironmentValue(env, "MICRONAUT_TEST_RESOURCES_SERVER_CLIENT_READ_TIMEOUT", readTimeout);
        }

        private void appendJvmArgs(List<String> command) {
            appendJvmArg(command, SERVER_URI_KEY, serverUri);
            appendJvmArg(command, TOKEN_KEY, token);
            appendJvmArg(command, READ_TIMEOUT_KEY, readTimeout);
        }

        private static void appendJvmArg(List<String> command, String key, String value) {
            if (value == null || value.isBlank()) {
                return;
            }
            command.add("-D" + key + "=" + value);
        }

        private static TestResourcesConnection fromProperties(Properties properties) {
            return new TestResourcesConnection(
                properties.getProperty("server.uri"),
                properties.getProperty("server.access.token"),
                properties.getProperty("server.client.read.timeout")
            );
        }

        private static TestResourcesConnection fromEnvironment(Map<String, String> environment) {
            String javaToolOptions = environment.getOrDefault("JAVA_TOOL_OPTIONS", "");
            return new TestResourcesConnection(
                firstNonBlank(environment.get("MICRONAUT_TEST_RESOURCES_SERVER_URI"), readSystemProperty(javaToolOptions, SERVER_URI_KEY)),
                firstNonBlank(environment.get("MICRONAUT_TEST_RESOURCES_SERVER_ACCESS_TOKEN"), readSystemProperty(javaToolOptions, TOKEN_KEY)),
                firstNonBlank(environment.get("MICRONAUT_TEST_RESOURCES_SERVER_CLIENT_READ_TIMEOUT"), readSystemProperty(javaToolOptions, READ_TIMEOUT_KEY))
            );
        }

        private static String readSystemProperty(String options, String key) {
            if (options == null || options.isBlank()) {
                return null;
            }
            String marker = "-D" + key + "=";
            int start = options.indexOf(marker);
            if (start < 0) {
                return null;
            }
            int index = start + marker.length();
            StringBuilder value = new StringBuilder();
            boolean escaping = false;
            while (index < options.length()) {
                char ch = options.charAt(index++);
                if (escaping) {
                    value.append(ch);
                    escaping = false;
                    continue;
                }
                if (ch == '\\') {
                    escaping = true;
                    continue;
                }
                if (Character.isWhitespace(ch)) {
                    break;
                }
                value.append(ch);
            }
            return value.toString();
        }
    }

    private enum ManagedCommandTarget {
        RUN,
        TEST
    }
}

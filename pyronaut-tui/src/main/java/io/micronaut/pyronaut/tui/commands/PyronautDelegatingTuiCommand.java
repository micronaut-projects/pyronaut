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
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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

@Command(name = "delegating-tui", description = "Runs the Tamboui TUI delegated to v2 standalone commands", mixinStandardHelpOptions = true)
public final class PyronautDelegatingTuiCommand implements Callable<Integer> {

    private static final int PRECONDITION_FAILED = 8;
    private static final String EVENTS_REPORT = "events.ndjson";
    private static final long WATCH_DEBOUNCE_MILLIS = 250;
    private static final long TEST_RESOURCES_POLL_MILLIS = 2000;
    private static final long TEST_RESOURCES_MAX_BACKOFF_MILLIS = 8000;
    private static final Pattern SERVER_URI = Pattern.compile("Server Running:\\s*(\\S+)");

    @Option(names = "--project-dir", required = true, description = "Project directory")
    Path projectDir;

    @Option(names = "--mode", defaultValue = "run", description = "Initial mode: run|test")
    String mode;

    @Option(names = "--report-dir", description = "Path to pyronaut-test report directory")
    Path reportDir;

    @Option(names = "--install-executable", required = true, description = "Path to pyronaut-install executable")
    Path installExecutable;

    @Option(names = "--process-executable", required = true, description = "Path to pyronaut-processor executable")
    Path processExecutable;

    @Option(names = "--run-executable", required = true, description = "Path to pyronaut-run executable")
    Path runExecutable;

    @Option(names = "--test-executable", required = true, description = "Path to pyronaut-test executable")
    Path testExecutable;

    @Option(names = "--trace-delegation", description = "Log delegated command lines")
    boolean traceDelegation;

    private final ExecutorService controlExecutor = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
    private final AtomicReference<ManagedProcess> activeProcess = new AtomicReference<>();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final AtomicLong executionGeneration = new AtomicLong();
    private final AtomicReference<WatchLoop> watchLoop = new AtomicReference<>();
    private final AtomicReference<TestResourcesPoller> testResourcesPoller = new AtomicReference<>();
    private final HttpClient insightsClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private UiController controller;
    private PyronautTui tui;
    private volatile Mode activeMode;
    private Path currentProject;
    private Path currentReportDir;

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
        controller.resetState();
        controller.setRunning();
        tui.setMode(Mode.RUN);
        runMode(project, true);
    }

    private void switchToTest(Path project, Path reports) {
        activeMode = Mode.TEST;
        controller.resetState();
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
        controller.notify(reason + " (install -> process -> run)", UiModel.Severity.INFO);
        controller.startCompiling();

        var installCode = runForeground(project, List.of(installExecutable.toString(), "--project-dir", project.toString()), false);
        if (installCode != 0) {
            controller.stopCompiling();
            controller.notify("Install failed with exit code " + installCode, UiModel.Severity.ERROR);
            return;
        }
        var processCode = runForeground(project, List.of(processExecutable.toString(), "--project-dir", project.toString()), false);
        if (processCode != 0) {
            controller.stopCompiling();
            controller.notify("Process failed with exit code " + processCode, UiModel.Severity.ERROR);
            return;
        }
        controller.stopCompiling();

        var runCommand = List.of(runExecutable.toString(), "--project-dir", project.toString());
        if (traceDelegation) {
            controller.addActivityOutput("[tui-delegate] " + String.join(" ", runCommand));
        }

        try {
            long generation = executionGeneration.incrementAndGet();
            var process = startProcess(project, runCommand);
            activeProcess.set(new ManagedProcess(generation, process));
            controller.setRunning();
            controller.notify(restart ? "Run command restarted" : "Run command started", UiModel.Severity.SUCCESS);
            attachOutputReaders(process, true);
            Thread.ofVirtual().start(() -> onBackgroundProcessExit(generation, process, "Run command"));
        } catch (IOException e) {
            controller.notify("Failed starting run command: " + e.getMessage(), UiModel.Severity.ERROR);
        }
    }

    private void testModeCycle(Path project, Path reports, String reason, boolean restart) {
        shutdownActiveProcess();
        controller.startTesting();
        if (restart) {
            controller.notify("Change detected, rerunning tests", UiModel.Severity.INFO);
        }
        controller.notify(reason + " (install -> process -> test)", UiModel.Severity.INFO);
        controller.startCompiling();

        var installCode = runForeground(project, List.of(installExecutable.toString(), "--project-dir", project.toString()), false);
        if (installCode != 0) {
            controller.stopCompiling();
            controller.notify("Install failed with exit code " + installCode, UiModel.Severity.ERROR);
            controller.stopTesting();
            return;
        }

        var processCode = runForeground(project, List.of(processExecutable.toString(), "--project-dir", project.toString()), false);
        if (processCode != 0) {
            controller.stopCompiling();
            controller.notify("Process failed with exit code " + processCode, UiModel.Severity.ERROR);
            controller.stopTesting();
            return;
        }
        controller.stopCompiling();

        var testCode = runForegroundWithIncrementalEvents(
            project,
            List.of(testExecutable.toString(), "--project-dir", project.toString()),
            reports.resolve(EVENTS_REPORT)
        );
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
            activeProcess.set(new ManagedProcess(generation, process));
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
            activeProcess.set(new ManagedProcess(generation, process));
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
        return builder.start();
    }

    private void attachOutputReaders(Process process, boolean parseServerUri) {
        Thread.ofVirtual().start(() -> consumeOutput(process, parseServerUri));
    }

    private void consumeOutput(Process process, boolean parseServerUri) {
        try (var reader = new BufferedReader(process.inputReader())) {
            String line;
            while ((line = reader.readLine()) != null) {
                controller.addActivityOutput(line);
                if (parseServerUri) {
                    var matcher = SERVER_URI.matcher(line);
                    if (matcher.find()) {
                        controller.setUrl(matcher.group(1));
                    }
                }
            }
        } catch (IOException e) {
            controller.notify("Failed reading command output: " + e.getMessage(), UiModel.Severity.WARNING);
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
        controller.setTestResourcesLoading();
        var poller = new TestResourcesPoller(project);
        testResourcesPoller.set(poller);
        poller.start();
    }

    private void stopTestResourcesPolling() {
        var existing = testResourcesPoller.getAndSet(null);
        if (existing != null) {
            existing.stop();
        }
    }

    private boolean refreshTestResourcesSnapshot(Path project) {
        try {
            var settingsFile = project.resolve(".micronaut/test-resources/test-resources.properties");
            if (!Files.exists(settingsFile)) {
                controller.setTestResourcesUnavailable("waiting for .micronaut/test-resources/test-resources.properties");
                return false;
            }

            var properties = new Properties();
            try (var in = Files.newInputStream(settingsFile)) {
                properties.load(in);
            }
            var serverUri = properties.getProperty("server.uri");
            var token = properties.getProperty("server.access.token");
            if (serverUri == null || serverUri.isBlank()) {
                controller.setTestResourcesUnavailable("server.uri missing in test-resources properties");
                return false;
            }
            if (token == null || token.isBlank()) {
                controller.setTestResourcesUnavailable("server.access.token missing in test-resources properties");
                return false;
            }

            var health = fetchInsights(serverUri, "/api/test-resources/health", token);
            if (health.statusCode() == 401 || health.statusCode() == 403) {
                controller.setTestResourcesAuthFailed("insights auth failed (" + health.statusCode() + ")");
                return false;
            }
            if (health.statusCode() != 200) {
                controller.setTestResourcesError("health endpoint failed with status " + health.statusCode());
                return false;
            }

            var containers = fetchInsights(serverUri, "/api/test-resources/containers", token);
            var propertiesResponse = fetchInsights(serverUri, "/api/test-resources/properties", token);
            var errors = fetchInsights(serverUri, "/api/test-resources/errors", token);
            if (containers.statusCode() != 200 || propertiesResponse.statusCode() != 200 || errors.statusCode() != 200) {
                controller.setTestResourcesError(
                    "insights endpoints failed (containers=" + containers.statusCode()
                        + ", properties=" + propertiesResponse.statusCode()
                        + ", errors=" + errors.statusCode() + ")"
                );
                return false;
            }

            var healthStatus = jsonString(health.body(), "status");
            var healthUri = jsonString(health.body(), "uri");
            var healthPort = jsonLong(health.body(), "port");
            String healthMessage = (healthStatus == null ? "UNKNOWN" : healthStatus)
                + (healthUri == null ? "" : " @ " + healthUri)
                + (healthPort >= 0 ? " (port " + healthPort + ")" : "");

            controller.setTestResourcesRunning(
                healthMessage,
                summarizeArrayPayload(containers.body(), "containers"),
                summarizeArrayPayload(propertiesResponse.body(), "properties"),
                summarizeArrayPayload(errors.body(), "errors")
            );
            return true;
        } catch (Exception e) {
            controller.setTestResourcesError("insights refresh failed: " + e.getMessage());
            return false;
        }
    }

    private HttpResponse<String> fetchInsights(String serverUri, String path, String token) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(serverUri + path))
            .header("Authorization", "Bearer " + token)
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();
        return insightsClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static List<String> summarizeArrayPayload(String body, String key) {
        var array = extractArrayContent(body, key);
        if (array == null || array.isBlank()) {
            return List.of();
        }
        var trimmed = array.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return List.of(trimmed);
        }
        var split = trimmed.split("\\},\\{");
        var rows = new ArrayList<String>(split.length);
        for (String raw : split) {
            String object = raw;
            if (!object.startsWith("{")) {
                object = "{" + object;
            }
            if (!object.endsWith("}")) {
                object = object + "}";
            }
            rows.add(summarizeObject(key, object));
        }
        return rows;
    }

    private static String summarizeObject(String key, String object) {
        return switch (key) {
            case "containers" -> {
                String name = firstNonBlank(jsonString(object, "name"), "<unknown>");
                String status = firstNonBlank(jsonString(object, "status"), "unknown");
                String image = firstNonBlank(jsonString(object, "image"), "unknown-image");
                String scope = firstNonBlank(jsonString(object, "scope"), "default");
                yield name + " [" + status + "] image=" + image + " scope=" + scope;
            }
            case "properties" -> {
                String keyName = firstNonBlank(jsonString(object, "key"), "<key>");
                String value = firstNonBlank(jsonString(object, "value"), "<value>");
                String resolver = firstNonBlank(jsonString(object, "resolver"), "resolver");
                String scope = firstNonBlank(jsonString(object, "scope"), "default");
                yield keyName + "=" + value + " (" + resolver + "/" + scope + ")";
            }
            case "errors" -> {
                String property = firstNonBlank(jsonString(object, "property"), "<property>");
                String resolver = firstNonBlank(jsonString(object, "resolver"), "resolver");
                String message = firstNonBlank(jsonString(object, "message"), "unknown error");
                yield property + " [" + resolver + "] " + message;
            }
            default -> object;
        };
    }

    private static String extractArrayContent(String body, String key) {
        String marker = "\"" + key + "\"";
        int keyIndex = body.indexOf(marker);
        if (keyIndex < 0) {
            return null;
        }
        int colon = body.indexOf(':', keyIndex + marker.length());
        if (colon < 0) {
            return null;
        }
        int start = body.indexOf('[', colon + 1);
        if (start < 0) {
            return null;
        }
        int depth = 0;
        for (int i = start; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch == '[') {
                depth++;
            } else if (ch == ']') {
                depth--;
                if (depth == 0) {
                    return body.substring(start + 1, i);
                }
            }
        }
        return null;
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
        return body.substring(firstQuote + 1, secondQuote);
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
        var lastNodeId = readOptionalTrimmed(reportsDir.resolve(".pyronaut-last-nodeid.txt"));
        if (!Files.exists(junit)) {
            return new ReportSummary(0, 0, 0, 0, List.of(), lastNodeId);
        }

        try {
            var dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            var document = dbf.newDocumentBuilder().parse(junit.toFile());
            return summarizeDocument(document, lastNodeId);
        } catch (Exception e) {
            controller.notify("Failed parsing junit report: " + e.getMessage(), UiModel.Severity.WARNING);
            return new ReportSummary(0, 0, 0, 0, List.of(), lastNodeId);
        }
    }

    private ReportSummary summarizeDocument(Document document, String lastNodeId) {
        var suites = new ArrayList<Element>();
        var root = document.getDocumentElement();
        if (root == null) {
            return new ReportSummary(0, 0, 0, 0, List.of(), lastNodeId);
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
        return new ReportSummary(total, passed, failed, skipped, cases, lastNodeId);
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
        if (summary.lastNodeId != null && !summary.lastNodeId.isBlank()) {
            controller.addActivityOutput("[tui] last failing test: " + summary.lastNodeId);
        }
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

    private static String readOptionalTrimmed(Path path) {
        if (!Files.exists(path)) {
            return null;
        }
        try {
            var text = Files.readString(path).trim();
            return text.isEmpty() ? null : text;
        } catch (IOException e) {
            return null;
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

        private ManagedProcess(long generation, Process process) {
            this.generation = generation;
            this.process = process;
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
        private final String lastNodeId;

        private ReportSummary(int total, int passed, int failed, int skipped, List<TestCaseResult> cases, String lastNodeId) {
            this.total = total;
            this.passed = passed;
            this.failed = failed;
            this.skipped = skipped;
            this.cases = cases;
            this.lastNodeId = lastNodeId;
        }
    }
}

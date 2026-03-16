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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

@Command(name = "delegating-tui", description = "Runs the Tamboui TUI delegated to v2 standalone commands", mixinStandardHelpOptions = true)
public final class PyronautDelegatingTuiCommand implements Callable<Integer> {

    private static final int PRECONDITION_FAILED = 8;
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
    private final AtomicReference<Process> activeProcess = new AtomicReference<>();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    private UiController controller;
    private PyronautTui tui;

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

        tui.setOnQuit(() -> controlExecutor.submit(this::shutdownActiveProcess));
        tui.setOnRunRequested(() -> controlExecutor.submit(() -> switchToRun(resolvedProject)));
        tui.setOnTestRequested(() -> controlExecutor.submit(() -> switchToTest(resolvedProject, resolvedReportDir)));

        if (initialMode == Mode.RUN) {
            controlExecutor.submit(() -> runMode(resolvedProject));
        } else {
            controlExecutor.submit(() -> testMode(resolvedProject, resolvedReportDir));
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
            controlExecutor.shutdownNow();
            shutdownActiveProcess();
            StreamsCapture.getInstance().restore();
        }
    }

    private void switchToRun(Path project) {
        controller.resetState();
        controller.setRunning();
        tui.setMode(Mode.RUN);
        runMode(project);
    }

    private void switchToTest(Path project, Path reports) {
        controller.resetState();
        controller.startTesting();
        tui.setMode(Mode.TEST);
        testMode(project, reports);
    }

    private void runMode(Path project) {
        shutdownActiveProcess();
        controller.notify("Preparing run workflow (install -> process -> run)", UiModel.Severity.INFO);

        var installCode = runForeground(project, List.of(installExecutable.toString(), "--project-dir", project.toString()), false);
        if (installCode != 0) {
            controller.notify("Install failed with exit code " + installCode, UiModel.Severity.ERROR);
            return;
        }
        var processCode = runForeground(project, List.of(processExecutable.toString(), "--project-dir", project.toString()), false);
        if (processCode != 0) {
            controller.notify("Process failed with exit code " + processCode, UiModel.Severity.ERROR);
            return;
        }

        var runCommand = List.of(runExecutable.toString(), "--project-dir", project.toString());
        if (traceDelegation) {
            controller.addActivityOutput("[tui-delegate] " + String.join(" ", runCommand));
        }

        try {
            var process = startProcess(project, runCommand);
            activeProcess.set(process);
            controller.setRunning();
            controller.notify("Run command started", UiModel.Severity.SUCCESS);
            attachOutputReaders(process, true);
            Thread.ofVirtual().start(() -> {
                var code = waitFor(process);
                if (activeProcess.compareAndSet(process, null) && !shuttingDown.get()) {
                    if (code == 0) {
                        controller.notify("Run command exited", UiModel.Severity.INFO);
                    } else {
                        controller.notify("Run command exited with code " + code, UiModel.Severity.ERROR);
                    }
                }
            });
        } catch (IOException e) {
            controller.notify("Failed starting run command: " + e.getMessage(), UiModel.Severity.ERROR);
        }
    }

    private void testMode(Path project, Path reports) {
        shutdownActiveProcess();
        controller.startTesting();
        controller.notify("Executing test workflow", UiModel.Severity.INFO);

        var installCode = runForeground(project, List.of(installExecutable.toString(), "--project-dir", project.toString()), false);
        if (installCode != 0) {
            controller.notify("Install failed with exit code " + installCode, UiModel.Severity.ERROR);
            controller.stopTesting();
            return;
        }

        var processCode = runForeground(project, List.of(processExecutable.toString(), "--project-dir", project.toString()), false);
        if (processCode != 0) {
            controller.notify("Process failed with exit code " + processCode, UiModel.Severity.ERROR);
            controller.stopTesting();
            return;
        }

        var testCode = runForeground(project, List.of(testExecutable.toString(), "--project-dir", project.toString()), true);
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
            var process = startProcess(project, command);
            activeProcess.set(process);
            var reader = Thread.ofVirtual().unstarted(() -> consumeOutput(process, parseServerUri));
            reader.start();
            var code = waitFor(process);
            reader.join(1000);
            activeProcess.compareAndSet(process, null);
            return code;
        } catch (IOException e) {
            controller.notify("Failed running command: " + e.getMessage(), UiModel.Severity.ERROR);
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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

    private void shutdownActiveProcess() {
        var process = activeProcess.getAndSet(null);
        if (process == null) {
            return;
        }
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

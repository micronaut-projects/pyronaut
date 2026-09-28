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
package io.micronaut.pyronaut.test;

import io.micronaut.pyronaut.config.terminal.Glyphs;
import io.micronaut.pyronaut.config.terminal.LiveRegion;
import io.micronaut.pyronaut.config.terminal.Terminal;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Console reporter for test execution.
 *
 * <p>On a terminal a live region shows the running tests under a spinner
 * header with pass/fail counts; every finished test collapses into a
 * permanent line and failures print their message beneath it. Without a
 * terminal, or when output is streamed verbosely, deterministic plain lines
 * are printed instead.
 */
public final class TestProgressReporter implements TestExecutionListener, AutoCloseable {
    static final int MAX_RUNNING_ROWS = 6;
    private static final int MAX_FAILURE_LINES = 30;
    private static final int MAX_STACK_FRAMES = 6;

    private final PrintStream output;
    private final boolean interactive;
    private final LiveRegion region;
    private final Object lock = new Object();
    private final Map<TestIdentifier, Long> running = new LinkedHashMap<>();
    private final long startedNanos = System.nanoTime();
    private long total;
    private boolean started;
    private int passed;
    private int failed;
    private int errors;
    private int skipped;

    TestProgressReporter(PrintStream output, boolean tty, boolean color, boolean unicode) {
        this.output = output;
        this.interactive = tty;
        this.region = new LiveRegion(output, tty, color, unicode, this::frameLines);
    }

    /**
     * Create a reporter for the given stream using the terminal's capabilities.
     *
     * @param output the stream to report on
     * @param plain force plain line output, for example while application logs are streamed to the same terminal
     * @return the reporter
     */
    public static TestProgressReporter create(PrintStream output, boolean plain) {
        boolean tty = !plain && Terminal.isInteractive();
        boolean unicode = tty && Terminal.unicodeSupported();
        PrintStream stream = unicode ? new PrintStream(output, true, StandardCharsets.UTF_8) : output;
        return new TestProgressReporter(stream, tty, Terminal.colorEnabled("auto", tty), unicode);
    }

    /**
     * @return whether the reporter owns a live terminal region
     */
    public boolean interactive() {
        return interactive;
    }

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        planStarted(testPlan.countTestIdentifiers(TestIdentifier::isTest));
    }

    void planStarted(long testCount) {
        synchronized (lock) {
            total = testCount;
            started = true;
        }
        region.refresh();
    }

    @Override
    public void executionStarted(TestIdentifier identifier) {
        if (!identifier.isTest()) {
            return;
        }
        synchronized (lock) {
            running.put(identifier, System.nanoTime());
        }
        if (!interactive) {
            output.println("> " + name(identifier));
        }
    }

    @Override
    public void executionSkipped(TestIdentifier identifier, String reason) {
        if (!identifier.isTest()) {
            return;
        }
        synchronized (lock) {
            skipped++;
        }
        String detail = reason == null || reason.isBlank() ? "skipped" : "skipped: " + reason;
        if (interactive) {
            region.printAbove("  " + region.paint(LiveRegion.DIM, glyphs().skipped() + " " + name(identifier) + " (" + detail + ")"));
        } else {
            output.println("SKIPPED " + name(identifier) + " (" + detail + ")");
        }
    }

    @Override
    public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
        if (!identifier.isTest()) {
            if (result.getStatus() == TestExecutionResult.Status.FAILED) {
                containerFailed(identifier, result);
            }
            return;
        }
        long took;
        synchronized (lock) {
            Long started = running.remove(identifier);
            took = started == null ? 0 : System.nanoTime() - started;
            switch (result.getStatus()) {
                case SUCCESSFUL -> passed++;
                case FAILED -> failed++;
                case ABORTED -> skipped++;
                default -> { }
            }
        }
        String name = name(identifier);
        String duration = Terminal.formatDuration(took);
        switch (result.getStatus()) {
            case SUCCESSFUL -> {
                if (interactive) {
                    region.printAbove("  " + region.paint(LiveRegion.GREEN, glyphs().check()) + " " + name + " " + region.paint(LiveRegion.DIM, "(" + duration + ")"));
                } else {
                    output.println("PASSED " + name + " (" + duration + ")");
                }
            }
            case ABORTED -> {
                // pytest reports skips as aborted executions carrying the reason.
                String reason = result.getThrowable().map(Throwable::getMessage)
                    .filter(message -> message != null && !message.isBlank())
                    .map(message -> message.lines().findFirst().orElse("").trim())
                    .map(message -> message.startsWith("Skipped: ") ? message.substring("Skipped: ".length()) : message)
                    .orElse("");
                String detail = reason.isEmpty() ? "skipped" : "skipped: " + reason;
                if (interactive) {
                    region.printAbove("  " + region.paint(LiveRegion.DIM, glyphs().skipped() + " " + name + " (" + detail + ")"));
                } else {
                    output.println("SKIPPED " + name + " (" + detail + ")");
                }
            }
            default -> {
                if (interactive) {
                    region.printAbove("  " + region.paint(LiveRegion.RED, glyphs().cross()) + " " + region.paint(LiveRegion.BOLD, name) + " " + region.paint(LiveRegion.DIM, "(" + duration + ")"));
                } else {
                    output.println("FAILED " + name + " (" + duration + ")");
                }
                result.getThrowable().ifPresent(this::printFailure);
            }
        }
    }

    private void containerFailed(TestIdentifier identifier, TestExecutionResult result) {
        synchronized (lock) {
            errors++;
        }
        if (interactive) {
            region.printAbove("  " + region.paint(LiveRegion.RED, glyphs().cross()) + " " + region.paint(LiveRegion.BOLD, identifier.getDisplayName()) + " " + region.paint(LiveRegion.DIM, "(error)"));
        } else {
            output.println("ERROR " + identifier.getDisplayName());
        }
        result.getThrowable().ifPresent(this::printFailure);
    }

    /**
     * Print the final outcome line and the report location.
     *
     * @param reportDirectory the report directory, or {@code null} when no report was written
     */
    public void summary(Path reportDirectory) {
        int passedCount;
        int failedCount;
        int errorCount;
        int skippedCount;
        synchronized (lock) {
            passedCount = passed;
            failedCount = failed;
            errorCount = errors;
            skippedCount = skipped;
            running.clear();
        }
        int ran = passedCount + failedCount;
        long took = System.nanoTime() - startedNanos;
        String skippedText = skippedCount == 0 ? "" : ", " + skippedCount + " skipped";
        String errorText = errorCount == 0 ? "" : ", " + errorCount + (errorCount == 1 ? " error" : " errors");
        String message;
        String tone;
        String glyph;
        if (failedCount > 0) {
            message = failedCount + " of " + ran + " tests failed" + errorText + skippedText;
            tone = LiveRegion.RED;
            glyph = glyphs().cross();
        } else if (ran == 0) {
            message = "No tests ran" + errorText + skippedText;
            tone = errorCount > 0 ? LiveRegion.RED : LiveRegion.YELLOW;
            glyph = errorCount > 0 ? glyphs().cross() : glyphs().warning();
        } else if (errorCount > 0) {
            message = ran + (ran == 1 ? " test passed" : " tests passed") + errorText + skippedText;
            tone = LiveRegion.RED;
            glyph = glyphs().cross();
        } else {
            message = ran + (ran == 1 ? " test passed" : " tests passed") + skippedText;
            tone = LiveRegion.GREEN;
            glyph = glyphs().check();
        }
        if (interactive) {
            region.printAbove(region.stamp(tone, glyph, message, took));
            if (reportDirectory != null) {
                Path html = reportDirectory.resolve("index.html");
                // Show the file: URL itself: terminals without OSC 8 support
                // still detect it as a link, unlike a relative path.
                String uri = html.toUri().toString();
                region.printAbove(region.paint(LiveRegion.DIM, "  Report: " + Terminal.link(uri, uri)));
            }
        } else {
            output.println(message + " in " + Terminal.formatDuration(took));
            if (reportDirectory != null) {
                // Hyperlink escapes only help on a real terminal; logs get the plain path.
                printReportLocations(output, reportDirectory, Terminal.isInteractive());
            }
        }
    }

    /**
     * Print the report directory and the location of the HTML report.
     *
     * @param output the stream
     * @param reportDirectory the report directory
     * @param hyperlink whether to wrap the report path in a terminal hyperlink
     */
    public static void printReportLocations(PrintStream output, Path reportDirectory, boolean hyperlink) {
        Path directory = reportDirectory.normalize();
        Path html = directory.resolve("index.html");
        output.println("Test reports directory: " + directory);
        String uri = html.toUri().toString();
        output.println("Test report: " + (hyperlink ? Terminal.link(uri, uri) : uri));
    }

    /**
     * Print a neutral line that must reach the console regardless of capture.
     *
     * @param message the message
     */
    public void note(String message) {
        if (interactive) {
            region.printAbove(region.stamp(null, glyphs().bullet(), message, -1));
        } else {
            output.println(message);
        }
    }

    /**
     * Print a diagnostic line that must reach the console regardless of capture.
     *
     * @param message the message
     */
    public void error(String message) {
        if (interactive) {
            region.printAbove(region.stamp(LiveRegion.RED, glyphs().cross(), message, -1));
        } else {
            output.println(message);
        }
    }

    @Override
    public void close() {
        region.close();
    }

    private void printFailure(Throwable throwable) {
        List<String> lines = failureLines(throwable);
        for (String line : lines) {
            if (interactive) {
                region.printAbove("      " + region.paint(LiveRegion.RED, line));
            } else {
                output.println("      " + line);
            }
        }
    }

    static List<String> failureLines(Throwable throwable) {
        List<String> lines = new ArrayList<>();
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            lines.add(throwable.toString());
        } else {
            boolean python = throwable.getClass().getName().contains("Python") || throwable.getClass().getName().contains("pytest");
            if (!python && !message.startsWith(throwable.getClass().getSimpleName())) {
                lines.add(throwable.getClass().getSimpleName() + ": " + message.lines().findFirst().orElse(""));
                message.lines().skip(1).forEach(lines::add);
            } else {
                message.lines().forEach(lines::add);
            }
        }
        // Frames from the JUnit platform, the JDK and the test tooling say
        // nothing about the failure; keep the first frames inside the project.
        int frames = 0;
        boolean pythonFailure = throwable.getClass().getName().contains("Python") || throwable.getClass().getName().contains("pytest");
        for (StackTraceElement element : pythonFailure ? new StackTraceElement[0] : throwable.getStackTrace()) {
            String className = element.getClassName();
            if (className.startsWith("org.junit.") || className.startsWith("java.") || className.startsWith("jdk.")
                || className.startsWith("com.gradle.") || className.startsWith("org.gradle.") || className.startsWith("worker.")
                || className.startsWith("io.micronaut.test.pytest.") || className.startsWith("io.micronaut.pyronaut.test.")
                || className.startsWith("com.oracle.") || className.startsWith("org.graalvm.")) {
                continue;
            }
            if (frames == MAX_STACK_FRAMES) {
                lines.add("  ...");
                break;
            }
            lines.add("  at " + element);
            frames++;
        }
        if (lines.size() > MAX_FAILURE_LINES) {
            List<String> trimmed = new ArrayList<>(lines.subList(0, MAX_FAILURE_LINES));
            trimmed.add("  ... (" + (lines.size() - MAX_FAILURE_LINES) + " more lines in the report)");
            return trimmed;
        }
        return lines;
    }

    /**
     * @param identifier a test identifier
     * @return whether the test is a Java (JUnit) method rather than a pytest node
     */
    public static boolean isJavaTest(TestIdentifier identifier) {
        return identifier.getSource()
            .filter(MethodSource.class::isInstance)
            .map(MethodSource.class::cast)
            .map(MethodSource::getClassName)
            // The pytest engine records the test file path as the class name.
            .filter(className -> !className.contains("/") && !className.contains("\\") && !className.endsWith(".py"))
            .isPresent();
    }

    /**
     * @param identifier a test identifier
     * @return the name shown for the test: {@code SimpleClass.method()} for Java tests, the pytest node id otherwise
     */
    public static String name(TestIdentifier identifier) {
        if (!isJavaTest(identifier)) {
            return identifier.getDisplayName();
        }
        String className = ((MethodSource) identifier.getSource().orElseThrow()).getClassName();
        int dot = className.lastIndexOf('.');
        return (dot >= 0 ? className.substring(dot + 1) : className) + "." + identifier.getDisplayName();
    }

    private Glyphs glyphs() {
        return region.glyphs();
    }


    private List<String> frameLines(LiveRegion region, int spinnerFrame, int width) {
        List<String> lines = new ArrayList<>();
        synchronized (lock) {
            if (!started) {
                // The JVM, the Python runtime and the application context come up
                // before the first test runs; show that this is what is happening.
                lines.add(region.headerRow(spinnerFrame, "Starting test runtime", null, System.nanoTime() - startedNanos, width));
                return lines;
            }
            int done = passed + failed;
            StringBuilder detail = new StringBuilder();
            if (total > 0) {
                detail.append(done).append('/').append(total);
            } else {
                detail.append(done);
            }
            detail.append(" done");
            if (failed > 0) {
                detail.append(", ").append(failed).append(" failed");
            }
            lines.add(region.headerRow(spinnerFrame, "Running tests", detail.toString(), System.nanoTime() - startedNanos, width));
            int shown = 0;
            for (Map.Entry<TestIdentifier, Long> entry : running.entrySet()) {
                if (shown == MAX_RUNNING_ROWS) {
                    lines.add(region.paint(LiveRegion.DIM, "  " + glyphs().branch() + " " + glyphs().ellipsis() + " " + (running.size() - shown) + " more"));
                    break;
                }
                String name = name(entry.getKey());
                String elapsed = Terminal.formatDuration(System.nanoTime() - entry.getValue());
                String left = "  " + glyphs().branch() + " " + name;
                int padding = Math.max(1, width - left.length() - elapsed.length());
                lines.add(region.paint(LiveRegion.DIM, "  " + glyphs().branch() + " ") + glyphs().truncate(name, Math.max(12, width - 12))
                    + " ".repeat(padding) + region.paint(LiveRegion.DIM, elapsed));
                shown++;
            }
        }
        return lines;
    }
}

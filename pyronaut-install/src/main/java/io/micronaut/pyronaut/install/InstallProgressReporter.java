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
package io.micronaut.pyronaut.install;

import java.io.Console;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Renders dependency resolution progress.
 *
 * <p>On a terminal the reporter keeps a live region at the bottom of the
 * output: one spinner row per scope being resolved followed by a byte-level
 * progress row for every in-flight transfer. A background ticker repaints the
 * region at a fixed rate so the spinner and elapsed time keep moving even
 * while Maven is silently negotiating metadata. Finished work collapses into
 * permanent {@code [elapsed] ✓ ...} lines above the region.
 *
 * <p>Without a terminal (CI, pipes) the reporter prints deterministic plain
 * lines and never emits control sequences.
 */
@SuppressWarnings({"checkstyle:NeedBraces", "checkstyle:LeftCurly"})
final class InstallProgressReporter implements AutoCloseable {
    static final int DEFAULT_WIDTH = 80;
    static final int MAX_TRANSFER_ROWS = 6;
    private static final long FRAME_INTERVAL_MILLIS = 80L;
    private static final int MIN_TRANSFER_NAME_WIDTH = 12;
    private static final int BAR_WIDTH = 14;

    private static final String[] SPINNER_UNICODE = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private static final String[] SPINNER_ASCII = {"-", "\\", "|", "/"};

    private static final String RESET = "\033[0m";
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";
    private static final String RED = "\033[31m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String CYAN = "\033[36m";

    private final PrintStream output;
    private final boolean enabled;
    private final boolean interactive;
    private final boolean color;
    private final Glyphs glyphs;
    private final String barColor;
    private final boolean debug;
    private final long startNanos = System.nanoTime();
    private final Object lock = new Object();
    // Insertion order matters: rows are rendered in the order scopes started.
    private final Map<InstallScope, ScopeState> scopes = new LinkedHashMap<>();
    private final Map<String, Transfer> transfers = new LinkedHashMap<>();
    private final Thread ticker;
    private volatile boolean running;
    private int drawnLines;
    private int spinnerFrame;

    InstallProgressReporter(PrintStream output, ProgressMode mode, boolean tty) {
        this(output, mode, tty, false, false);
    }

    InstallProgressReporter(PrintStream output, ProgressMode mode, boolean tty, boolean color, boolean unicode) {
        this.output = output;
        this.enabled = mode != ProgressMode.OFF;
        this.interactive = enabled && tty;
        this.color = interactive && color;
        this.glyphs = unicode ? Glyphs.UNICODE : Glyphs.ASCII;
        this.barColor = supports256Colors() ? "\033[38;5;141m" : "\033[35m";
        this.debug = Boolean.getBoolean("pyronaut.progress.debug");
        if (interactive) {
            running = true;
            ticker = new Thread(this::tick, "pyronaut-progress");
            ticker.setDaemon(true);
            ticker.start();
        } else {
            ticker = null;
        }
    }

    static InstallProgressReporter create(String mode) {
        return create(mode, "auto");
    }

    static InstallProgressReporter create(String mode, String colorMode) {
        ProgressMode progressMode = ProgressMode.fromCliValue(mode);
        DependencyTreeRenderer.ColorMode colors = DependencyTreeRenderer.ColorMode.fromCliValue(colorMode);
        boolean tty = stderrIsTerminal();
        boolean color = colors == DependencyTreeRenderer.ColorMode.ALWAYS
            || (colors == DependencyTreeRenderer.ColorMode.AUTO && tty && System.getenv("NO_COLOR") == null);
        boolean unicode = tty && unicodeSupported();
        PrintStream output = unicode ? new PrintStream(System.err, true, StandardCharsets.UTF_8) : System.err;
        return new InstallProgressReporter(output, progressMode, tty, color, unicode);
    }

    /**
     * Whether the standard streams are attached to a terminal capable of
     * cursor movement.
     */
    static boolean stderrIsTerminal() {
        Console console = System.console();
        if (console == null || !console.isTerminal()) return false;
        String term = System.getenv("TERM");
        return term == null || !term.equalsIgnoreCase("dumb");
    }

    static boolean unicodeSupported() {
        String override = System.getProperty("pyronaut.progress.unicode");
        if (override != null) return Boolean.parseBoolean(override);
        String encoding = System.getProperty("stderr.encoding",
            System.getProperty("native.encoding", Charset.defaultCharset().name()));
        return encoding.toUpperCase(Locale.ROOT).replace("-", "").contains("UTF8");
    }

    private static boolean supports256Colors() {
        String term = System.getenv("TERM");
        return System.getenv("COLORTERM") != null || (term != null && term.contains("256color"));
    }

    void cacheHit() { done("Dependency manifests are up to date (cache hit)"); }

    void cacheBypass() { note("Bypassing dependency cache (--refresh/--no-cache)"); }

    void startScope(InstallScope scope) {
        if (!enabled) return;
        synchronized (lock) {
            // Everything before begin() (BOM imports, graph collection) is
            // descriptor work: the artifact denominator is not yet known.
            ScopeState state = new ScopeState();
            state.collecting = true;
            scopes.put(scope, state);
            if (!interactive) output.println("Resolving " + scope.cliValue() + " dependencies...");
            else render();
        }
    }

    void resetScope(InstallScope scope) {
        if (!enabled) return;
        synchronized (lock) {
            ScopeState state = scopes.computeIfAbsent(scope, ignored -> new ScopeState());
            state.planned.clear();
            state.completedArtifacts.clear();
            state.active = false;
            state.collecting = true;
        }
    }

    void beginScope(InstallScope scope) {
        if (!enabled) return;
        synchronized (lock) {
            ScopeState state = scopes.computeIfAbsent(scope, ignored -> new ScopeState());
            state.active = true;
            state.collecting = false;
        }
    }

    void artifactPlanned(InstallScope scope, String name) {
        if (!enabled) return;
        synchronized (lock) {
            ScopeState state = scopes.computeIfAbsent(scope, ignored -> new ScopeState());
            if (!state.active || state.finished) return;
            debug(scope, "planned", name, state);
            state.planned.add(name);
        }
    }

    void artifactStarted(InstallScope scope, String name) {
        if (!enabled) return;
        synchronized (lock) {
            ScopeState state = scopes.computeIfAbsent(scope, ignored -> new ScopeState());
            if (state.finished) return;
            debug(scope, "transfer-start", name, state);
            transfers.computeIfAbsent(transferKey(scope, name), ignored -> new Transfer(basename(name)));
        }
    }

    void artifactProgressed(InstallScope scope, String name, long transferred, long total) {
        if (!enabled) return;
        synchronized (lock) {
            Transfer transfer = transfers.get(transferKey(scope, name));
            if (transfer == null) return;
            transfer.transferred = Math.max(0, transferred);
            transfer.total = total;
        }
    }

    void artifactTransferFinished(InstallScope scope, String name) {
        if (!enabled) return;
        synchronized (lock) {
            ScopeState state = scopes.computeIfAbsent(scope, ignored -> new ScopeState());
            debug(scope, "transfer-success", name, state);
            transfers.remove(transferKey(scope, name));
        }
    }

    void artifactCompleted(InstallScope scope, String name) {
        if (!enabled) return;
        synchronized (lock) {
            ScopeState state = scopes.computeIfAbsent(scope, ignored -> new ScopeState());
            if (state.finished) return;
            debug(scope, "resolved", name, state);
            if (state.active) state.completedArtifacts.add(name);
            else state.descriptors++;
        }
    }

    void artifactFailed(InstallScope scope, String name) {
        if (!enabled) return;
        synchronized (lock) {
            ScopeState state = scopes.computeIfAbsent(scope, ignored -> new ScopeState());
            if (state.finished) return;
            debug(scope, "failed", name, state);
            state.failed = true;
        }
    }

    void finishScope(InstallScope scope, int artifactCount) {
        if (!enabled) return;
        synchronized (lock) {
            ScopeState state = scopes.computeIfAbsent(scope, ignored -> new ScopeState());
            state.finished = true;
            state.artifactCount = Math.max(artifactCount, state.completedArtifacts.size());
            transfers.keySet().removeIf(key -> key.startsWith(scope.cliValue() + ":"));
            scopes.remove(scope);
            if (interactive) {
                printAboveRegion(stamp(GREEN, glyphs.check,
                    "Resolved " + state.artifactCount + " " + scope.cliValue() + " dependencies", state.elapsed()));
            } else {
                output.println("Resolved " + scope.cliValue() + " dependencies (" + state.artifactCount + " artifacts)");
            }
        }
    }

    void finishScope(InstallScope scope) {
        synchronized (lock) {
            ScopeState state = scopes.get(scope);
            finishScope(scope, state == null ? 0 : state.completedArtifacts.size());
        }
    }

    void failScope(InstallScope scope) {
        if (!enabled) return;
        synchronized (lock) {
            ScopeState state = scopes.computeIfAbsent(scope, ignored -> new ScopeState());
            if (state.finished) return;
            state.failed = true;
            state.finished = true;
            transfers.keySet().removeIf(key -> key.startsWith(scope.cliValue() + ":"));
            String message = "Failed resolving " + scope.cliValue() + " dependencies";
            scopes.remove(scope);
            if (interactive) printAboveRegion(stamp(RED, glyphs.cross, message, state.elapsed()));
            else output.println(message);
        }
    }

    void generatedApplicationSchema(int fragmentCount) {
        done("Generated application schema from runtime classpath (" + fragmentCount + " fragments)");
    }

    void generatedEditorStubs(int packageCount, int symbolCount) {
        done("Generated Python editor stubs (" + packageCount + " packages, " + symbolCount + " symbols)");
    }

    void cachedEditorStubs() { done("Python editor stubs are up to date"); }

    void directSourceSelection(String language, int sourceCount) {
        note("Installing IDE support for " + sourceCount + " direct " + language + " source" + (sourceCount == 1 ? "" : "s") + "...");
    }

    void directSourceDeclarations(int buildDependencies, int runtimeDependencies, int repositories) {
        done("Discovered direct-source declarations (" + buildDependencies + " build, " + runtimeDependencies + " runtime, " + repositories + " repositories)");
    }

    void directSourceDependencies(int artifactCount) { done("Resolved direct-source dependencies (" + artifactCount + " artifacts)"); }

    void directSourceEditorSupport(String language) { done("Generated " + language + " IDE support in .vscode, .idea, and __pyronaut__"); }

    void toolRuntimeReady(Path path) { done("Pyronaut tool runtime ready: " + path); }

    void editorStubsWarnings(int warningCount, Path reportPath) {
        String message = "Python editor stubs generated with " + warningCount + " warning" + (warningCount == 1 ? "" : "s");
        if (reportPath != null) message += " (report: " + reportPath + ")";
        warn(message);
    }

    void warn(String message) {
        if (!enabled) return;
        synchronized (lock) {
            if (interactive) printAboveRegion(stamp(YELLOW, glyphs.warning, message, -1));
            else output.println("WARNING: " + message);
        }
    }

    @Override
    public void close() {
        running = false;
        if (ticker != null) {
            ticker.interrupt();
            try {
                ticker.join(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (lock) {
            if (!interactive) return;
            // Scopes that never reported completion or failure (for example when
            // an exception unwound the resolver) must not linger on screen.
            scopes.clear();
            transfers.clear();
            output.print(clearRegion());
            output.flush();
        }
    }

    private void done(String message) {
        if (!enabled) return;
        synchronized (lock) {
            if (interactive) printAboveRegion(stamp(GREEN, glyphs.check, message, -1));
            else output.println(message);
        }
    }

    private void note(String message) {
        if (!enabled) return;
        synchronized (lock) {
            if (interactive) printAboveRegion(stamp(null, glyphs.bullet, message, -1));
            else output.println(message);
        }
    }

    private void tick() {
        while (running) {
            try {
                Thread.sleep(FRAME_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
            synchronized (lock) {
                if (!running) return;
                spinnerFrame++;
                render();
            }
        }
    }

    /**
     * Formats a permanent status line: {@code [elapsed] ✓ message (took)}.
     */
    private String stamp(String tone, String glyph, String message, long tookNanos) {
        String prefix = "[" + formatDuration(System.nanoTime() - startNanos) + "]";
        StringBuilder line = new StringBuilder();
        line.append(paint(DIM, prefix)).append(' ');
        line.append(paint(tone, glyph)).append(' ');
        line.append(message);
        if (tookNanos >= 0) line.append(' ').append(paint(DIM, "(" + formatDuration(tookNanos) + ")"));
        return line.toString();
    }

    private void printAboveRegion(String line) {
        StringBuilder frame = new StringBuilder(clearRegion());
        frame.append(line).append('\n');
        output.print(frame);
        drawnLines = 0;
        render();
    }

    private String clearRegion() {
        if (drawnLines == 0) return "";
        String sequence = "\033[" + drawnLines + "A\r\033[J";
        drawnLines = 0;
        return sequence;
    }

    private void render() {
        if (!interactive) return;
        List<String> lines = frameLines();
        if (lines.isEmpty() && drawnLines == 0) return;
        StringBuilder frame = new StringBuilder();
        if (drawnLines > 0) frame.append("\033[").append(drawnLines).append('A');
        frame.append('\r');
        for (String line : lines) frame.append("\033[2K").append(line).append('\n');
        frame.append("\033[J");
        drawnLines = lines.size();
        output.print(frame);
        output.flush();
    }

    private List<String> frameLines() {
        List<String> lines = new ArrayList<>();
        int width = terminalWidth() - 1;
        String spinner = glyphs.spinner[spinnerFrame % glyphs.spinner.length];
        for (Map.Entry<InstallScope, ScopeState> entry : scopes.entrySet()) {
            ScopeState state = entry.getValue();
            String label = (state.collecting ? "Collecting " : "Resolving ") + entry.getKey().cliValue() + " dependencies";
            String count = artifactCount(state);
            String elapsed = formatDuration(state.elapsed());
            String left = spinner + " " + label + (count.isEmpty() ? "" : " " + glyphs.separator + " " + count);
            int padding = Math.max(1, width - visibleLength(left) - elapsed.length());
            String line = paint(CYAN, spinner) + " " + paint(BOLD, label)
                + (count.isEmpty() ? "" : " " + paint(DIM, glyphs.separator + " " + count))
                + " ".repeat(padding) + paint(DIM, elapsed);
            lines.add(fit(line, width));
        }
        int shown = 0;
        for (Transfer transfer : transfers.values()) {
            if (shown == MAX_TRANSFER_ROWS) {
                int remaining = transfers.size() - shown;
                lines.add(fit(paint(DIM, "  " + glyphs.branch + " " + glyphs.ellipsis + " " + remaining + " more"), width));
                break;
            }
            lines.add(fit(transferRow(transfer, width), width));
            shown++;
        }
        return lines;
    }

    private static String artifactCount(ScopeState state) {
        if (state.collecting) return state.descriptors == 0 ? "" : state.descriptors + " descriptor" + (state.descriptors == 1 ? "" : "s");
        int resolved = state.completedArtifacts.size();
        int planned = state.planned.size();
        if (resolved == 0 && planned == 0) return "";
        String count = planned >= resolved && planned > 0 ? resolved + "/" + planned : String.valueOf(resolved);
        return count + " artifact" + (planned == 1 && resolved <= 1 ? "" : "s");
    }

    private String transferRow(Transfer transfer, int width) {
        // Size the name column so the bar and byte counts always fit; wider
        // terminals give long names more room.
        int nameWidth = Math.min(60, Math.max(MIN_TRANSFER_NAME_WIDTH, width - 48));
        String name = truncate(transfer.name, nameWidth);
        StringBuilder row = new StringBuilder();
        row.append(paint(DIM, "  " + glyphs.branch + " Fetch ")).append(String.format("%-" + nameWidth + "s", name));
        row.append(' ');
        if (transfer.total > 0) {
            int percent = (int) Math.min(100L, transfer.transferred * 100L / transfer.total);
            row.append(bar(percent)).append(' ');
            row.append(paint(DIM, formatBytes(transfer.transferred) + "/" + formatBytes(transfer.total)
                + " (" + percent + "%)"));
        } else {
            row.append(bar(0)).append(' ');
            row.append(paint(DIM, formatBytes(transfer.transferred)));
        }
        return row.toString();
    }

    private String bar(int percent) {
        int filled = percent * BAR_WIDTH / 100;
        String done = glyphs.barFilled.repeat(filled);
        String todo = glyphs.barEmpty.repeat(BAR_WIDTH - filled);
        return paint(barColor, done) + paint(DIM, todo);
    }

    private String paint(String tone, String text) {
        if (!color || tone == null || text.isEmpty()) return text;
        return tone + text + RESET;
    }

    private static String transferKey(InstallScope scope, String name) {
        return scope.cliValue() + ":" + name;
    }

    private void debug(InstallScope scope, String event, String name, ScopeState state) {
        if (debug) {
            System.err.println("PROGRESS-DEBUG scope=" + scope.cliValue() + " event=" + event
                + " planned=" + state.planned.size() + " completed=" + state.completedArtifacts.size()
                + " transfers=" + transfers.size() + " artifact=" + name);
        }
    }

    static int terminalWidth() {
        String columns = System.getenv("COLUMNS");
        if (columns != null) {
            try {
                int value = Integer.parseInt(columns.trim());
                if (value >= 40) return value;
            } catch (NumberFormatException ignored) {
                // fall through to the default width
            }
        }
        return DEFAULT_WIDTH;
    }

    static String formatDuration(long nanos) {
        double seconds = nanos / 1_000_000_000.0;
        if (seconds < 60) return String.format(Locale.ROOT, "%.1fs", seconds);
        long whole = (long) seconds;
        return String.format(Locale.ROOT, "%dm %02ds", whole / 60, whole % 60);
    }

    static String formatBytes(long bytes) {
        if (bytes < 1000) return bytes + "B";
        double value = bytes / 1000.0;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = 0;
        while (value >= 1000 && unit < units.length - 1) {
            value /= 1000;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f%s", value, units[unit]);
    }

    private static String basename(String name) {
        if (name == null || name.isBlank()) return "";
        String clean = name.replace('', '?').replace('\n', '?').replace('\r', '?');
        int slash = Math.max(clean.lastIndexOf('/'), clean.lastIndexOf('\\'));
        return slash >= 0 ? clean.substring(slash + 1) : clean;
    }

    private String truncate(String value, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (value.length() <= maxWidth) return value;
        String ellipsis = glyphs.ellipsis;
        if (maxWidth <= ellipsis.length()) return value.substring(0, maxWidth);
        int head = (maxWidth - ellipsis.length()) / 2;
        int tail = maxWidth - ellipsis.length() - head;
        return value.substring(0, head) + ellipsis + value.substring(value.length() - tail);
    }

    /**
     * Clamps a possibly colored line to the terminal width so the live region
     * never wraps; a wrapped row breaks the cursor arithmetic used to repaint.
     */
    private static String fit(String line, int width) {
        StringBuilder result = new StringBuilder();
        int visible = 0;
        int index = 0;
        while (index < line.length()) {
            char ch = line.charAt(index);
            if (ch == '\033') {
                int end = line.indexOf('m', index);
                if (end < 0) break;
                result.append(line, index, end + 1);
                index = end + 1;
                continue;
            }
            if (visible < width) {
                result.append(ch);
                visible++;
            }
            index++;
        }
        return result.toString();
    }

    private static int visibleLength(String line) {
        return line.replaceAll("\033\\[[0-9;]*m", "").length();
    }

    private static final class ScopeState {
        final long startedNanos = System.nanoTime();
        final Set<String> planned = new HashSet<>();
        final Set<String> completedArtifacts = new HashSet<>();
        boolean active;
        boolean collecting;
        int descriptors;
        boolean failed;
        boolean finished;
        int artifactCount;

        long elapsed() { return System.nanoTime() - startedNanos; }
    }

    private static final class Transfer {
        final String name;
        long transferred;
        long total = -1;

        Transfer(String name) { this.name = name; }
    }

    private static final class Glyphs {
        static final Glyphs UNICODE = new Glyphs(SPINNER_UNICODE, "█", "░", "✓", "✗", "!", "•", "└", "·", "…");
        static final Glyphs ASCII = new Glyphs(SPINNER_ASCII, "#", "-", "+", "x", "!", "*", "L", "-", "...");

        final String[] spinner;
        final String barFilled;
        final String barEmpty;
        final String check;
        final String cross;
        final String warning;
        final String bullet;
        final String branch;
        final String separator;
        final String ellipsis;

        Glyphs(String[] spinner, String barFilled, String barEmpty, String check, String cross, String warning,
               String bullet, String branch, String separator, String ellipsis) {
            this.spinner = spinner;
            this.barFilled = barFilled;
            this.barEmpty = barEmpty;
            this.check = check;
            this.cross = cross;
            this.warning = warning;
            this.bullet = bullet;
            this.branch = branch;
            this.separator = separator;
            this.ellipsis = ellipsis;
        }
    }

    enum ProgressMode {
        AUTO, ON, OFF;

        static ProgressMode fromCliValue(String value) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Invalid --progress value ''. Expected one of: auto|on|off");
            return switch (value.trim().toLowerCase()) {
                case "auto" -> AUTO;
                case "on" -> ON;
                case "off" -> OFF;
                default -> throw new IllegalArgumentException("Invalid --progress value '" + value + "'. Expected one of: auto|on|off");
            };
        }
    }
}

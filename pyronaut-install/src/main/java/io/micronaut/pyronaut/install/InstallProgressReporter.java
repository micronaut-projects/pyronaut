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

import io.micronaut.pyronaut.config.terminal.Glyphs;
import io.micronaut.pyronaut.config.terminal.LiveRegion;
import io.micronaut.pyronaut.config.terminal.Terminal;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders dependency resolution progress.
 *
 * <p>On a terminal the reporter keeps a live region at the bottom of the
 * output: one spinner row per scope being resolved followed by a byte-level
 * progress row for every in-flight transfer. Finished work collapses into
 * permanent {@code [elapsed] ✓ ...} lines above the region.
 *
 * <p>Without a terminal (CI, pipes) the reporter prints deterministic plain
 * lines and never emits control sequences.
 */
@SuppressWarnings({"checkstyle:NeedBraces", "checkstyle:LeftCurly"})
public final class InstallProgressReporter implements AutoCloseable {
    static final int MAX_TRANSFER_ROWS = 6;
    private static final int MIN_TRANSFER_NAME_WIDTH = 12;
    private static final int BAR_WIDTH = 14;

    private final PrintStream output;
    private final boolean enabled;
    private final boolean interactive;
    private final LiveRegion region;
    private final boolean debug;
    private final Object lock = new Object();
    // Insertion order matters: rows are rendered in the order scopes started.
    private final Map<InstallScope, ScopeState> scopes = new LinkedHashMap<>();
    private final Map<String, Transfer> transfers = new LinkedHashMap<>();
    // Post-resolution work (schema bundling, stub generation) shown as rows too.
    private final Map<String, Long> tasks = new LinkedHashMap<>();

    InstallProgressReporter(PrintStream output, ProgressMode mode, boolean tty) {
        this(output, mode, tty, false, false);
    }

    InstallProgressReporter(PrintStream output, ProgressMode mode, boolean tty, boolean color, boolean unicode) {
        this.output = output;
        this.enabled = mode != ProgressMode.OFF;
        this.interactive = enabled && tty;
        this.region = new LiveRegion(output, interactive, color, unicode, this::frameLines);
        this.debug = Boolean.getBoolean("pyronaut.progress.debug");
    }

    /**
     * @param mode the {@code --progress} mode: {@code auto}, {@code on} or {@code off}
     * @return a reporter drawing on the process's own terminal
     */
    public static InstallProgressReporter create(String mode) {
        return create(mode, "auto");
    }

    /**
     * @param mode the {@code --progress} mode
     * @param colorMode {@code auto}, {@code always} or {@code never}
     * @return a reporter drawing on the process's own terminal
     */
    public static InstallProgressReporter create(String mode, String colorMode) {
        ProgressMode progressMode = ProgressMode.fromCliValue(mode);
        DependencyTreeRenderer.ColorMode.fromCliValue(colorMode);
        boolean tty = Terminal.isInteractive();
        boolean unicode = tty && Terminal.unicodeSupported();
        PrintStream output = unicode ? new PrintStream(System.err, true, StandardCharsets.UTF_8) : System.err;
        InstallProgressReporter reporter = new InstallProgressReporter(output, progressMode, tty, Terminal.colorEnabled(colorMode, tty), unicode);
        reporter.region.captureStandardStreams();
        return reporter;
    }

    void cacheHit() { done("Dependency manifests are up to date (cache hit)"); }

    /**
     * @param scope a scope about to be resolved
     * @return a listener that reports the resolver's events for that scope here
     */
    public DependencyProgressListener listener(InstallScope scope) {
        return new DependencyProgressListener() {
            @Override
            public void artifactPlanned(String name) { InstallProgressReporter.this.artifactPlanned(scope, name); }

            @Override
            public void reset() { resetScope(scope); }

            @Override
            public void begin() { beginScope(scope); }

            @Override
            public void artifactStarted(String name) { InstallProgressReporter.this.artifactStarted(scope, name); }

            @Override
            public void artifactProgressed(String name, long transferred, long total) { InstallProgressReporter.this.artifactProgressed(scope, name, transferred, total); }

            @Override
            public void artifactTransferFinished(String name) { InstallProgressReporter.this.artifactTransferFinished(scope, name); }

            @Override
            public void artifactCompleted(String name) { InstallProgressReporter.this.artifactCompleted(scope, name); }

            @Override
            public void artifactFailed(String name) { InstallProgressReporter.this.artifactFailed(scope, name); }
        };
    }

    void cacheBypass() { note("Bypassing dependency cache (--refresh/--no-cache)"); }

    /**
     * @param scope a scope whose resolution starts now
     */
    public void startScope(InstallScope scope) {
        if (!enabled) return;
        synchronized (lock) {
            // Everything before begin() (BOM imports, graph collection) is
            // descriptor work: the artifact denominator is not yet known.
            ScopeState state = new ScopeState();
            state.collecting = true;
            scopes.put(scope, state);
        }
        if (!interactive) output.println("Resolving " + scope.cliValue() + " dependencies...");
        else region.refresh();
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

    /**
     * @param scope a resolved scope
     * @param artifactCount the number of artifacts it resolved to
     */
    public void finishScope(InstallScope scope, int artifactCount) {
        if (!enabled) return;
        ScopeState state;
        synchronized (lock) {
            state = scopes.computeIfAbsent(scope, ignored -> new ScopeState());
            state.finished = true;
            state.artifactCount = Math.max(artifactCount, state.completedArtifacts.size());
            transfers.keySet().removeIf(key -> key.startsWith(scope.cliValue() + ":"));
            scopes.remove(scope);
        }
        if (interactive) {
            // A scope that finished without ever leaving the collecting phase
            // was served from cache: nothing was resolved, so say so.
            boolean upToDate = state.collecting && state.descriptors == 0 && state.artifactCount == 0;
            region.printAbove(region.stamp(LiveRegion.GREEN, glyphs().check(), upToDate
                ? scope.cliValue() + " dependencies are up to date"
                : "Resolved " + state.artifactCount + " " + scope.cliValue() + " dependencies", state.elapsed()));
        } else {
            output.println("Resolved " + scope.cliValue() + " dependencies (" + state.artifactCount + " artifacts)");
        }
    }

    void finishScope(InstallScope scope) {
        int completed;
        synchronized (lock) {
            ScopeState state = scopes.get(scope);
            completed = state == null ? 0 : state.completedArtifacts.size();
        }
        finishScope(scope, completed);
    }

    /**
     * @param scope a scope whose resolution failed
     */
    public void failScope(InstallScope scope) {
        if (!enabled) return;
        ScopeState state;
        synchronized (lock) {
            state = scopes.computeIfAbsent(scope, ignored -> new ScopeState());
            if (state.finished) return;
            state.failed = true;
            state.finished = true;
            transfers.keySet().removeIf(key -> key.startsWith(scope.cliValue() + ":"));
            scopes.remove(scope);
        }
        String message = "Failed resolving " + scope.cliValue() + " dependencies";
        if (interactive) region.printAbove(region.stamp(LiveRegion.RED, glyphs().cross(), message, state.elapsed()));
        else output.println(message);
    }

    /**
     * Show a spinner row for work that follows dependency resolution until it
     * is reported finished.
     *
     * @param label the row's label
     */
    void startTask(String label) {
        if (!enabled) return;
        synchronized (lock) {
            tasks.put(label, System.nanoTime());
        }
        if (!interactive) output.println(label + "...");
        else region.refresh();
    }

    /**
     * Take down a task's row without printing anything.
     *
     * @param label the row's label
     * @return how long the task ran, or {@code -1} when it was never started
     */
    long endTask(String label) {
        Long started;
        synchronized (lock) {
            started = tasks.remove(label);
        }
        if (interactive) region.refresh();
        return started == null ? -1 : System.nanoTime() - started;
    }

    void generatingApplicationSchema() { startTask("Generating application schema"); }

    void generatedApplicationSchema(int fragmentCount) {
        done("Generated application schema from runtime classpath (" + fragmentCount + " fragments)", endTask("Generating application schema"));
    }

    void generatingEditorStubs() { startTask("Generating Python editor stubs"); }

    void generatedEditorStubs(int packageCount, int symbolCount) {
        done("Generated Python editor stubs (" + packageCount + " packages, " + symbolCount + " symbols)", endTask("Generating Python editor stubs"));
    }

    void cachedEditorStubs() {
        endTask("Generating Python editor stubs");
        done("Python editor stubs are up to date");
    }

    void directSourceSelection(String language, int sourceCount, boolean ideSupport) {
        String sources = sourceCount + " direct " + language + " source" + (sourceCount == 1 ? "" : "s");
        note(ideSupport ? "Installing IDE support for " + sources + "..." : "Resolving dependencies for " + sources + "...");
    }

    void directSourceDeclarations(int buildDependencies, int runtimeDependencies, int repositories) {
        done("Discovered direct-source declarations (" + buildDependencies + " build, " + runtimeDependencies + " runtime, " + repositories + " repositories)");
    }

    void directSourceDependencies(int artifactCount) { done("Resolved direct-source dependencies (" + artifactCount + " artifacts)"); }

    /**
     * Report that a direct source launch found its declared dependencies already resolved.
     *
     * @param declarationCount the number of declared dependencies
     */
    public void directSourceLaunchCacheHit(int declarationCount) {
        done("Declared dependencies are up to date (" + declarationCount + " declared)");
    }

    void directSourceEditorSupport(String language) { done("Generated " + language + " IDE support in .vscode, .idea, and __pyronaut__"); }

    void toolRuntimeReady(Path path) { done("Pyronaut tool runtime ready: " + path); }

    void editorStubsWarnings(int warningCount, Path reportPath) {
        String message = "Python editor stubs generated with " + warningCount + " warning" + (warningCount == 1 ? "" : "s");
        if (reportPath != null) message += " (report: " + reportPath + ")";
        warn(message);
    }

    /**
     * @param message a warning printed above the live rows
     */
    public void warn(String message) {
        if (!enabled) return;
        synchronized (lock) {
            // A warning about a task ends it: nothing further will be reported.
            tasks.clear();
        }
        if (interactive) region.printAbove(region.stamp(LiveRegion.YELLOW, glyphs().warning(), message, -1));
        else output.println("WARNING: " + message);
    }

    /**
     * @return whether the reporter draws a live region
     */
    public boolean interactive() {
        return interactive;
    }

    @Override
    public void close() {
        synchronized (lock) {
            // Scopes that never reported completion or failure (for example when
            // an exception unwound the resolver) must not linger on screen.
            scopes.clear();
            transfers.clear();
            tasks.clear();
        }
        region.close();
    }

    private Glyphs glyphs() { return region.glyphs(); }

    private void done(String message) {
        done(message, -1);
    }

    private void done(String message, long tookNanos) {
        if (!enabled) return;
        if (interactive) region.printAbove(region.stamp(LiveRegion.GREEN, glyphs().check(), message, tookNanos));
        else output.println(message);
    }

    private void note(String message) {
        if (!enabled) return;
        if (interactive) region.printAbove(region.stamp(null, glyphs().bullet(), message, -1));
        else output.println(message);
    }

    private List<String> frameLines(LiveRegion region, int spinnerFrame, int width) {
        List<String> lines = new ArrayList<>();
        synchronized (lock) {
            for (Map.Entry<InstallScope, ScopeState> entry : scopes.entrySet()) {
                ScopeState state = entry.getValue();
                String label = (state.collecting ? "Collecting " : "Resolving ") + entry.getKey().cliValue() + " dependencies";
                lines.add(region.headerRow(spinnerFrame, label, artifactCount(state), state.elapsed(), width));
            }
            for (Map.Entry<String, Long> task : tasks.entrySet()) {
                lines.add(region.headerRow(spinnerFrame, task.getKey(), null, System.nanoTime() - task.getValue(), width));
            }
            int shown = 0;
            for (Transfer transfer : transfers.values()) {
                if (shown == MAX_TRANSFER_ROWS) {
                    int remaining = transfers.size() - shown;
                    lines.add(region.paint(LiveRegion.DIM, "  " + glyphs().branch() + " " + glyphs().ellipsis() + " " + remaining + " more"));
                    break;
                }
                lines.add(transferRow(transfer, width));
                shown++;
            }
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
        String name = glyphs().truncate(transfer.name, nameWidth);
        StringBuilder row = new StringBuilder();
        row.append(region.paint(LiveRegion.DIM, "  " + glyphs().branch() + " Fetch ")).append(String.format("%-" + nameWidth + "s", name));
        row.append(' ');
        if (transfer.total > 0) {
            int percent = (int) Math.min(100L, transfer.transferred * 100L / transfer.total);
            row.append(region.bar(percent, BAR_WIDTH)).append(' ');
            row.append(region.paint(LiveRegion.DIM, Terminal.formatBytes(transfer.transferred) + "/" + Terminal.formatBytes(transfer.total)
                + " (" + percent + "%)"));
        } else {
            row.append(region.bar(0, BAR_WIDTH)).append(' ');
            row.append(region.paint(LiveRegion.DIM, Terminal.formatBytes(transfer.transferred)));
        }
        return row.toString();
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

    private static String basename(String name) {
        if (name == null || name.isBlank()) return "";
        String clean = name.replace('\u001b', '?').replace('\n', '?').replace('\r', '?');
        int slash = Math.max(clean.lastIndexOf('/'), clean.lastIndexOf('\\'));
        return slash >= 0 ? clean.substring(slash + 1) : clean;
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

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

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

@SuppressWarnings({"checkstyle:NeedBraces", "checkstyle:LeftCurly"})
final class InstallProgressReporter implements AutoCloseable {
    private static final int BAR_WIDTH = 13;
    // Keep ANSI-managed rows below the width of a standard terminal. A wrapped
    // row makes the cursor movement used by the multi-scope display unreliable.
    private static final int MAX_RENDERED_LINE_WIDTH = 79;
    private static final char[] UNICODE_BLOCKS = {' ', '▏', '▎', '▍', '▌', '▋', '▊', '▉', '█'};

    private final PrintStream output;
    private final boolean enabled;
    private final boolean interactive;
    private final boolean unicode;
    private final boolean debug;
    private final Object lock = new Object();
    private final Map<InstallScope, State> states = new EnumMap<>(InstallScope.class);

    InstallProgressReporter(PrintStream output, ProgressMode mode, boolean tty) {
        this.output = output;
        this.enabled = mode != ProgressMode.OFF;
        this.interactive = enabled && tty;
        // Keep the default representation byte-safe across terminals and log collectors.
        // Unicode can be enabled explicitly once the output stream is known to be UTF-8 safe.
        this.unicode = Boolean.getBoolean("pyronaut.progress.unicode");
        this.debug = Boolean.getBoolean("pyronaut.progress.debug");
    }

    static InstallProgressReporter create(String mode) {
        PrintStream output = Boolean.getBoolean("pyronaut.progress.unicode")
            ? new PrintStream(System.err, true, StandardCharsets.UTF_8)
            : System.err;
        return new InstallProgressReporter(output, ProgressMode.fromCliValue(mode), System.console() != null);
    }

    void cacheHit() {
        if (enabled) output.println("Dependency manifests are up to date (cache hit)");
    }

    void cacheBypass() {
        if (enabled) output.println("Bypassing dependency cache (--refresh/--no-cache)");
    }

    void startScope(InstallScope scope) {
        if (!enabled) return;
        synchronized (lock) {
            states.put(scope, new State());
            states.get(scope).active = true;
            if (!interactive) output.println("Resolving " + scope.cliValue() + " dependencies...");
            else render();
        }
    }

    void resetScope(InstallScope scope) {
        if (!enabled) return;
        synchronized (lock) {
            State state = states.computeIfAbsent(scope, ignored -> new State());
            state.total = 0;
            state.completed = 0;
            state.activeTransfers = 0;
            state.planned.clear();
            state.completedArtifacts.clear();
            state.current = null;
            state.active = false;
            if (interactive) render();
        }
    }

    void beginScope(InstallScope scope) {
        if (!enabled) return;
        synchronized (lock) {
            State state = states.computeIfAbsent(scope, ignored -> new State());
            state.active = true;
        }
    }

    void artifactPlanned(InstallScope scope, String name) {
        if (!enabled) return;
        synchronized (lock) {
            State state = states.computeIfAbsent(scope, ignored -> new State());
            if (!state.active) return;
            debug(scope, "planned", name, state);
            if (state.finished) return;
            if (state.planned.add(name)) state.total++;
            state.current = basename(name);
            if (interactive) render();
        }
    }

    void artifactStarted(InstallScope scope, String name) {
        if (!enabled) return;
        synchronized (lock) {
            State state = states.computeIfAbsent(scope, ignored -> new State());
            if (!state.active) return;
            debug(scope, "transfer-start", name, state);
            if (state.finished) return;
            state.current = basename(name);
            state.activeTransfers++;
            // Some Maven sessions do not emit an artifactResolving event for
            // every transferred file. Keep the denominator at least as large
            // as the work observed by the transfer listener.
            state.total = Math.max(state.total, state.completed + state.activeTransfers);
            if (interactive) render();
        }
    }

    void artifactTransferFinished(InstallScope scope, String name) {
        if (!enabled) return;
        synchronized (lock) {
            State state = states.computeIfAbsent(scope, ignored -> new State());
            if (!state.active) return;
            debug(scope, "transfer-success", name, state);
            if (state.finished) return;
            state.activeTransfers = Math.max(0, state.activeTransfers - 1);
            state.current = basename(name);
            if (interactive) render();
        }
    }

    void artifactCompleted(InstallScope scope, String name) {
        if (!enabled) return;
        synchronized (lock) {
            State state = states.computeIfAbsent(scope, ignored -> new State());
            if (!state.active) return;
            debug(scope, "resolved", name, state);
            if (state.finished) return;
            if (state.completedArtifacts.add(name)) state.completed++;
            state.current = basename(name);
            if (interactive) render();
        }
    }

    void artifactFailed(InstallScope scope, String name) {
        if (!enabled) return;
        synchronized (lock) {
            State state = states.computeIfAbsent(scope, ignored -> new State());
            if (state.finished) return;
            state.failed = true;
            state.current = basename(name);
            if (interactive) render();
        }
    }

    void finishScope(InstallScope scope, int artifactCount) {
        if (!enabled) return;
        synchronized (lock) {
            State state = states.computeIfAbsent(scope, ignored -> new State());
            state.total = Math.max(state.total, artifactCount);
            state.completed = Math.max(state.completed, state.total);
            state.finished = true;
            state.activeTransfers = 0;
            if (interactive) render();
            state.artifactCount = artifactCount;
            if (!interactive) output.println("Resolved " + scope.cliValue() + " dependencies (" + artifactCount + " artifacts)");
        }
    }

    void finishScope(InstallScope scope) {
        synchronized (lock) {
            State state = states.get(scope);
            finishScope(scope, state == null ? 0 : state.completed);
        }
    }

    void failScope(InstallScope scope) {
        if (!enabled) return;
        synchronized (lock) {
            State state = states.computeIfAbsent(scope, ignored -> new State());
            if (!state.active) return;
            state.failed = true;
            state.finished = true;
            state.activeTransfers = 0;
            if (interactive) render();
            else output.println("Failed resolving " + scope.cliValue() + " dependencies");
        }
    }

    void generatedApplicationSchema(int fragmentCount) {
        if (enabled) printlnAfterProgress("Generated application schema from runtime classpath (" + fragmentCount + " fragments)");
    }

    void generatedEditorStubs(int packageCount, int symbolCount) {
        if (enabled) printlnAfterProgress("Generated Python editor stubs (" + packageCount + " packages, " + symbolCount + " symbols)");
    }

    void cachedEditorStubs() { if (enabled) printlnAfterProgress("Python editor stubs are up to date"); }

    void directSourceSelection(String language, int sourceCount) {
        if (enabled) printlnAfterProgress("Installing IDE support for " + sourceCount + " direct " + language + " source" + (sourceCount == 1 ? "" : "s") + "...");
    }

    void directSourceDeclarations(int buildDependencies, int runtimeDependencies, int repositories) {
        if (enabled) printlnAfterProgress("Discovered direct-source declarations (" + buildDependencies + " build, " + runtimeDependencies + " runtime, " + repositories + " repositories)");
    }

    void directSourceDependencies(int artifactCount) { if (enabled) printlnAfterProgress("Resolved direct-source dependencies (" + artifactCount + " artifacts)"); }

    void directSourceEditorSupport(String language) { if (enabled) printlnAfterProgress("Generated " + language + " IDE support in .vscode, .idea, and __pyronaut__"); }

    void toolRuntimeReady(Path path) { if (enabled) printlnAfterProgress("Pyronaut tool runtime ready: " + path); }

    void editorStubsWarnings(int warningCount, Path reportPath) {
        if (!enabled) return;
        String message = "Python editor stubs generated with " + warningCount + " warning" + (warningCount == 1 ? "" : "s");
        if (reportPath != null) message += " (report: " + reportPath + ")";
        printlnAfterProgress(message);
    }

    void warn(String message) { if (enabled) printlnAfterProgress("WARNING: " + message); }

    @Override
    public void close() {
        synchronized (lock) {
            if (interactive && !states.isEmpty()) {
                render();
                output.print("\033[" + states.size() + "B\r");
                boolean failed = false;
                for (InstallScope scope : InstallScope.values()) {
                    State state = states.get(scope);
                    if (state != null && state.finished && !state.failed) {
                        output.println("Resolved " + scope.cliValue() + " dependencies (" + state.artifactCount + " artifacts)");
                    } else if (state != null && state.failed) {
                        failed = true;
                    }
                }
                if (failed) {
                    // Leave a clean line for the caller's diagnostic. Without this
                    // newline, a Python wrapper error overwrites the final progress row.
                    output.print("\033[2K");
                    output.println();
                }
                output.flush();
            }
        }
    }

    private void render() {
        output.print("\r\033[2K");
        List<InstallScope> scopes = List.of(InstallScope.values());
        boolean first = true;
        for (InstallScope scope : scopes) {
            State state = states.get(scope);
            if (state == null) continue;
            if (!first) output.print("\n");
            output.print("\r\033[2K");
            output.print(format(scope, state));
            first = false;
        }
        output.print("\033[" + Math.max(0, states.size() - 1) + "A\r");
        output.flush();
    }

    private void printlnAfterProgress(String message) {
        synchronized (lock) {
            if (interactive && !states.isEmpty()) {
                output.print("\033[" + states.size() + "B\r");
            }
            output.println(message);
        if (interactive && !states.isEmpty()) {
            // println advanced past the message line, so include that line when
            // returning to the first progress row.
            output.print("\033[" + (states.size() + 1) + "A\r");
            render();
        }
            output.flush();
        }
    }

    private String format(InstallScope scope, State state) {
        int total = Math.max(state.total, state.completed);
        int percent = total == 0 ? (state.finished && !state.failed ? 100 : 0) : Math.min(100, state.completed * 100 / total);
        boolean indeterminate = !state.finished && total > 0 && state.completed >= total;
        if (indeterminate) percent = state.animationFrame++ % 2 == 0 ? 99 : 98;
        String bar = unicode ? unicodeBar(percent) : asciiBar(percent);
        String prefix = String.format("%-21s %s %3d%%", scope.cliValue(), bar, percent);
        String status = indeterminate ? " (resolving)" : "";
        if (state.failed) {
            return prefix + " FAILED";
        }
        int available = MAX_RENDERED_LINE_WIDTH - prefix.length() - status.length() - 1;
        String current = state.current == null ? "" : truncate(state.current, Math.max(0, available));
        return prefix + " " + current + status;
    }

    private void debug(InstallScope scope, String event, String name, State state) {
        if (debug) {
            System.err.println("PROGRESS-DEBUG scope=" + scope.cliValue() + " event=" + event
                + " total=" + state.total + " completed=" + state.completed
                + " active=" + state.activeTransfers + " artifact=" + name);
        }
    }

    private static String asciiBar(int percent) {
        int completed = percent * BAR_WIDTH / 100;
        return "[" + "#".repeat(completed) + ".".repeat(BAR_WIDTH - completed) + "]";
    }

    private static String unicodeBar(int percent) {
        int eighths = percent * BAR_WIDTH * 8 / 100;
        StringBuilder result = new StringBuilder("|");
        for (int i = 0; i < BAR_WIDTH; i++) {
            result.append(UNICODE_BLOCKS[Math.min(8, Math.max(0, eighths - i * 8))]);
        }
        return result.append('|').toString();
    }

    private static String basename(String name) {
        if (name == null || name.isBlank()) return null;
        String clean = name.replace('\u001b', '?').replace('\n', '?').replace('\r', '?');
        int slash = Math.max(clean.lastIndexOf('/'), clean.lastIndexOf('\\'));
        return slash >= 0 ? clean.substring(slash + 1) : clean;
    }

    private static String truncate(String value, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (value.length() <= maxWidth) return value;
        if (maxWidth <= 3) return value.substring(0, maxWidth);
        int head = (maxWidth - 3) / 2;
        int tail = maxWidth - 3 - head;
        return value.substring(0, head) + "..." + value.substring(value.length() - tail);
    }

    private static final class State {
        int total;
        int completed;
        String current;
        boolean failed;
        boolean finished;
        int activeTransfers;
        int artifactCount;
        final Set<String> planned = new HashSet<>();
        final Set<String> completedArtifacts = new HashSet<>();
        int animationFrame;
        boolean active;
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

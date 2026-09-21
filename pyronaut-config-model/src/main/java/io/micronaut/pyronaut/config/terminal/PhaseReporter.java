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
package io.micronaut.pyronaut.config.terminal;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports a tool's work as a sequence of phases.
 *
 * <p>On a terminal every running phase is a spinner row carrying an optional
 * detail and its elapsed time; a finished phase collapses into a permanent
 * {@code [elapsed] ✓ message (took)} line. Without a terminal the reporter
 * prints deterministic plain lines and never emits control sequences.
 *
 * <p>Standard output and error are routed through the live region while the
 * reporter is open, so output from libraries and child processes lands above
 * the running rows instead of tearing them.
 */
public final class PhaseReporter implements AutoCloseable {
    private final PrintStream output;
    private final boolean interactive;
    private final LiveRegion region;
    private final Object lock = new Object();
    private final Map<Phase, PhaseState> phases = new LinkedHashMap<>();

    PhaseReporter(PrintStream output, TerminalInfo terminal) {
        this.output = output;
        this.interactive = terminal.interactive();
        this.region = new LiveRegion(output, terminal, this::frameLines);
    }

    /**
     * Create a reporter on the standard error stream using the terminal's
     * capabilities.
     *
     * @return the reporter
     */
    public static PhaseReporter create() {
        return create(false);
    }

    /**
     * Create a reporter on the standard error stream.
     *
     * @param plain force plain line output even on a terminal
     * @return the reporter
     */
    public static PhaseReporter create(boolean plain) {
        TerminalInfo terminal = plain ? TerminalInfo.plain() : TerminalInfo.detect("auto");
        PrintStream output = terminal.unicode() ? new PrintStream(System.err, true, StandardCharsets.UTF_8) : System.err;
        PhaseReporter reporter = new PhaseReporter(output, terminal);
        reporter.region.captureStandardStreams();
        return reporter;
    }

    /**
     * @return whether the reporter owns a live terminal region
     */
    public boolean interactive() {
        return interactive;
    }

    /**
     * Start a phase.
     *
     * @param label the row's label, for example {@code Compiling sources}
     * @return the phase, to be finished with {@link Phase#done(String)} or {@link Phase#fail(String)}
     */
    public Phase start(String label) {
        Phase phase = new Phase(label);
        synchronized (lock) {
            phases.put(phase, new PhaseState());
        }
        if (interactive) {
            region.refresh();
        } else {
            output.println(label + "...");
        }
        return phase;
    }

    /**
     * Print a permanent success line that belongs to no phase.
     *
     * @param message the message
     */
    public void done(String message) {
        if (interactive) {
            region.printAbove(region.stamp(LiveRegion.GREEN, region.glyphs().check(), message, -1));
        } else {
            output.println(message);
        }
    }

    /**
     * Print a permanent neutral line.
     *
     * @param message the message
     */
    public void note(String message) {
        if (interactive) {
            region.printAbove(region.stamp(null, region.glyphs().bullet(), message, -1));
        } else {
            output.println(message);
        }
    }

    /**
     * Print a permanent warning line.
     *
     * @param message the message
     */
    public void warn(String message) {
        if (interactive) {
            region.printAbove(region.stamp(LiveRegion.YELLOW, region.glyphs().warning(), message, -1));
        } else {
            output.println("WARNING: " + message);
        }
    }

    /**
     * Print a permanent error line.
     *
     * @param message the message
     */
    public void error(String message) {
        if (interactive) {
            region.printAbove(region.stamp(LiveRegion.RED, region.glyphs().cross(), message, -1));
        } else {
            output.println(message);
        }
    }

    /**
     * Print a dimmed continuation line beneath the previous permanent line.
     *
     * @param message the message
     */
    public void hint(String message) {
        if (interactive) {
            region.printAbove(region.paint(LiveRegion.DIM, "  " + region.glyphs().branch() + " " + message));
        } else {
            output.println("  " + message);
        }
    }

    /**
     * Print a line verbatim above the live rows.
     *
     * @param line the line
     */
    public void print(String line) {
        if (interactive) {
            region.printAbove(line);
        } else {
            output.println(line);
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            phases.clear();
        }
        region.close();
    }

    private void finish(Phase phase, String tone, String glyph, String message) {
        PhaseState state;
        synchronized (lock) {
            state = phases.remove(phase);
        }
        long took = state == null ? -1 : state.elapsed();
        if (interactive) {
            region.printAbove(region.stamp(tone, glyph, message, took));
        } else {
            output.println(took < 0 ? message : message + " (" + Terminal.formatDuration(took) + ")");
        }
    }

    private void detail(Phase phase, String detail) {
        synchronized (lock) {
            PhaseState state = phases.get(phase);
            if (state != null) {
                state.detail = detail;
            }
        }
        if (interactive) {
            region.refresh();
        }
    }

    private List<String> frameLines(LiveRegion region, int spinnerFrame, int width) {
        List<String> lines = new ArrayList<>();
        synchronized (lock) {
            for (Map.Entry<Phase, PhaseState> entry : phases.entrySet()) {
                lines.add(region.headerRow(spinnerFrame, entry.getKey().label, entry.getValue().detail, entry.getValue().elapsed(), width));
            }
        }
        return lines;
    }

    /**
     * A running phase.
     */
    public final class Phase {
        private final String label;

        private Phase(String label) {
            this.label = label;
        }

        /**
         * Replace the dim detail shown after the label.
         *
         * @param detail the detail, or {@code null} for none
         */
        public void detail(String detail) {
            PhaseReporter.this.detail(this, detail);
        }

        /**
         * Finish the phase successfully.
         *
         * @param message the permanent line's message
         */
        public void done(String message) {
            finish(this, LiveRegion.GREEN, region.glyphs().check(), message);
        }

        /**
         * Finish the phase with a failure.
         *
         * @param message the permanent line's message
         */
        public void fail(String message) {
            finish(this, LiveRegion.RED, region.glyphs().cross(), message);
        }
    }

    private static final class PhaseState {
        final long startedNanos = System.nanoTime();
        String detail;

        long elapsed() {
            return System.nanoTime() - startedNanos;
        }
    }
}

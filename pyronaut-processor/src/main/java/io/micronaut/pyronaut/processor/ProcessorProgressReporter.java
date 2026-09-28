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
package io.micronaut.pyronaut.processor;

import io.micronaut.pyronaut.config.terminal.LiveRegion;
import io.micronaut.pyronaut.config.terminal.Terminal;
import io.micronaut.pyronaut.config.terminal.TerminalInfo;
import io.micronaut.python.compiler.PyronautCompiler;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports processing progress per source pass. On a terminal each active pass
 * is a spinner row with its elapsed time and finished passes collapse into
 * permanent {@code [elapsed] ✓ ...} lines; elsewhere plain lines are printed.
 */
final class ProcessorProgressReporter implements AutoCloseable {
    private final PrintStream output;
    private final boolean enabled;
    private final boolean interactive;
    private final LiveRegion region;
    private final Object lock = new Object();
    private final Map<String, Pass> passes = new LinkedHashMap<>();
    private IncrementalPlan incrementalPlan;

    ProcessorProgressReporter(PrintStream output, ProgressMode mode, boolean tty) {
        this(output, mode, tty, false, false);
    }

    ProcessorProgressReporter(PrintStream output, ProgressMode mode, boolean tty, boolean color, boolean unicode) {
        this(output, mode, new TerminalInfo(tty, color, unicode, Terminal.width(), Terminal.epochMillis()));
    }

    ProcessorProgressReporter(PrintStream output, ProgressMode mode, TerminalInfo terminal) {
        this.output = output;
        this.enabled = mode != ProgressMode.OFF;
        this.interactive = enabled && (mode == ProgressMode.ON || terminal.interactive());
        this.region = new LiveRegion(output, new TerminalInfo(interactive, terminal.color(), terminal.unicode(),
            terminal.width(), terminal.epochMillis()), this::frameLines);
    }

    static ProcessorProgressReporter create(String mode) {
        return create(mode, null);
    }

    /**
     * @param mode the {@code --progress} mode
     * @param terminalSpec the client's terminal capabilities when rendering
     *                     through the compiler daemon, or {@code null} to detect
     * @return the reporter
     */
    static ProcessorProgressReporter create(String mode, String terminalSpec) {
        ProgressMode progressMode = ProgressMode.fromCliValue(mode);
        TerminalInfo terminal = terminalSpec == null ? TerminalInfo.detect("auto") : TerminalInfo.parse(terminalSpec);
        PrintStream output = terminal.unicode() ? new PrintStream(System.err, true, StandardCharsets.UTF_8) : System.err;
        ProcessorProgressReporter reporter = new ProcessorProgressReporter(output, progressMode, terminal);
        reporter.region.captureStandardStreams();
        return reporter;
    }

    void startPass(String passName, boolean incremental) {
        startPass(passName, -1L, incremental);
    }

    void startPass(String passName, long sourceCount, boolean incremental) {
        if (!enabled) {
            return;
        }
        incrementalPlan = null;
        String action = incremental ? "Checking" : "Processing";
        String message = sourceCount < 0
            ? action + " " + passName + " sources"
            : action + " " + passName + " sources (" + sourceCount + " files)";
        synchronized (lock) {
            passes.put(passName, new Pass(message));
        }
        if (!interactive) {
            output.println(message + "...");
        } else {
            region.refresh();
        }
    }

    void finishPass(String passName, long sourceCount) {
        if (!enabled) {
            return;
        }
        String message;
        if (incrementalPlan != null && incrementalPlan.passName().equals(passName)) {
            PyronautCompiler.IncrementalCompilationPlan plan = incrementalPlan.plan();
            if (plan.upToDate()) {
                message = "Skipped " + passName + " sources (" + sourceCount
                    + " files checked, incremental state up to date)";
            } else if (plan.fullRebuild()) {
                message = "Processed " + passName + " sources (" + sourceCount + " files, full rebuild)";
            } else {
                message = "Processed " + passName + " sources (" + plan.sources().size()
                    + " of " + sourceCount + " files recompiled incrementally)";
            }
            incrementalPlan = null;
        } else {
            message = "Processed " + passName + " sources (" + sourceCount + " files)";
        }
        done(passName, message);
    }

    void incrementalPlan(String passName,
                         Path projectRoot,
                         long sourceCount,
                         PyronautCompiler.IncrementalCompilationPlan plan) {
        if (!enabled) {
            return;
        }
        incrementalPlan = new IncrementalPlan(passName, plan);
        if (plan.upToDate()) {
            return;
        }
        if (plan.fullRebuild()) {
            note("Full rebuild selected for " + passName + " sources (" + sourceCount + " files)");
            return;
        }
        note("Incrementally compiling " + passName + " sources (" + plan.sources().size() + " of " + sourceCount + " files):");
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        for (Path source : plan.sources()) {
            Path normalizedSource = source.toAbsolutePath().normalize();
            Path display = normalizedSource.startsWith(normalizedRoot)
                ? normalizedRoot.relativize(normalizedSource)
                : normalizedSource;
            if (interactive) {
                region.printAbove(region.paint(LiveRegion.DIM, "    " + region.glyphs().branch() + " " + display));
            } else {
                output.println("  - " + display);
            }
        }
    }

    void cacheHit(String passName, long sourceCount) {
        done(passName, "Skipped " + passName + " sources (" + sourceCount + " files, cache hit)");
    }

    void cacheBypass(String passName, long sourceCount) {
        note("Processing " + passName + " sources (" + sourceCount + " files, cache bypass)");
    }

    void noSources(String passName) {
        done(passName, "Skipped " + passName + " sources (0 files, no processable sources)");
    }

    /**
     * Says where the static compilation report of a pass was rendered, as a link on a terminal.
     *
     * @param html The page
     */
    void staticCompilationReport(Path html) {
        if (!enabled) {
            return;
        }
        if (interactive) {
            region.printAbove(region.paint(LiveRegion.DIM, "  Static compilation report: " + Terminal.link(displayPath(html), html.toUri().toString())));
        } else {
            output.println("Static compilation report: " + html);
        }
    }

    private static String displayPath(Path path) {
        try {
            Path cwd = Path.of("").toAbsolutePath();
            Path absolute = path.toAbsolutePath().normalize();
            return absolute.startsWith(cwd) ? cwd.relativize(absolute).toString() : absolute.toString();
        } catch (RuntimeException e) {
            return path.toString();
        }
    }

    void complete(String mainStatus, String testStatus) {
        if (!enabled) {
            return;
        }
        String message = "Processing completed (main: " + mainStatus + ", test: " + testStatus + ")";
        if (interactive) {
            region.printAbove(region.stamp(LiveRegion.GREEN, region.glyphs().check(), message, -1));
        } else {
            output.println(message);
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            passes.clear();
        }
        region.close();
    }

    private void done(String passName, String message) {
        if (!enabled) {
            return;
        }
        Pass pass;
        synchronized (lock) {
            pass = passes.remove(passName);
        }
        if (interactive) {
            region.printAbove(region.stamp(LiveRegion.GREEN, region.glyphs().check(), message,
                pass == null ? -1 : System.nanoTime() - pass.startedNanos));
        } else {
            output.println(message);
        }
    }

    private void note(String message) {
        if (!enabled) {
            return;
        }
        if (interactive) {
            region.printAbove(region.stamp(null, region.glyphs().bullet(), message, -1));
        } else {
            output.println(message);
        }
    }

    private List<String> frameLines(LiveRegion region, int spinnerFrame, int width) {
        List<String> lines = new ArrayList<>();
        synchronized (lock) {
            for (Pass pass : passes.values()) {
                lines.add(region.headerRow(spinnerFrame, pass.message, null, System.nanoTime() - pass.startedNanos, width));
            }
        }
        return lines;
    }

    private static final class Pass {
        private final String message;
        private final long startedNanos = System.nanoTime();

        private Pass(String message) {
            this.message = message;
        }
    }

    enum ProgressMode {
        AUTO,
        ON,
        OFF;

        static ProgressMode fromCliValue(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Invalid --progress value ''. Expected one of: auto|on|off");
            }
            return switch (value.trim().toLowerCase()) {
                case "auto" -> AUTO;
                case "on" -> ON;
                case "off" -> OFF;
                default -> throw new IllegalArgumentException("Invalid --progress value '" + value + "'. Expected one of: auto|on|off");
            };
        }
    }

    private record IncrementalPlan(String passName,
                                   PyronautCompiler.IncrementalCompilationPlan plan) {
    }
}
